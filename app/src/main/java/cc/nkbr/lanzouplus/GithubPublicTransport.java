package cc.nkbr.lanzouplus;

import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.CancellationException;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorCompletionService;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.json.JSONException;
import org.json.JSONObject;
import org.json.JSONTokener;

/**
 * Anonymous transport for the public GitHub resources used by the market.
 *
 * <p>Only repository metadata, raw documents and owner avatars are eligible for a mirror.
 * Every request is a plain, credential-free GET; authorization, token and other non-public
 * GitHub requests must stay on their official transport and never pass through this class.
 * The mirror set is the same public gh-proxy family already used by the updater, but the
 * capability differs by resource: API and avatar mirrors are deliberately narrower because
 * some instances reject those paths.
 *
 * <p>All eligible routes race concurrently. The first response that passes content validation
 * wins, failures never win, and losing connections are disconnected. A small bounded pool keeps
 * simultaneous sockets predictable on a phone. When every mirror is known to be unhealthy the
 * direct GitHub route is the only route submitted, so the fallback does not wait for mirrors.
 */
final class GithubPublicTransport {
  private static final String[] RAW_MIRRORS={
      "https://gh-proxy.com/",
      "https://ghfast.top/",
      "https://gh.llkk.cc/"
  };
  private static final String[] API_MIRRORS={
      "https://gh-proxy.com/",
      "https://gh.llkk.cc/"
  };
  private static final String[] AVATAR_MIRRORS={
      "https://gh-proxy.com/"
  };

  private static final int POOL_SIZE=8;
  private static final int QUEUE_LIMIT=64;
  private static final int REDIRECT_LIMIT=4;
  private static final int MIRROR_CONNECT_TIMEOUT_MS=4000;
  private static final int MIRROR_READ_TIMEOUT_MS=12000;
  private static final int DIRECT_CONNECT_TIMEOUT_MS=8000;
  private static final int DIRECT_READ_TIMEOUT_MS=15000;
  private static final long OVERALL_TIMEOUT_MS=20000L;
  private static final long RETRY_MILLIS=60L*1000L;
  private static final long BAD_MIRROR_RETRY_MILLIS=10L*60L*1000L;

  private static final AtomicInteger THREAD_IDS=new AtomicInteger();
  private static final ExecutorService POOL=new ThreadPoolExecutor(
      POOL_SIZE,POOL_SIZE,30L,TimeUnit.SECONDS,new LinkedBlockingQueue<>(QUEUE_LIMIT),
      new ThreadFactory(){
        @Override public Thread newThread(Runnable task){
          Thread thread=new Thread(task,"lanzouplus-github-route-"+THREAD_IDS.incrementAndGet());
          thread.setDaemon(true);
          return thread;
        }
      });
  static{
    ((ThreadPoolExecutor)POOL).allowCoreThreadTimeOut(true);
  }

  private static final Map<String,Health> HEALTH=new ConcurrentHashMap<>();

  private GithubPublicTransport(){}

  /** True only for the public GitHub paths this transport is allowed to accelerate. */
  static boolean supports(String raw){
    try{return kind(new URL(raw))!=null;}catch(Exception ignored){return false;}
  }

  /**
   * Reads one bounded public GitHub resource. The caller has already checked
   * {@link #supports(String)}. Any URL that is not a public metadata, raw or avatar path is
   * rejected here rather than being forwarded to a mirror.
   */
  static byte[] get(String raw,int limit)throws Exception{
    URL original=new URL(raw);
    Kind resource=kind(original);
    if(resource==null)throw new IOException("仓库资源不属于公开 GitHub 资源");
    List<Route> routes=routes(original,resource);
    ExecutorCompletionService<Result> completion=new ExecutorCompletionService<>(POOL);
    List<Attempt> attempts=new ArrayList<>();
    List<Future<Result>> futures=new ArrayList<>();
    Map<Future<Result>,Attempt> byFuture=new IdentityHashMap<>();
    boolean rejected=false;
    for(Route route:routes){
      Attempt attempt=new Attempt(route,limit);
      try{
        Future<Result> future=completion.submit(attempt);
        attempts.add(attempt);
        futures.add(future);
        byFuture.put(future,attempt);
      }catch(RejectedExecutionException busy){
        rejected=true;
        break;
      }
    }
    if(rejected||attempts.isEmpty()){
      abort(attempts,futures);
      return direct(original,resource,limit);
    }

    IOException first=null;
    IOException directError=null;
    long deadline=System.nanoTime()+TimeUnit.MILLISECONDS.toNanos(OVERALL_TIMEOUT_MS);
    for(int i=0;i<attempts.size();i++){
      long remaining=deadline-System.nanoTime();
      if(remaining<=0)break;
      Future<Result> future;
      try{
        future=completion.poll(remaining,TimeUnit.NANOSECONDS);
      }catch(InterruptedException interrupted){
        abort(attempts,futures);
        Thread.currentThread().interrupt();
        throw new java.io.InterruptedIOException("仓库读取被中断");
      }
      if(future==null)break;
      Attempt attempt=byFuture.get(future);
      try{
        Result result=future.get();
        succeeded(result.route,result.elapsedNanos);
        abort(attempts,futures);
        return result.data;
      }catch(ExecutionException failed){
        Throwable cause=failed.getCause();
        if(cause instanceof CancellationException)continue;
        IOException error=cause instanceof IOException
            ?(IOException)cause:new IOException("仓库读取失败",cause);
        failed(attempt.route,error);
        if(attempt.route.base==null)directError=error;
        else if(first==null)first=error;
      }catch(CancellationException ignored){
        // A route cancelled after another route won is not a transport failure.
      }
    }
    abort(attempts,futures);
    if(directError!=null)throw directError;
    if(first!=null)throw first;
    throw new IOException("仓库读取失败（镜像与直连均无有效响应）");
  }

