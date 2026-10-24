package cc.nkbr.lanzouplus;

import android.app.AlertDialog;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.view.Gravity;
import android.view.View;
import android.widget.*;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import org.json.*;

/**
 * Announcements published beside the static market catalog.
 *
 * <p>The payload is read from {@code announcements.json} in the public GitHub market repository
 * and kept in one local cache file together with the read version of every announcement id. An
 * announcement counts as unread while the stored read version differs from its current
 * {@code updatedAt}, so an edited announcement becomes unread again without hashing anything.
 * The whole history stays in the payload and stays visible after it has been read.
 */
final class MarketAnnouncements {
  static final int SCHEMA=1;
  /** One bounded read of the public announcement document. */
  static final int PAYLOAD_LIMIT=1024*1024;
  static final String CACHE_FILE="announcements-cache.json";
  private static final AtomicBoolean LAUNCH_CHECKED=new AtomicBoolean();

  /** One published announcement; {@code body} is the complete text shown in the detail view. */
  static final class Notice {
    final String id,title,body,publishedAt,updatedAt;
    Notice(String id,String title,String body,String publishedAt,String updatedAt){
      this.id=id;this.title=title;this.body=body;this.publishedAt=publishedAt;this.updatedAt=updatedAt;
    }
    static Notice parse(JSONObject value)throws IOException{
      String id=value.optString("id","").trim(),title=value.optString("title","").trim();
      String body=value.optString("body","");
      String publishedAt=value.optString("publishedAt","").trim();
      String updatedAt=value.optString("updatedAt","").trim();
      if(id.isEmpty()||title.isEmpty()||body.trim().isEmpty()||publishedAt.isEmpty())
        throw new IOException("公告条目缺少 id、标题、正文或发布时间");
      return new Notice(id,title,body,publishedAt,updatedAt.isEmpty()?publishedAt:updatedAt);
    }
    /** Text version used for unread comparison; no digest is involved. */
    String readVersion(){return updatedAt;}
    JSONObject json()throws JSONException{
      return new JSONObject().put("id",id).put("title",title).put("body",body)
          .put("publishedAt",publishedAt).put("updatedAt",updatedAt);
    }
    String displayDate(){return publishedAt.length()>=10?publishedAt.substring(0,10):publishedAt;}
    String preview(){
      String flat=body.replaceAll("\\s+"," ").trim();
      return flat.codePointCount(0,flat.length())<=88?flat:
          flat.substring(0,flat.offsetByCodePoints(0,88))+"…";
    }
  }

  static final class Payload {
    final long version;
    final List<Notice> notices;
    Payload(long version,List<Notice> notices){this.version=version;this.notices=notices;}
  }

  private final File directory;
  private final MarketRepositoryClient.Transport transport;
  /** Guards the in-memory snapshot only; network, parsing and disk writes stay outside it. */
  private final Object lock=new Object();
  private final Object loadLock=new Object();
  /** Serializes cache writes and gives preload a deterministic boundary for queued writes. */
  private static final ExecutorService SAVE_IO=Executors.newSingleThreadExecutor(task->{
    Thread thread=new Thread(task,"market-announcements-cache");thread.setDaemon(true);return thread;
  });
  /** id → updatedAt that was on screen when the user opened it. */
  private final Map<String,String> readVersions=new LinkedHashMap<>();
  private List<Notice> notices=Collections.emptyList();
  private long version,fetchedAt;
  private boolean loaded;

  MarketAnnouncements(File directory){
    this(directory,MarketRepositoryClient::httpGet);
  }
  MarketAnnouncements(File directory,MarketRepositoryClient.Transport transport){
    this.directory=directory;this.transport=transport;
  }

  static String remoteUrl(){return MarketRepositoryClient.MARKET_STATIC_ORIGIN+MarketRepositoryClient.STATIC_ANNOUNCEMENTS_FILE;}

  static Payload parsePayload(JSONObject value)throws IOException{
    if(value.optInt("schema",0)!=SCHEMA)throw new IOException("公告格式版本不受支持");
    JSONArray rows=value.optJSONArray("announcements");
    if(rows==null)throw new IOException("公告清单缺少 announcements");
    LinkedHashMap<String,Notice> unique=new LinkedHashMap<>();
    for(int i=0;i<rows.length();i++){
      JSONObject row=rows.optJSONObject(i);
      if(row==null)continue;
      Notice notice=Notice.parse(row);
      Notice previous=unique.get(notice.id);
      if(previous==null||notice.updatedAt.compareTo(previous.updatedAt)>0)unique.put(notice.id,notice);
    }
    List<Notice> values=new ArrayList<>(unique.values());
    values.sort((left,right)->{
      int byDate=right.publishedAt.compareTo(left.publishedAt);
      return byDate!=0?byDate:right.id.compareTo(left.id);
    });
    return new Payload(value.optLong("version",0),values);
  }

