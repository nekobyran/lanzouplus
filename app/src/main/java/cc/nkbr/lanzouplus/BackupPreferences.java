package cc.nkbr.lanzouplus;

import android.app.Application;
import android.content.Context;
import android.content.SharedPreferences;
import android.content.pm.ComponentInfo;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.content.pm.ProviderInfo;
import java.io.IOException;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/** Serializes this process's preference commits with backup's insert-if-absent operation. */
final class BackupPreferences implements SharedPreferences {
  private static final ConcurrentHashMap<String,Object> LOCKS=new ConcurrentHashMap<>();
  private final SharedPreferences delegate;
  private final Object lock;
  private final Map<OnSharedPreferenceChangeListener,OnSharedPreferenceChangeListener> listeners=
      new java.util.WeakHashMap<>();
  private BackupPreferences(String name,SharedPreferences delegate){
    this.delegate=delegate;this.lock=LOCKS.computeIfAbsent(name,key->new Object());
  }
  static SharedPreferences wrap(String name,SharedPreferences preferences){
    if(preferences instanceof BackupPreferences)return preferences;
    return new BackupPreferences(name,preferences);
  }
  static Set<String> knownNames(){return new java.util.HashSet<>(LOCKS.keySet());}
  /** Context variants retain the same locking boundary as their originating component. */
  static Context wrapContext(Context context){
    return new android.content.ContextWrapper(context){
      @Override public SharedPreferences getSharedPreferences(String name,int mode){
        return BackupPreferences.wrap(name,super.getSharedPreferences(name,mode));
      }
      @Override public Context createDeviceProtectedStorageContext(){
        return BackupPreferences.wrapContext(super.createDeviceProtectedStorageContext());
      }
      @Override public Context createConfigurationContext(android.content.res.Configuration configuration){
        return BackupPreferences.wrapContext(super.createConfigurationContext(configuration));
      }
    };
  }

