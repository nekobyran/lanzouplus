package cc.nkbr.lanzouplus;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import net.lingala.zip4j.io.inputstream.ZipInputStream;
import net.lingala.zip4j.io.outputstream.ZipOutputStream;
import net.lingala.zip4j.model.LocalFileHeader;
import net.lingala.zip4j.model.ZipParameters;
import net.lingala.zip4j.model.enums.AesKeyStrength;
import net.lingala.zip4j.model.enums.EncryptionMethod;
import org.json.JSONException;
import org.json.JSONObject;

/** Append-only encrypted records: unchanged values are never uploaded again. */
final class GithubBackupRecords {
  interface Cancellation { boolean isCancelled(); }
  private static final Object LOCK=new Object();
  private final GithubBackupRepository repository;
  private final android.content.SharedPreferences baselines;
  private final java.io.File cacheDirectory;
  GithubBackupRecords(GithubBackupRepository repository){this.repository=repository;this.baselines=null;this.cacheDirectory=null;}
  GithubBackupRecords(GithubBackupRepository repository,android.content.Context context){
    this.repository=repository;
    this.cacheDirectory=context.getApplicationContext().getCacheDir();
    this.baselines=context.getApplicationContext().getSharedPreferences("github_backup_baseline_v1",0);
  }
  static final class VersionRef{
    final String versionId;
    VersionRef(String versionId){this.versionId=versionId;}
  }
  static final class Conflict{
    final String kind,key,reason;final List<VersionRef> remoteVersions;
    Conflict(String kind,String key,String reason,List<JSONObject> versions){
      this.kind=kind;this.key=key;this.reason=reason;this.remoteVersions=new ArrayList<>();
      for(JSONObject version:versions)remoteVersions.add(new VersionRef(version.optString("_version")));
    }
  }
  static final class SyncResult{
    int uploaded,unchanged;final List<Conflict> conflicts=new ArrayList<>();
  }

