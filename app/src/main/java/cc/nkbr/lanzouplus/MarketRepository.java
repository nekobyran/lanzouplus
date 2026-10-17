package cc.nkbr.lanzouplus;

import java.io.IOException;
import java.math.BigInteger;
import java.net.URI;
import java.util.*;
import org.json.*;

/** Shared metadata contract for GitHub subscriptions and local market projects. */
final class MarketRepository {
  final JSONObject json;
  final String name,title,version,versionMode,publisher,publisherAvatar,description,icon,readme,sources;
  final List<String> categories;

  MarketRepository(JSONObject value)throws Exception{
    if(value.getInt("schema")!=1)throw new IOException("不支持的仓库格式");
    json=new JSONObject(value.toString());
    name=value.getString("name").trim();
    if(!name.matches("[A-Za-z0-9_.-]+"))throw new IOException("仓库名称无效");
    title=value.optString("title",name).trim();version=value.getString("version").trim();
    versionMode=value.getString("versionMode");
    compareVersions(versionMode,version,version);
    publisher=value.optString("publisher").trim();publisherAvatar=value.optString("publisherAvatar").trim();
    description=value.optString("description");icon=value.optString("icon").trim();
    readme=path(value.optString("readme","README.md"));sources=path(value.optString("sources","sources.lzp"));
    categories=SourceCategories.read(value);
    if(!icon.isEmpty()&&!icon.startsWith("https://"))path(icon);
    if(!publisherAvatar.isEmpty()&&!publisherAvatar.startsWith("https://"))path(publisherAvatar);
  }

  static String canonicalUrl(String raw)throws IOException{
    try{
      URI uri=new URI(raw.trim());
      if(!"https".equalsIgnoreCase(uri.getScheme())||!"github.com".equalsIgnoreCase(uri.getHost())||uri.getUserInfo()!=null||uri.getPort()!=-1||uri.getQuery()!=null||uri.getFragment()!=null)throw new IOException("请输入 GitHub 仓库的 HTTPS 链接");
      String path=uri.getPath().replaceAll("/+$","");
      if(path.endsWith(".git"))path=path.substring(0,path.length()-4);
      if(!path.matches("/[A-Za-z0-9-]+/[A-Za-z0-9_.-]+"))throw new IOException("链接必须指向 GitHub 仓库根目录");
      return "https://github.com"+path;
    }catch(java.net.URISyntaxException error){throw new IOException("GitHub 仓库链接无效",error);}
  }

  static boolean githubUrl(String value){try{canonicalUrl(value);return true;}catch(Exception error){return false;}}
  static String path(String value)throws IOException{
    if(value.isEmpty()||value.startsWith("/")||value.contains("\\")||Arrays.asList(value.split("/",-1)).contains("..")||value.contains(":"))throw new IOException("仓库文件路径无效");
    return value;
  }

  void validateSubmission(String githubUrl)throws IOException{
    String actual=canonicalUrl(githubUrl);actual=actual.substring(actual.lastIndexOf('/')+1);
    if(!actual.startsWith("lzpmarket-")||actual.length()==10||!actual.equals(name))throw new IOException("市场上传要求仓库名为 lzpmarket-名称，且与仓库信息一致");
  }

  String assetUrl(String rawRoot,String asset)throws IOException{
    if(asset.isEmpty())return "";
    if(asset.startsWith("https://"))return asset;
    return rawRoot+path(asset);
  }

  String assetUrlUnchecked(String root,String value){try{return assetUrl(root,value);}catch(IOException invalid){throw new IllegalArgumentException(invalid);}}

  static int compareVersions(String mode,String left,String right)throws IOException{
    if("integer".equals(mode)){
      if(!left.matches("[0-9]+")||!right.matches("[0-9]+"))throw new IOException("整数版本号只能包含数字");
      return new BigInteger(left).compareTo(new BigInteger(right));
    }
    if("date".equals(mode)){
      String a=left.replace('-','.').replace('_','.'),b=right.replace('-','.').replace('_','.');
      if(!a.matches("[0-9]{4}\\.[0-9]{2}\\.[0-9]{2}(?:\\.[0-9]+)?")||!b.matches("[0-9]{4}\\.[0-9]{2}\\.[0-9]{2}(?:\\.[0-9]+)?"))throw new IOException("日期版本使用 YYYY.MM.DD 或 YYYY-MM-DD，可追加修订号");
      return compareParts(a.split("\\."),b.split("\\."));
    }
    if(!"semantic".equals(mode))throw new IOException("请选择 semantic、date 或 integer 版本格式");
    String format="[0-9]+\\.[0-9]+\\.[0-9]+(?:-[0-9A-Za-z.-]+)?(?:\\+[0-9A-Za-z.-]+)?";
    if(!left.matches(format)||!right.matches(format))throw new IOException("语义版本使用 1.0.0，可追加 -alpha.1");
    String[] a=left.split("\\+",2)[0].split("-",2),b=right.split("\\+",2)[0].split("-",2);
    int result=compareParts(a[0].split("\\."),b[0].split("\\."));
    if(result!=0)return result;
    if(a.length!=b.length)return a.length==1?1:-1;
    return a.length==1?0:compareParts(a[1].split("\\."),b[1].split("\\."));
  }

  private static int compareParts(String[] left,String[] right){
    for(int i=0;i<Math.min(left.length,right.length);i++){
      boolean a=left[i].matches("[0-9]+"),b=right[i].matches("[0-9]+");
      int result=a&&b?new BigInteger(left[i]).compareTo(new BigInteger(right[i])):a!=b?(a?-1:1):left[i].compareTo(right[i]);
      if(result!=0)return result;
    }
    return Integer.compare(left.length,right.length);
  }
}
