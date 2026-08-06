package cc.nkbr.lanzouplus;

import android.content.Context;
import android.content.SharedPreferences;
import org.json.JSONArray;
import org.json.JSONObject;
import java.util.*;

/** Persistent browser-profile sessions that grow on demand and are retained for reuse. */
final class DirectCookiePool {
  static final int HOURLY_DIRECT_LIMIT=32,PROFILE_ANDROID=0,PROFILE_DESKTOP=1;
  static final long WINDOW_MS=60L*60L*1000L;
  private static final String PREFS="direct-cookie-pool-v1",LEGACY_ENTRIES="entries",ENTRY="entry:";
  private final SharedPreferences prefs;
  private final Object lock=new Object();
  private final ArrayList<Entry> entries=new ArrayList<>();

  DirectCookiePool(Context context){prefs=context.getApplicationContext().getSharedPreferences(PREFS,Context.MODE_PRIVATE);load();}

  Lease acquire(Set<String> excluded,long now){
    synchronized(lock){
      Entry selected=null;
      for(Entry entry:entries){rollWindow(entry,now);if(excluded!=null&&excluded.contains(entry.id)||entry.count>=HOURLY_DIRECT_LIMIT||entry.cooldownUntil>now)continue;if(selected==null||entry.count<selected.count||entry.count==selected.count&&entry.lastUsed<selected.lastUsed)selected=entry;}
      if(selected==null)selected=newEntry(now);
      selected.count++;selected.lastUsed=now;saveEntryLocked(selected);return new Lease(selected.id,selected.profile,copyJar(selected.jar));
    }
  }

  void finish(Lease lease,Map<String,LinkedHashMap<String,String>> jar,boolean success,boolean rateLimited,long retryAfterMs,long now){
    if(lease==null)return;synchronized(lock){Entry entry=find(lease.id);if(entry==null)return;rollWindow(entry,now);if(jar!=null&&!jar.isEmpty())entry.jar=copyJar(jar);entry.lastUsed=now;if(success){entry.failures=0;entry.cooldownUntil=0;}else{entry.failures=Math.min(12,entry.failures+1);long delay=Math.max(rateLimited?15000:2000,retryAfterMs);delay=Math.min(2*60*1000L,delay*(1L<<Math.min(4,entry.failures-1)));entry.cooldownUntil=Math.max(entry.cooldownUntil,now+delay);}saveEntryLocked(entry);}
  }

  int size(){synchronized(lock){return entries.size();}}

  private Entry newEntry(long now){Entry entry=new Entry();entry.id=UUID.randomUUID().toString();int missing=missingProfile();entry.profile=missing>=0?missing:entries.size()%2;entry.windowStarted=now;entries.add(entry);saveEntryLocked(entry);return entry;}
  private Entry find(String id){for(Entry entry:entries)if(entry.id.equals(id))return entry;return null;}
  private int missingProfile(){boolean android=false,desktop=false;for(Entry entry:entries)if(entry.profile==PROFILE_DESKTOP)desktop=true;else android=true;return!android?PROFILE_ANDROID:!desktop?PROFILE_DESKTOP:-1;}
  private static void rollWindow(Entry entry,long now){if(entry.windowStarted<=0||now<entry.windowStarted||now-entry.windowStarted>=WINDOW_MS){entry.windowStarted=now;entry.count=0;}}

  private void load(){
    synchronized(lock){
      LinkedHashMap<String,Entry> loaded=new LinkedHashMap<>();
      try{for(Map.Entry<String,?> value:prefs.getAll().entrySet())if(value.getKey().startsWith(ENTRY)&&value.getValue() instanceof String){Entry entry=entry(new JSONObject((String)value.getValue()));if(entry!=null)loaded.put(entry.id,entry);}}catch(Exception ignored){}
      try{JSONArray legacy=new JSONArray(prefs.getString(LEGACY_ENTRIES,"[]"));for(int i=0;i<legacy.length();i++){Entry entry=entry(legacy.optJSONObject(i));if(entry!=null&&!loaded.containsKey(entry.id)){loaded.put(entry.id,entry);saveEntryLocked(entry);}}if(legacy.length()>0)prefs.edit().remove(LEGACY_ENTRIES).apply();}catch(Exception ignored){}
      entries.addAll(loaded.values());
    }
  }

  private static Entry entry(JSONObject raw){if(raw==null)return null;Entry entry=new Entry();entry.id=raw.optString("id");if(entry.id.isEmpty())return null;entry.profile=raw.optInt("profile",PROFILE_ANDROID)==PROFILE_DESKTOP?PROFILE_DESKTOP:PROFILE_ANDROID;entry.windowStarted=raw.optLong("window");entry.count=Math.max(0,raw.optInt("count"));entry.lastUsed=raw.optLong("used");entry.cooldownUntil=raw.optLong("cooldown");entry.failures=Math.max(0,raw.optInt("failures"));JSONObject hosts=raw.optJSONObject("cookies");if(hosts!=null)for(Iterator<String> keys=hosts.keys();keys.hasNext();){String host=keys.next();JSONObject cookies=hosts.optJSONObject(host);if(cookies==null)continue;LinkedHashMap<String,String> bucket=new LinkedHashMap<>();for(Iterator<String> names=cookies.keys();names.hasNext();){String name=names.next(),value=cookies.optString(name);if(!name.isEmpty()&&!value.isEmpty())bucket.put(name,value);}if(!bucket.isEmpty())entry.jar.put(host,bucket);}return entry;}
  private static JSONObject json(Entry entry)throws Exception{JSONObject hosts=new JSONObject();for(Map.Entry<String,LinkedHashMap<String,String>> host:entry.jar.entrySet()){JSONObject cookies=new JSONObject();for(Map.Entry<String,String> cookie:host.getValue().entrySet())cookies.put(cookie.getKey(),cookie.getValue());hosts.put(host.getKey(),cookies);}return new JSONObject().put("id",entry.id).put("profile",entry.profile).put("window",entry.windowStarted).put("count",entry.count).put("used",entry.lastUsed).put("cooldown",entry.cooldownUntil).put("failures",entry.failures).put("cookies",hosts);}
  private void saveEntryLocked(Entry entry){try{prefs.edit().putString(ENTRY+entry.id,json(entry).toString()).apply();}catch(Exception ignored){}}
  private static Map<String,LinkedHashMap<String,String>> copyJar(Map<String,? extends Map<String,String>> source){Map<String,LinkedHashMap<String,String>> copy=new HashMap<>();if(source!=null)for(Map.Entry<String,? extends Map<String,String>> host:source.entrySet())copy.put(host.getKey(),new LinkedHashMap<>(host.getValue()));return copy;}

  static final class Lease{final String id;final int profile;final Map<String,LinkedHashMap<String,String>> jar;Lease(String id,int profile,Map<String,LinkedHashMap<String,String>> jar){this.id=id;this.profile=profile;this.jar=jar;}}
  private static final class Entry{String id="";long windowStarted,lastUsed,cooldownUntil;int count,failures,profile;Map<String,LinkedHashMap<String,String>> jar=new HashMap<>();}
}
