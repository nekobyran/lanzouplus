package cc.nkbr.lanzouplus;

import org.json.JSONArray;
import org.json.JSONObject;

import java.net.URLEncoder;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Real recycle-bin protocol core for both LanZou account families.
 *
 * <p>Ports the already landed Rust worker rules one-for-one:
 * {@code LanzouYOU/native/lanzouyou_core/src/session.rs}
 * ({@code recycle_list}, {@code recycle_action}, {@code recycle_empty},
 * {@code recycle_form}), {@code parser.rs} ({@code recycle_items},
 * {@code formhash}, {@code ensure_session_html}) and
 * {@code premium/client.rs} (recycle endpoints).</p>
 *
 * <p>{@link ReadTransport} and its ordinary/premium extensions are the only I/O
 * boundary: each must be an already authenticated HTTP client owned by the
 * matching backend. This class never stores, reads or logs cookies, app tokens
 * or account identifiers.</p>
 *
 * <p>Every mutating method returns only after the server's own success marker
 * was observed. Missing markers raise {@link CloudFilesClient.CloudException};
 * no method fabricates an empty or successful result.</p>
 */
final class CloudRecycleProtocol {
  private CloudRecycleProtocol(){}

  /** Narrow authenticated HTTP boundary. Must not add credentials itself. */
  interface ReadTransport {
    Reply get(String url) throws CloudFilesClient.CloudException;
  }

  /** Ordinary cloud also posts HTML forms. */
  interface OrdinaryTransport extends ReadTransport {
    Reply postForm(String url, Map<String,String> form) throws CloudFilesClient.CloudException;
  }

  /** Premium cloud also posts JSON bodies. */
  interface PremiumTransport extends ReadTransport {
    Reply postJson(String url, JSONObject body) throws CloudFilesClient.CloudException;
  }

  /** Session-bound handle the management UI drives for one account. */
  interface Bin {
    Page list(int page,int limit) throws CloudFilesClient.CloudException;
    ActionResult restore(String id,boolean folder) throws CloudFilesClient.CloudException;
    ActionResult purge(String id,boolean folder) throws CloudFilesClient.CloudException;
    ActionResult empty() throws CloudFilesClient.CloudException;
  }

  static final class Reply {
    final int status;
    final String body;
    Reply(int status,String body){this.status=status;this.body=body==null?"":body;}
  }

  static final class Entry {
    final String id;
    final String name;
    final boolean folder;
    final String size;
    final String modifiedAt;
    final String description;
    Entry(String id,String name,boolean folder,String size,String modifiedAt,String description){
      this.id=id==null?"":id;
      this.name=name==null?"":name;
      this.folder=folder;
      this.size=size==null?"":size;
      this.modifiedAt=modifiedAt==null?"":modifiedAt;
      this.description=description==null?"":description;
    }
  }

  static final class Page {
    final List<Entry> items;
    final int page;
    final boolean hasMore;
    final int total;
    Page(List<Entry> items,int page,boolean hasMore,int total){
      this.items=Collections.unmodifiableList(new ArrayList<>(
          items==null?Collections.<Entry>emptyList():items));
      this.page=page;
      this.hasMore=hasMore;
      this.total=Math.max(0,total);
    }
  }

  static final class ActionResult {
    final String id;
    final boolean folder;
    final boolean purged;
    final boolean restored;
    final boolean emptied;
    ActionResult(String id,boolean folder,boolean purged,boolean restored,boolean emptied){
      this.id=id==null?"":id;this.folder=folder;this.purged=purged;this.restored=restored;
      this.emptied=emptied;
    }
  }

  // ---- ordinary (woozooo pc web session) ---------------------------------

  static Page ordinaryList(OrdinaryTransport transport,String pcOrigin)
      throws CloudFilesClient.CloudException{
    String base=origin(pcOrigin);
    Reply reply=transport.get(base+"/mydisk.php?item=recycle&action=files");
    requireHttpOk(reply,"读取回收站失败");
    String html=reply.body;
    ensureSessionHtml(html);
    List<Entry> items=parseOrdinaryItems(html);
    return new Page(items,1,false,items.size());
  }

