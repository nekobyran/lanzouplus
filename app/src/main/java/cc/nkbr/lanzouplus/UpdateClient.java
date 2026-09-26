package cc.nkbr.lanzouplus;

import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import org.json.*;

/** Minimal, blocking release client for LanzouPlus. Call check off the UI thread. */
final class UpdateClient {
  static final String ASSET_NAME="LanzouPlus.apk";
  private static final String GITHUB_LATEST="https://api.github.com/repos/nekobyran/lanzouplus/releases/latest";
  private static final String SITE_LATEST="https://lanzouplus.nkbr.cc/latest.json";
  private static final int JSON_LIMIT=256*1024;
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
      for(String candidate:downloadUrls)if(!candidate.equals(current))return candidate;
      return "";
    }
  }

  static UpdateInfo check(String currentVersion)throws IOException{
    long[] current=parseVersion(currentVersion);
    boolean preferMirror=preferMirrorForLocale();
    String[] endpoints=preferMirror?new String[]{SITE_LATEST,GITHUB_LATEST}:new String[]{GITHUB_LATEST,SITE_LATEST};
    IOException first=null;UpdateInfo found=null;
    for(String endpoint:endpoints)try{
      UpdateInfo info=parse(fetch(endpoint),current,SITE_LATEST.equals(endpoint),preferMirror,ASSET_NAME);
      if(info!=null&&(found==null||compare(parseVersion(info.version),parseVersion(found.version))>0))found=info;
    }catch(IOException error){if(first==null)first=error;}
    if(found!=null)return found;
    if(first!=null)throw first;
    return null;
  }

  static UpdateInfo parse(JSONObject release,long[] current,boolean fromSite,boolean preferMirror,String assetName)throws IOException{
    if(release.optBoolean("draft")||release.optBoolean("prerelease"))throw new IOException("更新信息不是正式版本");
    String rawTag=release.optString("tag_name",release.optString("version","")).trim();
    long[] latest=parseVersion(rawTag);
    if(compare(latest,current)<=0)return null;
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
    String github=asset.optString("browser_download_url",release.optString("browser_download_url","")).trim();
    String mirror=asset.optString("mirror_url",release.optString("mirror_url","")).trim();
    String version=normalizeVersion(rawTag);
    requireGithubAsset(github,rawTag,assetName);
    if(mirror.isEmpty())mirror=fromSite?release.optString("download_url","").trim():githubMirrorAsset(github);
    if(!mirror.isEmpty())requireMirrorAsset(mirror,github,assetName);
    return new UpdateInfo(version,release.optString("body","").trim(),github,mirror,size,preferMirror,assetName);
  }

  static JSONObject fetch(String endpoint)throws IOException{
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
        if(length>JSON_LIMIT)throw new IOException("更新信息过大");
        try(InputStream input=connection.getInputStream();ByteArrayOutputStream output=new ByteArrayOutputStream(length>0?(int)length:4096)){
          byte[] buffer=new byte[4096];int total=0;
          for(int count;(count=input.read(buffer))>0;){total+=count;if(total>JSON_LIMIT)throw new IOException("更新信息过大");output.write(buffer,0,count);}
          try{return new JSONObject(new String(output.toByteArray(),StandardCharsets.UTF_8));}catch(JSONException error){throw new IOException("更新信息格式无效",error);}
        }
      }finally{connection.disconnect();}
    }
    throw new IOException("更新地址跳转过多");
  }

  static long[] parseVersion(String value)throws IOException{
    String normalized=normalizeVersion(value);
    if(!normalized.matches("(?:0|[1-9][0-9]*)\\.(?:0|[1-9][0-9]*)\\.(?:0|[1-9][0-9]*)"))throw new IOException("版本号格式无效");
    String[] parts=normalized.split("\\.");long[] out=new long[3];
    try{for(int i=0;i<3;i++)out[i]=Long.parseLong(parts[i]);}catch(NumberFormatException error){throw new IOException("版本号超出范围",error);}
    return out;
  }

  static String normalizeVersion(String value){String out=value==null?"":value.trim();return out.startsWith("v")?out.substring(1):out;}
  static int compare(long[] left,long[] right){for(int i=0;i<3;i++){int value=Long.compare(left[i],right[i]);if(value!=0)return value;}return 0;}
  static boolean isRedirect(int code){return code==301||code==302||code==303||code==307||code==308;}
  static boolean defaultHttpsPort(URL url){return url.getPort()==-1||url.getPort()==443;}
  static boolean preferMirrorForLocale(){Locale locale=Locale.getDefault();String country=locale.getCountry(),language=locale.getLanguage();return "zh".equalsIgnoreCase(language)||"CN".equalsIgnoreCase(country)||"HK".equalsIgnoreCase(country)||"MO".equalsIgnoreCase(country)||"TW".equalsIgnoreCase(country)||"SG".equalsIgnoreCase(country);}

  static String githubMirrorAsset(String github){
    String value=github==null?"":github.trim();
    if(value.isEmpty())return "";
    int index=Math.floorMod(value.hashCode(),GITHUB_MIRROR_PREFIXES.length);
    return GITHUB_MIRROR_PREFIXES[index]+value;
  }

  private static String[] orderedDownloadUrls(String github,String mirror,boolean preferMirror){
    ArrayList<String> urls=new ArrayList<>();
    if(preferMirror&&!isBlank(mirror))urls.add(mirror.trim());
    if(!isBlank(github)&&!urls.contains(github.trim()))urls.add(github.trim());
    if(!preferMirror&&!isBlank(mirror)&&!urls.contains(mirror.trim()))urls.add(mirror.trim());
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
      if(host.equals("lanzouplus.nkbr.cc"))return;
      if(GITHUB_MIRROR_HOSTS.contains(host)&&!isBlank(github)&&value.contains(github))return;
      throw new IOException("镜像安装包地址不受信任");
    }catch(IOException error){throw error;}catch(Exception error){throw new IOException("镜像安装包地址无效",error);}
  }

  static boolean isAllowedDownloadUrl(URL url){
    if(url==null||!"https".equalsIgnoreCase(url.getProtocol())||url.getUserInfo()!=null||!defaultHttpsPort(url))return false;
    String host=url.getHost().toLowerCase(Locale.ROOT);
    return host.equals("github.com")||host.equals("githubusercontent.com")||host.endsWith(".githubusercontent.com")||host.equals("lanzouplus.nkbr.cc")||GITHUB_MIRROR_HOSTS.contains(host);
  }
}
