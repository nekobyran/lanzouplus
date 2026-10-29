package cc.nkbr.lanzouplus;

import org.json.JSONArray;
import org.json.JSONObject;

import java.net.URLEncoder;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Premium (iLanzou) account protocol: profile, nickname, password, packages,
 * payment ways, order history, order creation and payment query.
 *
 * <p>Ports {@code LanzouYOU/native/lanzouyou_core/src/premium/settings.rs} and
 * {@code premium/account_pages.rs} field for field, including
 * {@code order_request} and {@code parse_checkout}. Endpoints, JSON bodies and
 * validation rules come from that Rust source; nothing here is guessed.</p>
 *
 * <p>{@link Transport} is a raw authenticated HTTP boundary: it returns the
 * server reply as-is and never pre-checks the payload status. A pending payment
 * ({@code code=-1}) is a valid query result, so success parsing stays here.</p>
 */
final class CloudPremiumAccountProtocol {
  private CloudPremiumAccountProtocol(){}

  /** Raw authenticated transport. Paths are official API paths, token is implicit. */
  interface Transport {
    Reply get(String path) throws CloudFilesClient.CloudException;
    Reply postJson(String path,JSONObject body) throws CloudFilesClient.CloudException;
  }

  static final class Reply {
    final int status;
    final String body;
    Reply(int status,String body){this.status=status;this.body=body==null?"":body;}
  }

  /** Captured user id plus a transport already bound to this account. */
  interface Account {
    JSONObject profile() throws CloudFilesClient.CloudException;
    JSONObject setNickname(String name) throws CloudFilesClient.CloudException;
    JSONObject changePassword(String oldPassword,String newPassword,String confirmation)
        throws CloudFilesClient.CloudException;
    JSONObject vipPackages() throws CloudFilesClient.CloudException;
    JSONObject paymentWays() throws CloudFilesClient.CloudException;
    JSONObject orderHistory(int page,int pageSize) throws CloudFilesClient.CloudException;
    JSONObject createOrder(String packageId,String paymentWayId)
        throws CloudFilesClient.CloudException;
    JSONObject queryOrder(String orderId) throws CloudFilesClient.CloudException;
  }

  /**
   * Called exactly once after an official password change is confirmed. The
   * client repository owns credentials; this protocol only reports success.
   */
  interface PasswordChangeCommit {
    void commit(String newPassword) throws CloudFilesClient.CloudException;
  }

