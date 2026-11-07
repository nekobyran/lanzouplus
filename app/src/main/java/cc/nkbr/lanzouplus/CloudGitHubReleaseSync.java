package cc.nkbr.lanzouplus;

import android.content.Context;
import android.content.SharedPreferences;
import android.net.Uri;
import android.util.Base64;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import org.json.JSONArray;
import org.json.JSONObject;

/** Runs on a worker thread. GitHub credentials never leave the official API host. */
final class CloudGitHubReleaseSync {
  private static final Object SYNC_LOCK=new Object();
  private static final String PREFS="github_release_sync_v1";
  private final Context context;
  private final CloudFilesClient cloud;
  private final GithubAccountClient github;

  CloudGitHubReleaseSync(Context context,CloudFilesClient cloud,GithubAccountClient github){
    this.context=context.getApplicationContext();this.cloud=cloud;this.github=github;
  }

  static final class ItemResult {
    final String repository,name,status,detail;final long bytes;
    ItemResult(String repository,String name,String status,String detail){this(repository,name,status,detail,-1);}
    ItemResult(String repository,String name,String status,String detail,long bytes){
      this.repository=repository;this.name=name;this.status=status;this.detail=detail;this.bytes=bytes;
    }
  }
  static final class Result {
    final List<ItemResult> items=new ArrayList<>();
    int projects,matched,uploaded,unchanged,failed;long matchedBytes,uploadedBytes;boolean cancelled;
  }
  static final class PendingUpload {
    final String token,repository,name;
    PendingUpload(String token,String repository,String name){this.token=token;this.repository=repository;this.name=name;}
  }

  static List<PendingUpload> unknownUploads(Context context,long taskId)throws IOException{
    if(context==null||taskId<=0)throw new IOException("同步任务无效");
    synchronized(SYNC_LOCK){
      SharedPreferences prefs=context.getApplicationContext().getSharedPreferences(PREFS,Context.MODE_PRIVATE);
      List<PendingUpload> result=new ArrayList<>();
      for(String entry:prefs.getAll().keySet()){
        if(!entry.startsWith("pending-revision/"))continue;
        String key=entry.substring("pending-revision/".length());
        if(prefs.getLong("pending-owner/"+key,0)!=taskId||prefs.getString(entry,"").isEmpty()
            ||!prefs.getString("pending-id/"+key,"").isEmpty())continue;
        try{
          String[] identity=new String(Base64.decode(key,Base64.NO_WRAP),StandardCharsets.UTF_8).split("\\n",5);
          if(identity.length!=5)throw new IllegalArgumentException();
          result.add(new PendingUpload(key,identity[3],identity[4]));
        }catch(IllegalArgumentException error){throw new IOException("无法读取待核验上传记录");}
      }
      return result;
    }
  }
  static void clearUnknownUpload(Context context,long taskId,String token)throws IOException{
    if(context==null||taskId<=0||token==null||token.isEmpty())throw new IOException("待核验上传记录无效");
    synchronized(SYNC_LOCK){
      SharedPreferences prefs=context.getApplicationContext().getSharedPreferences(PREFS,Context.MODE_PRIVATE);
      if(prefs.getLong("pending-owner/"+token,0)!=taskId||prefs.getString("pending-revision/"+token,"").isEmpty()
          ||!prefs.getString("pending-id/"+token,"").isEmpty())
        throw new IOException("待核验记录已变化，请重新查看");
      if(!prefs.edit().remove("pending-revision/"+token).remove("pending-id/"+token)
          .remove("pending-name/"+token).remove("pending-owner/"+token).commit())
        throw new IOException("无法保存待核验状态");
    }
  }