  /** Returns only current causal heads. Concurrent heads remain separate. */
  List<JSONObject> readAll(char[] password)throws IOException{return readAll(password,null);}
  List<JSONObject> readAll(char[] password,Cancellation cancellation)throws IOException{
    return heads(readVersions(password,cancellation));
  }
  private List<JSONObject> readVersions(char[] password,Cancellation cancellation)throws IOException{
    return readVersions(password,cancellation,null);
  }
  private List<JSONObject> readVersions(char[] password,Cancellation cancellation,String block)throws IOException{
    requirePassword(password);checkCancelled(cancellation);
    List<JSONObject> versions=new ArrayList<>();List<String> paths=repository.records(()->checkCancelled(cancellation),block);
    try{
      for(String path:paths){
        checkCancelled(cancellation);String leaf=path.substring(path.lastIndexOf('/')+1);String id;
        if("manifest".equals(leaf)){
          String parent=path.substring(0,path.lastIndexOf('/'));
          id=parent.substring(parent.lastIndexOf('/')+1);
        }else{
          int suffix=leaf.lastIndexOf('.');if(suffix<0)throw new IOException("备份记录名称无效");
          id=leaf.substring(0,suffix);
        }
        if(!id.matches("[0-9a-fA-F-]{36}"))throw new IOException("备份版本标识无效");
        JSONObject record=loadRecord(path,id,password,cancellation);
        String actualBlock=blockForKind(record.optString("kind"));String pathBlock=pathBlock(path);
        if(pathBlock!=null&&!pathBlock.equals(actualBlock))throw new IOException("备份记录的数据块不匹配");
        if(block!=null&&!block.equals(actualBlock))continue;
        record.put("_version",id);versions.add(record);
      }    }catch(JSONException error){throw new IOException("备份版本格式无效",error);}
    checkCancelled(cancellation);return versions;
  }
  private JSONObject loadRecord(String path,String id,char[] password,Cancellation cancellation)throws IOException{
    java.io.File cached=cacheFile(id);
    if(cached!=null){
      try{
        JSONObject record=decrypt(new android.util.AtomicFile(cached).openRead(),password,cancellation);
        verifyVersion(record,id);return record;
      }catch(HeapSpaceException error){throw error;
      }catch(IOException error){checkCancelled(cancellation);}
    }
    java.io.File downloaded=tempFile();
    try{
      repository.readToFile(path,downloaded,()->checkCancelled(cancellation));
      JSONObject record=decrypt(new java.io.FileInputStream(downloaded),password,cancellation);
      verifyVersion(record,id);checkCancelled(cancellation);
      if(cached!=null)saveCache(cached,downloaded,cancellation);
      return record;
    }finally{downloaded.delete();}
  }
  private static void verifyVersion(JSONObject record,String id)throws IOException{
    if(record.has("_version")&&!id.equals(record.optString("_version")))
      throw new IOException("备份版本标识不一致");
  }
  private java.io.File cacheFile(String id){
    if(cacheDirectory==null)return null;
    String identity=repository.baselineIdentity().toLowerCase(java.util.Locale.ROOT);
    String encoded=android.util.Base64.encodeToString(identity.getBytes(StandardCharsets.UTF_8),
        android.util.Base64.URL_SAFE|android.util.Base64.NO_WRAP|android.util.Base64.NO_PADDING);
    java.io.File directory=new java.io.File(cacheDirectory,"github-backup-cipher-v1");
    for(int i=0;i<encoded.length();i+=100)
      directory=new java.io.File(directory,encoded.substring(i,Math.min(i+100,encoded.length())));
    return new java.io.File(directory,id.toLowerCase(java.util.Locale.ROOT)+".zip");
  }
  private static void saveCache(java.io.File target,java.io.File source,Cancellation cancellation)throws IOException{
    java.io.File parent=target.getParentFile();
    if(!parent.isDirectory()&&!parent.mkdirs()&&!parent.isDirectory())return;
    android.util.AtomicFile file=new android.util.AtomicFile(target);java.io.FileOutputStream output=null;
    try(java.io.FileInputStream input=new java.io.FileInputStream(source)){
      output=file.startWrite();byte[] buffer=new byte[65536];int count;
      while((count=input.read(buffer))!=-1){checkCancelled(cancellation);output.write(buffer,0,count);}
      checkCancelled(cancellation);file.finishWrite(output);output=null;
    }catch(IOException error){
      if(output!=null)file.failWrite(output);
      checkCancelled(cancellation);
      // Cache is disposable; a failed cache write must not change a successful backup.
    }
  }
  private static final class HeapSpaceException extends IOException{
    HeapSpaceException(){super("可用内存不足，无法恢复此设置或源；请在内存更大的设备恢复");}
  }  private static List<JSONObject> heads(List<JSONObject> versions)throws IOException{
    java.util.Map<String,JSONObject> byId=new java.util.HashMap<>();
    java.util.Set<String> superseded=new java.util.HashSet<>();
    for(JSONObject record:versions){String id=record.optString("_version","");
      if(id.isEmpty()||byId.put(id,record)!=null)throw new IOException("备份版本标识重复或缺失");}
    for(JSONObject record:versions){
      org.json.JSONArray parents=record.optJSONArray("_parents");if(parents==null)continue;
      for(int i=0;i<parents.length();i++){
        String id=parents.optString(i,"");JSONObject parent=byId.get(id);
        if(parent==null||!sameKey(record,parent))throw new IOException("备份版本关系不完整");
        superseded.add(id);
      }
    }
    List<JSONObject> result=new ArrayList<>();
    for(JSONObject value:versions)if(!superseded.contains(value.optString("_version")))result.add(value);
    return result;
  }
  /** Legacy entry points fail closed unless constructed with the local baseline store. */
  int uploadChanges(List<JSONObject> current,char[] password)throws IOException{
    return uploadChanges(current,password,null);
  }
  int uploadChanges(List<JSONObject> current,char[] password,Cancellation cancellation)throws IOException{
    SyncResult result=uploadChangesDetailed(current,password,cancellation);
    if(!result.conflicts.isEmpty())throw new IOException("备份存在冲突，请先处理后重试");
    return result.uploaded;
  }
  static final class BlockResult{
    final String kind;String status="success",errorSummary="";int uploaded,unchanged,conflicts;
    BlockResult(String kind){this.kind=kind;}
  }
  /** Each data block is independently snapshotted, compared and uploaded. */
  List<BlockResult> syncBlocks(GithubBackupState state,char[] password,Cancellation cancellation)throws IOException{
    if(state==null||baselines==null)throw new IOException("备份缺少本机同步基线");
    synchronized(LOCK){
      requirePassword(password);List<BlockResult> results=new ArrayList<>();
      try{verifyRemotePassword(password,cancellation);}
      catch(IOException error){
        checkCancelled(cancellation);
        for(GithubBackupState.Block block:GithubBackupState.Block.values()){
          BlockResult failed=new BlockResult(block.kind);failed.status="failed";
          failed.errorSummary="无法确认备份密码或读取远端记录";results.add(failed);
        }
        return results;
      }
      for(GithubBackupState.Block block:GithubBackupState.Block.values()){
        checkCancelled(cancellation);BlockResult output=new BlockResult(block.kind);SyncResult partial=new SyncResult();
        try{
          List<JSONObject> snapshot=state.snapshotBlock(block);
          List<JSONObject> versions=readVersions(password,cancellation,block.kind);
          uploadWithVersions(snapshot,password,cancellation,versions,partial);
          if(!partial.conflicts.isEmpty())output.status="needs_attention";
        }catch(Exception error){
          checkCancelled(cancellation);output.status="failed";
          output.errorSummary="此数据块读取或同步失败，请检查密码、网络和存储空间";
        }
        output.uploaded=partial.uploaded;output.unchanged=partial.unchanged;output.conflicts=partial.conflicts.size();
        results.add(output);
      }
      return results;
    }
  }
  /** A non-empty repository must authenticate at least one existing record before any write. */
  private void verifyRemotePassword(char[] password,Cancellation cancellation)throws IOException{
    boolean sawRecord=false,listingFailed=false;
    for(GithubBackupState.Block block:GithubBackupState.Block.values()){
      List<String> paths;
      try{paths=repository.records(()->checkCancelled(cancellation),block.kind);}
      catch(IOException error){checkCancelled(cancellation);listingFailed=true;continue;}
      for(String path:paths){
        sawRecord=true;checkCancelled(cancellation);
        try{
          String leaf=path.substring(path.lastIndexOf('/')+1),id;
          if("manifest".equals(leaf)){String parent=path.substring(0,path.lastIndexOf('/'));id=parent.substring(parent.lastIndexOf('/')+1);}
          else{int dot=leaf.lastIndexOf('.');if(dot<0)continue;id=leaf.substring(0,dot);}
          if(!id.matches("[0-9a-fA-F-]{36}"))continue;
          JSONObject record=loadRecord(path,id,password,cancellation);
          String actual=blockForKind(record.optString("kind")),visible=pathBlock(path);
          if(visible==null||visible.equals(actual))return;
        }catch(IOException error){checkCancelled(cancellation);}
      }
    }
    if(sawRecord||listingFailed)throw new IOException("无法确认备份密码或读取远端记录");
  }  /** Performs network reads only and never modifies baselines or remote data. */
  List<Conflict> previewConflicts(List<JSONObject> current,char[] password,Cancellation cancellation)throws IOException{
    if(baselines==null)throw new IOException("备份缺少本机同步基线，无法检查冲突");
    synchronized(LOCK){
      List<JSONObject> versions=readVersions(password,cancellation),remote=heads(versions);
      java.util.Map<String,JSONObject> byId=new java.util.HashMap<>();
      for(JSONObject value:versions)byId.put(value.optString("_version"),value);
      List<Conflict> conflicts=new ArrayList<>();
      for(JSONObject value:current){
        checkCancelled(cancellation);validate(value);List<JSONObject> candidates=matching(remote,value);
        String baseline=baselines.getString(baselineKey(value),"");JSONObject base=byId.get(baseline);
        String reason="";
        if(candidates.isEmpty()){if(!baseline.isEmpty())reason="本机基线不存在于远端";}
        else if(candidates.size()!=1)reason="远端存在多个并发版本";
        else if(baseline.isEmpty()){
          if(!sameValue(value,candidates.get(0)))reason="本机无基线且与远端不同";
        }else if(base==null||!sameKey(base,value)||!baseline.equals(candidates.get(0).optString("_version")))
          reason="远端自上次同步后已变化";
        if(!reason.isEmpty())conflicts.add(new Conflict(value.optString("kind"),value.optString("key"),reason,candidates));
      }
      addRemoteOnlyConflicts(current,remote,conflicts);
      return conflicts;
    }
  }  SyncResult uploadChangesDetailed(List<JSONObject> current,char[] password,Cancellation cancellation)throws IOException{
    if(baselines==null)throw new IOException("备份缺少本机同步基线，无法安全上传");
    synchronized(LOCK){
      SyncResult result=new SyncResult();
      uploadWithVersions(current,password,cancellation,readVersions(password,cancellation),result);
      return result;
    }
  }
  private void uploadWithVersions(List<JSONObject> current,char[] password,Cancellation cancellation,
      List<JSONObject> versions,SyncResult result)throws IOException{
    List<JSONObject> remote=heads(versions);
    java.util.Map<String,JSONObject> byId=new java.util.HashMap<>();
    for(JSONObject value:versions)byId.put(value.optString("_version"),value);
    for(JSONObject value:current){
      checkCancelled(cancellation);validate(value);List<JSONObject> candidates=matching(remote,value);
      String baseline=baselines.getString(baselineKey(value),"");JSONObject base=byId.get(baseline);
      if(candidates.isEmpty()){
        if(!baseline.isEmpty()){result.conflicts.add(new Conflict(value.optString("kind"),value.optString("key"),"本机基线不存在于远端",candidates));continue;}
        String id=writeVersion(value,new ArrayList<String>(),password,cancellation);
        result.uploaded++;saveBaseline(value,id);continue;
      }
      if(candidates.size()!=1){result.conflicts.add(new Conflict(value.optString("kind"),value.optString("key"),"远端存在多个并发版本",candidates));continue;}
      JSONObject head=candidates.get(0);String headId=head.optString("_version");
      if(baseline.isEmpty()){
        if(sameValue(value,head)){saveBaseline(value,headId);result.unchanged++;}
        else result.conflicts.add(new Conflict(value.optString("kind"),value.optString("key"),"本机无基线且与远端不同",candidates));
        continue;
      }
      if(base==null||!sameKey(base,value)||!baseline.equals(headId)){
        result.conflicts.add(new Conflict(value.optString("kind"),value.optString("key"),"远端自上次同步后已变化",candidates));continue;
      }
      if(sameValue(value,base)){result.unchanged++;continue;}
      List<String> parents=new ArrayList<>();parents.add(baseline);
      String id=writeVersion(value,parents,password,cancellation);result.uploaded++;saveBaseline(value,id);
    }
    addRemoteOnlyConflicts(current,remote,result.conflicts);
  }  interface ConflictApplier {
    /** Must atomically reject if the local value differs from expectedLocal, then apply selected. */
    void apply(JSONObject expectedLocal,JSONObject selected)throws IOException;
  }
  void resolveConflict(JSONObject chosen,List<String> expectedHeads,char[] password,Cancellation cancellation)throws IOException{
    throw new IOException("冲突选择需要先安全应用本机数据，当前操作不可用");
  }
  void resolveConflictAndApply(JSONObject expectedLocal,JSONObject chosen,List<String> expectedHeads,
      char[] password,Cancellation cancellation,ConflictApplier applier)throws IOException{
    if(baselines==null||applier==null)throw new IOException("缺少本机安全应用接口");
    synchronized(LOCK){
      validate(expectedLocal);validate(chosen);
      if(!sameKey(expectedLocal,chosen))throw new IOException("冲突选择与本机项目不一致");
      List<JSONObject> actual=matching(heads(readVersions(password,cancellation)),chosen);
      java.util.Set<String> actualIds=new java.util.HashSet<>();
      for(JSONObject item:actual)actualIds.add(item.optString("_version"));
      if(expectedHeads==null||actualIds.size()!=expectedHeads.size()||!actualIds.equals(new java.util.HashSet<>(expectedHeads)))
        throw new IOException("远端冲突已变化，请重新查看");
      boolean isCandidate=sameValue(chosen,expectedLocal);
      for(JSONObject candidate:actual)if(sameValue(chosen,candidate)){isCandidate=true;break;}
      if(!isCandidate)throw new IOException("所选版本已不在冲突列表中");
      checkCancelled(cancellation);
      String localId=writeVersion(expectedLocal,new ArrayList<String>(),password,cancellation);
      actualIds.add(localId);
      checkCancelled(cancellation);applier.apply(expectedLocal,chosen);
      String id=writeVersion(chosen,new ArrayList<>(actualIds),password,cancellation);saveBaseline(chosen,id);
    }
  }  private String writeVersion(JSONObject value,List<String> parents,char[] password,Cancellation cancellation)throws IOException{
    String id=UUID.randomUUID().toString();java.io.File encrypted=tempFile();
    try{
      encryptToFile(value,id,parents,password,encrypted,cancellation);checkCancelled(cancellation);
      repository.addRecord(blockForKind(value.optString("kind")),id,encrypted,()->checkCancelled(cancellation));
      java.io.File cached=cacheFile(id);if(cached!=null)saveCache(cached,encrypted,cancellation);return id;
    }finally{encrypted.delete();}
  }
  private java.io.File tempFile()throws IOException{
    return java.io.File.createTempFile("github-backup-",".zip",cacheDirectory);
  }  private static void addRemoteOnlyConflicts(List<JSONObject> current,List<JSONObject> remote,List<Conflict> output){
    java.util.Set<String> handled=new java.util.HashSet<>();
    for(JSONObject value:remote){
      if(!matching(current,value).isEmpty())continue;
      String identity=value.optString("kind")+":"+value.optString("key");
      if(!handled.add(identity))continue;
      List<JSONObject> candidates=matching(remote,value);
      if(candidates.size()>1)output.add(new Conflict(value.optString("kind"),value.optString("key"),
          "远端存在多个并发版本",candidates));
    }
  }  private static List<JSONObject> matching(List<JSONObject> records,JSONObject target){
    List<JSONObject> found=new ArrayList<>();for(JSONObject value:records)if(sameKey(value,target))found.add(value);return found;
  }
  private String baselineKey(JSONObject record){
    String id=repository.baselineIdentity()+"\n"+record.optString("kind")+"\n"+record.optString("key");
    return android.util.Base64.encodeToString(id.getBytes(StandardCharsets.UTF_8),android.util.Base64.NO_WRAP);
  }
  private void saveBaseline(JSONObject record,String id)throws IOException{
    if(!baselines.edit().putString(baselineKey(record),id).commit())throw new IOException("无法保存本机同步基线");
  }  private static void checkCancelled(Cancellation cancellation)throws java.io.InterruptedIOException{
    if(Thread.currentThread().isInterrupted()||(cancellation!=null&&cancellation.isCancelled()))
      throw new java.io.InterruptedIOException("同步已取消");
  }
  private static String blockForKind(String kind)throws IOException{
    switch(kind){
      case "setting":return "settings";
      case "source":return "sources";
      case "ordinary":case "premium":case "ordinary-current":case "premium-current":return "cloud_accounts";
      case "sync-task":return "sync_tasks";
      default:throw new IOException("备份记录类型不受支持");
    }
  }
  private static String pathBlock(String path){
    int slash=path.indexOf('/');if(slash<0)return null;String first=path.substring(0,slash);
    return first.equals("settings")||first.equals("sources")||first.equals("cloud_accounts")||first.equals("sync_tasks")?first:null;
  }  private static boolean sameKey(JSONObject first,JSONObject second){
    return first.optString("kind").equals(second.optString("kind"))
        &&first.optString("key").equals(second.optString("key"));
  }
  private static boolean sameValue(JSONObject first,JSONObject second){
    return first.optString("kind").equals(second.optString("kind"))
        &&first.optString("key").equals(second.optString("key"))
        &&first.optString("value").equals(second.optString("value"));
  }
  private static void validate(JSONObject record)throws IOException{
    if(record==null||!(record.opt("kind") instanceof String)
        ||!(record.opt("key") instanceof String)||!(record.opt("value") instanceof String)
        ||record.optString("kind").isEmpty()||record.optString("key").isEmpty())
      throw new IOException("备份记录格式无效");
  }
  private static void requirePassword(char[] password)throws IOException{
    if(password==null||password.length==0)throw new IOException("请输入备份密码");
  }
  /** ZIP content is written incrementally; the cache contains ciphertext only. */
  private static void encryptToFile(JSONObject record,String id,List<String> parents,char[] password,
      java.io.File destination,Cancellation cancellation)throws IOException{
    requirePassword(password);
    try(ZipOutputStream zip=new ZipOutputStream(new java.io.FileOutputStream(destination),password)){
      ZipParameters options=new ZipParameters();options.setFileNameInZip("record.json");
      options.setEncryptFiles(true);options.setEncryptionMethod(EncryptionMethod.AES);
      options.setAesKeyStrength(AesKeyStrength.KEY_STRENGTH_256);zip.putNextEntry(options);
      java.io.OutputStreamWriter writer=new java.io.OutputStreamWriter(zip,StandardCharsets.UTF_8);
      writer.write("{\"kind\":");writeJsonString(writer,record.optString("kind"),cancellation);
      writer.write(",\"key\":");writeJsonString(writer,record.optString("key"),cancellation);
      writer.write(",\"value\":");writeJsonString(writer,record.optString("value"),cancellation);
      writer.write(",\"at\":");writer.write(Long.toString(record.optLong("at")));
      writer.write(",\"_version\":");writeJsonString(writer,id,cancellation);
      writer.write(",\"_parents\":");writer.write(new org.json.JSONArray(parents).toString());writer.write('}');
      writer.flush();zip.closeEntry();
    }
  }
  private static void writeJsonString(java.io.Writer writer,String value,Cancellation cancellation)throws IOException{
    writer.write('"');
    for(int start=0;start<value.length();){
      checkCancelled(cancellation);int end=Math.min(value.length(),start+8192);
      if(end<value.length()&&Character.isHighSurrogate(value.charAt(end-1))
          &&Character.isLowSurrogate(value.charAt(end)))end--;
      String quoted=JSONObject.quote(value.substring(start,end));
      writer.write(quoted,1,quoted.length()-2);start=end;
    }
    writer.write('"');
  }
  /** The app currently represents each value as a Java String, so decoding needs available heap. */
  private static JSONObject decrypt(java.io.InputStream encrypted,char[] password,Cancellation cancellation)throws IOException{
    try(ZipInputStream zip=new ZipInputStream(encrypted,password)){
      LocalFileHeader entry=zip.getNextEntry();
      if(entry==null||!entry.isEncrypted()||!"record.json".equals(entry.getFileName()))
        throw new IOException("备份记录格式无效");
      ByteArrayOutputStream bytes=new ByteArrayOutputStream();byte[] block=new byte[8192];int count;
      while((count=zip.read(block))!=-1){
        checkCancelled(cancellation);
        Runtime runtime=Runtime.getRuntime();long available=runtime.maxMemory()-(runtime.totalMemory()-runtime.freeMemory());
        if(available<((long)bytes.size()+count)*4+8L*1024*1024)
          throw new HeapSpaceException();
        bytes.write(block,0,count);
      }
      if(zip.getNextEntry()!=null)throw new IOException("备份记录格式无效");
      JSONObject record=new JSONObject(new String(bytes.toByteArray(),StandardCharsets.UTF_8));
      validate(record);return record;
    }catch(JSONException error){throw new IOException("备份记录格式无效",error);}
  }
}