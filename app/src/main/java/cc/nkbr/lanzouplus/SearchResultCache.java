package cc.nkbr.lanzouplus;

import android.content.Context;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import org.json.JSONArray;
import org.json.JSONObject;

/**
 * Temporary copy of the last full-source search. A crash or a system kill no longer throws away
 * minutes of waiting: the same query is seeded from this snapshot before the live search resumes.
 */
final class SearchResultCache {
  static final int MAX_ITEMS=6000;
  static final long MAX_AGE_MS=3L*24L*60L*60L*1000L;
  private static final String FILE_NAME="last-search.json";
  private static final int VERSION=1;

  static final class Snapshot{
    String query="";
    long savedAt;
    int total;
    final List<Models.Item> items=new ArrayList<>();
    boolean usable(){return savedAt>0L&&!query.isEmpty()&&!items.isEmpty()&&System.currentTimeMillis()-savedAt<MAX_AGE_MS;}
    String ageText(long now){
      long delta=Math.max(0L,now-savedAt);
      if(delta<60_000L)return "刚刚";
      if(delta<3_600_000L)return (delta/60_000L)+" 分钟前";
      if(delta<86_400_000L)return (delta/3_600_000L)+" 小时前";
      return (delta/86_400_000L)+" 天前";
    }
  }

  private SearchResultCache(){}

  private static File file(Context context){
    return new File(context.getFilesDir(),FILE_NAME);
  }

  static Snapshot load(Context context){
    File source=file(context);
    if(!source.isFile())return null;
    try{
      JSONObject root=new JSONObject(readAll(source));
      long savedAt=root.optLong("saved_at",0L);
      String query=root.optString("query","");
      JSONArray rows=root.optJSONArray("items");
      if(savedAt<=0L||query.isEmpty()||rows==null||rows.length()==0)return null;
      if(System.currentTimeMillis()-savedAt>=MAX_AGE_MS)return null;
      Snapshot snapshot=new Snapshot();
      snapshot.savedAt=savedAt;
      snapshot.query=query;
      snapshot.total=root.optInt("total",rows.length());
      for(int i=0;i<rows.length();i++){
        JSONObject row=rows.optJSONObject(i);
        if(row==null)continue;
        Models.Item item=fromJson(row);
        if(item!=null)snapshot.items.add(item);
      }
      return snapshot.items.isEmpty()?null:snapshot;
    }catch(Exception ignored){
      return null;
    }
  }

  static boolean save(Context context,String query,List<Models.Item> items,int total){
    if(query==null||query.trim().isEmpty()||items==null||items.isEmpty())return false;
    File target=file(context),temp=new File(target.getPath()+".tmp");
    try{
      JSONObject root=new JSONObject();
      root.put("version",VERSION);
      root.put("query",query);
      root.put("saved_at",System.currentTimeMillis());
      root.put("total",Math.max(total,items.size()));
      JSONArray rows=new JSONArray();
      int limit=Math.min(MAX_ITEMS,items.size());
      for(int i=0;i<limit;i++){
        Models.Item item=items.get(i);
        if(item==null||item.url==null||item.url.isEmpty())continue;
        rows.put(toJson(item));
      }
      root.put("items",rows);
      byte[] payload=root.toString().getBytes("UTF-8");
      try(FileOutputStream output=new FileOutputStream(temp)){
        output.write(payload);
        output.getFD().sync();
      }
      if(target.exists()&&!target.delete())return false;
      return temp.renameTo(target);
    }catch(Exception ignored){
      try{if(temp.exists())temp.delete();}catch(Exception ignoredDelete){}
      return false;
    }
  }

  static void clear(Context context){
    try{
      File target=file(context);
      if(target.exists())target.delete();
    }catch(Exception ignored){}
  }

  private static JSONObject toJson(Models.Item item)throws Exception{
    JSONObject row=new JSONObject();
    row.put("title",safe(item.title));
    row.put("url",safe(item.url));
    row.put("share",safe(item.shareUrl));
    row.put("size",safe(item.size));
    row.put("time",safe(item.time));
    row.put("icon",safe(item.iconUrl));
    row.put("source",safe(item.source));
    row.put("password",safe(item.password));
    row.put("description",safe(item.description));
    row.put("folder_id",safe(item.folderId));
    row.put("source_id",safe(item.sourceId));
    row.put("error",safe(item.error));
    row.put("folder",item.folder);
    row.put("source_entry",item.sourceEntry);
    row.put("index_hit",item.indexHit);
    if(!item.categories.isEmpty()){
      JSONArray categories=new JSONArray();
      for(String category:item.categories)if(category!=null&&!category.isEmpty())categories.put(category);
      if(categories.length()>0)row.put("categories",categories);
    }
    return row;
  }

  private static Models.Item fromJson(JSONObject row){
    String url=row.optString("url","");
    if(url.isEmpty())return null;
    Models.Item item=new Models.Item();
    item.title=row.optString("title","");
    item.url=url;
    item.shareUrl=row.optString("share","");
    item.size=row.optString("size","");
    item.time=row.optString("time","");
    item.iconUrl=row.optString("icon","");
    item.source=row.optString("source","");
    item.password=row.optString("password","");
    item.description=row.optString("description","");
    item.folderId=row.optString("folder_id","");
    item.sourceId=row.optString("source_id","");
    item.error=row.optString("error","");
    item.folder=row.optBoolean("folder",false);
    item.sourceEntry=row.optBoolean("source_entry",false);
    item.indexHit=row.optBoolean("index_hit",false);
    JSONArray categories=row.optJSONArray("categories");
    if(categories!=null)for(int i=0;i<categories.length();i++){
      String category=categories.optString(i,"").trim();
      if(!category.isEmpty())item.categories.add(category);
    }
    return item;
  }

  private static String safe(String value){return value==null?"":value;}

  private static String readAll(File source)throws Exception{
    try(InputStream input=new FileInputStream(source)){
      ByteArrayOutputStream output=new ByteArrayOutputStream(Math.max(256,(int)Math.min(source.length(),1<<20)));
      byte[] buffer=new byte[8192];
      int read;
      while((read=input.read(buffer))>0){
        output.write(buffer,0,read);
        if(output.size()>(16<<20))throw new IllegalStateException("搜索结果缓存过大");
      }
      return output.toString("UTF-8");
    }
  }
}
