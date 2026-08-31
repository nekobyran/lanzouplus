package cc.nkbr.lanzouplus;

import android.content.*;
import java.util.*;
import java.util.concurrent.*;

/** Dynamic direct-link scheduling with process-wide cache, URL single-flight and password retry. */
final class DirectLinkResolver implements AutoCloseable {
  interface Callback { void resolved(String directUrl,long resolvedAt,boolean cached); void failed(String error); }
  interface PasswordCallback extends Callback { void passwordRequired(boolean rejectedPrevious); }
  interface Ticket { boolean cancel(); }
  interface Clock { long now(); }
  static final long TTL_MS=60*60*1000L;
  private static final long WORKER_STACK_BYTES=262144L;
  private static final String PREFS="direct_links",DIRECT="d:",TIME="t:",PASS="pw:",SCHEMA="schema";
  private static final int CACHE_SCHEMA=4;
  private final LanzouCore core;
  private final SharedPreferences prefs;
  private final Clock clock;
  private final Object lock=new Object();
  private final ConcurrentHashMap<String,Request> inflight=new ConcurrentHashMap<>();
  private final PriorityQueue<Request> pending=new PriorityQueue<>((first,second)->first.confirmed==second.confirmed?Long.compare(first.sequence,second.sequence):(first.confirmed?-1:1));
  private final ThreadPoolExecutor executor;
  private final ScheduledThreadPoolExecutor retries=new ScheduledThreadPoolExecutor(1,r->{Thread t=new Thread(r,"lanzou-resolve-retry");t.setDaemon(true);return t;});
  private volatile boolean closed;
  private volatile int parallelism;
    private int emergencyWorkerCeiling=Integer.MAX_VALUE,active,pressureSuccesses;
  private long nextSequence;

  DirectLinkResolver(Context context,LanzouCore core){this(context,core,System::currentTimeMillis);}
  DirectLinkResolver(Context context,LanzouCore core,Clock clock){
    this.core=core;this.clock=clock;this.prefs=context.getApplicationContext().getSharedPreferences(PREFS,Context.MODE_PRIVATE);
    parallelism=normalizeParallelism(context.getApplicationContext().getSharedPreferences("download_settings-v1",Context.MODE_PRIVATE).getInt("parallel_resolves",0));
    executor=new ThreadPoolExecutor(0,Integer.MAX_VALUE,60L,TimeUnit.SECONDS,new SynchronousQueue<>(),r->{Thread t=new Thread(null,r,"lanzou-resolve",WORKER_STACK_BYTES);t.setDaemon(true);return t;});
    executor.allowCoreThreadTimeOut(true);migrateCacheSchema();cleanupExpired();
  }

  private void migrateCacheSchema(){if(prefs.getInt(SCHEMA,0)==CACHE_SCHEMA)return;SharedPreferences.Editor edit=prefs.edit();for(String key:prefs.getAll().keySet())if(key.startsWith(DIRECT)||key.startsWith(TIME))edit.remove(key);edit.putInt(SCHEMA,CACHE_SCHEMA).apply();}

