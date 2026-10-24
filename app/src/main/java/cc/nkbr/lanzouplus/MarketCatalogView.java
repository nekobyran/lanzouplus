package cc.nkbr.lanzouplus;

import android.app.AlertDialog;
import android.content.res.Configuration;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.*;
import java.io.IOException;
import java.util.*;

/** The market is a full destination. GridView only binds visible repository cards. */
final class MarketCatalogView {
  private final MainActivity owner;
  private final GridView grid;
  private final TextView summary;
  private final List<Card> cards=new ArrayList<>();
  private final List<Card> visible=new ArrayList<>();
  private String query="";
  private final CatalogAdapter adapter=new CatalogAdapter();
  private final int session;
  private final boolean force;

  private static final class Card {
    final String url;
    MarketRepositoryClient.CatalogEntry entry;
    boolean loading;
    MarketRepositoryClient.Document document;
    MarketRepositoryClient.GithubInfo github;
    String error="";
    Card(MarketRepositoryClient.CatalogEntry entry){this.entry=entry;url=entry.githubUrl;}
  }
  private static final class Holder {
    ImageView icon,avatar;
    TextView name,publisher,stars,description;
  }

  MarketCatalogView(MainActivity owner,GridView grid,TextView summary,boolean force){
    this.force=force;
    this.owner=owner;this.grid=grid;this.summary=summary;session=owner.navigationSession;
    grid.setNumColumns(columns());
    grid.setHorizontalSpacing(owner.dp(8));grid.setVerticalSpacing(owner.dp(8));
    grid.setStretchMode(GridView.STRETCH_COLUMN_WIDTH);
    grid.setClipToPadding(false);
    grid.setAdapter(adapter);
    grid.setOnItemClickListener((parent,view,position,id)->showDetails(visible.get(position)));
  }
  void render(List<MarketRepositoryClient.CatalogEntry> entries){
    if(session!=owner.navigationSession||owner.pageKind!=5)return;
    Map<String,Card> before=new HashMap<>();for(Card card:cards)before.put(card.url,card);
    cards.clear();
    for(MarketRepositoryClient.CatalogEntry entry:entries){
      Card card=before.get(entry.githubUrl);
      if(card==null)card=new Card(entry);else card.entry=entry;
      cards.add(card);
    }
    filter(query);
  }
  void filter(String value){
    query=value.trim().toLowerCase(Locale.ROOT);visible.clear();
    List<Card> matches=new ArrayList<>(cards);
    Map<Card,Integer> ranks=new IdentityHashMap<>();
    for(Card card:cards)ranks.put(card,searchRank(card.entry,query));
    matches.removeIf(card->ranks.get(card)<0);
    matches.sort(Comparator.comparingInt(ranks::get));visible.addAll(matches);
    summary.setText(cards.isEmpty()?"市场暂无仓库，可上传 GitHub 仓库链接":query.isEmpty()?"资源仓库 · "+cards.size()+" 个":"本地匹配 · "+visible.size()+" / "+cards.size()+" 个");
    adapter.notifyDataSetChanged();
  }
  static int searchRank(MarketRepositoryClient.CatalogEntry entry,String query){
    if(query.isEmpty())return 0;
    int best=Integer.MAX_VALUE;
    String[] fields={entry.displayTitle(),entry.repositoryName,entry.description,entry.displayPublisher(),entry.githubUrl,entry.version};
    for(String field:fields){int rank=LanzouCore.matchRank(field,query,true);if(rank>=0)best=Math.min(best,rank);}
    return best==Integer.MAX_VALUE?-1:best;
  }
  int columns(){return owner.getResources().getConfiguration().orientation==Configuration.ORIENTATION_LANDSCAPE?4:2;}
  void columnsChanged(){grid.setNumColumns(columns());}

