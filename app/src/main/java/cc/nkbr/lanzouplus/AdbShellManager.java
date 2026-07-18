package cc.nkbr.lanzouplus;

import android.content.ComponentName;
import android.content.ContentResolver;
import android.content.Context;
import android.content.ServiceConnection;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.IBinder;
import android.os.ParcelFileDescriptor;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import rikka.shizuku.Shizuku;

/** Tracks Shizuku/Sui authorization and exposes only the constrained APK install operation. */
final class AdbShellManager implements AutoCloseable {
  static final int PERMISSION_REQUEST=7201;
  enum State {NOT_INSTALLED,NOT_RUNNING,UNSUPPORTED,DENIED,NEEDS_PERMISSION,CONNECTING,READY_SHELL,READY_ROOT,ERROR}
  static final class Snapshot {
    final State state;final String title,detail;final int uid;
    Snapshot(State state,String title,String detail,int uid){this.state=state;this.title=title;this.detail=detail;this.uid=uid;}
    boolean ready(){return state==State.READY_SHELL||state==State.READY_ROOT;}
  }
  static final class InstallResult {
    final boolean success;final String message;
    InstallResult(boolean success,String message){this.success=success;this.message=message==null?"":message.trim();}
  }
  interface Listener {void changed(Snapshot snapshot);}

  private final Context context;private final Listener listener;private volatile IAdbShellService service;private volatile Snapshot snapshot;
  private boolean started,binding;
  private final Shizuku.OnBinderReceivedListener binderReceived=this::refresh;
  private final Shizuku.OnBinderDeadListener binderDead=()->{service=null;binding=false;refresh();};
  private final Shizuku.OnRequestPermissionResultListener permissionResult=(requestCode,result)->{if(requestCode==PERMISSION_REQUEST)refresh();};
  private final ServiceConnection connection=new ServiceConnection(){
    @Override public void onServiceConnected(ComponentName name,IBinder binder){service=IAdbShellService.Stub.asInterface(binder);binding=false;refresh();}
    @Override public void onServiceDisconnected(ComponentName name){service=null;binding=false;refresh();}
  };
  private final Shizuku.UserServiceArgs serviceArgs;

  AdbShellManager(Context context,Listener listener){
    this.context=context.getApplicationContext();this.listener=listener;
    serviceArgs=new Shizuku.UserServiceArgs(new ComponentName(context.getPackageName(),AdbShellService.class.getName())).daemon(false).processNameSuffix("adb-shell").debuggable(BuildConfig.DEBUG).version(BuildConfig.VERSION_CODE);
    snapshot=new Snapshot(State.NOT_RUNNING,"正在检测","正在检测 Shizuku / Sui 服务",-1);
  }

  void start(){if(started)return;started=true;Shizuku.addBinderReceivedListenerSticky(binderReceived);Shizuku.addBinderDeadListener(binderDead);Shizuku.addRequestPermissionResultListener(permissionResult);refresh();}
  Snapshot snapshot(){return snapshot;}
  boolean ready(){return snapshot.ready()&&service!=null;}

  void refresh(){
    try{
      if(!Shizuku.pingBinder()){publish(shizukuInstalled()?new Snapshot(State.NOT_RUNNING,"未连接","Shizuku 已安装但服务未运行；可用无线调试、USB 调试或 root 启动",-1):new Snapshot(State.NOT_INSTALLED,"未安装 Shizuku","安装并启动 Shizuku 后可申请 ADB Shell 权限",-1));return;}
      if(Shizuku.isPreV11()){publish(new Snapshot(State.UNSUPPORTED,"版本过旧","当前 Shizuku API 版本不支持 UserService，请升级 Shizuku",-1));return;}
      int permission=Shizuku.checkSelfPermission();
      if(permission!=PackageManager.PERMISSION_GRANTED){boolean denied=Shizuku.shouldShowRequestPermissionRationale();publish(new Snapshot(denied?State.DENIED:State.NEEDS_PERMISSION,denied?"授权已拒绝":"等待授权",denied?"请在 Shizuku 的应用管理中重新允许本应用":"点击申请 Shizuku 的 ADB Shell 权限",-1));return;}
      int uid=Shizuku.getUid();
      if(service==null){publish(new Snapshot(State.CONNECTING,"正在连接 Shell","权限已授予，正在启动隔离安装服务",uid));bind();return;}
      publish(new Snapshot(uid==0?State.READY_ROOT:State.READY_SHELL,uid==0?"Root Shell 已连接":"ADB Shell 已连接",uid==0?"Sui / root 服务可用，可启用静默安装":"Shizuku shell (UID 2000) 可用，可启用静默安装",uid));
    }catch(Throwable error){service=null;binding=false;publish(new Snapshot(State.ERROR,"检测失败",safeMessage(error),-1));}
  }

