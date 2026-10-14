package cc.nkbr.lanzouplus;

import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import org.json.*;

/** Minimal, blocking release client for LanzouPlus. Call check off the UI thread. */
final class UpdateClient {
  static final String ASSET_NAME="LanzouPlus.apk";

  private static final String GITHUB_RELEASES="https://api.github.com/repos/nekobyran/lanzouplus/releases?per_page=100";
  private static final String SITE_LATEST="https://lanzouplus.nkbr.cc/api/release";
  private static final int JSON_LIMIT=256*1024;
  private static final int GITHUB_JSON_LIMIT=4*1024*1024;
  private static final String[] GITHUB_MIRROR_PREFIXES={"https://gh.llkk.cc/","https://gh-proxy.com/","https://ghfast.top/"};
  private static final Set<String> GITHUB_MIRROR_HOSTS=new HashSet<>(Arrays.asList("gh.llkk.cc","gh-proxy.com","ghfast.top"));

  static final class UpdateInfo {
    final String version,body,browserDownloadUrl,mirrorUrl,assetName;
    final String[] downloadUrls;
    final long size;
    final boolean preferMirror;
    UpdateInfo(String version,String body,String browserDownloadUrl,String mirrorUrl,long size,boolean preferMirror,String assetName){
      this.version=version;this.body=body;this.browserDownloadUrl=browserDownloadUrl;this.mirrorUrl=mirrorUrl;this.size=size;this.preferMirror=preferMirror;this.assetName=assetName;
      this.downloadUrls=orderedDownloadUrls(browserDownloadUrl,mirrorUrl,preferMirror);
    }
    String primaryUrl(){return downloadUrls.length==0?"":downloadUrls[0];}
    String fallbackUrl(){return downloadUrls.length>1?downloadUrls[1]:"";}
    String fallbackUrl(String current){
      if(current==null)current="";
      for(int i=0;i<downloadUrls.length-1;i++)if(downloadUrls[i].equals(current))return downloadUrls[i+1];
      return "";
    }
  }

  static UpdateInfo check(String currentVersion,UpdateChannel channel)throws IOException{
    return checkProduct(currentVersion,SITE_LATEST,ASSET_NAME,channel);
  }

  /**
   * Query the official channel endpoint and the GitHub release list concurrently. The list
   * must be inspected by version and by product asset; its first entry is not always usable.
   */
  static UpdateInfo checkProduct(String currentVersion,String siteLatest,String assetName,UpdateChannel channel)throws IOException{
    ReleaseVersion current=ReleaseVersion.parse(currentVersion);
    boolean preferMirror=preferMirrorForLocale();
    String siteEndpoint=siteLatest+(siteLatest.contains("?")?"&":"?")+"channel="+channel.queryValue();
    String[] endpoints=preferMirror?new String[]{siteEndpoint,GITHUB_RELEASES}:new String[]{GITHUB_RELEASES,siteEndpoint};
    Object[] data=new Object[endpoints.length];
    IOException[] errors=new IOException[endpoints.length];
    Thread[] workers=new Thread[endpoints.length];
    for(int i=0;i<workers.length;i++){
      final int index=i;final String endpoint=endpoints[i];
      workers[i]=new Thread(()->{
        try{data[index]=siteEndpoint.equals(endpoint)?fetch(endpoint):fetchReleaseList(endpoint);}
        catch(IOException error){errors[index]=error;}
        catch(RuntimeException error){errors[index]=new IOException("更新请求失败",error);}
      },"lanzouplus-update-"+index);
      workers[i].setDaemon(true);
      workers[i].start();
    }
    for(Thread worker:workers)try{worker.join();}catch(InterruptedException error){
      Thread.currentThread().interrupt();
      throw new InterruptedIOException("更新检查被中断");
    }
    IOException first=null;UpdateInfo found=null;boolean checked=false;
    for(int i=0;i<endpoints.length;i++){
      if(data[i]==null){if(errors[i]!=null&&first==null)first=errors[i];continue;}
      try{
        UpdateInfo info=siteEndpoint.equals(endpoints[i])
            ?parseSiteRelease((JSONObject)data[i],current,preferMirror,assetName,channel)
            :selectGithubRelease((JSONArray)data[i],current,preferMirror,assetName,channel);
        checked=true;
        if(info!=null&&(found==null||ReleaseVersion.parse(info.version).compareTo(ReleaseVersion.parse(found.version))>0
            ||(ReleaseVersion.parse(info.version).compareTo(ReleaseVersion.parse(found.version))==0&&found.body.isEmpty()&&!info.body.isEmpty())))found=info;
      }catch(IOException|ClassCastException error){if(first==null)first=new IOException("更新信息格式无效",error);}
    }
    if(found!=null)return found;
    if(!checked&&first!=null)throw first;
    return null;
  }