  /** Snapshot read used by the page and the badges; never touches the disk or the network. */
  List<Notice> cached(){
    synchronized(lock){return new ArrayList<>(notices);}
  }
  long version(){synchronized(lock){return version;}}
  long fetchedAt(){synchronized(lock){return fetchedAt;}}
  boolean isRead(Notice notice){
    if(notice==null)return false;
    synchronized(lock){return notice.readVersion().equals(readVersions.get(notice.id));}
  }
  int unreadCount(){
    synchronized(lock){
      int count=0;
      for(Notice notice:notices)if(!notice.readVersion().equals(readVersions.get(notice.id)))count++;
      return count;
    }
  }

  /**
   * Reads the local cache once. Runs on a background thread so the first page render never waits
   * on the file; later callers return immediately.
   */
  void preload(){
    synchronized(loadLock){
      synchronized(lock){if(loaded)return;}
      if(!awaitQueuedWrites())return;
      List<Notice> diskNotices=Collections.emptyList();
      Map<String,String> diskRead=new LinkedHashMap<>();
      long diskVersion=0,diskFetched=0;
      File file=cacheFile();
      if(file.isFile()){
        try(InputStream input=new FileInputStream(file)){
          JSONObject value=new JSONObject(new String(
              MarketRepositoryClient.bytes(input,PAYLOAD_LIMIT+256*1024),StandardCharsets.UTF_8));
          if(value.optInt("schema",0)==SCHEMA){
            Payload payload=parsePayload(value);
            diskNotices=payload.notices;diskVersion=payload.version;
            diskFetched=value.optLong("fetchedAt",0);
            JSONObject read=value.optJSONObject("read");
            if(read!=null){
              for(Iterator<String> keys=read.keys();keys.hasNext();){
                String id=keys.next();String stored=read.optString(id,"");
                if(!id.isEmpty()&&!stored.isEmpty())diskRead.put(id,stored);
              }
            }
          }
        }catch(Exception ignored){} // A damaged cache is replaced by the next refresh.
      }
      synchronized(lock){
        if(loaded)return;
        loaded=true;notices=diskNotices;version=diskVersion;fetchedAt=diskFetched;
        readVersions.putAll(diskRead);
      }
    }
  }

  /**
   * Reads the public announcement document and replaces the cache. Read versions are kept only
   * for announcements that still exist, so a removed and later re-published id starts unread.
   */
  Payload refresh(boolean force)throws Exception{
    preload();
    String address=remoteUrl();
    /* The transport call is deliberately outside every lock: a slow GitHub route must never
       stall the header badge, the cached list or a second reader. */
    byte[] body=transport.get(force?MarketRepositoryClient.freshUrl(address):address,PAYLOAD_LIMIT);
    JSONObject value=new JSONObject(new String(body,StandardCharsets.UTF_8));
    Payload fresh=parsePayload(value);
    synchronized(lock){
      Set<String> active=new HashSet<>();
      for(Notice notice:fresh.notices)active.add(notice.id);
      readVersions.keySet().retainAll(active);
      notices=fresh.notices;version=fresh.version;fetchedAt=System.currentTimeMillis();
    }
    saveAndWait();
    return fresh;
  }

  /** Marks one announcement read at the version that was just displayed. */
  boolean markRead(String id){
    synchronized(lock){
      for(Notice notice:notices){
        if(!notice.id.equals(id))continue;
        String current=readVersions.get(id);
        if(notice.readVersion().equals(current))return false;
        readVersions.put(id,notice.readVersion());
        break;
      }
      if(!hasNoticeLocked(id))return false;
    }
    SAVE_IO.execute(this::saveNow);
    return true;
  }
  private boolean hasNoticeLocked(String id){
    for(Notice notice:notices)if(notice.id.equals(id))return true;
    return false;
  }

