package cc.nkbr.lanzouplus;

import android.content.Context;
import android.content.SharedPreferences;
import org.json.*;
import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.BooleanSupplier;
import java.util.regex.*;
import java.util.zip.GZIPInputStream;
import java.util.zip.InflaterInputStream;

import static cc.nkbr.lanzouplus.LanzouCore.*;

/** Shared HTTP, browser and direct-download transport for LanzouCore. */
abstract class LanzouNetworkEngine {
  abstract DirectCookiePool directCookiePool();
  abstract <T> RouteOutcome<T> raceRoutes(List<RouteCandidate> routes,RouteAttempt<T> attempt,RouteAcceptance<T> acceptance,RouteTerminal terminal)throws Exception;

  DirectLink resolveDirect(String shareUrl)throws Exception{return resolveDirect(shareUrl,"");}
  DirectLink resolveDirectBalanced(String shareUrl,String password,long routePreference)throws Exception{Long previous=DIRECT_ROUTE_PREFERENCE.get();DIRECT_ROUTE_PREFERENCE.set(routePreference);try{return resolveDirect(shareUrl,password);}finally{if(previous==null)DIRECT_ROUTE_PREFERENCE.remove();else DIRECT_ROUTE_PREFERENCE.set(previous);}}
  DirectLink resolveDirect(String shareUrl,String password)throws Exception{
                        if(PremiumPublicShare.matches(shareUrl))return PremiumPublicShare.resolve(shareUrl,password);
                        String pwd=password==null?"":password.trim();if(pwd.length()>64||containsControl(pwd))throw new DirectPasswordException("密码格式无效");long deadline=System.nanoTime()+TimeUnit.MILLISECONDS.toNanos(DIRECT_RESOLVE_TIMEOUT_MS);SourceProfile profile=directRouteProfile(shareUrl);List<RouteCandidate> routes=routeCandidates(shareUrl,UA_SCOPE_DIRECT,profile);if(routes.isEmpty())throw new IOException("没有可用的蓝奏线路");Set<String> attempted=ConcurrentHashMap.newKeySet();RouteCandidate learned=learnedRoute(shareUrl,UA_SCOPE_DIRECT,profile),fast=directFastRoute(routes,learned,profile);


        Exception retainedVerification=null;boolean passwordTerminal=pwd.isEmpty();if(fast!=null)try{DirectLink value=resolveDirectRoute(shareUrl,pwd,fast,directLearnedRouteDeadline(deadline),attempted);profile.observeRoute(UA_SCOPE_DIRECT,fast.origin,fast.ua);observeRouteSuccess();return value;}catch(Exception error){if(passwordTerminal&&error instanceof DirectPasswordException||directRetry(error)==null&&terminalDirectFailure(error))throw error;if(routePressureError(error))observeRouteFailure(error);routes=routesAfter(routes,fast);if(userVerificationRequired(error)!=null)retainedVerification=error;if(routes.isEmpty())throw error;}
                RouteOutcome<DirectLink> outcome;try{outcome=raceRoutes(routes,route->resolveDirectRoute(shareUrl,pwd,route,deadline,attempted),value->true,error->passwordTerminal&&error instanceof DirectPasswordException||directRetry(error)==null&&terminalDirectFailure(error));}catch(Exception error){throw retainRouteChallenge(error,retainedVerification);}profile.observeRoute(UA_SCOPE_DIRECT,outcome.route.origin,outcome.route.ua);return outcome.value;
    
    
  }

  private DirectLink resolveDirectRoute(String logicalShareUrl,String password,RouteCandidate route,long deadline,Set<String> attempted)throws Exception{
    DirectCookiePool pool=directCookiePool();int quotaRenewals=0;
    while(true){
      directRemainingMillis(deadline);long now=System.currentTimeMillis();DirectCookiePool.Lease lease=pool.acquire(attempted,now,route.ua&0xff);attempted.add(lease.id);NetSession session=new NetSession(lease.jar,route.ua,deadline);
      try{DirectLink direct=resolveDirectWithSession(logicalShareUrl,route.url,password,session);direct.requestUserAgent=session.userAgent;direct.cookie=session.cookies(new URL(direct.url).getHost());pool.finish(lease,session.snapshot(),true,false,0,System.currentTimeMillis());return direct;}
      catch(Exception error){DirectRetryException retry=directRetry(error);pool.finish(lease,session.snapshot(),false,retry!=null&&retry.rateLimited,retry==null?0:retry.retryAfterMs,System.currentTimeMillis());if(error instanceof DirectCookieQuotaException){pool.exhaust(lease,System.currentTimeMillis());if(quotaRenewals++==0)continue;}else if(userVerificationRequired(error)!=null||routePressureError(error))markChallengedOrigin(route.origin);throw error;}
    }
  }
    static long directRetryDelay(Throwable error,int failures){if(error==null||failures>=3||error instanceof DirectPasswordException||terminalDirectFailure(error))return 0;DirectRetryException retry=directRetry(error);if(retry!=null)return retry.rateLimited?Math.max(600L,Math.min(3000L,retry.retryAfterMs)):Math.max(180L,Math.min(900L,retry.retryAfterMs));for(Throwable value=error;value!=null;value=value.getCause())if(value instanceof SocketTimeoutException||value instanceof ConnectException||value instanceof SocketException||value instanceof UnknownHostException)return failures==1?180L:420L;String message=String.valueOf(error.getMessage()).toLowerCase(Locale.ROOT);return message.contains("timeout")||message.contains("超时")||message.contains("reset")||message.contains("网络")?failures==1?180L:420L:0;}
  static int directRemainingMillis(long deadline)throws SocketTimeoutException{long nanos=deadline-System.nanoTime();if(nanos<=0)throw new SocketTimeoutException("直链解析超时");return(int)Math.min(Integer.MAX_VALUE,Math.max(1L,TimeUnit.NANOSECONDS.toMillis(nanos)));}
  static DirectRetryException directRetry(Throwable error){for(Throwable value=error;value!=null;value=value.getCause())if(value instanceof DirectRetryException)return(DirectRetryException)value;return null;}
        static boolean terminalDirectFailure(Throwable error){if(error instanceof ShareCancelledException)return false;for(Throwable value=error;value!=null;value=value.getCause())if(value instanceof ShareCancelledException)return false;String value=error==null?null:error.getMessage();return value!=null&&DIRECT_PROFILE_TERMINAL_INFO.matcher(value).find();}

  static void requireDirectResponse(int status,String body,String retryAfter)throws DirectRetryException{if(status==409&&strip(body).trim().equals("403"))throw new DirectCookieQuotaException();if(status==429||status==503||status==403&&DIRECT_RATE_LIMIT_INFO.matcher(body).find())throw new DirectRetryException("蓝奏请求频率受限",retryAfterMillis(retryAfter,30000),true);}
  static long retryAfterMillis(String value,long fallback){if(value==null||value.trim().isEmpty())return fallback;try{return Math.max(1000,Long.parseLong(value.trim())*1000L);}catch(NumberFormatException ignored){return fallback;}}
  static void requireDirectRateLimit(JSONObject data)throws DirectRetryException{if(data.optInt("zt")==1)return;String info=data.optString("inf","").trim();if(DIRECT_RATE_LIMIT_INFO.matcher(info).find())throw new DirectRetryException(info.isEmpty()?"蓝奏请求频率受限":info,30000,true);}
  static void requireDirectPasswordResult(JSONObject data)throws DirectPasswordException{if(data.optInt("zt")==1)return;String info=firstNonEmpty(data.optString("inf"),data.optString("info"),data.optString("msg"));if(DIRECT_PASSWORD_INFO.matcher(info).find())throw new DirectPasswordException(info);}
  /**
   * A route that carries a stale transfer signature answers with a generic "密码错误" even for a
   * share that has no password at all. Only a page that really shows a password gate may raise the
   * password prompt; everything else stays a recoverable route failure so the other routes keep
   * racing instead of aborting the whole download.
   */
  static void requireDirectTransferResult(JSONObject data,String pageHtml)throws Exception{
    try{requireDirectPasswordResult(data);}catch(DirectPasswordException error){if(!requiresSharePassword(pageHtml))throw new IOException("蓝奏下载校验未通过");throw error;}
  }