  static UpdateInfo parseSiteRelease(JSONObject data,ReleaseVersion current,boolean preferMirror,String assetName,UpdateChannel channel)throws IOException{
    if(!data.optBoolean("ok"))throw new IOException("官网更新接口不可用");
    if(data.optBoolean("draft"))return null;
    String tag=data.optString("tag","").trim();
    ReleaseVersion latest=ReleaseVersion.parse(tag);
    if(channel==UpdateChannel.STABLE&&(latest.isAlpha()||data.optBoolean("prerelease")))return null;
    if(latest.compareTo(current)<=0)return null;
    JSONObject asset=data.optJSONObject("asset");
    if(asset==null||!assetName.equals(asset.optString("name")))throw new IOException("官网更新信息缺少安装包");
    long size=asset.optLong("size",-1);
    if(size<=0)throw new IOException("官网更新安装包大小无效");
    String github=asset.optString("githubUrl","").trim();
    requireGithubAsset(github,tag,assetName);
    JSONObject mirrorInfo=data.optJSONObject("mirror");
    String status=mirrorInfo==null?"":mirrorInfo.optString("status","");
    String mirror=("local-static".equals(status)||"github-proxy".equals(status)||"github-shared-release".equals(status))?asset.optString("mirrorUrl","").trim():"";
    if(mirror.isEmpty())mirror=githubMirrorAsset(github);
    requireMirrorAsset(mirror,github,assetName);
    return new UpdateInfo(normalizeVersion(tag),data.optString("releaseNotes","").trim(),github,mirror,size,preferMirror,assetName);
  }

  static UpdateInfo selectGithubRelease(JSONArray releases,ReleaseVersion current,boolean preferMirror,String assetName,UpdateChannel channel){
    UpdateInfo selected=null;
    ReleaseVersion selectedVersion=null;
    for(int i=0;i<releases.length();i++){
      JSONObject release=releases.optJSONObject(i);
      if(release==null)continue;
      try{
        UpdateInfo candidate=parseGithubRelease(release,current,preferMirror,assetName,channel);
        if(candidate==null)continue;
        ReleaseVersion candidateVersion=ReleaseVersion.parse(candidate.version);
        if(selectedVersion==null||candidateVersion.compareTo(selectedVersion)>0){
          selected=candidate;
          selectedVersion=candidateVersion;
        }
      }catch(IOException invalidCandidate){
        // One unusable release must not hide a valid release for this product.
      }
    }
    return selected;
  }

  static UpdateInfo parseGithubRelease(JSONObject release,ReleaseVersion current,boolean preferMirror,String assetName,UpdateChannel channel)throws IOException{
    if(release.optBoolean("draft"))return null;
    String rawTag=release.optString("tag_name","").trim();
    ReleaseVersion latest=ReleaseVersion.parse(rawTag);
    if(channel==UpdateChannel.STABLE&&(latest.isAlpha()||release.optBoolean("prerelease")))return null;
    if(latest.compareTo(current)<=0)return null;
    JSONArray assets=release.optJSONArray("assets");
    if(assets==null)throw new IOException("更新信息缺少安装包");
    JSONObject asset=null;
    for(int i=0;i<assets.length();i++){
      JSONObject candidate=assets.optJSONObject(i);
      if(candidate==null||!assetName.equals(candidate.optString("name"))||!"uploaded".equals(candidate.optString("state","uploaded")))continue;
      if(asset!=null)throw new IOException("更新安装包不唯一");
      asset=candidate;
    }
    if(asset==null)throw new IOException("更新信息缺少指定安装包");
    long size=asset.optLong("size",-1);
    if(size<=0)throw new IOException("更新安装包大小无效");
    String github=asset.optString("browser_download_url","").trim();
    requireGithubAsset(github,rawTag,assetName);
    String mirror=githubMirrorAsset(github);
    return new UpdateInfo(normalizeVersion(rawTag),release.optString("body","").trim(),github,mirror,size,preferMirror,assetName);
  }

  static JSONObject fetch(String endpoint)throws IOException{
    Object result=fetchPayload(endpoint,JSON_LIMIT);
    if(!(result instanceof JSONObject))throw new IOException("官网更新信息格式无效");
    return (JSONObject)result;
  }

  static JSONArray fetchReleaseList(String endpoint)throws IOException{
    Object result=fetchPayload(endpoint,GITHUB_JSON_LIMIT);
    if(!(result instanceof JSONArray))throw new IOException("GitHub 发布列表格式无效");
    return (JSONArray)result;
  }

