package cc.nkbr.lanzouplus;

import android.content.ContentResolver;
import android.content.Context;
import android.database.Cursor;
import android.net.Uri;
import android.provider.DocumentsContract;
import android.util.AtomicFile;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/** Worker-thread-only SAF/cloud synchronization. Versions are immutable unique copies. */
final class CloudFolderSyncEngine {
  interface Cancellation { boolean isCancelled(); }
  interface Progress { void onProgress(int files, long bytes); }
  interface ConflictCopies {
    String copyToCloud()throws Exception;
    String copyToLocal()throws Exception;
  }
  enum Change { EQUAL, CONFLICT, LOCAL, REMOTE, UNRESOLVED, AMBIGUOUS }
  static Change decideChange(boolean equal,boolean hasBase,boolean localChanged,boolean remoteChanged,boolean conflict){
    if(equal)return Change.EQUAL;
    if(!hasBase||(localChanged&&remoteChanged))return Change.CONFLICT;
    if(localChanged)return Change.LOCAL;
    if(remoteChanged)return Change.REMOTE;
    return conflict?Change.UNRESOLVED:Change.AMBIGUOUS;
  }
  static boolean missingSide(boolean localPresent,boolean remotePresent){return !localPresent||!remotePresent;}
  static String[] preserveConflict(ConflictCopies copies)throws Exception{
    String remote=copies.copyToCloud();
    String local=copies.copyToLocal();
    return new String[]{remote,local};
  }
  static final class Options {
    final char[] zipPassword,sharePassword;
    final String extensions,policyRevision;
    Options(char[] zipPassword,String extensions,char[] sharePassword){
      this(zipPassword,extensions,sharePassword,"");
    }
    Options(char[] zipPassword,String extensions,char[] sharePassword,String policyRevision){
      this.zipPassword=zipPassword;this.extensions=extensions==null?"":extensions;this.sharePassword=sharePassword;
      this.policyRevision=policyRevision==null?"":policyRevision;
    }
  }
  static final class Result {
    final int files;
    final long bytes;
    final int conflicts;
    final int ignoredDeletions;
    final String warning;
    Result(int files,long bytes,int conflicts,int ignoredDeletions,String warning){
      this.files=files;this.bytes=bytes;this.conflicts=conflicts;this.ignoredDeletions=ignoredDeletions;this.warning=warning;
    }
  }
  static final class SyncException extends Exception {
    SyncException(String message){super(message);}
  }
  static final class PendingInfo {
    final boolean present;
    final String message;
    PendingInfo(boolean present,String message){this.present=present;this.message=message;}
  }
  private static final String DIR=DocumentsContract.Document.MIME_TYPE_DIR;
  private static final Object SYNC_LOCK=new Object();
  private final Context context;
  private final ContentResolver resolver;
  private Cancellation cancellation;
  private Progress progress;
  private CloudFilesClient.BoundSession cloud;
  private Uri tree;
  private File storage;
  private AtomicFile stateFile;
  private JSONObject state;
  private int files,conflicts,ignoredDeletions;
  private long bytes;
  private long taskId;
  private String activeUploadPath;
  private long activeUploadBytes;
  private Options options;
  private String warning="";
  private final List<File> temporary=new ArrayList<>();

  CloudFolderSyncEngine(Context context){
    this.context=context.getApplicationContext();
    resolver=this.context.getContentResolver();
  }

  Result sync(Uri treeUri,CloudFilesClient.Provider provider,String account,
      String remoteFolderId,String syncId,Cancellation cancel,Progress listener)throws Exception{
    return sync(treeUri,provider,account,remoteFolderId,syncId,new Options(null,"",null),cancel,listener);
  }
  Result sync(Uri treeUri,CloudFilesClient.Provider provider,String account,
      String remoteFolderId,String syncId,Options options,Cancellation cancel,Progress listener)throws Exception{
    synchronized(SYNC_LOCK){
      this.options=options==null?new Options(null,"",null):options;
      try{return runSync(treeUri,provider,account,remoteFolderId,syncId,cancel,listener);}
      catch(SyncException error){finishActiveUpload(false);throw error;}
      catch(Exception error){finishActiveUpload(false);throw new SyncException("同步操作失败，请检查账号、目录权限和网络");}
      finally{cleanupTemps();}
    }
  }
  private Result runSync(Uri treeUri,CloudFilesClient.Provider provider,String account,
      String remoteFolderId,String syncId,Cancellation cancel,Progress listener)throws Exception{
    if(treeUri==null||!DocumentsContract.isTreeUri(treeUri)||provider==null||account==null
        ||account.isEmpty()||remoteFolderId==null||remoteFolderId.isEmpty()
        ||syncId==null||!syncId.matches("[A-Za-z0-9_-]{1,80}"))
      throw new SyncException("同步任务配置无效");
    if(!options.extensions.trim().isEmpty()&&(options.zipPassword==null||options.zipPassword.length==0))
      throw new SyncException("已选择加密扩展名，但未提供 ZIP 密码");
    tree=treeUri;cancellation=cancel;progress=listener;files=0;bytes=0;conflicts=0;ignoredDeletions=0;warning="";temporary.clear();
    activeUploadPath=null;activeUploadBytes=0;
    try{taskId=Long.parseLong(syncId);}catch(NumberFormatException error){taskId=0;}
    storage=new File(context.getFilesDir(),"folder-sync/"+syncId);
    if(!storage.isDirectory()&&!storage.mkdirs())throw new SyncException("无法保存同步状态");
    stateFile=new AtomicFile(new File(storage,"state.json"));
    if(stateFile.getBaseFile().exists()){
      state=new JSONObject(new String(stateFile.readFully(),StandardCharsets.UTF_8));
      if(!state.getString("tree").equals(tree.toString())||!state.getString("provider").equals(provider.name())
          ||!state.getString("account").equals(account)||!state.getString("root").equals(remoteFolderId))
        throw new SyncException("同步任务的目录或账号已更改");

    }else{
      state=new JSONObject().put("tree",tree.toString()).put("provider",provider.name())
          .put("account",account).put("root",remoteFolderId).put("pending",false)
          .put("dirs",new JSONArray()).put("files",new JSONArray())
          .put("ignoredLocal",new JSONArray()).put("ignoredCloud",new JSONArray());
      save();
    }
    cloud=CloudFilesClient.shared(context).bindAccount(provider,account);
    if(state.optBoolean("pending",false))recoverPending();
    Uri root=DocumentsContract.buildDocumentUriUsingTree(tree,DocumentsContract.getTreeDocumentId(tree));
    syncDirectory(root,remoteFolderId,"");
    if(conflicts>0)throw new SyncException("存在未解决的版本冲突，同步未完成");
    if(ignoredDeletions>0)warning=(warning.isEmpty()?"":warning+"；")+"已保留另一侧内容并忽略 "+ignoredDeletions+" 个删除或移动的映射";
    return new Result(files,bytes,conflicts,ignoredDeletions,warning);
  }

