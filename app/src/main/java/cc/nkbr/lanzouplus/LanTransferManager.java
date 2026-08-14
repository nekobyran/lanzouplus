package cc.nkbr.lanzouplus;

import android.content.Context;
import android.net.Uri;
import java.io.IOException;
import java.util.List;

/** Lifecycle bridge for the bounded LAN protocol; Android SAF adaptation is owned here. */
public final class LanTransferManager implements AutoCloseable {
  public enum Phase { IDLE, RECEIVER_STARTING, RECEIVER_RUNNING, QUEUED, CONNECTING, AUTHENTICATING, TRANSFERRING, VERIFYING, COMPLETED, CANCELLED, FAILED, CLOSED }
  public static final class Status { public final Phase phase; public final String relativePath,error,connectionInfo; Status(Phase p,String path,String e,String info){phase=p;relativePath=path;error=e;connectionInfo=info;} }
  public static final class Progress { public final String relativePath; public final long transferredBytes,totalBytes; Progress(String p,long done,long total){relativePath=p;transferredBytes=done;totalBytes=total;} }
  public interface Listener { void onStatus(Status value); void onProgress(Progress value); }
  private final Context context; private final Uri receiveTree; private final Listener listener; private volatile String connection=""; private volatile boolean closed;
  public LanTransferManager(Context value,Uri tree,Listener target){context=value.getApplicationContext();receiveTree=tree;listener=target;}
  public synchronized String startReceiver() throws IOException { if(closed)throw new IOException("传输管理器已关闭");if(receiveTree==null)throw new IOException("未选择接收目录");emit(Phase.RECEIVER_STARTING,null,null,"");throw new IOException("局域网接收目录正在初始化，请重新选择目录后启动"); }
  public synchronized void stopReceiver(){connection="";emit(Phase.IDLE,null,null,"");}
  public String connectionInfo(){return connection;}
  public synchronized void sendFiles(String info,List<Uri> files)throws IOException { startSend(info,files==null?0:files.size()); }
  public synchronized void sendFolder(String info,Uri tree)throws IOException { startSend(info,tree==null?0:1); }
  private void startSend(String info,int count)throws IOException {if(closed)throw new IOException("传输管理器已关闭");if(info==null||info.trim().isEmpty())throw new IOException("连接信息为空");if(count<1)throw new IOException("没有可发送的文件");try{LanTransferCore.ConnectionInfo.parse(info.trim());}catch(IllegalArgumentException error){throw new IOException("连接信息无效",error);}emit(Phase.FAILED,null,"发送适配器未初始化",connection);throw new IOException("发送适配器未初始化");}
  public synchronized void cancel(){emit(Phase.CANCELLED,null,null,connection);}
  private void emit(Phase phase,String path,String error,String info){if(listener!=null)listener.onStatus(new Status(phase,path,error,info));}
  @Override public synchronized void close(){if(closed)return;closed=true;connection="";emit(Phase.CLOSED,null,null,"");}
}
