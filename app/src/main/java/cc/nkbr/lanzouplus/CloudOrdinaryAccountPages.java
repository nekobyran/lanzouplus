package cc.nkbr.lanzouplus;

import java.net.URI;
import java.net.URISyntaxException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Data model for the eight ordinary-account settings pages. Section paths are
 * copied from LanzouYOU/native/lanzouyou_core/src/account_settings.rs. */
final class CloudOrdinaryAccountPages {
  private CloudOrdinaryAccountPages(){}
  enum Section {
    PROFILE("mydisk.php?item=profile&action=mypower"),
    MEMBERSHIP("mydisk.php?item=profile&action=huiyuan"),
    BALANCE("mydisk.php?item=profile&action=goods"),
    FILE_UPGRADE("mydisk.php?item=profile&action=buygoods"),
    CASHOUT("mydisk.php?item=profile&action=exchange"),
    ORDERS("mydisk.php?item=profile&action=orders"),
    PASSWORD("mydisk.php?item=profile&action=password"),
    INVITE("mydisk.php?item=buddy&action=invite");
    private final String path;
    Section(String path){this.path=path;}
    String path(){return path;}
  }
  /** Raw authenticated boundary; redirects stay visible via {@link Reply#location}. */
  interface Transport {
    Reply get(String url,String referer) throws CloudFilesClient.CloudException;
    Reply postForm(String url,Map<String,String> form,String referer)
        throws CloudFilesClient.CloudException;
  }
  static final class Reply {
    final int status;
    final String body;
    final String location;
    Reply(int status,String body,String location){
      this.status=status; this.body=nz(body); this.location=nz(location);
    }
  }
  interface Pages {
    Page read(Section section) throws CloudFilesClient.CloudException;
    Result submit(Section section,String formId,Map<String,String> fields)
        throws CloudFilesClient.CloudException;
  }
  static final class Fact {
    final String label;
    final String value;
    Fact(String label,String value){this.label=nz(label); this.value=nz(value);}
  }
  static final class Option {
    final String label;
    final String value;
    Option(String label,String value){this.label=nz(label); this.value=nz(value);}
  }
  static final class Field {
    final String name;
    final String label;
    final String kind;
    /** Always empty for kind=password. */
    final String value;
    final boolean required;
    final Integer maxLength;
    final String checkedValue;
    final List<Option> options;
    Field(String name,String label,String kind,String value,boolean required,
          Integer maxLength,String checkedValue,List<Option> options){
      this.name=nz(name); this.label=nz(label); this.kind=nz(kind);
      this.value="password".equals(kind)?"":nz(value);
      this.required=required; this.maxLength=maxLength;
      this.checkedValue=checkedValue; this.options=copy(options);
    }
  }
  static final class Form {
    final String id;
    final String submitLabel;
    final List<Field> fields;
    private final String endpoint;
    private final String method;
    /** Official hidden values; never surfaced to UI or toString(). */
    private final Map<String,String> hidden;
    Form(String id,String submitLabel,List<Field> fields,String endpoint,
         String method,Map<String,String> hidden){
      this.id=nz(id); this.submitLabel=nz(submitLabel); this.fields=copy(fields);
      this.endpoint=nz(endpoint); this.method=nz(method);
      this.hidden=hidden==null?Collections.<String,String>emptyMap()
          :Collections.unmodifiableMap(new LinkedHashMap<>(hidden));
    }
    @Override public String toString(){
      return "Form{id="+id+",submitLabel="+submitLabel+",fields="+fields.size()+"}";
    }
  }
  static final class Page {
    final Section section;
    final String title;
    final List<Fact> facts;
    final List<Form> forms;
    Page(Section section,String title,List<Fact> facts,List<Form> forms){
      this.section=section; this.title=nz(title);
      this.facts=copy(facts); this.forms=copy(forms);
    }
  }
  /** Exactly one of {@link #succeeded} and {@link #paymentRequired} is true. */
  static final class Result {
    final boolean succeeded;
    final boolean paymentRequired;
    final String message;
    final Page page;
    final String paymentUrl;
    Result(boolean succeeded,String message,Page page,String paymentUrl){
      this.succeeded=succeeded; this.paymentRequired=!succeeded;
      this.message=nz(message); this.page=page; this.paymentUrl=nz(paymentUrl);
    }
  }
  /** Bound pages for one account; pcOrigin is the configured official host. */
  static Pages pages(Transport transport,String pcOrigin)throws CloudFilesClient.CloudException{
    if(transport==null)throw invalid("缺少官方设置传输通道");
    URI origin=uri(pcOrigin,"官方账号服务地址无效");
    if(origin.getUserInfo()!=null)throw invalid("官方账号服务地址不能包含用户信息");
    String root=origin.getScheme()+"://"+origin.getAuthority();
    return new Session(transport,origin,root.endsWith("/")?root:root+"/");
  }
  /** Lightweight node produced by {@link #tokenize(String)}. */
  private static final class Node {
    final String tag; final Map<String,String> attrs; final Node parent;
    final List<Node> children=new ArrayList<>();
    private final StringBuilder text=new StringBuilder();
    private final StringBuilder own=new StringBuilder();
    Node(String tag,Map<String,String> attrs,Node parent){
      this.tag=tag; this.attrs=attrs; this.parent=parent;
    }
    String attr(String name){String value=attrs.get(name); return value==null?"":value;}
    String text(){return text.toString();}
    String ownText(){return own.toString();}
    void addText(String value){
      own.append(value);
      for(Node n=this;n!=null;n=n.parent)n.text.append(value);
    }
    @Override public String toString(){return "<"+tag+" children="+children.size()+">";}
  }
  private static final Set<String> VOID=new HashSet<>(Arrays.asList("area","base","br",
      "col","embed","hr","img","input","link","meta","source","track","wbr"));
  /** Tags that real pages usually leave unclosed; a same tag starts a new sibling. */
  private static final Set<String> AUTO_CLOSE=new HashSet<>(Arrays.asList(
      "option","li","td","th","tr","dt","dd","p"));
  /** Quote-aware scan; comments and script/style are dropped, entities decode once. */
  private static Node tokenize(String html){
    Node root=new Node("#root",Collections.<String,String>emptyMap(),null);
    Node current=root;
    String body=html==null?"":html;
    int index=0;
    while(index<body.length()){
      int lt=body.indexOf('<',index);
      if(lt<0){text(current,body.substring(index));break;}
      if(lt>index)text(current,body.substring(index,lt));
      char after=body.charAt(lt+1<body.length()?lt+1:lt);
      if(body.startsWith("<!--",lt)){
        int end=body.indexOf("-->",lt+4);
        index=end<0?body.length():end+3;
        continue;
      }
      // A '<' followed by whitespace or any non-name char is literal prose.
      if(body.length()<=lt+1||(!Character.isLetter(after)&&after!='/'&&after!='!'&&after!='?')){
        text(current,"<");
        index=lt+1;
        continue;
      }
      int gt=tagEnd(body,lt+1);
      if(gt<0){text(current,body.substring(lt));break;}
      String inner=body.substring(lt+1,gt).trim();
      index=gt+1;
      if(inner.isEmpty()||inner.charAt(0)=='!'||inner.charAt(0)=='?')continue;
      if(inner.charAt(0)=='/'){
        String name=tagName(inner,1);
        Node node=current;
        while(node!=null&&!name.equals(node.tag))node=node.parent;
        if(node!=null&&node.parent!=null)current=node.parent;
        continue;
      }
      String name=tagName(inner,0);
      if(name.isEmpty())continue;
      if(name.equals("script")||name.equals("style")){
        int close=findClosing(body,name,index);
        int end=close<0?-1:body.indexOf('>',close);
        index=close<0?body.length():(end<0?body.length():end+1);
        continue;
      }
      // Unclosed siblings such as <option> or <li> are closed by the next same tag.
      if(AUTO_CLOSE.contains(name)&&name.equals(current.tag)&&current.parent!=null){
        current=current.parent;
      }
      Node node=new Node(name,attributes(inner,name.length()),current);
      current.children.add(node);
      if(name.equals("textarea")||name.equals("title")){
        int close=findClosing(body,name,index);
        if(close<0){rcdata(node,body.substring(index)); index=body.length();}
        else{rcdata(node,body.substring(index,close)); index=closeTagEnd(body,close+name.length()+2);}
        continue;
      }
      if(!VOID.contains(name))current=node;
    }
    return root;
  }
  private static void rcdata(Node node,String raw){
    String normalized=raw.replace("\r\n","\n").replace('\r','\n');
    if(node.tag.equals("textarea")&&normalized.startsWith("\n"))normalized=normalized.substring(1);
    text(node,normalized);
  }
  /** Index of the closing {@code >} ignoring any inside single or double quotes. */
  private static int tagEnd(String body,int from){
    char quote=0;
    for(int i=from;i<body.length();i++){
      char c=body.charAt(i);
      if(quote!=0){if(c==quote)quote=0;continue;}
      if(c=='"'||c=='\'')quote=c;
      else if(c=='>')return i;
    }
    return -1;
  }
  private static String tagName(String inner,int from){
    int i=from;
    while(i<inner.length()){
      char c=inner.charAt(i);
      if(!Character.isLetterOrDigit(c)&&c!='-'&&c!='_'&&c!=':')break;
      i++;
    }
    return inner.substring(from,i).toLowerCase(java.util.Locale.ROOT);
  }
  /** Reads quoted, unquoted and bare boolean attributes; values decoded once. */
  private static Map<String,String> attributes(String inner,int from){
    Map<String,String> attrs=new LinkedHashMap<>();
    int i=from;
    while(i<inner.length()){
      while(i<inner.length()&&Character.isWhitespace(inner.charAt(i)))i++;
      if(i>=inner.length())break;
      if(inner.charAt(i)=='/'){i++;continue;}
      int start=i;
      while(i<inner.length()){
        char c=inner.charAt(i);
        if(Character.isWhitespace(c)||c=='='||c=='/'||c=='>')break;
        i++;
      }
      String name=inner.substring(start,i).toLowerCase(java.util.Locale.ROOT);
      while(i<inner.length()&&Character.isWhitespace(inner.charAt(i)))i++;
      String value="";
      if(i<inner.length()&&inner.charAt(i)=='='){
        i++;
        while(i<inner.length()&&Character.isWhitespace(inner.charAt(i)))i++;
        if(i<inner.length()&&(inner.charAt(i)=='"'||inner.charAt(i)=='\'')){
          char quote=inner.charAt(i);
          int end=inner.indexOf(quote,i+1);
          if(end<0)end=inner.length();
          value=inner.substring(i+1,end);
          i=Math.min(inner.length(),end+1);
        }else{
          int vs=i;
          while(i<inner.length()&&!Character.isWhitespace(inner.charAt(i))
              &&!(inner.charAt(i)=='/'&&i==inner.length()-1))i++;
          value=inner.substring(vs,i);
        }
      }
      if(!name.isEmpty())attrs.put(name,CloudPremiumAccountProtocol.decodeEntities(value));
    }
    return attrs;
  }
  private static void text(Node node,String raw){
    if(raw.isEmpty())return;
    node.addText(CloudPremiumAccountProtocol.decodeEntities(raw));
  }
  /** Finds {@code </name} with a real name boundary, so </scripture> never matches. */
  private static int findClosing(String body,String name,int from){
    String needle="</"+name;
    int max=body.length()-needle.length();
    for(int i=from;i<=max;i++){
      if(!body.regionMatches(true,i,needle,0,needle.length()))continue;
      int after=i+needle.length();
      if(after>=body.length())return i;
      char c=body.charAt(after);
      if(Character.isWhitespace(c)||c=='>'||c=='/')return i;
    }
    return -1;
  }
  /** Index just past the '>' that ends the end tag starting at {@code from}. */
  private static int closeTagEnd(String body,int from){
    int gt=body.indexOf('>',from);
    return gt<0?body.length():gt+1;
  }
  /** Every descendant whose tag is one of {@code tags}, in document order. */
  private static List<Node> descendants(Node root,String...tags){
    Set<String> wanted=new HashSet<>(Arrays.asList(tags));
    List<Node> found=new ArrayList<>();
    List<Node> stack=new ArrayList<>();
    for(int i=root.children.size()-1;i>=0;i--)stack.add(root.children.get(i));
    while(!stack.isEmpty()){
      Node node=stack.remove(stack.size()-1);
      if(wanted.contains(node.tag))found.add(node);
      for(int i=node.children.size()-1;i>=0;i--)stack.add(node.children.get(i));
    }
    return found;
  }
  /** Splits the class attribute into tokens, so {@code a b} matches both. */
  private static boolean hasClass(Node node,String token){
    for(String value:node.attr("class").split("\\s+")){
      if(value.equals(token))return true;
    }
    return false;
  }
  /** Reads one official settings page; mirrors account_settings.rs 276..417. */
  static Page parsePage(Section section,String pageUrl,String html)
      throws CloudFilesClient.CloudException{
    String text=html==null?"":html;
    Node root=tokenize(text);
    ensureSessionHtml(root);
    URI base=uri(pageUrl,"官方设置地址无效");
    String title="账号设置";
    for(Node node:descendants(root,"h1","h2","title")){
      String value=trim(node.text());
      if(!value.isEmpty()){title=value; break;}
    }
    List<Fact> facts=new ArrayList<>();
    for(Node row:all(root)){
      if(!hasClass(row,"mf"))continue;
      Node label=firstByClass(row,"mf1");
      Node value=firstByClass(row,"mf2");
      if(label==null||value==null)continue;
      facts.add(new Fact(trim(label.text()),trim(value.text())));
    }
    List<Form> forms=new ArrayList<>();
    List<Node> formNodes=descendants(root,"form");
    for(int index=0;index<formNodes.size();index++){
      Form form=parseForm(base,pageUrl,root,formNodes.get(index),index);
      if(form!=null)forms.add(form);
    }
    return new Page(section,title,facts,forms);
  }

