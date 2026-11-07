package cc.nkbr.lanzouplus;

import android.net.Uri;
import android.util.Base64;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

/** GitHub transport for individually encrypted backup records. Called off the UI thread. */
final class GithubBackupRepository {
  private final GithubAccountClient account;
  private final String login;
  private final String repository;

  GithubBackupRepository(GithubAccountClient account,String login,String name)throws IOException{
    if(login==null||login.isEmpty()||name==null||!name.matches("[A-Za-z0-9_.-]+"))
      throw new IOException("备份仓库名称无效");
    this.account=account;this.login=login;
    this.repository="/repos/"+Uri.encode(login)+"/"+Uri.encode(name);
  }

  String baselineIdentity(){return login.toLowerCase(java.util.Locale.ROOT)+"/"+repository;}

  void ensurePrivateRepository(String name)throws IOException{
    JSONObject repo;
    try{repo=account.apiForAccount(login,"GET",repository,null);}
    catch(GithubAccountClient.ApiFailure error){
      if(error.status!=404)throw error;
      try{
        JSONObject request=new JSONObject().put("name",name).put("private",true).put("auto_init",true);
        repo=account.apiForAccount(login,"POST","/user/repos",request);
      }catch(GithubAccountClient.ApiFailure failure){
        if(failure.status==422)throw new IOException("仓库已存在或不可见；请确认仓库私有并退出 GitHub 后重新授权 repo 权限");
        throw failure;
      }catch(JSONException malformed){throw new IOException("无法创建备份仓库请求",malformed);}
    }
    JSONObject owner=repo.optJSONObject("owner");
    if(!repo.optBoolean("private",false)||owner==null||!login.equalsIgnoreCase(owner.optString("login")))
      throw new IOException("备份仓库必须属于当前账号且为私有仓库");
  }

  interface Cancellation {void check()throws IOException;}
  private static final int CHUNK=512*1024;