  void setParallelism(int value){int selected=normalizeParallelism(value);synchronized(lock){parallelism=selected;pumpLocked();}}
  int parallelism(){return parallelism;}
  int effectiveParallelism(){synchronized(lock){return effectiveLimitLocked();}}
  private static int normalizeParallelism(int value){return Math.max(0,value);}
    private int effectiveLimitLocked(){int device=LanzouCore.adaptiveNetworkWorkers(Integer.MAX_VALUE),desired=parallelism==0?device:parallelism;return Math.max(1,Math.min(Math.min(desired,device),emergencyWorkerCeiling));}
  private static boolean upstreamPressure(Throwable error){String value=failureMessage(error).toLowerCase(Locale.ROOT);return value.contains("验证")||value.contains("captcha")||value.contains("waf")||value.contains("429")||value.contains("频率")||value.contains("限流")||value.contains("rate limit")||value.contains("too many requests");}
  private boolean adaptToUpstreamPressure(){synchronized(lock){int limit=effectiveLimitLocked();boolean serial=limit<=1&&active<=1;if(!serial&&active<=limit){int reduced=Math.max(1,limit/2);emergencyWorkerCeiling=Math.min(emergencyWorkerCeiling,reduced);pressureSuccesses=0;pumpLocked();}return !serial;}}
  private void recordPressureFreeSuccess(){synchronized(lock){if(emergencyWorkerCeiling==Integer.MAX_VALUE)return;int current=Math.max(1,emergencyWorkerCeiling);pressureSuccesses++;if(pressureSuccesses<Math.max(1,current/2))return;int device=LanzouCore.adaptiveNetworkWorkers(Integer.MAX_VALUE),raised=Math.min(device,current+Math.max(1,current/2));emergencyWorkerCeiling=raised>=device?Integer.MAX_VALUE:raised;pressureSuccesses=0;pumpLocked();}}


  void prewarm(String shareUrl){resolve(shareUrl,false,null);}
  void prewarm(String shareUrl,Callback callback){resolve(shareUrl,false,callback);}
  void prewarmAll(Collection<String> shareUrls){if(shareUrls!=null)for(String url:shareUrls)prewarm(url);}
  boolean hasFresh(String shareUrl){return cached(shareUrl)!=null;}
  void remember(String shareUrl,String directUrl,long resolvedAt){String share=clean(shareUrl),direct=clean(directUrl);long age=clock.now()-resolvedAt;if(!share.isEmpty()&&!direct.isEmpty()&&resolvedAt>0&&age>=0&&age<TTL_MS)prefs.edit().putString(DIRECT+share,direct).putLong(TIME+share,resolvedAt).apply();}
  String cachedPassword(String shareUrl){String url=clean(shareUrl);return url.isEmpty()?"":prefs.getString(PASS+url,"");}
  void rememberPassword(String shareUrl,String value){String url=clean(shareUrl),secret=value==null?"":value.trim();if(url.isEmpty()||!validPassword(secret))return;prefs.edit().putString(PASS+url,secret).apply();}
  void forgetPassword(String shareUrl){String url=clean(shareUrl);if(!url.isEmpty())prefs.edit().remove(PASS+url).apply();}

  Ticket resolve(String shareUrl,boolean confirmed,Callback callback){
    String url=clean(shareUrl);if(url.isEmpty()){if(callback!=null)callback.failed("缺少蓝奏云链接");return()->false;}
    if(closed){if(callback!=null)callback.failed("直链解析已取消");return()->false;}
    String rememberedPassword=cachedPassword(url);Cache hit=rememberedPassword.isEmpty()?cached(url):null;if(hit!=null){if(callback!=null)callback.resolved(hit.url,hit.at,true);return()->false;}
    boolean cancelled=false;
    synchronized(lock){
      if(closed)cancelled=true;
      else{
        Request request=inflight.get(url);
        if(request!=null){
          if(callback!=null)request.callbacks.add(callback);
          if(confirmed&&!request.confirmed){request.confirmed=true;if(request.queued&&pending.remove(request))pending.add(request);pumpLocked();}
          Request joined=request;return callback==null?()->false:()->cancel(joined,callback);
        }
        rememberedPassword=cachedPassword(url);hit=rememberedPassword.isEmpty()?cached(url):null;
        if(hit==null){request=new Request(url,confirmed,++nextSequence,rememberedPassword);if(callback!=null)request.callbacks.add(callback);inflight.put(url,request);enqueueLocked(request);Request started=request;return callback==null?()->false:()->cancel(started,callback);}
      }
    }
    if(callback!=null)if(cancelled)callback.failed("直链解析已取消");else callback.resolved(hit.url,hit.at,true);
    return()->false;
  }