  static Account account(final Transport transport,final String userId,
                         final PasswordChangeCommit passwordCommit){
    final String uid=userId==null?"":userId;
    if(passwordCommit==null)throw new IllegalArgumentException("passwordCommit required");
    return new Account(){
      @Override public JSONObject profile()throws CloudFilesClient.CloudException{
        JSONObject profile=read(transport.get("/proved/user/info/map"),"读取个人资料失败");
        JSONObject capacity=read(transport.get("/proved/user/account/map"),"读取账号容量失败");
        JSONObject map=profile.optJSONObject("map");
        JSONObject capacityMap=capacity.optJSONObject("map");
        if(map==null||capacityMap==null)
          throw protocol("优享资料响应缺少数据");
        JSONObject result=new JSONObject();
        try{result.put("profile",map);result.put("capacity",capacityMap);}
        catch(Exception impossible){throw protocol("优享资料整理失败");}
        return result;
      }

      @Override public JSONObject setNickname(String name)
          throws CloudFilesClient.CloudException{
        String trimmed=trimWhitespace(name);
        int length=trimmed.codePointCount(0,trimmed.length());
        if(length<2||length>24)throw invalid("昵称长度须为 2 至 24 个字符");
        JSONObject body=new JSONObject();
        try{body.put("userName",trimmed);}catch(Exception impossible){throw invalid("昵称无效");}
        read(transport.postJson("/proved/user/setUserName",body),"修改昵称失败");
        return saved("官方已确认保存");
      }

      @Override public JSONObject changePassword(String oldPassword,String newPassword,
                                                 String confirmation)
          throws CloudFilesClient.CloudException{
        String old=oldPassword==null?"":oldPassword;
        String next=newPassword==null?"":newPassword;
        String confirm=confirmation==null?"":confirmation;
        int length=next.codePointCount(0,next.length());
        if(old.isEmpty()||length<6||length>18||containsWhitespace(next))
          throw invalid("请输入当前密码及 6 至 18 位无空格的新密码");
        if(!next.equals(confirm))throw invalid("两次输入的新密码不一致");
        JSONObject body=new JSONObject();
        try{body.put("oldPwd",old).put("loginPwd1",next).put("loginPwd2",confirm);}
        catch(Exception impossible){throw invalid("密码参数无效");}
        read(transport.postJson("/proved/user/chgPwd",body),"修改密码失败");
        passwordCommit.commit(next);
        return saved("官方已确认保存");
      }

      @Override public JSONObject vipPackages()throws CloudFilesClient.CloudException{
        if(uid.isEmpty())throw protocol("优享账号缺少真实用户标识");
        String path="/unproved/buy/vip/list?userId="+encode(uid)+"&extra=1";
        return read(transport.postJson(path,null),"读取会员套餐失败");
      }

      @Override public JSONObject paymentWays()throws CloudFilesClient.CloudException{
        return read(transport.postJson("/proved/wallet/recharge/way/list",null),
            "读取支付方式失败");
      }

      @Override public JSONObject orderHistory(int page,int pageSize)
          throws CloudFilesClient.CloudException{
        if(page<1||pageSize<1||pageSize>100)throw invalid("订单页码或每页数量无效");
        String path="/proved/buy/list?offset="+page+"&limit="+pageSize;
        return read(transport.get(path),"读取订单记录失败");
      }

      @Override public JSONObject createOrder(String packageId,String paymentWayId)
          throws CloudFilesClient.CloudException{
        JSONObject plans=vipPackages();
        JSONObject ways=paymentWays();
        JSONObject body=orderRequest(plans,ways,packageId,paymentWayId);
        JSONObject root=read(transport.postJson("/proved/wallet/recharge/submit",body),
            "创建支付订单失败");
        return parseCheckout(root);
      }

      @Override public JSONObject queryOrder(String orderId)
          throws CloudFilesClient.CloudException{
        if(orderId==null||orderId.isEmpty())throw invalid("订单编号不能为空");
        JSONObject body=new JSONObject();
        try{body.put("orderId",orderId);}catch(Exception impossible){throw invalid("订单编号无效");}
        return parsePaymentQuery(parseRawReply(
            transport.postJson("/proved/wallet/recharge/query",body),"查询支付结果失败"));
      }
    };
  }

  // ---- request body / result parsing -------------------------------------

  /** Mirrors Rust {@code order_request}: package and way must exist, price is the server's. */
  static JSONObject orderRequest(JSONObject plans,JSONObject ways,String packageId,
                                 String paymentWayId)throws CloudFilesClient.CloudException{
    JSONArray tiers=plans==null?null:plans.optJSONArray("list");
    if(tiers==null)throw protocol("会员套餐响应缺少列表");
    JSONObject selected=null;
    for(int index=0;index<tiers.length()&&selected==null;index++){
      JSONObject tier=tiers.optJSONObject(index);
      JSONArray packages=tier==null?null:tier.optJSONArray("packages");
      if(packages==null)continue;
      for(int item=0;item<packages.length();item++){
        JSONObject candidate=packages.optJSONObject(item);
        if(candidate!=null&&idMatches(candidate.opt("pkgId"),packageId)){selected=candidate;break;}
      }
    }
    if(selected==null)throw invalid("所选会员套餐已失效，请刷新套餐");
    Object price=selected.opt("price");
    if(!(price instanceof Number))throw protocol("会员套餐缺少价格");
    JSONArray wayList=ways==null?null:ways.optJSONArray("list");
    if(wayList==null)throw protocol("支付方式响应缺少列表");
    JSONObject way=null;
    for(int index=0;index<wayList.length()&&way==null;index++){
      JSONObject candidate=wayList.optJSONObject(index);
      if(candidate!=null&&idMatches(candidate.opt("wayId"),paymentWayId))way=candidate;
    }
    if(way==null)throw invalid("所选支付方式已失效，请刷新支付方式");
    JSONObject body=new JSONObject();
    try{
      body.put("buyType",2);
      body.put("buyId",selected.opt("pkgId"));
      body.put("wayId",way.opt("wayId"));
      body.put("amt",price);
    }catch(Exception impossible){throw protocol("订单参数整理失败");}
    return body;
  }

