package cc.nkbr.lanzouplus;

import android.app.AlertDialog;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.content.res.ColorStateList;
import android.graphics.Color;
import android.graphics.drawable.ColorDrawable;
import android.os.Bundle;
import android.os.Environment;
import android.text.Editable;
import android.text.InputType;
import android.text.TextWatcher;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.*;
import java.io.*;
import java.util.*;

/** Independent file workspace shared by the ordinary and premium cloud providers. */
final class CloudManagement {
  private final MainActivity owner;
  private final CloudFilesClient client;
  private final CloudSyncUi syncUi;
  private CloudFilesClient.Provider provider=CloudFilesClient.Provider.ORDINARY;
  private final List<String> folderIds=new ArrayList<>(),folderNames=new ArrayList<>();
  private final List<CloudFilesClient.CloudItem> items=new ArrayList<>();
  private final Set<String> selected=new LinkedHashSet<>();
  private final List<View> controls=new ArrayList<>();
  private ListView list;
  private LinearLayout breadcrumbs;
  private TextView status;
  private EditText filterField;
  private LinearLayout selectionBar;
  private TextView selectionSummary;
  private Button selectionAll;
  private FileAdapter adapter;
  private String query="";
  private int page;
  private int listGeneration;
  private boolean mutating,listLoading,appendLoading,hasMore;
  private boolean recycleView;
  private CloudRecyclePage recyclePage;
  private Bundle recycleState;
  private String failure="";
  private static final int CLOUD_PAGE_SIZE=50;
  interface Work { String run()throws Exception; }
  CloudManagement(MainActivity owner){
    this.owner=owner;client=CloudFilesClient.shared(owner);syncUi=new CloudSyncUi(owner,client);
    client.setVerificationPrompt(url->{
      java.util.concurrent.CountDownLatch finished=new java.util.concurrent.CountDownLatch(1);
      java.util.concurrent.atomic.AtomicBoolean passed=new java.util.concurrent.atomic.AtomicBoolean();
      owner.runOnUiThread(()->{
        if(owner.activityClosing()){finished.countDown();return;}
        owner.launchLanzouVerification(url,()->{passed.set(true);finished.countDown();},finished::countDown);
        if(!owner.verificationLaunching)finished.countDown();
      });
      while(!finished.await(1,java.util.concurrent.TimeUnit.SECONDS))if(owner.activityClosing())return false;
      return passed.get();
    });
    if(owner.getSharedPreferences("cloud_management",0).getBoolean("premium",false))provider=CloudFilesClient.Provider.PREMIUM;
  }
  void saveState(Bundle state){syncUi.saveState(state);state.putBoolean("cloudPremium",provider==CloudFilesClient.Provider.PREMIUM);state.putStringArrayList("cloudIds",new ArrayList<>(folderIds));state.putStringArrayList("cloudNames",new ArrayList<>(folderNames));state.putString("cloudQuery",query);state.putBoolean("cloudRecycleView",recycleView);if(recyclePage!=null)recyclePage.saveState(state);}
  void restoreState(Bundle state){if(state==null)return;syncUi.restoreState(state);provider=state.getBoolean("cloudPremium",false)?CloudFilesClient.Provider.PREMIUM:CloudFilesClient.Provider.ORDINARY;folderIds.clear();folderNames.clear();if(state.getStringArrayList("cloudIds")!=null)folderIds.addAll(state.getStringArrayList("cloudIds"));if(state.getStringArrayList("cloudNames")!=null)folderNames.addAll(state.getStringArrayList("cloudNames"));query=state.getString("cloudQuery","");recycleView=state.getBoolean("cloudRecycleView",false);recycleState=new Bundle();recycleState.putInt("cloudRecycleFirst",state.getInt("cloudRecycleFirst",0));recycleState.putInt("cloudRecycleTop",state.getInt("cloudRecycleTop",0));}
  boolean onActivityResult(int request,int result,Intent data){return syncUi.onActivityResult(request,result,data);}
  private String folder(){return folderIds.isEmpty()?"-1":folderIds.get(folderIds.size()-1);}
  void show(){
    if(recycleView){showRecycle();return;}
    owner.pageDirection=1;owner.base();owner.pageKind=7;owner.activeSource=null;owner.clearFolderTrail();owner.systemBackAction=this::back;
    listGeneration++;listLoading=false;appendLoading=false;
    owner.primaryHeader("管理");controls.clear();
    ImageButton back=owner.iconButton(R.drawable.ic_back,"返回主页");back.setOnClickListener(v->owner.navigateHome());owner.pageHeaderRow.addView(back,0,new LinearLayout.LayoutParams(owner.dp(48),owner.dp(48)));
    ImageButton accountSettings=owner.iconButton(R.drawable.ic_settings,"云盘账号设置");accountSettings.setOnClickListener(v->owner.openCloudAccountSettings());owner.pageHeaderRow.addView(accountSettings);controls.add(accountSettings);
    ImageButton refresh=owner.iconButton(R.drawable.ic_refresh,"刷新云盘文件");refresh.setOnClickListener(v->load(false,true));owner.pageHeaderRow.addView(refresh);controls.add(refresh);
    ImageButton entry=owner.iconButton(R.drawable.ic_add,"上传文件或新建文件夹");entry.setOnClickListener(v->entryMenu());owner.pageHeaderRow.addView(entry);controls.add(entry);
    ImageButton uploads=owner.iconButton(R.drawable.ic_history,"上传列表");uploads.setOnClickListener(v->syncUi.showUploads());owner.pageHeaderRow.addView(uploads);controls.add(uploads);
    ImageButton sync=owner.iconButton(R.drawable.ic_add,"同步管理");sync.setOnClickListener(v->syncUi.show(provider,folder(),folderNames.isEmpty()?"根目录":folderNames.get(folderNames.size()-1)));owner.pageHeaderRow.addView(sync);controls.add(sync);
    LinearLayout providers=new LinearLayout(owner);
    providers.setGravity(Gravity.CENTER_VERTICAL);
    for(CloudFilesClient.Provider value:CloudFilesClient.Provider.values()){
      String label=value==CloudFilesClient.Provider.ORDINARY?"普通蓝奏云":"蓝奏云优享版";
      View tab=owner.underlineFilter(label,value==provider,()->{
        if(mutating||provider==value)return;
        provider=value;folderIds.clear();folderNames.clear();items.clear();selected.clear();query="";page=0;
        owner.getSharedPreferences("cloud_management",0).edit().putBoolean("premium",value==CloudFilesClient.Provider.PREMIUM).apply();
        show();
      });
      providers.addView(tab,new LinearLayout.LayoutParams(-2,owner.dp(46)));
      controls.add(tab);
    }
    HorizontalScrollView providerScroll=new HorizontalScrollView(owner);
    providerScroll.setHorizontalScrollBarEnabled(false);
    providerScroll.addView(providers);
    FrameLayout searchBox=new FrameLayout(owner);searchBox.setBackground(owner.shape(owner.SURFACE,14));
    filterField=owner.sourceInput("搜索当前文件夹",android.text.InputType.TYPE_CLASS_TEXT);filterField.setSingleLine(true);filterField.setText(query);
    filterField.setBackgroundColor(Color.TRANSPARENT);filterField.setPadding(owner.dp(14),0,owner.dp(12),0);
    searchBox.addView(filterField,new FrameLayout.LayoutParams(-1,-1));
    filterField.addTextChangedListener(new TextWatcher(){public void beforeTextChanged(CharSequence s,int a,int b,int c){}public void afterTextChanged(Editable e){}public void onTextChanged(CharSequence s,int a,int b,int c){query=s.toString();if(adapter!=null)update();if(list!=null)list.post(CloudManagement.this::loadMoreIfViewportFilled);}});
    owner.root.addView(searchBox,new LinearLayout.LayoutParams(-1,owner.dp(48)));
    HorizontalScrollView pathScroll=new HorizontalScrollView(owner);pathScroll.setHorizontalScrollBarEnabled(false);breadcrumbs=new LinearLayout(owner);pathScroll.addView(breadcrumbs);renderBreadcrumbs();
    status=owner.text("",12,owner.MUTED);status.setSingleLine(true);
    status.setEllipsize(android.text.TextUtils.TruncateAt.END);status.setMaxWidth(owner.dp(150));
    status.setGravity(Gravity.END|Gravity.CENTER_VERTICAL);status.setPadding(owner.dp(8),0,owner.dp(2),0);
    LinearLayout pathRow=new LinearLayout(owner);pathRow.setGravity(Gravity.CENTER_VERTICAL);
    pathRow.addView(pathScroll,new LinearLayout.LayoutParams(0,owner.dp(48),1));
    LinearLayout.LayoutParams statusParams=new LinearLayout.LayoutParams(-2,owner.dp(48));statusParams.setMargins(owner.dp(8),0,0,0);
    pathRow.addView(status,statusParams);
    owner.root.addView(pathRow,new LinearLayout.LayoutParams(-1,owner.dp(48)));
    LinearLayout providerRow=new LinearLayout(owner);providerRow.setGravity(Gravity.CENTER_VERTICAL);
    providerRow.addView(providerScroll,new LinearLayout.LayoutParams(0,owner.dp(48),1));
    ImageButton recycle=owner.iconButton(R.drawable.ic_delete_record,"打开当前云盘回收站");
    recycle.setOnClickListener(v->showRecycle());controls.add(recycle);
    providerRow.addView(recycle,new LinearLayout.LayoutParams(owner.dp(48),owner.dp(48)));
    owner.root.addView(providerRow,new LinearLayout.LayoutParams(-1,owner.dp(48)));
    list=new ListView(owner);list.setDivider(new android.graphics.drawable.ColorDrawable(owner.DIV));list.setDividerHeight(owner.dp(1));
    list.setItemsCanFocus(false);
    adapter=new FileAdapter();
    list.setAdapter(adapter);
    android.widget.FrameLayout body=new android.widget.FrameLayout(owner);body.addView(list,new android.widget.FrameLayout.LayoutParams(-1,-1));
    LinearLayout empty=new LinearLayout(owner);empty.setOrientation(LinearLayout.VERTICAL);empty.setGravity(Gravity.CENTER);empty.addView(owner.text("此目录暂无项目；未登录时请前往设置添加账号",13,owner.MUTED));
    Button emptyUpload=owner.button("上传到当前目录");emptyUpload.setOnClickListener(v->uploadMenu());empty.addView(emptyUpload,new LinearLayout.LayoutParams(-2,owner.dp(48)));controls.add(emptyUpload);
    Button loginAction=owner.button("前往设置登录");loginAction.setOnClickListener(v->owner.openCloudAccountSettings());empty.addView(loginAction,new LinearLayout.LayoutParams(-2,owner.dp(48)));controls.add(loginAction);body.addView(empty,new android.widget.FrameLayout.LayoutParams(-1,-1));list.setEmptyView(empty);owner.root.addView(body,new LinearLayout.LayoutParams(-1,0,1));
    list.setOnItemClickListener((p,v,position,id)->{
      if(mutating)return;
      CloudFilesClient.CloudItem item=itemAt(position);
      if(item==null)return;
      if(!selected.isEmpty()){toggle(item);return;}
      if(item.folder)openFolder(item);else if(CloudImageGallery.isImage(item))CloudImageGallery.show(owner,client,provider,new ArrayList<>(items),item);else if(CloudImageGallery.isArchive(item))CloudImageGallery.showArchive(owner,client,provider,item,()->showFileDetails(item));else showFileDetails(item);
    });
    list.setOnItemLongClickListener((p,v,position,id)->{
      if(mutating)return false;
      CloudFilesClient.CloudItem item=itemAt(position);
      if(item==null)return false;
      toggle(item);
      return true;
    });
    list.setOnScrollListener(new AbsListView.OnScrollListener(){
      public void onScrollStateChanged(AbsListView view,int state){}
      public void onScroll(AbsListView view,int firstVisible,int visibleCount,int totalCount){
        if(totalCount>0&&firstVisible+visibleCount>=totalCount-2)loadMore();
      }
    });
    selectionSummary=owner.text("已选 0",11,owner.TEXT);
    selectionAll=owner.toolbarTextButton("全选");selectionAll.setOnClickListener(v->{
      List<CloudFilesClient.CloudItem> visible=adapter.visible();boolean all=!visible.isEmpty();
      for(CloudFilesClient.CloudItem item:visible)if(!selected.contains(item.id)){all=false;break;}
      for(CloudFilesClient.CloudItem item:visible)if(all)selected.remove(item.id);else selected.add(item.id);
      update();
    });
    ImageButton move=owner.iconButton(R.drawable.ic_folder_open,"移动所选项目");move.setOnClickListener(v->target(selectedItems(),false));
    ImageButton copy=owner.iconButton(R.drawable.ic_copy,"复制所选项目");copy.setOnClickListener(v->target(selectedItems(),true));
    ImageButton download=owner.iconButton(R.drawable.ic_download,"下载所选项目");download.setOnClickListener(v->download(selectedItems()));
    ImageButton share=owner.iconButton(R.drawable.ic_share,"分享所选项目");share.setOnClickListener(v->shareSelected(selectedItems()));
    ImageButton delete=owner.iconButton(R.drawable.ic_delete_file,"删除所选项目");delete.setOnClickListener(v->remove(selectedItems()));
    ImageButton close=owner.iconButton(R.drawable.ic_close,"退出多选");close.setOnClickListener(v->{selected.clear();update();});
    controls.add(selectionAll);controls.add(move);controls.add(copy);controls.add(download);controls.add(share);controls.add(delete);controls.add(close);
    selectionBar=owner.makeSelectionBar(selectionSummary,selectionAll,56,48,move,copy,download,share,delete,close);
    selectionBar.setContentDescription("云盘文件多选操作");selectionBar.setVisibility(View.GONE);
    update();load(false);
  }
  private void showRecycle(){
    if(mutating)return;
    listGeneration++;listLoading=false;appendLoading=false;recycleView=true;
    if(recyclePage==null){
      final CloudFilesClient.Provider target=provider;
      recyclePage=new CloudRecyclePage(owner,()->{synchronized(client){client.selectProvider(target);return client.recycleBin();}},
          ()->{recycleView=false;recyclePage=null;recycleState=null;show();});
      recyclePage.restoreState(recycleState);
    }
    recyclePage.show();
  }
  private void back(){
    if(!selected.isEmpty()){selected.clear();update();return;}
    if(!folderIds.isEmpty()){jump(folderIds.size()-2);return;}
    owner.navigateHome();
  }
  private void jump(int level){
    if(mutating)return;
    while(folderIds.size()>level+1){folderIds.remove(folderIds.size()-1);folderNames.remove(folderNames.size()-1);}
    clearFilter();
    clearFolderView();
    load(false);
  }
  private void openFolder(CloudFilesClient.CloudItem item){
    if(item==null||mutating)return;
    String id=item.id==null?"":item.id.trim();
    if(id.isEmpty()){owner.showNotice("该文件夹缺少标识，无法进入",true);return;}
    folderIds.add(id);folderNames.add(item.name);
    clearFilter();
    clearFolderView();
    list.setSelection(0);
    load(false);
  }
  private void clearFolderView(){
    items.clear();selected.clear();hasMore=false;page=0;failure="";
    if(adapter!=null)adapter.refilter();
    update();
  }
  private void clearFilter(){
    query="";
    if(filterField!=null&&filterField.getText().length()>0)filterField.setText("");
  }
  private CloudFilesClient.CloudItem itemAt(int position){
    if(position<0||adapter==null)return null;
    List<CloudFilesClient.CloudItem> visible=adapter.visible();
    return position<visible.size()?visible.get(position):null;
  }
  private void entryMenu(){
    String[] options={"上传文件","新建文件夹"};
    AlertDialog dialog=new AlertDialog.Builder(owner).setTitle("添加到当前目录").setItems(options,(d,which)->{if(which==0)uploadMenu();else createFolder();}).setNegativeButton("取消",null).create();owner.showRounded(dialog);
  }
  private void renderBreadcrumbs(){
    if(breadcrumbs==null)return;
    List<Object> state=new ArrayList<>(folderNames);state.add(mutating);
    if(state.equals(breadcrumbs.getTag()))return;
    breadcrumbs.setTag(state);breadcrumbs.removeAllViews();
    Button root=owner.toolbarTextButton("根目录");root.setOnClickListener(v->jump(-1));root.setOnLongClickListener(v->{showBreadcrumbChildren(-1,v);return true;});root.setContentDescription("根目录，点击进入，长按选择下级目录");root.setEnabled(!mutating);breadcrumbs.addView(root);
    for(int i=0;i<folderNames.size();i++){final int level=i;Button crumb=owner.toolbarTextButton("› "+folderNames.get(i));crumb.setOnClickListener(v->jump(level));crumb.setOnLongClickListener(v->{showBreadcrumbChildren(level,v);return true;});crumb.setContentDescription(folderNames.get(i)+"，点击进入，长按选择下级目录");crumb.setEnabled(!mutating);breadcrumbs.addView(crumb);}
  }
  private void showBreadcrumbChildren(int level,View anchor){
    if(mutating)return;
    String directory=level<0?"-1":folderIds.get(level);
    CloudFilesClient.Provider requested=provider;int generation=listGeneration;
    List<CloudFilesClient.CloudItem> choices=new ArrayList<>();
    LinearLayout panel=new LinearLayout(owner);panel.setOrientation(LinearLayout.VERTICAL);panel.setPadding(owner.dp(6),owner.dp(5),owner.dp(6),owner.dp(5));panel.setBackground(owner.solidShape(owner.SURFACE,16));
    TextView hint=owner.text("正在读取目录…",12,owner.MUTED);hint.setPadding(owner.dp(10),0,owner.dp(10),0);panel.addView(hint,new LinearLayout.LayoutParams(-1,owner.dp(36)));
    ListView options=new ListView(owner);options.setDividerHeight(0);
    BaseAdapter picker=new BaseAdapter(){
      public int getCount(){return choices.size();}public Object getItem(int position){return choices.get(position);}public long getItemId(int position){return position;}
      public View getView(int position,View recycled,ViewGroup parent){TextView row=recycled instanceof TextView?(TextView)recycled:owner.text("",13,owner.TEXT);row.setGravity(Gravity.CENTER_VERTICAL);row.setPadding(owner.dp(14),0,owner.dp(14),0);row.setMinHeight(owner.dp(46));row.setText(choices.get(position).name);row.setContentDescription("切换到 "+choices.get(position).name);return row;}
    };
    options.setAdapter(picker);panel.addView(options,new LinearLayout.LayoutParams(-1,0,1));
    int width=Math.min(owner.dp(330),Math.max(owner.dp(220),owner.safeContentWidth()-owner.dp(32)));
    PopupWindow popup=new PopupWindow(panel,width,owner.dp(244),true);popup.setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));popup.setOutsideTouchable(true);popup.setClippingEnabled(true);popup.setElevation(owner.dp(10));
    java.util.concurrent.atomic.AtomicBoolean active=new java.util.concurrent.atomic.AtomicBoolean(true);popup.setOnDismissListener(()->active.set(false));
    options.setOnItemClickListener((parent,view,position,id)->{
      if(position<0||position>=choices.size())return;
      CloudFilesClient.CloudItem item=choices.get(position);popup.dismiss();
      while(folderIds.size()>level+1){folderIds.remove(folderIds.size()-1);folderNames.remove(folderNames.size()-1);}
      folderIds.add(item.id);folderNames.add(item.name);clearFilter();clearFolderView();list.setSelection(0);load(false);
    });
    popup.showAsDropDown(anchor);
    owner.io.execute(()->{
      List<CloudFilesClient.CloudItem> loaded=new ArrayList<>();Set<String> seen=new HashSet<>();
      try{
        CloudFilesClient.CloudPage cached=client.cachedFolder(requested,directory,1,CLOUD_PAGE_SIZE);
        if(cached!=null){for(CloudFilesClient.CloudItem item:cached.items)if(item.folder&&seen.add(item.id))loaded.add(item);List<CloudFilesClient.CloudItem> initial=new ArrayList<>(loaded);owner.runOnUiThread(()->{if(active.get()&&owner.pageKind==7&&generation==listGeneration){choices.clear();choices.addAll(initial);picker.notifyDataSetChanged();}});}
        loaded.clear();seen.clear();Set<String> seenItems=new HashSet<>();int next=1;
        while(active.get()){
          CloudFilesClient.CloudPage pageResult;synchronized(client){client.selectProvider(requested);pageResult=client.listFolder(directory,next,CLOUD_PAGE_SIZE);}
          int before=seenItems.size();for(CloudFilesClient.CloudItem item:pageResult.items){seenItems.add(item.id);if(item.folder&&seen.add(item.id))loaded.add(item);}
          List<CloudFilesClient.CloudItem> current=new ArrayList<>(loaded);boolean more=pageResult.hasMore&&!pageResult.items.isEmpty()&&seenItems.size()>before;
          owner.runOnUiThread(()->{if(active.get()&&owner.pageKind==7&&generation==listGeneration){choices.clear();choices.addAll(current);picker.notifyDataSetChanged();hint.setText(more?"正在读取目录…":current.isEmpty()?"此目录没有下级目录":"选择下级目录");}});
          if(!more)break;next++;
        }
      }catch(Exception error){String message=owner.friendlyError(error);owner.runOnUiThread(()->{if(active.get()&&owner.pageKind==7&&generation==listGeneration)hint.setText("目录读取失败："+message);});}
    });
  }
  private void update(){
    renderBreadcrumbs();
    if(adapter!=null)adapter.refilter();
    boolean selecting=!selected.isEmpty();
    if(selectionBar!=null)selectionBar.setVisibility(selecting?View.VISIBLE:View.GONE);
    if(selectionSummary!=null)selectionSummary.setText("已选 "+selected.size());
    if(selectionAll!=null&&adapter!=null){boolean all=!adapter.visible().isEmpty();for(CloudFilesClient.CloudItem item:adapter.visible())if(!selected.contains(item.id)){all=false;break;}selectionAll.setText(all?"取消全选":"全选");}
    for(View control:controls)control.setEnabled(!mutating);
    if(status!=null){
      String summary=mutating?"处理中":!failure.isEmpty()?failure:items.size()+" 个项目";
      if(!selected.isEmpty())summary="已选 "+selected.size()+" 个";
      if(appendLoading)summary=summary+" · 正在加载更多…";
      status.setText(summary);
    }
  }
  private void work(String message,Work action,boolean refresh){
    if(mutating)return;mutating=true;failure="";update();final CloudFilesClient.Provider requested=provider;
    owner.io.execute(()->{String result="",error="";boolean loginRequired=false;try{synchronized(client){client.selectProvider(requested);result=action.run();if(refresh)client.clearFolderCache(requested);}}catch(CloudFilesClient.CloudException failed){loginRequired=failed.needsCredentials()||failed.sessionExpired();error=loginRequired&&message==null?"登录后查看私人文件":failed.getMessage();}catch(Exception failed){error=owner.friendlyError(failed);}final String done=result,problem=error;final boolean quietLogin=loginRequired&&message==null;owner.runOnUiThread(()->{mutating=false;failure=problem;if(owner.pageKind!=7)return;update();if(!problem.isEmpty()){if(!quietLogin)owner.showNotice(problem,true);}else{if(message!=null)owner.showNotice(done.isEmpty()?message:done,false);if(refresh)load(false);}});});
  }
  private void load(boolean append){load(append,false);}
  private void load(boolean append,boolean forceRefresh){
    if(append&&(listLoading||appendLoading||!hasMore||items.isEmpty()))return;
    final String directory=folder();
    final CloudFilesClient.Provider requested=provider;
    final int generation=append?listGeneration:++listGeneration;
    final int targetPage=append?page+1:1;
    final int appendStart=append?items.size():0;
    if(append)appendLoading=true;
    else{listLoading=true;appendLoading=false;}
    failure="";
    update();
    owner.io.execute(()->{
      String error="";
      boolean loginRequired=false;
      CloudFilesClient.CloudPage found=null;
      try{
        if(forceRefresh)client.clearFolderCache(requested);
        else{
          CloudFilesClient.CloudPage cached=client.cachedFolder(requested,directory,targetPage,CLOUD_PAGE_SIZE);
          if(cached!=null)owner.runOnUiThread(()->{
            if(generation!=listGeneration||owner.pageKind!=7)return;
            applyPage(cached,append,appendStart,false);update();
          });
        }
        synchronized(client){client.selectProvider(requested);found=client.listFolder(directory,targetPage,CLOUD_PAGE_SIZE);}
      }catch(CloudFilesClient.CloudException failed){
        loginRequired=failed.needsCredentials()||failed.sessionExpired();
        error=loginRequired?"登录后查看私人文件":failed.getMessage();
      }catch(Exception failed){
        error=owner.friendlyError(failed);
      }
      final CloudFilesClient.CloudPage result=found;
      final String problem=error;
      final boolean quietLogin=loginRequired;
      final boolean appended=append;
      owner.runOnUiThread(()->{
        if(generation!=listGeneration||owner.pageKind!=7)return;
        listLoading=false;appendLoading=false;
        if(result==null){
          failure=problem;
          update();
          if(!problem.isEmpty()&&!quietLogin)owner.showNotice(problem,true);
          return;
        }
        applyPage(result,appended,appendStart,true);
        update();
        list.post(CloudManagement.this::loadMoreIfViewportFilled);
      });
    });
  }
  private void applyPage(CloudFilesClient.CloudPage result,boolean append,int appendStart,boolean authoritative){
    if(!append)items.clear();
    else while(items.size()>appendStart)items.remove(items.size()-1);
    int before=items.size();Set<String> seen=new HashSet<>();
    for(CloudFilesClient.CloudItem item:items)seen.add(item.id);
    for(CloudFilesClient.CloudItem item:result.items)if(seen.add(item.id))items.add(item);
    page=result.page;hasMore=morePagesAvailable(result.hasMore,result.items,append,before,items.size());
    if(authoritative){Set<String> present=new HashSet<>();for(CloudFilesClient.CloudItem item:items)present.add(item.id);selected.retainAll(present);}
  }
  private void loadMoreIfViewportFilled(){
    if(mutating||!hasMore||listLoading||appendLoading||items.isEmpty())return;
    int total=list.getCount();
    if(total<=0)return;
    if(list.getLastVisiblePosition()>=total-1)load(true);
  }
  private void loadMore(){
    if(mutating||!hasMore||listLoading||appendLoading||items.isEmpty())return;
    load(true);
  }
  /** The ordinary endpoint reports info!=0 whenever a page has rows, so an empty page ends paging. */
  static boolean morePagesAvailable(boolean serverHasMore,List<CloudFilesClient.CloudItem> pageItems,
                                    boolean appended,int loadedBefore,int loadedAfter){
    if(!serverHasMore||pageItems==null||pageItems.isEmpty())return false;
    return !appended||loadedAfter>loadedBefore;
  }
  /**
   * Keeps a managed row tappable on the whole row while its own menu button still receives clicks.
   * Inline focusable children made ListView drop row taps, so the row blocks descendant focus and the
   * checkbox/menu stop taking focus without losing their click handlers.
   */
  static void applyRowFocusPolicy(ListView list,ViewGroup row,View check,View menu){
    if(list!=null)list.setItemsCanFocus(false);
    row.setDescendantFocusability(ViewGroup.FOCUS_BLOCK_DESCENDANTS);
    check.setFocusable(false);
    menu.setFocusable(false);
  }
  private void toggle(CloudFilesClient.CloudItem item){if(!selected.add(item.id))selected.remove(item.id);update();}
  private List<CloudFilesClient.CloudItem> selectedItems(){List<CloudFilesClient.CloudItem> out=new ArrayList<>();for(CloudFilesClient.CloudItem item:items)if(selected.contains(item.id))out.add(item);return out;}
  private void createFolder(){
    LinearLayout panel=panel();
    EditText name=input(panel,"文件夹名称",false),description=input(panel,"文件夹简介（可选）",false);
    AlertDialog dialog=new AlertDialog.Builder(owner).setTitle("新建文件夹").setView(panel).setNegativeButton("取消",null).setPositiveButton("创建",null).create();
    owner.showRounded(dialog);
    dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v->{
      String title=name.getText().toString().trim();
      if(title.isEmpty()){name.setError("请输入名称");return;}
      String detail=description.getText().toString();
      dialog.dismiss();
      String parent=folder();
      work("文件夹已创建",()->{client.createFolder(parent,title,detail);return "";},true);
    });
  }

  private void showFileDetails(CloudFilesClient.CloudItem item){
    LinearLayout panel=panel();
    panel.addView(owner.text("文件名："+item.name,14,owner.TEXT));
    panel.addView(owner.text("大小："+formatBytes(item.size)+" ("+item.size+" 字节)",13,owner.TEXT));
    if(!item.modifiedAt.isEmpty()){
      panel.addView(owner.text("修改时间："+item.modifiedAt,13,owner.MUTED));
    }
    if(item.downloads>0){
      panel.addView(owner.text("下载次数："+item.downloads,13,owner.MUTED));
    }
    if(item.hasPassword){
      panel.addView(owner.text("提取码：已设置提取码保护",13,owner.MUTED));
    }
    TextView descLabel=owner.text("简介：",13,owner.TEXT);
    descLabel.setPadding(0,owner.dp(8),0,owner.dp(2));
    panel.addView(descLabel);
    TextView desc=owner.text(item.description.isEmpty()?"暂无简介":item.description,12,item.description.isEmpty()?owner.MUTED:owner.TEXT);
    desc.setTextIsSelectable(true);
    panel.addView(desc);

    AlertDialog dialog=new AlertDialog.Builder(owner)
        .setTitle("文件详情")
        .setView(owner.limitedDialogScroll(panel,400))
        .setNegativeButton("关闭",null)
        .setNeutralButton("管理",(d,w)->itemMenu(item))
        .setPositiveButton("下载",(d,w)->download(Collections.singletonList(item)))
        .create();
    owner.showRounded(dialog);
  }

  private void itemMenu(CloudFilesClient.CloudItem item){
    List<String> labels=new ArrayList<>(Arrays.asList("分享","下载","重命名","移动","复制","设置密码","编辑简介","加入列表源","删除"));
    if(item.folder)labels.remove("下载");if(!item.folder&&provider==CloudFilesClient.Provider.ORDINARY)labels.add("自定义图标");if(!item.folder&&item.name.toLowerCase(Locale.ROOT).endsWith(".apk"))labels.add("智能换源（上传新版 APK）");
    AlertDialog dialog=new AlertDialog.Builder(owner).setTitle(item.name).setItems(labels.toArray(new String[0]),(d,which)->{switch(labels.get(which)){
      case "分享":share(item,false);break;case "加入列表源":share(item,true);break;case "下载":download(Collections.singletonList(item));break;
      case "重命名":editValue("重命名",item.name,value->work("名称已更新",()->{client.rename(item,value);return "";},true));break;
      case "移动":target(Collections.singletonList(item),false);break;case "复制":target(Collections.singletonList(item),true);break;
      case "设置密码":editValue("设置密码（留空取消密码）","",value->work("密码已更新",()->{client.setPassword(item,value);return "";},true));break;
      case "编辑简介":work(null,()->{String current=client.getDescription(item).description;owner.runOnUiThread(()->editValue("编辑简介",current,value->work("简介已更新",()->{client.setDescription(item,value);return "";},true)));return "";},false);break;
      case "删除":remove(Collections.singletonList(item));break;
      case "自定义图标":MarketFilePicker.chooseImage(owner,uri->localImage(uri,file->work("图标已更新",()->{client.setIcon(item.id,file.getAbsolutePath());return "";},true)));break;
      case "智能换源（上传新版 APK）":replaceApk(item);break;
    }}).setNegativeButton("取消",null).create();owner.showRounded(dialog);
  }
  private void share(CloudFilesClient.CloudItem item,boolean add){
    work(null,()->{
      CloudFilesClient.ShareInfo info=client.shareInfo(item);
      owner.runOnUiThread(()->{
        if(owner.pageKind!=7)return;
        if(add)MainActivity.favoriteWebPage(owner,info.shareUrl,item.name);
        else showShareDialog(item,info);
      });
      return "";
    },false);
  }
  private void showShareDialog(CloudFilesClient.CloudItem item,CloudFilesClient.ShareInfo info){
    LinearLayout panel=panel();
    addShareRow(panel,"分享链接",info.shareUrl,"暂无分享链接",info.shareUrl);
    addShareRow(panel,"提取码",info.password,"未设置提取码",info.password);
    if(!info.folder){
      addShareRow(panel,"临时直链",info.directUrl,"未取得临时直链",info.directUrl);
    }
    if(info.fragmented){
      addShareRow(panel,"分片外链",info.fragmentExternalLinks,"未取得完整分片外链",info.fragmentExternalLinks);
      String modeSummary="分片模式："+("archive_volume".equals(info.fragmentMode)?"压缩分卷":"字节拆分")+" · "+info.fragmentPartCount+" 片";
      addShareRow(panel,"分片模式/数量",modeSummary,"未知",modeSummary);
      Button dlBtn=owner.toolbarTextButton("下载原文件");
      dlBtn.setOnClickListener(v->downloadFragment(item));
      LinearLayout.LayoutParams btnParams=new LinearLayout.LayoutParams(-1,owner.dp(44));
      btnParams.setMargins(0,owner.dp(6),0,owner.dp(4));
      panel.addView(dlBtn,btnParams);
    }
    if(!info.description.isEmpty()){
      addShareRow(panel,"简介",info.description,"",info.description);
    }

    AlertDialog dialog=new AlertDialog.Builder(owner)
        .setTitle("分享 · "+item.name)
        .setView(owner.limitedDialogScroll(panel,460))
        .setNegativeButton("关闭",null)
        .setNeutralButton("加入列表源",(d,w)->MainActivity.favoriteWebPage(owner,info.shareUrl,item.name))
        .setPositiveButton("系统分享",(d,w)->{
          StringBuilder sb=new StringBuilder();
          sb.append(info.shareUrl);
          if(!info.password.isEmpty())sb.append("\n密码：").append(info.password);
          if(!info.directUrl.isEmpty())sb.append("\n直链：").append(info.directUrl);
          if(info.fragmented&&!info.fragmentExternalLinks.isEmpty())sb.append("\n分片外链：\n").append(info.fragmentExternalLinks);
          if(!info.description.isEmpty())sb.append("\n简介：").append(info.description);
          owner.shareText(sb.toString(),"分享云盘文件");
        })
        .create();
    owner.showRounded(dialog);
  }

  private void addShareRow(LinearLayout panel,String label,String value,String unavailableText,String copyValue){
    LinearLayout row=new LinearLayout(owner);
    row.setOrientation(LinearLayout.HORIZONTAL);
    row.setGravity(Gravity.CENTER_VERTICAL);
    row.setPadding(0,owner.dp(6),0,owner.dp(6));

    LinearLayout textCol=new LinearLayout(owner);
    textCol.setOrientation(LinearLayout.VERTICAL);
    TextView labelView=owner.text(label,12,owner.MUTED);
    textCol.addView(labelView);
    boolean hasValue=value!=null&&!value.trim().isEmpty();
    TextView valueView=owner.text(hasValue?value:unavailableText,13,hasValue?owner.TEXT:owner.MUTED);
    valueView.setTextIsSelectable(true);
    textCol.addView(valueView);
    row.addView(textCol,new LinearLayout.LayoutParams(0,-2,1));

    if(hasValue&&copyValue!=null&&!copyValue.isEmpty()){
      Button copyBtn=owner.toolbarTextButton("复制");
      copyBtn.setOnClickListener(v->copyToClipboard(label,copyValue));
      row.addView(copyBtn,new LinearLayout.LayoutParams(owner.dp(56),owner.dp(48)));
    }
    panel.addView(row);
  }

  private void copyToClipboard(String label,String text){
    if(text==null||text.isEmpty())return;
    ClipboardManager cm=(ClipboardManager)owner.getSystemService(Context.CLIPBOARD_SERVICE);
    if(cm!=null){
      cm.setPrimaryClip(ClipData.newPlainText(label,text));
      owner.showNotice(label+"已复制",false);
    }
  }

  private void shareSelected(List<CloudFilesClient.CloudItem> chosen){
    work(null,()->{
      StringBuilder links=new StringBuilder();
      for(CloudFilesClient.CloudItem item:chosen){
        CloudFilesClient.ShareInfo info=client.shareInfo(item);
        if(links.length()>0)links.append("\n\n");
        links.append(item.name).append("\n").append(info.shareUrl);
        if(!info.password.isEmpty())links.append(" 密码：").append(info.password);
        if(!info.directUrl.isEmpty())links.append("\n直链：").append(info.directUrl);
      }
      owner.runOnUiThread(()->owner.shareText(links.toString(),"分享云盘项目"));
      return "";
    },false);
  }

  private void remove(List<CloudFilesClient.CloudItem> chosen){
    confirm("删除所选项目？","将删除 "+chosen.size()+" 个云端项目。",()->work("所选项目已删除",()->{client.deleteMany(chosen);return "";},true));
  }

  private void download(List<CloudFilesClient.CloudItem> chosen){
    File directory=owner.selectedDownloadDirectory();
    work("下载完成",()->{
      int count=0;
      for(CloudFilesClient.CloudItem item:chosen){
          if(item.folder&&!client.shareInfo(item).fragmented)continue;
          CloudFilesClient.DownloadResult result=client.download(item,directory.getAbsolutePath()+File.separator);
          owner.runOnUiThread(()->owner.recordCloudDownload(item,result));
          count++;
      }
      return count==0?"所选项目没有可下载的文件":"已下载 "+count+" 个文件至 "+directory.getAbsolutePath();
    },false);
  }

  private void downloadFragment(CloudFilesClient.CloudItem item){
    File directory=owner.selectedDownloadDirectory();
    work("下载完成",()->{
      CloudFilesClient.DownloadResult result=client.download(item,directory.getAbsolutePath()+File.separator);
      owner.runOnUiThread(()->owner.recordCloudDownload(item,result));
      return "已下载原文件至 "+directory.getAbsolutePath();
    },false);
  }

  private void target(List<CloudFilesClient.CloudItem> chosen,boolean copy){
    work(null,()->{
      List<CloudFilesClient.CloudItem> targets=client.moveTargets();
      List<String> ids=new ArrayList<>(),names=new ArrayList<>();
      ids.add("-1");
      names.add("根目录");
      for(CloudFilesClient.CloudItem target:targets){
        if(target.folder&&!selected.contains(target.id)){
          ids.add(target.id);
          names.add(target.path.isEmpty()?target.name:target.path);
        }
      }
      owner.runOnUiThread(()->{
        AlertDialog dialog=new AlertDialog.Builder(owner).setTitle(copy?"复制到":"移动到").setItems(names.toArray(new String[0]),(d,index)->work(copy?"复制完成":"移动完成",()->{
          if(copy)for(CloudFilesClient.CloudItem item:chosen)client.copy(item,ids.get(index),owner.getCacheDir().getAbsolutePath());
          else client.moveMany(chosen,ids.get(index));
          return "";
        },true)).setNegativeButton("取消",null).create();
        owner.showRounded(dialog);
      });
      return "";
    },false);
  }

  private void uploadMenu(){
    LinearLayout panel=panel();boolean ordinary=provider==CloudFilesClient.Provider.ORDINARY;
    CheckBox wrap=new CheckBox(owner);wrap.setText("兼容包装（用于不支持的文件类型）");wrap.setTextColor(owner.TEXT);wrap.setButtonTintList(ColorStateList.valueOf(owner.PRIMARY));wrap.setEnabled(ordinary);panel.addView(wrap);
    CheckBox icon=new CheckBox(owner);icon.setText("提取 APK 软件图标");icon.setTextColor(owner.TEXT);icon.setButtonTintList(ColorStateList.valueOf(owner.PRIMARY));icon.setChecked(ordinary);icon.setEnabled(ordinary);panel.addView(icon);
    if(!ordinary)panel.addView(owner.text("优享版支持直接上传，暂不提供自定义图标和普通版兼容包装。",12,owner.MUTED));
    AlertDialog dialog=new AlertDialog.Builder(owner).setTitle("上传到当前目录").setView(owner.limitedDialogScroll(panel,380)).setNegativeButton("取消",null).create();
    String[] labels={"选择多个文件","选择 APK 并上传","上传文件夹","分片上传文件","智能换源（替换已上传的 APK）"};
    for(int i=0;i<labels.length;i++){
      final int action=i;Button button=owner.toolbarTextButton(labels[i]);panel.addView(button,new LinearLayout.LayoutParams(-1,owner.dp(48)));
      button.setOnClickListener(v->{
        dialog.dismiss();boolean w=wrap.isChecked(),image=icon.isChecked();
        if(action==0)pickLocalFiles(true,"",files->uploadFiles(files,w,image));
        else if(action==1)pickLocalFiles(false,".apk",files->uploadFiles(files,w,image));
        else if(action==2)localDirectory(file->{String target=folder();CloudFilesClient.Provider requested=provider;work("上传完成",()->{String account=client.currentAccountName(requested);try{recordManualUpload(account,requested,target,file,"上传中","");CloudFilesClient.DirectoryUploadResult result=client.uploadDirectory(target,file.getAbsolutePath(),CloudFilesClient.UploadOptions.defaults().compatibilityWrap(w).autoIcon(image));recordManualUpload(account,requested,target,file,"完成","");return "已上传 "+result.filesUploaded+" 个文件"+(result.warning.isEmpty()?"":"；"+result.warning);}catch(Exception error){recordManualUpload(account,requested,target,file,"失败","上传失败");throw error;}},true);});
        else if(action==3)pickLocalFiles(false,"",files->showFragmentUploadDialog(files.get(0),w,image));
        else smartSourceUpload();
      });
    }
    owner.showRounded(dialog);
  }

  private void uploadFiles(List<File> files,boolean wrap,boolean autoIcon){
    if(files.isEmpty())return;
    String target=folder();
    CloudFilesClient.Provider requested=provider;
    CloudFilesClient.UploadOptions options=CloudFilesClient.UploadOptions.defaults()
        .compatibilityWrap(wrap)
        .autoIcon(autoIcon);
    work("上传完成",()->{
      int success=0;
      List<String> warnings=new ArrayList<>();
      String account=client.currentAccountName(requested);
      for(File file:files){
        try {
          recordManualUpload(account,requested,target,file,"上传中","");
          CloudFilesClient.UploadResult result=client.uploadForAccount(requested,account,target,file.getAbsolutePath(),options);
          recordManualUpload(account,requested,target,file,"完成","");success++;
          if(!result.warning.isEmpty())warnings.add(file.getName()+"："+result.warning);
        } catch(Exception error){recordManualUpload(account,requested,target,file,"失败","上传失败");throw error;}
      }
      String message="已上传 "+success+" 个文件";
      return warnings.isEmpty()?message:message+"；"+Texts.join("；",warnings);
    },true);
  }

  private void recordManualUpload(String account,CloudFilesClient.Provider provider,String target,File file,String state,String message){
    new CloudSyncTasks(owner).recordManualUpload(account,provider==CloudFilesClient.Provider.PREMIUM?"premium":"ordinary",target,file.getName(),state,message,file.length());
  }
  private void showFragmentUploadDialog(File file,boolean wrap,boolean autoIcon){
    LinearLayout panel=panel();
    panel.addView(owner.text(file.getName()+" · "+formatBytes(file.length()),14,owner.TEXT));
    panel.addView(owner.text("分片形式",12,owner.MUTED));
    RadioGroup modes=new RadioGroup(owner);
    RadioButton bytes=radio(modes,"字节拆分"),archive=radio(modes,"压缩分卷");bytes.setChecked(true);panel.addView(modes);
    panel.addView(owner.text("分片策略",12,owner.MUTED));
    RadioGroup strategies=new RadioGroup(owner);
    RadioButton automatic=radio(strategies,"按账号确认的上传上限"),parts=radio(strategies,"指定分片数量"),size=radio(strategies,"指定单片大小");
    parts.setChecked(true);panel.addView(strategies);
    EditText parameter=input(panel,"分片数量（2～4096）",false);parameter.setInputType(InputType.TYPE_CLASS_NUMBER);parameter.setText("2");
    TextView summary=owner.text("",12,owner.MUTED);panel.addView(summary);
    Runnable update=()->{
      parameter.setVisibility(automatic.isChecked()?View.GONE:View.VISIBLE);
      parameter.setHint(size.isChecked()?"单片最大大小（MiB）":"分片数量（2～4096）");
      summary.setText(automatic.isChecked()?"仅使用账号提供的单文件上限；无法确认时，请选择手动策略。":archive.isChecked()?"先生成压缩数据，再按实际大小规划分卷。":"下载全部分片后可还原原文件。");
    };
    strategies.setOnCheckedChangeListener((g,id)->update.run());modes.setOnCheckedChangeListener((g,id)->update.run());update.run();
    AlertDialog dialog=new AlertDialog.Builder(owner).setTitle("分片上传").setView(owner.limitedDialogScroll(panel,440)).setNegativeButton("取消",null).setPositiveButton("开始上传",null).create();owner.showRounded(dialog);
    dialog.getButton(-1).setOnClickListener(v->{
      String mode=archive.isChecked()?"archive_volume":"byte_split";
      CloudFilesClient.UploadOptions options=CloudFilesClient.UploadOptions.defaults().compatibilityWrap(wrap).autoIcon(autoIcon);
      if(automatic.isChecked())options.fragmentAutomatic(mode);
      else try{
        long value=Long.parseLong(parameter.getText().toString().trim());
        if(size.isChecked()){
          if(value<=0||value>Integer.MAX_VALUE)throw new NumberFormatException();
          options.fragmentBytes(mode,value*1024L*1024L);
        }else{
          if(value<2||value>4096)throw new NumberFormatException();
          options.fragment(mode,(int)value);
        }
      }catch(NumberFormatException error){parameter.setError(size.isChecked()?"请输入大于零的单片大小":"请输入 2～4096 的分片数量");return;}
      dialog.dismiss();String target=folder();
      CloudFilesClient.Provider requested=provider;work("分片上传完成",()->{String account=client.currentAccountName(requested);try{recordManualUpload(account,requested,target,file,"上传中","");CloudFilesClient.UploadResult result=client.uploadFragmented(target,file.getAbsolutePath(),options);recordManualUpload(account,requested,target,file,"完成","");return "已上传 "+result.fragmentPartCount+" 个分片";}catch(Exception error){recordManualUpload(account,requested,target,file,"失败","上传失败");throw error;}},true);
    });
  }

  private RadioButton radio(RadioGroup group,String label){
    RadioButton button=new RadioButton(owner);button.setText(label);button.setTextColor(owner.TEXT);button.setButtonTintList(ColorStateList.valueOf(owner.PRIMARY));group.addView(button);return button;
  }

  private void replaceApk(CloudFilesClient.CloudItem item){
    pickLocalFiles(false,".apk",files->{
      beginReplaceApk(item,files.get(0));
    });
  }

  /** Upload-path entry: pick the new APK first, then choose which uploaded APK in this folder it replaces. */
  private void smartSourceUpload(){
    pickLocalFiles(false,".apk",files->{
      File replacement=files.get(0);
      PackageInfo info=owner.getPackageManager().getPackageArchiveInfo(replacement.getAbsolutePath(),0);
      if(info==null){owner.showNotice("无法读取所选 APK 信息",true);return;}
      SmartSourcePicker picker=new SmartSourcePicker(replacement,folder(),provider);
      AlertDialog dialog=new AlertDialog.Builder(owner).setTitle("选择要替换的旧 APK")
          .setView(picker.holder).setNegativeButton("取消",null).create();
      picker.dialog=dialog;
      owner.showRounded(dialog);
      picker.loadNextPage();
    });
  }

  /**
   * Shows the first page of APK candidates immediately and appends further pages from list scrolling.
   * Only the folder the user is viewing is read, and paging stops on a short, empty or duplicate page.
   */
  private final class SmartSourcePicker {
    final File replacement;
    final String directory;
    final CloudFilesClient.Provider requested;
    final List<CloudFilesClient.CloudItem> apks=new ArrayList<>();
    final Set<String> loadedIds=new HashSet<>();
    final ListView list;
    final FrameLayout holder;
    final android.widget.BaseAdapter adapter;
    AlertDialog dialog;
    int nextPage=1;
    boolean hasMore=true;
    boolean loading=false;

    SmartSourcePicker(File replacement,String directory,CloudFilesClient.Provider requested){
      this.replacement=replacement;
      this.directory=directory;
      this.requested=requested;
      list=new ListView(owner);
      list.setItemsCanFocus(false);
      list.setDivider(null);
      holder=new FrameLayout(owner);
      holder.addView(list,new FrameLayout.LayoutParams(-1,owner.dp(400)));
      adapter=new android.widget.BaseAdapter(){
        public int getCount(){return apks.size();}
        public Object getItem(int position){return apks.get(position);}
        public long getItemId(int position){return position;}
        public View getView(int position,View recycled,ViewGroup parent){
          TextView text=recycled instanceof TextView?(TextView)recycled:owner.text("",14,owner.TEXT);
          text.setText(apks.get(position).name);
          text.setMaxLines(2);
          text.setPadding(owner.dp(16),owner.dp(12),owner.dp(16),owner.dp(12));
          return text;
        }
      };
      list.setAdapter(adapter);
      list.setOnItemClickListener((parent,view,position,id)->{
        CloudFilesClient.CloudItem target=apks.get(position);
        if(dialog!=null)dialog.dismiss();
        beginReplaceApk(target,replacement);
      });
      list.setOnScrollListener(new android.widget.AbsListView.OnScrollListener(){
        public void onScrollStateChanged(android.widget.AbsListView view,int state){}
        public void onScroll(android.widget.AbsListView view,int first,int visible,int total){
          if(total>0&&first+visible>=total-2)loadNextPage();
        }
      });
    }

    void loadNextPage(){
      if(loading||!hasMore)return;
      loading=true;
      final int page=nextPage;
      owner.io.execute(()->{
        CloudFilesClient.CloudPage found=null;
        String error="";
        try{
          synchronized(client){client.selectProvider(requested);found=client.listFolder(directory,page,CLOUD_PAGE_SIZE);}
        }catch(CloudFilesClient.CloudException failed){
          error=failed.getMessage();
        }catch(Exception failed){
          error=owner.friendlyError(failed);
        }
        final CloudFilesClient.CloudPage result=found;
        final String problem=error;
        owner.runOnUiThread(()->{
          if(owner.pageKind!=7)return;
          loading=false;
          if(result==null){
            hasMore=false;
            if(!problem.isEmpty())owner.showNotice(problem,true);
            if(apks.isEmpty()&&dialog!=null)dialog.dismiss();
            return;
          }
          int addedRows=0;
          for(CloudFilesClient.CloudItem item:result.items){
            if(!loadedIds.add(item.id))continue;
            addedRows++;
            if(!item.folder&&item.name.toLowerCase(Locale.ROOT).endsWith(".apk"))apks.add(item);
          }
          nextPage=result.page+1;
          hasMore=result.hasMore&&!result.items.isEmpty()&&addedRows>0;
          adapter.notifyDataSetChanged();
          if(apks.isEmpty()&&!hasMore){
            owner.showNotice("当前目录没有可替换的 APK",true);
            if(dialog!=null)dialog.dismiss();
            return;
          }
          list.post(()->{if(list.getLastVisiblePosition()>=list.getCount()-1)loadNextPage();});
        });
      });
    }
  }

  private void beginReplaceApk(CloudFilesClient.CloudItem item,File replacement){
    work(null,()->{
      PackageInfo info=owner.getPackageManager().getPackageArchiveInfo(replacement.getAbsolutePath(),0);
      if(info==null)throw new CloudFilesClient.CloudException(CloudFilesClient.ERROR_INVALID,"无法读取所选 APK 信息");
      CloudFilesClient.ManagedDescription metadata=client.getDescription(item);
      owner.runOnUiThread(()->{
        if(owner.pageKind==7)showReplaceApkConfirmation(item,replacement,info,metadata);
      });
      return "";
    },false);
  }

  private void showReplaceApkConfirmation(CloudFilesClient.CloudItem item,File file,PackageInfo info,CloudFilesClient.ManagedDescription metadata){
    LinearLayout panel=panel();
    panel.addView(owner.text("智能换源：上传新版安装包并沿用云端标题与简介。",13,owner.TEXT));
    panel.addView(owner.text("保留云端标题："+metadata.name,14,owner.TEXT));
    panel.addView(owner.text(metadata.description.isEmpty()?"云端简介为空":"保留简介："+metadata.description,13,owner.MUTED));
    panel.addView(owner.text("新安装包："+file.getName(),14,owner.TEXT));
    panel.addView(owner.text("包名："+info.packageName+"\n版本："+info.versionName+" ("+info.versionCode+")",13,owner.MUTED));
    CheckBox autoIcon=new CheckBox(owner);autoIcon.setText("从新 APK 提取并设置图标");autoIcon.setTextColor(owner.TEXT);autoIcon.setButtonTintList(ColorStateList.valueOf(owner.PRIMARY));
    autoIcon.setChecked(provider==CloudFilesClient.Provider.ORDINARY);autoIcon.setEnabled(provider==CloudFilesClient.Provider.ORDINARY);panel.addView(autoIcon);
    panel.addView(owner.text("新版上传并回读确认标题、简介与图标后，旧文件才移入回收站。新版会生成新的分享链接，旧分享链接继续指向已被替换的旧文件。",12,owner.MUTED));
    final String target=folder();final CloudFilesClient.Provider requested=provider;
    AlertDialog dialog=new AlertDialog.Builder(owner).setTitle("智能换源（上传新版 APK）").setView(owner.limitedDialogScroll(panel,420)).setNegativeButton("取消",null).setPositiveButton("上传新版",(d,w)->work("智能换源完成",()->{
      String account=client.currentAccountName(requested);CloudFilesClient.ReplaceResult result;
      try{recordManualUpload(account,requested,target,file,"上传中","");result=client.replaceApk(item,target,file.getAbsolutePath(),CloudFilesClient.UploadOptions.defaults().autoIcon(autoIcon.isChecked()));recordManualUpload(account,requested,target,file,"完成","");}
      catch(Exception error){recordManualUpload(account,requested,target,file,"失败","换源未完成，请检查云端文件");throw error;}
      CloudFilesClient.CloudItem fresh=new CloudFilesClient.CloudItem(result.newFileId,result.name,false,0,"","",false,0,"",false,"");
      CloudFilesClient.ShareInfo share=client.shareInfo(fresh);
      owner.runOnUiThread(()->{
        if(owner.pageKind==7)showSmartSourceResult(result,share);
      });
      return "新版安装包已上传，旧文件已回收";
    },true)).create();owner.showRounded(dialog);
  }

  private void showSmartSourceResult(CloudFilesClient.ReplaceResult result,CloudFilesClient.ShareInfo info){
    LinearLayout panel=panel();
    panel.addView(owner.text("新版安装包已上传，标题与简介已回读确认。",13,owner.TEXT));
    panel.addView(owner.text("旧文件已移入回收站；请使用下面的新分享链接。",12,owner.MUTED));
    addShareRow(panel,"新分享链接",info.shareUrl,"暂未生成分享链接",info.shareUrl);
    addShareRow(panel,"提取码",info.password,"未设置提取码",info.password);
    addShareRow(panel,"新文件 ID",result.newFileId,"",result.newFileId);
    AlertDialog dialog=new AlertDialog.Builder(owner)
        .setTitle("智能换源完成")
        .setView(owner.limitedDialogScroll(panel,420))
        .setNegativeButton("关闭",null)
        .setPositiveButton("系统分享",(d,w)->{
          StringBuilder share=new StringBuilder(info.shareUrl);
          if(!info.password.isEmpty())share.append("\n提取码：").append(info.password);
          owner.shareText(share.toString(),result.name);
        })
        .create();
    owner.showRounded(dialog);
  }

  private void pickLocalFiles(boolean multi,String suffix,java.util.function.Consumer<List<File>> onSelected){
    if(multi)MarketFilePicker.chooseMultiple(owner,suffix,onSelected);
    else MarketFilePicker.choose(owner,suffix,file->onSelected.accept(Collections.singletonList(file)));
  }

  private void localDirectory(java.util.function.Consumer<File> selected){
    MarketFilePicker.chooseDirectory(owner,selected);
  }

  private void localImage(String uri,java.util.function.Consumer<File> done){
    owner.io.execute(()->{
      try{
        File output=File.createTempFile("cloud-icon-",".png",owner.getCacheDir());
        try(InputStream input=owner.getContentResolver().openInputStream(android.net.Uri.parse(uri));
            OutputStream target=new FileOutputStream(output)){
          byte[] buffer=new byte[8192];
          int count;
          while((count=input.read(buffer))!=-1)target.write(buffer,0,count);
        }
        owner.runOnUiThread(()->done.accept(output));
      }catch(Exception error){
        owner.runOnUiThread(()->owner.showNotice(owner.friendlyError(error),true));
      }
    });
  }

  private void editValue(String title,String value,java.util.function.Consumer<String> apply){
    LinearLayout panel=panel();
    EditText field=input(panel,title,false);
    field.setText(value);
    AlertDialog dialog=new AlertDialog.Builder(owner).setTitle(title).setView(panel).setNegativeButton("取消",null).setPositiveButton("保存",(d,w)->apply.accept(field.getText().toString())).create();
    owner.showRounded(dialog);
  }

  private LinearLayout panel(){
    LinearLayout panel=new LinearLayout(owner);
    panel.setOrientation(LinearLayout.VERTICAL);
    panel.setPadding(owner.dp(20),owner.dp(8),owner.dp(20),owner.dp(8));
    return panel;
  }

  private EditText input(LinearLayout panel,String hint,boolean password){
    EditText field=owner.sourceInput(hint,InputType.TYPE_CLASS_TEXT|(password?InputType.TYPE_TEXT_VARIATION_PASSWORD:0));
    panel.addView(field,new LinearLayout.LayoutParams(-1,owner.dp(56)));
    return field;
  }

  private void confirm(String title,String message,Runnable apply){
    String action=title.startsWith("删除")?"删除":title.startsWith("移除")?"移除":"确认";
    AlertDialog dialog=new AlertDialog.Builder(owner).setTitle(title).setMessage(message).setNegativeButton("取消",null).setPositiveButton(action,(d,w)->apply.run()).create();
    owner.showRounded(dialog);
  }

  static String formatBytes(long bytes){
    if(bytes<=0)return "0 B";
    if(bytes<1024)return bytes+" B";
    if(bytes<1024*1024)return String.format(Locale.ROOT,"%.1f KB",bytes/1024.0);
    if(bytes<1024*1024*1024)return String.format(Locale.ROOT,"%.1f MB",bytes/(1024.0*1024.0));
    return String.format(Locale.ROOT,"%.2f GB",bytes/(1024.0*1024.0*1024.0));
  }

  final class FileAdapter extends BaseAdapter {
    private final List<CloudFilesClient.CloudItem> filteredItems=new ArrayList<>();
    private String lastFilteredQuery=null;

    void refilter(){
      filteredItems.clear();
      String needle=query.toLowerCase(Locale.ROOT);
      for(CloudFilesClient.CloudItem item:items){
        if(needle.isEmpty()||item.name.toLowerCase(Locale.ROOT).contains(needle)){
          filteredItems.add(item);
        }
      }
      lastFilteredQuery=query;
      notifyDataSetChanged();
    }

    List<CloudFilesClient.CloudItem> visible(){
      if(lastFilteredQuery==null||!lastFilteredQuery.equals(query)){
        filteredItems.clear();
        String needle=query.toLowerCase(Locale.ROOT);
        for(CloudFilesClient.CloudItem item:items){
          if(needle.isEmpty()||item.name.toLowerCase(Locale.ROOT).contains(needle)){
            filteredItems.add(item);
          }
        }
        lastFilteredQuery=query;
      }
      return filteredItems;
    }

    public int getCount(){return visible().size();}
    public Object getItem(int position){return visible().get(position);}
    public long getItemId(int position){return position;}

    final class ViewHolder {
      CheckBox check;
      ImageView icon;
      TextView text;
      ImageButton menu;
    }

    public View getView(int position,View recycled,ViewGroup parent){
      CloudFilesClient.CloudItem item=visible().get(position);
      ViewHolder holder;
      View row=recycled;
      if(row!=null&&row.getTag() instanceof ViewHolder){
        holder=(ViewHolder)row.getTag();
      }else{
        LinearLayout layout=new LinearLayout(owner);
        layout.setGravity(Gravity.CENTER_VERTICAL);
        layout.setPadding(owner.dp(4),owner.dp(8),owner.dp(4),owner.dp(8));
        layout.setMinimumHeight(owner.dp(68));
        layout.setDescendantFocusability(ViewGroup.FOCUS_BLOCK_DESCENDANTS);

        CheckBox check=new CheckBox(owner);
        check.setClickable(false);
        check.setFocusable(false);
        check.setButtonTintList(ColorStateList.valueOf(owner.PRIMARY));
        layout.addView(check);

        ImageView icon=new ImageView(owner);
        icon.setColorFilter(owner.PRIMARY);
        layout.addView(icon,new LinearLayout.LayoutParams(owner.dp(32),owner.dp(32)));

        TextView text=owner.text("",14,owner.TEXT);
        text.setMaxLines(3);
        text.setPadding(owner.dp(12),0,owner.dp(6),0);
        layout.addView(text,new LinearLayout.LayoutParams(0,-2,1));

        ImageButton menu=owner.iconButton(R.drawable.ic_more,"管理");
        menu.setFocusable(false);
        layout.addView(menu,new LinearLayout.LayoutParams(owner.dp(48),owner.dp(48)));

        holder=new ViewHolder();
        holder.check=check;
        holder.icon=icon;
        holder.text=text;
        holder.menu=menu;
        layout.setTag(holder);
        applyRowFocusPolicy(list,layout,check,menu);
        row=layout;
      }

      boolean selecting=!selected.isEmpty();
      holder.check.setVisibility(selecting?View.VISIBLE:View.GONE);
      holder.check.setChecked(selecting&&selected.contains(item.id));
      holder.icon.setTag(item.iconUrl);
      holder.icon.setColorFilter(owner.PRIMARY);
      holder.icon.setImageResource(item.folder?R.drawable.ic_folder:R.drawable.ic_download);
      if(!item.iconUrl.isEmpty()){
        owner.loadImage(item.iconUrl,holder.icon);
      }
      holder.text.setText(item.name+"\n"+(item.folder?"文件夹":formatBytes(item.size))+(item.hasPassword?" · 有密码":""));
      holder.menu.setEnabled(!mutating);
      holder.menu.setContentDescription("管理 "+item.name);
      holder.menu.setOnClickListener(v->itemMenu(item));
      return row;
    }
  }
}
