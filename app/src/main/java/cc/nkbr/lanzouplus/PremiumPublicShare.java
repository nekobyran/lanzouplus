package cc.nkbr.lanzouplus;

import org.json.JSONArray;
import org.json.JSONObject;
import java.io.IOException;
import java.net.CookieManager;
import java.net.CookiePolicy;
import java.net.URI;
import java.net.URLDecoder;
import java.net.URLEncoder;
import java.util.LinkedHashMap;
import java.util.Map;

/** Anonymous public premium shares; never reads an account's credentials or cookies. */
final class PremiumPublicShare {
  private static final String API="https://apis.ilanzou.com";
  private static final String DOWNLOAD_API="https://apix.ilanzou.com";
  private static Session cached;

  private PremiumPublicShare(){}
  private static final class Session {
    final String origin,uuid;
    final CookieManager cookies;
    Session(String origin,String uuid,CookieManager cookies){this.origin=origin;this.uuid=uuid;this.cookies=cookies;}
  }

  static boolean matches(String value){
    try{
      URI uri=new URI(value);
      String host=uri.getHost();
      return "https".equalsIgnoreCase(uri.getScheme())
          &&uri.getRawUserInfo()==null&&(uri.getPort()==-1||uri.getPort()==443)
          &&("www.ilanzou.com".equalsIgnoreCase(host)||"ilanzou.com".equalsIgnoreCase(host))
          &&uri.getPath()!=null&&uri.getPath().matches("/s/[A-Za-z0-9]+");
    }catch(Exception error){return false;}
  }

  static Models.Folder browse(String url,String password,int page)throws Exception{
    return browse(url,password,page,API);
  }
  static Models.Folder browse(String url,String password,int page,String origin)throws Exception{
    if(!matches(url))throw new IOException("优享分享地址无效");
    URI uri=new URI(url);
    String share=uri.getPath().substring(3);
    String code=code(uri,password);
    Session session=session(origin);
    JSONObject recommendation=json(session,"POST","/unproved/recommend/list",fields(
        "shareId",share,"type","0","offset","1","limit","60","code",code));
    JSONArray groups=recommendation.optJSONArray("list");
    if(groups==null||groups.length()==0)throw new IOException("优享分享已取消或不存在");
    JSONObject group=groups.optJSONObject(0);
    if(group==null||id(group.opt("userId")).isEmpty())throw new IOException("优享分享缺少用户标识");
    JSONArray files=group.optJSONArray("fileList");
    if(files==null)throw new IOException("优享分享缺少文件列表");
    JSONObject only=files.length()==1?files.optJSONObject(0):null;
    String folderId=selection(uri,"lanzou-folder");
    if(folderId.isEmpty()&&only!=null&&only.optInt("fileType")==2)folderId=id(only.opt("folderId"));
    Models.Folder folder=new Models.Folder();
    folder.url=withCode(uri,code);
    folder.password=code;
    folder.folderId=folderId;
    folder.page=Math.max(1,page);
    folder.title=only==null?"优享分享":itemName(only);
    JSONObject metadata=group.optJSONObject("map");
    if(metadata!=null){folder.publisher=metadata.optString("userName","");folder.avatarUrl=metadata.optString("avatar","");}
    JSONArray listed=files;
    if(!folderId.isEmpty()){
      JSONObject listing=json(session,"POST","/unproved/share/list",fields(
          "shareId",share,"folderId",folderId,"offset",Integer.toString(folder.page),"limit","60","code",code));
      listed=listing.optJSONArray("list");
      if(listed==null)throw new IOException("优享目录缺少文件列表");
      folder.hasMore=listing.has("totalPage")?folder.page<listing.optInt("totalPage"):listed.length()>=60;
      JSONObject listMeta=listing.optJSONObject("map");
      if(listMeta!=null&&!listMeta.optString("folderName","").isEmpty())folder.title=listMeta.optString("folderName");
    }else if(folder.page>1){listed=new JSONArray();}
    for(int i=0;i<listed.length();i++){
      JSONObject raw=listed.optJSONObject(i);
      if(raw==null)throw new IOException("优享目录项目无效");
      boolean isFolder=raw.optInt("fileType")==2;
      String itemId=id(raw.opt(isFolder?"folderId":"fileId"));
      if(itemId.isEmpty())throw new IOException("优享目录项目缺少标识");
      Models.Item item=new Models.Item();
      item.title=itemName(raw);item.folder=isFolder;item.folderId=isFolder?itemId:"";
      String fragment=isFolder?"lanzou-folder="+encode(itemId):
          (folderId.isEmpty()?"":"lanzou-folder="+encode(folderId)+"&")+"lanzou-file="+encode(itemId);
      item.url=folder.url+"#"+fragment;item.shareUrl=item.url;item.password=code;item.source=folder.title;
      if(raw.has("fileSize"))item.size=raw.optString("fileSize","")+" KiB";
      item.time=raw.optString("updTime","");
      item.description=raw.optString(isFolder?"folderDesc":"fileDesc","");
      folder.items.add(item);
    }
    return folder;
  }

