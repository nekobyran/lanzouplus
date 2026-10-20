package cc.nkbr.lanzouplus;

import android.app.AlertDialog;
import android.content.Context;
import android.view.*;
import android.widget.*;
import java.io.*;
import java.util.*;
import org.json.*;

/** Market and subscription navigation reuse the existing source rows and list state. */
final class MarketPages {
  final MainActivity owner;
  MarketPages(MainActivity owner){this.owner=owner;}
  void showRepository(Models.Source repository,boolean refresh){
    boolean entering=!repository.url.equals(owner.sourcePageRepository);if(owner.pageKind==1&&entering)owner.retainSourceListPage();if(entering)owner.selectedSourceCategories.clear();
    Runnable returnToSources=()->{if(!owner.restoreSourceListPage())owner.showSources();};
    SourceSecondaryPage.Identity identity=SourceSecondaryPage.begin(owner,repository,1,returnToSources,()->showRepository(repository,true));
    owner.activeSource=null;owner.clearFolderTrail();owner.sourcePageRepository=repository.url;owner.sourcePageRebuildAction=()->showRepository(repository,false);owner.systemBackAction=returnToSources;
    owner.activeSourceCategory="全部";TextView info=identity.text;ImageView repoIcon=identity.icon,publisherAvatar=identity.avatar;
    List<Models.Source> sources=new ArrayList<>();owner.sourcePageSources=sources;owner.sourceGrid=new GridLayout(owner);GridLayout grid=owner.sourceGrid;
    owner.sourceFilter=owner.pageSearch(query->owner.queueSourceListFilter(sources,query,owner.sourceHeading));owner.sourceHeading=owner.text("正在读取仓库…",12,owner.MUTED);owner.root.addView(owner.sourceHeading);owner.addSourceCategoryBar(sources);
    ScrollView scroll=new ScrollView(owner);owner.pageScroll=scroll;scroll.addView(grid);owner.root.addView(owner.draggableList(scroll,grid,"拖动仓库列表"),new LinearLayout.LayoutParams(-1,0,1));int session=owner.navigationSession;
    owner.io.execute(()->{
      try{List<Models.Source> cached=owner.core.cachedRepositorySources(repository);if(!cached.isEmpty())publishSources(session,grid,sources,cached);
        List<Models.Source> latest=owner.core.repositorySources(repository,refresh);publishSources(session,grid,sources,latest);
        publishRepositoryHeader(session,info,repoIcon,publisherAvatar,repository);
      }catch(Exception error){owner.runOnUiThread(()->{if(session==owner.navigationSession){owner.showNotice("仓库读取失败："+owner.friendlyError(error),true);if(sources.isEmpty())owner.sourceHeading.setText("读取失败，可点右上角重试");}});}
    });
  }
  /** After a read (including a manual check) the header shows the repository's newest metadata. */
  private void publishRepositoryHeader(int session,TextView info,ImageView repoIcon,ImageView avatar,Models.Source repository){
    MarketRepositoryClient.Document document;try{document=owner.core.marketRepositories().cached(repository.url);}catch(Exception error){return;}if(document==null)return;
    Models.Source fresh;try{fresh=document.source();}catch(Exception error){return;}
    final String iconUrl=fresh.iconUrl,avatarUrl=fresh.avatarUrl;owner.runOnUiThread(()->{if(session!=owner.navigationSession)return;SourceSecondaryPage.update(owner,new SourceSecondaryPage.Identity(repoIcon,avatar,info),fresh.title,fresh.publisher,fresh.repositoryVersion,fresh.description,iconUrl,avatarUrl);});
  }
  private void publishSources(int session,GridLayout grid,List<Models.Source> target,List<Models.Source> values){owner.runOnUiThread(()->{if(session!=owner.navigationSession||owner.sourceGrid!=grid)return;target.clear();target.addAll(values);owner.sourceDataRevision++;owner.rebuildCategoryMemberships(target);owner.renderSourceCategories(target);owner.renderSources(grid,target,owner.sourceFilter.getText().toString(),owner.sourceHeading);MainActivity.SourceListPageState restore=owner.pendingSourceListRestore;if(restore!=null){owner.pendingSourceListRestore=null;owner.restoreFolderScrollBeforeDraw(owner.pageScroll,restore.pageScroll.getScrollY());}});}

