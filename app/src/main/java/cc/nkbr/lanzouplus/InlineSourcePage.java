package cc.nkbr.lanzouplus;

import android.view.*;
import android.widget.*;
import java.util.*;

/** Local collections and GitHub repositories change data inside the existing Lists destination. */
final class InlineSourcePage {
  static final class Level {
    final Models.Source source;
    List<Models.Source> values;
    final Map<String,Models.Item> items=new HashMap<>();
    final Map<String,Models.Source> rows=new HashMap<>();
    String query="";
    final List<String> categories=new ArrayList<>();
    int scroll;
    Level(Models.Source source,List<Models.Source> values){this.source=source;this.values=values;}
  }
  final MainActivity owner;
  final List<Level> levels=new ArrayList<>();
  private final Map<String,Models.Source> roots=new java.util.concurrent.ConcurrentHashMap<>();
  private HorizontalScrollView breadcrumb;
  private TextView identity;
  private LinearLayout shell;
  private int request;
    private final Set<String> hydrated=new HashSet<>();
  private List<BrowserHistory.Route> historyRoute;
  private int historyDepth;
  private String historyQuery="";
  private int historyScroll;

  InlineSourcePage(MainActivity owner){this.owner=owner;}
  static void open(MainActivity owner,Models.Source source,boolean refresh){
    if(owner.pageKind!=1||owner.sourceGrid==null)owner.showSources((MainActivity.SourceListPageState)null);
    if(owner.inlineSourcePage==null)owner.inlineSourcePage=new InlineSourcePage(owner);
    owner.inlineSourcePage.enter(source,refresh);
  }
  static void openHistory(MainActivity owner,Models.Source source,BrowserHistory.Entry entry){
    if(owner.pageKind!=1||owner.sourceGrid==null)owner.showSources((MainActivity.SourceListPageState)null);
    if(owner.inlineSourcePage==null)owner.inlineSourcePage=new InlineSourcePage(owner);
    owner.inlineSourcePage.restoreHistory(entry);
    owner.inlineSourcePage.enter(source,false);
  }
    /** Rebuild each path level from its parent; never rehydrate saved Source.members. */
  void restoreHistory(BrowserHistory.Entry entry){
    historyRoute=new ArrayList<>(entry.routes);
    historyDepth=1;
    historyQuery=entry.query;
    historyScroll=entry.scroll;
  }

  private void advanceHistoryRestore(Level loaded){
    if(historyRoute==null)return;
    if(historyDepth<historyRoute.size()){
      BrowserHistory.Route target=historyRoute.get(historyDepth);
      for(Models.Source candidate:loaded.values){
        String key=candidate.id.isEmpty()?candidate.url:candidate.id;
        if(!key.equals(target.id)||!candidate.nodeId.equals(target.nodeId))continue;
        historyDepth++;
        enter(candidate,false);
        return;
      }
      historyRoute=null;
      owner.showNotice("历史列表层级已不存在："+target.title,true);
      owner.finishHistoryRestore(null);
      return;
    }
    loaded.query=historyQuery;
    loaded.scroll=historyScroll;
    historyRoute=null;
    display(loaded);
    final ScrollView scroll=owner.pageScroll;
    final int savedScroll=loaded.scroll;
    if(scroll!=null&&savedScroll>0)scroll.postDelayed(()->{
      if(owner.inlineSourcePage==this&&owner.pageScroll==scroll){
        scroll.scrollTo(0,savedScroll);
        if(owner.sourceListWindow!=null)owner.sourceListWindow.refresh();
      }
    },150L);
    BrowserHistory.Entry restored=owner.currentHistoryEntry();
    owner.finishHistoryRestore(restored==null?null:restored.withPosition(historyQuery,historyScroll));
  }

