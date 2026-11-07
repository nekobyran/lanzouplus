package cc.nkbr.lanzouplus;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.text.InputType;
import android.widget.*;
import java.util.Arrays;
import java.util.List;

/** Sync task management for the cloud folder currently shown by CloudManagement. */
final class CloudSyncUi {
  private static final int PICK_DIRECTORY = 44197;
  private final MainActivity owner;
  private final CloudFilesClient client;
  private final CloudSyncTasks tasks;
  private String pendingAccount="", pendingType="", pendingFolder="", pendingName="";
  private long reauthorizeTaskId;
  CloudSyncUi(MainActivity owner, CloudFilesClient client) {
    this.owner=owner; this.client=client; tasks=new CloudSyncTasks(owner);
  }
  void saveState(Bundle out) {
    out.putString("syncAccount",pendingAccount); out.putString("syncType",pendingType);
    out.putString("syncFolder",pendingFolder); out.putString("syncName",pendingName);out.putLong("syncReauthorizeTask",reauthorizeTaskId);
  }
  void restoreState(Bundle in) {
    if(in==null)return;
    pendingAccount=in.getString("syncAccount",""); pendingType=in.getString("syncType","");
    pendingFolder=in.getString("syncFolder",""); pendingName=in.getString("syncName","");reauthorizeTaskId=in.getLong("syncReauthorizeTask",0);
  }
  boolean onActivityResult(int request,int result,Intent data) {
    if(request!=PICK_DIRECTORY)return false;
    if(result==Activity.RESULT_OK && data!=null && data.getData()!=null) {
      Uri uri=data.getData();
      try {
        int flags=data.getFlags() & (Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_WRITE_URI_PERMISSION);
        int required=Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_WRITE_URI_PERMISSION;
        if((flags & required)!=required)throw new SecurityException("目录未授予读写权限，请重新选择");
        owner.getContentResolver().takePersistableUriPermission(uri,required);
        if(reauthorizeTaskId!=0){long id=reauthorizeTaskId;reauthorizeTaskId=0;long previous=tasks.pendingAutomaticInterval(id);tasks.reauthorizeRestoredFolder(id,uri);String message="目录已重新授权；任务保持手动同步"+(previous>0?"。此前间隔为 "+previous+" 分钟，可在任务中重新设置。":"。");AlertDialog notice=new AlertDialog.Builder(owner).setTitle("目录授权完成").setMessage(message).setPositiveButton("确定",null).create();owner.showRounded(notice);}
        else createFolderForm(uri);
      } catch(Exception e) { owner.showNotice("无法保存目录授权："+owner.friendlyError(e),true); }
    }
    reauthorizeTaskId=0;return true;
  }
  void show(CloudFilesClient.Provider provider,String folder,String folderName) {
    LinearLayout panel=panel();
    Button add=owner.button("新建本地目录同步"); panel.addView(add);
    Button github=owner.button("新建 GitHub 发行版同步"); panel.addView(github);
    Button backup=owner.button("GitHub 应用资料备份"); panel.addView(backup);
    AlertDialog dialog=new AlertDialog.Builder(owner).setTitle("同步管理")
      .setView(owner.limitedDialogScroll(panel,480)).setNegativeButton("关闭",null).create();
    add.setOnClickListener(v->{dialog.dismiss();pickDirectory(provider,folder,folderName);});
    github.setOnClickListener(v->{dialog.dismiss();createGithubForm(provider,folder,folderName);});
    backup.setOnClickListener(v->{dialog.dismiss();createBackupForm();});
    List<CloudSyncTasks.Task> list=tasks.list();
    if(list.isEmpty())panel.addView(owner.text("暂无同步任务",14,owner.MUTED));
    for(CloudSyncTasks.Task task:list) {
      boolean needsAuthorization="folder".equals(task.kind)&&task.sourceUri.isEmpty();
      TextView row=owner.text(task.name+"\n"+kind(task)+" · "+interval(task.intervalMinutes)+"\n"+(needsAuthorization?"需重新授权本地目录 · ":"")+"已同步 "+task.syncedFiles+" 个文件 · "+formatBytes(task.syncedBytes)+" · "+status(task),14,owner.TEXT);
      row.setPadding(owner.dp(8),owner.dp(12),owner.dp(8),owner.dp(12));
      row.setClickable(true); row.setFocusable(true); row.setOnClickListener(v->{dialog.dismiss();taskActions(task,provider,folder,folderName);});
      panel.addView(row,new LinearLayout.LayoutParams(-1,-2));
    }
    owner.showRounded(dialog);
  }
  private void createGithubForm(CloudFilesClient.Provider provider,String folder,String folderName) {
    final String account;
    try { account=client.currentAccountName(provider); }
    catch(Exception e){owner.showNotice(owner.friendlyError(e),true);return;}
    if(account==null||account.isEmpty()){owner.showNotice("请先登录当前云盘账号",true);return;}
    final GithubAccountClient.Profile profile;
    try{GithubAccountClient github=GithubAccountClient.production(owner);profile=github.cachedProfile();if(profile==null||!github.hasUsableSession())throw new IllegalStateException("请先在设置中登录 GitHub");}
    catch(Exception e){owner.showNotice(owner.friendlyError(e),true);return;}
    LinearLayout panel=panel();
    panel.addView(owner.text("目标："+folderName+"。每个任务可单独设置一个仓库的匹配词和云端子目录。",13,owner.MUTED));
    EditText name=input("任务名称",InputType.TYPE_CLASS_TEXT);panel.addView(name);
    EditText repository=input("GitHub 仓库（所有者/仓库名）",InputType.TYPE_CLASS_TEXT);panel.addView(repository);
    EditText match=input("文件名匹配词（留空匹配全部）",InputType.TYPE_CLASS_TEXT);panel.addView(match);
    EditText subdirectory=input("云端子目录名称",InputType.TYPE_CLASS_TEXT);panel.addView(subdirectory);
    EditText extension=input("需加密压缩的文件扩展名（逗号分隔，留空不压缩）",InputType.TYPE_CLASS_TEXT);panel.addView(extension);
    EditText minutes=input("上传间隔（分钟，留空为手动，至少 15）",InputType.TYPE_CLASS_NUMBER);panel.addView(minutes);
    EditText password=input("云分享密码（可选）",InputType.TYPE_CLASS_TEXT|InputType.TYPE_TEXT_VARIATION_PASSWORD);panel.addView(password);
    EditText archivePassword=input("压缩包密码（选择加密压缩时必填）",InputType.TYPE_CLASS_TEXT|InputType.TYPE_TEXT_VARIATION_PASSWORD);panel.addView(archivePassword);
    AlertDialog dialog=new AlertDialog.Builder(owner).setTitle("GitHub 发行版同步").setView(owner.limitedDialogScroll(panel,470))
      .setNegativeButton("取消",null).setPositiveButton("创建",null).create();owner.showRounded(dialog);
    dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v->{
      char[] secret=password.getText().toString().toCharArray(), archiveSecret=archivePassword.getText().toString().toCharArray();
      try {
        if(!extension.getText().toString().trim().isEmpty()&&archiveSecret.length==0)throw new IllegalArgumentException("请输入压缩包密码");
        String[] parts=repository.getText().toString().trim().split("/",-1);
        if(parts.length!=2||parts[0].trim().isEmpty()||parts[1].trim().isEmpty())throw new IllegalArgumentException("请输入所有者/仓库名");
        org.json.JSONObject item=new org.json.JSONObject();item.put("owner",parts[0].trim());item.put("repo",parts[1].trim());
        item.put("match",match.getText().toString().trim());item.put("subdirectory",subdirectory.getText().toString().trim());
        org.json.JSONObject config=new org.json.JSONObject();config.put("githubLogin",profile.login);config.put("repositories",new org.json.JSONArray().put(item));config.put("archiveFormat",extension.getText().toString().trim());
        CloudSyncTasks.Task t=new CloudSyncTasks.Task();t.kind="github_release";t.configJson=config.toString();
        t.name=name.getText().toString().trim();t.accountId=account;t.cloudType=provider==CloudFilesClient.Provider.PREMIUM?"premium":"ordinary";
        t.cloudFolderId=folder;t.intervalMinutes=parseMinutes(minutes);tasks.create(t,secret,archiveSecret);
        password.setText("");archivePassword.setText("");dialog.dismiss();owner.showNotice("同步任务已创建",false);
      }catch(Exception e){owner.showNotice(owner.friendlyError(e),true);}finally{Arrays.fill(secret,'\0');Arrays.fill(archiveSecret,'\0');}
    });
  }
  private void createBackupForm() {
    final GithubAccountClient github=GithubAccountClient.production(owner);
    final GithubAccountClient.Profile profile;
    try {profile=github.cachedProfile();if(profile==null||!github.hasUsableSession())throw new IllegalStateException("请先在设置中登录 GitHub");}
    catch(Exception e){owner.showNotice(owner.friendlyError(e),true);return;}
    LinearLayout panel=panel();
    panel.addView(owner.text("备份使用当前 GitHub 账号的私有仓库；备份内容使用密码加密。首次执行备份时，如仓库不存在将自动创建。",13,owner.MUTED));
    EditText name=input("任务名称",InputType.TYPE_CLASS_TEXT);name.setText("应用资料备份");panel.addView(name);
    EditText repo=input("私有仓库名称",InputType.TYPE_CLASS_TEXT);panel.addView(repo);
    EditText minutes=input("备份间隔（分钟，留空为手动，至少 15）",InputType.TYPE_CLASS_NUMBER);panel.addView(minutes);
    EditText password=input("备份密码（必填）",InputType.TYPE_CLASS_TEXT|InputType.TYPE_TEXT_VARIATION_PASSWORD);panel.addView(password);
    AlertDialog dialog=new AlertDialog.Builder(owner).setTitle("GitHub 应用资料备份").setView(owner.limitedDialogScroll(panel,420))
      .setNegativeButton("取消",null).setPositiveButton("创建",null).create();owner.showRounded(dialog);
    dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v->{
      char[] secret=password.getText().toString().toCharArray();
      try {
        String repository=repo.getText().toString().trim();
        if(!repository.matches("[A-Za-z0-9_.-]+"))throw new IllegalArgumentException("请输入有效的仓库名称");
        if(secret.length==0)throw new IllegalArgumentException("请输入备份密码");
        CloudSyncTasks.Task t=new CloudSyncTasks.Task();t.kind="github_backup";t.name=name.getText().toString().trim();
        t.configJson=new org.json.JSONObject().put("githubLogin",profile.login).put("repository",repository).toString();
        t.intervalMinutes=parseMinutes(minutes);tasks.create(t,secret);password.setText("");dialog.dismiss();owner.showNotice("备份任务已创建",false);
      }catch(Exception e){owner.showNotice(owner.friendlyError(e),true);}finally{Arrays.fill(secret,'\0');}
    });
  }
  private void restoreBackup(CloudSyncTasks.Task task) {
    AlertDialog confirm=new AlertDialog.Builder(owner).setTitle("从 GitHub 恢复资料？")
      .setMessage("设置不会自动恢复；不同或缺失的设置保留本机并计入冲突。其他冲突项也会保留本地和远端资料。完成后请重启应用，并重新选择需要授权的目录。")
      .setNegativeButton("取消",null).setPositiveButton("恢复",(d,w)->{
        owner.showNotice("正在读取备份",false);
        owner.io.execute(()->{
          char[] secret=null;
          try {
            org.json.JSONObject config=new org.json.JSONObject(task.configJson);
            GithubBackupRepository repository=new GithubBackupRepository(GithubAccountClient.production(owner),config.getString("githubLogin"),config.getString("repository"));
            secret=tasks.password(task.id);
            List<org.json.JSONObject> records=new GithubBackupRecords(repository,owner).readAll(secret);
            int conflicts=new GithubBackupState(owner,owner.core).restore(records);
            owner.runOnUiThread(()->{AlertDialog result=new AlertDialog.Builder(owner).setTitle("恢复结果").setMessage(conflicts>0?"已合并可安全恢复的资料；"+conflicts+" 项存在冲突，无法自动选择，已保留本机和远端资料。请重启应用并重新选择目录授权。":"已合并可安全恢复的资料；设置未自动恢复。请重启应用并重新选择目录授权。").setPositiveButton("确定",null).create();owner.showRounded(result);});
          }catch(Exception e){String message=owner.friendlyError(e);owner.runOnUiThread(()->owner.showNotice("恢复失败："+message,true));}
          finally{if(secret!=null)Arrays.fill(secret,'\0');}
        });
      }).create();owner.showRounded(confirm);
  }
  private void previewBackupConflicts(CloudSyncTasks.Task task) {
    AlertDialog confirm=new AlertDialog.Builder(owner).setTitle("查看备份冲突？")
      .setMessage("将使用保存的备份密码读取并解密远端版本，冲突信息仅显示在本机。")
      .setNegativeButton("取消",null).setPositiveButton("查看",(d,w)->owner.io.execute(()->{
        char[] secret=null;
        try {
          org.json.JSONObject config=new org.json.JSONObject(task.configJson);
          GithubBackupRepository repository=new GithubBackupRepository(GithubAccountClient.production(owner),config.getString("githubLogin"),config.getString("repository"));
          GithubBackupRecords records=new GithubBackupRecords(repository,owner);
          secret=tasks.password(task.id);
          List<org.json.JSONObject> local=new GithubBackupState(owner,owner.core).snapshot();
          List<GithubBackupRecords.Conflict> conflicts=records.previewConflicts(local,secret,null);
          List<org.json.JSONObject> remote=records.readAll(secret);
          owner.runOnUiThread(()->showBackupConflicts(task,conflicts,local,remote));
        }catch(Exception e){String message=owner.friendlyError(e);owner.runOnUiThread(()->owner.showNotice("无法读取备份冲突："+message,true));}
        finally{if(secret!=null)Arrays.fill(secret,'\0');}
      })).create();owner.showRounded(confirm);
  }
  private void showBackupConflicts(CloudSyncTasks.Task task,List<GithubBackupRecords.Conflict> conflicts,List<org.json.JSONObject> local,List<org.json.JSONObject> remote) {
    LinearLayout panel=panel();GithubBackupState state=new GithubBackupState(owner,owner.core);
    if(conflicts.isEmpty())panel.addView(owner.text("没有检测到备份冲突",14,owner.MUTED));
    for(GithubBackupRecords.Conflict conflict:conflicts){
      org.json.JSONObject expected=null;for(org.json.JSONObject item:local)if(conflict.kind.equals(item.optString("kind"))&&conflict.key.equals(item.optString("key"))){expected=item;break;}
      final org.json.JSONObject localRecord=expected;
      List<org.json.JSONObject> candidates=new java.util.ArrayList<>();
      for(GithubBackupRecords.VersionRef version:conflict.remoteVersions)for(org.json.JSONObject item:remote)
        if(version.versionId.equals(item.optString("_version"))&&conflict.kind.equals(item.optString("kind"))&&conflict.key.equals(item.optString("key")))candidates.add(item);
      boolean selectable="setting".equals(conflict.kind)&&localRecord!=null&&!candidates.isEmpty()
          &&candidates.size()==conflict.remoteVersions.size()&&state.canApplySelectedSetting(conflict.key);
      StringBuilder details=new StringBuilder(conflict.kind+" · "+conflict.key+"\n"+conflict.reason+"\n本机版本及 "+conflict.remoteVersions.size()+" 个远端版本均已保留");
      details.append(selectable?"\n点击预览并选择远端版本":"\n此类型暂不支持覆盖选择");
      TextView row=owner.text(details.toString(),14,owner.TEXT);
      row.setPadding(owner.dp(8),owner.dp(12),owner.dp(8),owner.dp(12));
      if(selectable){row.setClickable(true);row.setFocusable(true);row.setOnClickListener(v->chooseSettingVersion(task,conflict,localRecord,candidates));}
      panel.addView(row);
    }
    AlertDialog dialog=new AlertDialog.Builder(owner).setTitle("备份冲突").setView(owner.limitedDialogScroll(panel,480)).setPositiveButton("关闭",null).create();owner.showRounded(dialog);
  }
  private void chooseSettingVersion(CloudSyncTasks.Task task,GithubBackupRecords.Conflict conflict,org.json.JSONObject local,List<org.json.JSONObject> candidates){
    String[] choices=new String[candidates.size()];
    for(int i=0;i<candidates.size();i++){
      org.json.JSONObject candidate=candidates.get(i);long at=candidate.optLong("at",0);
      String time=at>0?android.text.format.DateFormat.format("yyyy-MM-dd HH:mm",at).toString():"时间未知";
      boolean equal=local.optString("value").equals(candidate.optString("value"));
      choices[i]="远端版本 "+(i+1)+" · "+time+" · "+(equal?"与本机内容相同":"与本机内容不同");
    }
    AlertDialog dialog=new AlertDialog.Builder(owner).setTitle("选择设置版本")
      .setItems(choices,(d,which)->confirmSettingVersion(task,conflict,local,candidates.get(which)))
      .setNegativeButton("取消",null).create();owner.showRounded(dialog);
  }
  private void confirmSettingVersion(CloudSyncTasks.Task task,GithubBackupRecords.Conflict conflict,org.json.JSONObject local,org.json.JSONObject chosen){
    AlertDialog confirm=new AlertDialog.Builder(owner).setTitle("应用远端设置？")
      .setMessage("将先加密保存当前本机版本，再应用所选远端设置。若本机或远端版本发生变化，操作会停止，请重新查看。")
      .setNegativeButton("取消",null).setPositiveButton("应用",(d,w)->owner.io.execute(()->{
        char[] secret=null;
        try{
          org.json.JSONObject config=new org.json.JSONObject(task.configJson);
          GithubBackupRepository repository=new GithubBackupRepository(GithubAccountClient.production(owner),config.getString("githubLogin"),config.getString("repository"));
          GithubBackupRecords records=new GithubBackupRecords(repository,owner);GithubBackupState state=new GithubBackupState(owner,owner.core);
          List<String> heads=new java.util.ArrayList<>();for(GithubBackupRecords.VersionRef ref:conflict.remoteVersions)heads.add(ref.versionId);
          secret=tasks.password(task.id);records.resolveConflictAndApply(local,chosen,heads,secret,null,state::applySelectedSetting);
          owner.runOnUiThread(()->owner.showNotice("已应用所选设置；本机原版本已加密保留",false));
        }catch(Exception e){String message=owner.friendlyError(e);owner.runOnUiThread(()->owner.showNotice("未能应用设置："+message,true));}
        finally{if(secret!=null)Arrays.fill(secret,'\0');}
      })).create();owner.showRounded(confirm);
  }
  private void pickDirectory(CloudFilesClient.Provider provider,String folder,String name) {
    try {
      reauthorizeTaskId=0;String account=client.currentAccountName(provider);
      if(account==null||account.isEmpty()){owner.showNotice("请先登录当前云盘账号",true);return;}
      pendingAccount=account;pendingType=provider==CloudFilesClient.Provider.PREMIUM?"premium":"ordinary";
      pendingFolder=folder;pendingName=name;
      Intent intent=new Intent(Intent.ACTION_OPEN_DOCUMENT_TREE);
      intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION|Intent.FLAG_GRANT_WRITE_URI_PERMISSION|Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION);
      owner.startActivityForResult(intent,PICK_DIRECTORY);
    } catch(Exception e){owner.showNotice(owner.friendlyError(e),true);}
  }
  private void createFolderForm(Uri uri) {
    LinearLayout panel=panel();
    panel.addView(owner.text("目标："+pendingName+"\n来源：已授权的本地目录",13,owner.MUTED));
    EditText name=input("任务名称",InputType.TYPE_CLASS_TEXT);name.setText(pendingName+"同步");panel.addView(name);
    EditText minutes=input("自动同步间隔（分钟，留空为手动，至少 15）",InputType.TYPE_CLASS_NUMBER);panel.addView(minutes);
    EditText extension=input("需加密压缩的文件扩展名（逗号分隔，留空不压缩）",InputType.TYPE_CLASS_TEXT);panel.addView(extension);
    EditText password=input("云分享密码（可选）",InputType.TYPE_CLASS_TEXT|InputType.TYPE_TEXT_VARIATION_PASSWORD);panel.addView(password);
    EditText archivePassword=input("压缩包密码（选择加密压缩时必填）",InputType.TYPE_CLASS_TEXT|InputType.TYPE_TEXT_VARIATION_PASSWORD);panel.addView(archivePassword);
    AlertDialog dialog=new AlertDialog.Builder(owner).setTitle("新建同步任务").setView(owner.limitedDialogScroll(panel,400))
      .setNegativeButton("取消",null).setPositiveButton("创建",null).create();
    owner.showRounded(dialog);
    dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v->{
      char[] secret=password.getText().toString().toCharArray(), archiveSecret=archivePassword.getText().toString().toCharArray();
      try {
        if(!extension.getText().toString().trim().isEmpty()&&archiveSecret.length==0)throw new IllegalArgumentException("请输入压缩包密码");
        CloudSyncTasks.Task t=new CloudSyncTasks.Task();t.configJson=new org.json.JSONObject().put("archiveFormat",extension.getText().toString().trim()).toString();t.name=name.getText().toString().trim();t.accountId=pendingAccount;
        t.cloudType=pendingType;t.cloudFolderId=pendingFolder;t.sourceUri=uri.toString();t.intervalMinutes=parseMinutes(minutes);
        tasks.create(t,secret,archiveSecret);password.setText("");archivePassword.setText("");dialog.dismiss();owner.showNotice("同步任务已创建",false);
      } catch(Exception e){owner.showNotice(owner.friendlyError(e),true);} finally {Arrays.fill(secret,'\0');Arrays.fill(archiveSecret,'\0');}
    });
  }
  private void taskActions(CloudSyncTasks.Task task,CloudFilesClient.Provider provider,String folder,String folderName) {
    boolean backup="github_backup".equals(task.kind);
    boolean needsAuthorization="folder".equals(task.kind)&&task.sourceUri.isEmpty();
    String[] actions=backup?new String[]{"立即备份","设置自动同步间隔","查看上传记录","删除任务","从 GitHub 恢复","查看备份冲突"}:needsAuthorization?new String[]{"重新授权本地目录","查看上传记录","删除任务"}:"folder".equals(task.kind)?new String[]{"立即同步","设置自动同步间隔","查看上传记录","删除任务","查看待处理事务"}:new String[]{"立即同步","设置自动同步间隔","查看上传记录","删除任务","查看待核验上传"};
    AlertDialog dialog=new AlertDialog.Builder(owner).setTitle(task.name).setItems(actions,(d,which)->{
      if(needsAuthorization){if(which==0)reauthorizeFolder(task);else if(which==1)showUploads(task.id);else confirmDelete(task,provider,folder,folderName);return;}
      if(which==0) {try{tasks.runNow(task.id);owner.showNotice("已请求同步，请在任务中查看结果",false);}catch(Exception e){owner.showNotice(owner.friendlyError(e),true);}}
      else if(which==1) setInterval(task);
      else if(which==2) showUploads(task.id);
      else if(which==4) {if(backup)restoreBackup(task);else if("folder".equals(task.kind))showPending(task);else showUnknownUploads(task);}
      else if(which==5) previewBackupConflicts(task);
      else confirmDelete(task,provider,folder,folderName);
    }).setNegativeButton("返回",(d,w)->show(provider,folder,folderName)).create();owner.showRounded(dialog);
  }
  private void confirmDelete(CloudSyncTasks.Task task,CloudFilesClient.Provider provider,String folder,String folderName){
    AlertDialog confirm=new AlertDialog.Builder(owner).setTitle("删除同步任务？").setMessage("上传记录也会删除，云端文件保留。")
      .setNegativeButton("取消",null).setPositiveButton("删除",(x,w)->{tasks.delete(task.id);show(provider,folder,folderName);}).create();owner.showRounded(confirm);
  }
  private void reauthorizeFolder(CloudSyncTasks.Task task){
    try{
      reauthorizeTaskId=task.id;
      Intent intent=new Intent(Intent.ACTION_OPEN_DOCUMENT_TREE);
      intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION|Intent.FLAG_GRANT_WRITE_URI_PERMISSION|Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION);
      owner.startActivityForResult(intent,PICK_DIRECTORY);
    }catch(Exception e){reauthorizeTaskId=0;owner.showNotice(owner.friendlyError(e),true);}
  }
  private void showUnknownUploads(CloudSyncTasks.Task task){
    owner.io.execute(()->{
      try{
        List<CloudGitHubReleaseSync.PendingUpload> pending=CloudGitHubReleaseSync.unknownUploads(owner.getApplicationContext(),task.id);
        owner.runOnUiThread(()->{
          LinearLayout panel=panel();
          panel.addView(owner.text("上传结果未知时，请先检查对应云端目录。清除只移除本机待处理记录；再次同步可能创建重复副本。",13,owner.MUTED));
          if(pending.isEmpty())panel.addView(owner.text("当前没有待核验的上传",14,owner.MUTED));
          AlertDialog dialog=new AlertDialog.Builder(owner).setTitle("待核验上传").setView(owner.limitedDialogScroll(panel,480)).setNegativeButton("关闭",null).create();
          for(CloudGitHubReleaseSync.PendingUpload item:pending){
            TextView row=owner.text(item.repository+"\n"+item.name,14,owner.TEXT);
            row.setPadding(owner.dp(8),owner.dp(12),owner.dp(8),owner.dp(12));row.setClickable(true);row.setFocusable(true);
            row.setOnClickListener(v->{dialog.dismiss();confirmClearUnknownUpload(task,item);});panel.addView(row);
          }
          owner.showRounded(dialog);
        });
      }catch(Exception e){String message=owner.friendlyError(e);owner.runOnUiThread(()->owner.showNotice("无法读取待核验上传："+message,true));}
    });
  }
  private void confirmClearUnknownUpload(CloudSyncTasks.Task task,CloudGitHubReleaseSync.PendingUpload item){
    AlertDialog confirm=new AlertDialog.Builder(owner).setTitle("清除待处理记录？")
      .setMessage("请先检查云端目录中的 "+item.name+"。确认已核验后，可清除本机记录；云端文件不会删除，再次同步可能创建重复副本。")
      .setNegativeButton("取消",null).setPositiveButton("已核验，清除",(d,w)->owner.io.execute(()->{
        try{
          CloudGitHubReleaseSync.clearUnknownUpload(owner.getApplicationContext(),task.id,item.token);
          owner.runOnUiThread(()->{owner.showNotice("已清除本机待处理记录",false);showUnknownUploads(task);});
        }catch(Exception e){String message=owner.friendlyError(e);owner.runOnUiThread(()->owner.showNotice("无法清除待处理记录："+message,true));}
      })).create();owner.showRounded(confirm);
  }
  private void showPending(CloudSyncTasks.Task task) {
    owner.io.execute(()->{
      try {
        CloudFolderSyncEngine.PendingInfo pending=new CloudFolderSyncEngine(owner).pending(Long.toString(task.id));
        owner.runOnUiThread(()->{
          AlertDialog.Builder builder=new AlertDialog.Builder(owner).setTitle("待处理同步事务").setMessage(pending.present?pending.message:"当前没有待处理事务")
            .setNegativeButton("关闭",null);
          if(pending.present)builder.setPositiveButton("重试同步",(d,w)->{
            try{tasks.runNow(task.id);owner.showNotice("已请求重试，请在任务中查看结果",false);}
            catch(Exception e){owner.showNotice(owner.friendlyError(e),true);}
          }).setNeutralButton("放弃待处理事务",(d,w)->confirmAbandonPending(task));
          owner.showRounded(builder.create());
        });
      }catch(Exception e){String message=owner.friendlyError(e);owner.runOnUiThread(()->owner.showNotice("无法读取待处理事务："+message,true));}
    });
  }
  private void confirmAbandonPending(CloudSyncTasks.Task task) {
    AlertDialog confirm=new AlertDialog.Builder(owner).setTitle("放弃待处理事务？")
      .setMessage("将保留本地和云端已有文件及发现的副本。确认放弃此事务后，可再次运行同步。")
      .setNegativeButton("取消",null).setPositiveButton("确认放弃",(d,w)->owner.io.execute(()->{
        try {
          CloudFilesClient.Provider provider="premium".equals(task.cloudType)?CloudFilesClient.Provider.PREMIUM:CloudFilesClient.Provider.ORDINARY;
          new CloudFolderSyncEngine(owner).abandonPending(Uri.parse(task.sourceUri),provider,task.accountId,task.cloudFolderId,Long.toString(task.id));
          owner.runOnUiThread(()->owner.showNotice("已放弃待处理事务，文件均已保留",false));
        }catch(Exception e){String message=owner.friendlyError(e);owner.runOnUiThread(()->owner.showNotice("无法放弃待处理事务："+message,true));}
      })).create();owner.showRounded(confirm);
  }
  private void setInterval(CloudSyncTasks.Task task) {
    LinearLayout panel=panel(); EditText minutes=input("分钟；留空或 0 为手动，至少 15",InputType.TYPE_CLASS_NUMBER);
    if(task.intervalMinutes>0)minutes.setText(Long.toString(task.intervalMinutes));panel.addView(minutes);
    AlertDialog dialog=new AlertDialog.Builder(owner).setTitle("自动同步间隔").setView(panel).setNegativeButton("取消",null).setPositiveButton("保存",null).create();owner.showRounded(dialog);
    dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v->{try{tasks.setAutomatic(task.id,parseMinutes(minutes));dialog.dismiss();owner.showNotice("同步间隔已保存",false);}catch(Exception e){owner.showNotice(owner.friendlyError(e),true);}});
  }
  void showUploads(){showUploads(0);}
  private void showUploads(long taskId) {
    LinearLayout panel=panel();
    panel.addView(owner.text("正在读取上传记录…",14,owner.MUTED));
    AlertDialog dialog=new AlertDialog.Builder(owner).setTitle("上传列表").setView(owner.limitedDialogScroll(panel,480)).setPositiveButton("关闭",null).create();
    android.os.Handler handler=new android.os.Handler(android.os.Looper.getMainLooper());
    java.util.concurrent.atomic.AtomicBoolean active=new java.util.concurrent.atomic.AtomicBoolean(true);
    android.content.Context appContext=owner.getApplicationContext();
    final List<String> displayed=new java.util.ArrayList<>();
    Runnable refresh=new Runnable(){
      @Override public void run(){
        if(!active.get())return;
        owner.io.execute(()->{
          List<String> rows=new java.util.ArrayList<>();
          try{
            List<CloudSyncTasks.Upload> uploads=taskId==0?tasks.allUploads():tasks.uploads(taskId);
            for(CloudSyncTasks.Upload upload:uploads){
              String label=taskId==0&&upload.taskId==0?"手动上传":"同步任务";
              String extra=upload.message.isEmpty()?"":" · "+upload.message;
              rows.add(label+" · "+upload.state+"\n"+upload.path+" · "+android.text.format.Formatter.formatFileSize(appContext,upload.bytes)+extra);
            }
            if(rows.isEmpty())rows.add("暂无上传记录");
          }catch(Exception error){rows.clear();rows.add("无法读取上传记录");}
          synchronized(active){if(!active.get())return;
          handler.post(()->{
            if(!active.get()||!dialog.isShowing())return;
            if(!displayed.equals(rows)){
              displayed.clear();displayed.addAll(rows);panel.removeAllViews();
              for(String value:rows){TextView row=owner.text(value,13,owner.TEXT);row.setPadding(owner.dp(8),owner.dp(10),owner.dp(8),owner.dp(10));panel.addView(row);}
            }
            handler.postDelayed(this,1500);
          });}
        });
      }
    };
    dialog.setOnDismissListener(d->{synchronized(active){active.set(false);handler.removeCallbacksAndMessages(null);}});
    owner.showRounded(dialog);handler.post(refresh);
  }
  private LinearLayout panel(){LinearLayout p=new LinearLayout(owner);p.setOrientation(LinearLayout.VERTICAL);p.setPadding(owner.dp(16),owner.dp(8),owner.dp(16),owner.dp(8));return p;}
  private EditText input(String hint,int type){return owner.sourceInput(hint,type);}
  private long parseMinutes(EditText field){String value=field.getText().toString().trim();long n=value.isEmpty()?0:Long.parseLong(value);if(n!=0&&n<15)throw new IllegalArgumentException("自动同步间隔至少为 15 分钟");return n;}
  private String kind(CloudSyncTasks.Task t){return "folder".equals(t.kind)?"本地目录":("github_release".equals(t.kind)?"GitHub 发行版":"GitHub 资料备份");}
  private String interval(long minutes){return minutes==0?"手动":"每 "+minutes+" 分钟";}
  private String status(CloudSyncTasks.Task t){return t.message.isEmpty()?t.status:t.message;}
  private String formatBytes(long bytes){return android.text.format.Formatter.formatFileSize(owner,bytes);}
}