  private DirectLink resolveDirectWithSession(String logicalShareUrl,String routedShareUrl,String password,NetSession session)throws Exception{
        DirectLink share=session.getGuarded(routedShareUrl,"");requireLanzouPage(share.url);requireActiveShare(share);observePageCapabilities(sourceProfile(logicalShareUrl),share,session.ua);
        String fileTitle=cleanFileName(cap(share.html,"(?is)<title[^>]*>(.*?)</title>"));
        String transfer=directTransferHref(share.html);        DirectLink page=share;
        LinkedHashSet<String> transferPages=new LinkedHashSet<>();
        while(!transfer.isEmpty()){
          String next=new URL(new URL(page.url),transfer).toString();
          if(!transferPages.add(next)||transferPages.size()>8)throw new IOException("下载中间页循环跳转");
          page=session.getGuarded(next,page.url);if(page.downloadResponse)return direct(page.url,fileTitle);requireDirectChallengeFree(page,share.url);
          if(LanzouPageNavigation.downloadProtocol(page.html)&&LanzouPageNavigation.downloadFrame(page.html).isEmpty())break;
          transfer=directTransferHref(page.html);
        }

        List<String> bootstrapBases=directJsStringValues(page.html,"vkjxld"),bootstrapTokens=directJsStringValues(page.html,"hyggid");
        if(!bootstrapBases.isEmpty()&&!bootstrapTokens.isEmpty()){
          LinkedHashSet<String> candidates=new LinkedHashSet<>();
          for(int baseIndex=bootstrapBases.size()-1;baseIndex>=0;baseIndex--){String base=bootstrapBases.get(baseIndex);for(String token:bootstrapTokens){candidates.add(base+token+"&lanosso2");candidates.add(base+token);}}
          Exception bootstrapError=null;
          for(String bootstrap:candidates){
            try{
              DirectLink verify=session.getBootstrap(bootstrap,page.url);requireDirectChallengeFree(verify,page.url);
              if((verify.redirected||verify.downloadResponse)&&verify.url!=null&&verify.url.startsWith("http"))return direct(verify.url,fileTitle);
              String immediate=directBootstrapHref(verify.html,verify.url);if(!immediate.isEmpty())return direct(immediate,fileTitle);
              String file=cap(verify.html,"['\"]file['\"]\\s*:\\s*['\"]([^'\"]+)['\"]");
              String sign=cap(verify.html,"['\"]sign['\"]\\s*:\\s*['\"]([^'\"]+)['\"]");
              if(file.isEmpty()||sign.isEmpty())continue;
              String ajax=cap(verify.html,"url\\s*:\\s*['\"]([^'\"]*ajax\\.php[^'\"]*)['\"]");
              if(ajax.isEmpty())ajax="ajax.php";
              Map<String,String> form=new LinkedHashMap<>();
              form.put("file",file);String el=cap(verify.html,"down_r\\(\\s*(\\d+)\\s*\\)");form.put("el",el.isEmpty()?"2":el);form.put("sign",sign);
              String endpoint=new URL(new URL(verify.url),ajax).toString();
              boolean pending=false;
              for(int exchange=0;exchange<3;exchange++){
                if(exchange>0)session.sleep(DIRECT_RETRY_WAIT_MS);
                JSONObject data=new JSONObject(session.post(endpoint,form,verify));requireDirectRateLimit(data);requireDirectTransferResult(data,verify.html);
                String direct=data.optString("url").trim();
                String directLower=direct.toLowerCase(Locale.ROOT);
                if(direct.startsWith("?")||directLower.contains("signerror")||direct.contains("验证码错误")){pending=true;continue;}
                if(data.optInt("zt")==1&&direct.startsWith("http"))return direct(direct,fileTitle);
                if(!directExchangePending(data))throw new IOException(firstNonEmpty(data.optString("inf"),direct,"蓝奏验证失败"));
                pending=true;
              }
              if(pending)continue;
            }catch(InterruptedException interrupted){throw interrupted;}catch(Exception error){bootstrapError=preferVerificationError(bootstrapError,error);directRemainingMillis(session.deadline);}
          }
          if(bootstrapError!=null)throw bootstrapError;
          throw new DirectRetryException("蓝奏验证未返回真实直链",3000,false);
        }

        Map<String,String> legacy=legacyDownprocessFields(page.html);
        String sign=legacy.getOrDefault("sign","");
        if(sign.isEmpty())sign=cap(page.html,"(?:ajaxdata|sign)\\s*[:=]\\s*['\"]([^'\"]+)['\"]");
        if(sign.isEmpty()){if(requiresSharePassword(page.html)&&!isDirectorySharePage(page.html))throw new DirectPasswordException();if(isDirectorySharePage(page.html))throw new IOException("这是文件夹或软件库分享页，请使用列表解析打开");if(DIRECT_DEAD_PAGE_INFO.matcher(strip(page.html)).find())throw new IOException("分享已失效或文件已删除");if(LanzouPageNavigation.downloadProtocol(share.html))return renderDirectDownload(share.url,session.cookies(new URL(share.url).getHost()),session.userAgent,session.deadline,fileTitle);throw new IOException("未找到下载签名");}
        Map<String,String> form=new LinkedHashMap<>();
        form.put("action","downprocess");form.put("sign",sign);
        for(String key:new String[]{"websignkey","signs","websign","kd","ves"})if(legacy.containsKey(key))form.put(key,legacy.get(key));
        form.put("p",password==null?"":password);
        String ajax=legacyAjaxEndpoint(page.html);if(ajax.isEmpty())ajax="/ajaxm.php";
        JSONObject data=new JSONObject(session.post(new URL(new URL(page.url),ajax).toString(),form,page));
        requireDirectRateLimit(data);requireDirectTransferResult(data,page.html);if(data.optInt("zt")!=1){String info=data.optString("inf","").trim();if(DIRECT_RETRY_INFO.matcher(info).find())throw new DirectRetryException(info.isEmpty()?"蓝奏直链响应需要重试":info,3000,false);throw new IOException(info.isEmpty()?"蓝奏解析失败":info);}
        String path=data.optString("url"),dom=data.optString("dom").replaceAll("/+$","");
        String url=path.startsWith("http")?path:dom+"/file/"+path.replaceAll("^/+","");
        if(!url.startsWith("http"))throw new IOException("蓝奏返回了无效直链");
        if(path.startsWith("?")){DirectLink verified=resolveVerificationFilePage(session,url,page.url,fileTitle);if(verified!=null)return verified;throw new DirectRetryException("蓝奏二次验证未返回真实直链",3000,false);}
        return direct(url,fileTitle);
  }

  static DirectLink resolveVerificationFilePage(NetSession session,String url,String referer,String fileTitle)throws Exception{
    DirectLink verify=session.getGuarded(url,referer);if(verify.downloadResponse)return direct(verify.url,fileTitle);requireDirectChallengeFree(verify,referer);String html=verify.html==null?"":verify.html;if(html.isEmpty())return null;
    String file=cap(html,"[\"']file[\"'][ \t\r\n]*:[ \t\r\n]*[\"']([^\"']+)[\"']"),sign=cap(html,"[\"']sign[\"'][ \t\r\n]*:[ \t\r\n]*[\"']([^\"']+)[\"']");
    if(file.isEmpty()||sign.isEmpty())return null;String ajax=cap(html,"url[ \t\r\n]*:[ \t\r\n]*[\"']([^\"']*ajax[.]php[^\"']*)[\"']");if(ajax.isEmpty())ajax="ajax.php";
    String endpoint=new URL(new URL(verify.url),ajax).toString();for(int exchange=0;exchange<3;exchange++){if(exchange>0)session.sleep(DIRECT_RETRY_WAIT_MS);for(String el:new String[]{"2","1","3"}){Map<String,String> form=new LinkedHashMap<>();form.put("file",file);form.put("el",el);form.put("sign",sign);JSONObject data=new JSONObject(session.post(endpoint,form,verify));if(data.optInt("zt")==1){String direct=data.optString("url","").trim();String lower=direct.toLowerCase(Locale.ROOT);if(direct.startsWith("http")&&!lower.contains("signerror")&&!direct.contains("验证码错误"))return direct(direct,fileTitle);}}}
    return null;
  }


                static String directTransferHref(String html){String transfer=cap(html,"(?is)<a(?=[^>]*id=[\"']downurl[\"'])(?=[^>]*href=[\"']([^\"']+)[\"'])[^>]*>");if(transfer.isEmpty())transfer=cap(html,"(?is)<a(?=[^>]*href=[\"']([^\"']+)[\"'])(?=[^>]*id=[\"']downurl[\"'])[^>]*>");if(transfer.isEmpty())transfer=cap(html,"(?is)(?:[A-Za-z_$][A-Za-z0-9_$]*|document\\.getElementById\\([^)]*\\))\\.href\\s*=\\s*[\"']([^\"']*/tp/[^\"']+)[\"']");if(transfer.isEmpty()){String staticTp=cap(html,"(?is)<a[^>]+href=[\"']([^\"']*/tp/[^\"']+)");if(!staticTp.matches("(?is).*/tp/#.*"))transfer=staticTp;}if(transfer.isEmpty())transfer=LanzouPageNavigation.downloadFrame(html);return transfer;}
    static List<String> directJsStringValues(String html,String name){LinkedHashSet<String> values=new LinkedHashSet<>();String source=html==null?"":html;Matcher matcher=parsePattern("(?is)(?:var|let|const)?\\s*"+Pattern.quote(name)+"\\s*=\\s*[\"']([^\"']+)[\"']").matcher(source);while(matcher.find()){String value=matcher.group(1).trim();if(!value.isEmpty())values.add(value);}return new ArrayList<>(values);}
  static String directBootstrapHref(String html,String pageUrl){String raw=cap(html,"(?is)<a(?=[^>]*id=[\"']ding[\"'])(?=[^>]*href=[\"']([^\"']+)[\"'])[^>]*>");if(raw.isEmpty())raw=cap(html,"(?is)window\\.location\\.href\\s*=\\s*[\"'](https?://[^\"']+)[\"']");if(raw.isEmpty())return"";try{URL url=pageUrl==null||pageUrl.isEmpty()?new URL(raw):new URL(new URL(pageUrl),raw);String protocol=url.getProtocol();return protocol.equalsIgnoreCase("http")||protocol.equalsIgnoreCase("https")?url.toString():"";}catch(Exception ignored){return"";}}

  static boolean directShareNeedsLanzouxMirror(String html){String value=html==null?"":html.toLowerCase(Locale.ROOT);if(value.contains("acw_sc__v2")||value.contains("aliyun_waf_")||value.contains("captchav2"))return true;return directTransferHref(html).isEmpty()&&value.contains("<html")&&!value.contains("ajaxm.php")&&!value.contains("downprocess");}
    static List<String> lanzouxDirectMirrors(String raw){ArrayList<String> out=new ArrayList<>();try{URL url=new URL(raw);String host=url.getHost();if(host==null||!LANZOU_HOST.matcher(host).matches())return out;String file=url.getFile();LinkedHashSet<String> origins=new LinkedHashSet<>();origins.add(url.getProtocol()+"://"+host.toLowerCase(Locale.ROOT));Collections.addAll(origins,LANZOU_BASE_ORIGINS);for(String origin:origins)out.add(new URL(new URL(origin),file).toString());}catch(Exception ignored){}return out;}

  static String stripJsComments(String text){
    if(text==null||text.isEmpty())return"";
    Matcher scripts=parsePattern("(?is)<script\\b[^>]*>(.*?)</script\\s*>").matcher(text);
    if(scripts.find()){
      StringBuilder javascript=new StringBuilder();
      do{javascript.append(scripts.group(1)).append('\n');}while(scripts.find());
      text=javascript.toString();
    }
    StringBuilder out=new StringBuilder(text.length());
    boolean inSingleQuote=false,inDoubleQuote=false,inBacktick=false,inLineComment=false,inBlockComment=false;
    int len=text.length();
    for(int i=0;i<len;i++){
      char c=text.charAt(i);
      if(inLineComment){
        if(c=='\r'||c=='\n'){inLineComment=false;out.append(c);}
      }else if(inBlockComment){
        if(c=='*'&&i+1<len&&text.charAt(i+1)=='/'){inBlockComment=false;out.append(' ');i++;}
      }else if(inSingleQuote){
        out.append(c);
        if(c=='\\' && i+1<len)out.append(text.charAt(++i));
        else if(c=='\'')inSingleQuote=false;
      }else if(inDoubleQuote){
        out.append(c);
        if(c=='\\' && i+1<len)out.append(text.charAt(++i));
        else if(c=='"')inDoubleQuote=false;
      }else if(inBacktick){
        out.append(c);
        if(c=='\\' && i+1<len)out.append(text.charAt(++i));
        else if(c=='`')inBacktick=false;
      }else{
        if(c=='/'&&i+1<len){
          char next=text.charAt(i+1);
          boolean isUrlSlash=i>0&&text.charAt(i-1)==':';
          if(next=='/'&&!isUrlSlash){inLineComment=true;i++;continue;}
          else if(next=='*'){inBlockComment=true;i++;continue;}
        }
        if(c=='\'')inSingleQuote=true;
        else if(c=='"')inDoubleQuote=true;
        else if(c=='`')inBacktick=true;
        out.append(c);
      }
    }
    return out.toString();
  }