  private void request(Card card){
    if(card.loading||card.github!=null&&card.document!=null)return;
    card.loading=true;
    owner.sourceMetadataIo.execute(()->{
      MarketRepositoryClient.GithubInfo github=null;
      MarketRepositoryClient.Document document=null;
      String error="";
      try{github=owner.core.marketRepositories().cachedGithubInfo(card.url);}catch(Exception ignored){}
      if(github==null||force)try{github=owner.core.marketRepositories().githubInfo(card.url,force);}catch(Exception failure){error=owner.friendlyError(failure);}
      try{document=owner.core.marketRepositories().cached(card.url);}catch(Exception ignored){}
      if(document==null||force||document.metadata.icon.isEmpty()){
        try{document=owner.core.marketRepositories().preview(card.url,force);}catch(Exception failure){if(error.isEmpty())error=owner.friendlyError(failure);}
      }
      MarketRepositoryClient.GithubInfo found=github;
      MarketRepositoryClient.Document preview=document;
      String warning=error;
      owner.runOnUiThread(()->{
        card.loading=false;card.github=found;card.document=preview;card.error=warning;
        if(session==owner.navigationSession&&owner.pageKind==5&&cards.contains(card))adapter.notifyDataSetChanged();
      });
    });
  }
  private final class CatalogAdapter extends BaseAdapter {
    @Override public int getCount(){return visible.size();}
    @Override public Object getItem(int position){return visible.get(position);}
    @Override public long getItemId(int position){return position;}
    @Override public View getView(int position,View convertView,ViewGroup parent){
      Holder holder;
      LinearLayout row;
      if(convertView instanceof LinearLayout&&convertView.getTag() instanceof Holder){
        row=(LinearLayout)convertView;holder=(Holder)row.getTag();
      }else{
        row=new LinearLayout(owner);row.setOrientation(LinearLayout.VERTICAL);
        row.setPadding(owner.dp(12),owner.dp(10),owner.dp(12),owner.dp(9));
        GradientDrawable background=new GradientDrawable();
        background.setColor(owner.SURFACE);background.setCornerRadius(owner.dp(8));
        background.setStroke(owner.dp(1),owner.DIV);row.setBackground(background);
        holder=new Holder();
        LinearLayout top=new LinearLayout(owner);top.setGravity(Gravity.CENTER_VERTICAL);
        holder.icon=new ImageView(owner);holder.icon.setScaleType(ImageView.ScaleType.CENTER_CROP);
        top.addView(holder.icon,new LinearLayout.LayoutParams(owner.dp(40),owner.dp(40)));
        holder.name=owner.text("",14,owner.TEXT);holder.name.setTypeface(android.graphics.Typeface.DEFAULT,android.graphics.Typeface.BOLD);
        holder.name.setMaxLines(2);holder.name.setEllipsize(android.text.TextUtils.TruncateAt.END);
        holder.name.setMinHeight(owner.dp(42));
        holder.name.setPadding(owner.dp(8),0,0,0);
        top.addView(holder.name,new LinearLayout.LayoutParams(0,-2,1));top.setMinimumHeight(owner.dp(45));
        row.addView(top,new LinearLayout.LayoutParams(-1,-2));
        LinearLayout publisher=new LinearLayout(owner);publisher.setGravity(Gravity.CENTER_VERTICAL);
        holder.avatar=new ImageView(owner);holder.avatar.setScaleType(ImageView.ScaleType.CENTER_CROP);
        publisher.addView(holder.avatar,new LinearLayout.LayoutParams(owner.dp(21),owner.dp(21)));
        holder.publisher=owner.text("",11,owner.MUTED);holder.publisher.setSingleLine(true);
        holder.publisher.setEllipsize(android.text.TextUtils.TruncateAt.END);holder.publisher.setPadding(owner.dp(6),0,0,0);
        publisher.addView(holder.publisher,new LinearLayout.LayoutParams(0,owner.dp(26),1));
        holder.stars=owner.text("",11,owner.PRIMARY);holder.stars.setGravity(Gravity.END|Gravity.CENTER_VERTICAL);
        publisher.addView(holder.stars,new LinearLayout.LayoutParams(-2,owner.dp(26)));
        row.addView(publisher,new LinearLayout.LayoutParams(-1,owner.dp(28)));
        holder.description=owner.text("",12,owner.MUTED);
        holder.description.setMaxLines(3);holder.description.setEllipsize(android.text.TextUtils.TruncateAt.END);
        holder.description.setGravity(Gravity.TOP|Gravity.START);
        holder.description.setMinHeight(owner.dp(59));row.addView(holder.description,new LinearLayout.LayoutParams(-1,-2));
        row.setTag(holder);
      }
      Card card=visible.get(position);
      holder.name.setText(card.entry.displayTitle());
      String publisher=card.entry.displayPublisher();
      if(publisher.isEmpty()&&card.github!=null)publisher=card.github.owner;
      holder.publisher.setText(publisher);
      holder.stars.setText(card.github==null||card.github.stars<0?"☆ —":"★ "+card.github.stars);
      String description=card.entry.description;
      if(description.isEmpty())description=card.error.isEmpty()?"暂无简介":"图标信息暂时无法读取";
      holder.description.setText(description);
      holder.icon.setTag(null);holder.avatar.setTag(null);holder.icon.setImageResource(R.drawable.ic_folder);
      holder.avatar.setImageResource(R.drawable.ic_globe);
      holder.avatar.setColorFilter(owner.MUTED);
      String icon=card.document==null?"":card.document.sourceIconUrl();
      if(!icon.isEmpty())owner.loadImage(icon,holder.icon);
      String avatar=card.document!=null&&!card.document.metadata.publisherAvatar.isEmpty()?card.document.metadata.assetUrlUnchecked(card.document.rawRoot,card.document.metadata.publisherAvatar):card.github==null?"":card.github.avatar;
      if(avatar.isEmpty()&&card.document!=null)avatar=card.document.ownerAvatar;
      if(!avatar.isEmpty())owner.loadProfileImage(avatar,holder.avatar);
      row.setContentDescription(holder.name.getText()+"，发布者 "+publisher+"，"+holder.stars.getText()+"，"+description);
      if(!card.loading&&(card.github==null||card.document==null)&&card.error.isEmpty())request(card);
      return row;
    }
  }

