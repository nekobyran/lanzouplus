package cc.nkbr.lanzouplus;

import android.content.Context;
import android.content.SharedPreferences;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.json.JSONArray;
import org.json.JSONObject;

/** Converts supported app state into independent encrypted records and merges restores. */
final class GithubBackupState {
  private final Context context;
  private final LanzouCore core;
  private final OrdinaryCloudClient ordinary;
  private final PremiumCloudClient premium;

  GithubBackupState(Context context,LanzouCore core){
    this.context=context.getApplicationContext();this.core=core;
    CloudFilesClient cloud=CloudFilesClient.shared(context);
    ordinary=cloud.ordinaryClient();premium=cloud.premiumClient();
  }

  /** Audited app-owned preference stores; caches and device-bound stores are excluded. */
  private static final String[] RESTORABLE_SETTINGS={"search_settings-v1","download_settings-v1",
      "source_settings-v1","adb_install_settings-v1","cloud_management","cloud_accounts_ui",
      "cache_cleanup","directory_parse_recovery_v147","v1810_index_scope","source_categories",
      "home_shortcuts_v1","update_channel_v187","market","download_destination","MainActivity"};
  enum Block {
    SETTINGS("settings"), SOURCES("sources"), ACCOUNTS("cloud_accounts"), TASKS("sync_tasks");
    final String kind;
    Block(String kind){this.kind=kind;}
  }
  List<JSONObject> snapshot()throws Exception{
    List<JSONObject> records=new ArrayList<>();
    for(Block block:Block.values())records.addAll(snapshotBlock(block));
    return records;
  }
  List<JSONObject> snapshotBlock(Block block)throws Exception{
    List<JSONObject> records=new ArrayList<>();long now=System.currentTimeMillis();
    switch(block){
      case SETTINGS:
        java.io.File directory=new java.io.File(context.getApplicationInfo().dataDir,"shared_prefs");
        java.io.File[] files=directory.listFiles();
        if(files==null&&directory.exists())throw new IOException("无法读取应用设置目录");
        Set<String> names=new java.util.LinkedHashSet<>(BackupPreferences.knownNames());
        Collections.addAll(names,RESTORABLE_SETTINGS);
        if(files!=null)for(java.io.File file:files){
          String filename=file.getName();if(filename.endsWith(".xml"))names.add(filename.substring(0,filename.length()-4));
        }
        for(String name:names){
          if(!portablePreferences(name))continue;
          Map<String,?> all=context.getSharedPreferences(name,Context.MODE_PRIVATE).getAll();
          for(Map.Entry<String,?> item:all.entrySet()){
            if("download_destination".equals(name)&&"tree".equals(item.getKey()))continue;
            records.add(record("setting",name+"/"+item.getKey(),encodeSetting(item.getValue()).toString(),now));
          }
        }
        break;
      case SOURCES:
        for(Models.Source source:core.sources()){
          String id=LanzouCore.sourceId(source);
          if(!id.isEmpty())records.add(record("source",id,core.exportSourceRules(Collections.singleton(id)),now));
        }
        break;
      case ACCOUNTS:
        addAccounts(records,"ordinary",ordinary.backupAccounts(),now);
        addAccounts(records,"premium",premium.backupAccounts(),now);
        break;
      case TASKS:
        JSONArray tasks=new CloudSyncTasks(context).backupTasks();
        for(int i=0;i<tasks.length();i++){
          JSONObject task=tasks.getJSONObject(i);records.add(record("sync-task",task.getString("id"),task.toString(),now));
        }
        break;
      default:throw new IOException("不支持的备份数据块");
    }
    return records;
  }
  private static void addAccounts(List<JSONObject> records,String kind,JSONObject vault,long at)throws Exception{
    String current=vault.optString("current","");
    if(!current.isEmpty())records.add(record(kind+"-current","selection",current,at));
    JSONArray accounts=vault.getJSONArray("accounts");
    for(int i=0;i<accounts.length();i++){
      JSONObject account=accounts.getJSONObject(i);
      records.add(record(kind,account.getString("loginName"),account.toString(),at));
    }
  }
  private static JSONObject record(String kind,String key,String value,long at)throws Exception{
    return new JSONObject().put("kind",kind).put("key",key).put("value",value).put("at",at);
  }
  private static JSONObject encodeSetting(Object value)throws Exception{
    JSONObject out=new JSONObject();
    if(value instanceof String)out.put("type","string").put("value",value);
    else if(value instanceof Boolean)out.put("type","boolean").put("value",value);
    else if(value instanceof Integer)out.put("type","int").put("value",value);
    else if(value instanceof Long)out.put("type","long").put("value",value);
    else if(value instanceof Float)out.put("type","float").put("value",value);
    else if(value instanceof Set){List<String> sorted=new ArrayList<>((Set<String>)value);
      Collections.sort(sorted);out.put("type","set").put("value",new JSONArray(sorted));}
    else throw new IOException("不支持的设置类型");
    return out;
  }