  static Map<String,String> legacyDownprocessFields(String html){
    LinkedHashMap<String,String> best=new LinkedHashMap<>();int bestLength=-1;String source=stripJsComments(html);
    Matcher objects=parsePattern("(?is)data\\s*[:=]\\s*\\{([^}]+)\\}").matcher(source);
    while(objects.find()){
      String body=objects.group(1);LinkedHashMap<String,String> fields=new LinkedHashMap<>();
      Matcher pairs=parsePattern("['\"]?([A-Za-z0-9_]+)['\"]?\\s*:\\s*([^,}]+)").matcher(body);
      while(pairs.find())fields.put(pairs.group(1),legacyJsValue(source,pairs.group(2)));
      if(!"downprocess".equals(fields.get("action")))continue;
      if(best.isEmpty()||body.length()>=bestLength){best=fields;bestLength=body.length();}
    }
    if(best.isEmpty()){
      String encoded=cap(source,"(?is)data\\s*:\\s*['\"](action=downprocess&[^'\"]+)");
      for(String field:encoded.split("&")){int split=field.indexOf('=');if(split>0)best.put(field.substring(0,split),unescape(field.substring(split+1)));}
    }
    return best;
  }

  static String legacyJsValue(String html,String expression){
    String value=expression==null?"":expression.trim();if(value.isEmpty())return"";
    if((value.startsWith("'")&&value.endsWith("'"))||(value.startsWith("\"")&&value.endsWith("\"")))return unescape(value.substring(1,value.length()-1));
    if(NUMERIC_LITERAL.matcher(value).matches())return value;
    if(!JS_IDENTIFIER.matcher(value).matches())return"";
    String source=html;
    Matcher matcher=parsePattern("(?is)(?:\\b(?:var|let|const)\\s+)?(?<![A-Za-z0-9_$])"
        +Pattern.quote(value)+"\\s*=\\s*(?!=)(?:'([^']*)'|\"([^\"]*)\"|([0-9]+))").matcher(source);
    String lastMatch=null;
    while(matcher.find()){
      if(matcher.group(1)!=null)lastMatch=unescape(matcher.group(1));
      else if(matcher.group(2)!=null)lastMatch=unescape(matcher.group(2));
      else if(matcher.group(3)!=null)lastMatch=matcher.group(3);
    }
    if(lastMatch!=null)return lastMatch;
    return"";
  }

  static String legacyAjaxEndpoint(String html){
    String source=stripJsComments(html);
    Matcher matcher=parsePattern("(?is)url[ \\t\\r\\n]*:[ \\t\\r\\n]*['\"]([^'\"]*(?:ajaxm|ajaxfile|ajax)[.]php(?:[?][^'\"]*)?)['\"]").matcher(source);
    String last="";
    while(matcher.find())last=unescape(matcher.group(1).trim());
    if(!last.isEmpty())return last;
    matcher=parsePattern("(?is)['\"]([^'\"]*(?:ajaxm|ajaxfile|ajax)[.]php(?:[?][^'\"]*)?)['\"]").matcher(source);
    while(matcher.find())last=unescape(matcher.group(1).trim());
    return last;
  }

  static boolean directExchangePending(JSONObject data){String info=data.optString("inf").trim();if(DIRECT_TERMINAL_INFO.matcher(info).find())return false;if(data.optInt("zt")==1)return!data.optString("url").startsWith("http");return info.isEmpty()||info.equals("0")||info.contains("稍后")||info.contains("验证")||info.contains("处理中")||info.contains("等待");}
  static boolean requiresSharePassword(String html){
    String value=html==null?"":html.toLowerCase(Locale.ROOT);if(value.isEmpty())return false;
    boolean passwordField=parsePattern("(?is)<input\\b[^>]*(?:name|id)\\s*=\\s*['\"]pwd['\"][^>]*>").matcher(value).find();
    boolean passwordPanel=parsePattern("(?is)\\bid\\s*=\\s*['\"]pwdload['\"]").matcher(value).find()||value.contains("passwddiv-input");
    boolean passwordSubmit=value.contains("document.getelementbyid('pwd')")||value.contains("document.getelementbyid(\"pwd\")")||value.contains("'pwd':pwd")||value.contains("\"pwd\":pwd");
    return passwordField&&passwordPanel&&passwordSubmit;
  }
  static final Pattern DIRECT_DEAD_PAGE_INFO=Pattern.compile("来晚|文件.{0,12}(?:取消|删除|不存在|失效)|分享.{0,12}(?:取消|删除|不存在|失效)");
    static final Pattern DIRECT_TERMINAL_INFO=Pattern.compile("取消|来晚|删除|不存在|失效|密码|禁止|违规|封禁|关闭|拒绝");
  static final Pattern DIRECT_PROFILE_TERMINAL_INFO=Pattern.compile("取消|来晚|删除|不存在|失效|密码|违规|封禁|关闭");

  static DirectLink direct(String url,String title){DirectLink out=new DirectLink();out.url=url;out.fileName=title.isEmpty()?"download.bin":title;return out;}
  static String cleanFileName(String title){String value=INVALID_FILE_NAME.matcher(strip(title)).replaceAll("_").trim();return value.isEmpty()?"download.bin":value;}

  /** Small per-resolution cookie jar; keeps the ACW and CDN verification sessions isolated. */
  static final class NetSession{
    private final Map<String,LinkedHashMap<String,String>> jar;
    private final String userAgent;
    private final byte ua;
    private final long deadline;
    NetSession(Map<String,LinkedHashMap<String,String>> seed,byte ua){this(seed,ua,NO_DEADLINE);}
    NetSession(Map<String,LinkedHashMap<String,String>> seed,byte ua,long deadline){this.deadline=deadline;this.ua=ua;userAgent=LanzouCore.userAgent(ua);jar=new HashMap<>();if(seed!=null)for(Map.Entry<String,LinkedHashMap<String,String>> host:seed.entrySet())jar.put(host.getKey(),new LinkedHashMap<>(host.getValue()));}
    Map<String,LinkedHashMap<String,String>> snapshot(){Map<String,LinkedHashMap<String,String>> out=new HashMap<>();for(Map.Entry<String,LinkedHashMap<String,String>> host:jar.entrySet())out.put(host.getKey(),new LinkedHashMap<>(host.getValue()));return out;}
    DirectLink getGuarded(String url,String referer)throws Exception{
      DirectLink verified=cachedBrowserPage(url,userAgent);if(verified!=null)return verified;String current=url;DirectLink page=get(current,referer);for(int round=0;round<3;round++){String value=acwCookie(page.html);if(value.isEmpty())return page;current=page.url==null||page.url.isEmpty()?current:page.url;put(new URL(current).getHost(),"acw_sc__v2",value);page=get(current,referer);}if(!acwCookie(page.html).isEmpty()){IOException challenge=acwChallengeFailure(page.html,page.url,current);if(challenge!=null)throw challenge;throw new DirectRetryException("蓝奏 ACW 验证未完成",1000,false);}return page;
    }
    DirectLink get(String url,String referer)throws Exception{
      String current=url,currentReferer=referer;
      for(int redirects=0;redirects<=8;redirects++){
        directRemainingMillis(deadline);HttpURLConnection c=openHttp(new URL(current));
        try{c.setInstanceFollowRedirects(false);applyTimeouts(c);applyNavigationHeaders(c,userAgent);if(!currentReferer.isEmpty()){c.setRequestProperty("Referer",currentReferer);c.setRequestProperty("Sec-Fetch-Site",origin(currentReferer).equals(origin(current))?"same-origin":"cross-site");}String cookie=cookies(new URL(current).getHost());if(!cookie.isEmpty())c.setRequestProperty("Cookie",cookie);int status=c.getResponseCode();capture(c);String retryAfter=c.getHeaderField("Retry-After"),location=c.getHeaderField("Location");if(status>=300&&status<400&&location!=null&&!location.trim().isEmpty()){requireDirectResponse(status,"",retryAfter);String next=new URL(new URL(current),location.trim()).toString();currentReferer=current;current=next;continue;}DirectLink page=new DirectLink();page.url=current;page.cookie=cookies(new URL(current).getHost());page.httpStatus=status;page.requestUserAgent=userAgent;page.createdAt=System.currentTimeMillis();page.downloadResponse=status>=200&&status<300&&isFileResponse(c);page.html=page.downloadResponse?"":readBody(c,status);requireDirectResponse(status,page.html,retryAfter);String navigation=LanzouPageNavigation.redirect(page.html,current);if(!navigation.isEmpty()){currentReferer=current;current=navigation;continue;}return page;}finally{c.disconnect();}
      }
      throw new DirectRetryException("蓝奏重定向次数过多",1000,false);
    }
    DirectLink getBootstrap(String url,String referer)throws Exception{
      String current=url;DirectLink page=getBootstrapOnce(current,referer);for(int round=0;round<3;round++){if(page.redirected)return page;String value=acwCookie(page.html);if(value.isEmpty())return page;current=page.url==null||page.url.isEmpty()?current:page.url;put(new URL(current).getHost(),"acw_sc__v2",value);page=getBootstrapOnce(current,referer);}if(page.redirected)return page;if(!acwCookie(page.html).isEmpty()){IOException challenge=acwChallengeFailure(page.html,page.url,current);if(challenge!=null)throw challenge;throw new DirectRetryException("蓝奏 ACW 验证未完成",1000,false);}return page;
    }
    private DirectLink getBootstrapOnce(String url,String referer)throws Exception{
      HttpURLConnection c=openHttp(new URL(url));try{c.setInstanceFollowRedirects(false);applyTimeouts(c);applyNavigationHeaders(c,userAgent);if(!referer.isEmpty())c.setRequestProperty("Referer",referer);String cookie=cookies(new URL(url).getHost());if(!cookie.isEmpty())c.setRequestProperty("Cookie",cookie);
      int status=c.getResponseCode();capture(c);DirectLink page=new DirectLink();String location=c.getHeaderField("Location");page.cookie=cookies(new URL(url).getHost());
      if(status>=300&&status<400&&location!=null&&!location.trim().isEmpty()){page.url=new URL(new URL(url),location.trim()).toString();page.html="";page.redirected=true;}else{page.url=url;page.downloadResponse=status>=200&&status<300&&isFileResponse(c);page.html=page.downloadResponse?"":readBody(c,status);}
      String retryAfter=c.getHeaderField("Retry-After");requireDirectResponse(status,page.html,retryAfter);return page;}finally{c.disconnect();}
    }
    String post(String url,Map<String,String> data,DirectLink referer)throws Exception{
            String host=new URL(url).getHost(),lastChallenge="";for(int round=0;round<3;round++){HttpURLConnection c=openHttp(new URL(url));try{applyTimeouts(c);c.setDoOutput(true);c.setRequestMethod("POST");applyAjaxHeaders(c,userAgent);c.setRequestProperty("Referer",referer.url);c.setRequestProperty("Origin",origin(referer.url));c.setRequestProperty("X-Requested-With","XMLHttpRequest");c.setRequestProperty("Content-Type","application/x-www-form-urlencoded; charset=UTF-8");String cookie=cookies(host);if(!cookie.isEmpty())c.setRequestProperty("Cookie",cookie);byte[] bytes=form(data).getBytes(StandardCharsets.UTF_8);c.setFixedLengthStreamingMode(bytes.length);try(OutputStream out=c.getOutputStream()){out.write(bytes);}int status=c.getResponseCode();capture(c);String result=readBody(c,status),retryAfter=c.getHeaderField("Retry-After");requireDirectResponse(status,result,retryAfter);String value=acwCookie(result);if(value.isEmpty()){if(isWafChallengePage(result))throw new UserVerificationRequiredException(referer!=null&&referer.url!=null&&!referer.url.isEmpty()?referer.url:url);return result;}put(host,"acw_sc__v2",value);lastChallenge=result;}finally{c.disconnect();}}IOException challenge=acwChallengeFailure(lastChallenge,referer==null?"":referer.url,url);if(challenge!=null)throw challenge;throw new DirectRetryException("蓝奏 ACW 验证未完成",1000,false);
    }
    void sleep(long millis)throws Exception{if(deadline==NO_DEADLINE){Thread.sleep(millis);return;}int remaining=directRemainingMillis(deadline);if(remaining<=millis){Thread.sleep(Math.max(1,remaining));throw new SocketTimeoutException("直链解析超时");}Thread.sleep(millis);directRemainingMillis(deadline);}
    private void applyTimeouts(HttpURLConnection connection)throws SocketTimeoutException{if(deadline==NO_DEADLINE){connection.setConnectTimeout(15000);connection.setReadTimeout(30000);return;}int remaining=directRemainingMillis(deadline);int connect=Math.max(1,Math.min(4000,remaining/3));int read=Math.max(1,Math.min(8000,remaining-connect));connection.setConnectTimeout(connect);connection.setReadTimeout(read);}
    static boolean isFileResponse(HttpURLConnection connection){String disposition=connection.getHeaderField("Content-Disposition"),type=connection.getHeaderField("Content-Type");if(disposition!=null&&disposition.trim().toLowerCase(Locale.ROOT).startsWith("attachment"))return true;String mime=type==null?"":type.split(";",2)[0].trim().toLowerCase(Locale.ROOT);return mime.equals("application/octet-stream")||mime.equals("application/vnd.android.package-archive")||mime.equals("application/zip");}
    private String readBody(HttpURLConnection connection,int status)throws Exception{InputStream raw=status<400?connection.getInputStream():connection.getErrorStream();if(raw==null)return"";try(InputStream input=decodedResponseStream(connection,raw)){ByteArrayOutputStream output=new ByteArrayOutputStream();byte[] buffer=new byte[8192];while(true){if(deadline!=NO_DEADLINE)connection.setReadTimeout(Math.max(1,Math.min(2000,directRemainingMillis(deadline))));int count=input.read(buffer);if(count<0)break;if(count==0)continue;if(output.size()+count>2*1024*1024)throw new IOException("蓝奏响应过大");output.write(buffer,0,count);}return output.toString("UTF-8");}}
    private LinkedHashMap<String,String> bucket(String host){LinkedHashMap<String,String> values=jar.get(host);if(values==null){values=new LinkedHashMap<>();jar.put(host,values);}return values;}
        private void capture(HttpURLConnection c){LinkedHashMap<String,String> values=bucket(c.getURL().getHost());mergeSetCookies(values,c.getHeaderFields());rememberSolvedAcwCookies(c.getURL().toString(),cookieHeader(values));}
    private void put(String host,String name,String value){bucket(host).put(name,value);}
                private String cookies(String host){Map<String,String> values=jar.get(host);String local=values==null?"":cookieHeader(values);String verified=verificationCookieForHost(host),solved=solvedAcwCookieForHost(host);return mergeCookieHeaders(mergeCookieHeaders(local,verified),solved);}

  }