  private void showDetails(Card card){
    LinearLayout panel=new LinearLayout(owner);
    panel.setOrientation(LinearLayout.VERTICAL);
    panel.setPadding(owner.dp(8),owner.dp(4),owner.dp(8),0);
    TextView status=owner.text("正在检查 GitHub 仓库版本…",12,owner.MUTED);
    status.setPadding(owner.dp(12),0,owner.dp(12),owner.dp(8));
    Button starButton=owner.toolbarTextButton("Star");
    starButton.setTextSize(12);
    starButton.setContentDescription("Star GitHub 仓库");
    Button refreshButton=owner.toolbarTextButton("刷新");
    refreshButton.setTextSize(12);
    refreshButton.setContentDescription("刷新仓库版本与 README");
    LinearLayout statusRow=new LinearLayout(owner);statusRow.setGravity(Gravity.CENTER_VERTICAL);
    statusRow.addView(status,new LinearLayout.LayoutParams(0,-2,1));
    statusRow.addView(refreshButton,new LinearLayout.LayoutParams(owner.dp(60),owner.dp(44)));
    statusRow.addView(starButton,new LinearLayout.LayoutParams(owner.dp(128),owner.dp(44)));
    panel.addView(statusRow,new LinearLayout.LayoutParams(-1,owner.dp(48)));
    FrameLayout frame=new FrameLayout(owner);
    ProgressBar progress=new ProgressBar(owner);
    FrameLayout.LayoutParams progressParams=new FrameLayout.LayoutParams(owner.dp(40),owner.dp(40),Gravity.CENTER);
    frame.addView(progress,progressParams);
    int height=Math.min(owner.dp(480),Math.max(owner.dp(96),owner.safeContentHeight()-owner.dp(190)));
    panel.addView(frame,new LinearLayout.LayoutParams(-1,height));
    String name=card.entry.displayTitle();
    AlertDialog dialog=new AlertDialog.Builder(owner).setTitle(name)
        .setView(panel).setNeutralButton("关闭",null)
        .setNegativeButton("删除",null).setPositiveButton("下载",null).create();
    final MarketRepositoryClient.Document[] document={null};
    final Models.Source[] installed={null};
    final Button[] download={null},delete={null};
    final boolean[] busy={false};
    final boolean[] starBusy={false};
    final boolean[] reading={false};
    final Boolean[] starred={null};
    final long[] stars={card.github==null?-1:card.github.stars};
    final android.webkit.WebView[] web={null};
    dialog.setOnDismissListener(d->{if(web[0]!=null){web[0].stopLoading();web[0].destroy();web[0]=null;}});
    final Runnable[] updateButtons={null};
    updateStarButton(starButton,starred[0],stars[0],starBusy[0]);
    updateButtons[0]=()->{
      if(download[0]==null||!dialog.isShowing())return;
      boolean has=installed[0]!=null;
      boolean ahead=has&&document[0]!=null&&newer(document[0],installed[0]);
      download[0].setText(busy[0]?"处理中…":!has?"下载":ahead?"更新":"已下载");
      download[0].setEnabled(!busy[0]&&document[0]!=null&&(!has||ahead));
      delete[0].setVisibility(has?View.VISIBLE:View.GONE);
      delete[0].setEnabled(!busy[0]);
    };
    dialog.setOnShowListener(ignored->{
      download[0]=dialog.getButton(AlertDialog.BUTTON_POSITIVE);
      delete[0]=dialog.getButton(AlertDialog.BUTTON_NEGATIVE);
      delete[0].setTextColor(owner.darkMode?Color.rgb(255,180,171):Color.rgb(179,38,30));
      updateButtons[0].run();
      download[0].setOnClickListener(v->{
        if(busy[0]||document[0]==null)return;
        busy[0]=true;download[0].setText(installed[0]==null?"正在下载…":"正在更新…");updateButtons[0].run();
        Models.Source before=installed[0];
        owner.io.execute(()->{
          try{
            if(before==null)owner.core.addUserSource(card.url,"");
            else owner.core.repositorySources(before,true);
            Models.Source updated=findInstalled(card.url);
            owner.runOnUiThread(()->{
              installed[0]=updated;busy[0]=false;updateButtons[0].run();
              owner.sourceDataRevision++;owner.showNotice(before==null?"仓库已下载":"仓库已更新",false);
            });
          }catch(Exception error){owner.runOnUiThread(()->{busy[0]=false;updateButtons[0].run();owner.showNotice(owner.friendlyError(error),true);});}
        });
      });
      delete[0].setOnClickListener(v->{
        if(installed[0]==null||busy[0])return;
        AlertDialog confirm=new AlertDialog.Builder(owner).setTitle("删除已下载仓库？")
            .setMessage("将从本地列表移除 "+name+" 及其订阅入口，市场中的仓库不会被删除。")
            .setNegativeButton("取消",null).setPositiveButton("删除",(d,w)->{
              Models.Source target=installed[0];busy[0]=true;updateButtons[0].run();
              owner.io.execute(()->{
                try{
                  owner.core.removeSources(Collections.singleton(LanzouCore.sourceId(target)));
                  owner.runOnUiThread(()->{
                    installed[0]=null;busy[0]=false;owner.sourceDataRevision++;
                    updateButtons[0].run();owner.showNotice("已删除本地仓库",false);
                  });
                }catch(Exception error){owner.runOnUiThread(()->{busy[0]=false;updateButtons[0].run();owner.showNotice(owner.friendlyError(error),true);});}
              });
            }).create();
        owner.showRounded(confirm);
        confirm.getButton(AlertDialog.BUTTON_POSITIVE).setTextColor(owner.darkMode?Color.rgb(255,180,171):Color.rgb(179,38,30));
      });
      starButton.setOnClickListener(v->{
        if(starBusy[0])return;
        GithubAccountClient account=GithubAccountClient.production(owner);
        starBusy[0]=true;updateStarButton(starButton,starred[0],stars[0],true);
        owner.io.execute(()->{
          try{
            if(!account.hasUsableSession()){
              owner.runOnUiThread(()->{
                starBusy[0]=false;updateStarButton(starButton,starred[0],stars[0],false);
                if(!dialog.isShowing())return;
                owner.showNotice("请先登录 GitHub，再为仓库点亮 Star",false);
                GithubAccountUi.signIn(owner,()->{
                  owner.io.execute(()->{
                    try{
                      boolean known=account.starred(card.url);
                      MarketRepositoryClient.GithubInfo refreshed=owner.core.marketRepositories().githubInfo(card.url,true);
                      owner.runOnUiThread(()->{
                        if(!dialog.isShowing())return;
                        starred[0]=known;stars[0]=refreshed.stars;
                        updateStarButton(starButton,starred[0],stars[0],false);
                        starButton.setContentDescription("Star GitHub 仓库");
                      });
                    }catch(Exception failure){
                      owner.runOnUiThread(()->{
                        if(!dialog.isShowing())return;
                        updateStarButton(starButton,null,stars[0],false);
                        owner.showNotice("登录成功，但暂时无法读取 Star 状态",true);
                      });
                    }
                  });
                });
              });
              return;
            }
            boolean next=starred[0]==null||!starred[0];
            account.setRepositoryStar(card.url,next);
            MarketRepositoryClient.GithubInfo refreshed=owner.core.marketRepositories().githubInfo(card.url,true);
            owner.runOnUiThread(()->{
              if(!dialog.isShowing())return;
              starred[0]=next;stars[0]=refreshed.stars;starBusy[0]=false;
              updateStarButton(starButton,starred[0],stars[0],false);
              owner.showNotice(next?"已 Star 此 GitHub 仓库":"已取消 Star",false);
            });
          }catch(Exception error){
            owner.runOnUiThread(()->{
              if(!dialog.isShowing())return;
              starBusy[0]=false;updateStarButton(starButton,starred[0],stars[0],false);
              owner.showNotice(error.getMessage()==null?"GitHub Star 操作失败":error.getMessage(),true);
            });
          }
        });
      });
    });
    refreshButton.setOnClickListener(v->loadDetail(card,dialog,status,frame,web,document,installed,
        starButton,refreshButton,starred,stars,reading,updateButtons,true));
    owner.showRounded(dialog);
    loadDetail(card,dialog,status,frame,web,document,installed,starButton,refreshButton,
        starred,stars,reading,updateButtons,force);
  }