  /** Walk bounded non-recursive Git trees; new records are sharded twice. */
  List<String> records()throws IOException{return records(null);}
  List<String> records(Cancellation cancellation)throws IOException{return records(cancellation,null);}
  List<String> records(Cancellation cancellation,String block)throws IOException{
    if(block!=null&&!validBlock(block))throw new IOException("备份数据块无效");
    if(cancellation!=null)cancellation.check();JSONObject repo=account.apiForAccount(login,"GET",repository,null);
    JSONObject owner=repo.optJSONObject("owner");
    if(!repo.optBoolean("private",false)||owner==null||!login.equalsIgnoreCase(owner.optString("login")))
      throw new IOException("备份仓库必须属于当前账号且为私有仓库");
    String branch=repo.optString("default_branch","");
    if(branch.isEmpty())throw new IOException("备份仓库尚未初始化");
    JSONArray root=tree(branch,cancellation);String recordsTree="";
    for(int i=0;i<root.length();i++){
      JSONObject entry=root.optJSONObject(i);
      if(entry!=null&&"tree".equals(entry.optString("type"))&&"records".equals(entry.optString("path")))
        recordsTree=entry.optString("sha","");
    }
    List<String> result=new ArrayList<>();
    if(!recordsTree.isEmpty())walk(recordsTree,"",0,result,cancellation,block);
    return result;
  }
  private JSONArray tree(String ref,Cancellation cancellation)throws IOException{
    if(cancellation!=null)cancellation.check();JSONObject response=account.apiForAccount(login,"GET",repository+"/git/trees/"+Uri.encode(ref),null);
    if(response.optBoolean("truncated",false))throw new IOException("备份目录过大，GitHub 无法完整列举");
    JSONArray entries=response.optJSONArray("tree");
    if(entries==null)throw new IOException("备份目录格式无效");return entries;
  }
  private void walk(String ref,String prefix,int depth,List<String> result,Cancellation cancellation,String selected)throws IOException{
    if(depth==1&&walkShardRecursive(ref,prefix,result,cancellation))return;
    JSONArray entries=tree(ref,cancellation);
    for(int i=0;i<entries.length();i++){
      JSONObject entry=entries.optJSONObject(i);if(entry==null)continue;
      String name=entry.optString("path","");String type=entry.optString("type","");
      if("tree".equals(type)&&depth==0&&prefix.isEmpty()&&validBlock(name)){
        if(selected!=null&&!selected.equals(name))continue;
        String child=entry.optString("sha","");if(child.isEmpty())throw new IOException("备份目录缺少标识");
        walk(child,name+"/",0,result,cancellation,selected);
      }else if("tree".equals(type)&&depth<2&&name.matches("[0-9a-f]{2}")){
        String child=entry.optString("sha","");if(child.isEmpty())throw new IOException("备份目录缺少标识");
        walk(child,prefix+name+"/",depth+1,result,cancellation,selected);
      }else if("tree".equals(type)&&depth==2&&name.matches("[0-9a-fA-F-]{36}")){
        String child=entry.optString("sha","");if(child.isEmpty())throw new IOException("备份目录缺少标识");
        walk(child,prefix+name+"/",depth+1,result,cancellation,selected);
      }else if("blob".equals(type)&&depth==3&&name.equals("manifest")){
        result.add(prefix+name);
      }else if("blob".equals(type)&&depth==0&&name.matches("[0-9a-fA-F-]{36}\\.zip")){
        result.add(name);
      }else if("blob".equals(type)&&depth==2&&name.matches("[0-9a-fA-F-]{36}\\.manifest")){
        result.add(prefix+name);
      }
    }
  }
  /** One recursive request per first-level shard; fall back to bounded tree walking. */
  private boolean walkShardRecursive(String ref,String prefix,List<String> result,Cancellation cancellation)throws IOException{
    if(cancellation!=null)cancellation.check();JSONObject response;
    try{response=account.apiForAccount(login,"GET",repository+"/git/trees/"+Uri.encode(ref)+"?recursive=1",null);}
    catch(IOException error){
      if("GitHub 响应数据超出上限".equals(error.getMessage()))return false;
      throw error;
    }
    if(response.optBoolean("truncated",false))return false;
    JSONArray entries=response.optJSONArray("tree");
    if(entries==null)throw new IOException("备份目录格式无效");
    for(int i=0;i<entries.length();i++){
      JSONObject entry=entries.optJSONObject(i);
      if(entry==null||!"blob".equals(entry.optString("type")))continue;
      String path=entry.optString("path","");
      if(path.matches("[0-9a-f]{2}/[0-9a-fA-F-]{36}/manifest")
          ||path.matches("[0-9a-f]{2}/[0-9a-fA-F-]{36}\\.manifest"))result.add(prefix+path);
    }
    return true;
  }  void readToFile(String name,java.io.File destination,Cancellation cancellation)throws IOException{
    if(cancellation!=null)cancellation.check();
    if(name.matches("[0-9a-fA-F-]{36}\\.zip")){
      try(java.io.FileOutputStream output=new java.io.FileOutputStream(destination)){
        output.write(readFile("records/"+name));
      }
      return;
    }
    boolean folderFormat=name.matches("(?:(?:settings|sources|cloud_accounts|sync_tasks)/)?[0-9a-f]{2}/[0-9a-f]{2}/[0-9a-fA-F-]{36}/manifest");
    if(!folderFormat&&!name.matches("[0-9a-f]{2}/[0-9a-f]{2}/[0-9a-fA-F-]{36}\\.manifest"))
      throw new IOException("备份记录名称无效");
    try{
      JSONObject manifest=new JSONObject(new String(readFile("records/"+name),java.nio.charset.StandardCharsets.UTF_8));
      long parts=manifest.getLong("parts"),length=manifest.getLong("bytes");
      if(parts<1||parts>Integer.MAX_VALUE||length<1||parts!=1+(length-1)/CHUNK)
        throw new IOException("备份分块清单无效");
      String stem=folderFormat?name.substring(0,name.length()-"/manifest".length())
          :name.substring(0,name.length()-".manifest".length());
      try(java.io.FileOutputStream output=new java.io.FileOutputStream(destination)){
        for(long i=0;i<parts;i++){
          if(cancellation!=null)cancellation.check();
          String partPath=folderFormat?stem+"/chunks/"+(i/256)+"/"+i+".part":stem+"."+i+".part";
          byte[] part=readFile("records/"+partPath);
          int expected=(int)Math.min((long)CHUNK,length-i*CHUNK);
          if(part.length!=expected)throw new IOException("备份分块缺失或大小不一致");
          output.write(part);
        }
      }
    }catch(JSONException error){throw new IOException("备份分块清单无效",error);}
  }  private byte[] readFile(String path)throws IOException{
    JSONObject entry=account.apiForAccount(login,"GET",repository+"/contents/"+path,null);
    if(!"base64".equals(entry.optString("encoding")))throw new IOException("备份记录过大或格式无效");
    try{return Base64.decode(entry.getString("content"),Base64.DEFAULT);}
    catch(Exception error){throw new IOException("备份记录格式无效",error);}
  }
  void addRecord(String block,String id,java.io.File encrypted,Cancellation cancellation)throws IOException{
    if(!validBlock(block))throw new IOException("备份数据块无效");
    if(!id.matches("[0-9a-fA-F-]{36}")||encrypted==null||!encrypted.isFile()||encrypted.length()<1)
      throw new IOException("备份记录名称无效或内容为空");
    long length=encrypted.length(),parts=1+(length-1)/CHUNK;
    if(parts>Integer.MAX_VALUE)throw new IOException("备份分块数量超过支持范围");
    String stem="records/"+block+"/"+id.substring(0,2)+"/"+id.substring(2,4)+"/"+id;
    try(java.io.FileInputStream input=new java.io.FileInputStream(encrypted)){
      byte[] buffer=new byte[CHUNK];
      for(long i=0;i<parts;i++){
        if(cancellation!=null)cancellation.check();int expected=(int)Math.min((long)CHUNK,length-i*CHUNK),filled=0;
        while(filled<expected){int n=input.read(buffer,filled,expected-filled);if(n<0)throw new IOException("加密备份文件不完整");filled+=n;}
        writeFile(stem+"/chunks/"+(i/256)+"/"+i+".part",filled==buffer.length?buffer:java.util.Arrays.copyOf(buffer,filled));
      }
      if(input.read()!=-1)throw new IOException("加密备份文件大小已变化");
    }
    try{
      byte[] manifest=new JSONObject().put("format",1).put("parts",parts).put("bytes",length)
          .toString().getBytes(java.nio.charset.StandardCharsets.UTF_8);
      if(cancellation!=null)cancellation.check();writeFile(stem+"/manifest",manifest);
    }catch(JSONException error){throw new IOException("备份分块清单创建失败",error);}
  }  private static boolean validBlock(String block){
    return "settings".equals(block)||"sources".equals(block)||"cloud_accounts".equals(block)||"sync_tasks".equals(block);
  }  private void writeFile(String path,byte[] value)throws IOException{
    try{
      JSONObject request=new JSONObject().put("message","Add encrypted backup record")
          .put("content",Base64.encodeToString(value,Base64.NO_WRAP));
      account.apiForAccount(login,"PUT",repository+"/contents/"+path,request);
    }catch(JSONException error){throw new IOException("无法创建备份记录请求",error);}
  }
}