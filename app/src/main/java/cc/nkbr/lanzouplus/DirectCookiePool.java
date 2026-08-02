package cc.nkbr.lanzouplus;

import android.content.Context;
import android.content.SharedPreferences;
import org.json.JSONArray;
import org.json.JSONObject;
import java.util.*;

/** Persistent anonymous-cookie rotation for Lanzou direct-link exchanges. */
final class DirectCookiePool {
  static final int HOURLY_DIRECT_LIMIT=40;
  static final long WINDOW_MS=60L*60L*1000L;
  private static final String PREFS="direct-cookie-pool-v1",ENTRIES="entries";
  private final SharedPreferences prefs;
  private final Object lock=new Object();
  private final ArrayList<Entry> entries=new ArrayList<>();

  DirectCookiePool(Context context){prefs=context.getApplicationContext().getSharedPreferences(PREFS,Context.MODE_PRIVATE);load();}

  Lease acquire(Set<String> excluded,long now){
    synchronized(lock){
      Entry selected=null;
      for(Entry entry:entries){rollWindow(entry,now);if(excluded!=null&&excluded.contains(entry.id)||entry.count>=HOURLY_DIRECT_LIMIT)continue;if(selected==null||entry.count<selected.count||entry.count==selected.count&&entry.lastUsed<selected.lastUsed)selected=entry;}
      if(selected==null){selected=new Entry();selected.id=UUID.randomUUID().toString();selected.windowStarted=now;entries.add(selected);}
      selected.count++;selected.lastUsed=now;saveLocked();return new Lease(selected.id,copyJar(selected.jar));
    }
  }

  void finish(Lease lease,Map<String,LinkedHashMap<String,String>> jar,boolean success,long now){
    if(lease==null)return;synchronized(lock){Entry entry=find(lease.id);if(entry==null)return;rollWindow(entry,now);if(jar!=null&&!jar.isEmpty())entry.jar=copyJar(jar);entry.lastUsed=now;if(!success)entry.count=HOURLY_DIRECT_LIMIT;saveLocked();}
  }

  int size(){synchronized(lock){return entries.size();}}

  private Entry find(String id){for(Entry entry:entries)if(entry.id.equals(id))return entry;return null;}
  private static void rollWindow(Entry entry,long now){if(entry.windowStarted<=0||now<entry.windowStarted||now-entry.windowStarted>=WINDOW_MS){entry.windowStarted=now;entry.count=0;}}
  private void load(){synchronized(lock){try{JSONArray values=new JSONArray(prefs.getString(ENTRIES,"[]"));for(int i=0;i<values.length();i++){JSONObject raw=values.optJSONObject(i);if(raw==null)continue;Entry entry=new Entry();entry.id=raw.optString("id");if(entry.id.isEmpty())continue;entry.windowStarted=raw.optLong("window");entry.count=Math.max(0,raw.optInt("count"));entry.lastUsed=raw.optLong("used");JSONObject hosts=raw.optJSONObject("cookies");if(hosts!=null)for(Iterator<String> keys=hosts.keys();keys.hasNext();){String host=keys.next();JSONObject cookies=hosts.optJSONObject(host);if(cookies==null)continue;LinkedHashMap<String,String> bucket=new LinkedHashMap<>();for(Iterator<String> names=cookies.keys();names.hasNext();){String name=names.next(),value=cookies.optString(name);if(!name.isEmpty()&&!value.isEmpty())bucket.put(name,value);}if(!bucket.isEmpty())entry.jar.put(host,bucket);}entries.add(entry);}}catch(Exception ignored){entries.clear();}}}
  private void saveLocked(){try{JSONArray values=new JSONArray();for(Entry entry:entries){JSONObject hosts=new JSONObject();for(Map.Entry<String,LinkedHashMap<String,String>> host:entry.jar.entrySet()){JSONObject cookies=new JSONObject();for(Map.Entry<String,String> cookie:host.getValue().entrySet())cookies.put(cookie.getKey(),cookie.getValue());hosts.put(host.getKey(),cookies);}values.put(new JSONObject().put("id",entry.id).put("window",entry.windowStarted).put("count",entry.count).put("used",entry.lastUsed).put("cookies",hosts));}prefs.edit().putString(ENTRIES,values.toString()).apply();}catch(Exception ignored){}}
  private static Map<String,LinkedHashMap<String,String>> copyJar(Map<String,? extends Map<String,String>> source){Map<String,LinkedHashMap<String,String>> copy=new HashMap<>();if(source!=null)for(Map.Entry<String,? extends Map<String,String>> host:source.entrySet())copy.put(host.getKey(),new LinkedHashMap<>(host.getValue()));return copy;}

  static final class Lease{final String id;final Map<String,LinkedHashMap<String,String>> jar;Lease(String id,Map<String,LinkedHashMap<String,String>> jar){this.id=id;this.jar=jar;}}
  private static final class Entry{String id="";long windowStarted,lastUsed;int count;Map<String,LinkedHashMap<String,String>> jar=new HashMap<>();}
}