  static String parsePhoneStatus(String html)throws CloudFilesClient.CloudException{
    Node root=tokenize(html);
    ensureSessionHtml(root);
    for(Node node:all(root)){
      if(node.attr("id").equals("phone_id"))return trim(node.text());
    }
    throw protocol("当前官方页面未提供接收短信手机号");
  }
  private static Form parseForm(URI base,String pageUrl,Node root,Node form,int index)
      throws CloudFilesClient.CloudException{
    if(!trim(form.attr("onsubmit")).isEmpty())return null;
    String action=form.attr("action");
    URI endpoint=uri(resolve(base,action.isEmpty()?pageUrl:action),"官方设置表单地址无效");
    if(!sameOrigin(endpoint,base))throw protocol("设置表单需要提交到官方账户服务");
    String method=trim(form.attr("method")).toUpperCase(java.util.Locale.ROOT);
    if(method.isEmpty())method="GET";
    if(!method.equals("GET")&&!method.equals("POST"))throw protocol("官方设置表单方法不受支持");
    Map<String,String> hidden=new LinkedHashMap<>();
    List<Field> fields=new ArrayList<>();
    String submitLabel="提交";
    boolean hasNativeSubmit=false;
    boolean hasJsOnlyButton=false;
    List<Node> controls=descendants(form,"input","select","textarea","button");
    for(Node control:controls){
      String kind=control.tag.equals("input")
          ?(control.attrs.containsKey("type")
              ?control.attr("type").toLowerCase(java.util.Locale.ROOT):"text")
          :control.tag;
      if(control.attrs.containsKey("disabled"))continue;
      boolean nativeSubmit=kind.equals("submit")
          ||(control.tag.equals("button")
              &&(!control.attrs.containsKey("type")
                  ||"submit".equalsIgnoreCase(trim(control.attr("type")))));
      if(nativeSubmit){
        if(!trim(control.attr("onclick")).isEmpty()){
          hasJsOnlyButton=true;
          continue;
        }
        hasNativeSubmit=true;
        String text=trim(control.attr("value"));
        submitLabel=text.isEmpty()?trim(control.text()):text;
        continue;
      }
      if(kind.equals("button")&&!trim(control.attr("onclick")).isEmpty()){
        hasJsOnlyButton=true;
      }
      if(!control.attrs.containsKey("name"))continue;
      String name=control.attr("name");
      String raw=control.attrs.containsKey("value")?control.attr("value"):"";
      if(kind.equals("hidden")||control.attrs.containsKey("readonly")){
        hidden.put(name,kind.equals("textarea")?control.text():raw);
        continue;
      }
      if(isSupported(kind)){
        Field field=parseField(control,kind,name,raw,root);
        if(kind.equals("radio")&&mergeRadio(fields,field))continue;
        fields.add(field);
      }
    }
    if(submitLabel.isEmpty())submitLabel="提交";
    return fields.isEmpty()||(!hasNativeSubmit&&hasJsOnlyButton)?null
        :new Form(Integer.toString(index),submitLabel,fields,endpoint.toString(),method,hidden);
  }
  private static Field parseField(Node control,String kind,String name,String raw,Node root){
    List<Option> options=new ArrayList<>();
    for(Node option:descendants(control,"option")){
      String optionLabel=trim(option.text());
      // An explicit value attribute is kept verbatim, even when empty.
      String value=option.attrs.containsKey("value")?option.attr("value"):optionLabel;
      options.add(new Option(optionLabel,value));
    }
    if(kind.equals("radio"))options.add(new Option(label(control,root,name),raw));
    String checkedValue=kind.equals("checkbox")
        ?(control.attrs.containsKey("value")?raw:"on"):null;
    boolean checked=control.attrs.containsKey("checked");
    String value;
    if(kind.equals("password"))value="";
    else if((kind.equals("radio")||kind.equals("checkbox"))&&!checked)value="";
    else if(kind.equals("checkbox"))value=checkedValue;
    else if(kind.equals("textarea"))value=control.text();
    else if(kind.equals("select"))value=selectValue(control);
    else value=raw;
    return new Field(name,label(control,root,name),kind,value,
        control.attrs.containsKey("required"),maxLength(control.attr("maxlength")),
        checkedValue,options);
  }
  /** Merges a second radio with the same name; returns true when merged. */
  private static boolean mergeRadio(List<Field> fields,Field field){
    for(int i=0;i<fields.size();i++){
      Field existing=fields.get(i);
      if(!existing.name.equals(field.name)||!existing.kind.equals("radio"))continue;
      List<Option> merged=new ArrayList<>(existing.options);
      merged.addAll(field.options);
      String value=field.value.isEmpty()?existing.value:field.value;
      Field group=new Field(existing.name,existing.label,existing.kind,value,
          existing.required||field.required,existing.maxLength,existing.checkedValue,merged);
      fields.set(i,group);
      return true;
    }
    return false;
  }
  private static String selectValue(Node select){
    Node first=null;
    for(Node option:descendants(select,"option")){
      if(first==null)first=option;
      if(!option.attrs.containsKey("selected"))continue;
      return optionValue(option);
    }
    return first==null?"":optionValue(first);
  }
  private static String optionValue(Node option){
    return option.attrs.containsKey("value")?option.attr("value"):trim(option.text());
  }
  /** Prefer explicit labels, then the official row caption before a machine field name. */
  private static String label(Node control,Node root,String name){
    String id=trim(control.attr("id"));
    if(!id.isEmpty()){
      for(Node candidate:descendants(root,"label")){
        if(id.equals(trim(candidate.attr("for"))))return trim(candidate.text());
      }
    }
    Node parent=control.parent;
    if(parent!=null&&parent.tag.equals("label"))return trim(parent.text());
    String placeholder=trim(control.attr("placeholder"));
    if(!placeholder.isEmpty())return placeholder;
    for(Node row=parent;row!=null&&!row.tag.equals("form");row=row.parent){
      if(!hasClass(row,"mf"))continue;
      Node caption=firstByClass(row,"mf1");
      if(caption!=null&&!trim(caption.text()).isEmpty())return trim(caption.text());
    }
    return name;
  }
  private static Node firstByClass(Node scope,String token){
    for(Node node:all(scope)){
      if(hasClass(node,token))return node;
    }
    return null;
  }
  private static Integer maxLength(String raw){
    String value=trim(raw);
    if(value.isEmpty())return null;
    for(int i=0;i<value.length();i++){
      if(!Character.isDigit(value.charAt(i)))return null;
    }
    try{
      int parsed=Integer.parseInt(value);
      return parsed<0?null:parsed;
    }catch(NumberFormatException tooLarge){
      return null;
    }
  }
  private static boolean isSupported(String kind){
    return kind.equals("text")||kind.equals("password")||kind.equals("email")
        ||kind.equals("tel")||kind.equals("number")||kind.equals("textarea")
        ||kind.equals("select")||kind.equals("checkbox")||kind.equals("radio");
  }
  /** Every descendant in document order, regardless of tag. */
  private static List<Node> all(Node root){
    List<Node> found=new ArrayList<>();
    List<Node> stack=new ArrayList<>();
    for(int i=root.children.size()-1;i>=0;i--)stack.add(root.children.get(i));
    while(!stack.isEmpty()){
      Node node=stack.remove(stack.size()-1);
      found.add(node);
      for(int i=node.children.size()-1;i>=0;i--)stack.add(node.children.get(i));
    }
    return found;
  }
  /** Resolves action against base, requiring same origin and no user info. */
  private static URI uri(String value,String message)throws CloudFilesClient.CloudException{
    try{
      URI parsed=new URI(value);
      if(parsed.getScheme()==null||parsed.getHost()==null)throw new URISyntaxException(value,"relative");
      return parsed;
    }catch(URISyntaxException invalid){
      throw protocol(message);
    }
  }
  private static String resolve(URI base,String reference)throws CloudFilesClient.CloudException{
    try{
      // URI.resolve treats a query-only reference as a sibling directory;
      // HTML form actions and Location headers keep the current page path.
      if(reference.startsWith("?")){
        String page=base.toString();
        int query=page.indexOf('?'),fragment=page.indexOf('#');
        int end=query<0?page.length():query;
        if(fragment>=0)end=Math.min(end,fragment);
        return page.substring(0,end)+reference;
      }
      return base.resolve(reference).toString();
    }catch(IllegalArgumentException invalid){
      throw protocol("官方设置表单地址无效");
    }
  }
  private static boolean sameOrigin(URI left,URI right){
    if(left.getUserInfo()!=null||right.getUserInfo()!=null)return false;
    if(!eq(left.getScheme(),right.getScheme()))return false;
    if(!eq(left.getHost(),right.getHost()))return false;
    return port(left)==port(right);
  }
  private static int port(URI uri){
    int explicit=uri.getPort();
    if(explicit!=-1)return explicit;
    return "https".equalsIgnoreCase(uri.getScheme())?443:80;
  }
  private static boolean eq(String left,String right){
    return left==null?right==null:left.equalsIgnoreCase(right==null?"":right);
  }
  /** Mirrors parser.rs ensure_session_html: title markers, else a real login form. */
  private static void ensureSessionHtml(Node root)throws CloudFilesClient.CloudException{
    if(root==null)return;
    StringBuilder title=new StringBuilder();
    for(Node node:descendants(root,"title"))title.append(node.text()).append(' ');
    String compact=compactWhitespace(title.toString());
    boolean loginTitle=compact.contains("网盘用户登录")||compact.contains("用户登录")
        ||compact.contains("登录蓝奏云");
    boolean password=false;
    boolean identity=false;
    for(Node node:descendants(root,"input")){
      String type=trim(node.attr("type")).toLowerCase(java.util.Locale.ROOT);
      String name=node.attr("name");
      if(type.equals("password")||name.equals("password")||name.equals("passwd"))password=true;
      // The login identity must be visible, so a hidden username is not a login form.
      if(!type.equals("hidden")
          &&(name.equals("username")||name.equals("account")||name.equals("loginName")))
        identity=true;
    }
    String visible=compactWhitespace(visibleText(root));
    boolean loginForm=password&&identity
        &&(visible.contains("登录")||visible.contains("登陆"));
    if(loginTitle||loginForm)throw sessionExpired();
  }
  /** Visible text outside script/style/noscript/template, per parser.rs. */
  private static String visibleText(Node root){
    StringBuilder text=new StringBuilder(root.ownText());
    collectVisible(root,text);
    return text.toString();
  }
  private static void collectVisible(Node node,StringBuilder text){
    for(Node child:node.children){
      String tag=child.tag;
      if(tag.equals("script")||tag.equals("style")||tag.equals("noscript")
          ||tag.equals("template")||!visible(child))continue;
      text.append(child.ownText()).append(' ');
      collectVisible(child,text);
    }
  }
  private static CloudFilesClient.CloudException protocol(String message){
    return new CloudFilesClient.CloudException(CloudFilesClient.ERROR_PROTOCOL,message);
  }
  private static CloudFilesClient.CloudException sessionExpired(){
    return new CloudFilesClient.CloudException(CloudFilesClient.ERROR_SESSION_EXPIRED,
        "登录状态已失效");
  }
  private static CloudFilesClient.CloudException invalid(String message){
    return new CloudFilesClient.CloudException(CloudFilesClient.ERROR_INVALID,message);
  }
  /** Drops Unicode whitespace code points the same way parser.rs joins titles. */
  private static String compactWhitespace(String value){
    StringBuilder compact=new StringBuilder();
    for(int i=0;i<value.length();){
      int codePoint=value.codePointAt(i);
      boolean space=Character.isWhitespace(codePoint)||Character.isSpaceChar(codePoint)
          ||codePoint==0x85;
      if(!space)compact.appendCodePoint(codePoint);
      i+=Character.charCount(codePoint);
    }
    return compact.toString();
  }
  private static String trim(String value){
    return CloudPremiumAccountProtocol.trimWhitespace(value);
  }
  private static String nz(String value){return value==null?"":value;}
  private static <T> List<T> copy(List<T> values){
    return values==null?Collections.<T>emptyList()
        :Collections.unmodifiableList(new ArrayList<>(values));
  }
  /** Follows only same-origin GETs, bounded so a redirect loop cannot hang. */
  private static final class Session implements Pages {
    private static final int MAX_HOPS=5;
    private final Transport transport;
    private final URI origin;
    private final String base;
    Session(Transport transport,URI origin,String base){
      this.transport=transport; this.origin=origin; this.base=base;
    }
    @Override public Page read(Section section)throws CloudFilesClient.CloudException{
      String url=base+section.path();
      String referer=url;
      Reply reply=transport.get(url,referer);
      for(int hop=0;;hop++){
        checkSession(reply.status,reply.location);
        if(!isRedirect(reply.status))break;
        if(reply.location.isEmpty())throw protocol("官方设置页面跳转缺少地址");
        if(hop>=MAX_HOPS)throw protocol("官方设置页面跳转次数过多");
        // A relative Location resolves against the page that produced it.
        String next=resolve(uri(url,"官方设置页面地址无效"),reply.location);
        URI target=uri(next,"官方设置页面跳转地址无效");
        if(!sameOrigin(target,origin))throw sessionExpired();
        reply=transport.get(next,referer);
        url=next;
      }
      requireOk(reply.status);
      return parsePage(section,url,reply.body);
    }
    @Override public Result submit(Section section,String formId,Map<String,String> fields)
        throws CloudFilesClient.CloudException{
      Page page=read(section);
      Form form=null;
      for(Form candidate:page.forms){
        if(candidate.id.equals(formId)){form=candidate; break;}
      }
      if(form==null)throw invalid("当前官方页面已无此表单，请刷新");
      Map<String,String> values=new LinkedHashMap<>(form.hidden);
      values.putAll(validated(form,fields));
      String endpoint=form.endpoint;
      String referer=base+section.path();
      Reply reply;
      if(form.method.equals("POST")){
        reply=transport.postForm(endpoint,values,referer);
      }else{
        reply=transport.get(withQuery(endpoint,values),referer);
      }
      return outcome(transport,section,form,reply);
    }
  }
  /** Rejects authenticated-page failures: 401/403 or a hop to the login host. */
  private static void checkSession(int status,String location)
      throws CloudFilesClient.CloudException{
    if(status==401||status==403)throw sessionExpired();
    if(isLoginLocation(location))throw sessionExpired();
  }
  /** Only a real redirect target on the login host counts; path text does not. */
  private static boolean isLoginLocation(String location){
    if(location==null||location.isEmpty())return false;
    try{
      return "accounts.woozooo.com".equalsIgnoreCase(new URI(location).getHost());
    }catch(URISyntaxException invalid){
      return false;
    }
  }
  private static boolean isRedirect(int status){
    return status==301||status==302||status==303||status==307||status==308;
  }
  private static void requireOk(int status)throws CloudFilesClient.CloudException{
    if(status<200||status>=300)throw protocol("官方设置服务返回 HTTP "+status);
  }
  /** Validates caller fields against the freshly parsed form, Rust-identical. */
  private static Map<String,String> validated(Form form,Map<String,String> supplied)
      throws CloudFilesClient.CloudException{
    Map<String,String> values=new LinkedHashMap<>();
    if(supplied!=null){
      for(Map.Entry<String,String> entry:supplied.entrySet()){
        String name=entry.getKey();
        Field field=null;
        for(Field candidate:form.fields){
          if(candidate.name.equals(name)){field=candidate; break;}
        }
        if(field==null)throw invalid("提交项不属于当前官方表单");
        String value=nz(entry.getValue());
        if(field.required&&value.isEmpty())throw invalid("请填写"+field.label);
        if(field.maxLength!=null&&value.codePointCount(0,value.length())>field.maxLength){
          throw invalid(field.label+"超过官方长度限制");
        }
        if(!field.options.isEmpty()&&!hasOption(field,value)){
          throw invalid("选项不属于当前官方表单");
        }
        if(!field.kind.equals("checkbox")||!value.isEmpty())values.put(name,value);
      }
    }
    for(Field field:form.fields){
      if(field.required&&(supplied==null||!supplied.containsKey(field.name))){
        throw invalid("请填写"+field.label);
      }
    }
    return values;
  }
  private static boolean hasOption(Field field,String value){
    for(Option option:field.options){
      if(option.value.equals(value))return true;
    }
    return false;
  }
  /** Appends encoded fields after any existing query, dropping the fragment. */
  private static String withQuery(String endpoint,Map<String,String> values){
    int hash=endpoint.indexOf('#');
    String url=hash<0?endpoint:endpoint.substring(0,hash);
    StringBuilder query=new StringBuilder();
    for(Map.Entry<String,String> entry:values.entrySet()){
      if(query.length()>0)query.append('&');
      query.append(encode(entry.getKey())).append('=').append(encode(entry.getValue()));
    }
    if(query.length()==0)return url;
    return url+(url.indexOf('?')<0?'?':(url.endsWith("?")||url.endsWith("&")?"":"&"))+query;
  }
  private static String encode(String value){
    try{
      return java.net.URLEncoder.encode(nz(value),"UTF-8");
    }catch(java.io.UnsupportedEncodingException impossible){
      throw new IllegalStateException(impossible);
    }
  }
  /** Turns the reply into exactly one confirmed result, or a hard failure. */
  private static Result outcome(Transport transport,Section section,Form form,Reply reply)
      throws CloudFilesClient.CloudException{
    checkSession(reply.status,reply.location);
    if(isRedirect(reply.status)&&!reply.location.isEmpty()){
      URI from=uri(form.endpoint,"官方设置表单地址无效");
      URI target=uri(resolve(from,reply.location),
          "官方返回的跳转地址无效");
      if(!sameOrigin(target,from)){
        if("accounts.woozooo.com".equalsIgnoreCase(target.getHost())){
          throw sessionExpired();
        }
        if(!isPaymentSection(section))throw protocol("官方设置返回了本页以外的跳转，请刷新核对");
        if(!"https".equalsIgnoreCase(target.getScheme())||target.getUserInfo()!=null){
          throw protocol("官方支付入口不是安全的 HTTPS 地址");
        }
        return new Result(false,"官方要求完成支付",null,target.toString());
      }
      Followed followed=followSameOrigin(transport,target,form.endpoint);
      return success(section,followed.reply,followed.url);
    }
    requireOk(reply.status);
    return success(section,reply,form.endpoint);
  }
  private static boolean isPaymentSection(Section section){
    return section==Section.MEMBERSHIP||section==Section.BALANCE
        ||section==Section.FILE_UPGRADE;
  }
  /** The terminal reply together with the URL that produced it. */
  private static final class Followed {
    final Reply reply; final String url;
    Followed(Reply reply,String url){this.reply=reply; this.url=url;}
  }
  private static Followed followSameOrigin(Transport transport,URI target,String referer)
      throws CloudFilesClient.CloudException{
    URI current=target;
    for(int hop=0;;hop++){
      Reply reply=transport.get(current.toString(),referer);
      checkSession(reply.status,reply.location);
      if(!isRedirect(reply.status)||reply.location.isEmpty()){
        if(isRedirect(reply.status))throw protocol("官方设置页面跳转缺少地址");
        requireOk(reply.status);
        return new Followed(reply,current.toString());
      }
      if(hop>=5)throw protocol("官方设置页面跳转次数过多");
      URI next=uri(resolve(current,reply.location),"官方设置页面跳转地址无效");
      if(!sameOrigin(next,current))throw protocol("官方设置返回了本页以外的跳转，请刷新核对");
      current=next;
    }
  }
  /** zt=1 JSON confirms; otherwise exact success text on an HTML page confirms. */
  private static Result success(Section section,Reply reply,String url)
      throws CloudFilesClient.CloudException{
    String body=reply.body;
    String trimmed=body.trim();
    if(trimmed.startsWith("{")){
      Result json=jsonOutcome(trimmed);
      if(json!=null)return json;
      throw protocol("官方未确认设置提交");
    }
    Page page=parsePage(section,url,body);
    String message=successMessage(body);
    if(message==null)throw protocol("官方已响应但未明确确认");
    return new Result(true,message,page,"");
  }
  /** Accepts only integer zt==1 (or "1"); zt==9 means the session expired. */
  private static Result jsonOutcome(String json)throws CloudFilesClient.CloudException{
    org.json.JSONObject value;
    try{
      value=new org.json.JSONObject(json);
    }catch(org.json.JSONException malformed){
      return null;
    }
    Integer status=integer(value,"zt");
    if(status==null)return null;
    if(status==9)throw sessionExpired();
    if(status!=1)throw protocol(message(value,"官方未确认设置提交"));
    return new Result(true,message(value,"官方已确认提交"),null,"");
  }
  private static Integer integer(org.json.JSONObject value,String name){
    Object raw=value.opt(name);
    if(raw instanceof Float||raw instanceof Double)return null;
    if(!(raw instanceof Number)&&!(raw instanceof String))return null;
    String text=raw.toString();
    if(!text.matches("[+-]?[0-9]+"))return null;
    try{return Integer.parseInt(text);}catch(NumberFormatException tooLarge){return null;}
  }
  private static String message(org.json.JSONObject value,String fallback){
    String text=value.optString("info","");
    if(text.isEmpty())text=value.optString("inf","");
    return text.isEmpty()?fallback:text;
  }
  /** An explicit visible success message is the only HTML confirmation. */
  private static String successMessage(String html){
    Node root=tokenize(html==null?"":html);
    for(Node node:all(root)){
      boolean marker=hasClass(node,"success")||hasClass(node,"message")
          ||"w_info".equals(node.attr("id"));
      if(!marker)continue;
      if(!visible(node))continue;
      String message=visibleText(node);
      if(confirmsSuccess(compactWhitespace(message)))return message.trim();
    }
    return null;
  }
  private static boolean visible(Node node){
    for(Node current=node;current!=null;current=current.parent){
      if(current.tag.equals("template")||current.tag.equals("noscript")
          ||current.attrs.containsKey("hidden"))return false;
      String style=compactWhitespace(current.attr("style")).toLowerCase(java.util.Locale.ROOT);
      if(style.contains("display:none")||style.contains("visibility:hidden"))return false;
    }
    return true;
  }
  /** "成功" counts only when no nearby negation precedes it (未成功/不成功/没有成功). */
  private static boolean confirmsSuccess(String compact){
    int index=compact.indexOf("成功");
    while(index>=0){
      String prefix=compact.substring(Math.max(0,index-3),index);
      if(!prefix.contains("未")&&!prefix.contains("不")&&!prefix.contains("没"))return true;
      index=compact.indexOf("成功",index+2);
    }
    return false;
  }
}