  private static byte[] direct(URL original,Kind resource,int limit)throws Exception{
    Route route=new Route(null,original.toExternalForm(),resource,original.getPath());
    return fetch(route,limit).data;
  }

  private static List<Route> routes(URL original,Kind resource){
    String[] mirrors=mirrors(resource);
    List<String> enabled=new ArrayList<>();
    for(String base:mirrors)if(healthy(resource,base))enabled.add(base);
    Collections.sort(enabled,(left,right)->Long.compare(score(resource,left),score(resource,right)));
    List<Route> routes=new ArrayList<>();
    for(String base:enabled)routes.add(new Route(base,mirrorUrl(base,original,resource),resource,
        original.getPath()));
    routes.add(new Route(null,original.toExternalForm(),resource,original.getPath()));
    return routes;
  }

  private static String[] mirrors(Kind resource){
    if(resource==Kind.API)return API_MIRRORS;
    if(resource==Kind.AVATAR)return AVATAR_MIRRORS;
    return RAW_MIRRORS;
  }

  private static String mirrorUrl(String base,URL original,Kind resource){
    if(resource==Kind.AVATAR&&"github.com".equalsIgnoreCase(original.getHost())){
      String path=original.getPath();
      String owner=path.substring(1,path.length()-".png".length());
      return base+"https://avatars.githubusercontent.com/"+owner+"?s="+avatarSize(original.getQuery());
    }
    return base+original.toExternalForm();
  }

  private static int avatarSize(String query){
    int size=96;
    if(query==null)return size;
    for(String part:query.split("&",-1)){
      int equals=part.indexOf('=');
      if(equals<=0)continue;
      String name=part.substring(0,equals);
      if(!name.equals("s")&&!name.equals("size"))continue;
      int value=Integer.parseInt(part.substring(equals+1));
      if(value>0&&value<=1024)size=value;
    }
    return size;
  }

  private static Result fetch(Route route,int limit)throws Exception{
    long started=System.nanoTime();
    URL current=new URL(route.url);
    for(int redirects=0;redirects<=REDIRECT_LIMIT;redirects++){
      if(route.aborted)throw new CancellationException();
      if(!"https".equalsIgnoreCase(current.getProtocol())||current.getUserInfo()!=null
          ||!defaultHttpsPort(current)||!allowedRedirectHost(current.getHost())){
        throw new IOException("仓库资源跳转不受信任");
      }
      HttpURLConnection connection=(HttpURLConnection)current.openConnection();
      route.connection=connection;
      if(route.aborted)throw new CancellationException();
      try{
        connection.setConnectTimeout(route.mirror()
            ?MIRROR_CONNECT_TIMEOUT_MS:DIRECT_CONNECT_TIMEOUT_MS);
        connection.setReadTimeout(route.mirror()
            ?MIRROR_READ_TIMEOUT_MS:DIRECT_READ_TIMEOUT_MS);
        connection.setInstanceFollowRedirects(false);
        connection.setRequestProperty("User-Agent","LanzouPlus-Market");
        connection.setRequestProperty("Accept",acceptFor(route.kind));
        connection.setRequestProperty("Accept-Encoding","identity");
        int status=connection.getResponseCode();
        if(isRedirect(status)){
          String location=connection.getHeaderField("Location");
          if(location==null||location.isEmpty())throw new IOException("仓库资源跳转无效");
          current=new URL(current,location);
          continue;
        }
        if(status!=HttpURLConnection.HTTP_OK)throw new HttpStatusException(status);
        if(route.aborted)throw new CancellationException();
        long length=connection.getContentLengthLong();
        if(length>limit)throw new IOException("仓库文件超过容量上限");
        byte[] data;
        try(InputStream input=connection.getInputStream()){
          data=MarketRepositoryClient.bytes(input,limit);
        }
        if(length>=0&&data.length!=length)throw new IOException("仓库数据不完整");
        if(route.aborted)throw new CancellationException();
        validate(data,connection.getContentType(),route.kind,route.originalPath);
        return new Result(route,data,System.nanoTime()-started);
      }finally{
        if(route.connection==connection)route.connection=null;
        try{connection.disconnect();}catch(Exception ignored){}
      }
    }
    throw new IOException("仓库资源跳转过多");
  }

