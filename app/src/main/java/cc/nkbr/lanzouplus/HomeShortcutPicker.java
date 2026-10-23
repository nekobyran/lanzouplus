package cc.nkbr.lanzouplus;

import android.app.AlertDialog;
import android.widget.*;
import java.util.*;

/** Select paths or files with a draft selection, then confirm the complete batch. */
final class HomeShortcutPicker {
  interface Listener { void picked(Selection selection); }
  static final class Selection {
    final List<HomeShortcutStore.Entry> entries;
    final Set<String> editableIds;
    Selection(Collection<HomeShortcutStore.Entry> entries,Set<String> editableIds){this.entries=new ArrayList<>(entries);this.editableIds=editableIds;}
  }
  private HomeShortcutPicker() {}

  static void choose(MainActivity owner,List<HomeShortcutStore.Entry> saved,HomeShortcutStore.Entry replacing,Listener listener){
    AlertDialog mode=new AlertDialog.Builder(owner).setTitle(replacing==null?"添加快捷项目":"替换快捷项目")
        .setItems(new String[]{"添加路径（可多选）","添加路径中的文件项目"},(dialog,index)->choosePaths(owner,saved,replacing,index==1,listener))
        .setNegativeButton("取消",null).create();owner.showRounded(mode);
  }

  static void choosePaths(MainActivity owner,List<HomeShortcutStore.Entry> saved,HomeShortcutStore.Entry replacing,boolean files,Listener listener){
    int session=owner.navigationSession;
    owner.io.execute(()->{try{
      List<Models.Source> known=owner.core.knownCategorySources();
      List<SourceDestinationPopup.Destination> initial=new ArrayList<>();
      Set<String> editable=new LinkedHashSet<>(),knownIds=new LinkedHashSet<>();
      for(Models.Source source:known)knownIds.add(LanzouCore.sourceId(source));
      if(!files)for(HomeShortcutStore.Entry entry:saved){
        if((entry.kind==Models.SOURCE_SINGLE||entry.kind==Models.SOURCE_WEB)&&!knownIds.contains(entry.rootId))continue;
        editable.add(entry.id);if(replacing!=null&&entry.id.equals(replacing.id))continue;
        Models.Source source=entry.source(owner);if(!entry.nodeId.isEmpty())source.nodeId="";
        initial.add(new SourceDestinationPopup.Destination("",source,entry.nodeId,entry.originPath.isEmpty()?entry.title:entry.originPath));
      }
      if(replacing!=null)editable.add(replacing.id);
      owner.runOnUiThread(()->{
        if(session!=owner.navigationSession||owner.isDestroyed())return;
        AlertDialog picker=SourceDestinationPopup.select(owner,new SourcePathCatalog(owner,known,initial),paths->{
          if(session!=owner.navigationSession)return;
          if(!files){for(SourceDestinationPopup.Destination path:paths)if(path.categoryOnly()){owner.showNotice("请选择具体路径，分类不能直接添加为快捷项目",true);return;}listener.picked(new Selection(entriesFor(paths),editable));return;}
          if(paths==null||paths.size()!=1||paths.get(0).categoryOnly()){
            owner.showNotice("请选择一个具体路径，再选择其中的快捷项目",true);return;
          }
          SourceDestinationPopup.Destination path=paths.get(0);
          Models.Source source=LanzouCore.copySource(path.composite);source.nodeId=path.nodeId;
          if(source.kind==Models.SOURCE_SINGLE||source.kind==Models.SOURCE_WEB){listener.picked(new Selection(entriesFor(paths),editable));return;}
          chooseMember(owner,source,path.path,saved,replacing,editable,listener);
        });
        picker.setTitle(files?"选择文件所在路径":"选择快捷路径");
      });
    }catch(Exception error){owner.runOnUiThread(()->owner.showNotice("快捷项目读取失败："+owner.friendlyError(error),true));}});
  }

  private static void chooseMember(MainActivity owner,Models.Source source,String path,List<HomeShortcutStore.Entry> saved,HomeShortcutStore.Entry replacing,Set<String> editable,Listener listener){
    LinkedHashMap<String,HomeShortcutStore.Entry> selected=new LinkedHashMap<>();
    for(HomeShortcutStore.Entry entry:saved)if(entry.kind==Models.SOURCE_SINGLE&&entry.originPath.equals(path)&&entry.repositoryUrl.equals(source.repositoryUrl)){
      editable.add(entry.id);if(replacing==null||!entry.id.equals(replacing.id))selected.put(entry.id,entry);
    }
    LinearLayout panel=new LinearLayout(owner);panel.setOrientation(LinearLayout.VERTICAL);panel.setPadding(owner.dp(16),owner.dp(8),owner.dp(16),0);
    panel.addView(owner.text(path,12,owner.MUTED));
    LinearLayout rows=new LinearLayout(owner);rows.setOrientation(LinearLayout.VERTICAL);panel.addView(owner.limitedDialogScroll(rows,360));
    Button more=owner.button("加载更多项目");more.setVisibility(android.view.View.GONE);panel.addView(more);
    AlertDialog dialog=new AlertDialog.Builder(owner).setTitle("选择快捷文件（可多选）").setView(panel).setNegativeButton("取消",null)
        .setPositiveButton("使用所选项目",(d,w)->listener.picked(new Selection(selected.values(),editable))).create();
    Set<String> seen=new LinkedHashSet<>();Models.Folder[] folder={null};boolean[] loading={false};int session=owner.navigationSession;
    Runnable[] load={null};
    load[0]=()->{if(loading[0])return;loading[0]=true;more.setEnabled(false);more.setText("正在读取…");
      owner.io.execute(()->{try{
        Models.Folder next=folder[0]==null?owner.core.browseSource(source,false):owner.core.browsePage(source.url,source.password,false,folder[0].page+1);
        owner.runOnUiThread(()->{
          loading[0]=false;if(session!=owner.navigationSession||!dialog.isShowing())return;folder[0]=next;
          for(Models.Item item:next.items){
            if(item.folder||item.url==null||item.url.isEmpty()||!seen.add(item.url))continue;
            HomeShortcutStore.Entry entry=entryFor(source,item,path);if(entry==null)continue;editable.add(entry.id);
            CheckBox row=new CheckBox(owner);row.setText(item.title);row.setTextColor(owner.TEXT);row.setChecked(selected.containsKey(entry.id));row.setContentDescription("选择快捷文件 "+item.title);
            row.setOnCheckedChangeListener((button,checked)->{if(checked)selected.put(entry.id,entry);else selected.remove(entry.id);});
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

  static List<HomeShortcutStore.Entry> entriesFor(List<SourceDestinationPopup.Destination> paths){
    LinkedHashMap<String,HomeShortcutStore.Entry> entries=new LinkedHashMap<>();
    for(SourceDestinationPopup.Destination path:paths){
      if(path.categoryOnly())continue;
      Models.Source source=LanzouCore.copySource(path.composite);source.nodeId=path.nodeId;source.title=path.name();source.originPath=path.path;
      HomeShortcutStore.Entry entry=HomeShortcutStore.Entry.fromSource(source);if(entry!=null)entries.put(entry.id,entry);
    }
    return new ArrayList<>(entries.values());
  }
}