  boolean providePassword(String shareUrl,String value){String url=clean(shareUrl),secret=value==null?"":value.trim();if(!validPassword(secret))return false;synchronized(lock){Request request=inflight.get(url);if(request==null||request.done||!request.awaitingPassword||closed)return false;request.password=secret;request.awaitingPassword=false;request.failures=0;if(request.running)request.resumePending=true;else enqueueLocked(request);return true;}}
  boolean cancelPasswordRequest(String shareUrl){String url=clean(shareUrl);Request request;synchronized(lock){request=inflight.get(url);if(request==null||request.done||!request.awaitingPassword)return false;request.awaitingPassword=false;}finished(request,null,0,"直链解析已取消");return true;}

  private void enqueueLocked(Request request){if(closed||request.done||request.queued||request.running||request.awaitingPassword)return;request.queued=true;pending.add(request);pumpLocked();}
  private void pumpLocked(){if(closed)return;int limit=effectiveLimitLocked();while(active<limit&&!pending.isEmpty()){Request request=pending.poll();request.queued=false;if(request.done||request.awaitingPassword)continue;request.running=true;active++;try{executor.execute(()->runAdmitted(request));}catch(RejectedExecutionException rejected){request.running=false;active--;request.done=true;inflight.remove(request.url,request);}catch(OutOfMemoryError exhausted){request.running=false;if(active>0)active--;int reduced=Math.max(1,Math.max(active,limit/2));emergencyWorkerCeiling=Math.min(emergencyWorkerCeiling,reduced);request.queued=true;pending.add(request);if(active==0){pending.remove(request);request.queued=false;request.done=true;inflight.remove(request.url,request);for(Callback callback:new ArrayList<>(request.callbacks))try{callback.failed("系统资源不足，请降低解析并发");}catch(RuntimeException ignored){}}return;}}}
  private void runAdmitted(Request request){try{request.resolveNow();}finally{synchronized(lock){if(request.running){request.running=false;if(active>0)active--;}if(request.resumePending&&!request.done&&!request.awaitingPassword){request.resumePending=false;enqueueLocked(request);}pumpLocked();}}}
  private boolean cancel(Request request,Callback callback){synchronized(lock){if(request.done||!request.callbacks.remove(callback))return false;if(request.callbacks.isEmpty()&&!request.running){request.done=true;request.awaitingPassword=false;if(request.queued){pending.remove(request);request.queued=false;}inflight.remove(request.url,request);}return true;}}

  void invalidate(String shareUrl,String directUrl){String url=clean(shareUrl);if(url.isEmpty())return;String stored=prefs.getString(DIRECT+url,"");if(directUrl==null||directUrl.isEmpty()||directUrl.equals(stored))prefs.edit().remove(DIRECT+url).remove(TIME+url).apply();}

  private Cache cached(String url){url=clean(url);if(url.isEmpty())return null;long at=prefs.getLong(TIME+url,0),age=clock.now()-at;String direct=prefs.getString(DIRECT+url,"");if(!direct.isEmpty()&&at>0&&age>=0&&age<TTL_MS)return new Cache(direct,at);if(at!=0||!direct.isEmpty())prefs.edit().remove(DIRECT+url).remove(TIME+url).apply();return null;}
  private void cleanupExpired(){long now=clock.now();SharedPreferences.Editor edit=null;for(Map.Entry<String,?> entry:prefs.getAll().entrySet())if(entry.getKey().startsWith(TIME)){long at=entry.getValue() instanceof Number?((Number)entry.getValue()).longValue():0;if(at<=0||now-at<0||now-at>=TTL_MS){if(edit==null)edit=prefs.edit();String url=entry.getKey().substring(TIME.length());edit.remove(entry.getKey()).remove(DIRECT+url);}}if(edit!=null)edit.apply();}

