package cc.nkbr.lanzouplus;

import android.content.*;
import android.net.Uri;
import android.os.ParcelFileDescriptor;
import java.io.*;
import java.net.*;
import java.nio.channels.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.regex.*;

/** Bounded process-wide transfer engine with restart-safe Range continuation. */
final class SegmentDownloader {
  interface Listener { void progress(long done,long total); void completed(); void failed(String error); default void metadata(String contentType,String contentDisposition,String finalUrl){} default void notice(String text){} default void paused(long done,long total){} default void cancelled(long done,long total){} }
  private static final String UA="Mozilla/5.0 (Linux; Android 15; Pixel 9 Pro) AppleWebKit/537.36 Chrome/138 Mobile Safari/537.36";
  private static final Pattern CONTENT_RANGE=Pattern.compile("(?i)bytes\\s+(\\d+)-(\\d+)/(\\d+)");
  private static final int WORKER_COUNT=TransferCoordinator.adaptiveUnlimitedLimit();
  private static final long WORKER_STACK_BYTES=524288L;
  private static final AtomicInteger ACTIVE_WORKERS=new AtomicInteger();
  private static final int FAST_SEGMENTS=6;
  private static final long FAST_MIN_BYTES=512L*1024;
  private static final ThreadPoolExecutor WORKERS=new ThreadPoolExecutor(WORKER_COUNT,WORKER_COUNT,20L,TimeUnit.SECONDS,new LinkedBlockingQueue<>(),r->{Thread t=new Thread(null,r,"lanzou-download",WORKER_STACK_BYTES);t.setDaemon(true);return t;});
  static{WORKERS.allowCoreThreadTimeOut(true);}
  private final Context context;
  private final TransferTerminal terminal=new TransferTerminal();
  private volatile HttpURLConnection active;
  private final Set<HttpURLConnection> parallelConnections=Collections.newSetFromMap(new ConcurrentHashMap<>());

  SegmentDownloader(Context context){this.context=context.getApplicationContext();}
  boolean pause(){return stop(1);}
  boolean cancel(){return stop(2);}
  private boolean stop(int mode){if(!terminal.request(mode))return false;HttpURLConnection connection=active;if(connection!=null)connection.disconnect();for(HttpURLConnection other:parallelConnections)other.disconnect();return true;}

  void startResolved(String directUrl,Uri destination,long expectedTotal,Listener listener){start(directUrl,destination,expectedTotal,listener,false);}
  /** Downloads a trusted GitHub/official-site update URL without Lanzou resolution. */
  void startDirect(String directUrl,Uri destination,Listener listener){start(directUrl,destination,0,listener,true);}
  /** Probes trusted mirror candidates, then downloads the fastest one with parallel Range segments. */
  void startFast(List<String> candidates,Uri destination,long expectedTotal,Listener listener){
    LinkedHashSet<String> urls=new LinkedHashSet<>();if(candidates!=null)for(String value:candidates)if(value!=null&&!value.trim().isEmpty())urls.add(value.trim());
    try{WORKERS.execute(()->runFast(new ArrayList<>(urls),destination,expectedTotal,listener));}
    catch(OutOfMemoryError exhausted){listener.failed("无法下载：系统内存不足，已降低并发后请重试");}
    catch(RejectedExecutionException rejected){listener.failed("无法下载：下载任务队列不可用");}
  }

  private void start(String url,Uri destination,long expectedTotal,Listener listener,boolean guarded){
    try{WORKERS.execute(()->runTransfer(url,destination,expectedTotal,listener,guarded));}
    catch(OutOfMemoryError exhausted){listener.failed("无法下载：系统内存不足，已降低并发后请重试");}
    catch(RejectedExecutionException rejected){listener.failed("无法下载：下载任务队列不可用");}
  }