  /** Valid only for this app's default-process components and guarded preferences. */
  static void requireSingleProcess(Context context)throws IOException{
    Context application=context.getApplicationContext();
    if(!(application instanceof BackupPreferenceApplication))throw new IOException("设置恢复缺少并发保护");
    try{
      PackageManager pm=application.getPackageManager();
      PackageInfo info=pm.getPackageInfo(application.getPackageName(),PackageManager.GET_ACTIVITIES
          |PackageManager.GET_SERVICES|PackageManager.GET_RECEIVERS|PackageManager.GET_PROVIDERS
          |PackageManager.MATCH_DISABLED_COMPONENTS);
      String main=info.applicationInfo.processName;
      if(main==null||main.isEmpty())main=application.getPackageName();
      String running;
      if(android.os.Build.VERSION.SDK_INT>=28)running=Application.getProcessName();
      else{
        try(java.io.FileInputStream input=new java.io.FileInputStream("/proc/self/cmdline")){
          java.io.ByteArrayOutputStream out=new java.io.ByteArrayOutputStream();int ch;
          while((ch=input.read())>0)out.write(ch);
          running=out.toString("UTF-8");
        }
      }
      if(!main.equals(running))throw new IOException("设置恢复只能在应用主进程执行");
      checkProcesses(info.activities,main);checkProcesses(info.services,main);
      checkProcesses(info.receivers,main);checkProcesses(info.providers,main);
      requireKnown(info.activities,"cc.nkbr.lanzouplus.MainActivity","cc.nkbr.lanzouplus.LanzouWebActivity");
      requireKnown(info.services,"cc.nkbr.lanzouplus.CloudSyncJobService");
      requireKnown(info.receivers);
      requireKnown(info.providers,"cc.nkbr.lanzouplus.DownloadFileProvider","rikka.shizuku.ShizukuProvider");
      if(info.providers!=null)for(ProviderInfo provider:info.providers)
        if(provider.multiprocess)throw new IOException("应用包含多进程入口，无法安全恢复设置");
    }catch(PackageManager.NameNotFoundException error){throw new IOException("无法确认应用进程配置",error);}
  }
  private static void checkProcesses(ComponentInfo[] components,String main)throws IOException{
    if(components==null)return;
    for(ComponentInfo component:components){
      String process=component.processName;
      if(process!=null&&!process.isEmpty()&&!main.equals(process))
        throw new IOException("应用包含多进程入口，无法安全恢复设置");
    }
  }
  private static void requireKnown(ComponentInfo[] components,String... allowed)throws IOException{
    if(components==null)return;
    for(ComponentInfo component:components){
      boolean found=false;for(String name:allowed)if(name.equals(component.name)){found=true;break;}
      if(!found)throw new IOException("应用组件入口尚未完成设置并发保护审计");
    }
  }  /** Existing values always win, including writes that complete before this operation. */
  static boolean insertIfAbsent(SharedPreferences preferences,String key,Object value)throws IOException{
    if(!(preferences instanceof BackupPreferences))throw new IOException("设置恢复缺少并发保护");
    BackupPreferences guarded=(BackupPreferences)preferences;
    synchronized(guarded.lock){
      if(guarded.delegate.contains(key))return java.util.Objects.equals(guarded.delegate.getAll().get(key),value);
      save(guarded.delegate,key,value);return true;
    }
  }
  /** User-selected overwrite only if the currently stored value still equals the preview. */
  static void replaceIfUnchanged(SharedPreferences preferences,String key,Object expected,Object selected)throws IOException{
    if(!(preferences instanceof BackupPreferences))throw new IOException("设置恢复缺少并发保护");
    BackupPreferences guarded=(BackupPreferences)preferences;
    synchronized(guarded.lock){
      if(!guarded.delegate.contains(key)||!java.util.Objects.equals(guarded.delegate.getAll().get(key),expected))
        throw new IOException("本机设置已变化，请重新查看冲突");
      if(!java.util.Objects.equals(expected,selected))save(guarded.delegate,key,selected);
    }
  }
  private static void save(SharedPreferences preferences,String key,Object value)throws IOException{
    Editor editor=preferences.edit();
    if(value instanceof String)editor.putString(key,(String)value);
    else if(value instanceof Boolean)editor.putBoolean(key,(Boolean)value);
    else if(value instanceof Integer)editor.putInt(key,(Integer)value);
    else if(value instanceof Long)editor.putLong(key,(Long)value);
    else if(value instanceof Float)editor.putFloat(key,(Float)value);
    else if(value instanceof Set)editor.putStringSet(key,(Set<String>)value);
    else throw new IOException("设置记录类型无效");
    if(!editor.commit())throw new IOException("设置恢复保存失败");
  }  @Override public Map<String,?> getAll(){return delegate.getAll();}
  @Override public String getString(String key,String def){return delegate.getString(key,def);}
  @Override public Set<String> getStringSet(String key,Set<String> def){return delegate.getStringSet(key,def);}
  @Override public int getInt(String key,int def){return delegate.getInt(key,def);}
  @Override public long getLong(String key,long def){return delegate.getLong(key,def);}
  @Override public float getFloat(String key,float def){return delegate.getFloat(key,def);}
  @Override public boolean getBoolean(String key,boolean def){return delegate.getBoolean(key,def);}
  @Override public boolean contains(String key){return delegate.contains(key);}
  @Override public Editor edit(){return new GuardedEditor(delegate.edit());}
  @Override public void registerOnSharedPreferenceChangeListener(OnSharedPreferenceChangeListener listener){
    synchronized(listeners){
      if(listeners.containsKey(listener))return;
      java.lang.ref.WeakReference<OnSharedPreferenceChangeListener> reference=new java.lang.ref.WeakReference<>(listener);
      OnSharedPreferenceChangeListener proxy=(ignored,key)->{
        OnSharedPreferenceChangeListener target=reference.get();
        if(target!=null)target.onSharedPreferenceChanged(BackupPreferences.this,key);
      };
      listeners.put(listener,proxy);delegate.registerOnSharedPreferenceChangeListener(proxy);
    }
  }
  @Override public void unregisterOnSharedPreferenceChangeListener(OnSharedPreferenceChangeListener listener){
    synchronized(listeners){OnSharedPreferenceChangeListener proxy=listeners.remove(listener);
      if(proxy!=null)delegate.unregisterOnSharedPreferenceChangeListener(proxy);}
  }
  private final class GuardedEditor implements Editor{
    private final Editor editor;
    GuardedEditor(Editor editor){this.editor=editor;}
    @Override public Editor putString(String k,String v){editor.putString(k,v);return this;}
    @Override public Editor putStringSet(String k,Set<String> v){editor.putStringSet(k,v);return this;}
    @Override public Editor putInt(String k,int v){editor.putInt(k,v);return this;}
    @Override public Editor putLong(String k,long v){editor.putLong(k,v);return this;}
    @Override public Editor putFloat(String k,float v){editor.putFloat(k,v);return this;}
    @Override public Editor putBoolean(String k,boolean v){editor.putBoolean(k,v);return this;}
    @Override public Editor remove(String k){editor.remove(k);return this;}
    @Override public Editor clear(){editor.clear();return this;}
    @Override public boolean commit(){synchronized(lock){return editor.commit();}}
    @Override public void apply(){synchronized(lock){editor.apply();}}
  }
}
