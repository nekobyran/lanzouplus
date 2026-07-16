package cc.nkbr.lanzouplus;

import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import org.json.*;

/** Minimal, blocking GitHub Release client. Call {@link #check(String)} off the UI thread. */
final class UpdateClient {
  static final String ASSET_NAME="LanzouPlus.apk";
  private static final String GITHUB_LATEST="https://api.github.com/repos/nekobyran/lanzouplus/releases/latest";
  private static final String SITE_LATEST="https://lanzouplus.nkbr.cc/latest.json";
  private static final String SITE_APK="https://lanzouplus.nkbr.cc/download/"+ASSET_NAME;
  private static final int JSON_LIMIT=256*1024;

  static final class UpdateInfo {
    final String version,body,browserDownloadUrl,mirrorUrl,digest;
    final long size;
    final boolean preferMirror;
    UpdateInfo(String version,String body,String browserDownloadUrl,String mirrorUrl,String digest,long size,boolean preferMirror){this.version=version;this.body=body;this.browserDownloadUrl=browserDownloadUrl;this.mirrorUrl=mirrorUrl;this.digest=digest;this.size=size;this.preferMirror=preferMirror;}
    String primaryUrl(){return preferMirror?mirrorUrl:browserDownloadUrl;}
    String fallbackUrl(){return preferMirror?browserDownloadUrl:mirrorUrl;}
  }

  /** Returns null when the latest stable release is not newer than currentVersion. */
  static UpdateInfo check(String currentVersion)throws IOException{
    long[] current=parseVersion(currentVersion);
    boolean china="CN".equalsIgnoreCase(Locale.getDefault().getCountry());
    String[] endpoints=china?new String[]{SITE_LATEST,GITHUB_LATEST}:new String[]{GITHUB_LATEST,SITE_LATEST};
    IOException first=null;
    for(String endpoint:endpoints)try{return parse(fetch(endpoint),current,SITE_LATEST.equals(endpoint));}catch(IOException error){if(first==null)first=error;}
    throw new IOException("无法获取更新信息",first);
  }

  private static UpdateInfo parse(JSONObject release,long[] current,boolean preferMirror)throws IOException{
    if(release.optBoolean("draft")||release.optBoolean("prerelease"))throw new IOException("更新信息不是正式版本");
    String rawTag=release.optString("tag_name",release.optString("version","")).trim();
    long[] latest=parseVersion(rawTag);
    if(compare(latest,current)<=0)return null;
    JSONArray assets=release.optJSONArray("assets");
    if(assets==null)throw new IOException("更新信息缺少安装包");
    JSONObject asset=null;
    for(int i=0;i<assets.length();i++){
      JSONObject candidate=assets.optJSONObject(i);
      if(candidate==null||!ASSET_NAME.equals(candidate.optString("name"))||!"uploaded".equals(candidate.optString("state","uploaded")))continue;
      if(asset!=null)throw new IOException("更新安装包不唯一");
      asset=candidate;
    }
    if(asset==null)throw new IOException("更新信息缺少指定安装包");
    long size=asset.optLong("size",-1);
    if(size<=0)throw new IOException("更新安装包大小无效");
    String digest=asset.optString("digest",release.optString("digest","")).toLowerCase(Locale.ROOT);
    if(!digest.matches("sha256:[0-9a-f]{64}"))throw new IOException("更新安装包摘要无效");
    String github=asset.optString("browser_download_url",release.optString("browser_download_url","")).trim();
    String mirror=asset.optString("mirror_url",release.optString("mirror_url",SITE_APK)).trim();
    String version=normalizeVersion(rawTag);
    requireGithubAsset(github,rawTag);
    requireMirrorAsset(mirror);
    return new UpdateInfo(version,release.optString("body","").trim(),github,mirror,digest,size,preferMirror);
  }

  private static JSONObject fetch(String endpoint)throws IOException{
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

  private static long[] parseVersion(String value)throws IOException{
    String normalized=normalizeVersion(value);
    if(!normalized.matches("(?:0|[1-9][0-9]*)\\.(?:0|[1-9][0-9]*)\\.(?:0|[1-9][0-9]*)"))throw new IOException("版本号格式无效");
    String[] parts=normalized.split("\\.");long[] out=new long[3];
    try{for(int i=0;i<3;i++)out[i]=Long.parseLong(parts[i]);}catch(NumberFormatException error){throw new IOException("版本号超出范围",error);}
    return out;
  }

  private static String normalizeVersion(String value){String out=value==null?"":value.trim();return out.startsWith("v")?out.substring(1):out;}
  private static int compare(long[] left,long[] right){for(int i=0;i<3;i++){int value=Long.compare(left[i],right[i]);if(value!=0)return value;}return 0;}
  private static boolean isRedirect(int code){return code==301||code==302||code==303||code==307||code==308;}
  private static boolean defaultHttpsPort(URL url){return url.getPort()==-1||url.getPort()==443;}

  private static void requireGithubAsset(String value,String tag)throws IOException{
    try{
      URL url=new URL(value);String expected="/nekobyran/lanzouplus/releases/download/"+tag+"/"+ASSET_NAME;
      if(!"https".equalsIgnoreCase(url.getProtocol())||!"github.com".equalsIgnoreCase(url.getHost())||url.getUserInfo()!=null||!defaultHttpsPort(url)||!expected.equals(url.getPath()))throw new IOException("GitHub 安装包地址不受信任");
    }catch(IOException error){throw error;}catch(Exception error){throw new IOException("GitHub 安装包地址无效",error);}
  }

  private static void requireMirrorAsset(String value)throws IOException{
    try{
      URL url=new URL(value);
      if(!"https".equalsIgnoreCase(url.getProtocol())||!"lanzouplus.nkbr.cc".equalsIgnoreCase(url.getHost())||url.getUserInfo()!=null||!defaultHttpsPort(url)||!url.getPath().endsWith('/'+ASSET_NAME))throw new IOException("镜像安装包地址不受信任");
    }catch(IOException error){throw error;}catch(Exception error){throw new IOException("镜像安装包地址无效",error);}
  }

  /** Redirect allowlist used by SegmentDownloader's direct update entry point. */
  static boolean isAllowedDownloadUrl(URL url){
    if(url==null||!"https".equalsIgnoreCase(url.getProtocol())||url.getUserInfo()!=null||!defaultHttpsPort(url))return false;
    String host=url.getHost().toLowerCase(Locale.ROOT);
    return host.equals("github.com")||host.equals("githubusercontent.com")||host.endsWith(".githubusercontent.com")||host.equals("lanzouplus.nkbr.cc");
  }
}