  static byte pageTemplate(String html){String value=html==null?"":html.toLowerCase(Locale.ROOT);boolean minimal=value.contains("/assets/share/folder2.css")&&(value.contains("/assets/share/folder3.css")||value.contains("/assets/share/folder4.css"))&&value.contains("user-top")&&value.contains("fileview");if(minimal)return TEMPLATE_MINIMAL;boolean hasInfo=value.contains("id=\"info\"")||value.contains("id='info'");boolean classic=value.contains("/assets/img/t0.css")&&(value.contains("/assets/share/folder6.css")||value.contains("/assets/share/folder8.css")||value.contains("/assets/share/folder9.css"))&&hasInfo;return classic?TEMPLATE_CLASSIC:TEMPLATE_UNKNOWN;}
  static String searchEndpoint(DirectLink page){return sameOriginEndpoint(page,cap(page.html,"url\\s*:\\s*['\"]([^'\"]*/search/s\\.php\\?file=\\d+[^'\"]*)['\"]"),"/search/s.php");}
  static String searchSign(String html){return cap(html,"['\"]sign['\"]\\s*:\\s*['\"]([^'\"]+)['\"]");}
  static boolean hasSearchCapability(DirectLink page){return !searchEndpoint(page).isEmpty()&&!searchSign(page.html).isEmpty();}
        static boolean isWafChallengePage(String html){String value=html==null?"":html,lower=value.toLowerCase(Locale.ROOT);return lower.contains("aliyun_waf")||lower.contains("http_auto_ratelimit")||lower.contains("acw_sc__v2")||lower.contains("captchav2")||value.contains("请完成以下操作，验证您是真人")||lower.contains("verify that you are a real person")||lower.contains("nc_1_n1z")||lower.contains("nocaptcha")||lower.contains("slider")&&(lower.contains("captcha")||lower.contains("waf")||lower.contains("verify"))||acwCookie(value).length()==40;}

