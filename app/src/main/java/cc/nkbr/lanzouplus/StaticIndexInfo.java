package cc.nkbr.lanzouplus;

import org.json.JSONException;
import org.json.JSONObject;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/** One imported static search index candidate; its entries live in the JSON file at {@link #path}. */
final class StaticIndexInfo {
  final String id,name,path;
  final long builtAt;
  final int entryCount,sourceCount,coveredSourceCount;
  /** Explicit names used by the market/build UI: covered = sources that produced entries. */
  final int coveredSources,totalSources;
  /** Download/asset package size (matches the shipped .gz when installed from assets). */
  final long sizeBytes;
  /** Size of the installed plain JSON file. */
  final long rawBytes;

  StaticIndexInfo(String id,String name,long builtAt,int entryCount,int sourceCount,String path){
    this(id,name,builtAt,entryCount,sourceCount,path,0L,0L,sourceCount);
  }

  StaticIndexInfo(String id,String name,long builtAt,int entryCount,int sourceCount,String path,long sizeBytes,int coveredSourceCount){
    this(id,name,builtAt,entryCount,sourceCount,path,sizeBytes,sizeBytes,coveredSourceCount);
  }

  StaticIndexInfo(String id,String name,long builtAt,int entryCount,int sourceCount,String path,long sizeBytes,long rawBytes,int coveredSourceCount){
    this.id=id==null?"":id;
    this.name=name==null?"":name;
    this.builtAt=builtAt;
    this.entryCount=Math.max(0,entryCount);
    this.sourceCount=Math.max(0,sourceCount);
    this.coveredSourceCount=Math.max(0,Math.min(this.sourceCount<=0?coveredSourceCount:this.sourceCount,coveredSourceCount));
    this.sizeBytes=Math.max(0L,sizeBytes);
    this.rawBytes=Math.max(0L,rawBytes);
    this.path=path==null?"":path;
    this.coveredSources=this.coveredSourceCount;
    this.totalSources=this.sourceCount;
  }

  JSONObject toJson(){
    JSONObject out=new JSONObject();
    try{
      out.put("id",id).put("name",name).put("builtAt",builtAt).put("entryCount",entryCount).put("sourceCount",sourceCount)
        .put("path",path).put("sizeBytes",sizeBytes).put("rawBytes",rawBytes).put("coveredSourceCount",coveredSourceCount);
    }catch(JSONException ignored){}
    return out;
  }

  static StaticIndexInfo fromJson(JSONObject value){
    if(value==null)return null;
    String id=value.optString("id","").trim();
    if(id.isEmpty())return null;
    int sources=value.optInt("sourceCount",0);
    int covered=value.has("coveredSourceCount")?value.optInt("coveredSourceCount",sources):sources;
    long sizeBytes=value.optLong("sizeBytes",0L);
    long rawBytes=value.optLong("rawBytes",sizeBytes);
    return new StaticIndexInfo(id,value.optString("name",""),value.optLong("builtAt",0L),value.optInt("entryCount",0),sources,value.optString("path",""),sizeBytes,rawBytes,covered);
  }

  /** Sources declared by the index that produced no entries at build time. */
  int missingSourceCount(){return Math.max(0,sourceCount-coveredSourceCount);}

  boolean coversAllSources(){return sourceCount>0&&missingSourceCount()==0;}

  String coverageText(){return coveredSourceCount+"/"+sourceCount+" 源";}

  String sizeText(){
    long bytes=sizeBytes;
    if(bytes<=0L)return "";
    if(bytes<1024L)return bytes+" B";
    if(bytes<1024L*1024L)return String.format(Locale.CHINA,"%.1f KB",bytes/1024.0);
    return String.format(Locale.CHINA,"%.1f MB",bytes/(1024.0*1024.0));
  }

  /** Human readable build date used by the market/settings list. */
  String builtAtText(){
    if(builtAt<=0L)return "";
    return new SimpleDateFormat("yyyy-MM-dd HH:mm",Locale.CHINA).format(new Date(builtAt));
  }
}