  /** One network check per process launch; later activities resume the local snapshot and badge. */
  void checkOnLaunch(MainActivity owner){
    if(LAUNCH_CHECKED.getAndSet(true)){
      owner.io.execute(()->{
        preload();
        owner.runOnUiThread(()->{
          if(owner.isFinishing())return;
          owner.applyAnnouncementBadge(unreadCount());
        });
      });
      return;
    }
    checkInBackground(owner,false);
  }

  /**
   * Fetches in the host's IO pool, then updates the cached list and every bound badge. A failed
   * read keeps the previous cache: announcements are optional and must not disturb the page.
   */
  void checkInBackground(MainActivity owner,boolean force){
    final int generation=++checkGeneration;
    owner.io.execute(()->{
      String failure="";
      try{refresh(force);}catch(Exception error){failure=owner.friendlyError(error);}
      final String message=failure;
      owner.runOnUiThread(()->{
        if(generation!=checkGeneration||owner.isFinishing())return;
        owner.applyAnnouncementBadge(unreadCount());
        if(onChanged!=null)onChanged.run();
        if(!message.isEmpty()&&force)owner.showNotice("公告暂时无法更新："+message,true);
      });
    });
  }
  private volatile int checkGeneration;
  private volatile Runnable onChanged;

  void observe(Runnable listener){onChanged=listener;}
  void clearObserver(){onChanged=null;}

  private File cacheFile(){return new File(directory,CACHE_FILE);}
  /** Waits for writes that were queued before this call; callers stay off the UI thread. */
  private static boolean awaitQueuedWrites(){
    try{SAVE_IO.submit(()->{}).get();return true;}
    catch(InterruptedException interrupted){
      Thread.currentThread().interrupt();
      return false;
    }catch(ExecutionException impossible){
      throw new IllegalStateException(impossible);
    }
  }
  /** A refresh returns only after its cache write task completed. */
  private void saveAndWait()throws InterruptedException{
    try{SAVE_IO.submit(()->{saveNow();}).get();}
    catch(InterruptedException interrupted){
      Thread.currentThread().interrupt();
      throw interrupted;
    }catch(ExecutionException impossible){
      throw new IllegalStateException(impossible);
    }
  }
  /** Snapshots when the serial writer runs, then writes outside the state lock. */
  private void saveNow(){
    List<Notice> snapshot;Map<String,String> readSnapshot;long snapshotVersion,snapshotFetched;
    synchronized(lock){
      snapshot=notices;snapshotVersion=version;snapshotFetched=fetchedAt;
      readSnapshot=new LinkedHashMap<>(readVersions);
    }
    saveNow(snapshot,snapshotVersion,readSnapshot,snapshotFetched);
  }
  private void saveNow(List<Notice> snapshot,long snapshotVersion,Map<String,String> readSnapshot,long snapshotFetched){
    try{
      if(!directory.isDirectory()&&!directory.mkdirs())throw new IOException("无法创建公告缓存目录");
      JSONArray rows=new JSONArray();
      for(Notice notice:snapshot)rows.put(notice.json());
      JSONObject read=new JSONObject();
      for(Map.Entry<String,String> entry:readSnapshot.entrySet())read.put(entry.getKey(),entry.getValue());
      JSONObject saved=new JSONObject().put("schema",SCHEMA).put("version",snapshotVersion)
          .put("fetchedAt",snapshotFetched).put("announcements",rows).put("read",read);
      File file=cacheFile(),temp=new File(directory,CACHE_FILE+".tmp");
      try(FileOutputStream output=new FileOutputStream(temp)){
        output.write(saved.toString().getBytes(StandardCharsets.UTF_8));
      }
      if(file.isFile()&&!file.delete())throw new IOException("无法替换公告缓存");
      if(!temp.renameTo(file))throw new IOException("无法保存公告缓存");
    }catch(Exception ignored){}
  }

