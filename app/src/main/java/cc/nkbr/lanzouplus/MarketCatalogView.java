package cc.nkbr.lanzouplus;

import android.app.AlertDialog;
import android.content.res.Configuration;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.*;
import java.util.*;

/** The market is a full destination. GridView only binds visible repository cards. */
final class MarketCatalogView {
  private final MainActivity owner;
  private final GridView grid;
  private final TextView summary;
  private final List<Card> cards=new ArrayList<>();
  private final CatalogAdapter adapter=new CatalogAdapter();
  private final int session;
  private final boolean force;

  private static final class Card {
    final String url;
    boolean loading;
    MarketRepositoryClient.Document document;
    MarketRepositoryClient.GithubInfo github;
    String error="";
    Card(String url){this.url=url;}
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
    grid.setOnItemClickListener((parent,view,position,id)->showDetails(cards.get(position)));
  }
  void render(List<String> urls){
    if(session!=owner.navigationSession||owner.pageKind!=5)return;
    Map<String,Card> before=new HashMap<>();for(Card card:cards)before.put(card.url,card);
    cards.clear();
    for(String url:urls)cards.add(before.containsKey(url)?before.get(url):new Card(url));
    summary.setText(cards.isEmpty()?"市场暂无仓库，可上传 GitHub 仓库链接":"资源仓库 · "+cards.size()+" 个");
    adapter.notifyDataSetChanged();
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
      try{github=owner.core.marketRepositories().githubInfo(card.url,force);}catch(Exception failure){error=owner.friendlyError(failure);}
      try{document=owner.core.marketRepositories().preview(card.url,force);}catch(Exception failure){if(error.isEmpty())error=owner.friendlyError(failure);}
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
    @Override public int getCount(){return cards.size();}
    @Override public Object getItem(int position){return cards.get(position);}
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
      Card card=cards.get(position);
      String identity=card.url.substring(card.url.lastIndexOf('/')+1);
      holder.name.setText(card.github==null?identity:card.github.name);
      String publisher=card.document!=null?card.document.sourcePublisher():card.github==null?card.url.substring("https://github.com/".length(),card.url.lastIndexOf('/')):card.github.owner;
      holder.publisher.setText(publisher);
      holder.stars.setText(card.github==null||card.github.stars<0?"☆ —":"★ "+card.github.stars);
      String description=card.github==null?"":card.github.description;
      if(description.isEmpty()&&card.document!=null)description=card.document.metadata.description;
      if(description.isEmpty())description=card.error.isEmpty()?"读取仓库简介中…":"信息暂时无法读取";
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
    TextView status=owner.text("正在读取 GitHub README…",12,owner.MUTED);
    status.setPadding(owner.dp(12),0,owner.dp(12),owner.dp(8));
    panel.addView(status,new LinearLayout.LayoutParams(-1,-2));
    FrameLayout frame=new FrameLayout(owner);
    ProgressBar progress=new ProgressBar(owner);
    FrameLayout.LayoutParams progressParams=new FrameLayout.LayoutParams(owner.dp(40),owner.dp(40),Gravity.CENTER);
    frame.addView(progress,progressParams);
    int height=Math.min(owner.dp(480),Math.max(owner.dp(96),owner.safeContentHeight()-owner.dp(190)));
    panel.addView(frame,new LinearLayout.LayoutParams(-1,height));
    String name=card.github==null?card.url.substring(card.url.lastIndexOf('/')+1):card.github.name;
    AlertDialog dialog=new AlertDialog.Builder(owner).setTitle(name)
        .setView(panel).setNeutralButton("关闭",null)
        .setNegativeButton("删除",null).setPositiveButton("下载",null).create();
    final MarketRepositoryClient.Document[] document={null};
    final Models.Source[] installed={null};
    final Button[] download={null},delete={null};
    final boolean[] busy={false};
    final android.webkit.WebView[] web={null};
    dialog.setOnDismissListener(d->{if(web[0]!=null){web[0].stopLoading();web[0].destroy();web[0]=null;}});
    final Runnable[] updateButtons={null};
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
    });
    owner.showRounded(dialog);
    owner.io.execute(()->{
      try{
        MarketRepositoryClient.Document loaded=owner.core.marketRepositories().readme(card.url,true);
        Models.Source current=findInstalled(card.url);
        MarketRepositoryClient.GithubInfo info=card.github;
        if(info==null)try{info=owner.core.marketRepositories().githubInfo(card.url,force);}catch(Exception ignored){}
        MarketRepositoryClient.GithubInfo identity=info;
        owner.runOnUiThread(()->{
          if(!dialog.isShowing())return;
          document[0]=loaded;installed[0]=current;status.setText(loaded.sourcePublisher()+
              (identity==null?"":" · ★ "+identity.stars)+" · "+loaded.metadata.version);
          frame.removeAllViews();
          web[0]=MarketReadmeView.create(owner,loaded.readme,loaded.rawRoot,loaded.githubUrl);
          frame.addView(web[0],
              new FrameLayout.LayoutParams(-1,-1));
          updateButtons[0].run();
        });
      }catch(Exception error){
        owner.runOnUiThread(()->{
          if(!dialog.isShowing())return;
          frame.removeAllViews();TextView failure=owner.text("无法读取 README：\n"+owner.friendlyError(error),13,owner.MUTED);
          failure.setPadding(owner.dp(16),owner.dp(24),owner.dp(16),0);
          frame.addView(failure);status.setText("GitHub 仓库读取失败");updateButtons[0].run();
        });
      }
    });
  }
  private Models.Source findInstalled(String url)throws Exception{
    for(Models.Source source:owner.core.sources())if(source.kind==Models.SOURCE_REPOSITORY&&url.equals(source.url))return source;
    return null;
  }
  private static boolean newer(MarketRepositoryClient.Document document,Models.Source source){
    try{return !document.metadata.versionMode.equals(source.repositoryVersionMode)||
        MarketRepository.compareVersions(document.metadata.versionMode,document.metadata.version,source.repositoryVersion)>0;
    }catch(Exception ignored){return false;}
  }
}