  static ActionResult ordinaryRestore(OrdinaryTransport transport,String pcOrigin,String id,
                                     boolean folder)
      throws CloudFilesClient.CloudException{
    return ordinaryAction(transport,pcOrigin,id,folder,false);
  }

  static ActionResult ordinaryPurge(OrdinaryTransport transport,String pcOrigin,String id,
                                   boolean folder)
      throws CloudFilesClient.CloudException{
    return ordinaryAction(transport,pcOrigin,id,folder,true);
  }

  static ActionResult ordinaryEmpty(OrdinaryTransport transport,String pcOrigin)
      throws CloudFilesClient.CloudException{
    ordinaryForm(transport,origin(pcOrigin),"delete_all",null,null);
    return new ActionResult("",false,false,false,true);
  }

  /** Session-bound ordinary bin; the web page has no paging, only page 1. */
  static Bin ordinaryBin(final OrdinaryTransport transport,final String pcOrigin)
      throws CloudFilesClient.CloudException{
    final String base=origin(pcOrigin);
    return new Bin(){
      @Override public Page list(int page,int limit)throws CloudFilesClient.CloudException{
        if(page!=1)throw invalid("普通云回收站只有第 1 页");
        return ordinaryList(transport,base);
      }
      @Override public ActionResult restore(String id,boolean folder)
          throws CloudFilesClient.CloudException{
        return ordinaryRestore(transport,base,id,folder);
      }
      @Override public ActionResult purge(String id,boolean folder)
          throws CloudFilesClient.CloudException{
        return ordinaryPurge(transport,base,id,folder);
      }
      @Override public ActionResult empty()throws CloudFilesClient.CloudException{
        return ordinaryEmpty(transport,base);
      }
    };
  }

  private static ActionResult ordinaryAction(OrdinaryTransport transport,String pcOrigin,String id,
                                             boolean folder,boolean purge)
      throws CloudFilesClient.CloudException{
    String checked=requireId(id);
    String idKey=folder?"folder_id":"file_id";
    String realAction=purge
        ?(folder?"folder_delete_complete":"file_delete_complete")
        :(folder?"folder_restore":"file_restore");
    ordinaryForm(transport,origin(pcOrigin),realAction,idKey,checked);
    return new ActionResult(checked,folder,purge,!purge,false);
  }

  private static void ordinaryForm(OrdinaryTransport transport,String origin,String action,
                                   String idKey,String id)
      throws CloudFilesClient.CloudException{
    StringBuilder first=new StringBuilder(origin)
        .append("/mydisk.php?item=recycle&action=").append(encode(action));
    if(idKey!=null)first.append('&').append(idKey).append('=').append(encode(id));
    Reply page=transport.get(first.toString());
    requireHttpOk(page,"打开回收站操作页失败");
    String html=page.body;
    ensureSessionHtml(html);
    String hash=formhash(html);
    Map<String,String> form=new LinkedHashMap<>();
    form.put("action",action);
    form.put("task",action);
    form.put("formhash",hash);
    if(idKey!=null)form.put(idKey,id);
    Reply response=transport.postForm(origin+"/mydisk.php?item=recycle",form);
    requireHttpOk(response,"回收站操作失败");
    String text=response.body;
    ensureSessionHtml(text);
    boolean success;
    if("delete_all".equals(action)){
      success=text.contains("清空回收站成功")||text.contains("成功");
    }else if(action.contains("restore")){
      success=text.contains("恢复成功")||text.contains("还原成功");
    }else{
      success=text.contains("删除成功")||text.contains("成功");
    }
    if(!success){
      throw new CloudFilesClient.CloudException(CloudFilesClient.ERROR_PROTOCOL,
          "回收站操作未获服务端确认");
    }
  }