  static String sameOriginEndpoint(DirectLink page,String raw,String expectedPath){if(raw.isEmpty())return"";try{URL base=new URL(page.url),endpoint=new URL(base,raw);if(!base.getProtocol().equalsIgnoreCase(endpoint.getProtocol())||!base.getHost().equalsIgnoreCase(endpoint.getHost())||effectivePort(base)!=effectivePort(endpoint)||!endpoint.getPath().endsWith(expectedPath))return"";return endpoint.toString();}catch(Exception ignored){return"";}}
  static int effectivePort(URL url){return url.getPort()>=0?url.getPort():url.getDefaultPort();}
    static void requireLanzouPage(String value)throws IOException{try{URL url=new URL(value);String scheme=url.getProtocol();if((!scheme.equalsIgnoreCase("http")&&!scheme.equalsIgnoreCase("https"))||!LANZOU_HOST.matcher(url.getHost()).matches())throw new IOException("蓝奏目录跳转到了不受信任的地址");}catch(IOException error){throw error;}catch(Exception ignored){throw new IOException("蓝奏目录返回地址无效");}}
    static boolean isDirectoryOffShell(String html){String value=html==null?"":html.toLowerCase(Locale.ROOT).replace('\'', '"');return value.contains("class=\"off\"")&&value.contains("ufolder")&&value.contains("display:none")&&!value.contains("filemoreajax.php")&&!value.contains("foldermoreajax.php");}
  static boolean isUnavailableShareShell(String html){String value=html==null?"":html.toLowerCase(Locale.ROOT),title=strip(cap(html==null?"":html,"(?is)<title[^>]*>(.*?)</title>")).toLowerCase(Locale.ROOT);return isDirectoryOffShell(html)||value.contains("router.parklogic.com")||title.equals("redirecting...")||title.contains("403 forbidden")||title.equals("403")||title.equals("404")||value.contains("/assets/share/404a.css");}
    static boolean isCancelledShareText(String html){String text=COLLAPSE_WHITESPACE.matcher(strip(html==null?"":html)).replaceAll("").replace("，","").replace(",","");return text.contains("分享已取消")||text.contains("文件取消分享")||text.contains("文件已取消分享")||text.contains("已被取消分享")||text.contains("文件不存在或已删除")||text.contains("来晚啦")&&text.contains("取消分享");}
    static void requireActiveShare(DirectLink page)throws IOException{requireActiveShare(page,page==null?"":page.url);}
  /** A WAF/verification page fetched inside a direct chain must surface as a challenge carrying its own url. */
  static void requireDirectChallengeFree(DirectLink page,String fallbackUrl)throws IOException{String html=page==null||page.html==null?"":page.html;if(isWafChallengePage(html)&&(page==null||!page.verificationComplete))throw new UserVerificationRequiredException(firstNonEmpty(page==null?"":page.url,fallbackUrl));}
  /** Keeps the first verification error so a later transport error cannot mask a raised challenge. */
  static Exception preferVerificationError(Exception current,Exception next){if(next==null)return current;if(current==null)return next;if(userVerificationRequired(current)==null&&userVerificationRequired(next)!=null)return next;return current;}
  /** The ACW retry budget is exhausted: a page that is still a challenge is a verification request, not a transport error. */
  static IOException acwChallengeFailure(String html,String fallbackUrl,String requestUrl){if(!isWafChallengePage(html))return null;return new UserVerificationRequiredException(firstNonEmpty(fallbackUrl,requestUrl));}
  /** A later transport failure must not hide an earlier user-verification challenge. */
  static Exception retainRouteChallenge(Exception current,Exception challenge){if(challenge==null||userVerificationRequired(challenge)==null)return current;if(current==null)return challenge;if(current instanceof ShareCancelledException||current instanceof DirectPasswordException||userVerificationRequired(current)!=null)return current;return challenge;}
    /**
     * Lanzou answers a removed share with HTTP 400 plus the "来晚啦…文件取消分享" body, so the cancelled-share text must be
     * classified before the status code; otherwise a dead link looks like a UA/line capability failure and the client
     * retries every route before reporting failure.
     */
    static void requireActiveShare(DirectLink page,String requestedUrl)throws IOException{String html=page==null||page.html==null?"":page.html;if(isWafChallengePage(html)&&(page==null||!page.verificationComplete))throw new UserVerificationRequiredException(page==null?"":page.url);boolean same=sameHost(page==null?"":page.url,requestedUrl)||sameLanzouSite(page==null?"":page.url,requestedUrl);if(isCancelledShareText(html)){if(same)throw new ShareCancelledException(true);throw new PageCapabilityException("蓝奏线路返回分享取消页",false);}if(page!=null&&page.httpStatus>=400)throw new PageCapabilityException("HTTP "+page.httpStatus,same);if(isDirectorySharePage(html)||isSingleFileSharePage(html)||pageTemplate(html)!=TEMPLATE_UNKNOWN)return;if(isUnavailableShareShell(html))throw new PageCapabilityException("当前 UA 返回不可解析的蓝奏中间页",same);throw new PageCapabilityException("当前 UA 未返回可解析的蓝奏分享页",same);}
  /** www.lanzouq.com and vdhpl.lanzouq.com are the same Lanzou site; lanzouq.com and lanzoum.com are not. */
  static boolean sameLanzouSite(String left,String right){
    try{String a=lanzouSiteCookieKey(new URL(left.trim()).getHost()),b=lanzouSiteCookieKey(new URL(right==null?"":right.trim()).getHost());return !a.isEmpty()&&a.equals(b);}
    catch(Exception ignored){return false;}
  }
    static boolean sameHost(String left,String right){try{String a=new URL(left.trim()).getHost(),b=new URL(right==null?"":right.trim()).getHost();return a!=null&&b!=null&&a.equalsIgnoreCase(b);}catch(Exception ignored){return false;}}
  static boolean verifiedBrowserUseful(DirectLink page,boolean challengeVisible){
    if(challengeVisible)return false;
    if(browserUseful(page))return true;
    String html=page==null?"":page.html;
    return page!=null&&page.httpStatus<400&&!html.isEmpty()&&!isCancelledShareText(html)
        &&!isUnavailableShareShell(html)&&(isDirectorySharePage(html)||isSingleFileSharePage(html));
  }
  static void importUserVerificationPage(DirectLink page){
    if(page==null||!verifiedBrowserUseful(page,false))return;page.verificationComplete=true;
    try{
      URL url=new URL(page.url);String host=completeLanzouHostAlias(url.getHost());
      if(!LANZOU_HOST.matcher(host).matches())return;
      if(page.requestUserAgent!=null&&!page.requestUserAgent.isEmpty())USER_VERIFICATION_AGENTS.put(host,page.requestUserAgent);
      importUserVerificationCookie(page.url,page.cookie);
      CHALLENGED_ORIGINS.remove(url.getProtocol()+"://"+url.getAuthority());
      BROWSER_PAGE_CACHE.put(browserCacheKey(page.url,page.requestUserAgent),copyPage(page));
    }catch(java.net.MalformedURLException error){return;}
  }
  static String verifiedUserAgent(String rawUrl,String requested){
    try{String value=USER_VERIFICATION_AGENTS.get(completeLanzouHostAlias(new URL(rawUrl).getHost()));return value==null?requested:value;}
    catch(java.net.MalformedURLException error){return requested;}
  }
  static String requestUserAgent(String rawUrl,String requested){String exact=PROBE_EXACT_USER_AGENT.get();return exact==null||exact.isEmpty()?verifiedUserAgent(rawUrl,requested):exact;}
  static boolean browserUseful(DirectLink page){String html=page==null||page.html==null?"":page.html;if(html.isEmpty()||page.httpStatus>=400)return false;if(isCancelledShareText(html)||isUnavailableShareShell(html)||!page.verificationComplete&&isWafChallengePage(html))return false;return isDirectorySharePage(html)||isSingleFileSharePage(html)||pageTemplate(html)!=TEMPLATE_UNKNOWN;}
  /** JavaScript transfer pages own the hash->XHR->download step; retain their UA and cookie session. */
  @android.annotation.SuppressLint("SetJavaScriptEnabled")
  static DirectLink renderDirectDownload(String url,String cookie,String ua,long deadline,String title)throws Exception{
    if(!configuredWebViewFallbackEnabled)throw new IOException("此下载中间页需要 JavaScript 解析");
    Context ctx=appContext;if(ctx==null)throw new IOException("下载中间页没有浏览器上下文");
    acquireBrowserSlot(deadline);
    java.util.concurrent.atomic.AtomicBoolean terminal=new java.util.concurrent.atomic.AtomicBoolean(),released=new java.util.concurrent.atomic.AtomicBoolean();
    java.util.concurrent.atomic.AtomicReference<DirectLink> result=new java.util.concurrent.atomic.AtomicReference<>();
    java.util.concurrent.atomic.AtomicReference<Exception> failure=new java.util.concurrent.atomic.AtomicReference<>();
    java.util.concurrent.atomic.AtomicReference<android.webkit.WebView> holder=new java.util.concurrent.atomic.AtomicReference<>();
    java.util.concurrent.atomic.AtomicReference<LanzouSliderVerification> slider=new java.util.concurrent.atomic.AtomicReference<>();
    CountDownLatch ready=new CountDownLatch(1);android.os.Handler handler=new android.os.Handler(android.os.Looper.getMainLooper());
    Runnable cleanup=()->{if(!released.compareAndSet(false,true))return;handler.removeCallbacksAndMessages(null);LanzouSliderVerification verification=slider.getAndSet(null);if(verification!=null)verification.close();android.webkit.WebView web=holder.getAndSet(null);if(web!=null){web.stopLoading();web.destroy();}releaseBrowserSlot();ready.countDown();};
    handler.post(()->{
      try{
        android.webkit.CookieManager cookies=android.webkit.CookieManager.getInstance();cookies.setAcceptCookie(true);
        if(cookie!=null&&!cookie.isEmpty())for(Map.Entry<String,String> entry:cookieValues(cookie).entrySet())cookies.setCookie(url,entry.getKey()+"="+entry.getValue()+"; Path=/");
        android.webkit.WebView web=new android.webkit.WebView(ctx);holder.set(web);
        android.webkit.WebSettings settings=web.getSettings();settings.setJavaScriptEnabled(true);settings.setDomStorageEnabled(true);settings.setUserAgentString(ua);settings.setUseWideViewPort(true);settings.setLoadWithOverviewMode(true);
        cookies.setAcceptThirdPartyCookies(web,true);
        android.util.DisplayMetrics metrics=ctx.getResources().getDisplayMetrics();web.measure(android.view.View.MeasureSpec.makeMeasureSpec(metrics.widthPixels,android.view.View.MeasureSpec.EXACTLY),android.view.View.MeasureSpec.makeMeasureSpec(metrics.heightPixels,android.view.View.MeasureSpec.EXACTLY));web.layout(0,0,metrics.widthPixels,metrics.heightPixels);
        web.setDownloadListener((download,agent,disposition,mime,length)->{
          if(terminal.get()||!(download.startsWith("https://")||download.startsWith("http://")))return;
          DirectLink value=direct(download,title);value.requestUserAgent=ua;value.cookie=firstNonEmpty(cookies.getCookie(download));value.createdAt=System.currentTimeMillis();
          String current=web.getUrl();if(current!=null)importUserVerificationCookie(current,cookies.getCookie(current));
          if(terminal.compareAndSet(false,true)){result.set(value);cleanup.run();}
        });
        web.setWebViewClient(new android.webkit.WebViewClient());
        Runnable poll=new Runnable(){boolean evaluating;String verificationUrl="";
          @Override public void run(){
            if(terminal.get()||evaluating)return;if(System.nanoTime()>=deadline){if(terminal.compareAndSet(false,true))failure.set(new SocketTimeoutException("下载中间页解析超时"));cleanup.run();return;}
            evaluating=true;
            web.evaluateJavascript("(function(){function scan(doc){var a=doc.querySelector('#ddown,#downurl,#submit,#tourl a,#ding a,#ding'),raw=a&&a.getAttribute('href');if(a&&a.id==='submit'&&(!raw||raw.charAt(0)==='#')&&doc.defaultView){var w=doc.defaultView;if(typeof w.vkjxld==='string'&&typeof w.hyggid==='string'){var target=w.vkjxld+w.hyggid+(typeof w.lanosso==='string'?w.lanosso:'');if(/^https?:/i.test(target)){a.href=target;raw=target;}}}if(a&&raw&&raw.charAt(0)!=='#'&&!/^javascript:/i.test(raw)&&/^https?:/i.test(a.href)){var r=a.getBoundingClientRect(),key=doc.URL+'|'+a.href;if(r.width>0&&r.height>0&&doc.__lanzouDownloadClick!==key){doc.__lanzouDownloadClick=key;a.removeAttribute('target');a.click();return true;}}var frames=doc.querySelectorAll('iframe');for(var i=0;i<frames.length;i++)try{if(frames[i].contentDocument&&scan(frames[i].contentDocument))return true;}catch(e){}return false;}var h=document.documentElement?document.documentElement.outerHTML:'';scan(document);return h;})()",answer->{
              evaluating=false;if(terminal.get())return;
              String html="";try{html=new JSONArray('['+answer+']').optString(0);}catch(JSONException ignored){}
              String current=firstNonEmpty(web.getUrl(),url);
              if(isWafChallengePage(html)&&slider.get()==null&&!current.equals(verificationUrl)){
                verificationUrl=current;
                LanzouSliderVerification attempt=new LanzouSliderVerification(web,deadline,passed->{slider.set(null);if(!passed&&!terminal.get()){failure.set(new UserVerificationRequiredException(firstNonEmpty(web.getUrl(),url)));terminal.set(true);cleanup.run();}});slider.set(attempt);attempt.start();
              }
              handler.postDelayed(this,150);
            });
          }
        };
        web.loadUrl(url);handler.postDelayed(poll,150);
      }catch(Exception error){failure.set(error);terminal.set(true);cleanup.run();}
    });
    try{if(!ready.await(Math.max(1,directRemainingMillis(deadline)),TimeUnit.MILLISECONDS)){terminal.set(true);handler.post(cleanup);throw new SocketTimeoutException("下载中间页解析超时");}}
    catch(InterruptedException interrupted){terminal.set(true);handler.post(cleanup);throw interrupted;}
    Exception error=failure.get();if(error!=null)throw error;DirectLink value=result.get();if(value==null)throw new IOException("下载中间页未返回下载事件");return value;
  }