  private static String acceptFor(Kind resource){
    if(resource==Kind.AVATAR)return "image/avif,image/webp,image/png,image/jpeg,image/*;q=0.9,*/*;q=0.1";
    return "application/vnd.github+json,application/json,text/plain;q=0.9,*/*;q=0.1";
  }

  private static boolean isRedirect(int status){
    return status==301||status==302||status==303||status==307||status==308;
  }

  private static boolean defaultHttpsPort(URL url){
    return url.getPort()==-1||url.getPort()==443;
  }

  private static boolean allowedRedirectHost(String host){
    String value=host==null?"":host.toLowerCase(Locale.ROOT);
    return value.equals("raw.githubusercontent.com")
        ||value.equals("api.github.com")
        ||value.equals("github.com")
        ||value.equals("avatars.githubusercontent.com")
        ||value.equals("gh-proxy.com")
        ||value.equals("ghfast.top")
        ||value.equals("gh.llkk.cc");
  }

  private static Kind kind(URL url){
    if(url==null||!("https".equalsIgnoreCase(url.getProtocol()))||url.getUserInfo()!=null
        ||url.getRef()!=null||!defaultHttpsPort(url))return null;
    String host=url.getHost().toLowerCase(Locale.ROOT);
    String path=url.getPath()==null?"":url.getPath();
    String query=url.getQuery();
    if(host.equals("api.github.com")){
      if(query!=null)return null;
      String[] parts=path.split("/",-1);
      if(parts.length==4&&parts[0].isEmpty()&&parts[1].equals("repos")
          &&!parts[2].isEmpty()&&!parts[3].isEmpty())return Kind.API;
      if(parts.length==5&&parts[0].isEmpty()&&parts[1].equals("repos")
          &&!parts[2].isEmpty()&&!parts[3].isEmpty()&&parts[4].isEmpty())return Kind.API;
      return null;
    }
    if(host.equals("raw.githubusercontent.com")){
      if(query!=null)return null;
      String[] parts=path.split("/",-1);
      return parts.length>=5?Kind.RAW:null;
    }
    if(host.equals("github.com")){
      if(!path.matches("/[A-Za-z0-9][A-Za-z0-9-]{0,38}\\.png"))return null;
      return avatarQuery(query)?Kind.AVATAR:null;
    }
    if(host.equals("avatars.githubusercontent.com")){
      if(!path.matches("/(?:u/[0-9]+|[A-Za-z0-9][A-Za-z0-9-]{0,38})"))return null;
      return avatarQuery(query)?Kind.AVATAR:null;
    }
    return null;
  }

  private static boolean avatarQuery(String query){
    if(query==null)return true;
    if(query.isEmpty())return false;
    for(String part:query.split("&",-1)){
      int equals=part.indexOf('=');
      if(equals<=0||equals==part.length()-1)return false;
      String name=part.substring(0,equals);
      if(!name.equals("v")&&!name.equals("s")&&!name.equals("size"))return false;
      if(!part.substring(equals+1).matches("[0-9]{1,6}"))return false;
    }
    return true;
  }

  private static void validate(byte[] data,String contentType,Kind resource,
      String originalPath)throws IOException{
    if(resource==Kind.API){
      validateJson(data,true,originalPath);
      return;
    }
    if(resource==Kind.AVATAR){
      validateImage(data,contentType);
      return;
    }
    String path=originalPath==null?"":originalPath.toLowerCase(Locale.ROOT);
    if(path.endsWith(".json")){
      validateJson(data,false,originalPath);
    }else if(path.endsWith(".lzp")){
      validateLzp(data);
    }else if(isImagePath(path)){
      validateImage(data,contentType);
    }else if(path.endsWith(".html")||path.endsWith(".htm")){
      // Raw HTML is not a structured market document; leave ordinary HTML untouched.
      return;
    }else{
      validateTextOrBinary(data,contentType);
    }
  }

