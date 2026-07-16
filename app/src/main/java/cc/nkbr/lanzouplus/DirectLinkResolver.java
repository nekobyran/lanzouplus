package cc.nkbr.lanzouplus;

import android.content.*;
import java.util.*;
import java.util.concurrent.*;

/** Process-wide direct-link cache and priority single-flight coordinator. */
final class DirectLinkResolver implements AutoCloseable {
  interface Callback { void resolved(String directUrl,long resolvedAt,boolean cached); void failed(String error); }
  interface Ticket { boolean cancel(); }
  interface Clock { long now(); }
  static final long TTL_MS=60*60*1000L;
  static final int DEFAULT_PARALLELISM=5,MAX_PARALLELISM=128;
  private static final String PREFS="direct_links",DIRECT="d:",TIME="t:",PARALLELISM="parallelism";
  private final LanzouCore core;
  private final SharedPreferences prefs;
  private final Clock clock;
  private final Object lock=new Object();
  private final ConcurrentHashMap<String,Request> inflight=new ConcurrentHashMap<>();
  private long sequence;
  private final ThreadPoolExecutor executor;
  private volatile boolean closed;

  DirectLinkResolver(Context context,LanzouCore core){this(context,core,System::currentTimeMillis);}
  DirectLinkResolver(Context context,LanzouCore core,Clock clock){
    this.core=core;this.clock=clock;this.prefs=context.getApplicationContext().getSharedPreferences(PREFS,Context.MODE_PRIVATE);
    int workers=effectiveParallelism(configuredParallelism());
    executor=new ThreadPoolExecutor(workers,workers,20,TimeUnit.SECONDS,new PriorityBlockingQueue<>(),r->{Thread t=new Thread(r,"lanzou-resolve");t.setDaemon(true);return t;});
    executor.allowCoreThreadTimeOut(true);cleanupExpired();
  }

  int configuredParallelism(){return Math.max(0,Math.min(MAX_PARALLELISM,prefs.getInt(PARALLELISM,DEFAULT_PARALLELISM)));}
  int effectiveParallelism(){return effectiveParallelism(configuredParallelism());}
  static int effectiveParallelism(int configured){return configured<=0?MAX_PARALLELISM:Math.max(1,Math.min(MAX_PARALLELISM,configured));}
  void setParallelism(int configured){
    configured=Math.max(0,Math.min(MAX_PARALLELISM,configured));prefs.edit().putInt(PARALLELISM,configured).apply();int target=effectiveParallelism(configured),current=executor.getCorePoolSize();
    if(target>current){executor.setMaximumPoolSize(target);executor.setCorePoolSize(target);}else if(target<current){executor.setCorePoolSize(target);executor.setMaximumPoolSize(target);}
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
      if(request!=null){if(callback!=null)request.callbacks.add(callback);if(confirmed)promote(request);Request joined=request;return callback==null?()->false:()->cancel(joined,callback);}
      hit=cached(url);if(hit==null){request=new Request(url,confirmed,++sequence);if(callback!=null)request.callbacks.add(callback);inflight.put(url,request);executor.execute(request);Request started=request;return callback==null?()->false:()->cancel(started,callback);}
      }
    }
    if(callback!=null)if(cancelled)callback.failed("直链解析已取消");else callback.resolved(hit.url,hit.at,true);
    return()->false;
  }

  private boolean cancel(Request request,Callback callback){synchronized(lock){if(request.done||!request.callbacks.remove(callback))return false;if(request.callbacks.isEmpty()&&!request.running&&executor.getQueue().remove(request)){request.done=true;inflight.remove(request.url,request);}return true;}}

  void invalidate(String shareUrl,String directUrl){
    String url=shareUrl==null?"":shareUrl.trim();if(url.isEmpty())return;String stored=prefs.getString(DIRECT+url,"");
    if(directUrl==null||directUrl.isEmpty()||directUrl.equals(stored))prefs.edit().remove(DIRECT+url).remove(TIME+url).apply();
  }

  private void promote(Request request){
    if(request.confirmed)return;request.confirmed=true;request.order=++sequence;
    if(!request.running&&executor.getQueue().remove(request))executor.execute(request);
  }

  private Cache cached(String url){
    long at=prefs.getLong(TIME+url,0),age=clock.now()-at;String direct=prefs.getString(DIRECT+url,"");
    if(!direct.isEmpty()&&at>0&&age>=0&&age<TTL_MS)return new Cache(direct,at);
    if(at!=0||!direct.isEmpty())prefs.edit().remove(DIRECT+url).remove(TIME+url).apply();return null;
  }

  private void cleanupExpired(){
    long now=clock.now();SharedPreferences.Editor edit=null;
    for(Map.Entry<String,?> entry:prefs.getAll().entrySet())if(entry.getKey().startsWith(TIME)){long at=entry.getValue() instanceof Number?((Number)entry.getValue()).longValue():0;if(at<=0||now-at<0||now-at>=TTL_MS){if(edit==null)edit=prefs.edit();String url=entry.getKey().substring(TIME.length());edit.remove(entry.getKey()).remove(DIRECT+url);}}
    if(edit!=null)edit.apply();
  }

  private void finished(Request request,String direct,long at,String error){
    // Publish a successful value before releasing the single-flight slot. Otherwise a
    // resolver arriving in the tiny remove->cache window could start a duplicate parse.
    List<Callback> callbacks;synchronized(lock){if(request.done)return;if(error==null)prefs.edit().putString(DIRECT+request.url,direct).putLong(TIME+request.url,at).apply();request.done=true;inflight.remove(request.url,request);callbacks=new ArrayList<>(request.callbacks);}
    if(error==null)for(Callback callback:callbacks)try{callback.resolved(direct,at,false);}catch(RuntimeException ignored){}else for(Callback callback:callbacks)try{callback.failed(error);}catch(RuntimeException ignored){}
  }

  @Override public void close(){List<Callback> callbacks=new ArrayList<>();synchronized(lock){if(closed)return;closed=true;for(Request request:inflight.values())if(!request.done){request.done=true;callbacks.addAll(request.callbacks);}inflight.clear();}executor.shutdownNow();for(Callback callback:callbacks)try{callback.failed("直链解析已取消");}catch(RuntimeException ignored){}}
  private static String rootMessage(Throwable error){Throwable current=error;while(current.getCause()!=null)current=current.getCause();String value=current.getMessage();return value==null?current.getClass().getSimpleName():value;}
  private static final class Cache { final String url;final long at;Cache(String url,long at){this.url=url;this.at=at;} }
  private final class Request implements Runnable,Comparable<Request> {
    final String url;final List<Callback> callbacks=new ArrayList<>();volatile boolean confirmed,running,done;volatile long order;
    Request(String url,boolean confirmed,long order){this.url=url;this.confirmed=confirmed;this.order=order;}
    @Override public int compareTo(Request other){if(confirmed!=other.confirmed)return confirmed?-1:1;return Long.compare(order,other.order);}
    @Override public void run(){running=true;try{LanzouCore.DirectLink link=core.resolveDirect(url);if(link==null||link.url==null||link.url.isEmpty())throw new IllegalStateException("未解析到下载直链");long at=clock.now();finished(this,link.url,at,null);}catch(Exception error){finished(this,null,0,rootMessage(error));}}
  }
}