  interface Cancellation { boolean isCancelled(); }
  interface Progress {
    void onProgress(String repository,String name,String state,long transferred,long total,String detail);
  }
  static final class Project {
    final String repository,folderName,keywords;
    Project(String repository,String folderName){this(repository,folderName,null);}
    Project(String repository,String folderName,String keywords){
      this.repository=repository;this.folderName=folderName;this.keywords=keywords;
    }
  }
  private static final class SyncCancelled extends java.io.InterruptedIOException {}
  private static final class SyncFailure extends IOException {
    SyncFailure(String message){super(message);}
  }

  /** Compatibility entry point: defaults each project folder to its repository name. */
  Result sync(List<String> repositories,String keywords,CloudFilesClient.Provider provider,
      String account,String folderId)throws IOException{
    if(repositories==null)throw new IOException("请输入 GitHub 仓库");
    List<Project> projects=new ArrayList<>();
    for(String value:repositories){
      String name=value==null?"":value.trim();int slash=name.lastIndexOf('/');
      projects.add(new Project(name,slash<0?name:name.substring(slash+1)));
    }
    return syncProjects(projects,keywords,provider,account,folderId);
  }

  /** Each project provides owner/name and its own official child-folder name. */
  Result syncProjects(List<Project> projects,String keywords,CloudFilesClient.Provider provider,
      String account,String parentId)throws IOException{
    return syncProjects(projects,keywords,provider,account,parentId,"",null);
  }
  /** matchingExtensions selects file suffixes for password-encrypted ZIP output. */
  Result syncProjects(List<Project> projects,String keywords,CloudFilesClient.Provider provider,
      String account,String parentId,String matchingExtensions,char[] zipPassword)throws IOException{
    return syncProjects(projects,keywords,provider,account,parentId,matchingExtensions,zipPassword,null);
  }
  Result syncProjects(List<Project> projects,String keywords,CloudFilesClient.Provider provider,
      String account,String parentId,String matchingExtensions,char[] zipPassword,Cancellation cancellation)throws IOException{
    return syncProjects(projects,keywords,provider,account,parentId,matchingExtensions,zipPassword,null,cancellation);
  }
  Result syncProjects(List<Project> projects,CloudFilesClient.Provider provider,String account,String parentId,
      java.util.Set<String> zipExtensions,char[] zipPassword,char[] sharePassword,Cancellation cancellation)throws IOException{
    return syncProjects(projects,provider,account,parentId,zipExtensions,zipPassword,sharePassword,"",cancellation);
  }
  Result syncProjects(List<Project> projects,CloudFilesClient.Provider provider,String account,String parentId,
      java.util.Set<String> zipExtensions,char[] zipPassword,char[] sharePassword,String policyRevision,
      Cancellation cancellation)throws IOException{
    return syncProjects(projects,provider,account,parentId,zipExtensions,zipPassword,sharePassword,policyRevision,"",cancellation);
  }
  Result syncProjects(List<Project> projects,CloudFilesClient.Provider provider,String account,String parentId,
      java.util.Set<String> zipExtensions,char[] zipPassword,char[] sharePassword,String policyRevision,
      String expectedGithubLogin,Cancellation cancellation)throws IOException{
    return syncProjects(projects,provider,account,parentId,zipExtensions,zipPassword,sharePassword,
        policyRevision,expectedGithubLogin,cancellation,null);
  }
  Result syncProjects(List<Project> projects,CloudFilesClient.Provider provider,String account,String parentId,
      java.util.Set<String> zipExtensions,char[] zipPassword,char[] sharePassword,String policyRevision,
      String expectedGithubLogin,Cancellation cancellation,Progress progress)throws IOException{
    return syncProjects(projects,provider,account,parentId,zipExtensions,zipPassword,sharePassword,
        policyRevision,expectedGithubLogin,cancellation,progress,0);
  }
  Result syncProjects(List<Project> projects,CloudFilesClient.Provider provider,String account,String parentId,
      java.util.Set<String> zipExtensions,char[] zipPassword,char[] sharePassword,String policyRevision,
      String expectedGithubLogin,Cancellation cancellation,Progress progress,long taskId)throws IOException{
    String extensions=zipExtensions==null?"":android.text.TextUtils.join(",",zipExtensions);
    return syncProjects(projects,"",provider,account,parentId,extensions,zipPassword,sharePassword,policyRevision,expectedGithubLogin,cancellation,progress,taskId);
  }
  Result syncProjects(List<Project> projects,String keywords,CloudFilesClient.Provider provider,
      String account,String parentId,String matchingExtensions,char[] zipPassword,char[] sharePassword,
      Cancellation cancellation)throws IOException{
    return syncProjects(projects,keywords,provider,account,parentId,matchingExtensions,zipPassword,sharePassword,"","",cancellation,null,0);
  }
  private Result syncProjects(List<Project> projects,String keywords,CloudFilesClient.Provider provider,
      String account,String parentId,String matchingExtensions,char[] zipPassword,char[] sharePassword,
      String policyRevision,String expectedGithubLogin,Cancellation cancellation,Progress progress,long taskId)throws IOException{
    if(projects==null||projects.isEmpty())throw new IOException("请输入 GitHub 仓库");
    if(provider==null||account==null||account.trim().isEmpty()||parentId==null||parentId.trim().isEmpty())
      throw new IOException("请选择云账号和目标文件夹");
    synchronized(SYNC_LOCK){
      Result result=new Result();
      if(isCancelled(cancellation)){result.cancelled=true;return result;}
      String bearer=accessToken(expectedGithubLogin);CloudFilesClient.BoundSession session;
      try{session=cloud.bindAccount(provider,account);}
      catch(CloudFilesClient.CloudException error){throw new IOException("无法绑定云账号");}
      String[] zipExtensions=splitTerms(matchingExtensions);
      SharedPreferences metadata=context.getSharedPreferences(PREFS,Context.MODE_PRIVATE);
      java.util.Set<String> usedFolders=new java.util.HashSet<>();
      for(Project project:projects){
        if(isCancelled(cancellation)){result.cancelled=true;return result;}
        result.projects++;
        String repository=project==null||project.repository==null?"":project.repository.trim();
        String folderName=project==null||project.folderName==null?"":project.folderName.trim();
        String[] filters=splitTerms(project!=null&&project.keywords!=null?project.keywords:keywords);
        if(!repository.matches("[A-Za-z0-9_.-]+/[A-Za-z0-9_.-]+")){
          result.failed++;result.items.add(new ItemResult(repository,"","失败","仓库格式应为 owner/repo"));continue;
        }
        if(folderName.isEmpty()||!usedFolders.add(folderName)){
          result.failed++;result.items.add(new ItemResult(repository,"","失败","项目子目录名称为空或重复"));continue;
        }
        try{
          String[] parts=repository.split("/",2);
          String base="https://api.github.com/repos/"+Uri.encode(parts[0])+"/"+Uri.encode(parts[1]);
          JSONObject release=new JSONObject(new String(readApi(base+"/releases/latest",bearer,cancellation),StandardCharsets.UTF_8));
          long releaseId=release.getLong("id");String assetsUrl=release.getString("assets_url");
          if(releaseId<=0||!assetsUrl.equalsIgnoreCase(base+"/releases/"+releaseId+"/assets"))
            throw new SyncFailure("GitHub 附件列表地址无效");
          List<JSONObject> assets=readAssets(assetsUrl,bearer,cancellation);boolean any=false;
          String assignmentKey="folder/"+metadataKey(provider,account,parentId,"folder",folderName);
          String assignment=metadata.getString(assignmentKey,"");
          if(!assignment.isEmpty()&&!assignment.equalsIgnoreCase(repository))
            throw new SyncFailure("该项目子目录已分配给其他仓库");
          String childId=projectFolder(session,parentId,folderName,repository,cancellation);
          if(assignment.isEmpty()&&!metadata.edit().putString(assignmentKey,repository).commit())
            throw new SyncFailure("无法保存项目子目录绑定");
          for(JSONObject asset:assets){
            checkCancelled(cancellation);
            String name=asset.getString("name");if(!matches(name,filters))continue;
            any=true;result.matched++;long itemSize=asset.optLong("size",-1);
            if(itemSize>=0)result.matchedBytes+=itemSize;
            try{
              if(name.isEmpty()||name.equals(".")||name.equals("..")||name.contains("/")||name.contains("\\"))
                throw new SyncFailure("附件名称无效");
              long id=asset.getLong("id"),size=asset.getLong("size");String updated=asset.getString("updated_at");
              if(id<=0||size<0||updated.isEmpty())throw new SyncFailure("附件元数据无效");
              String key=metadataKey(provider,account,childId,repository,name);
              boolean encrypt=matchesExtension(name,zipExtensions);
              if(encrypt&&(zipPassword==null||zipPassword.length==0))throw new SyncFailure("请设置 ZIP 加密密码");
              if(encrypt&&(policyRevision==null||policyRevision.isEmpty()))
                throw new SyncFailure("ZIP 加密配置缺少版本标识");
              JSONObject revisionData=new JSONObject().put("updated",updated).put("size",size);
              if(encrypt)revisionData.put("zip",true).put("policy",policyRevision);
              String revision=revisionData.toString();
              if(revision.equals(metadata.getString(key,""))&&metadata.getString("pending-revision/"+key,"").isEmpty()){
                if(sharePassword!=null&&sharePassword.length>0){
                  String storedId=metadata.getString("id/"+key,"");
                  if(storedId.isEmpty())throw new SyncFailure("已有记录缺少云文件 ID，无法设置分享密码");
                  checkCancelled(cancellation);
                  session.setPassword(CloudFilesClient.CloudItem.file(storedId,name),new String(sharePassword));
                }
                result.unchanged++;result.items.add(new ItemResult(repository,name,"未变化","",size));
                notifyProgress(progress,repository,name,"unchanged",size,size,"");continue;
              }
              String pendingRevision=metadata.getString("pending-revision/"+key,"");
              String pendingId=metadata.getString("pending-id/"+key,"");
              String pendingName=metadata.getString("pending-name/"+key,"");
              if(!pendingRevision.isEmpty()){
                if(metadata.getLong("pending-owner/"+key,0)!=taskId)
                  throw new SyncFailure("该附件有其他同步任务的待核验上传，请先处理");
                if(pendingId.isEmpty())
                  throw new SyncFailure("上次上传结果未知，请先检查云端文件，已停止自动重传");
                if(!revision.equals(pendingRevision)||pendingName.isEmpty())
                  throw new SyncFailure("存在未完成的上传记录，请先恢复原配置并重试");
                checkCancelled(cancellation);
                verifyUploaded(session,childId,pendingId,pendingName,cancellation);
                if(sharePassword!=null&&sharePassword.length>0)
                  session.setPassword(CloudFilesClient.CloudItem.file(pendingId,pendingName),new String(sharePassword));
                checkCancelled(cancellation);
                if(!metadata.edit().putString(key,revision).putString("id/"+key,pendingId)
                    .remove("pending-revision/"+key).remove("pending-id/"+key).remove("pending-name/"+key).remove("pending-owner/"+key).commit())
                  throw new SyncFailure("无法保存已完成的上传记录");
                result.uploaded++;result.uploadedBytes+=size;
                result.items.add(new ItemResult(repository,name,"已上传","",size));
                notifyProgress(progress,repository,name,"completed",size,size,"");continue;
              }
              File dir=new File(context.getCacheDir(),"github-release-"+UUID.randomUUID());
              if(!dir.mkdirs())throw new SyncFailure("无法创建临时目录");
              File file=new File(dir,name);File archive=new File(dir,name+".zip");
              try{
                notifyProgress(progress,repository,name,"downloading",0,size,"");
                download(base+"/releases/assets/"+id,bearer,file,size,cancellation,progress,repository,name);
                boolean apk=name.toLowerCase(Locale.ROOT).endsWith(".apk");
                checkCancelled(cancellation);
                File source=encrypt?CloudEncryptedZip.encryptSingleFile(file,archive,zipPassword):file;
                CloudFilesClient.UploadOptions options=CloudFilesClient.UploadOptions.defaults().autoIcon(apk);
                if(apk&&encrypt)options.iconPath(file.getAbsolutePath());
                checkCancelled(cancellation);
                if(!metadata.edit().putString("pending-revision/"+key,revision)
                    .putString("pending-name/"+key,source.getName()).putLong("pending-owner/"+key,taskId)
                    .remove("pending-id/"+key).commit())
                  throw new SyncFailure("无法保存上传意图，未开始上传");
                checkCancelled(cancellation);
                notifyProgress(progress,repository,name,"uploading",0,source.length(),"");
                CloudFilesClient.UploadResult uploaded=session.upload(childId,source.getAbsolutePath(),options);
                if(uploaded.id.isEmpty()||uploaded.uploadedName.isEmpty())
                  throw new SyncFailure("上传结果无法确认，请先检查云端文件");
                if(!metadata.edit().putString("pending-revision/"+key,revision)
                    .putString("pending-id/"+key,uploaded.id).putString("pending-name/"+key,uploaded.uploadedName).commit())
                  throw new SyncFailure("上传完成但无法保存恢复记录，请先检查云端文件");
                checkCancelled(cancellation);
                verifyUploaded(session,childId,uploaded.id,uploaded.uploadedName,cancellation);
                if(sharePassword!=null&&sharePassword.length>0){
                  checkCancelled(cancellation);
                  session.setPassword(CloudFilesClient.CloudItem.file(uploaded.id,uploaded.uploadedName),new String(sharePassword));
                }
                checkCancelled(cancellation);
                if(!metadata.edit().putString(key,revision).putString("id/"+key,uploaded.id)
                    .remove("pending-revision/"+key).remove("pending-id/"+key).remove("pending-name/"+key).remove("pending-owner/"+key).commit())
                  throw new SyncFailure("无法保存已完成的上传记录");
                result.uploaded++;result.uploadedBytes+=size;
                result.items.add(new ItemResult(repository,name,"已上传",uploaded.warning,size));
                notifyProgress(progress,repository,name,"completed",source.length(),source.length(),"");
              }finally{archive.delete();file.delete();dir.delete();}
            }catch(Exception failure){
              if(failure instanceof SyncCancelled){result.cancelled=true;notifyProgress(progress,repository,name,"cancelled",0,itemSize,"");return result;}
              result.failed++;result.items.add(new ItemResult(repository,name,"失败",safeFailure(failure),itemSize));
              notifyProgress(progress,repository,name,"failed",0,itemSize,safeFailure(failure));
            }
          }
          if(!any)result.items.add(new ItemResult(repository,"","无匹配附件",""));
        }catch(Exception failure){
          if(failure instanceof SyncCancelled){result.cancelled=true;return result;}
          result.failed++;result.items.add(new ItemResult(repository,"","失败",safeFailure(failure)));
          notifyProgress(progress,repository,"","failed",0,-1,safeFailure(failure));
        }
      }
      return result;
    }
  }

