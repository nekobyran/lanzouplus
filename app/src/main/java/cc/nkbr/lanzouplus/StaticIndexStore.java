package cc.nkbr.lanzouplus;

import android.content.Context;
import android.content.SharedPreferences;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.zip.GZIPInputStream;

/**
 * Storage + registry for user supplied static search indexes.
 * Files: filesDir/static-index/&lt;id&gt;.json, registry: SharedPreferences "static-index-v1".
 * Entry objects reuse the search-index-v1 shape (see {@link LanzouCore#indexJsonToItem}).
 */
final class StaticIndexStore {
  static final String PREFS="static-index-v1",KEY_CANDIDATES="candidates",KEY_SELECTED="selected",KEY_ENABLED="enabled";
  private static final String DIR_NAME="static-index";
  private static final long MAX_IMPORT_BYTES=256L*1024L*1024L;

  private final Context context;

  StaticIndexStore(Context context){this.context=context;}

  File dir(){
    File value=new File(context.getFilesDir(),DIR_NAME);
    if(!value.isDirectory())value.mkdirs();
    return value;
  }

  File fileFor(String id){
    return new File(dir(),safeId(id)+".json");
  }

  private SharedPreferences prefs(){
    return context.getSharedPreferences(PREFS,Context.MODE_PRIVATE);
  }

  boolean enabled(){return prefs().getBoolean(KEY_ENABLED,false);}

  void setEnabled(boolean value){prefs().edit().putBoolean(KEY_ENABLED,value).apply();}

  String selectedId(){String value=prefs().getString(KEY_SELECTED,"");return value==null?"":value.trim();}

  void select(String id){
    String value=id==null?"":id.trim();
    prefs().edit().putString(KEY_SELECTED,value).apply();
  }

  List<StaticIndexInfo> candidates(){
    List<StaticIndexInfo> out=new ArrayList<>();
    String raw=prefs().getString(KEY_CANDIDATES,"");
    if(raw==null||raw.trim().isEmpty())return out;
    try{
      JSONArray array=new JSONArray(raw);
      for(int i=0;i<array.length();i++){
        StaticIndexInfo info=StaticIndexInfo.fromJson(array.optJSONObject(i));
        if(info==null)continue;
        File file=fileFor(info.id);
        if(!file.isFile())continue;
        long sizeBytes=info.sizeBytes>0L?info.sizeBytes:file.length();
        long rawBytes=info.rawBytes>0L?info.rawBytes:file.length();
        out.add(info.path.equals(file.getAbsolutePath())&&info.sizeBytes>0L&&info.rawBytes>0L?info
          :new StaticIndexInfo(info.id,info.name,info.builtAt,info.entryCount,info.sourceCount,file.getAbsolutePath(),sizeBytes,rawBytes,info.coveredSourceCount));
      }
    }catch(Exception ignored){}
    return out;
  }

  StaticIndexInfo candidate(String id){
    if(id==null||id.trim().isEmpty())return null;
    for(StaticIndexInfo info:candidates())if(info.id.equals(id.trim()))return info;
    return null;
  }

  private void saveCandidates(List<StaticIndexInfo> values){
    JSONArray array=new JSONArray();
    for(StaticIndexInfo info:values)array.put(info.toJson());
    prefs().edit().putString(KEY_CANDIDATES,array.toString()).apply();
  }