  private static Object fetchPayload(String endpoint,int limit)throws IOException{
    URL current=new URL(endpoint);
    String expectedHost=current.getHost().toLowerCase(Locale.ROOT);
    for(int redirects=0;redirects<4;redirects++){
      if(!"https".equalsIgnoreCase(current.getProtocol())||!expectedHost.equals(current.getHost().toLowerCase(Locale.ROOT))||current.getUserInfo()!=null||!defaultHttpsPort(current))throw new IOException("更新地址不受信任");
      HttpURLConnection connection=(HttpURLConnection)current.openConnection();
      connection.setConnectTimeout(7000);connection.setReadTimeout(10000);connection.setInstanceFollowRedirects(false);
      connection.setRequestProperty("User-Agent","LanzouPlus-Update");connection.setRequestProperty("Accept","application/vnd.github+json, application/json");connection.setRequestProperty("Accept-Encoding","identity");
      try{
        int code=connection.getResponseCode();
        if(isRedirect(code)){
          String location=connection.getHeaderField("Location");
          if(location==null||location.isEmpty())throw new IOException("更新地址跳转无效");
          current=new URL(current,location);continue;
        }
        if(code!=HttpURLConnection.HTTP_OK)throw new IOException("更新请求失败 HTTP "+code);
        long length=connection.getContentLengthLong();
        if(length>limit)throw new IOException("更新信息过大");
        try(InputStream input=connection.getInputStream();ByteArrayOutputStream output=new ByteArrayOutputStream(length>0?(int)length:4096)){
          byte[] buffer=new byte[4096];int total=0;
          for(int count;(count=input.read(buffer))>0;){total+=count;if(total>limit)throw new IOException("更新信息过大");output.write(buffer,0,count);}
          try{return new JSONTokener(new String(output.toByteArray(),StandardCharsets.UTF_8)).nextValue();}
          catch(JSONException error){throw new IOException("更新信息格式无效",error);}
        }
      }finally{connection.disconnect();}
    }
    throw new IOException("更新地址跳转过多");
  }

  static String normalizeVersion(String value){String out=value==null?"":value.trim();return out.startsWith("v")||out.startsWith("V")?out.substring(1):out;}
  static boolean isRedirect(int code){return code==301||code==302||code==303||code==307||code==308;}
  static boolean defaultHttpsPort(URL url){return url.getPort()==-1||url.getPort()==443;}
  static boolean preferMirrorForLocale(){Locale locale=Locale.getDefault();String country=locale.getCountry(),language=locale.getLanguage();return "zh".equalsIgnoreCase(language)||"CN".equalsIgnoreCase(country)||"HK".equalsIgnoreCase(country)||"MO".equalsIgnoreCase(country)||"TW".equalsIgnoreCase(country)||"SG".equalsIgnoreCase(country);}

  static String githubMirrorAsset(String github){
    String value=github==null?"":github.trim();
    if(value.isEmpty())return "";
    return GITHUB_MIRROR_PREFIXES[0]+value;
  }

    private static String[] orderedDownloadUrls(String github,String mirror,boolean preferMirror){
    LinkedHashSet<String> urls=new LinkedHashSet<>();
    String asset=github==null?"":github.trim();
    if(preferMirror&&!isBlank(mirror))urls.add(mirror.trim());
    if(preferMirror&&!asset.isEmpty())for(String prefix:GITHUB_MIRROR_PREFIXES)urls.add(prefix+asset);
    if(!asset.isEmpty())urls.add(asset);
    if(!preferMirror&&!isBlank(mirror))urls.add(mirror.trim());
    if(!preferMirror&&!asset.isEmpty())for(String prefix:GITHUB_MIRROR_PREFIXES)urls.add(prefix+asset);
    return urls.toArray(new String[0]);
  }
  private static boolean isBlank(String value){return value==null||value.trim().isEmpty();}

  static void requireGithubAsset(String value,String tag,String assetName)throws IOException{
    try{
      URL url=new URL(value);String expected="/nekobyran/lanzouplus/releases/download/"+tag+"/"+assetName;
      if(!"https".equalsIgnoreCase(url.getProtocol())||!"github.com".equalsIgnoreCase(url.getHost())||url.getUserInfo()!=null||!defaultHttpsPort(url)||!expected.equals(url.getPath()))throw new IOException("GitHub 安装包地址不受信任");
    }catch(IOException error){throw error;}catch(Exception error){throw new IOException("GitHub 安装包地址无效",error);}
  }

    static void requireMirrorAsset(String value,String github,String assetName)throws IOException{
    try{
      URL url=new URL(value);String host=url.getHost().toLowerCase(Locale.ROOT);
      if(!"https".equalsIgnoreCase(url.getProtocol())||url.getUserInfo()!=null||!defaultHttpsPort(url)||!url.getPath().endsWith('/'+assetName)){
        throw new IOException("镜像安装包地址不受信任");
      }
      if(host.equals(officialMirrorHost(assetName)))return;
      if(GITHUB_MIRROR_HOSTS.contains(host)&&!isBlank(github)&&value.contains(github))return;
      throw new IOException("镜像安装包地址不受信任");
    }catch(IOException error){throw error;}catch(Exception error){throw new IOException("镜像安装包地址无效",error);}
  }

  /** The product's own official mirror host only; one product never accepts the other's mirror. */
  private static String officialMirrorHost(String assetName){return "lanzouplus.nkbr.cc";}

  static boolean isAllowedDownloadUrl(URL url){
    if(url==null||!"https".equalsIgnoreCase(url.getProtocol())||url.getUserInfo()!=null||!defaultHttpsPort(url))return false;
    String host=url.getHost().toLowerCase(Locale.ROOT);
    return host.equals("github.com")||host.equals("githubusercontent.com")||host.endsWith(".githubusercontent.com")||host.equals("lanzouplus.nkbr.cc")||GITHUB_MIRROR_HOSTS.contains(host);
  }
}