  private static void verifyUploaded(CloudFilesClient.BoundSession session,String parent,String id,String name,
      Cancellation cancellation)throws Exception{
    for(int page=1;page<=10000;page++){
      checkCancelled(cancellation);
      CloudFilesClient.CloudPage listing=session.listFolder(parent,page,200);
      for(CloudFilesClient.CloudItem item:listing.items)
        if(id.equals(item.id)&&!item.folder&&name.equals(item.name))return;
      if(!listing.hasMore)throw new SyncFailure("上传的云端文件尚未确认，请重试恢复");
    }
    throw new SyncFailure("云端目录页数超限，无法确认上传");
  }

  private static String projectFolder(CloudFilesClient.BoundSession session,String parent,String name,
      String repository,Cancellation cancellation)throws Exception{
    String marker="LanzouPlus GitHub Release: "+repository.toLowerCase(Locale.ROOT);
    String found=null;
    for(int page=1;page<=10000;page++){
      checkCancelled(cancellation);CloudFilesClient.CloudPage listing=session.listFolder(parent,page,200);
      for(CloudFilesClient.CloudItem item:listing.items)if(item.folder&&name.equals(item.name)){
        if(!marker.equals(item.description))throw new SyncFailure("目标存在同名目录，无法确认属于此仓库");
        if(found!=null&&!found.equals(item.id))throw new SyncFailure("目标目录存在多个同名子目录，请先处理");
        found=item.id;
      }
      if(!listing.hasMore){checkCancelled(cancellation);return found!=null?found:session.createFolder(parent,name,marker);}
    }
    throw new SyncFailure("目标目录页数超限，无法确定子目录");
  }
  private static List<JSONObject> readAssets(String assetsUrl,String bearer,Cancellation cancellation)throws Exception{
    List<JSONObject> result=new ArrayList<>();
    for(int page=1;page<=10000;page++){
      checkCancelled(cancellation);
      String url=assetsUrl+"?per_page=100&page="+page;
      JSONArray entries=new JSONArray(new String(readApi(url,bearer,cancellation),StandardCharsets.UTF_8));
      for(int i=0;i<entries.length();i++)result.add(entries.getJSONObject(i));
      if(entries.length()<100)return result;
    }
    throw new SyncFailure("GitHub 附件页数超限");
  }
  private String accessToken(String expectedLogin)throws IOException{
    if(expectedLogin==null||expectedLogin.trim().isEmpty())return "";
    if(github==null)throw new IOException("请先登录指定的 GitHub 账号");
    return github.accessTokenForAccount(expectedLogin);
  }
  private static String metadataKey(CloudFilesClient.Provider provider,String account,String folder,
      String repository,String name){
    String identity=provider.name()+"\n"+account+"\n"+folder+"\n"+repository.toLowerCase(Locale.ROOT)+"\n"+name;
    return Base64.encodeToString(identity.getBytes(StandardCharsets.UTF_8),Base64.NO_WRAP);
  }
  private static String[] splitTerms(String value){
    return value==null?new String[0]:value.toLowerCase(Locale.ROOT).split("[,，;；\\s]+");
  }
  private static boolean matchesExtension(String name,String[] extensions){
    String lower=name.toLowerCase(Locale.ROOT);
    for(String ext:extensions){
      if(ext.startsWith("*."))ext=ext.substring(1);
      if(!ext.isEmpty()&&!ext.startsWith("."))ext="."+ext;
      if(!ext.isEmpty()&&lower.endsWith(ext))return true;
    }
    return false;
  }
  private static boolean matches(String name,String[] filters){
    boolean specified=false;String lower=name.toLowerCase(Locale.ROOT);
    for(String filter:filters)if(!filter.isEmpty()){specified=true;if(lower.contains(filter))return true;}
    return !specified;
  }
  private static String safeFailure(Exception error){
    if(error instanceof SyncFailure)return error.getMessage();
    if(error instanceof CloudFilesClient.CloudException)return "云端操作失败，请检查账号、文件夹和文件限制";
    return "获取或处理附件失败，请检查仓库权限、网络和存储空间";
  }
  private static byte[] readApi(String url,String bearer,Cancellation cancellation)throws IOException{
    HttpURLConnection connection=open(url,bearer,"application/vnd.github+json");
    try{
      int status=connection.getResponseCode();if(status!=200)throw new SyncFailure("GitHub API 请求失败（HTTP "+status+"）");
      try(InputStream in=connection.getInputStream();ByteArrayOutputStream out=new ByteArrayOutputStream()){
        byte[] buffer=new byte[8192];int count;
        while((count=in.read(buffer))!=-1){checkCancelled(cancellation);if(out.size()+count>8*1024*1024)throw new IOException("发行版信息过大");out.write(buffer,0,count);}
        return out.toByteArray();
      }
    }finally{connection.disconnect();}
  }
  private static void download(String url,String bearer,File file,long expected,Cancellation cancellation,
      Progress progress,String repository,String name)throws IOException{
    String current=url;
    for(int redirects=0;redirects<6;redirects++){
      checkCancelled(cancellation);
      HttpURLConnection connection=open(current,bearer,"application/octet-stream");
      try{
        int status=connection.getResponseCode();
        if(status>=300&&status<400){
          String location=connection.getHeaderField("Location");
          if(location==null)throw new IOException("附件重定向无效");
          current=new URL(new URL(current),location).toString();continue;
        }
        if(status!=200)throw new SyncFailure("附件下载失败（HTTP "+status+"）");
        long total=0,lastReportTime=android.os.SystemClock.elapsedRealtime();
        try(InputStream input=connection.getInputStream();FileOutputStream output=new FileOutputStream(file)){
          byte[] buffer=new byte[64*1024];int count;
          while((count=input.read(buffer))!=-1){
            checkCancelled(cancellation);total+=count;if(total>expected)throw new SyncFailure("附件大小与 GitHub 声明不一致");
            output.write(buffer,0,count);long now=android.os.SystemClock.elapsedRealtime();
            if(now-lastReportTime>=500){
              notifyProgress(progress,repository,name,"downloading",total,expected,"");lastReportTime=now;
            }
          }
        }
        if(total!=expected)throw new SyncFailure("附件大小与 GitHub 声明不一致");
        notifyProgress(progress,repository,name,"downloading",total,expected,"");
        return;
      }finally{connection.disconnect();}
    }
    throw new IOException("附件重定向过多");
  }
  /** The callback receives no network addresses, credentials, or exception payloads. */
  private static void notifyProgress(Progress listener,String repository,String name,String state,
      long transferred,long total,String detail){
    if(listener==null)return;
    try{listener.onProgress(safeLabel(repository),safeLabel(name),state,transferred,total,detail);}
    catch(RuntimeException ignored){}
  }
  private static String safeLabel(String input){
    if(input==null)return "";StringBuilder result=new StringBuilder();
    for(int i=0;i<input.length()&&result.length()<200;i++){
      char value=input.charAt(i);result.append(Character.isISOControl(value)?' ':value);
    }
    return result.toString();
  }  private static boolean isCancelled(Cancellation cancellation){
    return Thread.currentThread().isInterrupted()||(cancellation!=null&&cancellation.isCancelled());
  }
  private static void checkCancelled(Cancellation cancellation)throws java.io.InterruptedIOException{
    if(isCancelled(cancellation))throw new SyncCancelled();
  }
  private static HttpURLConnection open(String address,String bearer,String accept)throws IOException{
    URL url=new URL(address);String host=url.getHost().toLowerCase(Locale.ROOT);
    if(!"https".equals(url.getProtocol())||url.getUserInfo()!=null||url.getRef()!=null
        ||(url.getPort()!=-1&&url.getPort()!=443)
        ||!(host.equals("api.github.com")||host.equals("github.com")
             ||host.equals("objects.githubusercontent.com")||host.equals("release-assets.githubusercontent.com")))
      throw new IOException("附件地址不是 GitHub 官方 HTTPS 地址");
    HttpURLConnection connection=(HttpURLConnection)url.openConnection();
    connection.setInstanceFollowRedirects(false);connection.setConnectTimeout(15000);connection.setReadTimeout(60000);
    connection.setRequestProperty("User-Agent","LanzouPlus");connection.setRequestProperty("Accept",accept);
    if(host.equals("api.github.com")&&!bearer.isEmpty())connection.setRequestProperty("Authorization","Bearer "+bearer);
    return connection;
  }
}