  private static List<Entry> parseOrdinaryItems(String html)throws CloudFilesClient.CloudException{
    ensureSessionHtml(html);
    List<Entry> items=new ArrayList<>();
    // Capture the whole anchor text, then strip inline tags and the "..."
    // truncation marker. A greedy [^<]* prefix used to swallow the folder name.
    Pattern folderPattern=Pattern.compile(
        "folder_id=(\\d+)[^>]*>(.*?)</a>.*?<td[^>]*>([^<]*)</td>"
            +".*?<td[^>]*>([^<]*)</td>",Pattern.DOTALL|Pattern.CASE_INSENSITIVE);
    Matcher folders=folderPattern.matcher(html);
    while(folders.find()){
      items.add(new Entry(folders.group(1),folderName(folders.group(2)),true,
          clean(folders.group(3)),clean(folders.group(4)),""));
    }
    Pattern filePattern=Pattern.compile(
        "fl_sel_ids[^>]+value=['\"](\\d+)['\"].*?filetype/([A-Za-z0-9]+)\\.gif.*?/?>\\s*"
            +"([^<]+?)(?:\\.{3})?</a>.*?<td[^>]*>([\\d\\-]+)</td>",
        Pattern.DOTALL|Pattern.CASE_INSENSITIVE);
    Matcher files=filePattern.matcher(html);
    while(files.find()){
      String extension=files.group(2);
      String name=clean(files.group(3));
      String suffix="."+extension;
      if(!name.toLowerCase(java.util.Locale.ROOT).endsWith(suffix.toLowerCase(java.util.Locale.ROOT))){
        name=name+suffix;
      }
      items.add(new Entry(files.group(1),name,false,"",files.group(4),""));
    }
    return items;
  }

  private static void ensureSessionHtml(String html)throws CloudFilesClient.CloudException{
    String text=html==null?"":html;
    String compact=text.replaceAll("\\s+","");
    boolean password=text.contains("type=\"password\"")||text.contains("type='password'")
        ||text.contains("name=\"password\"")||text.contains("name='password'")
        ||text.contains("name=\"passwd\"");
    boolean user=text.contains("name=\"username\"")||text.contains("name='username'")
        ||text.contains("name=\"account\"")||text.contains("name='account'")
        ||text.contains("name=\"loginName\"");
    boolean loginTitle=compact.contains("网盘用户登录")||compact.contains("用户登录")
        ||compact.contains("登录蓝奏云");
    if(loginTitle||(password&&user&&(text.contains("登录")||text.contains("登陆")))){
      throw new CloudFilesClient.CloudException(CloudFilesClient.ERROR_SESSION_EXPIRED,
          "登录状态已失效");
    }
  }

  private static String formhash(String html)throws CloudFilesClient.CloudException{
    Matcher matcher=Pattern.compile(
        "name=['\"]formhash['\"]\\s+value=['\"]([^'\"]+)",Pattern.CASE_INSENSITIVE)
        .matcher(html==null?"":html);
    if(!matcher.find()){
      throw new CloudFilesClient.CloudException(CloudFilesClient.ERROR_PROTOCOL,
          "回收站页面缺少 formhash");
    }
    return matcher.group(1);
  }

  // ---- premium (ilanzou app API) -----------------------------------------

  static Page premiumList(PremiumTransport transport,String apiOrigin,int page,int limit)
      throws CloudFilesClient.CloudException{
    if(page<1)throw invalid("回收站页码无效");
    if(limit<1||limit>200)throw invalid("回收站每页数量无效");
    String url=origin(apiOrigin)+"/proved/record/recycle/list?offset="+page+"&limit="+limit;
    JSONObject root=apiReply(transport.get(url));
    JSONArray list=root.optJSONArray("list");
    if(list==null)throw protocol("优享回收站响应缺少文件列表");
    List<Entry> items=new ArrayList<>();
    for(int index=0;index<list.length();index++){
      JSONObject raw=list.optJSONObject(index);
      if(raw==null)throw protocol("优享回收站列表条目格式无效");
      items.add(premiumEntry(raw));
    }
    int total=root.has("total")?root.optInt("total",items.size()):items.size();
    boolean hasMore=root.has("totalPage")&&page<root.optInt("totalPage",0);
    return new Page(items,page,hasMore,total);
  }