  /** Mirrors Rust {@code parse_checkout}: order id plus a URL, QR or payment form. */
  static JSONObject parseCheckout(JSONObject root)throws CloudFilesClient.CloudException{
    JSONObject map=root==null?null:root.optJSONObject("map");
    if(map==null)throw protocol("充值响应缺少数据");
    Object orderId=map.opt("orderId");
    if(!(orderId instanceof String)&&!(orderId instanceof Number))
      throw protocol("充值响应缺少订单编号");
    String payUrl=map.optString("payUrl","");
    if(payUrl.isEmpty())throw protocol("充值响应缺少支付地址");
    JSONObject payment;
    if(trimLeadingWhitespace(payUrl).startsWith("<")){
      payment=paymentForm(payUrl);
    }else if(payUrl.startsWith("weixin://wxpay/")){
      payment=new JSONObject();
      try{payment.put("kind","qr").put("content",payUrl);}
      catch(Exception impossible){throw protocol("支付二维码整理失败");}
    }else{
      payment=new JSONObject();
      try{payment.put("kind","url").put("url",paymentUrl(payUrl));}
      catch(CloudFilesClient.CloudException error){throw error;}
      catch(Exception impossible){throw protocol("支付地址整理失败");}
    }
    JSONObject result=new JSONObject();
    try{result.put("orderId",orderId).put("payment",payment);}
    catch(Exception impossible){throw protocol("支付结果整理失败");}
    return result;
  }

  /** Mirrors Rust {@code parse_payment_query}: 200 paid, -1 pending, -2 expired. */
  static JSONObject parsePaymentQuery(JSONObject root)throws CloudFilesClient.CloudException{
    Long code=strictInteger(root==null?null:root.opt("code"));
    if(code==null)throw protocol("支付查询响应缺少状态码");
    JSONObject result=new JSONObject();
    try{
      if(code==200L)return result.put("paid",true);
      if(code==-1L)return result.put("paid",false);
    }catch(Exception impossible){throw protocol("支付结果整理失败");}
    if(code==-2L)throw sessionExpired();
    throw protocol("查询支付结果失败");
  }

  // ---- form parsing ------------------------------------------------------

  /** Tag bodies may contain {@code >} inside a quoted attribute value. */
  private static final Pattern FORM_TAG=Pattern.compile(
      "(?is)<form\\b((?:\"[^\"]*\"|'[^']*'|[^>\"'])*)>");
  private static final Pattern INPUT_TAG=Pattern.compile(
      "(?is)<input\\b((?:\"[^\"]*\"|'[^']*'|[^>\"'])*)>");
  private static final Pattern ATTRIBUTE=Pattern.compile(
      "([A-Za-z_:][-A-Za-z0-9_:.]*)(?:\\s*=\\s*(?:\"([^\"]*)\"|'([^']*)'|([^\\s\"'>]+)))?");
  /** HTML5 character reference token, named or numeric. */
  private static final Pattern ENTITY=Pattern.compile(
      "&(?:#(?:[0-9]+|[xX][0-9A-Fa-f]+)|[A-Za-z][A-Za-z0-9]*);");
  /** Windows-1252 mapping HTML5 applies to numeric refs 0x80..0x9F. */
  private static final char[] C1_REPLACEMENTS={
      0x20AC,0x81,0x201A,0x0192,0x201E,0x2026,0x2020,0x2021,
      0x02C6,0x2030,0x0160,0x2039,0x0152,0x8D,0x017D,0x8F,
      0x90,0x2018,0x2019,0x201C,0x201D,0x2022,0x2013,0x2014,
      0x02DC,0x2122,0x0161,0x203A,0x0153,0x9D,0x017E,0x0178};

