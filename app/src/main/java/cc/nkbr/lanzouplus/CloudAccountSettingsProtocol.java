package cc.nkbr.lanzouplus;

import org.json.JSONObject;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Ordinary-account domain/sharing settings protocol.
 *
 * <p>Ports the observed official AJAX tasks from
 * {@code LanzouYOU/native/lanzouyou_core/src/account_settings.rs}:
 * domain list {@code task=49&type=1}, set domain {@code task=48&domain=...},
 * set default domain {@code task=49&type=2&domainid=...},
 * set sharing {@code task=51&share=0|1}.</p>
 *
 * <p>{@link Session} carries only the captured fixed task URL; the uid/vei are
 * already bound inside it. This class never calls a "current account" URL
 * helper, so a later account switch cannot redirect these requests to another
 * account.</p>
 */
final class CloudAccountSettingsProtocol {
  private CloudAccountSettingsProtocol(){}

  /** Narrow authenticated form transport; the backend supplies the cookie. */
  interface Transport {
    Reply postForm(String taskUrl,Map<String,String> form) throws CloudFilesClient.CloudException;
    Reply getPage(String url) throws CloudFilesClient.CloudException;
  }

  static final class Reply {
    final int status;
    final String body;
    Reply(int status,String body){this.status=status;this.body=body==null?"":body;}
  }

  /** Captured account task URL, fixed at capture time. */
  static final class Session {
    final String taskUrl;
    Session(String taskUrl){
      this.taskUrl=taskUrl==null?"":taskUrl;
    }
  }

  /** Session-bound settings handle. */
  interface Settings {
    JSONObject domainList() throws CloudFilesClient.CloudException;
    JSONObject setDomain(String domain) throws CloudFilesClient.CloudException;
    JSONObject setDefaultDomain(String domainId) throws CloudFilesClient.CloudException;
    JSONObject setSharing(boolean enabled) throws CloudFilesClient.CloudException;
    String phoneStatus() throws CloudFilesClient.CloudException;
    JSONObject requestPhoneChange(String currentPhone,String newPhone) throws CloudFilesClient.CloudException;
    JSONObject confirmPhoneChange(String currentPhone,String newPhone,String verificationCode)
        throws CloudFilesClient.CloudException;
  }

  static Settings settings(final Transport transport,final Session session){
    return new Settings(){
      @Override public JSONObject domainList()throws CloudFilesClient.CloudException{
        return readDomains(transport,session);
      }
      @Override public JSONObject setDomain(String domain)throws CloudFilesClient.CloudException{
        requireArgument(domain,"域名");
        Map<String,String> form=new LinkedHashMap<>();
        form.put("task","48");
        form.put("domain",domain);
        submit(transport,session,form);
        return readDomains(transport,session);
      }
      @Override public JSONObject setDefaultDomain(String domainId)
          throws CloudFilesClient.CloudException{
        requireArgument(domainId,"域名 ID");
        Map<String,String> form=new LinkedHashMap<>();
        form.put("task","49");
        form.put("type","2");
        form.put("domainid",domainId);
        submit(transport,session,form);
        return readDomains(transport,session);
      }
      @Override public JSONObject setSharing(boolean enabled)
          throws CloudFilesClient.CloudException{
        Map<String,String> form=new LinkedHashMap<>();
        form.put("task","51");
        form.put("share",enabled?"0":"1");
        // No observed read-back for sharing; return the confirmed server reply.
        return submit(transport,session,form);
      }
      @Override public String phoneStatus()throws CloudFilesClient.CloudException{
        return readPhoneStatus(transport,session);
      }
      @Override public JSONObject requestPhoneChange(String currentPhone,String newPhone)
          throws CloudFilesClient.CloudException{
        return changePhone(transport,session,"1",currentPhone,newPhone,"");
      }
      @Override public JSONObject confirmPhoneChange(String currentPhone,String newPhone,
                                                     String verificationCode)
          throws CloudFilesClient.CloudException{
        return changePhone(transport,session,"2",currentPhone,newPhone,verificationCode);
      }
    };
  }

  private static String readPhoneStatus(Transport transport,Session session)
      throws CloudFilesClient.CloudException{
    String url=java.net.URI.create(requireSession(session))
        .resolve("/mydisk.php?item=profile&action=mypower").toString();
    Reply reply=transport.getPage(url);
    if(reply.status<200||reply.status>=300)
      throw new CloudFilesClient.CloudException(CloudFilesClient.ERROR_NETWORK,
          "手机号读取失败 (HTTP "+reply.status+")");
    return CloudOrdinaryAccountPages.parsePhoneStatus(reply.body);
  }