  private void check()throws SyncException{
    if(cancellation!=null&&cancellation.isCancelled())throw new SyncException("同步已取消");
    if(Thread.currentThread().isInterrupted())throw new SyncException("同步已取消");
  }
  private void save()throws Exception{
    FileOutputStream out=null;
    try{out=stateFile.startWrite();out.write(state.toString().getBytes(StandardCharsets.UTF_8));stateFile.finishWrite(out);}
    catch(Exception error){if(out!=null)stateFile.failWrite(out);throw new SyncException("无法保存同步状态");}
  }
  private void begin(String operation,String localParent,String remoteParent,String localName,String remoteName)throws Exception{
    state.remove("journal");
    state.put("pending",true).put("operation",operation).put("pendingLocalParent",localParent)
        .put("pendingRemoteParent",remoteParent).put("pendingLocalName",localName).put("pendingRemoteName",remoteName);
    save();
  }
  private void journal(String lp,String rp,String local,String remote,String logical,
      File localBytes,File remoteBytes,boolean encrypted)throws Exception{
    JSONObject j=new JSONObject().put("localParent",lp).put("remoteParent",rp)
        .put("local",local).put("remote",remote).put("logicalName",logical)
        .put("localBytes",localBytes==null?"":localBytes.getName())
        .put("remoteBytes",remoteBytes==null?"":remoteBytes.getName()).put("encrypted",encrypted)
        .put("policyRevision",options.policyRevision)
        .put("originalName",logical.equals(state.optString("pendingLocalName",""))
            ||logical.equals(state.optString("pendingRemoteName","")));
    state.put("journal",j);save();
  }
  PendingInfo pending(String syncId)throws Exception{
    synchronized(SYNC_LOCK){
      if(syncId==null||!syncId.matches("[A-Za-z0-9_-]{1,80}"))throw new SyncException("同步任务配置无效");
      AtomicFile file=new AtomicFile(new File(context.getFilesDir(),"folder-sync/"+syncId+"/state.json"));
      if(!file.getBaseFile().exists())return new PendingInfo(false,"");
      JSONObject value;
      try{value=new JSONObject(new String(file.readFully(),StandardCharsets.UTF_8));}
      catch(Exception error){throw new SyncException("无法读取待处理状态");}
      boolean present=value.optBoolean("pending",false);
      return new PendingInfo(present,present?"上次写入需要核验，可重试或确认放弃；已有副本将保留":"");
    }
  }
  void abandonPending(Uri treeUri,CloudFilesClient.Provider provider,String account,
      String remoteFolderId,String syncId)throws Exception{
    synchronized(SYNC_LOCK){
      try{abandonPendingLocked(treeUri,provider,account,remoteFolderId,syncId);}
      catch(SyncException error){throw error;}
      catch(Exception error){throw new SyncException("无法确认待处理状态，请检查账号和目录权限");}
    }
  }
  private void abandonPendingLocked(Uri treeUri,CloudFilesClient.Provider provider,String account,
      String remoteFolderId,String syncId)throws Exception{
      if(treeUri==null||provider==null||account==null||remoteFolderId==null||syncId==null
          ||!syncId.matches("[A-Za-z0-9_-]{1,80}"))throw new SyncException("同步任务配置无效");
      tree=treeUri;cancellation=null;storage=new File(context.getFilesDir(),"folder-sync/"+syncId);
      stateFile=new AtomicFile(new File(storage,"state.json"));
      if(!stateFile.getBaseFile().exists())return;
      state=new JSONObject(new String(stateFile.readFully(),StandardCharsets.UTF_8));
      if(!state.getString("tree").equals(tree.toString())||!state.getString("provider").equals(provider.name())
          ||!state.getString("account").equals(account)||!state.getString("root").equals(remoteFolderId))
        throw new SyncException("同步任务的目录或账号已更改");
      if(!state.optBoolean("pending",false))return;
      cloud=CloudFilesClient.shared(context).bindAccount(provider,account);
      String lp=state.optString("pendingLocalParent",""),rp=state.optString("pendingRemoteParent","");
      String ln=state.optString("pendingLocalName",""),rn=state.optString("pendingRemoteName","");
      JSONObject pendingJournal=state.optJSONObject("journal");
      String localCreated=pendingJournal==null?"":pendingJournal.optString("createdLocal","");
      String remoteCreated=pendingJournal==null?"":pendingJournal.optString("createdRemote","");
      if(!lp.isEmpty()&&!ln.isEmpty())for(Local item:localChildren(Uri.parse(lp)))
        if(ln.equals(item.name)||item.uri.toString().equals(localCreated))ignore("ignoredLocal",item.uri.toString());
      if(!rp.isEmpty()&&!rn.isEmpty())for(CloudFilesClient.CloudItem item:cloudChildren(rp))
        if(rn.equals(item.name)||item.id.equals(remoteCreated))ignore("ignoredCloud",item.id);
      end();
  }
  private void recoverPending()throws Exception{
    JSONObject j=state.optJSONObject("journal");
    if(j==null)throw new SyncException("上次写入缺少恢复记录，请确认放弃后重试");
    if(j.optBoolean("originalName",false))
      throw new SyncException("原名创建结果需要人工核验，请确认处理后再重试");
    if(j.has("policyRevision")&&!j.optString("policyRevision","").equals(options.policyRevision))
      throw new SyncException("同步设置已更改，请恢复原设置处理待完成的写入");
    String operation=state.optString("operation",""),lp=j.getString("localParent"),rp=j.getString("remoteParent");
    String sourceLocal=j.getString("local"),sourceRemote=j.getString("remote"),logical=j.getString("logicalName");
    String ln=state.optString("pendingLocalName",""),rn=state.optString("pendingRemoteName","");
    Local localTarget=null,localSource=null;CloudFilesClient.CloudItem remoteTarget=null,remoteSource=null;
    for(Local item:localChildren(Uri.parse(lp))){
      if(item.uri.toString().equals(sourceLocal))localSource=item;
      if(!ln.isEmpty()&&ln.equals(item.name)){
        if(localTarget!=null)throw new SyncException("待处理本地副本不唯一，请确认放弃后处理");
        localTarget=item;
      }
    }
    for(CloudFilesClient.CloudItem item:cloudChildren(rp)){
      if(item.id.equals(sourceRemote))remoteSource=item;
      if(!rn.isEmpty()&&rn.equals(item.name)){
        if(remoteTarget!=null)throw new SyncException("待处理云端副本不唯一，请确认放弃后处理");
        remoteTarget=item;
      }
    }
    if((!sourceLocal.isEmpty()&&localSource==null)||(!sourceRemote.isEmpty()&&remoteSource==null)
        ||(!ln.isEmpty()&&localTarget==null)||(!rn.isEmpty()&&remoteTarget==null))
      throw new SyncException("待处理对象未确认，请检查后重试或确认放弃");
    JSONObject e;
    if(operation.equals("create-cloud-directory")||operation.equals("create-local-directory")){
      Local l=operation.equals("create-cloud-directory")?localSource:localTarget;
      CloudFilesClient.CloudItem r=operation.equals("create-cloud-directory")?remoteTarget:remoteSource;
      if(l==null||r==null||!l.folder()||!r.folder||record("dirs",l.uri.toString(),r.id)!=null)
        throw new SyncException("待处理目录映射无法确认");
      state.getJSONArray("dirs").put(new JSONObject().put("localParent",lp).put("remoteParent",rp)
          .put("local",l.uri.toString()).put("remote",r.id).put("logicalName",logical));
      end();return;
    }
    File lb=j.optString("localBytes","").isEmpty()?null:new File(storage,j.getString("localBytes"));
    File rb=j.optString("remoteBytes","").isEmpty()?null:new File(storage,j.getString("remoteBytes"));
    if((lb!=null&&!lb.isFile())||(rb!=null&&!rb.isFile()))throw new SyncException("恢复所需的内容快照缺失");
    boolean encrypted=j.optBoolean("encrypted",false);
    if(remoteTarget!=null){
      if(remoteTarget.folder||lb==null||!equal(lb,readCloud(remoteTarget,encrypted)))
        throw new SyncException("待处理云端副本内容无法确认");
      if(options.sharePassword!=null&&options.sharePassword.length>0)
        cloud.setPassword(remoteTarget,new String(options.sharePassword));
    }
    if(localTarget!=null&&(localTarget.folder()||rb==null||!equal(rb,readLocal(localTarget))))
      throw new SyncException("待处理本地副本内容无法确认");
    e=record("files",sourceLocal.isEmpty()?null:sourceLocal,sourceRemote.isEmpty()?null:sourceRemote);
    if(e!=null&&((!sourceLocal.isEmpty()&&!sourceLocal.equals(e.optString("local")))
        ||(!sourceRemote.isEmpty()&&!sourceRemote.equals(e.optString("remote")))))
      throw new SyncException("待处理文件映射不一致");
    if(operation.equals("conflict-copies")){
      if(e==null||localTarget==null||remoteTarget==null||lb==null||rb==null)
        throw new SyncException("冲突副本映射无法确认");
      ignore("ignoredLocal",localTarget.uri.toString());ignore("ignoredCloud",remoteTarget.id);
      e.put("conflict",true).put("localBase",lb.getName()).put("remoteBase",rb.getName());
    }else if(operation.equals("upload-version")){
      if(localSource==null||remoteTarget==null||lb==null)throw new SyncException("上传版本映射无法确认");
      if(e==null){e=new JSONObject().put("localParent",lp).put("remoteParent",rp)
          .put("local",sourceLocal).put("logicalName",logical);state.getJSONArray("files").put(e);}
      if(!sourceRemote.isEmpty())ignore("ignoredCloud",sourceRemote);
      e.put("remote",remoteTarget.id).put("encrypted",encrypted).put("newUpload",true)
          .put("policyRevision",j.optString("policyRevision",""))
          .put("localBase",lb.getName()).put("remoteBase",lb.getName());
    }else if(operation.equals("create-local-version")){
      if(remoteSource==null||localTarget==null||rb==null)throw new SyncException("本地版本映射无法确认");
      if(e==null){e=new JSONObject().put("localParent",lp).put("remoteParent",rp)
          .put("remote",sourceRemote).put("logicalName",logical);state.getJSONArray("files").put(e);}
      if(!sourceLocal.isEmpty())ignore("ignoredLocal",sourceLocal);
      e.put("local",localTarget.uri.toString()).put("localBase",rb.getName()).put("remoteBase",rb.getName());
    }else throw new SyncException("待处理操作类型无法确认");
    end();
  }
  private void end()throws Exception{state.put("pending",false).remove("operation");save();}
  private boolean validName(String name){return name!=null&&!name.isEmpty()&&!name.equals(".")&&!name.equals("..")&&!name.contains("/")&&!name.contains("\\");}
  private void appendWarning(String message){
    if(!warning.contains(message))warning=(warning.isEmpty()?"":warning+"；")+message;
  }
  private void warnChangedNames(String localName,String remoteName,String logicalName,boolean encrypted){
    String expectedRemote=logicalName+(encrypted?".zip":"");
    if(!logicalName.equals(localName)||!expectedRemote.equals(remoteName))
      appendWarning("部分文件或目录使用了版本副本名称");
  }
  private String unique(String name){
    int dot=name.lastIndexOf('.');
    String suffix=" (sync-"+UUID.randomUUID()+")";
    return dot>0?name.substring(0,dot)+suffix+name.substring(dot):name+suffix;
  }
  private static final class Local {
    final Uri uri;final String name,mime;final long modified,size;
    Local(Uri uri,String name,String mime,long modified,long size){this.uri=uri;this.name=name;this.mime=mime;this.modified=modified;this.size=size;}
    boolean folder(){return DIR.equals(mime);}
  }
  private List<Local> localChildren(Uri parent)throws Exception{
    Uri children=DocumentsContract.buildChildDocumentsUriUsingTree(tree,DocumentsContract.getDocumentId(parent));
    String[] columns={DocumentsContract.Document.COLUMN_DOCUMENT_ID,DocumentsContract.Document.COLUMN_DISPLAY_NAME,
        DocumentsContract.Document.COLUMN_MIME_TYPE,DocumentsContract.Document.COLUMN_LAST_MODIFIED,DocumentsContract.Document.COLUMN_SIZE};
    List<Local> result=new ArrayList<>();
    try(Cursor cursor=resolver.query(children,columns,null,null,null)){
      if(cursor==null)throw new SyncException("无法读取本地目录");
      checkDirectoryCursor(cursor);
      while(cursor.moveToNext()){
        String id=cursor.getString(0),name=cursor.getString(1),mime=cursor.getString(2);
        if(id==null||!validName(name)||mime==null)throw new SyncException("本地目录元数据不完整");
        result.add(new Local(DocumentsContract.buildDocumentUriUsingTree(tree,id),name,mime,
            cursor.isNull(3)?-1:cursor.getLong(3),cursor.isNull(4)?-1:cursor.getLong(4)));
      }
      checkDirectoryCursor(cursor);
    }
    return result;
  }
  private void checkDirectoryCursor(Cursor cursor)throws SyncException{
    android.os.Bundle extras=cursor.getExtras();
    if(extras!=null&&(extras.getBoolean(DocumentsContract.EXTRA_LOADING,false)
        ||extras.containsKey(DocumentsContract.EXTRA_ERROR)))
      throw new SyncException("本地目录读取未完成或失败，请重试");
  }
  private List<CloudFilesClient.CloudItem> cloudChildren(String folder)throws Exception{
    List<CloudFilesClient.CloudItem> result=new ArrayList<>();
    for(int page=1;page<=10000;page++){
      check();CloudFilesClient.CloudPage batch=cloud.listFolder(folder,page,100);
      for(CloudFilesClient.CloudItem item:batch.items){if(!validName(item.name))throw new SyncException("云端目录名称无法安全处理");result.add(item);}
      if(!batch.hasMore)return result;
    }
    throw new SyncException("云端目录分页超出限制");
  }
  private Set<String> strings(String key)throws Exception{
    Set<String> result=new HashSet<>();JSONArray a=state.getJSONArray(key);
    for(int i=0;i<a.length();i++)result.add(a.getString(i));return result;
  }
  private void ignore(String key,String value)throws Exception{state.getJSONArray(key).put(value);}
  private void ignoreMapping(JSONObject entry,boolean directory)throws Exception{
    String local=entry.getString("local"),remote=entry.getString("remote");
    if(directory){
      for(String kind:new String[]{"dirs","files"}){
        JSONArray entries=state.getJSONArray(kind);
        for(int i=0;i<entries.length();){
          JSONObject child=entries.getJSONObject(i);
          if(child!=entry&&(local.equals(child.optString("localParent"))||remote.equals(child.optString("remoteParent")))){
            ignoreMapping(child,"dirs".equals(kind));
            i=0;
          }else i++;
        }
      }
    }
    ignore("ignoredLocal",local);ignore("ignoredCloud",remote);
    JSONArray entries=state.getJSONArray(directory?"dirs":"files");
    for(int i=0;i<entries.length();i++)if(entries.getJSONObject(i)==entry){entries.remove(i);break;}
    ignoredDeletions++;
  }
  private void markIgnored(JSONObject entry,boolean directory)throws Exception{
    int previous=ignoredDeletions;
    ignoreMapping(entry,directory);
    state.put("ignoredDeletionTotal",state.optLong("ignoredDeletionTotal",0)+ignoredDeletions-previous);save();
  }
  private JSONObject record(String key,String local,String remote)throws Exception{
    JSONArray all=state.getJSONArray(key);
    for(int i=0;i<all.length();i++){
      JSONObject item=all.getJSONObject(i);
      if((local!=null&&local.equals(item.optString("local")))||(remote!=null&&remote.equals(item.optString("remote"))))return item;
    }
    return null;
  }
  private void syncDirectory(Uri localFolder,String remoteFolder,String path)throws Exception{
    check();
    List<Local> locals=localChildren(localFolder);List<CloudFilesClient.CloudItem> remotes=cloudChildren(remoteFolder);
    for(Local item:locals){
      JSONObject mapped=record(item.folder()?"dirs":"files",item.uri.toString(),null);
      if(mapped!=null&&!localFolder.toString().equals(mapped.optString("localParent")))markIgnored(mapped,item.folder());
    }
    for(CloudFilesClient.CloudItem item:remotes){
      JSONObject mapped=record(item.folder?"dirs":"files",null,item.id);
      if(mapped!=null&&!remoteFolder.equals(mapped.optString("remoteParent")))markIgnored(mapped,item.folder);
    }
    Map<String,Local> byLocal=new HashMap<>();Map<String,CloudFilesClient.CloudItem> byRemote=new HashMap<>();
    for(Local l:locals)byLocal.put(l.uri.toString(),l);
    for(CloudFilesClient.CloudItem r:remotes)byRemote.put(r.id,r);
    Set<String> usedL=strings("ignoredLocal"),usedR=strings("ignoredCloud");
    for(String kind:new String[]{"dirs","files"}){
      JSONArray all=state.getJSONArray(kind);
      for(int i=0;i<all.length();){
        JSONObject e=all.getJSONObject(i);
        if(!localFolder.toString().equals(e.optString("localParent"))||!remoteFolder.equals(e.optString("remoteParent"))){i++;continue;}
        Local l=byLocal.get(e.getString("local"));CloudFilesClient.CloudItem r=byRemote.get(e.getString("remote"));
        if(missingSide(l!=null,r!=null)){
          markIgnored(e,"dirs".equals(kind));
          usedL.addAll(strings("ignoredLocal"));usedR.addAll(strings("ignoredCloud"));
          i=0;continue;
        }
        usedL.add(l.uri.toString());usedR.add(r.id);
        String original=e.optString("logicalName",l.name);
        warnChangedNames(l.name,r.name,original,e.optBoolean("encrypted",false));
        String logical=path+original;
        if("dirs".equals(kind))syncDirectory(l.uri,r.id,logical+"/");else syncFile(localFolder,remoteFolder,l,r,e,logical);
        i++;
      }
    }
    usedL.addAll(strings("ignoredLocal"));usedR.addAll(strings("ignoredCloud"));
    Map<String,Local> namesL=new HashMap<>();Map<String,CloudFilesClient.CloudItem> namesR=new HashMap<>();
    for(Local l:locals)if(!usedL.contains(l.uri.toString())&&namesL.put(l.name,l)!=null)throw new SyncException("本地存在同名项目");
    for(CloudFilesClient.CloudItem r:remotes)if(!usedR.contains(r.id)&&namesR.put(r.name,r)!=null)throw new SyncException("云端存在同名项目");
    for(Local l:locals){
      if(usedL.contains(l.uri.toString()))continue;
      CloudFilesClient.CloudItem r=namesR.remove(l.name);
      boolean encrypted=false,createdUpload=false;
      if(r!=null){
        usedR.add(r.id);if(l.folder()!=r.folder)throw new SyncException("同名文件与目录冲突");
      }else if(l.folder()){
        String name=availableCloudFolderName(remoteFolder,l.name);begin("create-cloud-directory","",remoteFolder,"",name);
        journal(localFolder.toString(),remoteFolder,l.uri.toString(),"",l.name,null,null,false);
        String id=createCloudFolder(remoteFolder,name);
        if(id==null||id.isEmpty())throw new SyncException("云端目录创建结果未确认");
        r=CloudFilesClient.CloudItem.folder(id,name);
      }else{
        File local=readLocal(l);String name=unique(l.name);encrypted=encrypts(l.name);
        begin("upload-version","",remoteFolder,"",encrypted?name+".zip":name);
        journal(localFolder.toString(),remoteFolder,l.uri.toString(),"",l.name,local,null,encrypted);
        r=upload(remoteFolder,name,local,encrypted,path+l.name);createdUpload=true;
      }
      JSONObject e=new JSONObject().put("localParent",localFolder.toString()).put("remoteParent",remoteFolder)
          .put("local",l.uri.toString()).put("remote",r.id).put("logicalName",l.name).put("encrypted",encrypted).put("newUpload",createdUpload);
      warnChangedNames(l.name,r.name,l.name,encrypted);
      if(createdUpload)e.put("policyRevision",options.policyRevision);
      state.getJSONArray(l.folder()?"dirs":"files").put(e);
      if(state.optBoolean("pending"))end();else save();
      if(l.folder())syncDirectory(l.uri,r.id,path+l.name+"/");else syncFile(localFolder,remoteFolder,l,r,e,path+l.name);
    }
    for(CloudFilesClient.CloudItem r:namesR.values()){
      if(usedR.contains(r.id))continue;
      String name=availableLocalName(localFolder,r.name);
      warnChangedNames(name,r.name,r.name,false);
      if(r.folder){
        begin("create-local-directory",localFolder.toString(),"",name,"");
        journal(localFolder.toString(),remoteFolder,"",r.id,r.name,null,null,false);
        Uri uri=createLocalDocument(localFolder,DIR,name);
        JSONObject e=new JSONObject().put("localParent",localFolder.toString()).put("remoteParent",remoteFolder)
            .put("local",uri.toString()).put("remote",r.id).put("logicalName",r.name);
        state.getJSONArray("dirs").put(e);end();syncDirectory(uri,r.id,path+r.name+"/");
      }else{
        File remote=readCloud(r,false);begin("create-local-version",localFolder.toString(),"",name,"");
        journal(localFolder.toString(),remoteFolder,"",r.id,r.name,null,remote,false);
        Uri uri=writeLocal(localFolder,name,remote);
        Local l=findLocal(localFolder,uri);
        JSONObject e=new JSONObject().put("localParent",localFolder.toString()).put("remoteParent",remoteFolder)
            .put("local",uri.toString()).put("remote",r.id).put("logicalName",r.name);
        state.getJSONArray("files").put(e);end();syncFile(localFolder,remoteFolder,l,r,e,path+r.name);
      }
    }
  }
  private Local findLocal(Uri parent,Uri uri)throws Exception{
    for(Local item:localChildren(parent))if(item.uri.equals(uri))return item;
    throw new SyncException("本地版本创建结果未确认");
  }
  private String availableLocalName(Uri parent,String preferred)throws Exception{
    for(Local item:localChildren(parent))if(preferred.equals(item.name))return unique(preferred);
    return preferred;
  }
  private String availableCloudFolderName(String parent,String preferred)throws Exception{
    for(CloudFilesClient.CloudItem item:cloudChildren(parent))if(preferred.equals(item.name))return unique(preferred);
    return preferred;
  }
  private String createCloudFolder(String parent,String name)throws Exception{
    Set<String> before=new HashSet<>();
    for(CloudFilesClient.CloudItem item:cloudChildren(parent)){
      if(name.equals(item.name))throw new SyncException("云端目标名称已存在，未创建目录");
      before.add(item.id);
    }
    String id=cloud.createFolder(parent,name,"");
    if(id==null||id.isEmpty()||before.contains(id))throw new SyncException("云端目录创建结果未确认");
    state.getJSONObject("journal").put("createdRemote",id);save();
    int matches=0;boolean found=false;
    for(CloudFilesClient.CloudItem item:cloudChildren(parent))if(name.equals(item.name)){
      matches++;if(id.equals(item.id)&&item.folder)found=true;
    }
    if(!found||matches!=1)throw new SyncException("云端目录名称或结果存在冲突，请核验");
    return id;
  }
  private Uri createLocalDocument(Uri parent,String mime,String name)throws Exception{
    Set<String> before=new HashSet<>();
    for(Local item:localChildren(parent)){
      if(name.equals(item.name))throw new SyncException("本地目标名称已存在，未写入文件");
      before.add(item.uri.toString());
    }
    Uri uri=DocumentsContract.createDocument(resolver,parent,mime,name);
    if(uri==null)throw new SyncException("本地创建结果未确认");
    uri=DocumentsContract.buildDocumentUriUsingTree(tree,DocumentsContract.getDocumentId(uri));
    if(before.contains(uri.toString()))throw new SyncException("本地创建结果未确认");
    state.getJSONObject("journal").put("createdLocal",uri.toString());save();
    int matches=0;boolean found=false;
    for(Local item:localChildren(parent))if(name.equals(item.name)){
      matches++;if(uri.equals(item.uri)&&DIR.equals(mime)==item.folder())found=true;
    }
    if(!found||matches!=1)throw new SyncException("本地名称或创建结果存在冲突，请核验");
    return uri;
  }
  private File fresh(){File file=new File(storage,UUID.randomUUID().toString());temporary.add(file);return file;}
  private void cleanupTemps(){
    if(state!=null&&state.optBoolean("pending",false)){temporary.clear();return;}
    Set<String> baselines=new HashSet<>();
    if(state!=null){JSONArray entries=state.optJSONArray("files");if(entries!=null)for(int i=0;i<entries.length();i++){
      JSONObject e=entries.optJSONObject(i);if(e!=null){baselines.add(e.optString("localBase"));baselines.add(e.optString("remoteBase"));}
    }}
    for(File file:temporary){
      if(file.isDirectory()){
        File[] children=file.listFiles();if(children!=null)for(File child:children)if(child.isFile())child.delete();
        file.delete();
      }else if(!baselines.contains(file.getName()))file.delete();
    }
    temporary.clear();
  }
  private static void copy(InputStream in,OutputStream out)throws Exception{
    byte[] b=new byte[65536];int n;while((n=in.read(b))!=-1)out.write(b,0,n);
  }
  private File readLocal(Local item)throws Exception{
    File target=fresh();
    try(InputStream in=resolver.openInputStream(item.uri);OutputStream out=new FileOutputStream(target)){
      if(in==null)throw new SyncException("无法读取本地文件");copy(in,out);
    }
    return target;
  }
  private File readCloud(CloudFilesClient.CloudItem item,boolean encrypted)throws Exception{
    File target=fresh();cloud.download(item,target.getAbsolutePath());
    if(!target.isFile())throw new SyncException("云端下载结果未确认");
    if(!encrypted)return target;
    if(options.zipPassword==null||options.zipPassword.length==0)throw new SyncException("加密版本需要 ZIP 密码");
    File decoded=fresh();
    try{
      net.lingala.zip4j.ZipFile zip=new net.lingala.zip4j.ZipFile(target,options.zipPassword);
      java.util.List<net.lingala.zip4j.model.FileHeader> headers=zip.getFileHeaders();
      if(headers.size()!=1||headers.get(0).isDirectory()||!headers.get(0).isEncrypted())
        throw new SyncException("加密版本格式无法确认");
      try(InputStream in=zip.getInputStream(headers.get(0));OutputStream out=new FileOutputStream(decoded)){copy(in,out);}
    }
    catch(SyncException error){throw error;}
    catch(Exception error){throw new SyncException("无法解密云端版本，请检查原 ZIP 密码");}
    target.delete();return decoded;
  }
  private boolean encrypts(String name){
    int dot=name.lastIndexOf('.');if(dot<0)return false;
    String ext=name.substring(dot+1);
    for(String part:options.extensions.split("[,;\\s]+")){
      String candidate=part.startsWith(".")?part.substring(1):part;
      if(!candidate.isEmpty()&&candidate.equalsIgnoreCase(ext))return true;
    }
    return false;
  }
  static boolean equal(File a,File b)throws Exception{
    if(!a.isFile()||!b.isFile()||a.length()!=b.length())return false;
    try(InputStream x=new FileInputStream(a);InputStream y=new FileInputStream(b)){
      byte[] p=new byte[65536],q=new byte[65536];int n;
      while((n=x.read(p))!=-1){int count=0,k;while(count<n&&(k=y.read(q,count,n-count))!=-1)count+=k;
        if(count!=n)return false;for(int i=0;i<n;i++)if(p[i]!=q[i])return false;}
      return y.read()==-1;
    }
  }
  private void recordUpload(String path,String status,long size){
    if(taskId<=0||path==null)return;
    try{new CloudSyncTasks(context).recordUpload(taskId,path,status,"",size);}
    catch(Exception ignored){}
  }
  private void finishActiveUpload(boolean success){
    if(activeUploadPath==null)return;
    recordUpload(activeUploadPath,success?"已完成":"失败",activeUploadBytes);
    activeUploadPath=null;activeUploadBytes=0;
  }
  private CloudFilesClient.CloudItem upload(String parent,String name,File data,boolean encrypted,String relativePath)throws Exception{
    File dir=new File(storage,UUID.randomUUID().toString());if(!dir.mkdir())throw new SyncException("无法准备上传");temporary.add(dir);
    File named=new File(dir,name);
    try(InputStream in=new FileInputStream(data);OutputStream out=new FileOutputStream(named)){copy(in,out);}
    File uploadFile=named;
    if(encrypted)uploadFile=CloudEncryptedZip.encryptSingleFile(named,new File(dir,name+".zip"),options.zipPassword);
    String sentName=uploadFile.getName();
    CloudFilesClient.UploadOptions uploadOptions=CloudFilesClient.UploadOptions.defaults();
    boolean apk=name.toLowerCase(java.util.Locale.ROOT).endsWith(".apk");
    if(apk){uploadOptions.autoIcon(true);if(encrypted)uploadOptions.iconPath(named.getAbsolutePath());}
    activeUploadPath=relativePath;activeUploadBytes=data.length();
    recordUpload(relativePath,"上传中",activeUploadBytes);
    CloudFilesClient.UploadResult result=cloud.upload(parent,uploadFile.getAbsolutePath(),uploadOptions);
    if(!result.warning.isEmpty())appendWarning(cloud.provider==CloudFilesClient.Provider.PREMIUM
        ?"优享云不支持自动设置文件图标":"文件已上传，但云端图标设置未完成");
    if(result.id.isEmpty()||!sentName.equals(result.uploadedName)||result.compatibilityWrapped)
      throw new SyncException("云端版本写入结果未确认");
    for(CloudFilesClient.CloudItem item:cloudChildren(parent))if(item.id.equals(result.id)&&!item.folder){
      if(options.sharePassword!=null&&options.sharePassword.length>0)cloud.setPassword(item,new String(options.sharePassword));
      return item;
    }
    throw new SyncException("云端版本未出现在目录中");
  }
  private Uri writeLocal(Uri parent,String name,File data)throws Exception{
    Uri uri=createLocalDocument(parent,"application/octet-stream",name);
    try(InputStream in=new FileInputStream(data);OutputStream out=resolver.openOutputStream(uri,"w")){
      if(out==null)throw new SyncException("无法写入本地版本");copy(in,out);
    }
    return uri;
  }
  private void syncFile(Uri localParent,String remoteParent,Local local,CloudFilesClient.CloudItem remote,JSONObject entry,String relativePath)throws Exception{
    check();boolean encrypted=entry.optBoolean("encrypted",false);File l=readLocal(local),r=readCloud(remote,encrypted);
    File localCheck=readLocal(local),remoteCheck=readCloud(remote,encrypted);
    boolean stable=equal(l,localCheck)&&equal(r,remoteCheck);
    localCheck.delete();remoteCheck.delete();
    if(!stable)throw new SyncException("文件在读取期间发生变化，请重新同步");
    boolean hasBase=entry.has("localBase")&&entry.has("remoteBase");
    File lb=hasBase?new File(storage,entry.getString("localBase")):null;
    File rb=hasBase?new File(storage,entry.getString("remoteBase")):null;
    if(hasBase&&(!lb.isFile()||!rb.isFile()))throw new SyncException("同步基线缺失");
    boolean lc=!hasBase||!equal(l,lb),rc=!hasBase||!equal(r,rb);
    boolean policyChanged=!options.policyRevision.isEmpty()&&!options.policyRevision.equals(entry.optString("policyRevision",""));
    String detail=entry.optBoolean("newUpload",false)?"已完成":"跳过";
    Change change=decideChange(equal(l,r),hasBase,lc,rc,entry.optBoolean("conflict",false));
    if(change!=Change.EQUAL){
      if(change==Change.CONFLICT){
        String remoteName=unique(local.name),localName=unique(entry.optString("logicalName",local.name));boolean useZip=encrypts(local.name);
        begin("conflict-copies",localParent.toString(),remoteParent,localName,useZip?remoteName+".zip":remoteName);
        journal(localParent.toString(),remoteParent,local.uri.toString(),remote.id,entry.optString("logicalName",local.name),l,r,useZip);
        final File localSnapshot=l,remoteSnapshot=r;
        String[] copies=preserveConflict(new ConflictCopies(){
          @Override public String copyToCloud()throws Exception{return upload(remoteParent,remoteName,localSnapshot,useZip,relativePath).id;}
          @Override public String copyToLocal()throws Exception{return writeLocal(localParent,localName,remoteSnapshot).toString();}
        });
        ignore("ignoredCloud",copies[0]);ignore("ignoredLocal",copies[1]);
        entry.put("conflict",true);conflicts++;detail="冲突";
      }else if(change==Change.LOCAL){
        detail="已完成";String name=unique(local.name);boolean useZip=encrypts(local.name);
        begin("upload-version","",remoteParent,"",useZip?name+".zip":name);
        journal(localParent.toString(),remoteParent,local.uri.toString(),remote.id,entry.optString("logicalName",local.name),l,r,useZip);
        CloudFilesClient.CloudItem next=upload(remoteParent,name,l,useZip,relativePath);
        ignore("ignoredCloud",remote.id);entry.put("remote",next.id).put("encrypted",useZip);remote=next;r=readCloud(next,useZip);
        if(!equal(l,r))throw new SyncException("上传版本与源文件不一致");
        entry.put("policyRevision",options.policyRevision);policyChanged=false;
      }else if(change==Change.REMOTE){
        String name=unique(entry.optString("logicalName",local.name));begin("create-local-version",localParent.toString(),"",name,"");
        journal(localParent.toString(),remoteParent,local.uri.toString(),remote.id,entry.optString("logicalName",local.name),l,r,encrypted);
        Uri next=writeLocal(localParent,name,r);
        ignore("ignoredLocal",local.uri.toString());entry.put("local",next.toString());local=findLocal(localParent,next);l=readLocal(local);
        if(!equal(l,r))throw new SyncException("本地版本与源文件不一致");
      }else if(change==Change.UNRESOLVED){conflicts++;detail="冲突";}
      else throw new SyncException("文件内容无法可靠判断");
    }else if(entry.optBoolean("conflict",false)){
      // The user resolved the conflict by making both current versions identical.
      entry.remove("conflict");
    }
    if(policyChanged&&!entry.optBoolean("conflict",false)&&equal(l,r)){
      if(state.optBoolean("pending",false)){
        entry.put("localBase",l.getName()).put("remoteBase",r.getName());end();
        throw new SyncException("文件已同步，请再次运行以应用新的加密或分享设置");
      }
      String name=unique(entry.optString("logicalName",local.name));boolean useZip=encrypts(name);
      begin("upload-version","",remoteParent,"",useZip?name+".zip":name);
      journal(localParent.toString(),remoteParent,local.uri.toString(),remote.id,entry.optString("logicalName",local.name),l,r,useZip);
      CloudFilesClient.CloudItem next=upload(remoteParent,name,l,useZip,relativePath);
      ignore("ignoredCloud",remote.id);entry.put("remote",next.id).put("encrypted",useZip);
      remote=next;r=readCloud(next,useZip);
      if(!equal(l,r))throw new SyncException("上传版本与源文件不一致");
      entry.put("policyRevision",options.policyRevision);detail="已完成";
    }
    entry.remove("newUpload");
    entry.put("localBase",l.getName()).put("remoteBase",r.getName())
        .put("localModified",local.modified).put("localSize",local.size)
        .put("remoteModified",remote.modifiedAt).put("remoteSize",remote.size);
    if(state.optBoolean("pending"))end();else save();
    if(lb!=null&&!lb.equals(l)&&!lb.equals(r))lb.delete();
    if(rb!=null&&!rb.equals(l)&&!rb.equals(r))rb.delete();
    recordUpload(relativePath,detail,l.length());
    activeUploadPath=null;activeUploadBytes=0;
    files++;bytes+=l.length();if(progress!=null)progress.onProgress(files,bytes);
  }
}