  static ActionResult premiumRestore(PremiumTransport transport,String apiOrigin,String id,
                                    boolean folder)
      throws CloudFilesClient.CloudException{
    return premiumChange(transport,apiOrigin,id,folder,false);
  }

  static ActionResult premiumPurge(PremiumTransport transport,String apiOrigin,String id,
                                  boolean folder)
      throws CloudFilesClient.CloudException{
    return premiumChange(transport,apiOrigin,id,folder,true);
  }

  static ActionResult premiumEmpty(PremiumTransport transport,String apiOrigin)
      throws CloudFilesClient.CloudException{
    JSONObject body=new JSONObject();
    put(body,"fileIds",-1);
    put(body,"folderIds","");
    put(body,"status",-1);
    apiReply(transport.postJson(origin(apiOrigin)+"/proved/file/delete",body));
    return new ActionResult("",false,false,false,true);
  }

  /** Session-bound premium bin; paging follows the app API. */
  static Bin premiumBin(final PremiumTransport transport,final String apiOrigin)
      throws CloudFilesClient.CloudException{
    final String base=origin(apiOrigin);
    return new Bin(){
      @Override public Page list(int page,int limit)throws CloudFilesClient.CloudException{
        return premiumList(transport,base,page,limit);
      }
      @Override public ActionResult restore(String id,boolean folder)
          throws CloudFilesClient.CloudException{
        return premiumRestore(transport,base,id,folder);
      }
      @Override public ActionResult purge(String id,boolean folder)
          throws CloudFilesClient.CloudException{
        return premiumPurge(transport,base,id,folder);
      }
      @Override public ActionResult empty()throws CloudFilesClient.CloudException{
        return premiumEmpty(transport,base);
      }
    };
  }

  private static ActionResult premiumChange(PremiumTransport transport,String apiOrigin,String id,
                                            boolean folder,boolean purge)
      throws CloudFilesClient.CloudException{
    String checked=requireId(id);
    JSONObject body=new JSONObject();
    put(body,"fileIds",folder?"":checked);
    put(body,"folderIds",folder?checked:"");
    String path=purge?"/proved/file/delete":"/proved/file/resume";
    if(purge)put(body,"status",-1);
    apiReply(transport.postJson(origin(apiOrigin)+path,body));
    return new ActionResult(checked,folder,purge,!purge,false);
  }

  private static Entry premiumEntry(JSONObject raw)throws CloudFilesClient.CloudException{
    boolean folder=raw.optInt("fileType",0)==2;
    String id=scalar(raw.opt(folder?"folderId":"fileId"));
    String name=folder?raw.optString("folderName",""):raw.optString("fileName","");
    if(id.isEmpty()||name.isEmpty()){
      throw protocol((folder?"优享文件夹":"优享文件")+"缺少标识或名称");
    }
    String description=folder?raw.optString("folderDesc",""):raw.optString("fileDesc","");
    String size="";
    if(!folder){
      Object value=raw.opt("fileSize");
      if(value!=null&&value!=JSONObject.NULL)size=scalar(value);
    }
    String modified=firstNonEmpty(raw.optString("updTime",""),raw.optString("addTime",""));
    return new Entry(id,name,folder,size,modified,description);
  }

  private static JSONObject apiReply(Reply reply)throws CloudFilesClient.CloudException{
    requireHttpOk(reply,"回收站操作失败");
    String text=reply.body==null?"":reply.body.trim();
    if(text.isEmpty())throw protocol("优享响应为空");
    JSONObject root;
    try{
      root=new JSONObject(text);
    }catch(Exception error){
      throw new CloudFilesClient.CloudException(CloudFilesClient.ERROR_PROTOCOL,
          "优享响应不是 JSON",error);
    }
    if(!root.has("code"))throw protocol("优享响应缺少状态");
    Long code=strictInteger(root.opt("code"));
    if(code!=null&&code.longValue()==200L)return root;
    if(code!=null&&code.longValue()==-2L)
      throw new CloudFilesClient.CloudException(CloudFilesClient.ERROR_SESSION_EXPIRED,
        "优享会话已过期");
    String message=firstNonEmpty(root.optString("msg",""),root.optString("message",""));
    throw new CloudFilesClient.CloudException(CloudFilesClient.ERROR_PROTOCOL,
        message.isEmpty()?"优享回收站操作失败":message);
  }