  /** Official task 43: zt=1 requests verification; zt=2 confirms the change. */
  private static JSONObject changePhone(Transport transport,Session session,String stage,
                                        String currentPhone,String newPhone,String verificationCode)
      throws CloudFilesClient.CloudException{
    requireArgument(currentPhone,"当前手机号");
    requireArgument(newPhone,"新手机号");
    requireArgument(verificationCode,"验证码");
    Map<String,String> form=new LinkedHashMap<>();
    form.put("task","43");form.put("type",stage);
    form.put("phoneold",currentPhone);form.put("phonenew",newPhone);
    form.put("phonecode",verificationCode);
    JSONObject root=parseReply(transport.postForm(requireSession(session),form));
    Long status=status(root);
    if(status==null||(status.longValue()!=1L&&status.longValue()!=2L))
      throw new CloudFilesClient.CloudException(CloudFilesClient.ERROR_PROTOCOL,
          message(root,"官方未确认手机号变更"));
    Map<String,Object> result=new LinkedHashMap<>();
    result.put("state",status.longValue()==1L?"verification_required":"updated");
    result.put("message",message(root,"请核对官方手机号变更状态"));
    if(status.longValue()==2L)result.put("phone",readPhoneStatus(transport,session));
    return new JSONObject(result);
  }

  static JSONObject readDomains(Transport transport,Session session)
      throws CloudFilesClient.CloudException{
    Map<String,String> form=new LinkedHashMap<>();
    form.put("task","49");
    form.put("type","1");
    return parseRead(transport.postForm(requireSession(session),form));
  }

  static JSONObject submit(Transport transport,Session session,Map<String,String> form)
      throws CloudFilesClient.CloudException{
    JSONObject root=parseReply(transport.postForm(requireSession(session),form));
    Long status=status(root);
    if(status==null||status.longValue()!=1L){
      String detail=message(root,"官方未确认设置提交");
      throw new CloudFilesClient.CloudException(CloudFilesClient.ERROR_PROTOCOL,
          detail);
    }
    return root;
  }

  private static String requireSession(Session session)throws CloudFilesClient.CloudException{
    if(session==null||session.taskUrl.trim().isEmpty())
      throw new CloudFilesClient.CloudException(CloudFilesClient.ERROR_INVALID,
          "账号设置会话无效");
    return session.taskUrl;
  }

  private static JSONObject parseRead(Reply reply)throws CloudFilesClient.CloudException{
    JSONObject root=parseReply(reply);
    Long status=status(root);
    if(status!=null&&(status.longValue()==1L||status.longValue()==2L||status.longValue()==4L))
      return root;
    throw new CloudFilesClient.CloudException(CloudFilesClient.ERROR_PROTOCOL,
        message(root,"读取域名列表失败"));
  }

  private static JSONObject parseReply(Reply reply)throws CloudFilesClient.CloudException{
    int httpStatus=reply==null?0:reply.status;
    if(httpStatus<200||httpStatus>=300)
      throw new CloudFilesClient.CloudException(CloudFilesClient.ERROR_NETWORK,
          "账号设置请求失败 (HTTP "+httpStatus+")");
    String text=reply.body==null?"":reply.body.trim();
    if(text.isEmpty())
      throw new CloudFilesClient.CloudException(CloudFilesClient.ERROR_PROTOCOL,
          "账号设置响应为空");
    JSONObject root;
    try{
      root=new JSONObject(text);
    }catch(Exception error){
      throw new CloudFilesClient.CloudException(CloudFilesClient.ERROR_PROTOCOL,
          "账号设置响应不是 JSON",error);
    }
    if(!root.has("zt"))
      throw new CloudFilesClient.CloudException(CloudFilesClient.ERROR_PROTOCOL,
          "账号设置响应缺少状态");
    Long status=status(root);
    if(status!=null&&status.longValue()==9L)
      throw new CloudFilesClient.CloudException(CloudFilesClient.ERROR_SESSION_EXPIRED,
          "登录已过期");
    return root;
  }

  private static Long status(JSONObject root){
    return strictInteger(root==null?null:root.opt("zt"));
  }

  /**
   * Mirrors Rust {@code value_as_i64}: only integer JSON numbers and integer
   * strings count, so a fractional status such as {@code 1.5} never confirms.
   */
  private static Long strictInteger(Object value){
    if(value instanceof Float||value instanceof Double)return null;
    if(!(value instanceof Number)&&!(value instanceof String))return null;
    String text=value.toString();
    if(!text.matches("[+-]?[0-9]+"))return null;
    try{return Long.parseLong(text);}catch(NumberFormatException ignored){return null;}
  }

  private static String message(JSONObject root,String fallback){
    for(String key:new String[]{"inf","info","msgs"}){
      String text=root.optString(key,"");
      if(!text.trim().isEmpty())return text.trim();
    }
    return fallback;
  }

  /** The official task accepts the raw value; only a missing Java argument is refused. */
  private static void requireArgument(String value,String field)
      throws CloudFilesClient.CloudException{
    if(value==null)
      throw new CloudFilesClient.CloudException(CloudFilesClient.ERROR_INVALID,
          field+"不能为空");
  }
}