  StaticIndexInfo importIndex(InputStream in,String fallbackName){
    if(in==null)throw new IllegalArgumentException("索引文件为空");
    File temp=null,payload=null;
    try{
      temp=File.createTempFile("static-index-import",".tmp",dir());
      long copied=copyTo(in,temp);
      long displayBytes=copied;
      payload=temp;
      if(isGzip(temp)){
        payload=File.createTempFile("static-index-raw",".tmp",dir());
        inflate(temp,payload);
      }
      long rawBytes=payload.length();
      Parsed parsed=parseFile(payload);
      if(parsed.items.isEmpty())throw new IOException("索引条目为空");
      String id=newId();
      File target=fileFor(id);
      if(!payload.renameTo(target)){
        copyFile(payload,target);
        payload.delete();
      }
      if(payload!=temp)temp.delete();
      payload=null;
      temp=null;
      String title=parsed.title.trim();
      if(title.isEmpty()&&fallbackName!=null){
        title=fallbackName.trim();
        int slash=Math.max(title.lastIndexOf('/'),title.lastIndexOf('\\'));
        if(slash>=0)title=title.substring(slash+1);
        if(title.toLowerCase().endsWith(".json"))title=title.substring(0,title.length()-5);
        if(title.toLowerCase().endsWith(".lzindex"))title=title.substring(0,title.length()-8);
      }
      if(title.isEmpty())title="静态索引";
      StaticIndexInfo info=new StaticIndexInfo(id,title,parsed.builtAt,parsed.items.size(),parsed.sourceCount,target.getAbsolutePath(),displayBytes,rawBytes,parsed.coveredSources);
      List<StaticIndexInfo> values=candidates();
      values.add(info);
      saveCandidates(values);
      return info;
    }catch(IOException|JSONException error){
      if(temp!=null)temp.delete();
      if(payload!=null&&payload!=temp)payload.delete();
      throw new IllegalStateException(error.getMessage()==null?"索引导入失败":error.getMessage(),error);
    }
  }

  StaticIndexInfo installBundled(String assetName){
    String name=assetName==null?"":assetName.trim();
    if(name.isEmpty())throw new IllegalArgumentException("资源名称为空");
    try(InputStream in=openAsset(name)){
      return importIndex(in,name);
    }catch(IOException error){
      throw new IllegalStateException("内置索引不可用: "+error.getMessage(),error);
    }
  }

  private InputStream openAsset(String name)throws IOException{
    String base=name;
    int slash=Math.max(base.lastIndexOf('/'),base.lastIndexOf('\\'));
    if(slash>=0)base=base.substring(slash+1);
    String[] candidates={name,"index/"+base,name+".gz","index/"+base+".gz"};
    IOException last=null;
    for(String candidate:candidates){
      try{
        return context.getAssets().open(candidate);
      }catch(IOException missing){
        last=missing;
      }
    }
    throw last==null?new IOException("内置索引不存在"):last;
  }

  void delete(String id){
    String value=id==null?"":id.trim();
    if(value.isEmpty())return;
    File file=fileFor(value);
    if(file.isFile())file.delete();
    List<StaticIndexInfo> values=candidates();
    List<StaticIndexInfo> kept=new ArrayList<>();
    for(StaticIndexInfo info:values)if(!info.id.equals(value))kept.add(info);
    saveCandidates(kept);
    if(selectedId().equals(value))select("");
  }

  /** Writes a freshly built index object to a new id file and registers it as a candidate. */
  StaticIndexInfo saveIndex(String title,JSONObject root,int totalSources,int coveredSources){
    if(root==null)throw new IllegalArgumentException("索引内容为空");
    String id=newId();
    File target=fileFor(id);
    String text=root.toString();
    try{
      File temp=new File(target.getPath()+".tmp");
      try(FileOutputStream out=new FileOutputStream(temp)){
        out.write(text.getBytes(StandardCharsets.UTF_8));
        out.getFD().sync();
      }
      if(!temp.renameTo(target)){
        copyFile(temp,target);
        temp.delete();
      }
    }catch(IOException error){
      throw new IllegalStateException("索引保存失败: "+error.getMessage(),error);
    }
    long builtAt=root.optLong("builtAt",System.currentTimeMillis());
    String name=title==null||title.trim().isEmpty()?root.optString("title","静态索引"):title.trim();
    StaticIndexInfo info=new StaticIndexInfo(id,name,builtAt,root.optInt("entryCount",0),Math.max(0,totalSources),
      target.getAbsolutePath(),target.length(),Math.max(0,coveredSources));
    List<StaticIndexInfo> values=candidates();
    values.add(info);
    saveCandidates(values);
    return info;
  }

  int entryCount(String id){
    StaticIndexInfo info=candidate(id);
    return info==null?0:info.entryCount;
  }

  /** Entries of one index file; tolerates the wrapper object, {"entries":[...]} and a plain array. */
  static List<Models.Item> readEntries(File file)throws IOException{
    try{
      return parseFile(file).items;
    }catch(JSONException error){
      throw new IOException(error.getMessage()==null?"索引格式错误":error.getMessage(),error);
    }
  }

