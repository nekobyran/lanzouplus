package cc.nkbr.lanzouplus;

import android.app.AlertDialog;
import android.widget.*;
import java.util.*;

/** Select an existing path, then one concrete project or file inside that path. */
final class HomeShortcutPicker {
  interface Listener { void picked(HomeShortcutStore.Entry entry); }
  private HomeShortcutPicker() {}

  static void choose(MainActivity owner,Listener listener){
    int session=owner.navigationSession;
    owner.io.execute(()->{try{
      List<Models.Source> known=owner.core.knownCategorySources();
      owner.runOnUiThread(()->{
        if(session!=owner.navigationSession||owner.isDestroyed())return;
        SourceDestinationPopup.select(owner,new SourcePathCatalog(owner,known),paths->{
          if(session!=owner.navigationSession)return;
          if(paths==null||paths.size()!=1||paths.get(0).categoryOnly()){
            owner.showNotice("请选择一个具体路径，再选择其中的快捷项目",true);return;
          }
          SourceDestinationPopup.Destination path=paths.get(0);
          Models.Source source=LanzouCore.copySource(path.composite);source.nodeId=path.nodeId;
          if(source.kind==Models.SOURCE_SINGLE||source.kind==Models.SOURCE_WEB){listener.picked(HomeShortcutStore.Entry.fromSource(source));return;}
          chooseMember(owner,source,path.path,listener);
        });
      });
    }catch(Exception error){owner.runOnUiThread(()->owner.showNotice("快捷项目读取失败："+owner.friendlyError(error),true));}});
  }

  private static void chooseMember(MainActivity owner,Models.Source source,String path,Listener listener){
    LinearLayout panel=new LinearLayout(owner);panel.setOrientation(LinearLayout.VERTICAL);panel.setPadding(owner.dp(16),owner.dp(8),owner.dp(16),0);
    panel.addView(owner.text(path,12,owner.MUTED));
    LinearLayout rows=new LinearLayout(owner);rows.setOrientation(LinearLayout.VERTICAL);panel.addView(owner.limitedDialogScroll(rows,360));
    Button more=owner.button("加载更多项目");more.setVisibility(android.view.View.GONE);panel.addView(more);
    AlertDialog dialog=new AlertDialog.Builder(owner).setTitle("选择单个快捷项目").setView(panel).setNegativeButton("取消",null).create();
    Set<String> seen=new LinkedHashSet<>();Models.Folder[] folder={null};boolean[] loading={false};int session=owner.navigationSession;
    Runnable[] load={null};
    load[0]=()->{if(loading[0])return;loading[0]=true;more.setEnabled(false);more.setText("正在读取…");
      owner.io.execute(()->{try{
        Models.Folder next=folder[0]==null?owner.core.browseSource(source,false):owner.core.browsePage(source.url,source.password,false,folder[0].page+1);
        owner.runOnUiThread(()->{
          loading[0]=false;if(session!=owner.navigationSession||!dialog.isShowing())return;folder[0]=next;
          for(Models.Item item:next.items){
            if(item.folder||item.url==null||item.url.isEmpty()||!seen.add(item.url))continue;
            Button row=owner.toolbarTextButton(item.title);row.setGravity(android.view.Gravity.START|android.view.Gravity.CENTER_VERTICAL);
            row.setOnClickListener(v->{HomeShortcutStore.Entry entry=entryFor(source,item,path);if(entry!=null){dialog.dismiss();listener.picked(entry);}});
            rows.addView(row,new LinearLayout.LayoutParams(-1,owner.dp(48)));
          }
          if(seen.isEmpty()&&!next.hasMore)rows.addView(owner.text("此路径没有单个文件项目，请返回选择其他路径",13,owner.MUTED));
          more.setVisibility(next.hasMore?android.view.View.VISIBLE:android.view.View.GONE);more.setEnabled(true);more.setText("加载更多项目");
        });
      }catch(Exception error){owner.runOnUiThread(()->{loading[0]=false;if(!dialog.isShowing())return;more.setVisibility(android.view.View.VISIBLE);more.setEnabled(true);more.setText("重试");owner.showNotice(owner.friendlyError(error),true);});}});
    };
    more.setOnClickListener(v->load[0].run());owner.showRounded(dialog);load[0].run();
  }

  static HomeShortcutStore.Entry entryFor(Models.Source parent,Models.Item item,String path){
    if(item==null||item.folder||item.url==null||item.url.isEmpty())return null;
    Models.Source leaf=new Models.Source();leaf.id=leaf.url=item.url;leaf.kind=Models.SOURCE_SINGLE;
    leaf.title=item.title;leaf.iconUrl=item.iconUrl;leaf.password=item.password;leaf.description=item.description;
    leaf.repositoryUrl=parent.repositoryUrl;leaf.originPath=path;leaf.parentId=LanzouCore.sourceId(parent);
    return HomeShortcutStore.Entry.fromSource(leaf);
  }

  static HomeShortcutStore.Entry entryFor(MainActivity owner,List<SourceDestinationPopup.Destination> paths){
    if(paths==null||paths.size()!=1||paths.get(0).categoryOnly())return null;
    Models.Source source=paths.get(0).composite;
    return source.kind==Models.SOURCE_SINGLE||source.kind==Models.SOURCE_WEB?HomeShortcutStore.Entry.fromSource(source):null;
  }
}