  // ---- shared helpers -----------------------------------------------------

  private static String origin(String value)throws CloudFilesClient.CloudException{
    String text=value==null?"":value.trim();
    if(text.isEmpty())throw invalid("服务地址不能为空");
    while(text.endsWith("/"))text=text.substring(0,text.length()-1);
    if(!text.startsWith("https://")&&!text.startsWith("http://"))
      throw invalid("服务地址必须为 HTTP(S) 绝对地址");
    return text;
  }

  private static void requireHttpOk(Reply reply,String message)
      throws CloudFilesClient.CloudException{
    int status=reply==null?0:reply.status;
    if(status<200||status>=300)
      throw new CloudFilesClient.CloudException(CloudFilesClient.ERROR_NETWORK,
          message+" (HTTP "+status+")");
  }

  private static String requireId(String value)throws CloudFilesClient.CloudException{
    String id=value==null?"":value.trim();
    if(id.isEmpty())throw invalid("回收站条目 ID 不能为空");
    if(id.length()>64)throw invalid("回收站条目 ID 过长");
    if("-1".equals(id))throw invalid("批量清空请使用 empty 操作");
    for(int index=0;index<id.length();index++){
      char ch=id.charAt(index);
      if(ch<'0'||ch>'9')throw invalid("回收站条目 ID 必须是数字");
    }
    return id;
  }

  private static String clean(String value){
    return value==null?"":value.replace("&nbsp;"," ").replace("&amp;","&").trim();
  }

  private static String folderName(String raw){
    String text=raw==null?"":raw.replaceAll("(?s)<[^>]*>","");
    text=clean(text);
    return text.endsWith("...")?text.substring(0,text.length()-3).trim():text;
  }

  private static String scalar(Object value){
    if(value==null||value==JSONObject.NULL)return "";
    if(value instanceof Number)return Long.toString(((Number)value).longValue());
    return value.toString().trim();
  }

  /**
   * Mirrors Rust premium {@code parse_api_response}: {@code root["code"].as_i64()}
   * accepts JSON integer numbers only, so a quoted "200" or a fractional
   * {@code 200.5} must never confirm.
   */
  private static Long strictInteger(Object value){
    if(value instanceof Float||value instanceof Double)return null;
    if(!(value instanceof Number))return null;
    String text=value.toString();
    if(!text.matches("[+-]?[0-9]+"))return null;
    try{return Long.parseLong(text);}catch(NumberFormatException ignored){return null;}
  }

  private static String firstNonEmpty(String... values){
    if(values!=null)for(String value:values)
      if(value!=null&&!value.trim().isEmpty())return value;
    return "";
  }

  private static String encode(String value)throws CloudFilesClient.CloudException{
    try{
      return URLEncoder.encode(value==null?"":value,"UTF-8");
    }catch(Exception impossible){
      throw new CloudFilesClient.CloudException(CloudFilesClient.ERROR_INVALID,"参数编码失败",
          impossible);
    }
  }

  private static void put(JSONObject body,String key,Object value)throws CloudFilesClient.CloudException{
    try{
      body.put(key,value);
    }catch(Exception error){
      throw new CloudFilesClient.CloudException(CloudFilesClient.ERROR_INVALID,"请求参数无效",
          error);
    }
  }

  private static CloudFilesClient.CloudException invalid(String message){
    return new CloudFilesClient.CloudException(CloudFilesClient.ERROR_INVALID,message);
  }

  private static CloudFilesClient.CloudException protocol(String message){
    return new CloudFilesClient.CloudException(CloudFilesClient.ERROR_PROTOCOL,message);
  }
}