  /** Extract the first official payment form without executing any script. */
  static JSONObject paymentForm(String fragment)throws CloudFilesClient.CloudException{
    if(fragment==null)throw protocol("充值响应缺少支付表单");
    Matcher form=FORM_TAG.matcher(fragment);
    if(!form.find())throw protocol("充值响应缺少支付表单");
    String formAttributes=form.group(1);
    String action=attribute(formAttributes,"action");
    String method=attribute(formAttributes,"method");
    if(action==null||trimWhitespace(action).isEmpty())throw protocol("支付表单缺少地址");
    if(method==null||trimWhitespace(method).isEmpty())throw protocol("支付表单缺少方法");
    String upper=trimWhitespace(method).toUpperCase(java.util.Locale.ROOT);
    if(!"GET".equals(upper)&&!"POST".equals(upper))throw protocol("支付表单的方法无效");
    int end=fragment.toLowerCase(java.util.Locale.ROOT).indexOf("</form>",form.end());
    String content=end<0?fragment.substring(form.end()):fragment.substring(form.end(),end);
    List<Object> fields=new ArrayList<>();
    Matcher input=INPUT_TAG.matcher(content);
    while(input.find()){
      String attributes=input.group(1);
      if(attribute(attributes,"disabled")!=null)continue;
      String name=attribute(attributes,"name");
      if(name==null||name.isEmpty())continue;
      String value=attribute(attributes,"value");
      JSONObject field=new JSONObject();
      try{field.put("name",name).put("value",value==null?"":value);}
      catch(Exception impossible){throw protocol("支付表单字段无效");}
      fields.add(field);
    }
    JSONObject payment=new JSONObject();
    try{
      payment.put("kind","form");
      payment.put("url",paymentUrl(action));
      payment.put("method",upper);
      payment.put("fields",new JSONArray(fields));
    }catch(CloudFilesClient.CloudException error){throw error;}
    catch(Exception impossible){throw protocol("支付表单整理失败");}
    return payment;
  }

  /** Decoded attribute value, "" for a bare attribute, null when absent. */
  private static String attribute(String attributes,String name){
    if(attributes==null)return null;
    Matcher matcher=ATTRIBUTE.matcher(attributes);
    while(matcher.find()){
      if(!matcher.group(1).equalsIgnoreCase(name))continue;
      String value=matcher.group(2)!=null?matcher.group(2)
          :(matcher.group(3)!=null?matcher.group(3):matcher.group(4));
      return decodeEntities(value==null?"":value);
    }
    return null;
  }

  /** Decodes each {@code &...;} token once and keeps every other character as-is. */
  static String decodeEntities(String value){
    if(value==null||value.isEmpty()||value.indexOf('&')<0)return value==null?"":value;
    Matcher matcher=ENTITY.matcher(value);
    StringBuffer text=new StringBuffer(value.length());
    while(matcher.find()){
      String token=matcher.group();
      String decoded=token.startsWith("&#")?numericReference(token)
          :org.commonmark.internal.util.Html5Entities.entityToString(token);
      matcher.appendReplacement(text,Matcher.quoteReplacement(decoded));
    }
    matcher.appendTail(text);
    return text.toString();
  }

  /**
   * HTML5 numeric reference rules. CommonMark 0.21.0's helper returns the raw
   * code point for {@code &#128;} (U+0080), while the HTML5 algorithm maps the
   * C1 range through the Windows-1252 table; Rust's html5ever yields "€" here.
   */
  private static String numericReference(String token){
    String digits=token.substring(2,token.length()-1);
    int radix=10;
    if(digits.startsWith("x")||digits.startsWith("X")){radix=16;digits=digits.substring(1);}
    long codePoint;
    try{
      codePoint=Long.parseLong(digits,radix);
    }catch(NumberFormatException tooLarge){
      return "\uFFFD";
    }
    if(codePoint==0||codePoint>0x10FFFF||(codePoint>=0xD800&&codePoint<=0xDFFF))
      return "\uFFFD";
    int value=(int)codePoint;
    if(value>=0x80&&value<=0x9F)
      return String.valueOf(C1_REPLACEMENTS[value-0x80]);
    return new String(Character.toChars(value));
  }

  private static String paymentUrl(String value)throws CloudFilesClient.CloudException{
    // The WHATWG URL parser strips leading/trailing C0 controls and spaces.
    String text=value==null?"":value.trim();
    try{
      java.net.URI uri=new java.net.URI(text);
      String scheme=uri.getScheme();
      if(uri.getHost()==null||scheme==null
          ||!("https".equalsIgnoreCase(scheme)||"http".equalsIgnoreCase(scheme)))
        throw protocol("支付地址须使用 HTTP 或 HTTPS");
      return uri.toString();
    }catch(CloudFilesClient.CloudException error){throw error;}
    catch(Exception error){throw protocol("支付地址无效");}
  }

  // ---- status / helpers ---------------------------------------------------

  private static JSONObject read(Reply reply,String fallback)throws CloudFilesClient.CloudException{
    JSONObject root=parseRawReply(reply,fallback);
    Long code=strictInteger(root.opt("code"));
    if(code==null)throw protocol("优享响应缺少状态");
    if(code==200L)return root;
    if(code==-2L)throw sessionExpired();
    String message=firstNonEmpty(root.optString("msg",""),root.optString("message",""));
    throw new CloudFilesClient.CloudException(CloudFilesClient.ERROR_PROTOCOL,
        message.isEmpty()?fallback:message);
  }