  static int adaptiveBrowserWorkers(){Runtime runtime=Runtime.getRuntime();int processors=Math.max(1,runtime.availableProcessors());long maxMiB=Math.max(64L,runtime.maxMemory()/(1024L*1024L));long usedMiB=Math.max(1L,(runtime.totalMemory()-runtime.freeMemory())/(1024L*1024L));double heapFactor=Math.sqrt(Math.max(1d,(double)(maxMiB-usedMiB)/64d));double pressureFactor=1d/Math.sqrt(1d+Math.max(0,ROUTE_PRESSURE.get()));return Math.max(1,(int)Math.floor(Math.sqrt(processors)*heapFactor*pressureFactor));}
    static boolean acquireBrowserSlot(long deadline)throws Exception{synchronized(BROWSER_RENDER_LOCK){while(browserRenderActive>=adaptiveBrowserWorkers()){if(Thread.currentThread().isInterrupted())throw new InterruptedException("浏览器渲染等待已取消");int wait=deadline==NO_DEADLINE?200:Math.min(200,remainingMillis(deadline));BROWSER_RENDER_LOCK.wait(Math.max(1,wait));}if(Thread.currentThread().isInterrupted())throw new InterruptedException("浏览器渲染等待已取消");browserRenderActive++;return true;}}
  static void releaseBrowserSlot(){synchronized(BROWSER_RENDER_LOCK){if(browserRenderActive>0)browserRenderActive--;BROWSER_RENDER_LOCK.notifyAll();}}
  static int browserWaitMillis(long deadline)throws SocketTimeoutException{int remaining=deadline==NO_DEADLINE?(int)METADATA_BROWSE_TIMEOUT_MS:remainingMillis(deadline);return Math.max(1,Math.min(remaining,(int)METADATA_BROWSE_TIMEOUT_MS));}
  static String browserCacheKey(String url,String userAgent){try{URL u=new URL(url);return origin(u.toString())+'\n'+u.getFile()+(u.getRef()==null?"":"#"+u.getRef())+'\n'+(userAgent==null?"":userAgent);}catch(Exception ignored){return url+'\n'+(userAgent==null?"":userAgent);}}
  static DirectLink copyPage(DirectLink value){if(value==null)return null;DirectLink out=new DirectLink();out.url=value.url;out.html=value.html;out.cookie=value.cookie;out.createdAt=value.createdAt;out.requestUserAgent=value.requestUserAgent;out.browserRendered=value.browserRendered;out.verificationComplete=value.verificationComplete;out.downloadResponse=value.downloadResponse;out.httpStatus=value.httpStatus;return out;}
  static DirectLink cachedBrowserPage(String url,String userAgent){DirectLink cached=BROWSER_PAGE_CACHE.get(browserCacheKey(url,requestUserAgent(url,userAgent)));return cached!=null&&System.currentTimeMillis()-cached.createdAt<SESSION_TTL_MS&&verifiedBrowserUseful(cached,false)?copyPage(cached):null;}
  static DirectLink renderedPageIfUseful(DirectLink nativePage,String requestUrl,String requestCookie,long deadline,String userAgent)throws Exception{
    if(browserUseful(nativePage))return nativePage;
    if(!configuredWebViewFallbackEnabled)return nativePage;
    String url=requestUrl==null||requestUrl.trim().isEmpty()?nativePage==null?"":nativePage.url:requestUrl.trim();
    String[] candidates=browserFallbackUserAgents(userAgent);
    for(String candidate:candidates){DirectLink cached=cachedBrowserPage(url,candidate);if(cached!=null)return cached;}
    if(SEARCH_CONNECTIONS.get()!=null)return nativePage;
    for(String candidate:candidates){
      try{checkDeadline(deadline);DirectLink rendered=renderBrowserPage(url,requestCookie,deadline,candidate);if(browserUseful(rendered))return rendered;}
      catch(InterruptedException interrupted){throw interrupted;}
      catch(Exception error){if(deadline!=NO_DEADLINE&&System.nanoTime()>=deadline)break;}
    }
    return nativePage;
  }
  static String[] browserFallbackUserAgents(String requested){String exact=PROBE_EXACT_USER_AGENT.get();if(exact!=null&&!exact.isEmpty())return new String[]{exact};LinkedHashSet<String> values=new LinkedHashSet<>();values.add(requested==null||requested.trim().isEmpty()?configuredUserAgent(currentUaScope()):requested.trim());for(byte ua:learnedFirstUaCandidates(currentUaScope(),UA_UNKNOWN))values.add(userAgent(ua));return values.toArray(new String[0]);}
  static DirectLink renderBrowserPage(String url,String requestCookie,long deadline,String userAgent)throws Exception{
    DirectLink cached=cachedBrowserPage(url,userAgent);if(cached!=null)return cached;
    if(SEARCH_CONNECTIONS.get()!=null)throw new IOException("全源搜索不启动 WebView");
    Context ctx=appContext;if(ctx==null||url==null||url.trim().isEmpty())throw new IOException("WebView 上下文不可用");
    acquireBrowserSlot(deadline);
    final CountDownLatch latch=new CountDownLatch(1);
    final java.util.concurrent.atomic.AtomicReference<DirectLink> result=new java.util.concurrent.atomic.AtomicReference<>();
    final java.util.concurrent.atomic.AtomicReference<Exception> failure=new java.util.concurrent.atomic.AtomicReference<>();
    final java.util.concurrent.atomic.AtomicReference<android.webkit.WebView> holder=new java.util.concurrent.atomic.AtomicReference<>();
    final java.util.concurrent.atomic.AtomicReference<LanzouSliderVerification> slider=new java.util.concurrent.atomic.AtomicReference<>();
    final java.util.concurrent.atomic.AtomicReference<Runnable> snapshotTask=new java.util.concurrent.atomic.AtomicReference<>();
    final java.util.concurrent.atomic.AtomicBoolean terminal=new java.util.concurrent.atomic.AtomicBoolean(),evaluating=new java.util.concurrent.atomic.AtomicBoolean(),released=new java.util.concurrent.atomic.AtomicBoolean(),verificationAttempted=new java.util.concurrent.atomic.AtomicBoolean();
    final android.os.Handler handler=new android.os.Handler(android.os.Looper.getMainLooper());
    final String target=url.trim(),ua=requestUserAgent(target,userAgent==null||userAgent.isEmpty()?configuredUserAgent(currentUaScope()):userAgent);
    final Runnable cleanup=()->{
      android.webkit.WebView web=holder.getAndSet(null);
      try{LanzouSliderVerification verification=slider.getAndSet(null);if(verification!=null)verification.close();if(web!=null){web.stopLoading();web.destroy();}}
      finally{if(released.compareAndSet(false,true))releaseBrowserSlot();latch.countDown();}
    };
    try{
      handler.post(()->{
        if(terminal.get())return;
        try{
          android.webkit.CookieManager cookies=android.webkit.CookieManager.getInstance();cookies.setAcceptCookie(true);
          if(requestCookie!=null&&!requestCookie.isEmpty())for(Map.Entry<String,String> cookie:cookieValues(requestCookie).entrySet())cookies.setCookie(target,cookie.getKey()+"="+cookie.getValue()+"; Path=/");
          android.webkit.WebView web=new android.webkit.WebView(ctx);holder.set(web);
          android.webkit.WebSettings settings=web.getSettings();settings.setJavaScriptEnabled(true);settings.setDomStorageEnabled(true);settings.setBlockNetworkImage(true);settings.setLoadsImagesAutomatically(false);settings.setCacheMode(android.webkit.WebSettings.LOAD_NO_CACHE);settings.setUserAgentString(ua);
          Runnable snapshot=()->{
            if(terminal.get()||!evaluating.compareAndSet(false,true))return;
            try{
              web.evaluateJavascript("(function(){return document.documentElement?document.documentElement.outerHTML:'';})()",value->{
                if(terminal.get())return;
                boolean verifying=false;
                try{
                  DirectLink page=new DirectLink();page.url=web.getUrl()==null?target:web.getUrl();page.html=value==null?"":new JSONArray('['+value+']').optString(0);page.cookie=firstNonEmpty(cookies.getCookie(page.url));page.createdAt=System.currentTimeMillis();page.requestUserAgent=ua;page.browserRendered=true;page.httpStatus=200;
                  if(isWafChallengePage(page.html)&&verificationAttempted.compareAndSet(false,true)){
                    requireLanzouPage(page.url);
                    LanzouSliderVerification verification=new LanzouSliderVerification(web,deadline,passed->{if(terminal.get())return;if(passed){LanzouCore.DirectLink verified=slider.get().verifiedPage();verified.cookie=firstNonEmpty(cookies.getCookie(verified.url));verified.verificationComplete=true;if(terminal.compareAndSet(false,true)){result.set(verified);BROWSER_PAGE_CACHE.put(browserCacheKey(target,ua),copyPage(verified));cleanup.run();}}else{evaluating.set(false);handler.post(snapshotTask.get());}});
                    slider.set(verification);verification.start();verifying=true;return;
                  }
                  if(terminal.compareAndSet(false,true)){
                    result.set(page);
                    if(browserUseful(page)){
                      String key=browserCacheKey(target,ua);
                      BROWSER_PAGE_CACHE.entrySet().removeIf(entry->System.currentTimeMillis()-entry.getValue().createdAt>=SESSION_TTL_MS);
                      int limit=adaptiveSourceWorkers(0,Integer.MAX_VALUE);
                      while(BROWSER_PAGE_CACHE.size()>=limit){Iterator<String> keys=BROWSER_PAGE_CACHE.keySet().iterator();if(!keys.hasNext())break;BROWSER_PAGE_CACHE.remove(keys.next());}
                      BROWSER_PAGE_CACHE.put(key,copyPage(page));
                    }
                  }
                }catch(Exception error){failure.set(error);terminal.set(true);}
                finally{if(!verifying)cleanup.run();}
              });
            }catch(Exception error){failure.set(error);terminal.set(true);cleanup.run();}
          };
          snapshotTask.set(snapshot);
          web.setWebViewClient(new android.webkit.WebViewClient(){
            @Override public void onPageFinished(android.webkit.WebView view,String loadedUrl){if(!terminal.get()){handler.removeCallbacks(snapshot);handler.postDelayed(snapshot,Math.max(80,Math.min(350,browserWaitMillisUnchecked(deadline)/4)));}}
            @Override public void onReceivedHttpError(android.webkit.WebView view,android.webkit.WebResourceRequest request,android.webkit.WebResourceResponse response){if(request.isForMainFrame()&&terminal.compareAndSet(false,true)){failure.set(new IOException("HTTP "+response.getStatusCode()));cleanup.run();}}
          });
          web.loadUrl(target);
        }catch(Exception error){failure.set(error);terminal.set(true);cleanup.run();}
      });
      if(!latch.await(browserWaitMillis(deadline),TimeUnit.MILLISECONDS))throw new SocketTimeoutException("WebView 渲染超时");
      if(failure.get()!=null)throw failure.get();
      DirectLink page=result.get();if(page==null)throw new IOException("WebView 未返回页面");return page;
    }finally{terminal.set(true);handler.post(cleanup);}
  }
  static int browserWaitMillisUnchecked(long deadline){try{return browserWaitMillis(deadline);}catch(Exception ignored){return 400;}}
  static DirectLink getGuarded(String url,long deadline)throws Exception{return getGuarded(url,deadline,ANDROID_UA);}
  static DirectLink getGuarded(String url,long deadline,String userAgent)throws Exception{return getGuarded(url,deadline,userAgent,"");}
  static DirectLink getGuarded(String url,long deadline,String userAgent,String initialCookie)throws Exception{
    String current=url,cookie=initialCookie==null?"":initialCookie;DirectLink page=null;
    for(int round=0;round<3;round++){
      String requestCookie=mergeCookieHeaders(cookie,userVerificationCookie(current));
      try{page=getPage(current,requestCookie,deadline,userAgent);}
      catch(InterruptedException interrupted){throw interrupted;}
      catch(Exception error){DirectLink rendered=renderedPageIfUseful(null,current,requestCookie,deadline,userAgent);if(rendered==null)throw error;return rendered;}
      String value=acwCookie(page.html);if(value.isEmpty()){rememberSolvedAcwCookies(current,page.cookie);return renderedPageIfUseful(page,current,mergeCookieHeaders(requestCookie,page.cookie),deadline,userAgent);}
      current=page.url==null||page.url.isEmpty()?current:page.url;cookie=mergeCookie(page.cookie,"acw_sc__v2",value);rememberSolvedAcwCookies(current,cookie);
    }
    if(page!=null&&!acwCookie(page.html).isEmpty()){page=renderedPageIfUseful(page,current,page.cookie,deadline,userAgent);if(!browserUseful(page)){IOException challenge=acwChallengeFailure(page==null?"":page.html,page==null?"":page.url,current);if(challenge!=null)throw challenge;throw new IOException("蓝奏 ACW 验证未完成");}}
    return page;
  }
    static void applyChromiumHints(HttpURLConnection c,String userAgent){String ua=userAgent==null?"":userAgent,major=cap(ua,"Chrome/([0-9]+)");if(major.isEmpty())return;boolean mobile=ua.toLowerCase(Locale.ROOT).contains("android")||ua.toLowerCase(Locale.ROOT).contains("mobile");c.setRequestProperty("Sec-CH-UA","\"Chromium\";v=\""+major+"\", \"Google Chrome\";v=\""+major+"\", \"Not)A;Brand\";v=\"24\"");c.setRequestProperty("Sec-CH-UA-Mobile",mobile?"?1":"?0");c.setRequestProperty("Sec-CH-UA-Platform",mobile?"\"Android\"":"\"Windows\"");}
  static void applyNavigationHeaders(HttpURLConnection c,String userAgent){userAgent=requestUserAgent(c.getURL().toString(),userAgent);c.setRequestProperty("User-Agent",userAgent);c.setRequestProperty("Accept","text/html,application/xhtml+xml,application/xml;q=0.9,image/avif,image/webp,image/apng,*/*;q=0.8");c.setRequestProperty("Accept-Language","zh-CN,zh;q=0.9,en-US;q=0.8,en;q=0.7");c.setRequestProperty("Accept-Encoding","gzip, deflate");c.setRequestProperty("Cache-Control","no-cache");c.setRequestProperty("Pragma","no-cache");c.setRequestProperty("DNT","1");c.setRequestProperty("Upgrade-Insecure-Requests","1");c.setRequestProperty("Sec-Fetch-Dest","document");c.setRequestProperty("Sec-Fetch-Mode","navigate");c.setRequestProperty("Sec-Fetch-Site","none");c.setRequestProperty("Sec-Fetch-User","?1");applyChromiumHints(c,userAgent);}
  static void applyAjaxHeaders(HttpURLConnection c,String userAgent){userAgent=requestUserAgent(c.getURL().toString(),userAgent);c.setRequestProperty("User-Agent",userAgent);c.setRequestProperty("Accept","application/json, text/javascript, */*; q=0.01");c.setRequestProperty("Accept-Language","zh-CN,zh;q=0.9,en-US;q=0.8,en;q=0.7");c.setRequestProperty("Accept-Encoding","gzip, deflate");c.setRequestProperty("Cache-Control","no-cache");c.setRequestProperty("Pragma","no-cache");c.setRequestProperty("Sec-Fetch-Dest","empty");c.setRequestProperty("Sec-Fetch-Mode","cors");c.setRequestProperty("Sec-Fetch-Site","same-origin");applyChromiumHints(c,userAgent);}
  static DirectLink getPage(String url,String cookie,long deadline,String userAgent)throws Exception{
    try{return getPage(url,cookie,deadline,userAgent,false);}
    catch(Exception error){
      if(!lanzouTlsFallbackAllowed(url,error))throw error;
      warnLenientLanzouTls(url,error);
      return getPage(url,cookie,deadline,userAgent,true);
    }
  }
  static DirectLink getPage(String url,String cookie,long deadline,String userAgent,boolean lenientTls)throws Exception{
    java.net.CookieManager jar=new java.net.CookieManager(null,CookiePolicy.ACCEPT_ORIGINAL_SERVER);
    URI initial=new URI(url);
    if(cookie!=null&&!cookie.isEmpty()){
      List<String> seeds=new ArrayList<>();for(Map.Entry<String,String> value:cookieValues(cookie).entrySet())seeds.add(value.getKey()+"="+value.getValue()+"; Path=/");
      jar.put(initial,Collections.singletonMap("Set-Cookie",seeds));
    }
    String current=url,referer="";
    for(int redirects=0;redirects<=8;redirects++){
      checkHttpCancelled();checkDeadline(deadline);
      URI uri=new URI(current);HttpURLConnection c=openHttp(uri.toURL(),lenientTls);trackConnection(c,true);
      try{
        c.setInstanceFollowRedirects(false);applyTimeouts(c,deadline);applyNavigationHeaders(c,userAgent);
        if(!referer.isEmpty()){c.setRequestProperty("Referer",referer);c.setRequestProperty("Sec-Fetch-Site",origin(referer).equals(origin(current))?"same-origin":"cross-site");}
        List<String> stored=jar.get(uri,Collections.emptyMap()).get("Cookie");
        String requestCookie=mergeCookieHeaders(stored==null?"":android.text.TextUtils.join("; ",stored),userVerificationCookie(current));
        if(!requestCookie.isEmpty())c.setRequestProperty("Cookie",requestCookie);
        int status=c.getResponseCode();jar.put(uri,c.getHeaderFields());
        String location=c.getHeaderField("Location");
        if(status>=300&&status<400&&location!=null&&!location.trim().isEmpty()){
          String next=new URL(new URL(current),location.trim()).toString();
          if(!new URL(next).getProtocol().equalsIgnoreCase("https"))throw new IOException("蓝奏线路跳转到了非 HTTPS 地址");
          referer=current;current=next;continue;
        }
        if(status==429||status==503)throw new PageRateLimitedException("HTTP "+status);
        DirectLink page=new DirectLink();page.url=current;page.httpStatus=status;page.requestUserAgent=userAgent;page.createdAt=System.currentTimeMillis();
        stored=jar.get(uri,Collections.emptyMap()).get("Cookie");page.cookie=mergeCookieHeaders(stored==null?"":android.text.TextUtils.join("; ",stored),userVerificationCookie(current));
        page.html=body(c);checkHttpCancelled();String navigation=LanzouPageNavigation.redirect(page.html,current);if(!navigation.isEmpty()){requireLanzouPage(navigation);referer=current;current=navigation;continue;}return page;
      }finally{trackConnection(c,false);}
    }
    throw new IOException("蓝奏重定向次数过多");
  }
  static String post(String url,Map<String,String> data,DirectLink page,long deadline)throws Exception{return post(url,data,page,deadline,ANDROID_UA);}
                static{try{System.setProperty("http.keepAlive","true");System.setProperty("http.maxConnections",String.valueOf(Math.max(1,adaptiveNetworkWorkers(Integer.MAX_VALUE))));}catch(Throwable ignored){}}
    static String post(String url,Map<String,String> data,DirectLink page,long deadline,String userAgent)throws Exception{
    try{return post(url,data,page,deadline,userAgent,false);}
    catch(Exception error){
      if(!lanzouTlsFallbackAllowed(url,error))throw error;
      warnLenientLanzouTls(url,error);
      return post(url,data,page,deadline,userAgent,true);
    }
  }
    static String post(String url,Map<String,String> data,DirectLink page,long deadline,String userAgent,boolean lenientTls)throws Exception{synchronized(page){String lastChallenge="";for(int round=0;round<3;round++){if(Thread.currentThread().isInterrupted())throw new InterruptedException();checkDeadline(deadline);HttpURLConnection c=openHttp(new URL(url),lenientTls);trackConnection(c,true);try{applyTimeouts(c,deadline);c.setInstanceFollowRedirects(false);c.setDoOutput(true);c.setRequestMethod("POST");applyAjaxHeaders(c,firstNonEmpty(page.requestUserAgent,userAgent));c.setRequestProperty("Referer",page.url);c.setRequestProperty("Origin",origin(page.url));if(!page.cookie.isEmpty())c.setRequestProperty("Cookie",page.cookie);c.setRequestProperty("X-Requested-With","XMLHttpRequest");c.setRequestProperty("Content-Type","application/x-www-form-urlencoded; charset=UTF-8");byte[] b=form(data).getBytes(StandardCharsets.UTF_8);c.setFixedLengthStreamingMode(b.length);try(OutputStream o=c.getOutputStream()){o.write(b);}int status=c.getResponseCode();page.cookie=captureCookies(page.cookie,c);String result=body(c),value=acwCookie(result);lastChallenge=result;if(!value.isEmpty()){page.cookie=mergeCookie(page.cookie,"acw_sc__v2",value);continue;}if(isWafChallengePage(result))throw new UserVerificationRequiredException(page.url);if(status==429||status==503)throw new PageRateLimitedException("HTTP "+status);if(status>=300)throw new IOException("目录接口 HTTP "+status);return result;}finally{trackConnection(c,false);}}IOException challenge=acwChallengeFailure(lastChallenge,page.url,url);if(challenge!=null)throw challenge;throw new IOException("蓝奏 ACW 验证未完成");}}
  static void trackConnection(HttpURLConnection connection,boolean open){ConnectionTracker tracker=SEARCH_CONNECTIONS.get();if(tracker!=null){if(open)tracker.opened(connection);else tracker.closed(connection);}}
  static void checkHttpCancelled()throws InterruptedException{ConnectionTracker tracker=SEARCH_CONNECTIONS.get();if(Thread.currentThread().isInterrupted()||tracker!=null&&tracker.isCancelled())throw new InterruptedException();}
  static String captureCookies(String existing,HttpURLConnection connection){LinkedHashMap<String,String> values=cookieValues(existing);mergeSetCookies(values,connection.getHeaderFields());return cookieHeader(values);}
  static String mergeCookie(String existing,String name,String value){LinkedHashMap<String,String> values=cookieValues(existing);values.put(name,value);return cookieHeader(values);}
    static LinkedHashMap<String,String> cookieValues(String header){LinkedHashMap<String,String> values=new LinkedHashMap<>();if(header==null||header.isEmpty())return values;for(int start=0;start<header.length();){int end=header.indexOf(';',start);if(end<0)end=header.length();int split=header.indexOf('=',start);if(split>start&&split<end)values.put(header.substring(start,split).trim(),header.substring(split+1,end).trim());start=end+1;}return values;}
    static void mergeSetCookies(Map<String,String> values,Map<String,List<String>> headers){for(Map.Entry<String,List<String>> header:headers.entrySet())if(header.getKey()!=null&&header.getKey().equalsIgnoreCase("Set-Cookie"))for(String raw:header.getValue()){int end=raw.indexOf(';');if(end<0)end=raw.length();int split=raw.indexOf('=');if(split>0&&split<end)values.put(raw.substring(0,split).trim(),raw.substring(split+1,end).trim());}}
  static String cookieHeader(Map<String,String> values){StringBuilder out=new StringBuilder();for(Map.Entry<String,String> value:values.entrySet()){if(out.length()>0)out.append("; ");out.append(value.getKey()).append('=').append(value.getValue());}return out.toString();}
  static String acwCookie(String html){String arg=cap(html,"arg1\\s*=\\s*['\"]([A-Fa-f0-9]{40})['\"]");if(arg.isEmpty())return"";String digits="0123456789abcdef";StringBuilder value=new StringBuilder(40);for(int i=0;i<40;i+=2){int mixed=((Character.digit(arg.charAt(POS[i]-1),16)<<4)|Character.digit(arg.charAt(POS[i+1]-1),16))^((Character.digit(MASK.charAt(i),16)<<4)|Character.digit(MASK.charAt(i+1),16));value.append(digits.charAt(mixed>>>4)).append(digits.charAt(mixed&15));}return value.toString();}
  static void applyTimeouts(HttpURLConnection c,long deadline)throws SocketTimeoutException{if(deadline==NO_DEADLINE){c.setConnectTimeout(12000);c.setReadTimeout(25000);return;}int remaining=remainingMillis(deadline);int connect=Math.max(1,Math.min(4000,remaining/3));c.setConnectTimeout(connect);c.setReadTimeout(Math.max(1,remaining-connect));}
  static int remainingMillis(long deadline)throws SocketTimeoutException{long nanos=deadline-System.nanoTime();if(nanos<=0)throw new SocketTimeoutException("蓝奏源搜索超时");return(int)Math.min(Integer.MAX_VALUE,Math.max(1L,TimeUnit.NANOSECONDS.toMillis(nanos)));}
  static void checkDeadline(long deadline)throws SocketTimeoutException{if(deadline!=NO_DEADLINE)remainingMillis(deadline);}
    static InputStream decodedResponseStream(HttpURLConnection connection,InputStream raw)throws Exception{String encoding=connection.getContentEncoding();if(encoding==null)return raw;String value=encoding.trim().toLowerCase(Locale.ROOT);if(value.contains("gzip"))return new GZIPInputStream(raw);if(value.contains("deflate"))return new InflaterInputStream(raw);return raw;}
    static String body(HttpURLConnection c)throws Exception{InputStream raw=c.getResponseCode()<400?c.getInputStream():c.getErrorStream();if(raw==null)return"";try(InputStream in=decodedResponseStream(c,raw)){long declared=c.getContentLengthLong();int initial=declared>0&&declared<1024L*1024L?(int)declared:8192;ByteArrayOutputStream o=new ByteArrayOutputStream(Math.max(1024,initial));byte[] b=new byte[8192];for(int n;(n=in.read(b))>0;)o.write(b,0,n);return o.toString("UTF-8");}}
  static String form(Map<String,String> m)throws Exception{StringBuilder b=new StringBuilder();for(Map.Entry<String,String>e:m.entrySet()){if(b.length()>0)b.append('&');b.append(URLEncoder.encode(e.getKey(),"UTF-8")).append('=').append(URLEncoder.encode(e.getValue(),"UTF-8"));}return b.toString();}
  /**
   * Collects the directory listing fields the share page hands to /filemoreajax.php. Besides plain literals the page may
   * wrap the proof values (t/k) in helper functions, so literal-returning helpers are resolved by name; an unresolved
   * expression is left out instead of being guessed.
   */
  static Map<String,String> formValues(String h){return formValues(h,"");}
  static Map<String,String> formValues(String h,String requestUrl){
    Map<String,String> out=new LinkedHashMap<>(),vars=new HashMap<>();
    Matcher vm=parsePattern("(?s)\\b(?:var|let|const)\\s+([A-Za-z_$][A-Za-z0-9_$]*)\\s*=\\s*(['\"])(.*?)\\2").matcher(h);while(vm.find())vars.put(vm.group(1),vm.group(3));
    Matcher am=parsePattern("(?s)(?:^|[;{,}])\\s*([A-Za-z_$][A-Za-z0-9_$]*)\\s*=\\s*(['\"])(.*?)\\2").matcher(h);while(am.find())vars.putIfAbsent(am.group(1),am.group(3));
    Matcher fm=parsePattern("(?is)(?:function\\s+([A-Za-z_][A-Za-z0-9_]*)\\s*\\([^)]*\\)\\s*\\{|([A-Za-z_][A-Za-z0-9_]*)\\s*=\\s*function\\s*\\([^)]*\\)\\s*\\{)[^{}]{0,400}?return\\s*['\"]([^'\"]+)['\"]").matcher(h);while(fm.find())vars.putIfAbsent(firstNonEmpty(fm.group(1),fm.group(2)),fm.group(3));
    String scope=ajaxDataScope(h,requestUrl);
    if(scope.isEmpty())scope=h;
    String fields="(?:lx|uid|puid|rep|t|k|up|webfoldersign|ls|vip|pg|pwd|folder_id)";
    collectFormFields(parsePattern("(?s)['\"]?("+fields+")['\"]?\\s*:\\s*(?:'([^']*)'|\"([^\"]*)\"|([A-Za-z_$][A-Za-z0-9_$]*)\\s*(?:\\(\\s*\\))?|(-?[0-9]+))").matcher(scope),vars,out);
    return out;
  }
  /** Restricts request fields to the data object attached to the actual listing endpoint, never an earlier AJAX block. */
  static String ajaxDataScope(String html,String requestUrl){
    String marker=ajaxEndpointTail(requestUrl);
    if(marker.isEmpty())return "";
    Matcher urls=parsePattern("(?is)\\burl\\s*:\\s*(['\"])([^'\"]*(?:filemoreajax|foldermoreajax)\\.php\\?[^'\"]*)\\1").matcher(html);
    int start=-1;
    while(urls.find())if(marker.equals(ajaxEndpointTail(urls.group(2)))){start=urls.end();break;}
    if(start<0)return "";
    int limit=Math.min(html.length(),start+800);
    Matcher data=parsePattern("(?is)\\bdata\\s*:\\s*\\{").matcher(html);data.region(start,limit);
    if(!data.find())return "";
    if(parsePattern("(?is)\\burl\\s*:").matcher(html).region(start,data.start()).find())return "";
    int close=matchingBrace(html,data.end()-1);
    return close>data.end()?html.substring(data.end(),close):"";
  }
  static String ajaxEndpointTail(String value){
    String text=value==null?"":value.trim();
    int file=text.indexOf("filemoreajax.php"),folder=text.indexOf("foldermoreajax.php");
    int at=file<0?folder:folder<0?file:Math.min(file,folder);
    return at<0?"":text.substring(at);
  }
  static int matchingBrace(String value,int open){
    int depth=0;char quote=0;
    for(int i=open;i<value.length();i++){
      char ch=value.charAt(i);
      if(quote!=0){if(ch=='\\')i++;else if(ch==quote)quote=0;continue;}
      if(ch=='\''||ch=='"'){quote=ch;continue;}
      if(ch=='{')depth++;
      else if(ch=='}'&&--depth==0)return i;
    }
    return -1;
  }
  /** Known listing fields only; CSS/text pairs such as "font:..." never enter the request. */
  static void collectFormFields(Matcher fields,Map<String,String> vars,Map<String,String> out){
    while(fields.find()){
      String literal=fields.group(2)!=null?fields.group(2):fields.group(3);
      String name=fields.group(4),number=fields.group(5);
      String value=literal!=null?literal:name!=null?vars.getOrDefault(name,""):number==null?"":number;
      if(!value.isEmpty())out.putIfAbsent(fields.group(1),value);
    }
  }