  static LanzouCore.DirectLink resolve(String url,String password)throws Exception{
    return resolve(url,password,API);
  }
  static LanzouCore.DirectLink resolve(String url,String password,String origin)throws Exception{
    if(!matches(url))throw new IOException("优享分享地址无效");
    URI uri=new URI(url);
    String selected=selection(uri,"lanzou-file");
    Models.Item found=null;
    int page=1;
    while(true){
      Models.Folder folder=browse(url,password,page,origin);
      if(selected.isEmpty()){
        if(folder.items.size()!=1||folder.items.get(0).folder)throw new IOException("请先选择优享分享中的具体文件");
        found=folder.items.get(0);break;
      }
      for(Models.Item item:folder.items)if(!item.folder&&selected.equals(selection(new URI(item.url),"lanzou-file"))){found=item;break;}
      if(found!=null)break;
      if(!folder.hasMore)throw new IOException("优享目录中未找到选择的文件");
      page++;
    }
    String file=selection(new URI(found.url),"lanzou-file");
    Session session=session(origin);
    long now=System.currentTimeMillis();
    String download=PremiumCloudClient.encryptDownloadPayload(file+"|");
    String auth=PremiumCloudClient.encryptDownloadPayload(file+"|"+now);
    String timestamp=PremiumCloudClient.encryptDownloadPayload(Long.toString(now));
    String endpoint=(API.equals(origin)?DOWNLOAD_API:origin)+"/unproved/file/redirect?"+query(fields(
        "downloadId",download,"enable","0","devType","6","uuid",session.uuid,
        "timestamp",timestamp,"auth",auth,"shareId",uri.getPath().substring(3)));
    PremiumCloudClient.HttpReply response=PremiumCloudClient.rawHttpWithCookies("GET",endpoint,null,null,15000,session.cookies,null,true);
    LanzouCore.DirectLink result=new LanzouCore.DirectLink();
    result.url=PremiumCloudClient.downloadTarget(response);result.fileName=found.title;
    return result;
  }

  private static synchronized Session session(String origin)throws Exception{
    if(cached!=null&&cached.origin.equals(origin))return cached;
    CookieManager cookies=new CookieManager(null,CookiePolicy.ACCEPT_ORIGINAL_SERVER);
    Map<String,String> initial=fields("uuid","","devCode","","devType","6","devModel","Chrome","devVersion","125","appVersion","","extra","2","timestamp",PremiumCloudClient.encryptDownloadPayload(Long.toString(System.currentTimeMillis())));
    PremiumCloudClient.HttpReply reply=PremiumCloudClient.rawHttpWithCookies("GET",origin+"/unproved/getUuid?"+query(initial),null,null,15000,cookies,null,true);
    JSONObject data=readJson(reply);
    String uuid=data.optString("uuid","");
    if(uuid.isEmpty())throw new IOException("优享服务未返回设备标识");
    Session created=new Session(origin,uuid,cookies);
    json(created,"POST","/unproved/buy/vip/list",new LinkedHashMap<>());
    cached=created;
    return created;
  }
  private static JSONObject json(Session session,String method,String path,Map<String,String> values)throws Exception{
    long now=System.currentTimeMillis();
    Map<String,String> params=fields("uuid",session.uuid,"devCode",session.uuid,"devType","6","devModel","Chrome","devVersion","125","appVersion","","extra","2","timestamp",PremiumCloudClient.encryptDownloadPayload(Long.toString(now)));
    params.putAll(values);
    PremiumCloudClient.HttpReply response=PremiumCloudClient.rawHttpWithCookies(method,session.origin+path+"?"+query(params),null,null,15000,session.cookies,null,true);
    return readJson(response);
  }
  private static JSONObject readJson(PremiumCloudClient.HttpReply response)throws Exception{
    if(response.status<200||response.status>=300)throw new IOException("优享服务请求失败 (HTTP "+response.status+")");
    JSONObject root=new JSONObject(response.text);
    if(root.optInt("code",-1)!=200)throw new IOException("优享分享请求失败，请检查分享链接和提取码");
    return root;
  }
  private static String itemName(JSONObject item)throws IOException{
    String name=item.optString(item.optInt("fileType")==2?"folderName":"fileName","");
    if(name.isEmpty())throw new IOException("优享文件缺少名称");return name;
  }
  private static String id(Object value){return value==null||value==JSONObject.NULL?"":String.valueOf(value);}
  private static String code(URI uri,String explicit)throws Exception{
    if(explicit!=null&&!explicit.isEmpty())return explicit;
    Map<String,String> params=parse(uri.getRawQuery());
    String code=params.get("code");if(code==null||code.isEmpty())code=params.get("pwd");
    return code==null?"":code;
  }
  private static String selection(URI uri,String key)throws Exception{
    String value=parse(uri.getRawFragment()).get(key);return value==null?"":value;
  }
  private static String withCode(URI uri,String code)throws Exception{
    Map<String,String> params=parse(uri.getRawQuery());
    params.remove("pwd");params.remove("code");
    if(!code.isEmpty())params.put("code",code);
    String base=uri.getScheme()+"://"+uri.getRawAuthority()+uri.getRawPath();
    String encoded=query(params);return encoded.isEmpty()?base:base+"?"+encoded;
  }
  private static Map<String,String> parse(String value)throws Exception{
    Map<String,String> out=new LinkedHashMap<>();
    if(value!=null&&!value.isEmpty())for(String entry:value.split("&")){
      int index=entry.indexOf('=');
      String key=index<0?entry:entry.substring(0,index);
      String data=index<0?"":entry.substring(index+1);
      out.put(URLDecoder.decode(key,"UTF-8"),URLDecoder.decode(data,"UTF-8"));
    }
    return out;
  }
  private static Map<String,String> fields(String... values){
    Map<String,String> out=new LinkedHashMap<>();for(int i=0;i<values.length;i+=2)out.put(values[i],values[i+1]);return out;
  }
  private static String query(Map<String,String> values)throws Exception{
    StringBuilder result=new StringBuilder();
    for(Map.Entry<String,String> entry:values.entrySet()){
      if(result.length()>0)result.append('&');result.append(encode(entry.getKey())).append('=').append(encode(entry.getValue()));
    }
    return result.toString();
  }
  private static String encode(String value)throws Exception{return URLEncoder.encode(value,"UTF-8");}
}