  /** Merge newest first; local entries and hidden sources remain untouched. */
  int restore(List<JSONObject> remote)throws Exception{
    for(JSONObject item:remote)if("setting".equals(item.optString("kind"))){
      BackupPreferences.requireSingleProcess(context);break;
    }
    List<JSONObject> ordered=new ArrayList<>(remote);
    Map<String,String> seen=new java.util.HashMap<>();Set<String> ambiguous=new HashSet<>();int conflicts=0;
    for(JSONObject item:ordered){String id=item.getString("kind")+":"+item.getString("key");
      String previous=seen.putIfAbsent(id,item.getString("value"));
      if(previous!=null&&!previous.equals(item.getString("value")))ambiguous.add(id);
    }
    conflicts+=ambiguous.size();seen.clear();
    boolean ordinaryEmpty=ordinary.accountNames().isEmpty(),premiumEmpty=premium.accountNames().isEmpty();
    String ordinaryCurrent="",premiumCurrent="";
    CloudSyncTasks syncTasks=new CloudSyncTasks(context);
    Set<String> presentSources=new HashSet<>();
    for(Models.Source source:core.sources())presentSources.add(LanzouCore.sourceId(source));
    JSONArray hidden=new JSONArray(context.getSharedPreferences("user-sources-v1",Context.MODE_PRIVATE)
        .getString("removed","[]"));
    for(int i=0;i<hidden.length();i++)presentSources.add(hidden.getString(i));
    for(JSONObject item:ordered){
      String kind=item.getString("kind"),key=item.getString("key"),value=item.getString("value");
      String identity=kind+":"+key;
      if(ambiguous.contains(identity)||seen.containsKey(identity))continue;
      seen.put(identity,value);
      if("ordinary-current".equals(kind)){ordinaryCurrent=value;continue;}
      if("premium-current".equals(kind)){premiumCurrent=value;continue;}
      if("setting".equals(kind)){if(!restoreSetting(key,new JSONObject(value)))conflicts++;}
      else if("source".equals(kind)){
        if(presentSources.contains(key)){
          boolean equal=false;
          for(Models.Source source:core.sources())if(key.equals(LanzouCore.sourceId(source))){
            equal=core.exportSourceRules(Collections.singleton(key)).equals(value);break;
          }
          if(!equal)conflicts++;continue;
        }
        if(!core.importBackupSourceIfAbsent(key,value))conflicts++;else presentSources.add(key);
      }else if("sync-task".equals(kind)){
        JSONObject task=new JSONObject(value);
        if(!key.equals(task.getString("id")))throw new IOException("备份任务标识不一致");
        if(!syncTasks.restoreBackupTask(task))conflicts++;      }else if("ordinary".equals(kind)||"premium".equals(kind)){
        JSONObject account=new JSONObject(value);
        JSONObject vault=new JSONObject().put("accounts",new JSONArray().put(account)).put("current",key);
        conflicts+="ordinary".equals(kind)?ordinary.mergeBackupAccounts(vault):premium.mergeBackupAccounts(vault);
      }else throw new IOException("备份记录类型不受支持");
    }
    if(ordinaryEmpty&&!ordinaryCurrent.isEmpty()&&!ordinary.selectAccount(ordinaryCurrent))conflicts++;
    if(premiumEmpty&&!premiumCurrent.isEmpty()&&!premium.selectAccount(premiumCurrent))conflicts++;
    return conflicts;
  }
  private static boolean portablePreferences(String name){
    if(!name.matches("[A-Za-z0-9_.-]+"))return false;
    String lower=name.toLowerCase(java.util.Locale.ROOT);
    if(lower.equals("cache_cleanup"))return true;
    if(lower.contains("cache")||lower.contains("cookie")||lower.contains("history")
        ||lower.contains("session")||lower.contains("github_account")||lower.contains("github_backup_baseline")||lower.contains("github_release_sync_v1")||lower.contains("route-hints")
        ||lower.contains("index_failures")||lower.contains("index-failures")
        ||lower.contains("index_schedule")||lower.contains("directory-index")
        ||lower.contains("web-source-meta")||lower.contains("publisher-avatar")
        ||lower.contains("composite-members")||lower.contains("base_origin_dialog")
        ||lower.contains("direct_links")||lower.contains("onboarding"))return false;
    return !name.equals("user-sources-v1")&&!name.equals("web-browser-v2")
        &&!name.equals("feedback")&&!name.equals("cloud-image-files-v1");
  }
  private boolean allowedSetting(String name,String key){
    if(!portablePreferences(name)||key.isEmpty()||("download_destination".equals(name)&&"tree".equals(key)))return false;
    for(String allowed:RESTORABLE_SETTINGS)if(allowed.equals(name))return true;
    return false;
  }
  private static Object decodeSetting(JSONObject value)throws Exception{
    String type=value.getString("type");
    switch(type){
      case "string":return value.getString("value");
      case "boolean":return value.getBoolean("value");
      case "int":return value.getInt("value");
      case "long":return value.getLong("value");
      case "float":return (float)value.getDouble("value");
      case "set":JSONArray values=value.getJSONArray("value");Set<String> set=new HashSet<>();
        for(int i=0;i<values.length();i++)set.add(values.getString(i));return set;
      default:throw new IOException("设置记录类型无效");
    }
  }
  /** Merge under the same per-store lock used by application writes. */
  private boolean restoreSetting(String identity,JSONObject value)throws Exception{
    int slash=identity.indexOf('/');if(slash<=0)throw new IOException("设置记录无效");
    String name=identity.substring(0,slash),key=identity.substring(slash+1);
    if(!allowedSetting(name,key))return false;
    return BackupPreferences.insertIfAbsent(context.getSharedPreferences(name,Context.MODE_PRIVATE),key,decodeSetting(value));
  }
  boolean canApplySelectedSetting(String identity){
    if(identity==null)return false;int slash=identity.indexOf('/');if(slash<=0)return false;
    if(!allowedSetting(identity.substring(0,slash),identity.substring(slash+1)))return false;
    try{BackupPreferences.requireSingleProcess(context);return true;}catch(IOException error){return false;}
  }  /** Applies one explicitly chosen setting only if it still matches the local preview. */
  void applySelectedSetting(JSONObject expectedLocal,JSONObject selected)throws IOException{
    try{
      BackupPreferences.requireSingleProcess(context);
      String kind=expectedLocal.getString("kind"),identity=expectedLocal.getString("key");
      if(!"setting".equals(kind)||!kind.equals(selected.getString("kind"))
          ||!identity.equals(selected.getString("key")))throw new IOException("所选设置与冲突项目不一致");
      int slash=identity.indexOf('/');if(slash<=0)throw new IOException("设置记录无效");
      String name=identity.substring(0,slash),key=identity.substring(slash+1);
      if(!allowedSetting(name,key))throw new IOException("该设置尚未完成并发保护审计");
      Object before=decodeSetting(new JSONObject(expectedLocal.getString("value")));
      Object after=decodeSetting(new JSONObject(selected.getString("value")));
      BackupPreferences.replaceIfUnchanged(context.getSharedPreferences(name,Context.MODE_PRIVATE),key,before,after);
    }catch(IOException error){throw error;}
    catch(Exception error){throw new IOException("设置冲突数据无效",error);}
  }
}