  /** HTTP and JSON shape only; payload {@code code} is judged by the caller. */
  private static JSONObject parseRawReply(Reply reply,String fallback)
      throws CloudFilesClient.CloudException{
    if(reply==null)throw protocol(fallback);
    if(reply.status==401||reply.status==403)throw sessionExpired();
    if(reply.status<200||reply.status>=300)
      throw new CloudFilesClient.CloudException(CloudFilesClient.ERROR_NETWORK,
          fallback+" (HTTP "+reply.status+")");
    String text=reply.body==null?"":reply.body.trim();
    if(text.isEmpty())throw protocol("优享响应为空");
    try{
      return new JSONObject(text);
    }catch(Exception error){
      throw protocol("优享响应不是 JSON");
    }
  }

  /** Strict integer status: integer JSON numbers only, mirroring {@code as_i64}. */
  private static Long strictInteger(Object value){
    if(value instanceof Float||value instanceof Double)return null;
    if(!(value instanceof Number))return null;
    String text=value.toString();
    if(!text.matches("[+-]?[0-9]+"))return null;
    try{return Long.parseLong(text);}catch(NumberFormatException ignored){return null;}
  }

  private static boolean idMatches(Object value,String requested){
    if(value==null||requested==null)return false;
    if(value instanceof Number)return value.toString().equals(requested);
    return value instanceof String&&value.equals(requested);
  }

  private static boolean containsWhitespace(String value){
    for(int index=0;index<value.length();){
      int codePoint=value.codePointAt(index);
      if(isUnicodeWhitespace(codePoint))return true;
      index+=Character.charCount(codePoint);
    }
    return false;
  }

  /** Unicode {@code White_Space}, matching Rust {@code char::is_whitespace}. */
  private static boolean isUnicodeWhitespace(int codePoint){
    return (codePoint>=0x09&&codePoint<=0x0D)||codePoint==0x20||codePoint==0x85
        ||codePoint==0xA0||codePoint==0x1680||(codePoint>=0x2000&&codePoint<=0x200A)
        ||codePoint==0x2028||codePoint==0x2029||codePoint==0x202F||codePoint==0x205F
        ||codePoint==0x3000;
  }

  /** Matches Rust {@code str::trim} for White_Space only. */
  static String trimWhitespace(String value){
    if(value==null)return "";
    int start=0,end=value.length();
    while(start<end){
      int codePoint=value.codePointAt(start);
      if(!isUnicodeWhitespace(codePoint))break;
      start+=Character.charCount(codePoint);
    }
    while(end>start){
      int codePoint=value.codePointBefore(end);
      if(!isUnicodeWhitespace(codePoint))break;
      end-=Character.charCount(codePoint);
    }
    return value.substring(start,end);
  }

  /** Matches Rust {@code str::trim_start} for White_Space only. */
  private static String trimLeadingWhitespace(String value){
    if(value==null)return "";
    int start=0;
    while(start<value.length()){
      int codePoint=value.codePointAt(start);
      if(!isUnicodeWhitespace(codePoint))break;
      start+=Character.charCount(codePoint);
    }
    return value.substring(start);
  }

  private static String firstNonEmpty(String... values){
    if(values!=null)for(String value:values)
      if(value!=null&&!value.trim().isEmpty())return value.trim();
    return "";
  }

  private static String encode(String value){
    try{return URLEncoder.encode(value==null?"":value,"UTF-8");}
    catch(Exception impossible){throw new AssertionError(impossible);}
  }

  private static JSONObject saved(String message){
    JSONObject result=new JSONObject();
    try{result.put("state","succeeded").put("message",message);}
    catch(Exception impossible){throw new AssertionError(impossible);}
    return result;
  }

  private static CloudFilesClient.CloudException protocol(String message){
    return new CloudFilesClient.CloudException(CloudFilesClient.ERROR_PROTOCOL,message);
  }

  private static CloudFilesClient.CloudException invalid(String message){
    return new CloudFilesClient.CloudException(CloudFilesClient.ERROR_INVALID,message);
  }

  private static CloudFilesClient.CloudException sessionExpired(){
    return new CloudFilesClient.CloudException(CloudFilesClient.ERROR_SESSION_EXPIRED,
        "优享会话已过期");
  }
}
