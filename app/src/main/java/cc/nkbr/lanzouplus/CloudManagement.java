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
  void saveState(Bundle state){state.putBoolean("cloudPremium",provider==CloudFilesClient.Provider.PREMIUM);state.putStringArrayList("cloudIds",new ArrayList<>(folderIds));state.putStringArrayList("cloudNames",new ArrayList<>(folderNames));state.putString("cloudQuery",query);}
  void restoreState(Bundle state){if(state==null)return;provider=state.getBoolean("cloudPremium",false)?CloudFilesClient.Provider.PREMIUM:CloudFilesClient.Provider.ORDINARY;folderIds.clear();folderNames.clear();if(state.getStringArrayList("cloudIds")!=null)folderIds.addAll(state.getStringArrayList("cloudIds"));if(state.getStringArrayList("cloudNames")!=null)folderNames.addAll(state.getStringArrayList("cloudNames"));query=state.getString("cloudQuery","");}
  boolean onActivityResult(int request,int result,Intent data){return false;}
  private String folder(){return folderIds.isEmpty()?"-1":folderIds.get(folderIds.size()-1);}
  void show(){
    owner.pageDirection=1;owner.base();owner.pageKind=7;owner.activeSource=null;owner.clearFolderTrail();owner.systemBackAction=this::back;
    owner.primaryHeader("管理");controls.clear();
    ImageButton back=owner.iconButton(R.drawable.ic_back,"返回市场");back.setOnClickListener(v->owner.marketPages().showMarket(false));owner.pageHeaderRow.addView(back,0,new LinearLayout.LayoutParams(owner.dp(48),owner.dp(48)));
    ImageButton accountSettings=owner.iconButton(R.drawable.ic_settings,"云盘账号设置");accountSettings.setOnClickListener(v->owner.openCloudAccountSettings());owner.pageHeaderRow.addView(accountSettings);controls.add(accountSettings);
    ImageButton refresh=owner.iconButton(R.drawable.ic_refresh,"刷新云盘文件");refresh.setOnClickListener(v->load(false));owner.pageHeaderRow.addView(refresh);controls.add(refresh);
    LinearLayout providers=new LinearLayout(owner);
    providers.setGravity(Gravity.CENTER_VERTICAL);
    for(CloudFilesClient.Provider value:CloudFilesClient.Provider.values()){
      String label=value==CloudFilesClient.Provider.ORDINARY?"普通蓝奏云":"蓝奏云优享版";
      View tab=owner.underlineFilter(label,value==provider,()->{
        if(busy||provider==value)return;
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
    owner.root.addView(providerScroll,new LinearLayout.LayoutParams(-1,owner.dp(48)));
    boolean landscape=owner.getResources().getConfiguration().orientation==android.content.res.Configuration.ORIENTATION_LANDSCAPE;
    HorizontalScrollView pathScroll=new HorizontalScrollView(owner);pathScroll.setHorizontalScrollBarEnabled(false);breadcrumbs=new LinearLayout(owner);pathScroll.addView(breadcrumbs);renderBreadcrumbs();

    EditText filter=owner.sourceInput("搜索当前文件夹",android.text.InputType.TYPE_CLASS_TEXT);filter.setSingleLine(true);filter.setText(query);
    owner.root.addView(pathScroll,new LinearLayout.LayoutParams(-1,owner.dp(48)));
    owner.root.addView(filter,new LinearLayout.LayoutParams(-1,owner.dp(48)));
    filter.addTextChangedListener(new TextWatcher(){public void beforeTextChanged(CharSequence s,int a,int b,int c){}public void afterTextChanged(Editable e){}public void onTextChanged(CharSequence s,int a,int b,int c){query=s.toString();if(adapter!=null)adapter.refilter();}});
    LinearLayout actions=new LinearLayout(owner);actions.setGravity(Gravity.CENTER_VERTICAL);
    ImageButton add=owner.iconButton(R.drawable.ic_add,"新建文件夹");add.setOnClickListener(v->createFolder());actions.addView(add);controls.add(add);
    ImageButton upload=owner.iconButton(R.drawable.ic_folder,"上传文件或文件夹");upload.setOnClickListener(v->uploadMenu());actions.addView(upload);controls.add(upload);
    selection=owner.text("长按项目多选",12,owner.MUTED);actions.addView(selection,new LinearLayout.LayoutParams(0,owner.dp(48),1));
    Button selectedActions=owner.toolbarTextButton("所选项目");selectedActions.setOnClickListener(v->selectionMenu());actions.addView(selectedActions);controls.add(selectedActions);owner.root.addView(actions,new LinearLayout.LayoutParams(-1,owner.dp(48)));
    status=landscape?selection:owner.text("",12,owner.MUTED);status.setMaxLines(2);if(!landscape)owner.root.addView(status,new LinearLayout.LayoutParams(-1,owner.dp(44)));
    list=new ListView(owner);list.setDivider(new android.graphics.drawable.ColorDrawable(owner.DIV));list.setDividerHeight(owner.dp(1));adapter=new FileAdapter();list.setAdapter(adapter);
    android.widget.FrameLayout body=new android.widget.FrameLayout(owner);body.addView(list,new android.widget.FrameLayout.LayoutParams(-1,-1));
    LinearLayout empty=new LinearLayout(owner);empty.setOrientation(LinearLayout.VERTICAL);empty.setGravity(Gravity.CENTER);empty.addView(owner.text("此目录暂无项目；未登录时请前往设置添加账号",13,owner.MUTED));
    Button loginAction=owner.button("前往设置登录");loginAction.setOnClickListener(v->owner.openCloudAccountSettings());empty.addView(loginAction,new LinearLayout.LayoutParams(-2,owner.dp(48)));controls.add(loginAction);body.addView(empty,new android.widget.FrameLayout.LayoutParams(-1,-1));list.setEmptyView(empty);owner.root.addView(body,new LinearLayout.LayoutParams(-1,0,1));
    list.setOnItemClickListener((p,v,position,id)->{if(busy)return;CloudFilesClient.CloudItem item=adapter.visible().get(position);if(!selected.isEmpty()){toggle(item);return;}if(item.folder){folderIds.add(item.id);folderNames.add(item.name);load(false);}else showFileDetails(item);});
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
    if(adapter!=null)adapter.refilter();
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
    if(item.folder)labels.remove("下载");if(!item.folder&&provider==CloudFilesClient.Provider.ORDINARY)labels.add("自定义图标");if(!item.folder&&item.name.toLowerCase(Locale.ROOT).endsWith(".apk"))labels.add("替换安装包");
    AlertDialog dialog=new AlertDialog.Builder(owner).setTitle(item.name).setItems(labels.toArray(new String[0]),(d,which)->{switch(labels.get(which)){
      case "分享":share(item,false);break;case "加入列表源":share(item,true);break;case "下载":download(Collections.singletonList(item));break;
      case "重命名":editValue("重命名",item.name,value->work("名称已更新",()->{client.rename(item,value);return "";},true));break;
      case "移动":target(Collections.singletonList(item),false);break;case "复制":target(Collections.singletonList(item),true);break;
      case "设置密码":editValue("设置密码（留空取消密码）","",value->work("密码已更新",()->{client.setPassword(item,value);return "";},true));break;
      case "编辑简介":work(null,()->{String current=client.getDescription(item).description;owner.runOnUiThread(()->editValue("编辑简介",current,value->work("简介已更新",()->{client.setDescription(item,value);return "";},true)));return "";},false);break;
      case "删除":remove(Collections.singletonList(item));break;
      case "自定义图标":MarketFilePicker.chooseImage(owner,uri->localImage(uri,file->work("图标已更新",()->{client.setIcon(item.id,file.getAbsolutePath());return "";},true)));break;
      case "替换安装包":replaceApk(item);break;
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

  private void selectionMenu(){
    List<CloudFilesClient.CloudItem> chosen=selectedItems();
    String[] choices={"全选当前列表","取消选择","移动","复制","下载","分享","删除"};
    AlertDialog dialog=new AlertDialog.Builder(owner).setTitle("所选项目 · "+chosen.size()).setItems(choices,(d,which)->{
      if(which==0){
        for(CloudFilesClient.CloudItem item:adapter.visible())selected.add(item.id);
        update();
      }else if(which==1){
        selected.clear();
        update();
      }else if(chosen.isEmpty()){
        owner.showNotice("请先长按选择项目",false);
      }else if(which==2){
        target(chosen,false);
      }else if(which==3){
        target(chosen,true);
      }else if(which==4){
        download(chosen);
      }else if(which==5){
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
      }else{
        remove(chosen);
      }
    }).setNegativeButton("关闭",null).create();
    owner.showRounded(dialog);
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
          client.download(item,directory.getAbsolutePath()+File.separator);
          count++;
      }
      return count==0?"所选项目没有可下载的文件":"已下载 "+count+" 个文件至 "+directory.getAbsolutePath();
    },false);
  }

  private void downloadFragment(CloudFilesClient.CloudItem item){
    File directory=owner.selectedDownloadDirectory();
    work("下载完成",()->{
      client.download(item,directory.getAbsolutePath()+File.separator);
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
    String[] labels={"选择多个文件","选择 APK 并上传","上传文件夹","分片上传文件"};
    for(int i=0;i<labels.length;i++){
      final int action=i;Button button=owner.toolbarTextButton(labels[i]);panel.addView(button,new LinearLayout.LayoutParams(-1,owner.dp(48)));
      button.setOnClickListener(v->{
        dialog.dismiss();boolean w=wrap.isChecked(),image=icon.isChecked();
        if(action==0)pickLocalFiles(true,"",files->uploadFiles(files,w,image));
        else if(action==1)pickLocalFiles(false,".apk",files->uploadFiles(files,w,image));
        else if(action==2)localDirectory(file->{String target=folder();work("上传完成",()->{CloudFilesClient.DirectoryUploadResult result=client.uploadDirectory(target,file.getAbsolutePath(),CloudFilesClient.UploadOptions.defaults().compatibilityWrap(w).autoIcon(image));return "已上传 "+result.filesUploaded+" 个文件";},true);});
        else pickLocalFiles(false,"",files->showFragmentUploadDialog(files.get(0),w,image));
      });
    }
    owner.showRounded(dialog);
  }

  private void uploadFiles(List<File> files,boolean wrap,boolean autoIcon){
    if(files.isEmpty())return;
    String target=folder();
    CloudFilesClient.UploadOptions options=CloudFilesClient.UploadOptions.defaults()
        .compatibilityWrap(wrap)
        .autoIcon(autoIcon);
    work("上传完成",()->{
      int success=0;
      for(File file:files){
        client.upload(target,file.getAbsolutePath(),options);
        success++;
      }
      return "已上传 "+success+" 个文件";
    },true);
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
      work("分片上传完成",()->{CloudFilesClient.UploadResult result=client.uploadFragmented(target,file.getAbsolutePath(),options);return "已上传 "+result.fragmentPartCount+" 个分片";},true);
    });
  }

  private RadioButton radio(RadioGroup group,String label){
    RadioButton button=new RadioButton(owner);button.setText(label);button.setTextColor(owner.TEXT);button.setButtonTintList(ColorStateList.valueOf(owner.PRIMARY));group.addView(button);return button;
  }

  private void replaceApk(CloudFilesClient.CloudItem item){
    pickLocalFiles(false,".apk",files->{
      File replacement=files.get(0);
      work(null,()->{
        PackageInfo info=owner.getPackageManager().getPackageArchiveInfo(replacement.getAbsolutePath(),0);
        if(info==null)throw new CloudFilesClient.CloudException(CloudFilesClient.ERROR_INVALID,"无法读取所选 APK 信息");
        CloudFilesClient.ManagedDescription metadata=client.getDescription(item);
        owner.runOnUiThread(()->showReplaceApkConfirmation(item,replacement,info,metadata));
        return "";
      },false);
    });
  }

  private void showReplaceApkConfirmation(CloudFilesClient.CloudItem item,File file,PackageInfo info,CloudFilesClient.ManagedDescription metadata){
    LinearLayout panel=panel();
    panel.addView(owner.text("保留云端标题："+metadata.name,14,owner.TEXT));
    panel.addView(owner.text(metadata.description.isEmpty()?"云端简介为空":"保留简介："+metadata.description,13,owner.MUTED));
    panel.addView(owner.text("新安装包："+file.getName(),14,owner.TEXT));
    panel.addView(owner.text("包名："+info.packageName+"\n版本："+info.versionName+" ("+info.versionCode+")",13,owner.MUTED));
    CheckBox autoIcon=new CheckBox(owner);autoIcon.setText("从新 APK 提取并设置图标");autoIcon.setTextColor(owner.TEXT);autoIcon.setButtonTintList(ColorStateList.valueOf(owner.PRIMARY));
    autoIcon.setChecked(provider==CloudFilesClient.Provider.ORDINARY);autoIcon.setEnabled(provider==CloudFilesClient.Provider.ORDINARY);panel.addView(autoIcon);
    panel.addView(owner.text("新文件上传并确认标题、简介及所选图标后，旧文件移入回收站。",12,owner.MUTED));
    AlertDialog dialog=new AlertDialog.Builder(owner).setTitle("替换安装包").setView(owner.limitedDialogScroll(panel,380)).setNegativeButton("取消",null).setPositiveButton("确认换包",(d,w)->work("安装包已替换",()->{
      client.replaceApk(item,file.getAbsolutePath(),CloudFilesClient.UploadOptions.defaults().autoIcon(autoIcon.isChecked()));return "";
    },true)).create();owner.showRounded(dialog);
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

        CheckBox check=new CheckBox(owner);
        check.setClickable(false);
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
        layout.addView(menu,new LinearLayout.LayoutParams(owner.dp(48),owner.dp(48)));

        holder=new ViewHolder();
        holder.check=check;
        holder.icon=icon;
        holder.text=text;
        holder.menu=menu;
        layout.setTag(holder);
        row=layout;
      }

      holder.check.setChecked(selected.contains(item.id));
      holder.icon.setTag(item.iconUrl);
      holder.icon.setColorFilter(owner.PRIMARY);
      holder.icon.setImageResource(item.folder?R.drawable.ic_folder:R.drawable.ic_download);
      if(!item.iconUrl.isEmpty()){
        owner.loadImage(item.iconUrl,holder.icon);
      }
      holder.text.setText(item.name+"\n"+(item.folder?"文件夹":formatBytes(item.size))+(item.hasPassword?" · 有密码":""));
      holder.menu.setEnabled(!busy);
      holder.menu.setContentDescription("管理 "+item.name);
      holder.menu.setOnClickListener(v->itemMenu(item));
      return row;
    }
  }
}