  private void loadDetail(Card card,AlertDialog dialog,TextView status,FrameLayout frame,
      android.webkit.WebView[] web,MarketRepositoryClient.Document[] document,
      Models.Source[] installed,Button starButton,Button refreshButton,Boolean[] starred,
      long[] stars,boolean[] reading,Runnable[] updateButtons,boolean force){
    if(reading[0])return;
    reading[0]=true;refreshButton.setEnabled(false);
    status.setText(force?"正在刷新 GitHub 仓库版本…":"正在检查 GitHub 仓库版本…");
    owner.io.execute(()->{
      MarketRepositoryClient.Document checked=null,loaded=null;
      MarketRepositoryClient.GithubInfo identity=null;
      Models.Source current=null;
      Boolean knownStar=null;boolean knownSignedIn=false;String failure="";
      try{
        checked=owner.core.marketRepositories().checkVersion(card.url);
      }catch(Exception error){
        failure=owner.friendlyError(error);
      }
      try{current=findInstalled(card.url);}catch(Exception ignored){}
      MarketRepositoryClient.Document checkedDocument=checked;
      Models.Source installedNow=current;
      String versionError=failure;
      /* The version decides the update button; README, GitHub identity and Star state load later. */
      owner.runOnUiThread(()->{
        if(!dialog.isShowing())return;
        if(checkedDocument!=null){
          card.document=checkedDocument;card.error="";document[0]=checkedDocument;installed[0]=installedNow;
          status.setText(checkedDocument.sourcePublisher()+" · "+checkedDocument.metadata.version);
        }else{
          document[0]=null;status.setText("GitHub 仓库版本读取失败");
        }
        updateButtons[0].run();
      });
      if(checked!=null)try{
        loaded=owner.core.marketRepositories().readme(checked,force);
      }catch(Exception error){
        if(failure.isEmpty())failure=owner.friendlyError(error);
      }
      identity=force?null:card.github;
      if(identity==null)try{identity=owner.core.marketRepositories().githubInfo(card.url,force);}catch(Exception ignored){}
      try{
        GithubAccountClient account=GithubAccountClient.production(owner);
        GithubAccountClient.Profile profile=account.cachedProfile();
        knownSignedIn=profile!=null&&account.hasUsableSession();
        if(knownSignedIn)knownStar=account.starred(card.url);
      }catch(IOException ignored){}
      MarketRepositoryClient.Document result=loaded;
      MarketRepositoryClient.GithubInfo github=identity;
      Boolean starState=knownStar;
      boolean signedIn=knownSignedIn;
      String error=failure;
      owner.runOnUiThread(()->{
        if(!dialog.isShowing())return;
        reading[0]=false;refreshButton.setEnabled(true);
        if(checkedDocument!=null){
          if(web[0]!=null){web[0].stopLoading();web[0].destroy();web[0]=null;}
          frame.removeAllViews();
          if(result!=null){
            web[0]=MarketReadmeView.create(owner,result.readme,result.rawRoot,result.githubUrl);
            frame.addView(web[0],new FrameLayout.LayoutParams(-1,-1));
          }else{
            TextView failed=owner.text("无法读取 README：\n"+error,13,owner.MUTED);
            failed.setPadding(owner.dp(16),owner.dp(24),owner.dp(16),0);
            frame.addView(failed);
          }
          if(github!=null)stars[0]=github.stars;
          starred[0]=starState;
          starButton.setContentDescription(signedIn?"Star GitHub 仓库":"登录 GitHub 后 Star 仓库");
          updateStarButton(starButton,starred[0],stars[0],false);
        }else{
          if(web[0]!=null){web[0].stopLoading();web[0].destroy();web[0]=null;}
          frame.removeAllViews();
          TextView failed=owner.text("无法读取 GitHub 仓库版本：\n"+versionError,13,owner.MUTED);
          failed.setPadding(owner.dp(16),owner.dp(24),owner.dp(16),0);
          frame.addView(failed);
        }
        updateButtons[0].run();
      });
    });
  }
  private Models.Source findInstalled(String url)throws Exception{
    for(Models.Source source:owner.core.sources())if(source.kind==Models.SOURCE_REPOSITORY&&url.equals(source.url))return source;
    return null;
  }
  /** GitHub {@code lzpmarket.json} decides updates; a catalog row version is display text only. */
  static boolean newer(MarketRepositoryClient.Document document,Models.Source source){
    try{
      if(source.repositoryVersion.isEmpty()||!MarketRepository.isVersionMode(source.repositoryVersionMode))return true;
      return !document.metadata.versionMode.equals(source.repositoryVersionMode)||
          MarketRepository.compareVersions(document.metadata.versionMode,document.metadata.version,source.repositoryVersion)>0;
    }catch(Exception ignored){return false;}
  }

  private static void updateStarButton(
      Button button, Boolean starred, long stars, boolean busy) {
    String count = stars < 0 ? "" : " · " + stars;
    if (busy) {
      button.setText("处理中…");
      button.setEnabled(false);
    } else if (starred == null) {
      button.setText("登录后 Star" + count);
      button.setEnabled(true);
    } else {
      button.setText((starred ? "★ 已 Star" : "☆ Star") + count);
      button.setEnabled(true);
    }
  }
}
