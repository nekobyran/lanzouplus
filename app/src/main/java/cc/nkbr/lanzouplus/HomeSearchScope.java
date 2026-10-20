package cc.nkbr.lanzouplus;

import android.app.AlertDialog;
import android.view.*;
import android.widget.*;
import java.util.*;
import org.json.*;

/** Local tag OR selection intersected with selected paths; web search has its own mode. */
final class HomeSearchScope {
  final MainActivity owner;
  final List<SourceDestinationPopup.Destination> paths=new ArrayList<>();
  final List<String> categories=new ArrayList<>();
  boolean hasCategories(){return !categories.isEmpty();}
  HomeSearchScope(MainActivity owner){this.owner=owner;}
  void choose(){owner.io.execute(()->{try{List<Models.Source> known=owner.core.knownCategorySources();Draft draft=new Draft();draft.paths.addAll(paths);draft.tags.addAll(categories);draft.web="网页搜索".equals(owner.sessionSearchCategory);draft.autoIndex=owner.sessionBackgroundIndex;owner.runOnUiThread(()->editor(known,draft));}catch(Exception error){owner.runOnUiThread(()->owner.showNotice(owner.friendlyError(error),true));}});}
  private static final class Draft {final List<SourceDestinationPopup.Destination> paths=new ArrayList<>();final List<String> tags=new ArrayList<>();boolean web,autoIndex;}
  private void editor(List<Models.Source> known,Draft draft){
    LinearLayout panel=new LinearLayout(owner);panel.setOrientation(LinearLayout.VERTICAL);panel.setPadding(owner.dp(18),0,owner.dp(18),0);
    RadioGroup modes=new RadioGroup(owner);modes.setOrientation(RadioGroup.HORIZONTAL);RadioButton local=new RadioButton(owner),web=new RadioButton(owner);local.setId(View.generateViewId());web.setId(View.generateViewId());local.setText("资源搜索");web.setText("网页搜索");local.setTextColor(owner.TEXT);web.setTextColor(owner.TEXT);modes.addView(local,new RadioGroup.LayoutParams(0,owner.dp(48),1));modes.addView(web,new RadioGroup.LayoutParams(0,owner.dp(48),1));modes.check(draft.web?web.getId():local.getId());modes.setOnCheckedChangeListener((group,id)->draft.web=id==web.getId());panel.addView(modes);
    AlertDialog[] dialog={null};
    Runnable select=()->HistoryPopupNavigation.switchCollection(owner,dialog[0],()->SourceCategoryPicker.showExisting(owner,known,draft.tags,values->{draft.tags.clear();draft.tags.addAll(values);editor(known,draft);},()->editor(known,draft)));
    Button pick=owner.toolbarTextButton("选择已有分类");pick.setContentDescription("搜索选择已有分类");pick.setOnClickListener(v->select.run());panel.addView(pick,new LinearLayout.LayoutParams(-1,owner.dp(48)));
    HorizontalScrollView scroll=new HorizontalScrollView(owner);scroll.setHorizontalScrollBarEnabled(false);LinearLayout tags=new LinearLayout(owner);tags.setGravity(Gravity.CENTER_VERTICAL);scroll.addView(tags);panel.addView(scroll,new LinearLayout.LayoutParams(-1,owner.dp(42)));
    if(draft.tags.isEmpty())tags.addView(owner.text("不限分类",12,owner.MUTED));
    for(String value:new ArrayList<>(draft.tags)){TextView tag=owner.sourceTag(value);tag.setContentDescription(value+"，长按删除或更换");tag.setOnLongClickListener(v->{AlertDialog actions=new AlertDialog.Builder(owner).setTitle(value).setItems(new String[]{"删除标签","更换为已有分类"},(d,index)->{if(index==0){draft.tags.remove(value);HistoryPopupNavigation.switchCollection(owner,dialog[0],()->editor(known,draft));}else select.run();}).create();owner.showRounded(actions);return true;});LinearLayout.LayoutParams p=new LinearLayout.LayoutParams(-2,owner.dp(32));p.rightMargin=owner.dp(6);tags.addView(tag,p);}
    Button path=owner.toolbarTextButton("选择一个或多个路径");path.setContentDescription("选择搜索路径");panel.addView(path,new LinearLayout.LayoutParams(-1,owner.dp(48)));
    HorizontalScrollView pathScroll=new HorizontalScrollView(owner);pathScroll.setHorizontalScrollBarEnabled(false);LinearLayout pathTags=new LinearLayout(owner);pathScroll.addView(pathTags);panel.addView(pathScroll,new LinearLayout.LayoutParams(-1,owner.dp(42)));
    if(draft.paths.isEmpty())pathTags.addView(owner.text("全部路径",12,owner.MUTED));
    for(SourceDestinationPopup.Destination selected:new ArrayList<>(draft.paths)){String label=selected.path.isEmpty()?selected.name():selected.path;TextView tag=owner.sourceTag(label);tag.setContentDescription("已选路径 "+label+"，长按移除");tag.setOnClickListener(v->path.performClick());tag.setOnLongClickListener(v->{draft.paths.remove(selected);HistoryPopupNavigation.switchCollection(owner,dialog[0],()->editor(known,draft));return true;});LinearLayout.LayoutParams p=new LinearLayout.LayoutParams(-2,owner.dp(32));p.rightMargin=owner.dp(6);pathTags.addView(tag,p);}
    path.setOnClickListener(v->HistoryPopupNavigation.switchCollection(owner,dialog[0],()->{AlertDialog picker=SourceDestinationPopup.select(owner,new SourcePathCatalog(owner,known,draft.paths),selected->{draft.paths.clear();draft.paths.addAll(selected);editor(known,draft);});returnToEditor(picker,()->editor(known,draft));}));
    CheckBox automatic=new CheckBox(owner);automatic.setText("自动索引所选路径与分类");automatic.setTextColor(owner.TEXT);automatic.setButtonTintList(android.content.res.ColorStateList.valueOf(owner.PRIMARY));automatic.setChecked(draft.autoIndex);automatic.setOnCheckedChangeListener((v,checked)->draft.autoIndex=checked);panel.addView(automatic,new LinearLayout.LayoutParams(-1,owner.dp(44)));
    TextView index=owner.text("索引所属路径："+(draft.paths.isEmpty()?"全部路径":pathSummary(draft.paths))+(draft.tags.isEmpty()?"":" · "+String.join("、",draft.tags)),11,owner.MUTED);panel.addView(index);
    dialog[0]=new AlertDialog.Builder(owner).setTitle("搜索范围").setView(SharedPopupSurface.dialogScroll(owner,panel,470)).setNegativeButton("取消",null).setNeutralButton("全部路径",null).setPositiveButton("应用",(d,w)->{paths.clear();paths.addAll(draft.paths);SourceCategories.replace(categories,draft.tags);owner.sessionSearchCategory=draft.web?"网页搜索":"全部";owner.sessionHomeSourceCategory=categories.size()==1?categories.get(0):"全部";refreshLabel();applyAutomaticIndex(known,draft);}).create();
    dialog[0].setOnShowListener(d->dialog[0].getButton(AlertDialog.BUTTON_NEUTRAL).setOnClickListener(v->{draft.paths.clear();HistoryPopupNavigation.switchCollection(owner,dialog[0],()->editor(known,draft));}));owner.showRounded(dialog[0]);
  }
  private static String pathSummary(List<SourceDestinationPopup.Destination> paths){List<String> names=new ArrayList<>();for(SourceDestinationPopup.Destination path:paths)names.add(path.path.isEmpty()?path.name():path.path);return String.join("、",names);}
  private void applyAutomaticIndex(List<Models.Source> known,Draft draft){
    owner.sessionBackgroundIndex=draft.autoIndex;owner.persistSearchSettings();
    if(!draft.autoIndex){owner.core.cancelBackgroundIndex();return;}
    owner.io.execute(()->{try{
      List<Models.Source> selected=draft.paths.isEmpty()?known:SourcePathCatalog.selectedSources(owner.core,draft.paths);
      List<Models.Source> scoped=categoryIndexSources(selected,draft.tags);List<IndexPathScope.Entry> entries=new ArrayList<>();
      for(SourceDestinationPopup.Destination path:draft.paths)entries.add(new IndexPathScope.Entry(String.join("、",draft.tags),path.nodeId,path.path,path.composite));
      if(entries.isEmpty()&&!draft.tags.isEmpty())entries.add(new IndexPathScope.Entry(String.join("、",draft.tags),"","全部路径 · "+String.join("、",draft.tags),null));
      IndexPathScope scope=IndexPathScope.of(entries,scoped);
      owner.runOnUiThread(()->{owner.applyIndexPathScope(scope);owner.requestDirectoryIndexUpdate(false,false);});
    }catch(Exception error){owner.runOnUiThread(()->owner.showNotice("自动索引范围设置失败："+owner.friendlyError(error),true));}});
  }
  static List<Models.Source> categoryIndexSources(List<Models.Source> values,List<String> tags){
    if(tags.isEmpty())return values;List<Models.Source> out=new ArrayList<>();
    for(Models.Source source:values){if(SourceCategoryPicker.matches(source,tags)){out.add(source);continue;}if(source.kind!=Models.SOURCE_COMPOSITE)continue;
      Models.Source copy=LanzouCore.copySource(source);Set<String> included=new HashSet<>();for(Models.SourceMember member:copy.members)if(SourceCategoryPicker.matches(member,tags))included.add(member.id);
      boolean changed;do{changed=false;for(Models.SourceMember member:copy.members)if(included.contains(member.parentId)&&included.add(member.id))changed=true;}while(changed);
      copy.members.removeIf(member->!included.contains(member.id));if(!copy.members.isEmpty())out.add(copy);
    }return out;
  }
  private void returnToEditor(AlertDialog picker,Runnable back){if(picker==null)return;picker.getButton(AlertDialog.BUTTON_NEGATIVE).setOnClickListener(v->HistoryPopupNavigation.switchCollection(owner,picker,back));picker.setOnKeyListener((d,key,event)->{if(key!=android.view.KeyEvent.KEYCODE_BACK)return false;if(event.getAction()==android.view.KeyEvent.ACTION_UP)HistoryPopupNavigation.switchCollection(owner,picker,back);return true;});}
  void refreshLabel(){if(owner.homeCategoryPicker!=null){String label="网页搜索".equals(owner.sessionSearchCategory)?"网页搜索":paths.isEmpty()?"全部路径":paths.size()==1?paths.get(0).name():paths.size()+" 个路径";if(!"网页搜索".equals(owner.sessionSearchCategory)&&!categories.isEmpty())label+=" · "+(categories.size()==1?categories.get(0):categories.size()+" 类");owner.homeCategoryPicker.selectedMode=owner.sessionSearchCategory;owner.homeCategoryPicker.value.setText(label);owner.homeCategoryPicker.trigger.setContentDescription("搜索范围："+label+"，点击更改路径、分类或网页搜索");}}
  List<Models.Source> sources(){List<Models.Source> out=new ArrayList<>();for(SourceDestinationPopup.Destination path:paths){Models.Source copy=new Models.Source();MainActivity.copySource(path.composite,copy);copy.nodeId=path.nodeId;out.add(copy);}return out;}
  List<Models.Item> cachedItems(String query)throws Exception{
    Set<String> allowed=ids();List<Models.Item> cached=owner.core.cachedPartialIndexMatches(query,allowed,owner.fuzzyIndexEnabled());
    List<Models.Item> out=new ArrayList<>();Set<String> local=new HashSet<>(),remote=new HashSet<>();
    for(Models.Source source:sources())if(source.kind==Models.SOURCE_COMPOSITE){for(Models.Item item:owner.core.collectLocalPathItems(source))local.add(item.url);}else if(source.kind==Models.SOURCE_REPOSITORY){for(Models.Source child:owner.core.cachedRepositorySources(source))remote.add(LanzouCore.sourceId(child));}else remote.add(LanzouCore.sourceId(source));
    for(Models.Item item:cached)if(local.contains(item.url)||remote.contains(item.sourceId))out.add(item);return out;
  }
  Set<String> ids()throws Exception{
    if(paths.isEmpty()&&categories.isEmpty())return null;
    LinkedHashSet<String> ids=new LinkedHashSet<>();for(Models.Source source:owner.core.knownCategorySources())if(SourceCategoryPicker.matches(source,categories)&&inPaths(source))ids.add(LanzouCore.sourceId(source));
    if(categories.isEmpty())for(Models.Source source:sources())ids.add(LanzouCore.sourceId(source));return ids;
  }
  boolean inPaths(Models.Source source){if(paths.isEmpty())return true;for(SourceDestinationPopup.Destination path:paths){if(LanzouCore.sourceId(path.composite).equals(LanzouCore.sourceId(source)))return true;if(path.composite.kind==Models.SOURCE_REPOSITORY&&path.composite.url.equals(source.repositoryUrl))return true;}return false;}
  private boolean wholeSourceInPaths(Models.Source source){if(paths.isEmpty())return true;for(SourceDestinationPopup.Destination path:paths)if(path.nodeId.isEmpty()&&(LanzouCore.sourceId(path.composite).equals(LanzouCore.sourceId(source))||path.composite.kind==Models.SOURCE_REPOSITORY&&path.composite.url.equals(source.repositoryUrl)))return true;return false;}
  /** A category entry matches the source node's title, description and its own labels. */
  private static String categoryMatchText(String title,String description,List<String> categories){StringBuilder out=new StringBuilder(title==null?"":title).append(' ').append(description==null?"":description);for(String value:categories)out.append(' ').append(value);return out.toString().toLowerCase(Locale.ROOT);}
  List<Models.Item> categoryItems(String query)throws Exception{
    List<Models.Item> out=new ArrayList<>();String needle=query.toLowerCase(Locale.ROOT);
    for(Models.Source source:owner.core.knownCategorySources()){
      if(!inPaths(source))continue;
      if(wholeSourceInPaths(source)&&SourceCategoryPicker.matches(source,categories)&&owner.folderMatchRank(categoryMatchText(source.title,source.description,SourceCategoryPicker.labels(source)),needle,owner.sessionSearchFuzzyMatching)>=0){Models.Item item=new Models.Item();item.title=source.title;item.url=source.url.isEmpty()?source.id:source.url;item.shareUrl=source.url;item.source=source.publisher;item.sourceId=LanzouCore.sourceId(source);item.sourceEntry=true;item.folder=source.kind==Models.SOURCE_OFFICIAL||source.kind==Models.SOURCE_COMPOSITE||source.kind==Models.SOURCE_REPOSITORY;item.iconUrl=source.iconUrl;item.categories.addAll(SourceCategoryPicker.labels(source));out.add(item);}
      if(source.kind==Models.SOURCE_COMPOSITE){Map<String,String> parents=new HashMap<>();for(Models.SourceMember member:source.members)parents.put(member.id,member.parentId);for(Models.SourceMember member:source.members){if(!SourceCategoryPicker.matches(member,categories))continue;if(owner.folderMatchRank(categoryMatchText(member.title,member.description,SourceCategoryPicker.labels(member)),needle,owner.sessionSearchFuzzyMatching)<0)continue;if(!memberInPaths(source,member,parents))continue;Models.Item item=new Models.Item();item.title=member.title;item.url=member.url.isEmpty()?LanzouCore.sourceId(source)+"::"+member.id:member.url;item.shareUrl=member.url;item.password=member.password;item.iconUrl=member.iconUrl;item.source=source.title;item.sourceId=LanzouCore.sourceId(source);item.sourceEntry=true;item.folder=member.kind!=Models.MEMBER_FILE;item.folderId=member.kind==Models.MEMBER_REMOTE_FOLDER?"remote:"+member.id:member.id;item.categories.addAll(member.categories);out.add(item);}}
    }return out;
  }
  private boolean memberInPaths(Models.Source source,Models.SourceMember member,Map<String,String> parents){if(paths.isEmpty())return true;for(SourceDestinationPopup.Destination path:paths){if(!LanzouCore.sourceId(source).equals(LanzouCore.sourceId(path.composite)))continue;if(path.nodeId.isEmpty())return true;if(path.nodeId.equals(member.id))continue;String id=member.parentId;Set<String> seen=new HashSet<>();while(!id.isEmpty()&&seen.add(id)){if(path.nodeId.equals(id))return true;id=parents.getOrDefault(id,"");}}return false;}
}