  /**
   * Announcement entry for a page header: the icon plus a red unread count in the top right
   * corner. The badge view is owned by the host so page rebuilds rebind without accumulating
   * detached views.
   */
  static View entry(final MainActivity owner){
    final MarketAnnouncements store=owner.marketAnnouncements();
    FrameLayout box=new FrameLayout(owner);
    ImageButton button=owner.iconButton(R.drawable.ic_announcement,"公告");
    button.setOnClickListener(v->store.show(owner));
    box.addView(button,new FrameLayout.LayoutParams(owner.dp(44),owner.dp(44)));
    final TextView badge=owner.text("",9,Color.WHITE);
    badge.setGravity(Gravity.CENTER);
    badge.setTypeface(android.graphics.Typeface.DEFAULT,android.graphics.Typeface.BOLD);
    badge.setIncludeFontPadding(false);
    GradientDrawable background=new GradientDrawable();
    background.setColor(owner.darkMode?Color.rgb(255,110,103):Color.rgb(211,47,42));
    background.setCornerRadius(owner.dp(9));
    badge.setBackground(background);
    badge.setMinWidth(owner.dp(16));
    badge.setPadding(owner.dp(4),0,owner.dp(4),0);
    FrameLayout.LayoutParams badgeParams=new FrameLayout.LayoutParams(-2,owner.dp(16),Gravity.TOP|Gravity.END);
    badgeParams.setMargins(0,owner.dp(1),owner.dp(1),0);
    box.addView(badge,badgeParams);
    owner.announcementBadge=badge;
    owner.applyAnnouncementBadge(store.unreadCount());
    return box;
  }

  /** Announcement history with unread state; opening one shows the complete text. */
  void show(final MainActivity owner){
    LinearLayout panel=new LinearLayout(owner);
    panel.setOrientation(LinearLayout.VERTICAL);
    panel.setPadding(owner.dp(6),owner.dp(2),owner.dp(6),0);
    final TextView status=owner.text("",12,owner.MUTED);
    status.setPadding(owner.dp(10),owner.dp(4),owner.dp(10),owner.dp(6));
    Button check=owner.toolbarTextButton("检查更新");
    check.setContentDescription("检查公告更新");
    LinearLayout toolbar=new LinearLayout(owner);
    toolbar.setGravity(Gravity.CENTER_VERTICAL);
    toolbar.addView(status,new LinearLayout.LayoutParams(0,owner.dp(44),1));
    toolbar.addView(check,new LinearLayout.LayoutParams(owner.dp(86),owner.dp(44)));
    panel.addView(toolbar,new LinearLayout.LayoutParams(-1,owner.dp(44)));
    final LinearLayout list=new LinearLayout(owner);
    list.setOrientation(LinearLayout.VERTICAL);
    list.setPadding(owner.dp(2),0,owner.dp(2),owner.dp(8));
    ScrollView scroll=new ScrollView(owner);
    scroll.setFillViewport(true);
    scroll.addView(list,new ScrollView.LayoutParams(-1,-2));
    int height=Math.min(owner.dp(460),Math.max(owner.dp(160),owner.safeContentHeight()-owner.dp(210)));
    panel.addView(scroll,new LinearLayout.LayoutParams(-1,height));
    final AlertDialog dialog=new AlertDialog.Builder(owner).setTitle("公告")
        .setView(panel).setNegativeButton("关闭",null).create();
    final Runnable[] render={null};
    render[0]=()->{
      List<Notice> items=cached();
      int unread=unreadCount();
      status.setText("未读 "+unread+" 条 · 共 "+items.size()+" 条公告");
      list.removeAllViews();
      if(items.isEmpty()){
        TextView empty=owner.text("暂无公告",13,owner.MUTED);
        empty.setGravity(Gravity.CENTER);
        empty.setPadding(0,owner.dp(30),0,0);
        list.addView(empty,new LinearLayout.LayoutParams(-1,owner.dp(120)));
        return;
      }
      for(Notice notice:items){
        LinearLayout.LayoutParams params=new LinearLayout.LayoutParams(-1,-2);
        params.setMargins(0,0,0,owner.dp(8));
        list.addView(row(owner,notice,render[0]),params);
      }
    };
    check.setOnClickListener(v->{
      check.setEnabled(false);check.setText("检查中…");
      owner.io.execute(()->{
        String failure="";
        try{refresh(true);}catch(Exception error){failure=owner.friendlyError(error);}
        final String message=failure;
        owner.runOnUiThread(()->{
          if(owner.isFinishing())return;
          check.setEnabled(true);check.setText("检查更新");
          if(!message.isEmpty()){owner.showNotice("公告暂时无法更新："+message,true);return;}
          render[0].run();owner.applyAnnouncementBadge(unreadCount());
        });
      });
    });
    dialog.setOnDismissListener(ignored->clearObserver());
    observe(()->{if(dialog.isShowing())render[0].run();});
    render[0].run();
    owner.showRounded(dialog);
    owner.io.execute(()->{
      preload();
      owner.runOnUiThread(()->{if(dialog.isShowing())render[0].run();});
    });
  }