  private void attach(){
    if(shell==owner.root)return;
    shell=owner.root;
    breadcrumb=new HorizontalScrollView(owner);breadcrumb.setHorizontalScrollBarEnabled(false);
    breadcrumb.setContentDescription("列表路径");
    identity=owner.text("",12,owner.MUTED);
    identity.setMaxLines(3);identity.setEllipsize(android.text.TextUtils.TruncateAt.END);
    shell.addView(breadcrumb,1,new LinearLayout.LayoutParams(-1,owner.dp(42)));
    shell.addView(identity,2,new LinearLayout.LayoutParams(-1,-2));
  }
  private void capture(){
    if(levels.isEmpty()||owner.root!=shell)return;
    Level current=levels.get(levels.size()-1);
    current.query=owner.sourceFilter.getText().toString();current.scroll=owner.pageScroll.getScrollY();
    current.categories.clear();current.categories.addAll(owner.selectedSourceCategories);
  }
  void captureCurrent(){capture();}
  void restored(){if(collection()){owner.activeSource=levels.get(levels.size()-1).source;hydrated.remove(MainActivity.sourceKey(owner.activeSource));applied(owner.visibleSources);}}
  boolean collection(){return levels.size()>1&&levels.get(levels.size()-1).source.kind==Models.SOURCE_COMPOSITE;}
  View row(Models.Source source){
    Models.Item item=levels.get(levels.size()-1).items.get(MainActivity.sourceKey(source));
    return item==null?owner.sourceRow(source):owner.itemRow(item);
  }
  void applied(List<Models.Source> values){
    if(!collection())return;
    Level current=levels.get(levels.size()-1);List<Models.Item> items=new ArrayList<>();
    for(Models.Source source:values){Models.Item item=current.items.get(MainActivity.sourceKey(source));if(item!=null)items.add(item);}
    owner.current=items;owner.folderItems=new ArrayList<>(current.items.values());owner.itemsGrid=owner.sourceGrid;
    int preload=owner.sourceListDisplayCount();
    if(preload==0)preload=Math.max(8,owner.safeContentHeight()/owner.dp(120)*owner.itemColumns());
    owner.visible=Math.min(items.size(),preload);
    if(!items.isEmpty()&&hydrated.add(MainActivity.sourceKey(current.source)))owner.hydrateVisibleCompositeMembers(owner.navigationSession,MainActivity.sourceKey(current.source),current.source);
  }
  void updateItem(Models.Item item){
    Level level=levels.get(levels.size()-1);Models.Item stored=level.items.get(item.url);Models.Source row=level.rows.get(item.url);
    if(stored==null||row==null)return;
    stored.title=item.title;stored.iconUrl=item.iconUrl;stored.description=item.description;stored.size=item.size;stored.time=item.time;stored.error=item.error;
    row.title=item.title;row.iconUrl=item.iconUrl;row.description=item.description;owner.sourceDataRevision++;
    if(owner.sourceListWindow!=null){View view=owner.sourceListWindow.mounted.get(item.url);if(view!=null)owner.bindItemCard(view,stored);}
  }
  String itemKey(Models.Source source){Models.Item item=levels.get(levels.size()-1).items.get(MainActivity.sourceKey(source));return item==null?MainActivity.sourceKey(source):item.url;}
  void refreshCurrent(){if(levels.size()>1){Level current=levels.get(levels.size()-1);hydrated.remove(MainActivity.sourceKey(current.source));load(current,false);}}
    private void enter(Models.Source source,boolean refresh){
    owner.recordCurrentHistory();
    attach();
    if(levels.isEmpty())levels.add(new Level(null,owner.sourcePageSources));
    capture();
    if(refresh&&levels.size()>1&&MainActivity.sourceKey(source).equals(MainActivity.sourceKey(levels.get(levels.size()-1).source))){load(levels.get(levels.size()-1),true);return;}
    Models.Source root=roots.get(MainActivity.sourceKey(source));
    if(root!=null&&source.kind==Models.SOURCE_COMPOSITE&&source.members.isEmpty())source.members.addAll(root.members);
    Level next=new Level(source,new ArrayList<>());levels.add(next);hydrated.remove(MainActivity.sourceKey(source));display(next);load(next,refresh);
  }
  private void load(Level level,boolean refresh){
    final int ticket=++request,session=owner.navigationSession;
    owner.sourceHeading.setText("正在读取 "+level.source.title+"…");
    owner.io.execute(()->{
      try{
        List<Models.Source> values;Level loaded=new Level(level.source,Collections.emptyList());
        if(level.source.kind==Models.SOURCE_REPOSITORY)values=owner.core.repositorySources(level.source,refresh);
        else{
          Models.Folder folder=owner.core.compositeSnapshot(level.source);
          values=children(loaded,folder.items);
        }
        owner.runOnUiThread(()->{
          if(ticket!=request||session!=owner.navigationSession||owner.inlineSourcePage!=this||owner.root!=shell)return;
          level.items.clear();level.items.putAll(loaded.items);level.rows.clear();level.rows.putAll(loaded.rows);level.values=values;display(level);advanceHistoryRestore(level);
        });
      }catch(Exception error){owner.runOnUiThread(()->{
        if(ticket!=request||session!=owner.navigationSession||owner.inlineSourcePage!=this)return;
        owner.sourceHeading.setText("读取失败，点击路径旁刷新按钮重试");owner.showNotice(owner.friendlyError(error),true);
        if(historyRoute!=null){historyRoute=null;owner.finishHistoryRestore(null);}
      });}
    });
  }
  private List<Models.Source> children(Level level,List<Models.Item> items){
    Models.Source parent=level.source;level.items.clear();
    List<Models.Source> values=new ArrayList<>(items.size());
    for(Models.Item item:items){
      Models.Source child=child(parent,item);
      level.items.put(MainActivity.sourceKey(child),item);
      level.rows.put(MainActivity.sourceKey(child),child);
      if(child.kind==Models.SOURCE_COMPOSITE)roots.put(MainActivity.sourceKey(child),parent);
      values.add(child);
    }
    return values;
  }
  static Models.Source child(Models.Source parent,Models.Item item){
    Models.Source child=new Models.Source();child.title=item.title;child.url=item.url;child.password=item.password;
    child.iconUrl=item.iconUrl;child.description=item.description;child.categories.addAll(item.categories);
    child.parentId=LanzouCore.sourceId(parent);child.repositoryUrl=parent.repositoryUrl;child.childDirectory=item.folder;
    child.originPath=parent.originPath.isEmpty()?parent.title:parent.originPath+" / "+parent.title;
    if(item.folder&&!item.folderId.isEmpty()&&!item.folderId.startsWith("remote:")){
      child.kind=Models.SOURCE_COMPOSITE;child.id=parent.id;child.nodeId=item.folderId;child.url=parent.url;
      child.publisher=parent.publisher;child.avatarUrl=parent.avatarUrl;
    }else if(item.folder){child.kind=Models.SOURCE_OFFICIAL;child.id=item.url;}
    else{
      child.kind=LanzouCore.isLanzouSourceUrl(item.url)?Models.SOURCE_SINGLE:Models.SOURCE_WEB;child.id=item.url;
      if(child.kind==Models.SOURCE_SINGLE){
        Models.SourceMember member=new Models.SourceMember();member.kind=Models.MEMBER_FILE;member.id=item.url;
        member.url=item.url;member.title=item.title;member.password=item.password;member.iconUrl=item.iconUrl;
        member.description=item.description;member.size=item.size;member.time=item.time;member.categories.addAll(item.categories);
        child.members.add(member);
      }
    }
    return child;
  }
  private void display(Level level){
    if(owner.selectionMode)owner.exitSelection();
    if(owner.sourceSelectionMode)owner.exitSourceSelection();
    owner.sourcePageSources=level.values;owner.sourceDataRevision++;
    owner.sourcePageRepository=level.source!=null&&level.source.kind==Models.SOURCE_REPOSITORY?level.source.url:"";
    owner.sourcePageRebuildAction=levels.size()>1?this::rebuild:null;
    owner.systemBackAction=levels.size()>1?this::back:null;
    owner.activeSource=level.source!=null&&level.source.kind==Models.SOURCE_COMPOSITE?level.source:null;owner.clearFolderTrail();owner.selectedSourceCategories.clear();owner.selectedSourceCategories.addAll(level.categories);
    owner.activeSourceCategory=level.categories.size()==1?level.categories.get(0):"全部";
    owner.rebuildCategoryMemberships(level.values);owner.renderSourceCategories(level.values);
    if(!level.query.contentEquals(owner.sourceFilter.getText()))owner.sourceFilter.setText(level.query);
    if(owner.sourceListFilterRunnable!=null)owner.ui.removeCallbacks(owner.sourceListFilterRunnable);
    owner.renderSources(owner.sourceGrid,level.values,level.query,owner.sourceHeading);
    if(owner.motionEnabled()){owner.sourceGrid.animate().cancel();owner.sourceGrid.setAlpha(0f);owner.sourceGrid.animate().alpha(1f).setDuration(190).start();}
    owner.pageScroll.scrollTo(0,level.scroll);owner.noteHistoryRoute();
    renderPath();
  }
  private void renderPath(){
    LinearLayout row=new LinearLayout(owner);row.setGravity(Gravity.CENTER_VERTICAL);
    for(int i=0;i<levels.size();i++){
      final int depth=i;Level level=levels.get(i);
      Button part=owner.toolbarTextButton(level.source==null?"列表":level.source.title);
      part.setContentDescription("切换列表路径 "+part.getText());part.setOnClickListener(v->go(depth));
      row.addView(part,new LinearLayout.LayoutParams(-2,-1));
    }
    ImageButton refresh=owner.iconButton(R.drawable.ic_refresh,"刷新当前源");
    refresh.setOnClickListener(v->{if(levels.size()>1)load(levels.get(levels.size()-1),true);});
    if(levels.size()>1)row.addView(refresh,new LinearLayout.LayoutParams(owner.dp(44),-1));
    Level current=levels.get(levels.size()-1);
    if(current.source!=null)identity.setText(current.source.publisher+(current.source.repositoryVersion.isEmpty()?"":" · "+current.source.repositoryVersion)+(current.source.description.isEmpty()?"":"\n"+current.source.description));
    else identity.setText("");
    boolean wide=owner.getResources().getConfiguration().orientation==android.content.res.Configuration.ORIENTATION_LANDSCAPE;
    identity.setVisibility(wide||identity.length()==0?View.GONE:View.VISIBLE);
    if(wide&&identity.length()>0){TextView metadata=owner.text(identity.getText().toString().replace('\n',' '),11,owner.MUTED);metadata.setGravity(Gravity.CENTER_VERTICAL);row.addView(metadata,new LinearLayout.LayoutParams(-2,-1));}
    breadcrumb.removeAllViews();breadcrumb.addView(row);breadcrumb.setVisibility(levels.size()>1?View.VISIBLE:View.GONE);
    breadcrumb.post(()->breadcrumb.fullScroll(View.FOCUS_RIGHT));
  }
  void reflow(){if(shell==owner.root)renderPath();}
  private void back(){go(levels.size()-2);}
  boolean navigateTo(Models.Source source){
    String id=MainActivity.sourceKey(source);
    for(int i=0;i<levels.size();i++)if(levels.get(i).source!=null&&id.equals(MainActivity.sourceKey(levels.get(i).source))){go(i);return true;}
    return false;
  }
  private void go(int depth){
    if(depth<0||depth>=levels.size()-1)return;
    owner.recordCurrentHistory();request++;capture();while(levels.size()>depth+1)levels.remove(levels.size()-1);
    Level level=levels.get(depth);display(level);
    if(depth==0&&level.values.isEmpty())owner.loadSourcePageSnapshot(owner.navigationSession,level.values,owner.sourceGrid,owner.sourceFilter,owner.sourceHeading);
  }
  private void rebuild(){
    capture();owner.showSources((MainActivity.SourceListPageState)null);owner.inlineSourcePage=this;attach();display(levels.get(levels.size()-1));
  }
}