  static final class NetworkGovernor {
    private final Object monitor=new Object();
    private final java.util.function.IntSupplier capacity;
    private int foregroundWaiting,backgroundWaiting,foregroundRunning,backgroundRunning;
    private boolean foregroundTurn=true;
    NetworkGovernor(){this(()->adaptiveNetworkWorkers(Integer.MAX_VALUE));}
    NetworkGovernor(java.util.function.IntSupplier capacity){this.capacity=capacity;}
    final class Lease implements AutoCloseable {
      private final boolean foreground;private boolean released;
      Lease(boolean foreground){this.foreground=foreground;}
      @Override public void close(){synchronized(monitor){if(released)return;released=true;if(foreground)foregroundRunning--;else backgroundRunning--;monitor.notifyAll();}}
    }
    Lease acquireForeground(BooleanSupplier cancelled)throws InterruptedException {return acquireForeground(cancelled,0);}
    Lease acquireForeground(BooleanSupplier cancelled,int requested)throws InterruptedException {
      synchronized(monitor){foregroundWaiting++;try{
        while(!cancelled.getAsBoolean()){
          int automatic=capacity.getAsInt(),budget=requested>0?Math.max(automatic,requested):automatic,reserved=backgroundWaiting>0?Math.max(1,(int)Math.floor(Math.sqrt(budget))):0;
          boolean available=foregroundRunning+backgroundRunning<budget;
          boolean fair=requested>0||(budget==1?(backgroundWaiting==0||foregroundTurn):(backgroundWaiting==0||foregroundRunning<budget-reserved));
          if(available&&fair){foregroundRunning++;foregroundTurn=false;return new Lease(true);}
          monitor.wait(50L);
        }
        throw new InterruptedException();
      }finally{foregroundWaiting--;monitor.notifyAll();}}
    }
    Lease acquireBackground(BooleanSupplier cancelled)throws InterruptedException {
      synchronized(monitor){backgroundWaiting++;try{
        while(!cancelled.getAsBoolean()){
          int budget=capacity.getAsInt(),share=Math.max(1,(int)Math.floor(Math.sqrt(budget)));
          boolean available=foregroundRunning+backgroundRunning<budget;
          boolean fair=budget==1?(foregroundWaiting==0||!foregroundTurn):(foregroundWaiting==0||backgroundRunning<share);
          int limit=foregroundWaiting+foregroundRunning>0?Math.max(1,budget-share):budget;
          if(available&&fair&&backgroundRunning<limit){backgroundRunning++;foregroundTurn=true;return new Lease(false);}
          monitor.wait(50L);
        }
        throw new InterruptedException();
      }finally{backgroundWaiting--;monitor.notifyAll();}}
    }
    int[] snapshot(){synchronized(monitor){return new int[]{foregroundRunning,backgroundRunning,foregroundWaiting,backgroundWaiting,capacity.getAsInt()};}}
  }
}