  private void runTransfer(String url,Uri destination,long expectedTotal,Listener listener,boolean guarded){
    ACTIVE_WORKERS.incrementAndGet();
    try{
      try{android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_BACKGROUND);}catch(Throwable ignored){}
      Transfer stats=new Transfer();Exception failure=null;try{download(url,destination,expectedTotal,listener,guarded,stats);}catch(Exception error){failure=error;}
      int outcome=terminal.claim();active=null;if(failure==null&&outcome==0){listener.completed();return;}if(stats.total>0)listener.progress(Math.min(stats.done,stats.total),stats.total);if(outcome==1)listener.paused(stats.done,stats.total);else if(outcome==2)listener.cancelled(stats.done,stats.total);else listener.failed(failureMessage(failure==null?new IOException("下载终态冲突"):failure));
    }finally{ACTIVE_WORKERS.decrementAndGet();}
  }

  private static long progressIntervalMs(){int active=ACTIVE_WORKERS.get();return active>=64?750L:active>=24?500L:240L;}

  private static final class Transfer { long done,total; }
  private static final class Response {
    final HttpURLConnection connection;final long start,end,total;
    Response(HttpURLConnection connection,long start,long end,long total){this.connection=connection;this.start=start;this.end=end;this.total=total;}
  }
  private static final class Sink implements Closeable {
    final OutputStream out;final Closeable owner;
    Sink(OutputStream out,Closeable owner){this.out=out;this.owner=owner;}
    @Override public void close()throws IOException{IOException failure=null;try{out.close();}catch(IOException error){failure=error;}if(owner!=null)try{owner.close();}catch(IOException error){if(failure==null)failure=error;}if(failure!=null)throw failure;}
  }

  private void download(String url,Uri destination,long expectedTotal,Listener listener,boolean guarded,Transfer stats)throws Exception{
    checkStopped();long existing=destinationLength(destination);Response response=existing>0?openResume(url,existing,expectedTotal,guarded,listener):openFirst(url,guarded);active=response.connection;checkStopped();
        long total=response.total,done=response.start;stats.total=total;stats.done=done;
    listener.metadata(response.connection.getContentType(),response.connection.getHeaderField("Content-Disposition"),response.connection.getURL()==null?url:response.connection.getURL().toString());
    listener.progress(done,total);
    Sink destinationOut;
    try{destinationOut=openDestination(destination,response.start);}catch(Exception error){response.connection.disconnect();throw error;}
    try(Sink sink=destinationOut){
      OutputStream out=sink.out;long lastEvent=0;byte[] buffer=new byte[32768];
      while(done<total){
        checkStopped();
        if(response.start!=done||response.total!=total){response.connection.disconnect();throw new IOException("Range 连续性校验失败");}
        long expected=response.end-response.start+1,written=0;
        try(InputStream in=response.connection.getInputStream()){
          for(int count;(count=in.read(buffer))>0;){
            checkStopped();out.write(buffer,0,count);written+=count;done+=count;stats.done=done;
            long now=System.currentTimeMillis();if(now-lastEvent>=progressIntervalMs()){lastEvent=now;listener.progress(Math.min(done,total),total);}
          }
        }finally{response.connection.disconnect();}
        if(written!=expected)throw new IOException(response.end+1<total?"Range 分段长度校验失败":"文件长度校验失败");
        if(done>total)throw new IOException("文件总长度校验失败");
        if(done<total){response=openRange(url,done,total,guarded);active=response.connection;checkStopped();}
      }
      out.flush();
    }
        if(done!=total)throw new IOException("文件总长度校验失败");long persisted=destinationLength(destination);if(persisted!=total)throw new IOException("本地文件长度校验失败");listener.progress(total,total);
  }
  private static final class Probe{final String url,finalUrl,type,disposition;final long total,bps;final boolean ranges;
    Probe(String url,String finalUrl,String type,String disposition,long total,long bps,boolean ranges){this.url=url;this.finalUrl=finalUrl;this.type=type;this.disposition=disposition;this.total=total;this.bps=bps;this.ranges=ranges;}}

  private void runFast(List<String> candidates,Uri destination,long expectedTotal,Listener listener){
    ACTIVE_WORKERS.incrementAndGet();
    try{
      try{android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_BACKGROUND);}catch(Throwable ignored){}
      List<Probe> probes=probeAll(candidates);
      checkStopped();
      if(probes.isEmpty()){
        Exception last=null;
        for(String candidate:candidates){
          try{Transfer stats=new Transfer();download(candidate,destination,expectedTotal,listener,true,stats);if(terminal.claim()==0)listener.completed();return;}
          catch(IOException error){checkStopped();last=error;}
        }
        if(checkStoppedAfterFailure(listener,last))return;
        listener.failed(failureMessage(last==null?new IOException("下载地址均不可用"):last));return;
      }
      Probe best=probes.get(0);
      if(expectedTotal>0&&best.total!=expectedTotal)throw new IOException("更新包大小与发布信息不一致");
      listener.metadata(best.type,best.disposition,best.finalUrl);
      if(best.ranges&&best.total>=FAST_MIN_BYTES){
        Exception last=null;
        for(Probe probe:probes){
          if(!probe.ranges||probe.total!=best.total)continue;
          try{downloadSegmented(probe,destination,best.total,listener);if(terminal.claim()==0)listener.completed();return;}
          catch(IOException error){checkStopped();last=error;}
        }
        if(checkStoppedAfterFailure(listener,last))return;
      }
      Exception last=null;
      for(Probe probe:probes){
        if(probe.total!=best.total)continue;
        try{
          Transfer stats=new Transfer();download(probe.url,destination,expectedTotal,listener,true,stats);clearParts(probe.url);
          if(terminal.claim()==0)listener.completed();return;
        }catch(IOException error){checkStopped();last=error;}
      }
      if(checkStoppedAfterFailure(listener,last))return;
      listener.failed(failureMessage(last==null?new IOException("下载地址均不可用"):last));
    }catch(Exception error){
      int outcome=terminal.claim();if(outcome==1)listener.paused(destinationLengthQuiet(destination),0);else if(outcome==2)listener.cancelled(destinationLengthQuiet(destination),0);else listener.failed(failureMessage(error));
    }finally{ACTIVE_WORKERS.decrementAndGet();}
  }

  private boolean checkStoppedAfterFailure(Listener listener,Exception last){
    int stop=terminal.stopMode();
    if(stop==0)return false;
    terminal.claim();
    if(stop==1)listener.paused(0,0);else listener.cancelled(0,0);
    return true;
  }
  private long destinationLengthQuiet(Uri destination){try{return destinationLength(destination);}catch(Exception ignored){return 0;}}

  private List<Probe> probeAll(List<String> candidates){
    List<Probe> found=new ArrayList<>();
    if(candidates.isEmpty())return found;
    int workers=Math.min(FAST_SEGMENTS,candidates.size());
    ExecutorService pool=Executors.newFixedThreadPool(workers,r->{Thread t=new Thread(r,"lanzou-probe");t.setDaemon(true);return t;});
    CompletionService<Probe> completed=new ExecutorCompletionService<>(pool);
    for(String candidate:candidates)completed.submit(()->probe(candidate));
    long deadline=System.nanoTime()+TimeUnit.MILLISECONDS.toNanos(6500);
    try{
      for(int i=0;i<candidates.size();i++){
        long remaining=deadline-System.nanoTime();if(remaining<=0)break;
        Future<Probe> task=completed.poll(remaining,TimeUnit.NANOSECONDS);if(task==null)break;
        try{Probe result=task.get();if(result!=null&&result.total>0)found.add(result);}catch(ExecutionException ignored){}
      }
    }catch(InterruptedException interrupted){Thread.currentThread().interrupt();}
    finally{pool.shutdownNow();}
    found.sort((first,second)->Long.compare(second.bps,first.bps));
    return found;
  }

  private Probe probe(String url){
    HttpURLConnection connection=null;
    try{
      long started=System.nanoTime();connection=open(url,"bytes=0-131071",true,3500,4500);
      int code=connection.getResponseCode();
      if(code!=200&&code!=206)return null;
      rejectHtml(connection);
      String range=connection.getHeaderField("Content-Range");
      long total=code==206?contentRangeTotal(range):connection.getContentLengthLong();
      if(total<=0)return null;
      long read=0;byte[] buffer=new byte[16384];
      try(InputStream input=connection.getInputStream()){for(int count;(count=input.read(buffer))>0;){read+=count;if(read>=131072)break;}}
      long elapsed=Math.max(1L,TimeUnit.NANOSECONDS.toMillis(System.nanoTime()-started));
      String finalUrl=connection.getURL()==null?url:connection.getURL().toString();
      long bps=read*1000L/Math.max(80L,elapsed);
      return new Probe(url,finalUrl,connection.getContentType(),connection.getHeaderField("Content-Disposition"),total,bps,code==206&&range!=null);
    }catch(Exception ignored){return null;}
    finally{if(connection!=null)connection.disconnect();}
  }

  private static long contentRangeTotal(String value){
    if(value==null)return -1;
    Matcher match=CONTENT_RANGE.matcher(value);
    return match.matches()?Long.parseLong(match.group(3)):-1;
  }

  /** Writes fixed-size parts first, then merges them; part lengths make pause/resume restart-safe. */
  private void downloadSegmented(Probe probe,Uri destination,long total,Listener listener)throws Exception{
    File dir=new File(context.getCacheDir(),"release-parts");if(!dir.isDirectory()&&!dir.mkdirs())throw new IOException("无法创建下载缓存目录");
    String key=safePartKey(probe.url)+"-"+total;
    int count=(int)Math.min(FAST_SEGMENTS,Math.max(1,(total+4095)/4096));
    long chunk=(total+count-1)/count;
    File[] parts=new File[count];
    for(int i=0;i<count;i++){long start=i*chunk,end=Math.min(total,start+chunk)-1;parts[i]=new File(dir,key+".p"+i+"."+start+"-"+end);}
    long done=0;for(File part:parts)done+=part.length();
    listener.progress(Math.min(done,total),total);
    AtomicLong[] written=new AtomicLong[count];
    for(int i=0;i<count;i++)written[i]=new AtomicLong(parts[i].length());
    AtomicLong lastEvent=new AtomicLong(System.currentTimeMillis());
    ExecutorService pool=Executors.newFixedThreadPool(count,r->{Thread t=new Thread(null,r,"lanzou-segment",WORKER_STACK_BYTES);t.setDaemon(true);return t;});
    List<Future<?>> tasks=new ArrayList<>();
    try{
      for(int i=0;i<count;i++){final int index=i;tasks.add(pool.submit(()->{downloadPart(probe.url,parts[index],written[index],written,lastEvent,listener,total);return null;}));}
      for(Future<?> task:tasks)try{task.get();}catch(ExecutionException failed){Throwable cause=failed.getCause();if(cause instanceof IOException)throw(IOException)cause;if(cause instanceof Exception)throw(Exception)cause;throw new IOException("分段下载失败",cause);}
      checkStopped();
      for(int i=0;i<count;i++)if(parts[i].length()!=partLength(parts[i]))throw new IOException("分段文件长度校验失败");
      mergeParts(destination,parts,total);
      for(File part:parts)part.delete();
      listener.progress(total,total);
    }finally{pool.shutdownNow();}
  }

  private static long partLength(File part){
    String[] pieces=part.getName().split("\\.");String[] span=pieces[pieces.length-1].split("-");
    try{return Long.parseLong(span[1])-Long.parseLong(span[0])+1;}catch(Exception ignored){return part.length();}
  }

  private static String safePartKey(String url){
    String value=url.replaceAll("[^A-Za-z0-9._-]","_");
    return value.substring(Math.max(0,value.length()-80));
  }

  private void downloadPart(String url,File part,AtomicLong mine,AtomicLong[] all,AtomicLong lastEvent,Listener listener,long total)throws Exception{
    long length=partLength(part),existing=part.length();
    if(existing>length){try(RandomAccessFile reset=new RandomAccessFile(part,"rw")){reset.setLength(0);}existing=0;mine.set(0);}
    if(existing==length)return;
    long start=partStart(part)+existing,end=partStart(part)+length-1;
    HttpURLConnection connection=open(url,"bytes="+start+"-"+end,true);
    parallelConnections.add(connection);
    try{
      checkStopped();int code=connection.getResponseCode();
      if(code!=206)throw new IOException("分段区间请求失败 HTTP "+code);
      rejectHtml(connection);Matcher span=CONTENT_RANGE.matcher(connection.getHeaderField("Content-Range")==null?"":connection.getHeaderField("Content-Range"));
      if(!span.matches()||Long.parseLong(span.group(1))!=start||Long.parseLong(span.group(2))!=end||Long.parseLong(span.group(3))!=total)throw new IOException("分段响应区间不一致");
      try(RandomAccessFile out=new RandomAccessFile(part,"rw")){
        out.seek(existing);byte[] buffer=new byte[32768];
        try(InputStream input=connection.getInputStream()){
          for(int count;(count=input.read(buffer))>0;){
            checkStopped();out.write(buffer,0,count);mine.addAndGet(count);
            long now=System.currentTimeMillis();
            if(now-lastEvent.get()>=progressIntervalMs()&&lastEvent.compareAndSet(lastEvent.get(),now))listener.progress(Math.min(sum(all),total),total);
          }
        }
      }
      if(part.length()!=length)throw new IOException("分段长度校验失败");
    }finally{parallelConnections.remove(connection);connection.disconnect();}
  }

  private static long partStart(File part){
    String name=part.getName();String[] span=name.substring(name.lastIndexOf('.')+1).split("-");
    return Long.parseLong(span[0]);
  }
  private static long sum(AtomicLong[] values){long total=0;for(AtomicLong value:values)total+=value.get();return total;}
  private void clearParts(String url){
    try{File dir=new File(context.getCacheDir(),"release-parts");String prefix=safePartKey(url);File[] files=dir.listFiles();if(files==null)return;for(File file:files)if(file.getName().startsWith(prefix))file.delete();}catch(Exception ignored){}
  }

  private void mergeParts(Uri destination,File[] parts,long total)throws Exception{
    OutputStream out;
    Closeable owner=null;
    if("file".equals(destination.getScheme())){
      out=new FileOutputStream(new File(destination.getPath()),false);
    }else{
      ParcelFileDescriptor descriptor=context.getContentResolver().openFileDescriptor(destination,"rw");
      if(descriptor==null)throw new IOException("无法写入 Download 目录");
      FileOutputStream output=new FileOutputStream(descriptor.getFileDescriptor());
      FileChannel channel=output.getChannel();channel.truncate(0);channel.position(0);
      out=Channels.newOutputStream(channel);owner=descriptor;
    }
    try(Sink sink=new Sink(out,owner)){
      byte[] buffer=new byte[65536];
      for(File part:parts)try(InputStream input=new FileInputStream(part)){for(int count;(count=input.read(buffer))>0;)sink.out.write(buffer,0,count);}
      sink.out.flush();
    }
    if(destinationLength(destination)!=total)throw new IOException("合并后文件长度校验失败");
  }

  private void checkStopped()throws IOException{int stop=terminal.stopMode();if(stop!=0)throw new IOException(stop==1?"下载已暂停":"下载已取消");}

    private long destinationLength(Uri destination)throws Exception{
    if("file".equals(destination.getScheme())){File file=new File(destination.getPath());return file.isFile()?file.length():0;}
        try(ParcelFileDescriptor descriptor=context.getContentResolver().openFileDescriptor(destination,"r")){
      if(descriptor==null)throw new IOException("无法读取 Download 目录文件");
      long size=descriptor.getStatSize();if(size>=0)return size;
      try(FileInputStream input=new FileInputStream(descriptor.getFileDescriptor())){return input.getChannel().size();}
    }catch(FileNotFoundException missing){return 0;}

  }

    private Sink openDestination(Uri destination,long offset)throws Exception{
    if("file".equals(destination.getScheme())){
      File file=new File(destination.getPath());long length=file.isFile()?file.length():0;if(offset>0&&length!=offset)throw new IOException("本地续传长度已变化");
      return new Sink(new FileOutputStream(file,offset>0),null);
    }
        ParcelFileDescriptor descriptor=context.getContentResolver().openFileDescriptor(destination,"rw");
    if(descriptor==null)throw new IOException("无法写入 Download 目录");
    try{
      FileOutputStream output=new FileOutputStream(descriptor.getFileDescriptor());FileChannel channel=output.getChannel();long length=channel.size();
      if(offset==0)channel.truncate(0);else if(length!=offset)throw new IOException("本地续传长度已变化");channel.position(offset);return new Sink(Channels.newOutputStream(channel),descriptor);
    }catch(Exception error){
      try{descriptor.close();}catch(IOException closeError){error.addSuppressed(closeError);}
      throw error;
    }

  }

  private Response openResume(String url,long existing,long expectedTotal,boolean guarded,Listener listener)throws Exception{
    HttpURLConnection connection=open(url,"bytes="+existing+"-",guarded);int code=connection.getResponseCode();
    if(code==206){Response response=rangeResponse(connection,existing,expectedTotal);if(response!=null)return response;connection.disconnect();throw new IOException("续传 Range 响应校验失败");}
    if(code==200){listener.notice("服务器不支持断点续传，已从头下载");return plainResponse(connection);}
    connection.disconnect();if(code==400||code==405||code==416)return openWithoutRange(url,guarded);throw new IOException("续传请求失败 HTTP "+code);
  }

  private Response openFirst(String url,boolean guarded)throws Exception{
    HttpURLConnection connection=open(url,"bytes=0-",guarded);int code=connection.getResponseCode();
    if(code==200)return plainResponse(connection);
    if(code==206){Response response=rangeResponse(connection,0,-1);if(response!=null)return response;connection.disconnect();return openWithoutRange(url,guarded);}
    connection.disconnect();if(code==400||code==405||code==416)return openWithoutRange(url,guarded);throw new IOException("直链请求失败 HTTP "+code);
  }

  private Response openWithoutRange(String url,boolean guarded)throws Exception{
    HttpURLConnection connection=open(url,null,guarded);int code=connection.getResponseCode();
    if(code==200)return plainResponse(connection);
    if(code==206){Response response=rangeResponse(connection,0,-1);if(response!=null)return response;}
    connection.disconnect();throw new IOException("直链请求失败 HTTP "+code);
  }

  private Response openRange(String url,long start,long total,boolean guarded)throws Exception{
    HttpURLConnection connection=open(url,"bytes="+start+"-",guarded);int code=connection.getResponseCode();
    if(code==206){Response response=rangeResponse(connection,start,total);if(response!=null)return response;connection.disconnect();throw new IOException("Range 响应校验失败");}
    connection.disconnect();if(code==200)throw new IOException("服务器中途拒绝 Range 请求");throw new IOException("续传请求失败 HTTP "+code);
  }

  private Response plainResponse(HttpURLConnection connection)throws IOException{
    try{rejectHtml(connection);long total=connection.getContentLengthLong();if(total<=0)throw new IOException("服务器未提供文件大小");return new Response(connection,0,total-1,total);}catch(IOException|RuntimeException error){connection.disconnect();throw error;}
  }

  private Response rangeResponse(HttpURLConnection connection,long expectedStart,long expectedTotal)throws IOException{
    try{rejectHtml(connection);String value=connection.getHeaderField("Content-Range");Matcher match=value==null?null:CONTENT_RANGE.matcher(value);if(match==null||!match.matches())return null;long start=Long.parseLong(match.group(1)),end=Long.parseLong(match.group(2)),total=Long.parseLong(match.group(3));if(start!=expectedStart||end<start||end>=total||total<=0||(expectedTotal>0&&total!=expectedTotal))return null;return new Response(connection,start,end,total);}catch(IOException|RuntimeException error){connection.disconnect();throw error;}
  }

  private static void rejectHtml(HttpURLConnection connection)throws IOException{String type=connection.getContentType();if(type!=null){type=type.toLowerCase(Locale.ROOT);if(type.contains("text/html")||type.contains("application/json"))throw new IOException("直链仍是验证页面");}}
  private static String failureMessage(Throwable error){Throwable current=error;while(current.getCause()!=null)current=current.getCause();String message=current.getMessage();if(message==null||message.trim().isEmpty())message=current.getClass().getSimpleName();return message.startsWith("无法下载：")?message:"无法下载："+message;}

  private static HttpURLConnection open(String value,String range,boolean guarded)throws Exception{
    return open(value,range,guarded,15000,30000);
  }
  private static HttpURLConnection open(String value,String range,boolean guarded,int connectTimeout,int readTimeout)throws Exception{
    if(!guarded){HttpURLConnection connection=connection(new URL(value),true);if(range!=null)connection.setRequestProperty("Range",range);return connection;}
    URL url=new URL(value);for(int redirects=0;redirects<8;redirects++){if(!UpdateClient.isAllowedDownloadUrl(url))throw new IOException("更新下载地址不受信任");HttpURLConnection connection=connection(url,false,connectTimeout,readTimeout);if(range!=null)connection.setRequestProperty("Range",range);int code=connection.getResponseCode();if(!redirect(code))return connection;String location=connection.getHeaderField("Location");connection.disconnect();if(location==null||location.isEmpty())throw new IOException("更新下载跳转无效");url=new URL(url,location);}throw new IOException("更新下载跳转过多");
  }
  private static HttpURLConnection connection(URL url,boolean follow)throws Exception{return connection(url,follow,15000,30000);}
  private static HttpURLConnection connection(URL url,boolean follow,int connectTimeout,int readTimeout)throws Exception{HttpURLConnection connection=(HttpURLConnection)url.openConnection();connection.setConnectTimeout(connectTimeout);connection.setReadTimeout(readTimeout);connection.setInstanceFollowRedirects(follow);connection.setRequestProperty("User-Agent",UA);connection.setRequestProperty("Accept-Encoding","identity");return connection;}
  private static boolean redirect(int code){return code==301||code==302||code==303||code==307||code==308;}
}