  boolean requestPermission(){
    try{if(!Shizuku.pingBinder())return false;if(Shizuku.checkSelfPermission()==PackageManager.PERMISSION_GRANTED){refresh();return true;}if(Shizuku.shouldShowRequestPermissionRationale()){refresh();return false;}Shizuku.requestPermission(PERMISSION_REQUEST);return true;}catch(Throwable error){publish(new Snapshot(State.ERROR,"申请失败",safeMessage(error),-1));return false;}
  }

  private void bind(){if(binding||service!=null)return;binding=true;try{Shizuku.bindUserService(serviceArgs,connection);}catch(Throwable error){binding=false;publish(new Snapshot(State.ERROR,"Shell 服务连接失败",safeMessage(error),-1));}}
  private void publish(Snapshot value){snapshot=value;if(listener!=null)listener.changed(value);}
  private boolean shizukuInstalled(){try{context.getPackageManager().getPackageInfo("moe.shizuku.privileged.api",0);return true;}catch(Throwable ignored){return false;}}
  private static String safeMessage(Throwable error){String message=error.getMessage();return message==null||message.trim().isEmpty()?error.getClass().getSimpleName():message.trim();}

  InstallResult install(ContentResolver resolver,Uri uri,long expectedSize){
    IAdbShellService target=service;if(target==null||!snapshot.ready())return new InstallResult(false,"ADB Shell 服务未连接");
    File staged=null;
    try{
      ParcelFileDescriptor source=resolver.openFileDescriptor(uri,"r");if(source==null)return new InstallResult(false,"无法读取安装包");
      long size=source.getStatSize();
      if(size<=0)size=expectedSize;
      if(size<=0){source.close();staged=File.createTempFile("silent-install-",".apk",context.getCacheDir());try(InputStream input=resolver.openInputStream(uri);FileOutputStream output=new FileOutputStream(staged)){if(input==null)throw new java.io.IOException("无法读取安装包");byte[] buffer=new byte[64*1024];for(int count;(count=input.read(buffer))>=0;)if(count>0)output.write(buffer,0,count);}size=staged.length();source=ParcelFileDescriptor.open(staged,ParcelFileDescriptor.MODE_READ_ONLY);}
      String raw;try(ParcelFileDescriptor descriptor=source){raw=target.installApk(descriptor,size);}
      boolean ok=raw!=null&&raw.startsWith("OK\n");String message=raw==null?"Shell 未返回安装结果":raw.replaceFirst("^(?:OK|ERROR)\\n","").trim();return new InstallResult(ok,message);
    }catch(Throwable error){service=null;refresh();return new InstallResult(false,safeMessage(error));}finally{if(staged!=null&&!staged.delete())staged.deleteOnExit();}
  }

  @Override public void close(){if(!started)return;started=false;Shizuku.removeBinderReceivedListener(binderReceived);Shizuku.removeBinderDeadListener(binderDead);Shizuku.removeRequestPermissionResultListener(permissionResult);try{if(Shizuku.pingBinder())Shizuku.unbindUserService(serviceArgs,connection,false);}catch(Throwable ignored){}service=null;binding=false;}
}