  private static final class Parsed{
    final String title;
    final long builtAt;
    final int sourceCount,coveredSources;
    final List<Models.Item> items;
    Parsed(String title,long builtAt,int sourceCount,int coveredSources,List<Models.Item> items){this.title=title;this.builtAt=builtAt;this.sourceCount=sourceCount;this.coveredSources=coveredSources;this.items=items;}
  }

  /**
   * Streams the file: only the small metadata header is buffered, entries are parsed one object
   * at a time, so a 40 MB full-source index never becomes one giant String/JSONArray.
   */
  private static Parsed parseFile(File file)throws IOException,JSONException{
    BufferedReader reader=new BufferedReader(new InputStreamReader(new FileInputStream(file),StandardCharsets.UTF_8),65536);
    StringBuilder header=new StringBuilder(4096);
    boolean pureArray=false;
    try{
      int first=skipWhitespace(reader);
      if(first<0)throw new JSONException("索引文件为空");
      if(first=='[')pureArray=true;
      else if(first=='{')header.append('{');
      else throw new JSONException("索引格式错误");
      List<Models.Item> items=new ArrayList<>();
      if(pureArray){
        readEntriesArray(reader,items);
      }else{
        readTopLevelMembers(reader,header,items);
      }
      if(!pureArray&&!items.isEmpty()&&header.indexOf("\"entries\"")>=0){
        int marker=header.lastIndexOf("\"entries\"");
        header.setLength(marker);
      }
      String title="";
      long builtAt=file.lastModified();
      int declaredSources=0;
      int declaredCovered=-1;
      if(!pureArray){
        int end=header.length()-1;
        while(end>=0&&(Character.isWhitespace(header.charAt(end))||header.charAt(end)==','))end--;
        String metaText=header.substring(0,end+1)+"}";
        try{
          JSONObject meta=new JSONObject(metaText);
          title=meta.optString("title","");
          long value=meta.optLong("builtAt",0L);
          if(value>0L)builtAt=value;
          declaredSources=meta.optInt("sourceCount",0);
          JSONArray coverage=meta.optJSONArray("coverage");
          if(coverage!=null&&coverage.length()>0){
            int covered=0;
            for(int i=0;i<coverage.length();i++){
              JSONObject row=coverage.optJSONObject(i);
              if(row!=null&&row.optInt("entryCount",0)>0)covered++;
            }
            declaredCovered=covered;
          }
        }catch(JSONException ignored){}
      }
      int sources=declaredSources>0?declaredSources:countSources(items);
      int covered=declaredCovered>=0?declaredCovered:countSources(items);
      return new Parsed(title,builtAt,sources,Math.min(covered,sources>0?sources:covered),items);
    }finally{
      try{reader.close();}catch(IOException ignored){}
    }
  }

  private static int skipWhitespace(BufferedReader reader)throws IOException{
    for(int c; (c=reader.read())>=0; )if(!Character.isWhitespace((char)c))return c;
    return -1;
  }

  /** Copies every top-level member into {@code header} until the entries array starts. */
  private static void readTopLevelMembers(BufferedReader reader,StringBuilder header,List<Models.Item> items)throws IOException,JSONException{
    boolean inString=false,escape=false,expectKey=false;
    int stringStart=-1;
    int depth=1;
    for(int c;(c=reader.read())>=0;){
      char ch=(char)c;
      header.append(ch);
      if(header.length()>16*1024*1024)throw new JSONException("索引头部异常");
      if(inString){
        if(escape)escape=false;
        else if(ch=='\\')escape=true;
        else if(ch=='"'){
          inString=false;
          String token=header.substring(stringStart+1,header.length()-1);
          if(expectKey&&"entries".equals(token)&&nextNonSpaceIsArray(reader)){
            header.setLength(stringStart-1);
            readEntriesArray(reader,items);
            return;
          }
        }
        continue;
      }
      if(ch=='"'){
        inString=true;escape=false;
        stringStart=header.length()-1;
        int probe=header.length()-2;
        while(probe>=0&&Character.isWhitespace(header.charAt(probe)))probe--;
        expectKey=probe<0||header.charAt(probe)=='{'||header.charAt(probe)==',';
        continue;
      }
      if(ch=='{'||ch=='['){depth++;continue;}
      if(ch=='}'||ch==']'){
        depth--;
        if(depth<=0)return;
        continue;
      }
      if(ch==',')expectKey=true;
    }
  }

