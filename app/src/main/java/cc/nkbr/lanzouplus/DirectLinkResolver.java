package cc.nkbr.lanzouplus;

import android.content.*;
import java.util.*;
import java.util.concurrent.*;

/** Unbounded direct-link scheduling with process-wide cache and URL single-flight. */
final class DirectLinkResolver implements AutoCloseable {
  interface Callback { void resolved(String directUrl,long resolvedAt,boolean cached); void failed(String error); }
  interface Ticket { boolean cancel(); }
  interface Clock { long now(); }
  static final long TTL_MS=60*60*1000L;
  private static final long WORKER_STACK_BYTES=262144L;
  private static final String PREFS="direct_links",DIRECT="d:",TIME="t:";
  private final LanzouCore core;
  private final SharedPreferences prefs;
  private final Clock clock;
  private final Object lock=new Object();
  private final ConcurrentHashMap<String,Request> inflight=new ConcurrentHashMap<>();
  private final ThreadPoolExecutor executor;
  private final ScheduledThreadPoolExecutor retries=new ScheduledThreadPoolExecutor(1,r->{Thread t=new Thread(r,"lanzou-resolve-retry");t.setDaemon(true);return t;});
  private volatile boolean closed;

  DirectLinkResolver(Context context,LanzouCore core){this(context,core,System::currentTimeMillis);}
  DirectLinkResolver(Context context,LanzouCore core,Clock clock){
    this.core=core;this.clock=clock;this.prefs=context.getApplicationContext().getSharedPreferences(PREFS,Context.MODE_PRIVATE);
    executor=new ThreadPoolExecutor(0,Integer.MAX_VALUE,60L,TimeUnit.SECONDS,new SynchronousQueue<>(),r->{Thread t=new Thread(null,r,"lanzou-resolve",WORKER_STACK_BYTES);t.setDaemon(true);return t;});
    executor.allowCoreThreadTimeOut(true);cleanupExpired();
  }

  void prewarm(String shareUrl){resolve(shareUrl,false,null);}
  void prewarm(String shareUrl,Callback callback){resolve(shareUrl,false,callback);}
  void prewarmAll(Collection<String> shareUrls){if(shareUrls!=null)for(String url:shareUrls)prewarm(url);}
  boolean hasFresh(String shareUrl){return cached(shareUrl)!=null;}
  void remember(String shareUrl,String directUrl,long resolvedAt){String share=shareUrl==null?"":shareUrl.trim(),direct=directUrl==null?"":directUrl.trim();long age=clock.now()-resolvedAt;if(!share.isEmpty()&&!direct.isEmpty()&&resolvedAt>0&&age>=0&&age<TTL_MS)prefs.edit().putString(DIRECT+share,direct).putLong(TIME+share,resolvedAt).apply();}

  Ticket resolve(String shareUrl,boolean confirmed,Callback callback){
    String url=shareUrl==null?"":shareUrl.trim();if(url.isEmpty()){if(callback!=null)callback.failed("缺少蓝奏云链接");return()->false;}
    if(closed){if(callback!=null)callback.failed("直链解析已取消");return()->false;}
    Cache hit=cached(url);if(hit!=null){if(callback!=null)callback.resolved(hit.url,hit.at,true);return()->false;}
    boolean cancelled=false;
    synchronized(lock){
      if(closed)cancelled=true;
      else{
        Request request=inflight.get(url);
        if(request!=null){if(callback!=null)request.callbacks.add(callback);if(confirmed)request.confirmed=true;Request joined=request;return callback==null?()->false:()->cancel(joined,callback);}
        hit=cached(url);if(hit==null){request=new Request(url,confirmed);if(callback!=null)request.callbacks.add(callback);inflight.put(url,request);submit(request);Request started=request;return callback==null?()->false:()->cancel(started,callback);}
      }
    }
    if(callback!=null)if(cancelled)callback.failed("直链解析已取消");else callback.resolved(hit.url,hit.at,true);
    return()->false;
  }