  private View row(final MainActivity owner,final Notice notice,final Runnable reRender){
    boolean read=isRead(notice);
    LinearLayout card=new LinearLayout(owner);
    card.setOrientation(LinearLayout.VERTICAL);
    card.setPadding(owner.dp(12),owner.dp(10),owner.dp(12),owner.dp(10));
    GradientDrawable background=new GradientDrawable();
    background.setColor(owner.SURFACE);background.setCornerRadius(owner.dp(10));
    background.setStroke(owner.dp(1),owner.DIV);
    card.setBackground(background);
    card.setClickable(true);card.setFocusable(true);
    LinearLayout heading=new LinearLayout(owner);
    heading.setGravity(Gravity.CENTER_VERTICAL);
    TextView title=owner.text(notice.title,15,owner.TEXT);
    title.setTypeface(android.graphics.Typeface.DEFAULT,android.graphics.Typeface.BOLD);
    title.setMaxLines(2);
    heading.addView(title,new LinearLayout.LayoutParams(0,-2,1));
    TextView mark=owner.text(read?"已读":"未读",10,read?owner.MUTED:owner.darkMode?Color.rgb(255,138,128):Color.rgb(197,34,31));
    mark.setPadding(owner.dp(6),0,owner.dp(6),0);
    heading.addView(mark,new LinearLayout.LayoutParams(-2,owner.dp(20)));
    card.addView(heading,new LinearLayout.LayoutParams(-1,-2));
    LinearLayout meta=new LinearLayout(owner);
    meta.setGravity(Gravity.CENTER_VERTICAL);
    TextView date=owner.text(notice.displayDate()+(notice.updatedAt.equals(notice.publishedAt)?"":" · 已更新"),11,owner.MUTED);
    meta.addView(date,new LinearLayout.LayoutParams(0,owner.dp(20),1));
    card.addView(meta,new LinearLayout.LayoutParams(-1,owner.dp(20)));
    TextView preview=owner.text(notice.preview(),12,owner.MUTED);
    preview.setMaxLines(2);
    preview.setEllipsize(android.text.TextUtils.TruncateAt.END);
    preview.setPadding(0,owner.dp(2),0,0);
    card.addView(preview,new LinearLayout.LayoutParams(-1,-2));
    card.setContentDescription(notice.title+"，"+(read?"已读":"未读")+"，"+notice.displayDate()+"，点击查看完整公告");
    card.setOnClickListener(v->read(owner,notice,()->{
      reRender.run();
      owner.applyAnnouncementBadge(unreadCount());
    }));
    return card;
  }

  /** Opening the complete text is what marks an announcement read. */
  private void read(final MainActivity owner,final Notice notice,final Runnable afterRead){
    LinearLayout panel=new LinearLayout(owner);
    panel.setOrientation(LinearLayout.VERTICAL);
    panel.setPadding(owner.dp(18),owner.dp(4),owner.dp(18),owner.dp(4));
    TextView meta=owner.text(notice.displayDate()+(notice.updatedAt.equals(notice.publishedAt)?"":" · 更新于 "+notice.updatedAt),11,owner.MUTED);
    panel.addView(meta,new LinearLayout.LayoutParams(-1,owner.dp(24)));
    TextView body=owner.text(notice.body,14,owner.TEXT);
    body.setGravity(Gravity.TOP|Gravity.START);
    body.setTextIsSelectable(true);
    body.setLineSpacing(owner.dp(4),1f);
    body.setPadding(0,owner.dp(4),0,owner.dp(12));
    ScrollView scroll=new ScrollView(owner);
    scroll.setFillViewport(true);
    scroll.addView(body,new ScrollView.LayoutParams(-1,-2));
    int height=Math.min(owner.dp(480),Math.max(owner.dp(140),owner.safeContentHeight()-owner.dp(230)));
    panel.addView(scroll,new LinearLayout.LayoutParams(-1,height));
    AlertDialog dialog=new AlertDialog.Builder(owner).setTitle(notice.title)
        .setView(panel).setNegativeButton("关闭",null).create();
    dialog.setOnDismissListener(ignored->{
      if(afterRead!=null)afterRead.run();
    });
    owner.showRounded(dialog);
    if(markRead(notice.id)&&afterRead!=null)owner.applyAnnouncementBadge(unreadCount());
  }
}