  /** Consumes ': [' when present (already positioned right after the entries key). */
  private static boolean nextNonSpaceIsArray(BufferedReader reader)throws IOException{
    int c;
    do{c=reader.read();}while(c>=0&&Character.isWhitespace((char)c));
    if(c!=':')return false;
    do{c=reader.read();}while(c>=0&&Character.isWhitespace((char)c));
    return c=='[';
  }

  /** Streams balanced objects out of the entries array; the opening '[' is already consumed. */
  private static void readEntriesArray(BufferedReader reader,List<Models.Item> items)throws IOException{
    boolean inString=false,escape=false;
    int objectDepth=0;
    StringBuilder current=null;
    for(int c;(c=reader.read())>=0;){
      char ch=(char)c;
      if(inString){
        if(current!=null)current.append(ch);
        if(escape)escape=false;
        else if(ch=='\\')escape=true;
        else if(ch=='"')inString=false;
        continue;
      }
      if(ch=='"'){
        inString=true;escape=false;
        if(objectDepth>0&&current!=null)current.append(ch);
        continue;
      }
      if(ch=='{'){
        if(objectDepth==0)current=new StringBuilder(256);
        objectDepth++;
        if(current!=null)current.append(ch);
        continue;
      }
      if(ch=='}'){
        objectDepth--;
        if(current!=null)current.append(ch);
        if(objectDepth<=0){
          objectDepth=0;
          if(current!=null){
            try{items.add(LanzouCore.indexJsonToItem(new JSONObject(current.toString())));}catch(JSONException ignored){}
            current=null;
          }
        }
        continue;
      }
      if(objectDepth==0&&ch==']')return;
      if(objectDepth>0&&current!=null)current.append(ch);
    }
  }

  private static int countSources(List<Models.Item> items){
    Set<String> values=new HashSet<>();
    for(Models.Item item:items){
      if(item==null)continue;
      String source=item.source==null?"":item.source.trim();
      if(source.isEmpty()&&item.sourceId!=null)source=item.sourceId.trim();
      if(!source.isEmpty())values.add(source);
    }
    return values.size();
  }

  private String newId(){
    long now=System.currentTimeMillis();
    String base="si-"+now;
    String id=base;
    for(int i=1;fileFor(id).exists();i++)id=base+"-"+i;
    return id;
  }

  private static String safeId(String id){
    String value=id==null?"":id.trim();
    StringBuilder out=new StringBuilder(value.length());
    for(int i=0;i<value.length();i++){
      char c=value.charAt(i);
      if(c>='a'&&c<='z'||c>='A'&&c<='Z'||c>='0'&&c<='9'||c=='-'||c=='_'||c=='.')out.append(c);
    }
    return out.length()==0?"index":out.toString();
  }

  private static long copyTo(InputStream in,File target)throws IOException{
    long total=0L;
    try(FileOutputStream out=new FileOutputStream(target)){
      byte[] buffer=new byte[16384];
      for(int n;(n=in.read(buffer))>0;){
        total+=n;
        if(total>MAX_IMPORT_BYTES)throw new IOException("索引文件过大");
        out.write(buffer,0,n);
      }
    }
    return total;
  }

  private static boolean isGzip(File file)throws IOException{
    try(FileInputStream in=new FileInputStream(file)){
      int first=in.read(),second=in.read();
      return first==0x1f&&second==0x8b;
    }
  }

  private static void inflate(File gzip,File target)throws IOException{
    try(InputStream in=new GZIPInputStream(new FileInputStream(gzip));FileOutputStream out=new FileOutputStream(target)){
      byte[] buffer=new byte[16384];
      long total=0L;
      for(int n;(n=in.read(buffer))>0;){
        total+=n;
        if(total>MAX_IMPORT_BYTES)throw new IOException("索引解压后过大");
        out.write(buffer,0,n);
      }
    }
  }

  private static void copyFile(File from,File to)throws IOException{
    try(FileInputStream in=new FileInputStream(from);FileOutputStream out=new FileOutputStream(to)){
      byte[] buffer=new byte[16384];
      for(int n;(n=in.read(buffer))>0;)out.write(buffer,0,n);
    }
  }
}
