package cc.nkbr.lanzouplus;

import android.app.AlertDialog;
import android.content.*;
import android.os.Bundle;
import android.text.*;
import android.view.*;
import android.widget.*;
import java.io.*;
import java.util.*;

/** Independent file workspace shared by the ordinary and premium cloud providers. */
final class CloudManagement {
  private final MainActivity owner;
  private final CloudFilesClient client;
  private CloudFilesClient.Provider provider=CloudFilesClient.Provider.ORDINARY;
  private final List<String> folderIds=new ArrayList<>(),folderNames=new ArrayList<>();
  private final List<CloudFilesClient.CloudItem> items=new ArrayList<>();
  private final Set<String> selected=new LinkedHashSet<>();
  private final List<View> controls=new ArrayList<>();
  private ListView list;
  private LinearLayout breadcrumbs;
  private TextView status,selection;
  private Button more;
  private FileAdapter adapter;
  private String query="";
  private int page;
  private boolean busy,hasMore;
  private String failure="";
  interface Work { String run()throws Exception; }
  CloudManagement(MainActivity owner){
    this.owner=owner;client=CloudFilesClient.shared(owner);
    if(owner.getSharedPreferences("cloud_management",0).getBoolean("premium",false))provider=CloudFilesClient.Provider.PREMIUM;
  }
  void saveState(Bundle state){state.putBoolean("cloudPremium",provider==CloudFilesClient.Provider.PREMIUM);state.putStringArrayList("cloudIds",new ArrayList<>(folderIds));state.putStringArrayList("cloudNames",new ArrayList<>(folderNames));state.putString("cloudQuery",query);}
  void restoreState(Bundle state){if(state==null)return;provider=state.getBoolean("cloudPremium",false)?CloudFilesClient.Provider.PREMIUM:CloudFilesClient.Provider.ORDINARY;folderIds.clear();folderNames.clear();if(state.getStringArrayList("cloudIds")!=null)folderIds.addAll(state.getStringArrayList("cloudIds"));if(state.getStringArrayList("cloudNames")!=null)folderNames.addAll(state.getStringArrayList("cloudNames"));query=state.getString("cloudQuery","");}
  boolean onActivityResult(int request,int result,Intent data){return false;}
  private String folder(){return folderIds.isEmpty()?"-1":folderIds.get(folderIds.size()-1);}
  void show(){
    owner.pageDirection=1;owner.base();owner.pageKind=7;owner.activeSource=null;owner.clearFolderTrail();owner.systemBackAction=this::back;
    owner.primaryHeader("管理");controls.clear();
    ImageButton back=owner.iconButton(R.drawable.ic_back,"返回市场");back.setOnClickListener(v->owner.marketPages().showMarket(false));owner.pageHeaderRow.addView(back,0,new LinearLayout.LayoutParams(owner.dp(48),owner.dp(48)));
    ImageButton accounts=owner.iconButton(R.drawable.ic_sources,"切换或登录云盘账号");accounts.setOnClickListener(v->accounts());owner.pageHeaderRow.addView(accounts);controls.add(accounts);
    ImageButton refresh=owner.iconButton(R.drawable.ic_refresh,"刷新云盘文件");refresh.setOnClickListener(v->load(false));owner.pageHeaderRow.addView(refresh);controls.add(refresh);
    LinearLayout providers=new LinearLayout(owner);
    for(CloudFilesClient.Provider value:CloudFilesClient.Provider.values()){
      Button button=value==provider?AppButtons.primary(owner,value==CloudFilesClient.Provider.ORDINARY?"蓝奏云":"蓝奏云优享版"):owner.button(value==CloudFilesClient.Provider.ORDINARY?"蓝奏云":"蓝奏云优享版");
      button.setContentDescription("管理"+(value==CloudFilesClient.Provider.ORDINARY?"普通蓝奏云":"蓝奏云优享版"));
      button.setOnClickListener(v->{if(busy||provider==value)return;provider=value;folderIds.clear();folderNames.clear();items.clear();selected.clear();query="";page=0;owner.getSharedPreferences("cloud_management",0).edit().putBoolean("premium",value==CloudFilesClient.Provider.PREMIUM).apply();show();});
      LinearLayout.LayoutParams params=new LinearLayout.LayoutParams(0,owner.dp(48),1);params.setMargins(owner.dp(3),0,owner.dp(3),0);providers.addView(button,params);controls.add(button);
    }
    owner.root.addView(providers);
    boolean landscape=owner.getResources().getConfiguration().orientation==android.content.res.Configuration.ORIENTATION_LANDSCAPE;
    HorizontalScrollView pathScroll=new HorizontalScrollView(owner);pathScroll.setHorizontalScrollBarEnabled(false);breadcrumbs=new LinearLayout(owner);pathScroll.addView(breadcrumbs);renderBreadcrumbs();
    
    EditText filter=owner.sourceInput("搜索当前文件夹",android.text.InputType.TYPE_CLASS_TEXT);filter.setSingleLine(true);filter.setText(query);
    if(landscape){LinearLayout navigation=new LinearLayout(owner);navigation.addView(pathScroll,new LinearLayout.LayoutParams(0,owner.dp(48),1));navigation.addView(filter,new LinearLayout.LayoutParams(0,owner.dp(48),1));owner.root.addView(navigation);}else{owner.root.addView(pathScroll,new LinearLayout.LayoutParams(-1,owner.dp(48)));owner.root.addView(filter,new LinearLayout.LayoutParams(-1,owner.dp(48)));}
    filter.addTextChangedListener(new TextWatcher(){public void beforeTextChanged(CharSequence s,int a,int b,int c){}public void afterTextChanged(Editable e){}public void onTextChanged(CharSequence s,int a,int b,int c){query=s.toString();if(adapter!=null)adapter.notifyDataSetChanged();}});
    LinearLayout actions=new LinearLayout(owner);actions.setGravity(Gravity.CENTER_VERTICAL);
    ImageButton add=owner.iconButton(R.drawable.ic_add,"新建文件夹");add.setOnClickListener(v->createFolder());actions.addView(add);controls.add(add);
    ImageButton upload=owner.iconButton(R.drawable.ic_folder,"上传文件或文件夹");upload.setOnClickListener(v->uploadMenu());actions.addView(upload);controls.add(upload);
    selection=owner.text("长按项目多选",12,owner.MUTED);actions.addView(selection,new LinearLayout.LayoutParams(0,owner.dp(48),1));
    Button selectedActions=owner.toolbarTextButton("所选项目");selectedActions.setOnClickListener(v->selectionMenu());actions.addView(selectedActions);controls.add(selectedActions);owner.root.addView(actions);
    status=landscape?selection:owner.text("",12,owner.MUTED);status.setMaxLines(2);if(!landscape)owner.root.addView(status,new LinearLayout.LayoutParams(-1,owner.dp(44)));
    list=new ListView(owner);list.setDivider(new android.graphics.drawable.ColorDrawable(owner.DIV));list.setDividerHeight(owner.dp(1));adapter=new FileAdapter();list.setAdapter(adapter);
    android.widget.FrameLayout body=new android.widget.FrameLayout(owner);body.addView(list,new android.widget.FrameLayout.LayoutParams(-1,-1));
    LinearLayout empty=new LinearLayout(owner);empty.setOrientation(LinearLayout.VERTICAL);empty.setGravity(Gravity.CENTER);empty.addView(owner.text("此目录暂无项目，或需要先登录云盘账号",13,owner.MUTED));
    Button loginAction=owner.button("登录云盘账号");loginAction.setOnClickListener(v->login());empty.addView(loginAction,new LinearLayout.LayoutParams(-2,owner.dp(48)));controls.add(loginAction);body.addView(empty,new android.widget.FrameLayout.LayoutParams(-1,-1));list.setEmptyView(empty);owner.root.addView(body,new LinearLayout.LayoutParams(-1,0,1));
    list.setOnItemClickListener((p,v,position,id)->{if(busy)return;CloudFilesClient.CloudItem item=adapter.visible().get(position);if(!selected.isEmpty()){toggle(item);return;}if(item.folder){folderIds.add(item.id);folderNames.add(item.name);load(false);}else itemMenu(item);});
    list.setOnItemLongClickListener((p,v,position,id)->{if(busy)return true;toggle(adapter.visible().get(position));return true;});
    more=owner.button("加载下一页");more.setOnClickListener(v->load(true));owner.root.addView(more,new LinearLayout.LayoutParams(-1,owner.dp(48)));controls.add(more);
    update();if(!busy)load(false);
  }
  private void back(){if(!selected.isEmpty()){selected.clear();update();return;}if(busy){owner.marketPages().showMarket(false);return;}if(!folderIds.isEmpty()){jump(folderIds.size()-2);return;}owner.marketPages().showMarket(false);}
  private void jump(int level){if(busy)return;while(folderIds.size()>level+1){folderIds.remove(folderIds.size()-1);folderNames.remove(folderNames.size()-1);}load(false);}
  private void renderBreadcrumbs(){
    if(breadcrumbs==null)return;breadcrumbs.removeAllViews();
    Button root=owner.toolbarTextButton("根目录");root.setOnClickListener(v->jump(-1));root.setEnabled(!busy);breadcrumbs.addView(root);
    for(int i=0;i<folderNames.size();i++){final int level=i;Button crumb=owner.toolbarTextButton("› "+folderNames.get(i));crumb.setOnClickListener(v->jump(level));crumb.setEnabled(!busy);breadcrumbs.addView(crumb);}
  }
  private void update(){
    renderBreadcrumbs();
    for(View control:controls)control.setEnabled(!busy);
    if(status!=null)status.setText(busy?"正在处理云盘文件…":!failure.isEmpty()?failure:items.size()+" 个项目 · "+(provider==CloudFilesClient.Provider.PREMIUM?"蓝奏云优享版":"蓝奏云"));
    if(selection!=null&&(selection!=status||!selected.isEmpty()))selection.setText(selected.isEmpty()?"长按项目多选":"已选 "+selected.size()+" 个");
    if(more!=null)more.setVisibility(hasMore?View.VISIBLE:View.GONE);
    if(adapter!=null)adapter.notifyDataSetChanged();
  }
  private void work(String message,Work action,boolean refresh){
    if(busy)return;busy=true;failure="";update();final CloudFilesClient.Provider requested=provider;
    owner.io.execute(()->{String result="",error="";boolean loginRequired=false;try{synchronized(client){client.selectProvider(requested);result=action.run();}}catch(CloudFilesClient.CloudException failed){loginRequired=failed.needsCredentials()||failed.sessionExpired();error=loginRequired&&message==null?"登录后查看私人文件":failed.getMessage();}catch(Exception failed){error=owner.friendlyError(failed);}final String done=result,problem=error;final boolean quietLogin=loginRequired&&message==null;owner.runOnUiThread(()->{busy=false;failure=problem;if(owner.pageKind!=7)return;update();if(!problem.isEmpty()){if(!quietLogin)owner.showNotice(problem,true);}else{if(message!=null)owner.showNotice(done.isEmpty()?message:done,false);if(refresh)load(false);}});});
  }
  private void load(boolean append){
    if(busy)return;final String directory=folder();final int requested=append?page+1:1;
    work(null,()->{CloudFilesClient.CloudPage found=client.listFolder(directory,requested,50);owner.runOnUiThread(()->{if(!append){items.clear();selected.clear();}Set<String> seen=new HashSet<>();for(CloudFilesClient.CloudItem item:items)seen.add(item.id);for(CloudFilesClient.CloudItem item:found.items)if(seen.add(item.id))items.add(item);page=found.page;hasMore=found.hasMore;});return "";},false);
  }
  private void toggle(CloudFilesClient.CloudItem item){if(!selected.add(item.id))selected.remove(item.id);update();}
  private List<CloudFilesClient.CloudItem> selectedItems(){List<CloudFilesClient.CloudItem> out=new ArrayList<>();for(CloudFilesClient.CloudItem item:items)if(selected.contains(item.id))out.add(item);return out;}
  private void accounts(){work(null,()->{List<String> names=client.accountNames(provider);String current=client.currentAccountName(provider);owner.runOnUiThread(()->{if(owner.pageKind!=7)return;String[] labels=new String[names.size()+1];for(int i=0;i<names.size();i++)labels[i]=names.get(i)+(names.get(i).equals(current)?" · 当前":"");labels[names.size()]="登录其他账号";AlertDialog dialog=new AlertDialog.Builder(owner).setTitle("云盘账号").setItems(labels,(d,which)->{if(which==names.size())login();else accountActions(names.get(which));}).setNegativeButton("关闭",null).create();owner.showRounded(dialog);});return "";},false);}
  private void accountActions(String name){AlertDialog dialog=new AlertDialog.Builder(owner).setTitle(name).setItems(new String[]{"切换到此账号","移除此账号"},(d,which)->{if(which==0)work("账号已切换",()->{client.selectAccount(provider,name);folderIds.clear();folderNames.clear();return "";},true);else confirm("移除账号？","只移除本机登录信息，不删除云端文件。",()->work("账号已移除",()->{client.removeAccount(provider,name);items.clear();folderIds.clear();folderNames.clear();return "";},true));}).setNegativeButton("取消",null).create();owner.showRounded(dialog);}
  private void login(){LinearLayout panel=panel();EditText account=input(panel,"账号",false),password=input(panel,"密码",true);AlertDialog dialog=new AlertDialog.Builder(owner).setTitle(provider==CloudFilesClient.Provider.PREMIUM?"登录蓝奏云优享版":"登录蓝奏云").setView(panel).setNegativeButton("取消",null).setPositiveButton("登录",null).create();owner.showRounded(dialog);dialog.getButton(-1).setOnClickListener(v->{String name=account.getText().toString().trim(),secret=password.getText().toString();if(name.isEmpty()||secret.isEmpty()){account.setError("请输入账号和密码");return;}password.setText("");dialog.dismiss();work("登录成功",()->{client.login(provider,name,secret);items.clear();folderIds.clear();folderNames.clear();return "";},true);});}
  private void createFolder(){LinearLayout panel=panel();EditText name=input(panel,"文件夹名称",false),description=input(panel,"文件夹简介（可选）",false);AlertDialog dialog=new AlertDialog.Builder(owner).setTitle("新建文件夹").setView(panel).setNegativeButton("取消",null).setPositiveButton("创建",null).create();owner.showRounded(dialog);dialog.getButton(-1).setOnClickListener(v->{String title=name.getText().toString().trim();if(title.isEmpty()){name.setError("请输入名称");return;}String detail=description.getText().toString();dialog.dismiss();String parent=folder();work("文件夹已创建",()->{client.createFolder(parent,title,detail);return "";},true);});}
  private void itemMenu(CloudFilesClient.CloudItem item){
    List<String> labels=new ArrayList<>(Arrays.asList("分享","下载","重命名","移动","复制","设置密码","编辑简介","加入列表源","删除"));
    if(item.folder)labels.remove("下载");if(!item.folder&&provider==CloudFilesClient.Provider.ORDINARY)labels.add("自定义图标");if(!item.folder&&item.name.toLowerCase(Locale.ROOT).endsWith(".apk"))labels.add("替换安装包");
    AlertDialog dialog=new AlertDialog.Builder(owner).setTitle(item.name).setItems(labels.toArray(new String[0]),(d,which)->{switch(labels.get(which)){
      case "分享":share(item,false);break;case "加入列表源":share(item,true);break;case "下载":download(Collections.singletonList(item));break;
      case "重命名":editValue("重命名",item.name,value->work("名称已更新",()->{client.rename(item,value);return "";},true));break;
      case "移动":target(Collections.singletonList(item),false);break;case "复制":target(Collections.singletonList(item),true);break;
      case "设置密码":editValue("设置密码（留空取消密码）","",value->work("密码已更新",()->{client.setPassword(item,value);return "";},true));break;
      case "编辑简介":work(null,()->{String current=client.getDescription(item).description;owner.runOnUiThread(()->editValue("编辑简介",current,value->work("简介已更新",()->{client.setDescription(item,value);return "";},true)));return "";},false);break;
      case "删除":remove(Collections.singletonList(item));break;
      case "自定义图标":MarketFilePicker.chooseImage(owner,uri->localImage(uri,file->work("图标已更新",()->{client.setIcon(item.id,file.getAbsolutePath());return "";},true)));break;
      case "替换安装包":MarketFilePicker.choose(owner,".apk",file->confirm("替换安装包？","将使用所选 APK 替换此文件。",()->work("安装包已替换",()->{client.replaceApk(item,file.getAbsolutePath(),CloudFilesClient.UploadOptions.defaults());return "";},true)));break;
    }}).setNegativeButton("取消",null).create();owner.showRounded(dialog);
  }
  private void share(CloudFilesClient.CloudItem item,boolean add){work(null,()->{CloudFilesClient.ShareInfo info=client.shareInfo(item);owner.runOnUiThread(()->{if(owner.pageKind!=7)return;if(add)MainActivity.favoriteWebPage(owner,info.shareUrl,item.name);else owner.shareText(info.shareUrl+(info.password.isEmpty()?"":"\n密码："+info.password),"分享云盘文件");});return "";},false);}
  private void selectionMenu(){List<CloudFilesClient.CloudItem> chosen=selectedItems();String[] choices={"全选当前列表","取消选择","移动","复制","下载","分享","删除"};AlertDialog dialog=new AlertDialog.Builder(owner).setTitle("所选项目 · "+chosen.size()).setItems(choices,(d,which)->{if(which==0){for(CloudFilesClient.CloudItem item:adapter.visible())selected.add(item.id);update();}else if(which==1){selected.clear();update();}else if(chosen.isEmpty())owner.showNotice("请先长按选择项目",false);else if(which==2)target(chosen,false);else if(which==3)target(chosen,true);else if(which==4)download(chosen);else if(which==5)work(null,()->{StringBuilder links=new StringBuilder();for(CloudFilesClient.CloudItem item:chosen){CloudFilesClient.ShareInfo info=client.shareInfo(item);if(links.length()>0)links.append('\n');links.append(item.name).append('\n').append(info.shareUrl);if(!info.password.isEmpty())links.append(" 密码：").append(info.password);}owner.runOnUiThread(()->owner.shareText(links.toString(),"分享云盘项目"));return "";},false);else remove(chosen);}).setNegativeButton("关闭",null).create();owner.showRounded(dialog);}
  private void remove(List<CloudFilesClient.CloudItem> chosen){confirm("删除所选项目？","将删除 "+chosen.size()+" 个云端项目。",()->work("所选项目已删除",()->{client.deleteMany(chosen);return "";},true));}
  private void download(List<CloudFilesClient.CloudItem> chosen){File directory=owner.selectedDownloadDirectory();work("下载完成",()->{int count=0;for(CloudFilesClient.CloudItem item:chosen){if(item.folder)continue;client.download(item,directory.getAbsolutePath()+File.separator);count++;}return count==0?"所选项目没有可下载的文件":"已下载 "+count+" 个文件至 "+directory.getAbsolutePath();},false);}
  private void target(List<CloudFilesClient.CloudItem> chosen,boolean copy){work(null,()->{List<CloudFilesClient.CloudItem> targets=client.moveTargets();List<String> ids=new ArrayList<>(),names=new ArrayList<>();ids.add("-1");names.add("根目录");for(CloudFilesClient.CloudItem target:targets)if(target.folder&&!selected.contains(target.id)){ids.add(target.id);names.add(target.path.isEmpty()?target.name:target.path);}owner.runOnUiThread(()->{AlertDialog dialog=new AlertDialog.Builder(owner).setTitle(copy?"复制到":"移动到").setItems(names.toArray(new String[0]),(d,index)->work(copy?"复制完成":"移动完成",()->{if(copy)for(CloudFilesClient.CloudItem item:chosen)client.copy(item,ids.get(index),owner.getCacheDir().getAbsolutePath());else client.moveMany(chosen,ids.get(index));return "";},true)).setNegativeButton("取消",null).create();owner.showRounded(dialog);});return "";},false);}
  private void uploadMenu(){String[] options={"上传文件","上传文件夹","分片上传文件"};AlertDialog dialog=new AlertDialog.Builder(owner).setTitle("上传到当前目录").setItems(options,(d,which)->{if(which==1)localDirectory(file->uploadOptions(file,true,false));else MarketFilePicker.choose(owner,"",file->uploadOptions(file,false,which==2));}).setNegativeButton("取消",null).create();owner.showRounded(dialog);}
  private void uploadOptions(File file,boolean directory,boolean fragmented){LinearLayout panel=panel();panel.addView(owner.text(file.getName(),14,owner.TEXT));CheckBox wrap=new CheckBox(owner);wrap.setText("兼容包装（用于不支持的文件类型）");wrap.setTextColor(owner.TEXT);wrap.setButtonTintList(android.content.res.ColorStateList.valueOf(owner.PRIMARY));panel.addView(wrap);CheckBox icon=new CheckBox(owner);icon.setText("提取 APK 软件图标");icon.setTextColor(owner.TEXT);icon.setButtonTintList(android.content.res.ColorStateList.valueOf(owner.PRIMARY));panel.addView(icon);EditText parts=null;if(fragmented){parts=input(panel,"分片数量",false);parts.setInputType(android.text.InputType.TYPE_CLASS_NUMBER);parts.setText("2");}final EditText partCount=parts;AlertDialog dialog=new AlertDialog.Builder(owner).setTitle(fragmented?"分片上传":"上传选项").setView(panel).setNegativeButton("取消",null).setPositiveButton("上传",null).create();owner.showRounded(dialog);dialog.getButton(-1).setOnClickListener(v->{CloudFilesClient.UploadOptions options=CloudFilesClient.UploadOptions.defaults().compatibilityWrap(wrap.isChecked()).autoIcon(icon.isChecked());if(fragmented){try{int count=Integer.parseInt(partCount.getText().toString());if(count<2)throw new NumberFormatException();options.fragment("byte_split",count);}catch(NumberFormatException error){partCount.setError("至少分为 2 片");return;}}dialog.dismiss();String target=folder();work("上传完成",()->{if(directory){CloudFilesClient.DirectoryUploadResult result=client.uploadDirectory(target,file.getAbsolutePath(),options);return "已上传 "+result.filesUploaded+" 个文件";}if(fragmented)client.uploadFragmented(target,file.getAbsolutePath(),options);else client.upload(target,file.getAbsolutePath(),options);return "";},true);});}
  private void localDirectory(java.util.function.Consumer<File> selected){LinearLayout panel=panel();TextView path=owner.text("",12,owner.MUTED);panel.addView(path);LinearLayout rows=new LinearLayout(owner);rows.setOrientation(LinearLayout.VERTICAL);panel.addView(owner.limitedDialogScroll(rows,330));File[] current={owner.storageAccessGranted()?android.os.Environment.getExternalStorageDirectory():owner.getExternalFilesDir(null)};AlertDialog dialog=new AlertDialog.Builder(owner).setTitle("选择要上传的文件夹").setView(panel).setNegativeButton("取消",null).setPositiveButton("选择此文件夹",(d,w)->selected.accept(current[0])).create();Runnable[] render={null};render[0]=()->{File directory=current[0];path.setText(directory.getAbsolutePath());owner.io.execute(()->{File[] folders=directory.listFiles(File::isDirectory);owner.runOnUiThread(()->{if(!dialog.isShowing())return;rows.removeAllViews();Button up=owner.toolbarTextButton("上一级");up.setOnClickListener(v->{if(current[0].getParentFile()!=null){current[0]=current[0].getParentFile();render[0].run();}});rows.addView(up);if(folders==null){rows.addView(owner.text("没有访问此目录的权限",13,owner.MUTED));return;}Arrays.sort(folders,Comparator.comparing(File::getName));for(File child:folders){Button row=owner.toolbarTextButton(child.getName());row.setOnClickListener(v->{current[0]=child;render[0].run();});rows.addView(row,new LinearLayout.LayoutParams(-1,owner.dp(48)));}});});};owner.showRounded(dialog);render[0].run();}
  private void localImage(String uri,java.util.function.Consumer<File> done){owner.io.execute(()->{try{File output=File.createTempFile("cloud-icon-",".png",owner.getCacheDir());try(InputStream input=owner.getContentResolver().openInputStream(android.net.Uri.parse(uri));OutputStream target=new FileOutputStream(output)){byte[] buffer=new byte[8192];int count;while((count=input.read(buffer))!=-1)target.write(buffer,0,count);}owner.runOnUiThread(()->done.accept(output));}catch(Exception error){owner.runOnUiThread(()->owner.showNotice(owner.friendlyError(error),true));}});}
  private void editValue(String title,String value,java.util.function.Consumer<String> apply){LinearLayout panel=panel();EditText field=input(panel,title,false);field.setText(value);AlertDialog dialog=new AlertDialog.Builder(owner).setTitle(title).setView(panel).setNegativeButton("取消",null).setPositiveButton("保存",(d,w)->apply.accept(field.getText().toString())).create();owner.showRounded(dialog);}
  private LinearLayout panel(){LinearLayout panel=new LinearLayout(owner);panel.setOrientation(LinearLayout.VERTICAL);panel.setPadding(owner.dp(20),owner.dp(8),owner.dp(20),owner.dp(8));return panel;}
  private EditText input(LinearLayout panel,String hint,boolean password){EditText field=owner.sourceInput(hint,android.text.InputType.TYPE_CLASS_TEXT|(password?android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD:0));panel.addView(field,new LinearLayout.LayoutParams(-1,owner.dp(56)));return field;}
  private void confirm(String title,String message,Runnable apply){String action=title.startsWith("删除")?"删除":title.startsWith("移除")?"移除":"确认";AlertDialog dialog=new AlertDialog.Builder(owner).setTitle(title).setMessage(message).setNegativeButton("取消",null).setPositiveButton(action,(d,w)->apply.run()).create();owner.showRounded(dialog);}
  private final class FileAdapter extends BaseAdapter {
    List<CloudFilesClient.CloudItem> visible(){List<CloudFilesClient.CloudItem> out=new ArrayList<>();String needle=query.toLowerCase(Locale.ROOT);for(CloudFilesClient.CloudItem item:items)if(item.name.toLowerCase(Locale.ROOT).contains(needle))out.add(item);return out;}
    public int getCount(){return visible().size();}public Object getItem(int position){return visible().get(position);}public long getItemId(int position){return position;}
    public View getView(int position,View recycled,ViewGroup parent){CloudFilesClient.CloudItem item=visible().get(position);LinearLayout row=new LinearLayout(owner);row.setGravity(Gravity.CENTER_VERTICAL);row.setPadding(owner.dp(4),owner.dp(8),owner.dp(4),owner.dp(8));CheckBox check=new CheckBox(owner);check.setChecked(selected.contains(item.id));check.setClickable(false);check.setButtonTintList(android.content.res.ColorStateList.valueOf(owner.PRIMARY));row.addView(check);ImageView icon=new ImageView(owner);icon.setImageResource(item.folder?R.drawable.ic_folder:R.drawable.ic_download);icon.setColorFilter(owner.PRIMARY);row.addView(icon,new LinearLayout.LayoutParams(owner.dp(32),owner.dp(32)));if(!item.iconUrl.isEmpty())owner.loadImage(item.iconUrl,icon);TextView text=owner.text(item.name+"\n"+(item.folder?"文件夹":item.size+" B")+(item.hasPassword?" · 有密码":""),14,owner.TEXT);text.setMaxLines(3);text.setPadding(owner.dp(12),0,owner.dp(6),0);row.addView(text,new LinearLayout.LayoutParams(0,-2,1));ImageButton menu=owner.iconButton(R.drawable.ic_more,"管理 "+item.name);menu.setEnabled(!busy);menu.setOnClickListener(v->itemMenu(item));row.addView(menu,new LinearLayout.LayoutParams(owner.dp(48),owner.dp(48)));row.setMinimumHeight(owner.dp(68));return row;}
  }
}