  private void submit(Request request){try{executor.execute(request);}catch(RejectedExecutionException rejected){finished(request,null,0,"直链解析已取消");}}
  private boolean cancel(Request request,Callback callback){synchronized(lock){if(request.done||!request.callbacks.remove(callback))return false;if(request.callbacks.isEmpty()&&!request.running){request.done=true;inflight.remove(request.url,request);}return true;}}

  void invalidate(String shareUrl,String directUrl){String url=shareUrl==null?"":shareUrl.trim();if(url.isEmpty())return;String stored=prefs.getString(DIRECT+url,"");if(directUrl==null||directUrl.isEmpty()||directUrl.equals(stored))prefs.edit().remove(DIRECT+url).remove(TIME+url).apply();}

  private Cache cached(String url){long at=prefs.getLong(TIME+url,0),age=clock.now()-at;String direct=prefs.getString(DIRECT+url,"");if(!direct.isEmpty()&&at>0&&age>=0&&age<TTL_MS)return new Cache(direct,at);if(at!=0||!direct.isEmpty())prefs.edit().remove(DIRECT+url).remove(TIME+url).apply();return null;}
  private void cleanupExpired(){long now=clock.now();SharedPreferences.Editor edit=null;for(Map.Entry<String,?> entry:prefs.getAll().entrySet())if(entry.getKey().startsWith(TIME)){long at=entry.getValue() instanceof Number?((Number)entry.getValue()).longValue():0;if(at<=0||now-at<0||now-at>=TTL_MS){if(edit==null)edit=prefs.edit();String url=entry.getKey().substring(TIME.length());edit.remove(entry.getKey()).remove(DIRECT+url);}}if(edit!=null)edit.apply();}

  private void finished(Request request,String direct,long at,String error){List<Callback> callbacks;synchronized(lock){if(request.done)return;if(error==null)prefs.edit().putString(DIRECT+request.url,direct).putLong(TIME+request.url,at).apply();request.done=true;inflight.remove(request.url,request);callbacks=new ArrayList<>(request.callbacks);}if(error==null)for(Callback callback:callbacks)try{callback.resolved(direct,at,false);}catch(RuntimeException ignored){}else for(Callback callback:callbacks)try{callback.failed(error);}catch(RuntimeException ignored){}}
  private void defer(Request request,long delay){synchronized(lock){if(request.done||closed)return;request.running=false;}retries.schedule(()->{synchronized(lock){if(request.done||closed)return;}submit(request);},Math.max(1,delay),TimeUnit.MILLISECONDS);}

  @Override public void close(){List<Callback> callbacks=new ArrayList<>();synchronized(lock){if(closed)return;closed=true;for(Request request:inflight.values())if(!request.done){request.done=true;callbacks.addAll(request.callbacks);}inflight.clear();}retries.shutdownNow();executor.shutdownNow();for(Callback callback:callbacks)try{callback.failed("直链解析已取消");}catch(RuntimeException ignored){}}
  private static String failureMessage(Throwable error){Throwable current=error;while(current.getCause()!=null)current=current.getCause();String value=current.getMessage();if(value==null||value.trim().isEmpty())value=current.getClass().getSimpleName();return value.startsWith("无法解析下载链接：")?value:"无法解析下载链接："+value;}
  private static final class Cache { final String url;final long at;Cache(String url,long at){this.url=url;this.at=at;} }
  private final class Request implements Runnable {
    final String url;final List<Callback> callbacks=new ArrayList<>();volatile boolean confirmed,running,done;int failures;
    Request(String url,boolean confirmed){this.url=url;this.confirmed=confirmed;}
    @Override public void run(){synchronized(lock){if(done||closed)return;running=true;}try{LanzouCore.DirectLink link=core.resolveDirect(url);if(link==null||link.url==null||link.url.isEmpty())throw new IllegalStateException("未解析到下载直链");long at=clock.now();finished(this,link.url,at,null);}catch(InterruptedException interrupted){Thread.currentThread().interrupt();if(!closed)finished(this,null,0,failureMessage(interrupted));}catch(Exception error){long delay=LanzouCore.directRetryDelay(error,++failures);if(delay>0)defer(this,delay);else finished(this,null,0,failureMessage(error));}}
  }
}