  private static void validateJson(byte[] data,boolean api,String originalPath)throws IOException{
    int start=firstContentByte(data);
    if(start>=data.length||(data[start]!='{'&&data[start]!='['))
      throw new InvalidContentException("仓库 JSON 响应无效");
    JSONTokener tokener=new JSONTokener(new String(data,start,data.length-start,StandardCharsets.UTF_8));
    Object parsed;
    try{
      parsed=tokener.nextValue();
      if(tokener.nextClean()!=0)throw new JSONException("trailing content");
    }catch(JSONException error){
      throw new InvalidContentException("仓库 JSON 响应无效",error);
    }
    if(!(parsed instanceof JSONObject))throw new InvalidContentException("仓库 JSON 响应不是对象");
    JSONObject object=(JSONObject)parsed;
    if(api){
      if(!object.has("full_name")&&!(object.has("name")&&object.has("owner")))
        throw new InvalidContentException("GitHub API 响应缺少仓库信息");
      String expected=repositoryIdentity(originalPath);
      String actual=object.optString("full_name","");
      if(expected!=null&&!actual.isEmpty()&&!expected.equalsIgnoreCase(actual))
        throw new InvalidContentException("GitHub API 仓库身份不匹配");
    }
    if(object.has("message")&&!object.has("schema")&&!object.has("name")
        &&!object.has("sources"))throw new InvalidContentException("GitHub API 返回错误信息");
  }

  private static String repositoryIdentity(String originalPath){
    if(originalPath==null)return null;
    String[] parts=originalPath.split("/",-1);
    return parts.length>=4&&parts[0].isEmpty()&&parts[1].equals("repos")
        ?parts[2]+"/"+parts[3]:null;
  }

  private static void validateLzp(byte[] data)throws IOException{
    if(data.length<=MarketSourceCodec.HEADER_LENGTH)
      throw new InvalidContentException("仓库源码文档过短");
    for(int i=0;i<MarketSourceCodec.MAGIC.length;i++){
      if(data[i]!=MarketSourceCodec.MAGIC[i])
        throw new InvalidContentException("仓库源码文档不是 LZP1 容器");
    }
  }

  private static void validateImage(byte[] data,String contentType)throws IOException{
    if(data.length==0)throw new InvalidContentException("仓库图片响应为空");
    if(looksLikeHtml(data))throw new InvalidContentException("镜像返回了 HTML 而不是图片");
    if(isImage(data))return;
    String type=contentType==null?"":contentType.toLowerCase(Locale.ROOT);
    if(type.startsWith("image/")&&!type.contains("html"))return;
    throw new InvalidContentException("仓库图片响应无效");
  }

  private static void validateTextOrBinary(byte[] data,String contentType)throws IOException{
    if(looksLikeHtml(data)||isHtmlContentType(contentType))
      throw new InvalidContentException("镜像返回了 HTML 错误页");
  }

  private static boolean isHtmlContentType(String contentType){
    return contentType!=null&&contentType.toLowerCase(Locale.ROOT).startsWith("text/html");
  }

  private static boolean isImagePath(String path){
    return path.endsWith(".png")||path.endsWith(".jpg")||path.endsWith(".jpeg")
        ||path.endsWith(".gif")||path.endsWith(".webp")||path.endsWith(".avif")
        ||path.endsWith(".bmp")||path.endsWith(".ico")||path.endsWith(".svg");
  }

  private static boolean isImage(byte[] data){
    if(data.length>=8&&(data[0]&255)==0x89&&data[1]=='P'&&data[2]=='N'&&data[3]=='G')return true;
    if(data.length>=3&&(data[0]&255)==0xFF&&(data[1]&255)==0xD8&&(data[2]&255)==0xFF)return true;
    if(data.length>=6&&data[0]=='G'&&data[1]=='I'&&data[2]=='F'&&data[3]=='8')return true;
    if(data.length>=12&&data[0]=='R'&&data[1]=='I'&&data[2]=='F'&&data[3]=='F'
        &&data[8]=='W'&&data[9]=='E'&&data[10]=='B'&&data[11]=='P')return true;
    if(data.length>=4&&data[0]=='B'&&data[1]=='M')return true;
    if(data.length>=4&&(data[0]&255)==0&&(data[1]&255)==0&&(data[2]&255)==1&&(data[3]&255)==0)return true;
    int start=firstContentByte(data);
    if(start<data.length&&data[start]!='<')return false;
    String head=new String(data,start,Math.min(256,data.length-start),StandardCharsets.UTF_8)
        .toLowerCase(Locale.ROOT);
    return head.contains("<svg");
  }