  private void finished(Request request,String direct,long at,String error){List<Callback> callbacks;synchronized(lock){if(request.done)return;request.done=true;request.awaitingPassword=false;if(request.queued){pending.remove(request);request.queued=false;}inflight.remove(request.url,request);callbacks=new ArrayList<>(request.callbacks);}if(error==null)prefs.edit().putString(DIRECT+request.url,direct).putLong(TIME+request.url,at).apply();if(error==null)for(Callback callback:callbacks)try{callback.resolved(direct,at,false);}catch(RuntimeException ignored){}else for(Callback callback:callbacks)try{callback.failed(error);}catch(RuntimeException ignored){}}
  private void defer(Request request,long delay){synchronized(lock){if(request.done||closed||request.awaitingPassword)return;}try{retries.schedule(()->{synchronized(lock){if(request.done||closed||request.awaitingPassword)return;enqueueLocked(request);}},Math.max(1,delay),TimeUnit.MILLISECONDS);}catch(RejectedExecutionException rejected){if(!closed)finished(request,null,0,"直链解析已取消");}}
  private void awaitPassword(Request request,boolean rejectedPrevious){PasswordCallback interactive=null;synchronized(lock){if(request.done||closed)return;request.awaitingPassword=true;request.password="";for(Callback callback:request.callbacks)if(callback instanceof PasswordCallback){interactive=(PasswordCallback)callback;break;}}if(interactive==null){finished(request,null,0,"无法解析下载链接：需要访问密码");return;}try{interactive.passwordRequired(rejectedPrevious);}catch(RuntimeException ignored){}}

  @Override public void close(){List<Callback> callbacks=new ArrayList<>();synchronized(lock){if(closed)return;closed=true;pending.clear();for(Request request:inflight.values())if(!request.done){request.done=true;request.queued=false;request.awaitingPassword=false;callbacks.addAll(request.callbacks);}inflight.clear();}retries.shutdownNow();executor.shutdownNow();for(Callback callback:callbacks)try{callback.failed("直链解析已取消");}catch(RuntimeException ignored){}}
  private static String failureMessage(Throwable error){Throwable current=error;while(current.getCause()!=null)current=current.getCause();String value=current.getMessage();if(value==null||value.trim().isEmpty())value=current.getClass().getSimpleName();return value.startsWith("无法解析下载链接：")?value:"无法解析下载链接："+value;}
  private static boolean validPassword(String value){if(value==null||value.isEmpty()||value.length()>64)return false;for(int i=0;i<value.length();i++)if(Character.isISOControl(value.charAt(i)))return false;return true;}
  private static String clean(String value){return value==null?"":value.trim();}
  private static final class Cache { final String url;final long at;Cache(String url,long at){this.url=url;this.at=at;} }
  private final class Request {
    final String url;final List<Callback> callbacks=new ArrayList<>();final long sequence;volatile boolean confirmed,running,queued,done,awaitingPassword,resumePending;String password;int failures;
    Request(String url,boolean confirmed,long sequence,String password){this.url=url;this.confirmed=confirmed;this.sequence=sequence;this.password=password==null?"":password;}
        void resolveNow(){try{LanzouCore.DirectLink link=core.resolveDirect(url,password);if(link==null||link.url==null||link.url.isEmpty())throw new IllegalStateException("未解析到下载直链");long at=clock.now();if(!password.isEmpty())rememberPassword(url,password);recordPressureFreeSuccess();finished(this,link.url,at,null);}catch(LanzouCore.DirectPasswordException rejected){boolean hadPassword=!password.isEmpty();if(hadPassword)forgetPassword(url);awaitPassword(this,hadPassword);}catch(InterruptedException interrupted){Thread.currentThread().interrupt();if(!closed)finished(this,null,0,failureMessage(interrupted));}catch(Exception error){++failures;if(upstreamPressure(error)&&adaptToUpstreamPressure()){synchronized(lock){if(!done&&!closed)resumePending=true;}return;}long delay=LanzouCore.directRetryDelay(error,failures);if(delay>0)defer(this,delay);else finished(this,null,0,failureMessage(error));}}
  }
}
