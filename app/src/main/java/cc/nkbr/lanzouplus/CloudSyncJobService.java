package cc.nkbr.lanzouplus;

import android.app.job.JobParameters;
import android.app.job.JobService;
import android.net.Uri;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import org.json.JSONArray;
import org.json.JSONObject;

/** Executes persisted jobs without Activity or process-local registration. */
public final class CloudSyncJobService extends JobService {
  @Override public android.content.Context createDeviceProtectedStorageContext(){
    return BackupPreferences.wrapContext(super.createDeviceProtectedStorageContext());
  }
  @Override public android.content.Context createConfigurationContext(android.content.res.Configuration configuration){
    return BackupPreferences.wrapContext(super.createConfigurationContext(configuration));
  }  @Override public android.content.SharedPreferences getSharedPreferences(String name,int mode){
    return BackupPreferences.wrap(name,super.getSharedPreferences(name,mode));
  }
  private static final Set<Long> RUNNING=new HashSet<>();
  private final Map<Integer,AtomicBoolean> cancellations=new HashMap<>();
  @Override public boolean onStartJob(JobParameters params) {
    long id=params.getExtras().getLong("task_id",0);
    synchronized(RUNNING) {
      if(!RUNNING.add(id)) { jobFinished(params,true); return true; }
    }
    AtomicBoolean cancelled=new AtomicBoolean();
    synchronized(cancellations) { cancellations.put(params.getJobId(),cancelled); }
    new Thread(() -> execute(params,id,cancelled),"cloud-sync-"+id).start();
    return true;
  }
  private void execute(JobParameters params,long id,AtomicBoolean cancelled) {
    CloudSyncTasks tasks=new CloudSyncTasks(this);
    char[] sharePassword=null,archivePassword=null;
    try {
      CloudSyncTasks.Task task=tasks.get(id);
      if(task==null) return;
      tasks.setStatus(id,"running","",task.syncedFiles,task.syncedBytes);
      JSONObject config=new JSONObject(task.configJson);
      sharePassword=tasks.password(id); archivePassword=tasks.archivePassword(id);
      if("github_backup".equals(task.kind)) {
        GithubAccountClient github=new GithubAccountClient(this,getString(R.string.github_oauth_client_id).trim());
        String login=config.getString("githubLogin"),repositoryName=config.getString("repository");
        GithubBackupRepository repository=new GithubBackupRepository(github,login,repositoryName);
        if(cancelled.get()) return;
        repository.ensurePrivateRepository(repositoryName);
        if(cancelled.get()) return;
        LanzouCore core=new LanzouCore(this);
        try {
          java.util.List<GithubBackupRecords.BlockResult> blocks=new GithubBackupRecords(repository,this)
              .syncBlocks(new GithubBackupState(this,core),sharePassword,cancelled::get);
          int failed=0,attentionBlocks=0,conflicts=0,completed=0;
          for(GithubBackupRecords.BlockResult block:blocks) {
            String label;
            switch(block.kind) {
              case "settings": label="设置"; break;
              case "sources": label="源"; break;
              case "cloud_accounts": label="云账号"; break;
              case "sync_tasks": label="同步任务"; break;
              default: label="其他资料";
            }
            boolean failedBlock="failed".equals(block.status);
            boolean attention="needs_attention".equals(block.status);
            if(failedBlock) failed++;
            if(attention) { attentionBlocks++; conflicts+=block.conflicts; }
            completed+=block.uploaded+block.unchanged;
            String detail=failedBlock?block.errorSummary:(attention?"待处理冲突："+block.conflicts:"");
            tasks.recordUpload(id,label,failedBlock?"失败":attention?"待处理":"已完成",detail,0);
          }
          if(!cancelled.get()) {
            String status=failed>0?"failed":attentionBlocks>0?"needs_attention":"completed";
            String detail=failed>0?"备份分块失败："+failed:attentionBlocks>0?"待处理备份冲突："+conflicts:"";
            tasks.setStatus(id,status,detail,completed,0);
          }
        } finally { core.close(); }
      } else {
        CloudFilesClient.Provider provider="premium".equals(task.cloudType)
            ? CloudFilesClient.Provider.PREMIUM : CloudFilesClient.Provider.ORDINARY;
        String extensions=config.optString("archiveFormat","").trim();
        if("folder".equals(task.kind)) {
          CloudFolderSyncEngine.Options options=new CloudFolderSyncEngine.Options(archivePassword,extensions,sharePassword,task.policyRevision);
          CloudFolderSyncEngine.Result result=new CloudFolderSyncEngine(this).sync(
              Uri.parse(task.sourceUri),provider,task.accountId,task.cloudFolderId,Long.toString(id),options,
              cancelled::get,(files,bytes) -> tasks.setStatus(id,"running","",files,bytes));
          if(!cancelled.get()) {
            String note=result.conflicts==0?"":"存在未覆盖的同名文件："+result.conflicts;
            if(!result.warning.isEmpty()) note=note.isEmpty()?result.warning:note+"；"+result.warning;
            tasks.setStatus(id,result.conflicts==0?"completed":"needs_attention",note,result.files,result.bytes);
          }
        } else {
          GithubAccountClient github=new GithubAccountClient(this,getString(R.string.github_oauth_client_id).trim());
          GithubAccountClient.Profile profile=github.cachedProfile();
          String login=config.getString("githubLogin");
          if(profile==null || !login.equalsIgnoreCase(profile.login)) throw new IllegalStateException("GitHub 账号已切换");
          JSONArray repos=config.getJSONArray("repositories");
          ArrayList<CloudGitHubReleaseSync.Project> projects=new ArrayList<>();
          for(int i=0;i<repos.length();i++) {
            JSONObject repo=repos.getJSONObject(i);
            projects.add(new CloudGitHubReleaseSync.Project(repo.getString("owner")+"/"+repo.getString("repo"),
                repo.getString("subdirectory"),repo.optString("match",""),repo.optString("releaseTag","")));
          }
          Set<String> extensionSet=new HashSet<>();
          for(String extension:extensions.split("[,，;；\\s]+")) if(!extension.isEmpty()) extensionSet.add(extension);
          Set<String> progressKeys=new HashSet<>();
          CloudGitHubReleaseSync.Result result=new CloudGitHubReleaseSync(this,CloudFilesClient.shared(this),github)
              .syncProjects(projects,provider,task.accountId,task.cloudFolderId,extensionSet,archivePassword,sharePassword,task.policyRevision,login,cancelled::get,
                  (repository,name,state,transferred,total,detail) -> {
                    String progress="downloading".equals(state) ? transferred+" / "+total+" B" :
                        ("failed".equals(state) ? detail : "");
                    String label;
                    switch(state) {
                      case "downloading": label="下载中"; break;
                      case "uploading": label="上传中"; break;
                      case "completed": label="已上传"; break;
                      case "failed": label="失败"; break;
                      case "unchanged": label="未变化"; break;
                      case "cancelled": label="已取消"; break;
                      default: label=state;
                    }
                    String key=repository+"/"+name;
                    progressKeys.add(key);
                    tasks.recordUpload(id,key,label,progress,Math.max(0,total));
                  },id);
          long syncedBytes=0;
          for(CloudGitHubReleaseSync.ItemResult item:result.items) {
            String key=item.repository+"/"+item.name;
            if(!progressKeys.contains(key))
              tasks.recordUpload(id,key,item.status,"失败".equals(item.status)?item.detail:"",Math.max(0,item.bytes));
            if("已上传".equals(item.status) || "未变化".equals(item.status)) syncedBytes+=Math.max(0,item.bytes);
          }
          if(!cancelled.get() && !result.cancelled) tasks.setStatus(id,result.failed>0?"failed":"completed",
              result.failed>0?"部分附件同步失败："+result.failed:"",result.uploaded+result.unchanged,syncedBytes);
        }
      }
    } catch(Exception error) {
      CloudSyncTasks.Task current=tasks.get(id);
      if(current!=null && !cancelled.get()) tasks.setStatus(id,"failed",safeFailure(error),current.syncedFiles,current.syncedBytes);
    } finally {
      if(sharePassword!=null) Arrays.fill(sharePassword,'\0');
      if(archivePassword!=null) Arrays.fill(archivePassword,'\0');
      synchronized(RUNNING) { RUNNING.remove(id); }
      synchronized(cancellations) { cancellations.remove(params.getJobId()); }
      if(!cancelled.get()) jobFinished(params,false);
    }
  }
  private static String safeFailure(Exception error) {
    if(error instanceof CloudFolderSyncEngine.SyncException) return error.getMessage();
    if(error instanceof GithubAccountClient.ApiFailure) {
      int status=((GithubAccountClient.ApiFailure)error).status;
      if(status==401) return "GitHub 登录已失效，请重新登录";
      if(status==403) return "GitHub 拒绝访问，请检查授权和请求限制";
      if(status==404) return "GitHub 仓库不存在或当前账号无权访问";
      return "GitHub 请求失败（HTTP "+status+"）";
    }
    if(error instanceof SecurityException) return "没有目录读取或写入权限，请重新授权";
    if(error instanceof CloudFilesClient.CloudException) {
      int code=((CloudFilesClient.CloudException)error).code();
      if(code==CloudFilesClient.ERROR_ACCOUNT_CHANGED) return "云账号已切换，请切回创建任务时的账号";
      if(code==CloudFilesClient.ERROR_CREDENTIALS_REQUIRED || code==CloudFilesClient.ERROR_CREDENTIALS_UNREADABLE || code==CloudFilesClient.ERROR_SESSION_EXPIRED)
        return "云账号未登录或登录已失效，请重新登录";
      if(code==CloudFilesClient.ERROR_NETWORK) return "网络连接失败，请检查网络";
      return "云端操作失败，请检查云目录和文件限制";
    }
    String message=error.getMessage();
    if("GitHub 账号已切换".equals(message)) return "GitHub 账号已切换，请切回创建任务时的账号";
    if(message!=null) {
      String[] approved={"备份仓库名称无效","请输入备份密码","单条备份记录过大，请拆分后同步","加密备份记录过大，请拆分后同步","备份记录过大","备份记录格式无效","备份仓库必须属于当前账号且为私有仓库","备份仓库尚未初始化","备份目录过大，GitHub 无法完整列举","仓库已存在或不可见；请确认仓库私有并退出 GitHub 后重新授权 repo 权限"};
      for(String value:approved) if(value.equals(message)) return value;
    }
    if(error instanceof java.net.SocketTimeoutException) return "网络请求超时，请检查网络后重试";
    if(error instanceof java.io.IOException) return "读取或网络请求失败，请检查网络与存储空间";
    return "同步未完成，请检查账号、配置和目录权限";
  }
  @Override public boolean onStopJob(JobParameters params) {
    synchronized(cancellations) {
      AtomicBoolean cancelled=cancellations.get(params.getJobId());
      if(cancelled!=null) cancelled.set(true);
    }
    return true;
  }
}