  void showMarket(boolean force){
    owner.pageDirection=1;owner.primaryBase(4);owner.pageKind=5;owner.activeSource=null;owner.clearFolderTrail();owner.systemBackAction=owner::navigateHome;owner.primaryHeader("市场");
    ImageButton submit=owner.iconButton(R.drawable.ic_add,"上传 GitHub 仓库链接");submit.setOnClickListener(v->submitDialog(null));owner.pageHeaderRow.addView(submit,new LinearLayout.LayoutParams(owner.dp(44),owner.dp(48)));
    ImageButton projects=owner.iconButton(R.drawable.ic_edit,"仓库工程");projects.setOnClickListener(v->showProjects());owner.pageHeaderRow.addView(projects,new LinearLayout.LayoutParams(owner.dp(44),owner.dp(48)));
    ImageButton management=owner.iconButton(R.drawable.ic_settings,"云盘文件管理");management.setOnClickListener(v->owner.cloudManagement().show());owner.pageHeaderRow.addView(management,new LinearLayout.LayoutParams(owner.dp(44),owner.dp(48)));
    ImageButton reload=owner.iconButton(R.drawable.ic_refresh,"更新市场");reload.setOnClickListener(v->showMarket(true));owner.pageHeaderRow.addView(reload,new LinearLayout.LayoutParams(owner.dp(44),owner.dp(48)));
    TextView status=owner.text("正在读取市场…",13,owner.MUTED);owner.root.addView(status,new LinearLayout.LayoutParams(-1,owner.dp(28)));
    GridView grid=new GridView(owner);owner.root.addView(grid,new LinearLayout.LayoutParams(-1,0,1));
    MarketCatalogView catalog=new MarketCatalogView(owner,grid,status,force);int session=owner.navigationSession;
    owner.io.execute(()->{try{
      File file=new File(owner.getFilesDir(),"market-index.json");MarketIndex cached=readIndex(file);
      if(!cached.entries.isEmpty())owner.runOnUiThread(()->catalog.render(cached.entries));
      List<MarketRepositoryClient.CatalogEntry> latest=new ArrayList<>();StringBuilder fingerprint=new StringBuilder();
      for(int offset=0;;offset+=50){MarketRepositoryClient.Catalog page=owner.core.marketRepositories().catalog(offset,50);latest.addAll(page.entries);fingerprint.append(page.fingerprint());if(page.entries.size()<50)break;}
      boolean auto=owner.getSharedPreferences("market",Context.MODE_PRIVATE).getBoolean("auto_update",true);
      String current=fingerprint.toString();
      Runnable apply=()->{owner.io.execute(()->{try{JSONArray rows=new JSONArray();for(MarketRepositoryClient.CatalogEntry entry:latest)rows.put(entry.json());JSONObject saved=new JSONObject().put("fingerprint",current).put("repositories",rows);try(FileOutputStream output=new FileOutputStream(file)){output.write(saved.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8));}}catch(Exception error){owner.runOnUiThread(()->owner.showNotice("市场缓存保存失败",true));}});catalog.render(latest);};
      owner.runOnUiThread(()->{if(session!=owner.navigationSession)return;if(auto||force||cached.entries.isEmpty())apply.run();else if(!cached.fingerprint.equals(current)){AlertDialog prompt=new AlertDialog.Builder(owner).setTitle("市场有更新").setMessage("已发现新的仓库目录，现在更新？").setNegativeButton("稍后",null).setPositiveButton("更新",(dialog,which)->apply.run()).create();owner.showRounded(prompt);}});
    }catch(Exception error){owner.runOnUiThread(()->{if(session==owner.navigationSession){status.setText("市场暂时无法更新");owner.showNotice(owner.friendlyError(error),true);}});}});
  }
  /**
   * The cached catalog page set. It keeps the snapshot fingerprint next to the links, so a
   * server re-snapshot (every seven days) is visible even when the URL list is unchanged.
   */
  private static final class MarketIndex {
    final String fingerprint;final List<MarketRepositoryClient.CatalogEntry> entries;
    MarketIndex(String fingerprint,List<MarketRepositoryClient.CatalogEntry> entries){this.fingerprint=fingerprint;this.entries=entries;}
  }
  private MarketIndex readIndex(File file)throws Exception{
    List<MarketRepositoryClient.CatalogEntry> entries=new ArrayList<>();
    if(!file.isFile())return new MarketIndex("",entries);
    String raw;try(InputStream input=new FileInputStream(file)){raw=new String(MarketRepositoryClient.bytes(input,512*1024),java.nio.charset.StandardCharsets.UTF_8);}
    JSONObject value=new JSONObject(raw);
    JSONArray array=value.optJSONArray("repositories");
    if(array!=null)for(int i=0;i<array.length();i++)entries.add(MarketRepositoryClient.CatalogEntry.parse(array.getJSONObject(i)));
    return new MarketIndex(value.optString("fingerprint"),entries);
  }
  void submitDialog(String initialUrl){
    LinearLayout panel=new LinearLayout(owner);panel.setOrientation(LinearLayout.VERTICAL);panel.setPadding(owner.dp(20),owner.dp(8),owner.dp(20),owner.dp(8));
    TextView help=owner.text("只需填写标准 GitHub 仓库链接。展示名称、仓库名、发布者和简介会从仓库自动读取。",12,owner.MUTED);
    help.setPadding(0,0,0,owner.dp(8));panel.addView(help);
    EditText input=owner.sourceInput("https://github.com/发布者/lzpmarket-名称",android.text.InputType.TYPE_CLASS_TEXT|android.text.InputType.TYPE_TEXT_VARIATION_URI);
    input.setContentDescription("GitHub 仓库链接");
    if(initialUrl!=null&&!initialUrl.trim().isEmpty())input.setText(initialUrl.trim());
    panel.addView(input,new LinearLayout.LayoutParams(-1,owner.dp(56)));
    TextView status=owner.text("链接已收录时会比较远端版本，仅上传更高版本。",12,owner.MUTED);
    status.setPadding(0,owner.dp(6),0,0);panel.addView(status);
    AlertDialog prompt=new AlertDialog.Builder(owner).setTitle("上传到市场").setView(panel).setNegativeButton("取消",null).setPositiveButton("检测并上传",null).create();int session=owner.navigationSession;
    prompt.setOnShowListener(dialog->prompt.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v->{
      Button action=prompt.getButton(AlertDialog.BUTTON_POSITIVE);action.setEnabled(false);action.setText("处理中…");final String link=input.getText().toString();
      owner.io.execute(()->{try{
        MarketRepositoryClient.Submission result=owner.core.marketRepositories().upload(link,message->owner.runOnUiThread(()->{
          if(owner.navigationSession==session&&prompt.isShowing())status.setText(message);
        }));
        owner.runOnUiThread(()->{
          if(owner.navigationSession!=session)return;
          prompt.dismiss();
          String outcome=result.current()?"仓库已是最新版本，未重复提交":
              result.created()?"市场资料已创建：":"市场资料已更新：";
          owner.showNotice(outcome+result.title+" · "+result.version,false);
          if(!result.current())showMarket(true);
        });
      }catch(Exception error){owner.runOnUiThread(()->{
        if(owner.navigationSession!=session)return;
        action.setEnabled(true);action.setText("检测并上传");
        status.setText(owner.friendlyError(error));
        input.setError(owner.friendlyError(error));
      });}});
    }));owner.showRounded(prompt);
  }
  void showProjects(){new MarketProjects(owner).show();}
}