  private static boolean looksLikeHtml(byte[] data){
    int start=firstContentByte(data);
    if(start>=data.length||data[start]!='<')return false;
    String head=new String(data,start,Math.min(64,data.length-start),StandardCharsets.UTF_8)
        .toLowerCase(Locale.ROOT);
    return head.startsWith("<!doctype html")||head.startsWith("<html")
        ||head.startsWith("<head")||head.startsWith("<body");
  }

  private static int firstContentByte(byte[] data){
    int index=0;
    if(data.length>=3&&(data[0]&255)==0xEF&&(data[1]&255)==0xBB&&(data[2]&255)==0xBF)index=3;
    while(index<data.length){
      int value=data[index]&255;
      if(value!=' '&&value!='\t'&&value!='\r'&&value!='\n')break;
      index++;
    }
    return index;
  }

  private static boolean healthy(Kind resource,String base){
    Health health=HEALTH.get(key(resource,base));
    return health==null||health.retryAt<=System.currentTimeMillis();
  }

  private static long score(Kind resource,String base){
    Health health=HEALTH.get(key(resource,base));
    return health==null||health.lastLatencyNanos<=0?Long.MAX_VALUE/2:health.lastLatencyNanos;
  }

  private static void succeeded(Route route,long elapsedNanos){
    if(route.base==null)return;
    Health health=HEALTH.computeIfAbsent(key(route.kind,route.base),ignored->new Health());
    health.retryAt=0;
    health.lastLatencyNanos=elapsedNanos;
  }

  private static void failed(Route route,IOException error){
    if(route.base==null)return;
    Health health=HEALTH.computeIfAbsent(key(route.kind,route.base),ignored->new Health());
    health.retryAt=System.currentTimeMillis()+retryDelay(error);
  }

  private static long retryDelay(IOException error){
    if(error instanceof HttpStatusException){
      int status=((HttpStatusException)error).status;
      if(status==403||status==404||status==410||status==429)return BAD_MIRROR_RETRY_MILLIS;
    }
    if(error instanceof InvalidContentException)return BAD_MIRROR_RETRY_MILLIS;
    return RETRY_MILLIS;
  }

  private static String key(Kind resource,String base){
    return resource.name()+"|"+base;
  }

  private static void abort(List<Attempt> attempts,List<Future<Result>> futures){
    for(Future<Result> future:futures){
      try{future.cancel(true);}catch(Exception ignored){}
    }
    for(Attempt attempt:attempts)attempt.abort();
  }

  private enum Kind{RAW,API,AVATAR}

  private static final class Health{
    volatile long retryAt;
    volatile long lastLatencyNanos;
  }

  private static final class Route{
    final String base;
    final String url;
    final Kind kind;
    final String originalPath;
    volatile HttpURLConnection connection;
    volatile boolean aborted;
    Route(String base,String url,Kind kind,String originalPath){
      this.base=base;this.url=url;this.kind=kind;this.originalPath=originalPath;
    }
    boolean mirror(){return base!=null;}
    void abort(){
      aborted=true;
      HttpURLConnection value=connection;
      if(value!=null)try{value.disconnect();}catch(Exception ignored){}
    }
  }

  private static final class Result{
    final Route route;
    final byte[] data;
    final long elapsedNanos;
    Result(Route route,byte[] data,long elapsedNanos){
      this.route=route;this.data=data;this.elapsedNanos=elapsedNanos;
    }
  }

  private static final class Attempt implements Callable<Result>{
    final Route route;
    final int limit;
    Attempt(Route route,int limit){
      this.route=route;this.limit=limit;
    }
    @Override public Result call()throws Exception{
      if(route.aborted)throw new CancellationException();
      return fetch(route,limit);
    }
    void abort(){route.abort();}
  }

  private static final class HttpStatusException extends IOException{
    final int status;
    HttpStatusException(int status){
      super("仓库读取失败（HTTP "+status+"）");
      this.status=status;
    }
  }

  private static final class InvalidContentException extends IOException{
    InvalidContentException(String message){super(message);}
    InvalidContentException(String message,Throwable cause){super(message,cause);}
  }
}
