package cc.nkbr.lanzouplus;

import android.content.Context;
import android.content.SharedPreferences;
import org.json.JSONArray;
import org.json.JSONObject;
import java.util.*;

/** Persistent, bounded browser-profile sessions for Lanzou direct-link exchanges. */
final class DirectCookiePool {
  static final int HOURLY_DIRECT_LIMIT=32,MAX_ENTRIES=8,PROFILE_ANDROID=0,PROFILE_DESKTOP=1;
  static final long WINDOW_MS=60L*60L*1000L;
  private static final String PREFS="direct-cookie-pool-v1",ENTRIES="entries";
  private final SharedPreferences prefs;
  private final Object lock=new Object();
  private final ArrayList<Entry> entries=new ArrayList<>();

  DirectCookiePool(Context context){prefs=context.getApplicationContext().getSharedPreferences(PREFS,Context.MODE_PRIVATE);load();}

  Lease acquire(Set<String> excluded,long now){
    synchronized(lock){
      Entry selected=null;
      for(Entry entry:entries){rollWindow(entry,now);if(excluded!=null&&excluded.contains(entry.id)||entry.count>=HOURLY_DIRECT_LIMIT||entry.cooldownUntil>now)continue;if(selected==null||entry.count<selected.count||entry.count==selected.count&&entry.lastUsed<selected.lastUsed)selected=entry;}
      if(selected==null&&entries.size()<MAX_ENTRIES){int missing=missingProfile();boolean exhausted=false;for(Entry entry:entries)exhausted|=entry.count>=HOURLY_DIRECT_LIMIT;if(missing>=0||entries.isEmpty()||exhausted){selected=new Entry();selected.id=UUID.randomUUID().toString();selected.profile=missing>=0?missing:entries.size()%2;selected.windowStarted=now;entries.add(selected);}}
      if(selected==null){long ready=Long.MAX_VALUE;Entry waiting=null;for(Entry entry:entries)if(excluded==null||!excluded.contains(entry.id)){long at=entry.count>=HOURLY_DIRECT_LIMIT?entry.windowStarted+WINDOW_MS:entry.cooldownUntil;if(at<ready){ready=at;waiting=entry;}}if(waiting==null){waiting=entries.get(0);ready=Math.max(now+1000,waiting.cooldownUntil);}return new Lease(waiting.id,waiting.profile,copyJar(waiting.jar),Math.max(1000,ready-now));}
      selected.count++;selected.lastUsed=now;saveLocked();return new Lease(selected.id,selected.profile,copyJar(selected.jar),0);
    }
  }

  void finish(Lease lease,Map<String,LinkedHashMap<String,String>> jar,boolean success,boolean rateLimited,long retryAfterMs,long now){
    if(lease==null)return;synchronized(lock){Entry entry=find(lease.id);if(entry==null)return;rollWindow(entry,now);if(jar!=null&&!jar.isEmpty())entry.jar=copyJar(jar);entry.lastUsed=now;if(success){entry.failures=0;entry.cooldownUntil=0;}else{entry.failures=Math.min(12,entry.failures+1);long delay=Math.max(rateLimited?15000:2000,retryAfterMs);delay=Math.min(2*60*1000L,delay*(1L<<Math.min(4,entry.failures-1)));entry.cooldownUntil=Math.max(entry.cooldownUntil,now+delay);if(rateLimited)for(Entry other:entries)if(other.profile==entry.profile)other.cooldownUntil=Math.max(other.cooldownUntil,entry.cooldownUntil);}saveLocked();}
  }

  int size(){synchronized(lock){return entries.size();}}

  private Entry find(String id){for(Entry entry:entries)if(entry.id.equals(id))return entry;return null;}
  private int missingProfile(){boolean android=false,desktop=false;for(Entry entry:entries)if(entry.profile==PROFILE_DESKTOP)desktop=true;else android=true;return!android?PROFILE_ANDROID:!desktop?PROFILE_DESKTOP:-1;}
  private static void rollWindow(Entry entry,long now){if(entry.windowStarted<=0||now<entry.windowStarted||now-entry.windowStarted>=WINDOW_MS){entry.windowStarted=now;entry.count=0;}}
  private void load(){synchronized(lock){try{JSONArray values=new JSONArray(prefs.getString(ENTRIES,"[]"));for(int i=0;i<values.length()&&entries.size()<MAX_ENTRIES;i++){JSONObject raw=values.optJSONObject(i);if(raw==null)continue;Entry entry=new Entry();entry.id=raw.optString("id");if(entry.id.isEmpty())continue;entry.profile=raw.optInt("profile",PROFILE_ANDROID)==PROFILE_DESKTOP?PROFILE_DESKTOP:PROFILE_ANDROID;entry.windowStarted=raw.optLong("window");entry.count=Math.max(0,raw.optInt("count"));entry.lastUsed=raw.optLong("used");entry.cooldownUntil=raw.optLong("cooldown");entry.failures=Math.max(0,raw.optInt("failures"));JSONObject hosts=raw.optJSONObject("cookies");if(hosts!=null)for(Iterator<String> keys=hosts.keys();keys.hasNext();){String host=keys.next();JSONObject cookies=hosts.optJSONObject(host);if(cookies==null)continue;LinkedHashMap<String,String> bucket=new LinkedHashMap<>();for(Iterator<String> names=cookies.keys();names.hasNext();){String name=names.next(),value=cookies.optString(name);if(!name.isEmpty()&&!value.isEmpty())bucket.put(name,value);}if(!bucket.isEmpty())entry.jar.put(host,bucket);}entries.add(entry);}}catch(Exception ignored){entries.clear();}}}
  private void saveLocked(){try{JSONArray values=new JSONArray();for(Entry entry:entries){JSONObject hosts=new JSONObject();for(Map.Entry<String,LinkedHashMap<String,String>> host:entry.jar.entrySet()){JSONObject cookies=new JSONObject();for(Map.Entry<String,String> cookie:host.getValue().entrySet())cookies.put(cookie.getKey(),cookie.getValue());hosts.put(host.getKey(),cookies);}values.put(new JSONObject().put("id",entry.id).put("profile",entry.profile).put("window",entry.windowStarted).put("count",entry.count).put("used",entry.lastUsed).put("cooldown",entry.cooldownUntil).put("failures",entry.failures).put("cookies",hosts));}prefs.edit().putString(ENTRIES,values.toString()).apply();}catch(Exception ignored){}}
  private static Map<String,LinkedHashMap<String,String>> copyJar(Map<String,? extends Map<String,String>> source){Map<String,LinkedHashMap<String,String>> copy=new HashMap<>();if(source!=null)for(Map.Entry<String,? extends Map<String,String>> host:source.entrySet())copy.put(host.getKey(),new LinkedHashMap<>(host.getValue()));return copy;}

  static final class Lease{final String id;final int profile;final Map<String,LinkedHashMap<String,String>> jar;final long waitMs;Lease(String id,int profile,Map<String,LinkedHashMap<String,String>> jar,long waitMs){this.id=id;this.profile=profile;this.jar=jar;this.waitMs=waitMs;}}
  private static final class Entry{String id="";long windowStarted,lastUsed,cooldownUntil;int count,failures,profile;Map<String,LinkedHashMap<String,String>> jar=new HashMap<>();}
}
