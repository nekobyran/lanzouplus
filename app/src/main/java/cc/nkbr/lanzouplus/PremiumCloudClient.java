package cc.nkbr.lanzouplus;

import android.annotation.SuppressLint;
import android.content.Context;
import android.content.SharedPreferences;
import android.os.Build;
import android.security.keystore.KeyGenParameterSpec;
import android.security.keystore.KeyProperties;
import android.util.Base64;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.UnsupportedEncodingException;
import java.net.HttpURLConnection;
import java.net.URI;
import java.net.URL;
import java.net.URLDecoder;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.security.KeyStore;
import java.security.MessageDigest;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TimeZone;
import java.util.UUID;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import javax.crypto.Cipher;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;

/**
 * Small synchronous client for the public LanZou premium-web contract.
 * Call every network method from a worker thread. Account data never leaves
 * the official API host and is persisted only as an AndroidKeyStore AES-GCM blob.
 */
final class PremiumCloudClient {
  static final int ERROR_CREDENTIALS_REQUIRED=-10001;
  static final int ERROR_CREDENTIALS_UNREADABLE=-10002;
  static final int ERROR_NETWORK=-10003;
  static final int ERROR_PROTOCOL=-10004;

  private static final String API_ORIGIN="https://apis.ilanzou.com";
  private static final String WEB_ORIGIN="https://www.ilanzou.com";
  private static final String GET_UUID="/unproved/getUuid";
  private static final String LOGIN="/unproved/login";
  private static final String USER_INFO="/proved/user/info/map";
  private static final String TRANSFER="/proved/pd/transfer";
  private static final String WEB_CIPHER_KEY="lanZouY-disk-app";
  private static final String PREFS="premium-session-v1";
  private static final String PREF_UUID="uuid";
  private static final String PREF_IV="iv";
  private static final String PREF_SECRET="secret";
  private static final String KEY_ALIAS="cc.nkbr.lanzouplus.premium.v1";
  private static final long SESSION_CACHE_MS=60_000L;
  private static final int MAX_JSON_BYTES=256*1024;
  private static final int MAX_REDIRECT_BODY_BYTES=64*1024;
  private static final int MAX_REDIRECTS=5;
  private static final Pattern AUTH_QUERY=Pattern.compile("(?i)(?:[?&#]|^|\\\\u0026)auth=([^&#\\\"'\\s]+)");
  private static final Pattern AUTH_JSON=Pattern.compile("(?i)[\\\"']auth[\\\"']\\s*:\\s*[\\\"']([^\\\"']+)");
  private static final Pattern PHONE=Pattern.compile("(?<!\\d)1\\d{10}(?!\\d)");
  private static final Pattern SECRET_PARAM=Pattern.compile("(?i)(appToken|auth|loginPwd|password|loginName)(?:=|\\s*:)\\s*[^&\\s,}]+(?=$|[&\\s,}])");
  private static final Pattern LONG_TOKEN=Pattern.compile("(?<![A-Za-z0-9_-])[A-Za-z0-9_-]{24,}(?![A-Za-z0-9_-])");

  private final Context context;
  private final SharedPreferences preferences;
  private final Object sessionLock=new Object();
  private final Map<String,SessionEntry> sessions=new HashMap<>();
  private final Map<String,Object> accountLocks=new HashMap<>();

  PremiumCloudClient(Context context){
    this.context=context.getApplicationContext();
    preferences=this.context.getSharedPreferences(PREFS,Context.MODE_PRIVATE);
  }

  static final class LoginState {
    final boolean configured;
    final boolean loggedIn;
    final boolean premium;
    final String userId;
    final String account;
    LoginState(boolean configured,boolean loggedIn,boolean premium){
      this(configured,loggedIn,premium,"","");
    }
    LoginState(boolean configured,boolean loggedIn,boolean premium,String userId){
      this(configured,loggedIn,premium,userId,"");
    }
    LoginState(boolean configured,boolean loggedIn,boolean premium,String userId,String account){
      this.configured=configured;this.loggedIn=loggedIn;this.premium=premium;
      this.userId=userId==null?"":userId;this.account=account==null?"":account;
    }
  }

  static final class SaveResult {
    final boolean saved;
    final String message;
    SaveResult(boolean saved,String message){this.saved=saved;this.message=message;}
  }

  /** Per-account state for multi-account verification; never exposes credentials. */
  static final class AccountLoginState {
    final String accountName;
    final LoginState state;
    final int errorCode;
    final String message;
    AccountLoginState(String accountName,LoginState state,int errorCode,String message){
      this.accountName=accountName;this.state=state;this.errorCode=errorCode;this.message=message==null||message.isEmpty()?"":redact(message);
    }
  }

  /** Per-account save result; the account field is only the login name. */
  static final class AccountSaveResult {
    final String accountName;
    final boolean saved;
    final int errorCode;
    final String message;
    AccountSaveResult(String accountName,boolean saved,int errorCode,String message){
      this.accountName=accountName;this.saved=saved;this.errorCode=errorCode;this.message=message==null||message.isEmpty()?"":redact(message);
    }
  }

  static final class CloudException extends Exception {
    private final int code;
    private final boolean sessionExpired;
    CloudException(int code,String safeMessage){super(redact(safeMessage));this.code=code;sessionExpired=code==-2;}
    // Do not retain transport/JSON exceptions: their messages may contain a signed URL or token.
    CloudException(int code,String safeMessage,Throwable ignored){super(redact(safeMessage));this.code=code;sessionExpired=code==-2;}
    int code(){return code;}
    boolean needsCredentials(){return code==ERROR_CREDENTIALS_REQUIRED||code==ERROR_CREDENTIALS_UNREADABLE;}
    boolean sessionExpired(){return sessionExpired;}
  }

  static final class CancelToken {
    private final Object lock=new Object();
    private final Set<HttpURLConnection> connections=new HashSet<>();
    private boolean cancelled;

    void check()throws CloudException{synchronized(lock){if(cancelled)throw new CloudException(ERROR_NETWORK,"操作已中断");}}
    boolean isCancelled(){synchronized(lock){return cancelled;}}
    void register(HttpURLConnection connection)throws CloudException{synchronized(lock){if(cancelled){connection.disconnect();throw new CloudException(ERROR_NETWORK,"操作已中断");}connections.add(connection);}}
    void unregister(HttpURLConnection connection){if(connection==null)return;synchronized(lock){connections.remove(connection);}}
        void cancel(){List<HttpURLConnection> active;synchronized(lock){if(cancelled)return;cancelled=true;active=new ArrayList<>(connections);connections.clear();}for(HttpURLConnection connection:active)connection.disconnect();}

  }

  /** Fast local check used to decide whether the account dialog is needed. */
  boolean hasStoredLogin(){return preferences.contains(PREF_IV)&&preferences.contains(PREF_SECRET);}

  /** Snapshot of stored login names in insertion order. Passwords and tokens are never returned. */
  List<String> accountNames()throws CloudException{
    synchronized(sessionLock){return Collections.unmodifiableList(new ArrayList<>(loadVault().accounts.keySet()));}
  }

  String currentAccountName()throws CloudException{
    synchronized(sessionLock){return loadVault().current;}
  }

  boolean selectAccount(String loginName)throws CloudException{
    String name=normalizedName(loginName);
    synchronized(sessionLock){
      Vault vault=loadVault();
      if(!vault.accounts.containsKey(name))return false;
      if(!name.equals(vault.current)){vault.current=name;persist(vault);}
      return true;
    }
  }

  boolean removeAccount(String loginName)throws CloudException{
    String name=normalizedName(loginName);
    synchronized(sessionLock){
      Vault vault=loadVault();
      if(vault.accounts.remove(name)==null)return false;
      sessions.remove(name);accountLocks.remove(name);
      if(vault.accounts.isEmpty()){clearStoredLogin();return true;}
      if(name.equals(vault.current))vault.current=vault.accounts.keySet().iterator().next();
      persist(vault);return true;
    }
  }

  /** Login once, verify the returned token, then atomically replace the encrypted blob. */
  LoginState loginAndRemember(String loginName,String loginPassword)throws CloudException{
    if(loginName==null||loginName.trim().isEmpty()||loginPassword==null||loginPassword.isEmpty())
      throw new CloudException(ERROR_CREDENTIALS_REQUIRED,"请输入账号和密码");
    String name=loginName.trim();
    synchronized(accountLock(name)){
      Credentials credentials=login(name,loginPassword);
      LoginState state=readLoginState(credentials.appToken);
      synchronized(sessionLock){
        Vault vault=loadVault();vault.accounts.put(name,credentials);vault.current=name;persist(vault);
        sessions.put(name,new SessionEntry(state,System.currentTimeMillis()));
      }
      return state;
    }
  }

  /** Check the server session and transparently re-login once when it has expired. */
  LoginState ensureLoggedIn()throws CloudException{
    return ensureLoggedIn(requiredCurrentAccount());
  }

  LoginState ensureLoggedIn(String loginName)throws CloudException{
    String name=normalizedName(loginName);
    synchronized(accountLock(name)){
      Credentials credentials;SessionEntry cached;long now=System.currentTimeMillis();
      synchronized(sessionLock){
        credentials=loadVault().accounts.get(name);cached=sessions.get(name);
      }
      if(credentials==null)throw new CloudException(ERROR_CREDENTIALS_REQUIRED,"未找到所选蓝奏云优享账号");
      if(cached!=null&&now-cached.verifiedAt<SESSION_CACHE_MS)return cached.state;
      try{
        LoginState state=readLoginState(credentials.appToken);
        synchronized(sessionLock){sessions.put(name,new SessionEntry(state,now));}
        return state;
      }catch(CloudException error){
        if(!error.sessionExpired())throw error;
        Credentials renewed=login(credentials.loginName,credentials.loginPassword);
        LoginState state=readLoginState(renewed.appToken);
        synchronized(sessionLock){
          Vault vault=loadVault();
          if(!vault.accounts.containsKey(name))throw new CloudException(ERROR_CREDENTIALS_REQUIRED,"所选账号已删除");
          vault.accounts.put(name,renewed);persist(vault);
          sessions.put(name,new SessionEntry(state,System.currentTimeMillis()));
        }
        return state;
      }
    }
  }

  /** Returns a non-throwing local state when no account has ever been configured. */
  LoginState queryLoginState()throws CloudException{
    if(!hasStoredLogin())return new LoginState(false,false,false);
    return ensureLoggedIn();
  }

  LoginState queryLoginState(String loginName)throws CloudException{
    if(!hasStoredLogin())return new LoginState(false,false,false);
    return ensureLoggedIn(loginName);
  }

  /** Verify every stored account, concurrently when requested, while retaining per-account failures. */
  List<AccountLoginState> queryAllLoginStates(boolean concurrent)throws CloudException{
    List<String> names=accountNames();
    return runAccounts(names,concurrent,name->{
      try{return new AccountLoginState(name,ensureLoggedIn(name),0,"");}
      catch(CloudException error){return new AccountLoginState(name,new LoginState(true,false,false),error.code(),error.getMessage());}
    });
  }

  List<AccountLoginState> ensureAllLoggedIn(boolean concurrent)throws CloudException{
    return queryAllLoginStates(concurrent);
  }

  /** Resolve a fresh transfer auth value and save the shared folder to the account. */
  SaveResult saveFromShare(String saveUrl)throws CloudException{
    return saveFromShare(saveUrl,requiredCurrentAccount());
  }

  SaveResult saveFromShare(String saveUrl,String loginName)throws CloudException{
    return saveFromShare(saveUrl,loginName,null);
  }

  SaveResult saveFromShare(String saveUrl,String loginName,CancelToken cancel)throws CloudException{
    check(cancel);String name=normalizedName(loginName),auth=resolveTransferAuth(saveUrl,cancel);check(cancel);
    return saveAuth(auth,name,cancel);
  }

  /** Resolve the share authorization once, then save with all accounts in parallel or order. */
  List<AccountSaveResult> saveFromShareAll(String saveUrl,boolean concurrent)throws CloudException{
    return saveFromShareAccounts(saveUrl,accountNames(),concurrent);
  }

  /** Resolve once and save only to the explicitly chosen accounts. */
  List<AccountSaveResult> saveFromShareAccounts(String saveUrl,List<String> loginNames,boolean concurrent)throws CloudException{
    List<String> names=new ArrayList<>();
    if(loginNames!=null)for(String loginName:loginNames){String name=normalizedName(loginName);if(!names.contains(name))names.add(name);}
    if(names.isEmpty())throw new CloudException(ERROR_CREDENTIALS_REQUIRED,"请先登录蓝奏云优享版");
    String auth=resolveTransferAuth(saveUrl);
    return runAccounts(names,concurrent,name->{
      try{SaveResult result=saveAuth(auth,name);return new AccountSaveResult(name,result.saved,0,result.message);}
      catch(CloudException error){return new AccountSaveResult(name,false,error.code(),error.getMessage());}
    });
  }

  private SaveResult saveAuth(String auth,String loginName)throws CloudException{return saveAuth(auth,loginName,null);}

  private SaveResult saveAuth(String auth,String loginName,CancelToken cancel)throws CloudException{
    check(cancel);
    ensureLoggedIn(loginName);
    check(cancel);
    Credentials credentials=credentials(loginName);
    ApiReply reply=transfer(auth,credentials.appToken,cancel);
    if(reply.expired()){
      synchronized(accountLock(loginName)){
        credentials=credentials(loginName);
        credentials=login(credentials.loginName,credentials.loginPassword);
        synchronized(sessionLock){
          Vault vault=loadVault();
          if(!vault.accounts.containsKey(loginName))throw new CloudException(ERROR_CREDENTIALS_REQUIRED,"所选账号已删除");
          vault.accounts.put(loginName,credentials);persist(vault);sessions.remove(loginName);
        }
      }
      check(cancel);reply=transfer(auth,credentials.appToken,cancel);
    }
    requireSuccess(reply,"保存失败");
    return new SaveResult(true,reply.message.isEmpty()?"保存成功":redact(reply.message));
  }

  /** Remove encrypted credentials and the associated keystore key. UUID is harmless and retained. */
  void clearLogin(){
    synchronized(sessionLock){clearStoredLogin();}
  }

  // ---- authenticated file management -------------------------------------

  private String premiumFolder(String folderId)throws CloudException{
    String id=folderId==null?"":folderId.trim();
    if(id.isEmpty())throw new CloudException(ERROR_PROTOCOL,"folderId 不能为空");
    if("-1".equals(id))return "0";
    if(id.length()>64)throw new CloudException(ERROR_PROTOCOL,"folderId 过长");
    for(int index=0;index<id.length();index++){
      char ch=id.charAt(index);
      if(ch<'0'||ch>'9')throw new CloudException(ERROR_PROTOCOL,"folderId 必须是数字 ID");
    }
    return id;
  }

  private String tokenFor(String loginName)throws CloudException{
    ensureLoggedIn(loginName);
    return credentials(loginName).appToken;
  }

  CloudFilesClient.CloudPage cloudListFolder(String folderId,int page,int pageSize)
      throws CloudException{
    if(page<1||page>10_000)throw new CloudException(ERROR_PROTOCOL,"页码必须在 1 到 10000 之间");
    if(pageSize<1||pageSize>200)throw new CloudException(ERROR_PROTOCOL,"每页数量必须在 1 到 200 之间");
    String name=requiredCurrentAccount();
    String token=tokenFor(name);
    String folder=premiumFolder(folderId);
    ApiReply reply=request("GET","/proved/record/file/list?offset="+page+"&limit="+pageSize
        +"&folderId="+folder+"&type=0",null,token);
    requireSuccess(reply,"读取文件列表失败");
    JSONArray list=reply.root.optJSONArray("list");
    if(list==null)throw new CloudException(ERROR_PROTOCOL,"优享响应缺少文件列表");
    List<CloudFilesClient.CloudItem> items=new ArrayList<>();
    for(int index=0;index<list.length();index++){
      JSONObject entry=list.optJSONObject(index);
      if(entry!=null)items.add(premiumItem(entry));
    }
    int totalPage=reply.root.optInt("totalPage",0);
    int total=reply.root.optInt("total",items.size());
    boolean hasMore=totalPage>0?page<totalPage:items.size()>=pageSize;
    return new CloudFilesClient.CloudPage(items,page,hasMore,total);
  }

  private List<CloudFilesClient.CloudItem> premiumAll(String token,String folder)
      throws CloudException{
    List<CloudFilesClient.CloudItem> items=new ArrayList<>();
    for(int page=1;page<=10_000;page++){
      ApiReply reply=request("GET","/proved/record/file/list?offset="+page
          +"&limit=100&folderId="+folder+"&type=0",null,token);
      requireSuccess(reply,"读取文件列表失败");
      JSONArray list=reply.root.optJSONArray("list");
      if(list==null)throw new CloudException(ERROR_PROTOCOL,"优享响应缺少文件列表");
      for(int index=0;index<list.length();index++){
        JSONObject entry=list.optJSONObject(index);
        if(entry!=null)items.add(premiumItem(entry));
      }
      int totalPage=reply.root.optInt("totalPage",0);
      if(totalPage<=0?list.length()<100:page>=totalPage)return items;
    }
    throw new CloudException(ERROR_PROTOCOL,"优享目录分页超过上限");
  }

  private static CloudFilesClient.CloudItem premiumItem(JSONObject entry)throws CloudException{
    boolean folder=entry.optInt("fileType",0)==2;
    String id=scalar(entry.opt(folder?"folderId":"fileId"));
    String name=entry.optString(folder?"folderName":"fileName","");
    String description=entry.optString(folder?"folderDesc":"fileDesc","");
    if(id.isEmpty()||name.isEmpty())
      throw new CloudException(ERROR_PROTOCOL,(folder?"优享文件夹":"优享文件")+"缺少标识或名称");
    long size=number(entry.opt(folder?"folderSize":"fileSize"));
    String modified=firstNonEmpty(entry.optString("updTime",""),entry.optString("addTime",""));
    return new CloudFilesClient.CloudItem(id,name,folder,size,modified,description,false,0,"",false,"");
  }

  List<CloudFilesClient.CloudItem> cloudMoveTargets()throws CloudException{
    String name=requiredCurrentAccount();
    String token=tokenFor(name);
    List<CloudFilesClient.CloudItem> result=new ArrayList<>();
    result.add(new CloudFilesClient.CloudItem("-1","蓝奏云优享版",true,0,"","",false,0,"/",false,""));
    java.util.ArrayDeque<String[]> pending=new java.util.ArrayDeque<>();
    Set<String> visited=new HashSet<>();
    pending.add(new String[]{"0","/"});
    int emitted=0;
    while(!pending.isEmpty()&&emitted<10_000){
      String[] pair=pending.removeFirst();
      if(!visited.add(pair[0]))continue;
      for(CloudFilesClient.CloudItem item:premiumAll(token,pair[0])){
        if(!item.folder)continue;
        String path=(pair[1].endsWith("/")?pair[1]:pair[1]+"/")+item.name;
        result.add(item.withPath(path));
        pending.addLast(new String[]{item.id,path});
        emitted++;
      }
    }
    return result;
  }

  String cloudCreateFolder(String parentId,String name,String description)throws CloudException{
    if(name==null||name.trim().isEmpty()||name.length()>255||containsControl(name))
      throw new CloudException(ERROR_PROTOCOL,"文件夹名称无效");
    if(description==null)description="";
    if(description.length()>4000||containsControl(description))
      throw new CloudException(ERROR_PROTOCOL,"文件夹简介过长或包含非法字符");
    String account=requiredCurrentAccount();
    String token=tokenFor(account);
    String parent=premiumFolder(parentId);
    Set<String> before=matchingFolders(premiumAll(token,parent),name);
    JSONObject body=new JSONObject();
    try{body.put("folderDesc",description).put("folderId",parent).put("folderName",name);}
    catch(Exception impossible){throw new CloudException(ERROR_PROTOCOL,"创建参数无效");}
    ApiReply reply=request("POST","/proved/file/folder/save",body,token);
    requireSuccess(reply,"创建文件夹失败");
    String id=firstScalar(reply.root.optJSONObject("data"),"folderId","id");
    if(id.isEmpty())id=firstScalar(reply.root.optJSONObject("map"),"folderId","id");
    if(id.isEmpty())id=firstScalar(reply.root,"folderId","id");
    if(!id.isEmpty()&&isNumeric(id))return id;
    Set<String> after=matchingFolders(premiumAll(token,parent),name);
    after.removeAll(before);
    if(after.size()!=1)
      throw new CloudException(ERROR_PROTOCOL,after.isEmpty()
          ?"官方已确认创建但未返回新文件夹 ID":"创建后出现多个同名文件夹，无法确定新目录");
    return after.iterator().next();
  }

  void cloudRename(CloudFilesClient.CloudItem item,String name)throws CloudException{
    if(item==null)throw new CloudException(ERROR_PROTOCOL,"请选择文件或文件夹");
    if(name==null||name.trim().isEmpty()||name.length()>255||containsControl(name))
      throw new CloudException(ERROR_PROTOCOL,"名称无效");
    String account=requiredCurrentAccount();
    String token=tokenFor(account);
    JSONObject body=new JSONObject();
    try{
      if(item.folder)body.put("folderDesc",item.description).put("folderId",item.id).put("folderName",name);
      else body.put("fileDesc",item.description).put("fileId",item.id).put("fileName",name);
    }catch(Exception impossible){throw new CloudException(ERROR_PROTOCOL,"重命名参数无效");}
    requireSuccess(request("POST",item.folder?"/proved/file/folder/edit":"/proved/file/edit",body,token),
        "重命名失败");
  }

  void cloudMove(CloudFilesClient.CloudItem item,String targetId)throws CloudException{
    if(item==null)throw new CloudException(ERROR_PROTOCOL,"请选择文件或文件夹");
    String account=requiredCurrentAccount();
    String token=tokenFor(account);
    String target=premiumFolder(targetId);
    JSONObject body=new JSONObject();
    try{
      body.put("folderIds",item.folder?item.id:"").put("fileIds",item.folder?"":item.id)
          .put("targetId",target);
    }catch(Exception impossible){throw new CloudException(ERROR_PROTOCOL,"移动参数无效");}
    requireSuccess(request("POST","/proved/file/folder/move",body,token),"移动文件失败");
  }

  void cloudMoveMany(List<CloudFilesClient.CloudItem> items,String targetId)throws CloudException{
    if(items==null||items.isEmpty())throw new CloudException(ERROR_PROTOCOL,"请选择要移动的项目");
    String account=requiredCurrentAccount();
    String token=tokenFor(account);
    StringBuilder folders=new StringBuilder(),files=new StringBuilder();
    for(CloudFilesClient.CloudItem item:items){
      if(item==null)continue;
      StringBuilder target=item.folder?folders:files;
      if(target.length()>0)target.append(',');
      target.append(item.id);
    }
    JSONObject body=new JSONObject();
    try{body.put("folderIds",folders.toString()).put("fileIds",files.toString())
        .put("targetId",premiumFolder(targetId));}
    catch(Exception impossible){throw new CloudException(ERROR_PROTOCOL,"移动参数无效");}
    requireSuccess(request("POST","/proved/file/folder/move",body,token),"批量移动失败");
  }

  void cloudDelete(CloudFilesClient.CloudItem item)throws CloudException{
    if(item==null)throw new CloudException(ERROR_PROTOCOL,"请选择文件或文件夹");
    String account=requiredCurrentAccount();
    String token=tokenFor(account);
    JSONObject body=new JSONObject();
    try{body.put("folderIds",item.folder?item.id:"").put("fileIds",item.folder?"":item.id)
        .put("status",0);}
    catch(Exception impossible){throw new CloudException(ERROR_PROTOCOL,"删除参数无效");}
    requireSuccess(request("POST","/proved/file/delete",body,token),"删除文件失败");
  }

  void cloudDeleteMany(List<CloudFilesClient.CloudItem> items)throws CloudException{
    if(items==null||items.isEmpty())throw new CloudException(ERROR_PROTOCOL,"请选择要删除的项目");
    String account=requiredCurrentAccount();
    String token=tokenFor(account);
    StringBuilder folders=new StringBuilder(),files=new StringBuilder();
    for(CloudFilesClient.CloudItem item:items){
      if(item==null)continue;
      StringBuilder target=item.folder?folders:files;
      if(target.length()>0)target.append(',');
      target.append(item.id);
    }
    JSONObject body=new JSONObject();
    try{body.put("folderIds",folders.toString()).put("fileIds",files.toString()).put("status",0);}
    catch(Exception impossible){throw new CloudException(ERROR_PROTOCOL,"删除参数无效");}
    requireSuccess(request("POST","/proved/file/delete",body,token),"批量删除失败");
  }

  CloudFilesClient.ShareInfo cloudShareInfo(CloudFilesClient.CloudItem item)throws CloudException{
    if(item==null)throw new CloudException(ERROR_PROTOCOL,"请选择文件或文件夹");
    String account=requiredCurrentAccount();
    LoginState state=ensureLoggedIn(account);
    String token=credentials(account).appToken;
    JSONObject existing=existingShare(token,item.id,item.folder);
    JSONObject body=shareBody(item,existing,"");
    ApiReply reply=request("POST","/proved/share/url",body,token);
    requireSuccess(reply,"创建分享失败");
    String shareUrl=firstNonEmpty(reply.root.optString("shareUrl",""),
        reply.root.optJSONObject("data")==null?"":reply.root.optJSONObject("data").optString("shareUrl",""));
    if(shareUrl.isEmpty())throw new CloudException(ERROR_PROTOCOL,"优享分享响应缺少真实分享地址");
    if(!trustedPremiumShare(shareUrl))throw new CloudException(ERROR_PROTOCOL,"优享分享地址不是官方分享");
    String code=body.optString("code","");
    String direct="";
    if(!item.folder){
      try{direct=downloadRedirect(token,state.userId,item.id);}catch(CloudException ignored){}
    }
    String resource=premiumResource(shareUrl,code);
    String mode="";
    int parts=0;
    String links="";
    if(item.folder){
      try{
        String rawDesc=currentFolderDescription(token,item);
        OrdinaryCloudClient.FragmentManifest manifest=OrdinaryCloudClient.FragmentManifest.parse(rawDesc);
        if(manifest!=null){
          links=premiumFragmentShareLinks(token,item.id,manifest);
          mode=manifest.mode;
          parts=manifest.partCount;
        }
      }catch(CloudFilesClient.CloudException error){
        throw new CloudException(ERROR_PROTOCOL,error.getMessage());
      }
    }
    String rawDesc=item.folder?currentFolderDescription(token,item):item.description;
    String desc=item.folder?OrdinaryCloudClient.visibleDescription(rawDesc):rawDesc;
    return new CloudFilesClient.ShareInfo(item.id,item.folder,item.name,shareUrl,code,
        desc,direct,resource,parts>0,mode,parts,links);
  }

  private String currentFolderDescription(String token,CloudFilesClient.CloudItem item)throws CloudException{
    if(item==null||!item.folder)return item==null?"":item.description;
    java.util.ArrayDeque<String> queue=new java.util.ArrayDeque<>();
    queue.add("0");
    Set<String> visited=new HashSet<>();
    while(!queue.isEmpty()){
      String current=queue.poll();
      if(!visited.add(current))continue;
      for(CloudFilesClient.CloudItem child:premiumAll(token,current)){
        if(child.folder){
          if(child.id.equals(item.id))return child.description;
          queue.add(child.id);
        }
      }
    }
    return item.description;
  }

  private String premiumFragmentShareLinks(String token,String folderId,OrdinaryCloudClient.FragmentManifest manifest)
      throws CloudException{
    Map<Integer,String> childFolderIds=new LinkedHashMap<>();
    for(CloudFilesClient.CloudItem child:premiumAll(token,premiumFolder(folderId))){
      if(!child.folder)continue;
      try{
        int index=Integer.parseInt(child.name);
        if(index>0&&index<=manifest.partCount){
          if(childFolderIds.put(index,child.id)!=null)
            throw new CloudException(ERROR_PROTOCOL,"分片目录存在重复数字子目录");
        }
      }catch(NumberFormatException ignored){}
    }
    if(childFolderIds.size()!=manifest.partCount)
      throw new CloudException(ERROR_PROTOCOL,"分片目录缺少数字子目录");
    List<String> links=new ArrayList<>();
    for(int index=1;index<=manifest.partCount;index++){
      String childId=childFolderIds.get(index);
      String expected;
      try{expected=manifest.uploadFileName(index);}
      catch(CloudFilesClient.CloudException error){throw new CloudException(ERROR_PROTOCOL,error.getMessage());}
      String fileId="";
      for(CloudFilesClient.CloudItem partItem:premiumAll(token,premiumFolder(childId))){
        if(!partItem.folder&&expected.equals(partItem.name)){
          fileId=partItem.id;
          break;
        }
      }
      if(fileId.isEmpty())
        throw new CloudException(ERROR_PROTOCOL,"分片子目录 "+index+" 中未找到 "+expected);
      String partShareUrl=premiumShareUrl(token,fileId,false);
      try{
        links.add(manifest.partUri(index,partShareUrl));
      }catch(CloudFilesClient.CloudException error){
        throw new CloudException(ERROR_PROTOCOL,error.getMessage());
      }
    }
    return OrdinaryCloudClient.joinLines(links);
  }

  private String premiumShareUrl(String token,String id,boolean folder)throws CloudException{
    JSONObject existing=existingShare(token,id,folder);
    CloudFilesClient.CloudItem target=new CloudFilesClient.CloudItem(id,"",folder,0,"","",false,0,"",false,"");
    JSONObject body=shareBody(target,existing,"");
    ApiReply reply=request("POST","/proved/share/url",body,token);
    requireSuccess(reply,"创建分享失败");
    String shareUrl=firstNonEmpty(reply.root.optString("shareUrl",""),
        reply.root.optJSONObject("data")==null?"":reply.root.optJSONObject("data").optString("shareUrl",""));
    if(shareUrl.isEmpty())throw new CloudException(ERROR_PROTOCOL,"优享分享响应缺少真实分享地址");
    if(!trustedPremiumShare(shareUrl))throw new CloudException(ERROR_PROTOCOL,"优享分享地址不是官方分享");
    return shareUrl;
  }

  private JSONObject existingShare(String token,String id,boolean folder)throws CloudException{
    for(int page=1;page<=10_000;page++){
      ApiReply reply=request("GET","/proved/record/share/list?offset="+page+"&limit=60",null,token);
      requireSuccess(reply,"读取分享记录失败");
      JSONArray list=reply.root.optJSONArray("list");
      if(list==null)throw new CloudException(ERROR_PROTOCOL,"优享分享记录缺少列表");
      String key=folder?"folderIds":"fileIds";
      for(int index=0;index<list.length();index++){
        JSONObject entry=list.optJSONObject(index);
        if(entry==null)continue;
        for(String value:entry.optString(key,"").split(","))
          if(value.trim().equals(id))return entry;
      }
      int totalPage=reply.root.optInt("totalPage",0);
      if(totalPage<=0||page>=totalPage)return null;
    }
    return null;
  }

  private JSONObject shareBody(CloudFilesClient.CloudItem item,JSONObject previous,String code)
      throws CloudException{
    JSONObject body=new JSONObject();
    try{
      body.put("fileIds",item.folder?"":item.id).put("folderIds",item.folder?item.id:"");
      if(previous!=null)for(String key:new String[]{"fileIds","folderIds"})
        if(previous.has(key))body.put(key,previous.get(key));
      String existingCode=previous==null?"":previous.optString("code","");
      String use=code==null||code.isEmpty()?existingCode:code;
      if(!use.isEmpty()&&(use.length()!=4||!use.matches("[A-Za-z0-9]{4}")))
        throw new CloudException(ERROR_PROTOCOL,"优享分享提取码必须为4位字母或数字");
      body.put("shareId",previous==null?"":previous.optString("shareId",""));
      body.put("code",use);
      body.put("amt",previous==null?"":previous.optString("amt",""));
      body.put("term",previous==null?"":previous.optString("term",""));
      for(String key:new String[]{"showRecommend","showUpTime","showDownloads","showComments",
          "showStars","showLikes"})
        body.put(key,previous==null?0:previous.optInt(key,0));
      return body;
    }catch(CloudException error){throw error;
    }catch(Exception impossible){throw new CloudException(ERROR_PROTOCOL,"分享参数无效");}
  }

  void cloudSetPassword(CloudFilesClient.CloudItem item,String password)throws CloudException{
    if(item==null)throw new CloudException(ERROR_PROTOCOL,"请选择文件或文件夹");
    String value=password==null?"":password;
    if(!value.isEmpty()&&(value.length()!=4||!value.matches("[A-Za-z0-9]{4}")))
      throw new CloudException(ERROR_PROTOCOL,"优享分享提取码必须为4位字母或数字");
    String account=requiredCurrentAccount();
    String token=tokenFor(account);
    JSONObject existing=existingShare(token,item.id,item.folder);
    JSONObject body=shareBody(item,existing,value);
    try{body.put("code",value);}catch(Exception impossible){}
    requireSuccess(request("POST","/proved/share/url",body,token),"设置提取码失败");
  }

  void cloudSetDescription(CloudFilesClient.CloudItem item,String description)throws CloudException{
    if(item==null)throw new CloudException(ERROR_PROTOCOL,"请选择文件或文件夹");
    String value=description==null?"":description;
    if(value.length()>4000||value.indexOf('\0')>=0)
      throw new CloudException(ERROR_PROTOCOL,"简介过长或包含非法字符");
    String account=requiredCurrentAccount();
    String token=tokenFor(account);
    if(item.folder){
      try{
        String currentDesc=currentFolderDescription(token,item);
        value=OrdinaryCloudClient.preserveFragmentDescription(currentDesc,value);
      }catch(CloudFilesClient.CloudException error){
        throw new CloudException(ERROR_PROTOCOL,error.getMessage());
      }
      if(value.length()>4000)
        throw new CloudException(ERROR_PROTOCOL,"简介与分片标记合并后过长");
    }
    JSONObject body=new JSONObject();
    try{
      if(item.folder)body.put("folderDesc",value).put("folderId",item.id).put("folderName",item.name);
      else body.put("fileDesc",value).put("fileId",item.id).put("fileName",item.name);
    }catch(Exception impossible){throw new CloudException(ERROR_PROTOCOL,"简介参数无效");}
    requireSuccess(request("POST",item.folder?"/proved/file/folder/edit":"/proved/file/edit",body,token),
        "更新简介失败");
  }

  CloudFilesClient.ManagedDescription cloudDescription(CloudFilesClient.CloudItem item)
      throws CloudException{
    if(item==null)throw new CloudException(ERROR_PROTOCOL,"请选择文件或文件夹");
    String desc=item.description;
    if(item.folder){
      try{
        String token=tokenFor(requiredCurrentAccount());
        desc=currentFolderDescription(token,item);
      }catch(Exception ignored){}
      desc=OrdinaryCloudClient.visibleDescription(desc);
    }
    return new CloudFilesClient.ManagedDescription(item.id,item.folder,item.name,desc);
  }

  CloudFilesClient.UploadResult cloudUpload(String folderId,String path,CloudFilesClient.UploadOptions options)
      throws CloudException{
    if(options!=null&&options.compatibilityWrap)throw new CloudException(ERROR_PROTOCOL,"优享版上传不使用普通版兼容包装");
    String account=requiredCurrentAccount();
    String token=tokenFor(account);
    LoginState state=ensureLoggedIn(account);
    String uploadAccount=state.account.isEmpty()?account:state.account;
    return premiumUpload(token,uploadAccount,premiumFolder(folderId),path);
  }

  CloudFilesClient.UploadResult cloudUploadFragmented(String folderId,String path,
                                                       CloudFilesClient.UploadOptions options)
      throws CloudException{
    if(folderId==null||folderId.trim().isEmpty())
      throw new CloudException(ERROR_PROTOCOL,"folderId 不能为空");
    if(options==null)options=CloudFilesClient.UploadOptions.defaults();
    File source=new File(path);
    if(!source.isFile())
      throw new CloudException(ERROR_PROTOCOL,"分片上传源必须是普通文件");
    if(source.length()==0)
      throw new CloudException(ERROR_PROTOCOL,"空文件不能执行分片上传");
    String account=requiredCurrentAccount();
    String token=tokenFor(account);
    LoginState state=ensureLoggedIn(account);
    String uploadAccount=state.account.isEmpty()?account:state.account;
    String parent=premiumFolder(folderId);
    File tempRoot=new File(context.getCacheDir(),"premium-fragments/"+UUID.randomUUID().toString());
    if(!tempRoot.mkdirs()&&!tempRoot.isDirectory())
      throw new CloudException(ERROR_NETWORK,"无法创建分片临时目录");
    try{
      String mode="archive_volume".equalsIgnoreCase(options.fragmentMode)
          ?"archive_volume":"byte_split";
      boolean archiveInput=false;
      File splitSource=source;
      if("archive_volume".equals(mode)){
        int kind;
        try{
          kind=OrdinaryCloudClient.archiveKind(source);
        }catch(CloudFilesClient.CloudException error){
          throw new CloudException(ERROR_PROTOCOL,error.getMessage());
        }
        archiveInput=kind!=0;
        splitSource=new File(tempRoot,"volume-source.zip");
        try{
          OrdinaryCloudClient.buildVolumeArchive(source,splitSource,archiveInput);
        }catch(CloudFilesClient.CloudException error){
          throw new CloudException(ERROR_PROTOCOL,error.getMessage());
        }
      }
      long splitBytes=splitSource.length();
      if(splitBytes==0)
        throw new CloudException(ERROR_PROTOCOL,"待分片数据不足以生成分片");
      int partCount;
      try{
        partCount=options.plannedFragmentCount(splitBytes,0L);
      }catch(CloudFilesClient.CloudException error){
        throw new CloudException(ERROR_PROTOCOL,error.getMessage());
      }
      OrdinaryCloudClient.FragmentManifest manifest=new OrdinaryCloudClient.FragmentManifest(
          mode,source.getName(),source.length(),partCount,OrdinaryCloudClient.binaryWidth(partCount),archiveInput);
      List<File> pieces;
      try{
        pieces=OrdinaryCloudClient.splitFile(splitSource,tempRoot,manifest);
      }catch(CloudFilesClient.CloudException error){
        throw new CloudException(ERROR_PROTOCOL,error.getMessage());
      }
      String rootId=cloudCreateFolder(parent,manifest.originalName,"");
      List<String> links=new ArrayList<>();
      for(int index=0;index<pieces.size();index++){
        int part=index+1;
        String childId=cloudCreateFolder(rootId,Integer.toString(part),"");
        CloudFilesClient.UploadResult uploaded=premiumUpload(token,uploadAccount,childId,
            pieces.get(index).getAbsolutePath());
        String shareUrl=premiumShareUrl(token,uploaded.id,false);
        try{
          links.add(manifest.partUri(part,shareUrl));
        }catch(CloudFilesClient.CloudException error){
          throw new CloudException(ERROR_PROTOCOL,error.getMessage());
        }
      }
      JSONObject descBody=new JSONObject();
      try{
        descBody.put("folderDesc",manifest.descriptionMarker())
            .put("folderId",rootId)
            .put("folderName",manifest.originalName);
      }catch(Exception impossible){throw new CloudException(ERROR_PROTOCOL,"分片描述更新参数无效");}
      requireSuccess(request("POST","/proved/file/folder/edit",descBody,token),"设置分片标记失败");
      String external=OrdinaryCloudClient.joinLines(links);
      return new CloudFilesClient.UploadResult(rootId,folderId,manifest.originalName,
          manifest.originalName,OrdinaryCloudClient.sumLengths(pieces),false,mode,manifest.partCount,
          manifest.binaryWidth,external);
    }finally{
      OrdinaryCloudClient.deleteTree(tempRoot);
    }
  }

  CloudFilesClient.DirectoryUploadResult cloudUploadDirectory(String folderId,String path,
                                                              CloudFilesClient.UploadOptions options)
      throws CloudException{
    if(options!=null&&options.compatibilityWrap)throw new CloudException(ERROR_PROTOCOL,"优享版上传不使用普通版兼容包装");
    File source=new File(path);
    if(!source.isDirectory())throw new CloudException(ERROR_PROTOCOL,"目录上传源必须是文件夹");
    String account=requiredCurrentAccount();
    String token=tokenFor(account);
    LoginState state=ensureLoggedIn(account);
    String uploadAccount=state.account.isEmpty()?account:state.account;
    String parent=premiumFolder(folderId);
    String root=cloudCreateFolder(parent,source.getName(),"");
    int[] counts=new int[]{0,0};
    long[] bytes=new long[]{0};
    uploadPremiumTree(token,uploadAccount,root,source,0,counts,bytes);
    return new CloudFilesClient.DirectoryUploadResult(root,source.getName(),counts[0],counts[1],bytes[0]);
  }

  private void uploadPremiumTree(String token,String account,String parent,File directory,int depth,
                                 int[] counts,long[] bytes)throws CloudException{
    if(depth>64)throw new CloudException(ERROR_PROTOCOL,"目录层级过深");
    File[] children=directory.listFiles();
    if(children==null)throw new CloudException(ERROR_PROTOCOL,"无法读取本地目录");
    java.util.Arrays.sort(children,java.util.Comparator.comparing(File::getName));
    for(File child:children){
      if(child.isDirectory()){
        try{if(android.system.OsConstants.S_ISLNK(android.system.Os.lstat(child.getAbsolutePath()).st_mode))continue;}catch(android.system.ErrnoException error){throw new CloudException(ERROR_PROTOCOL,"无法读取本地目录",error);}
        String created=cloudCreateFolder(parent,child.getName(),"");
        counts[1]++;
        uploadPremiumTree(token,account,created,child,depth+1,counts,bytes);
      }else if(child.isFile()){
        CloudFilesClient.UploadResult uploaded=premiumUpload(token,account,parent,child.getAbsolutePath());
        counts[0]++;
        bytes[0]+=uploaded.bytesUploaded;
      }
    }
  }

  CloudFilesClient.DownloadResult cloudDownload(CloudFilesClient.CloudItem item,String destination)
      throws CloudException{
    if(item==null||item.folder)throw new CloudException(ERROR_PROTOCOL,"请选择文件下载");
    String account=requiredCurrentAccount();
    LoginState state=ensureLoggedIn(account);
    String token=credentials(account).appToken;
    if(state.userId.isEmpty())throw new CloudException(ERROR_PROTOCOL,"优享账号缺少真实用户标识");
    String direct=downloadRedirect(token,state.userId,item.id);
    File requested=new File(destination);
    boolean directoryTarget=requested.isDirectory()||destination.endsWith("/")||destination.endsWith("\\");
    File target=directoryTarget?new File(requested,safeFileName(item.name)):requested;
    if(directoryTarget&&!requested.isDirectory()&&!requested.mkdirs())
      throw new CloudException(ERROR_NETWORK,"无法创建下载目录");
    File parent=target.getParentFile();
    if(parent!=null&&!parent.isDirectory()&&!parent.mkdirs())
      throw new CloudException(ERROR_NETWORK,"无法创建下载目录");
    if(target.exists())throw new CloudException(ERROR_PROTOCOL,"目标文件已存在");
    File part=new File(target.getAbsolutePath()+".part");
    if(part.exists())part.delete();
    long written=downloadAbsolute(direct,part);
    if(!part.renameTo(target)){
      if(!copyLocal(part,target)){
        part.delete();
        throw new CloudException(ERROR_NETWORK,"无法保存下载文件");
      }
      part.delete();
    }
    return new CloudFilesClient.DownloadResult(item.id,target.getAbsolutePath(),written,false,item.name);
  }

  CloudFilesClient.CopyResult cloudCopy(CloudFilesClient.CloudItem item,String targetId,
                                        String tempDirectory)throws CloudException{
    if(item==null)throw new CloudException(ERROR_PROTOCOL,"请选择复制项目");
    File temp=new File(tempDirectory==null?"":tempDirectory);
    if(tempDirectory==null||tempDirectory.trim().isEmpty()||!temp.isDirectory()&&!temp.mkdirs())
      throw new CloudException(ERROR_PROTOCOL,"无法准备复制临时目录");
    String account=requiredCurrentAccount();
    String token=tokenFor(account);
    LoginState state=ensureLoggedIn(account);
    String uploadAccount=state.account.isEmpty()?account:state.account;
    String target=premiumFolder(targetId);
    int[] counts=new int[]{0,0};
    long[] bytes=new long[]{0};
    String created="";
    if(item.folder){
      ensureNotPremiumDescendant(token,item.id,target);
      created=copyPremiumFolder(token,uploadAccount,state.userId,item,target,temp,0,
          new HashSet<>(),counts,bytes);
    }else{
      created=copyPremiumFile(token,uploadAccount,state.userId,item,target,temp,counts,bytes);
    }
    return new CloudFilesClient.CopyResult(item.id,created,target,counts[0],counts[1],bytes[0]);
  }

  private String copyPremiumFolder(String token,String account,String userId,CloudFilesClient.CloudItem source,
                                   String target,File temp,int depth,Set<String> visited,
                                   int[] counts,long[] bytes)throws CloudException{
    String sourceId=source.id;
    if(depth>64||!visited.add(sourceId))throw new CloudException(ERROR_PROTOCOL,"目录层级过深或存在循环");
    String created=cloudCreateFolder(target,source.name,source.description);
    counts[1]++;
    for(CloudFilesClient.CloudItem child:premiumAll(token,premiumFolder(sourceId))){
      if(child.folder)
        copyPremiumFolder(token,account,userId,child,created,temp,depth+1,visited,counts,bytes);
      else copyPremiumFile(token,account,userId,child,created,temp,counts,bytes);
    }
    visited.remove(sourceId);
    return created;
  }

  private String copyPremiumFile(String token,String account,String userId,CloudFilesClient.CloudItem item,
                               String target,File temp,int[] counts,long[] bytes)throws CloudException{
    File directory=new File(temp,"copy-"+item.id+"-"+UUID.randomUUID().toString());
    if(!directory.mkdirs()&&!directory.isDirectory())
      throw new CloudException(ERROR_NETWORK,"无法创建复制临时目录");
    File file=new File(directory,safeFileName(item.name));
    try{
      String direct=downloadRedirect(token,userId,item.id);
      long written=downloadAbsolute(direct,file);
      CloudFilesClient.UploadResult uploaded=premiumUpload(token,account,target,file.getAbsolutePath());
      JSONObject body=new JSONObject();
      try{body.put("fileDesc",item.description).put("fileId",uploaded.id).put("fileName",item.name);}
      catch(Exception impossible){throw new CloudException(ERROR_PROTOCOL,"复制参数无效");}
      requireSuccess(request("POST","/proved/file/edit",body,token),"复制文件失败");
      counts[0]++;
      bytes[0]+=written;
      return uploaded.id;
    }finally{
      if(file.exists())file.delete();
      if(directory.exists())directory.delete();
    }
  }

  private void ensureNotPremiumDescendant(String token,String sourceId,String targetId)
      throws CloudException{
    if("0".equals(targetId))return;
    Set<String> visited=new HashSet<>();
    java.util.ArrayDeque<String> pending=new java.util.ArrayDeque<>();
    pending.add(sourceId);
    while(!pending.isEmpty()){
      String folder=pending.removeFirst();
      if(!visited.add(folder))continue;
      if(folder.equals(targetId))
        throw new CloudException(ERROR_PROTOCOL,"不能把目录复制到其子目录");
      for(CloudFilesClient.CloudItem item:premiumAll(token,premiumFolder(folder)))
        if(item.folder)pending.addLast(item.id);
    }
  }

  CloudFilesClient.ReplaceResult cloudReplaceFile(CloudFilesClient.CloudItem oldFile,String path,
                                                  CloudFilesClient.UploadOptions options)
      throws CloudException{
    if(oldFile==null||oldFile.folder)throw new CloudException(ERROR_PROTOCOL,"请选择 APK 文件");
    File replacement=new File(path);
    if(!replacement.isFile()||!replacement.getName().toLowerCase(Locale.ROOT).endsWith(".apk"))
      throw new CloudException(ERROR_PROTOCOL,"换包文件必须是本地 APK");
    String account=requiredCurrentAccount();
    String token=tokenFor(account);
    LoginState state=ensureLoggedIn(account);
    String uploadAccount=state.account.isEmpty()?account:state.account;
    String folder=findPremiumFolder(token,oldFile.id);
    CloudFilesClient.UploadResult uploaded=premiumUpload(token,uploadAccount,folder,path);
    JSONObject body=new JSONObject();
    try{body.put("fileDesc",oldFile.description).put("fileId",uploaded.id).put("fileName",oldFile.name);}
    catch(Exception impossible){throw new CloudException(ERROR_PROTOCOL,"换包参数无效");}
    requireSuccess(request("POST","/proved/file/edit",body,token),"更新换包信息失败");
    cloudDelete(oldFile);
    boolean present=false;
    for(CloudFilesClient.CloudItem item:premiumAll(token,folder))
      if(!item.folder&&item.id.equals(oldFile.id)){present=true;break;}
    if(present)throw new CloudException(ERROR_PROTOCOL,"优享未确认旧文件已移入回收站");
    return new CloudFilesClient.ReplaceResult(oldFile.id,uploaded.id,folder,oldFile.name,
        uploaded.uploadedName,false);
  }

  private String findPremiumFolder(String token,String fileId)throws CloudException{
    java.util.ArrayDeque<String> pending=new java.util.ArrayDeque<>();
    Set<String> visited=new HashSet<>();
    pending.add("0");
    while(!pending.isEmpty()){
      String folder=pending.removeFirst();
      if(!visited.add(folder))continue;
      for(CloudFilesClient.CloudItem item:premiumAll(token,folder)){
        if(item.folder)pending.addLast(item.id);
        else if(item.id.equals(fileId))return folder;
      }
    }
    throw new CloudException(ERROR_PROTOCOL,"待换包文件不在云盘目录中");
  }

  private CloudFilesClient.UploadResult premiumUpload(String token,String account,String folder,
                                                      String path)throws CloudException{
    File file=new File(path);
    if(!file.isFile()||file.length()==0)
      throw new CloudException(ERROR_PROTOCOL,"待上传文件必须是非空普通文件");
    String name=safeFileName(file.getName());
    JSONObject requestBody=new JSONObject();
    try{
      requestBody.put("fileName",name).put("type",1)
          .put("fileSize",Long.toString(Math.max(1L,Math.round(file.length()/1024.0))))
          .put("folderId",folder).put("fileId","");
    }catch(Exception impossible){throw new CloudException(ERROR_PROTOCOL,"上传参数无效");}
    ApiReply granted=request("POST","/proved/7n/getUpToken",requestBody,token);
    requireSuccess(granted,"获取上传令牌失败");
    JSONObject map=granted.root.optJSONObject("map");
    String upToken=granted.root.optString("upToken","");
    if(upToken.isEmpty()&&map!=null)upToken=map.optString("upToken","");
    if("-1".equals(upToken)){
      String id=map==null?"":scalar(map.opt("fileId"));
      if(id.isEmpty())id=scalar(granted.root.opt("fileId"));
      if(id.isEmpty())throw new CloudException(ERROR_PROTOCOL,"秒传响应缺少文件 ID");
      return new CloudFilesClient.UploadResult(id,folder,file.getName(),name,file.length(),false);
    }
    if(upToken.isEmpty())throw new CloudException(ERROR_PROTOCOL,"官方未授予普通文件上传令牌");
    String[] tokenParts=upToken.split(":");
    if(tokenParts.length!=3)throw new CloudException(ERROR_PROTOCOL,"官方上传令牌格式无效");
    String policyJson=decodeUrlBase64(tokenParts[2]);
    String bucket="";
    try{
      JSONObject policy=new JSONObject(policyJson);
      String scope=policy.optString("scope","");
      bucket=scope.split(":",2)[0];
    }catch(Exception error){throw new CloudException(ERROR_PROTOCOL,"官方上传策略无效",error);}
    if(bucket.isEmpty()||!bucket.matches("[A-Za-z0-9-]+"))
      throw new CloudException(ERROR_PROTOCOL,"官方上传策略缺少存储空间");
    HttpReply query=rawHttp("GET","https://api.qiniu.com/v2/query?ak="+encodeQuery(tokenParts[0])
        +"&bucket="+encodeQuery(bucket),null,null,15_000);
    if(query.status<200||query.status>=300)throw new CloudException(ERROR_NETWORK,"七牛区域查询失败");
    String host;
    try{
      JSONObject root=new JSONObject(query.text);
      JSONArray hosts=root.optJSONObject("up").optJSONObject("acc").optJSONArray("main");
      host=hosts==null||hosts.length()==0?"":hosts.optString(0,"");
    }catch(Exception error){throw new CloudException(ERROR_PROTOCOL,"七牛区域响应无效",error);}
    if(host.isEmpty()||!(host.endsWith(".qiniup.com")||host.endsWith(".qiniu.com")))
      throw new CloudException(ERROR_PROTOCOL,"上传区域不是七牛官方服务");
    java.util.Calendar now=java.util.Calendar.getInstance();
    String key=String.format(Locale.ROOT,"disk/%04d/%02d/%02d/%s/%d.rar",
        now.get(java.util.Calendar.YEAR),now.get(java.util.Calendar.MONTH)+1,
        now.get(java.util.Calendar.DAY_OF_MONTH),sanitizeAccount(account),System.currentTimeMillis());
    String object=encodeUrlBase64(key.getBytes(StandardCharsets.UTF_8));
    String base="https://"+host+"/buckets/"+bucket+"/objects/"+object+"/uploads";
    Map<String,String> headers=new LinkedHashMap<>();
    headers.put("Authorization","UpToken "+upToken);
    HttpReply started=rawHttp("POST",base,null,headers,30_000);
    if(started.status<200||started.status>=300)throw new CloudException(ERROR_NETWORK,"七牛上传任务创建失败");
    String uploadId;
    try{uploadId=new JSONObject(started.text).optString("uploadId","");}
    catch(Exception error){throw new CloudException(ERROR_PROTOCOL,"七牛上传任务响应无效",error);}
    if(uploadId.isEmpty())throw new CloudException(ERROR_PROTOCOL,"七牛未返回上传任务标识");
    String uploadUrl=base+"/"+uploadId;
    JSONArray parts=new JSONArray();
    try(FileInputStream input=new FileInputStream(file)){
      byte[] buffer=new byte[8*1024*1024];
      int partNumber=1,read;
      while((read=input.read(buffer))!=-1){
        byte[] chunk=new byte[read];
        System.arraycopy(buffer,0,chunk,0,read);
        Map<String,String> partHeaders=new LinkedHashMap<>(headers);
        partHeaders.put("Content-Type","application/octet-stream");
        HttpReply uploaded=rawHttp("PUT",uploadUrl+"/"+partNumber,chunk,partHeaders,900_000);
        if(uploaded.status<200||uploaded.status>=300)throw new CloudException(ERROR_NETWORK,"七牛分片上传失败");
        String etag;
        try{etag=new JSONObject(uploaded.text).optString("etag","");}
        catch(Exception error){throw new CloudException(ERROR_PROTOCOL,"七牛分片响应无效",error);}
        if(etag.isEmpty())throw new CloudException(ERROR_PROTOCOL,"七牛未确认上传分片");
        parts.put(new JSONObject().put("etag",etag).put("partNumber",partNumber));
        partNumber++;
      }
    }catch(CloudException error){throw error;
    }catch(Exception error){throw new CloudException(ERROR_NETWORK,"读取上传文件失败",error);}
    JSONObject completeBody=new JSONObject();
    try{completeBody.put("parts",parts).put("fname",name);}
    catch(Exception impossible){throw new CloudException(ERROR_PROTOCOL,"上传完成参数无效");}
    HttpReply completed=rawHttp("POST",uploadUrl,completeBody.toString().getBytes(StandardCharsets.UTF_8),
        headers,30_000);
    if(completed.status<200||completed.status>=300)throw new CloudException(ERROR_NETWORK,"七牛合并分片失败");
    String callback;
    try{callback=new JSONObject(completed.text).optString("token","");}
    catch(Exception error){throw new CloudException(ERROR_PROTOCOL,"七牛完成响应无效",error);}
    if(callback.isEmpty())throw new CloudException(ERROR_PROTOCOL,"七牛未返回官方入库确认令牌");
    String tokenTime=rfc3339();
    String resultPath="/unproved/7n/results?tokenList="+encodeQuery(callback)+"&tokenTime="+encodeQuery(tokenTime);
    long deadline=System.currentTimeMillis()+120_000L;
    while(true){
      ApiReply result=request("GET",resultPath,null,token);
      requireSuccess(result,"官方入库确认失败");
      JSONArray list=result.root.optJSONArray("list");
      if(list!=null)for(int index=0;index<list.length();index++){
        JSONObject entry=list.optJSONObject(index);
        if(entry==null||!callback.equals(entry.optString("token","")))continue;
        int status=entry.optInt("status",0);
        if(status==-1)throw new CloudException(ERROR_PROTOCOL,"文件传输完成，但官方入库失败");
        if(status==1){
          String id=scalar(entry.opt("fileId"));
          if(id.isEmpty())throw new CloudException(ERROR_PROTOCOL,"官方上传确认缺少文件ID");
          return new CloudFilesClient.UploadResult(id,folder,file.getName(),name,file.length(),false);
        }
      }
      if(System.currentTimeMillis()>=deadline)
        throw new CloudException(ERROR_NETWORK,"等待官方确认上传结果超时");
      try{Thread.sleep(1300L);}catch(InterruptedException error){
        Thread.currentThread().interrupt();
        throw new CloudException(ERROR_NETWORK,"操作已中断",error);
      }
    }
  }

  private String downloadRedirect(String token,String userId,String fileId)throws CloudException{
    if(userId==null||userId.isEmpty())throw new CloudException(ERROR_PROTOCOL,"优享账号缺少真实用户标识");
    String uuid=ensureUuid();
    long timestamp=System.currentTimeMillis();
    String encodedTimestamp=encryptDownloadPayload(Long.toString(timestamp));
    String downloadId=encryptDownloadPayload(fileId+"|"+userId);
    String auth=encryptDownloadPayload(fileId+"|"+timestamp);
    String url=API_ORIGIN+"/unproved/file/redirect?uuid="+encodeQuery(uuid)
        +"&devType=6&devCode="+encodeQuery(uuid)+"&devModel=Windows&devVersion=125"
        +"&appVersion=&timestamp="+encodeQuery(encodedTimestamp)
        +"&appToken="+encodeQuery(token)+"&downloadId="+encodeQuery(downloadId)
        +"&auth="+encodeQuery(auth)+"&enable=1";
    Map<String,String> headers=new LinkedHashMap<>();
    headers.put("Referer",WEB_ORIGIN+"/");
    headers.put("Origin",WEB_ORIGIN);
    HttpReply reply=rawHttp("GET",url,null,headers,15_000);
    if(reply.status>=300&&reply.status<400&&reply.location!=null&&!reply.location.isEmpty())
      return reply.location;
    throw new CloudException(ERROR_PROTOCOL,"下载重定向失败，未返回 Location 头部");
  }

  private long downloadAbsolute(String url,File destination)throws CloudException{
    HttpURLConnection connection=null;
    try{
      connection=(HttpURLConnection)new URL(url).openConnection();
      connection.setInstanceFollowRedirects(true);
      connection.setConnectTimeout(15_000);
      connection.setReadTimeout(60_000);
      connection.setRequestProperty("User-Agent",desktopUserAgent());
      connection.setRequestProperty("Referer",WEB_ORIGIN+"/");
      int status=connection.getResponseCode();
      if(status<200||status>=300)throw new CloudException(ERROR_NETWORK,"下载服务返回 HTTP "+status);
      long total=0;
      try(InputStream input=connection.getInputStream();
          OutputStream output=new FileOutputStream(destination)){
        byte[] buffer=new byte[64*1024];
        int read;
        while((read=input.read(buffer))!=-1){output.write(buffer,0,read);total+=read;}
      }
      return total;
    }catch(CloudException error){throw error;
    }catch(Exception error){throw new CloudException(ERROR_NETWORK,"下载连接失败",error);
    }finally{if(connection!=null)connection.disconnect();}
  }

  private HttpReply rawHttp(String method,String url,byte[] body,Map<String,String> headers,int readTimeout)
      throws CloudException{
    HttpURLConnection connection=null;
    try{
      connection=(HttpURLConnection)new URL(url).openConnection();
      connection.setInstanceFollowRedirects(false);
      connection.setConnectTimeout(15_000);
      connection.setReadTimeout(readTimeout);
      connection.setRequestMethod(method);
      connection.setRequestProperty("User-Agent",desktopUserAgent());
      connection.setRequestProperty("Accept","application/json");
      connection.setRequestProperty("Origin",WEB_ORIGIN);
      connection.setRequestProperty("Referer",WEB_ORIGIN+"/");
      if(headers!=null)for(Map.Entry<String,String> entry:headers.entrySet())
        connection.setRequestProperty(entry.getKey(),entry.getValue());
      if(body!=null){
        connection.setDoOutput(true);
        connection.setRequestProperty("Content-Type","application/json;charset=UTF-8");
        connection.setFixedLengthStreamingMode(body.length);
        try(OutputStream output=connection.getOutputStream()){output.write(body);}
      }
      int status=connection.getResponseCode();
      InputStream input=status>=400?connection.getErrorStream():connection.getInputStream();
      String text=input==null?"":read(input,MAX_JSON_BYTES);
      return new HttpReply(status,text,connection.getHeaderField("Location"),url);
    }catch(CloudException error){throw error;
    }catch(Exception error){throw new CloudException(ERROR_NETWORK,"网络请求失败，请稍后重试",error);
    }finally{if(connection!=null)connection.disconnect();}
  }

  private static final class HttpReply {
    final int status;
    final String text;
    final String location;
    final String url;
    HttpReply(int status,String text,String location,String url){
      this.status=status;this.text=text==null?"":text;this.location=location;this.url=url;
    }
  }

  private static boolean trustedPremiumShare(String value){
    try{
      URI uri=new URI(value);
      String host=uri.getHost();
      return "https".equalsIgnoreCase(uri.getScheme())
          &&("www.ilanzou.com".equalsIgnoreCase(host)||"ilanzou.com".equalsIgnoreCase(host))
          &&uri.getPath()!=null&&uri.getPath().startsWith("/s/");
    }catch(Exception ignored){return false;}
  }

  private static String premiumResource(String shareUrl,String password){
    if(password==null||password.isEmpty())return shareUrl;
    int hash=shareUrl.indexOf('#');
    String fragment=hash>=0?shareUrl.substring(hash):"";
    String base=hash>=0?shareUrl.substring(0,hash):shareUrl;
    int question=base.indexOf('?');
    if(question>=0){
      StringBuilder kept=new StringBuilder();
      for(String pair:base.substring(question+1).split("&")){
        if(pair.isEmpty())continue;
        String key=pair.split("=",2)[0];
        if("pwd".equals(key)||"password".equals(key)||"code".equals(key))continue;
        if(kept.length()>0)kept.append('&');
        kept.append(pair);
      }
      if(kept.length()>0)kept.append('&');
      kept.append("pwd=").append(encodeQuery(password));
      return base.substring(0,question+1)+kept+fragment;
    }
    return base+"?pwd="+encodeQuery(password)+fragment;
  }

  private static Set<String> matchingFolders(List<CloudFilesClient.CloudItem> items,String name){
    Set<String> ids=new HashSet<>();
    for(CloudFilesClient.CloudItem item:items)if(item.folder&&name.equals(item.name))ids.add(item.id);
    return ids;
  }

  private static String firstScalar(JSONObject object,String... keys){
    if(object==null)return "";
    for(String key:keys){
      String value=scalar(object.opt(key));
      if(!value.isEmpty())return value;
    }
    return "";
  }

  private static String scalar(Object value){
    if(value==null||value==JSONObject.NULL)return "";
    if(value instanceof String)return ((String)value).trim();
    if(value instanceof Number)return Long.toString(((Number)value).longValue());
    return value.toString().trim();
  }

  private static long number(Object value){
    if(value==null||value==JSONObject.NULL)return 0;
    if(value instanceof Number)return Math.max(0,((Number)value).longValue());
    try{return Math.max(0,Long.parseLong(value.toString().trim()));}catch(Exception ignored){return 0;}
  }

  private static String firstNonEmpty(String first,String second){
    return first!=null&&!first.isEmpty()?first:second==null?"":second;
  }

  private static boolean isNumeric(String value){
    if(value==null||value.isEmpty())return false;
    for(int index=0;index<value.length();index++){
      char ch=value.charAt(index);
      if(ch<'0'||ch>'9')return false;
    }
    return true;
  }

  private static boolean containsControl(String value){
    if(value==null)return false;
    for(int index=0;index<value.length();index++){
      char ch=value.charAt(index);
      if(ch=='\r'||ch=='\n'||ch=='\0'||Character.isISOControl(ch))return true;
    }
    return false;
  }

  private static String safeFileName(String value)throws CloudException{
    String name=value==null?"":value.trim();
    if(name.isEmpty()||name.length()>255||name.contains("/")||name.contains("\\")
        ||name.contains(":")||name.contains("*")||name.contains("?")||name.contains("\"")
        ||name.contains("<")||name.contains(">")||name.contains("|")||containsControl(name))
      throw new CloudException(ERROR_PROTOCOL,"文件名无效");
    return name;
  }

  private static boolean copyLocal(File source,File target){
    try(InputStream input=new FileInputStream(source);OutputStream output=new FileOutputStream(target)){
      byte[] buffer=new byte[64*1024];
      int read;
      while((read=input.read(buffer))!=-1)output.write(buffer,0,read);
      return true;
    }catch(Exception ignored){return false;}
  }

  private static String sanitizeAccount(String account){
    StringBuilder out=new StringBuilder();
    if(account!=null)for(int index=0;index<account.length()&&out.length()<80;index++){
      char ch=account.charAt(index);
      if(Character.isLetterOrDigit(ch)||ch=='_'||ch=='-'||ch=='.')out.append(ch);
    }
    return out.length()==0?"account":out.toString();
  }

  private static String encodeQuery(String value){
    try{return URLEncoder.encode(value==null?"":value,"UTF-8");}
    catch(UnsupportedEncodingException impossible){throw new AssertionError(impossible);}
  }

  private static String encodeUrlBase64(byte[] value){
    return Base64.encodeToString(value,Base64.URL_SAFE|Base64.NO_WRAP|Base64.NO_PADDING);
  }

  private static String decodeUrlBase64(String value)throws CloudException{
    try{
      String padded=value.trim();
      while(padded.length()%4!=0)padded+="=";
      return new String(Base64.decode(padded,Base64.URL_SAFE|Base64.NO_WRAP),StandardCharsets.UTF_8);
    }catch(Exception error){throw new CloudException(ERROR_PROTOCOL,"官方上传策略格式无效",error);}
  }

  private static String encryptDownloadPayload(String payload)throws CloudException{
    try{
      Cipher cipher=Cipher.getInstance("AES/ECB/PKCS5Padding");
      cipher.init(Cipher.ENCRYPT_MODE,new SecretKeySpec(WEB_CIPHER_KEY.getBytes(StandardCharsets.UTF_8),"AES"));
      byte[] bytes=cipher.doFinal(payload.getBytes(StandardCharsets.UTF_8));
      String digits="0123456789ABCDEF";
      StringBuilder out=new StringBuilder(bytes.length*2);
      for(byte value:bytes){
        int unsigned=value&255;
        out.append(digits.charAt(unsigned>>>4)).append(digits.charAt(unsigned&15));
      }
      return out.toString();
    }catch(Exception error){throw new CloudException(ERROR_PROTOCOL,"下载授权生成失败",error);}
  }

  private static String rfc3339(){
    SimpleDateFormat format=new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'",Locale.ROOT);
    format.setTimeZone(TimeZone.getTimeZone("UTC"));
    return format.format(new java.util.Date());
  }

  private static String desktopUserAgent(){
    return "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 "
        +"(KHTML, like Gecko) Chrome/125.0.0.0 Safari/537.36";
  }

  private Credentials login(String loginName,String loginPassword)throws CloudException{
    JSONObject body=new JSONObject();
    try{body.put("loginName",loginName).put("loginPwd",loginPassword);}catch(Exception impossible){throw new CloudException(ERROR_PROTOCOL,"登录参数无效");}
    ApiReply reply=request("POST",LOGIN,body,"");
    requireSuccess(reply,"登录失败");
    JSONObject data=reply.root.optJSONObject("data");
    String token=data==null?"":data.optString("appToken","");
    if(token.isEmpty())token=reply.root.optString("appToken","");
    if(token.isEmpty())throw new CloudException(ERROR_PROTOCOL,"登录响应缺少会话信息");
    return new Credentials(loginName,loginPassword,token);
  }

  private LoginState readLoginState(String token)throws CloudException{
    if(token==null||token.isEmpty())throw new CloudException(-2,"登录已过期");
    ApiReply reply=request("GET",USER_INFO,null,token);
    requireSuccess(reply,"登录状态校验失败");
    JSONObject map=reply.root.optJSONObject("map");
    if(map==null){JSONObject data=reply.root.optJSONObject("data");if(data!=null)map=data.optJSONObject("map");}
    if(map==null)map=new JSONObject();
    String userId="";
    String account="";
    ApiReply accountReply=request("GET","/proved/user/account/map",null,token);
    requireSuccess(accountReply,"账号容量校验失败");
    JSONObject accountMap=accountReply.root.optJSONObject("map");
    if(accountMap==null){JSONObject data=accountReply.root.optJSONObject("data");if(data!=null)accountMap=data;}
    if(accountMap!=null){
      Object id=accountMap.opt("userId");
      if(id!=null&&id!=JSONObject.NULL)userId=id.toString();
      account=accountMap.optString("account","");
    }
    return new LoginState(true,true,truthy(map.opt("isVip")),userId,account);
  }

  private ApiReply transfer(String auth,String token)throws CloudException{
    return transfer(auth,token,null);
  }

  private ApiReply transfer(String auth,String token,CancelToken cancel)throws CloudException{
    JSONObject body=new JSONObject();
    try{body.put("auth",auth);}catch(Exception impossible){throw new CloudException(ERROR_PROTOCOL,"保存参数无效");}
    return request("POST",TRANSFER,body,token,cancel);
  }

  private String resolveTransferAuth(String saveUrl)throws CloudException{
    return resolveTransferAuth(saveUrl,null);
  }

  private String resolveTransferAuth(String saveUrl,CancelToken cancel)throws CloudException{
    check(cancel);
    URI current=parseOfficialUri(saveUrl);
    String direct=extractAuth(current.toString());
    if(!direct.isEmpty())return direct;
    for(int redirect=0;redirect<MAX_REDIRECTS;redirect++){
      HttpURLConnection connection=null;
      try{
        connection=(HttpURLConnection)current.toURL().openConnection();if(cancel!=null)cancel.register(connection);
        connection.setInstanceFollowRedirects(false);connection.setConnectTimeout(15_000);connection.setReadTimeout(15_000);
        connection.setRequestMethod("GET");connection.setRequestProperty("Accept","text/html,application/json;q=0.9,*/*;q=0.8");
        connection.setRequestProperty("User-Agent",userAgent());connection.setRequestProperty("Referer",WEB_ORIGIN+"/");
        int status=connection.getResponseCode();
        if(status>=300&&status<400){
          String location=connection.getHeaderField("Location");
          if(location==null||location.isEmpty())throw new CloudException(ERROR_PROTOCOL,"保存入口重定向无效");
          current=parseOfficialUri(current.resolve(location).toString());
          String auth=extractAuth(current.toString());if(!auth.isEmpty())return auth;
          continue;
        }
        if(status<200||status>=300)throw new CloudException(ERROR_PROTOCOL,"保存入口暂不可用 (HTTP "+status+")");
        String body=read(connection.getInputStream(),MAX_REDIRECT_BODY_BYTES);
        String auth=extractAuth(body);if(!auth.isEmpty())return auth;
        String next=findOfficialUrl(body);
        if(next.isEmpty())break;
        current=parseOfficialUri(current.resolve(next).toString());
        auth=extractAuth(current.toString());if(!auth.isEmpty())return auth;
      }catch(CloudException error){throw error;
      }catch(Exception error){throw new CloudException(ERROR_NETWORK,"保存入口连接失败",error);
      }finally{if(cancel!=null)cancel.unregister(connection);if(connection!=null)connection.disconnect();}
    }
    throw new CloudException(ERROR_PROTOCOL,"未能从保存入口取得授权信息，请刷新源后重试");
  }

  private ApiReply request(String method,String path,JSONObject body,String token)throws CloudException{
    return request(method,path,body,token,null);
  }

  private ApiReply request(String method,String path,JSONObject body,String token,CancelToken cancel)throws CloudException{
    check(cancel);return requestWithUuid(method,path,body,token,ensureUuid(),cancel);
  }

  private ApiReply requestWithUuid(String method,String path,JSONObject body,String token,String uuid)throws CloudException{
    return requestWithUuid(method,path,body,token,uuid,null);
  }

  private ApiReply requestWithUuid(String method,String path,JSONObject body,String token,String uuid,CancelToken cancel)throws CloudException{
    HttpURLConnection connection=null;
    try{
      connection=(HttpURLConnection)new URL(apiRequestUrl(path,uuid,token,System.currentTimeMillis())).openConnection();if(cancel!=null)cancel.register(connection);
      connection.setConnectTimeout(15_000);connection.setReadTimeout(30_000);connection.setRequestMethod(method);
      connection.setRequestProperty("Accept","application/json");connection.setRequestProperty("User-Agent",userAgent());
      connection.setRequestProperty("Origin",WEB_ORIGIN);connection.setRequestProperty("Referer",WEB_ORIGIN+"/");
      if(body!=null){
        byte[] bytes=body.toString().getBytes(StandardCharsets.UTF_8);
        connection.setDoOutput(true);connection.setRequestProperty("Content-Type","application/json;charset=UTF-8");
        connection.setFixedLengthStreamingMode(bytes.length);
        try(OutputStream output=connection.getOutputStream()){output.write(bytes);}
      }
      int status=connection.getResponseCode();
      InputStream stream=status>=400?connection.getErrorStream():connection.getInputStream();
      String text=stream==null?"{}":read(stream,MAX_JSON_BYTES);
      JSONObject root=text.trim().isEmpty()?new JSONObject():new JSONObject(text);
      int code=root.has("code")?root.optInt("code",status>=200&&status<300?200:status):(status>=200&&status<300?200:status);
      String message=root.optString("msg",root.optString("message",""));
      return new ApiReply(status,code,message,root);
    }catch(CloudException error){throw error;
    }catch(Exception error){throw new CloudException(ERROR_NETWORK,"网络请求失败，请稍后重试",error);
    }finally{if(cancel!=null)cancel.unregister(connection);if(connection!=null)connection.disconnect();}
  }

  private static void check(CancelToken cancel)throws CloudException{if(cancel!=null)cancel.check();}

  private String ensureUuid()throws CloudException{
    String uuid=preferences.getString(PREF_UUID,"");if(validUuid(uuid))return uuid;
    synchronized(sessionLock){
      uuid=preferences.getString(PREF_UUID,"");if(validUuid(uuid))return uuid;
      ApiReply reply=requestWithUuid("GET",GET_UUID,null,"","");
      requireSuccess(reply,"设备标识初始化失败");
      uuid=reply.root.optString("uuid","");
      if(uuid.isEmpty()){JSONObject data=reply.root.optJSONObject("data");if(data!=null)uuid=data.optString("uuid","");}
      if(uuid.isEmpty()){JSONObject map=reply.root.optJSONObject("map");if(map!=null)uuid=map.optString("uuid","");}
      if(!validUuid(uuid))throw new CloudException(ERROR_PROTOCOL,"设备标识响应无效");
      preferences.edit().putString(PREF_UUID,uuid).apply();return uuid;
    }
  }

  private void persist(Vault vault)throws CloudException{
    try{
      JSONArray accounts=new JSONArray();
      for(Credentials credentials:vault.accounts.values())accounts.put(new JSONObject()
          .put("loginName",credentials.loginName).put("loginPwd",credentials.loginPassword).put("appToken",credentials.appToken));
      JSONObject object=new JSONObject().put("version",2).put("current",vault.current).put("accounts",accounts);
      Cipher cipher=Cipher.getInstance("AES/GCM/NoPadding");cipher.init(Cipher.ENCRYPT_MODE,key());
      byte[] encrypted=cipher.doFinal(object.toString().getBytes(StandardCharsets.UTF_8));
      boolean committed=preferences.edit()
          .putString(PREF_IV,Base64.encodeToString(cipher.getIV(),Base64.NO_WRAP))
          .putString(PREF_SECRET,Base64.encodeToString(encrypted,Base64.NO_WRAP)).commit();
      if(!committed)throw new CloudException(ERROR_CREDENTIALS_UNREADABLE,"无法安全保存登录信息");
    }catch(CloudException error){throw error;
    }catch(Exception error){throw new CloudException(ERROR_CREDENTIALS_UNREADABLE,"无法安全保存登录信息",error);}
  }

  /** Decrypt v2 storage, or migrate the former single-account object using the same key and preferences. */
  private Vault loadVault()throws CloudException{
    String iv=preferences.getString(PREF_IV,"");String secret=preferences.getString(PREF_SECRET,"");
    if(iv.isEmpty()||secret.isEmpty())return new Vault();
    try{
      Cipher cipher=Cipher.getInstance("AES/GCM/NoPadding");
      cipher.init(Cipher.DECRYPT_MODE,key(),new GCMParameterSpec(128,Base64.decode(iv,Base64.NO_WRAP)));
      String json=new String(cipher.doFinal(Base64.decode(secret,Base64.NO_WRAP)),StandardCharsets.UTF_8);
      JSONObject object=new JSONObject(json);Vault vault=new Vault();JSONArray array=object.optJSONArray("accounts");
      if(array==null){
        Credentials legacy=credentialsFrom(object);
        vault.accounts.put(legacy.loginName,legacy);vault.current=legacy.loginName;
        persist(vault);return vault;
      }
      for(int index=0;index<array.length();index++){
        JSONObject item=array.optJSONObject(index);if(item==null)continue;
        Credentials credentials=credentialsFrom(item);vault.accounts.put(credentials.loginName,credentials);
      }
      if(vault.accounts.isEmpty())throw new IllegalStateException("empty credentials");
      vault.current=object.optString("current","");
      if(!vault.accounts.containsKey(vault.current))vault.current=vault.accounts.keySet().iterator().next();
      return vault;
    }catch(Exception error){
      if(error instanceof CloudException)throw (CloudException)error;
      clearStoredLogin();
      throw new CloudException(ERROR_CREDENTIALS_UNREADABLE,"本机登录凭据已失效，请重新输入",error);
    }
  }

  private static Credentials credentialsFrom(JSONObject object){
    String name=object.optString("loginName","").trim(),password=object.optString("loginPwd",""),token=object.optString("appToken","");
    if(name.isEmpty()||password.isEmpty())throw new IllegalStateException("empty credentials");
    return new Credentials(name,password,token);
  }

  private Credentials credentials(String loginName)throws CloudException{
    synchronized(sessionLock){
      Credentials credentials=loadVault().accounts.get(loginName);
      if(credentials==null)throw new CloudException(ERROR_CREDENTIALS_REQUIRED,"未找到所选蓝奏云优享账号");
      return credentials;
    }
  }

  private String requiredCurrentAccount()throws CloudException{
    synchronized(sessionLock){
      String current=loadVault().current;
      if(current.isEmpty())throw new CloudException(ERROR_CREDENTIALS_REQUIRED,"请先登录蓝奏云优享版");
      return current;
    }
  }

  private Object accountLock(String loginName){
    synchronized(sessionLock){
      Object lock=accountLocks.get(loginName);
      if(lock==null){lock=new Object();accountLocks.put(loginName,lock);}
      return lock;
    }
  }

  private void clearStoredLogin(){
    preferences.edit().remove(PREF_IV).remove(PREF_SECRET).apply();sessions.clear();accountLocks.clear();
        try{KeyStore store=KeyStore.getInstance("AndroidKeyStore");store.load(null);if(store.containsAlias(KEY_ALIAS))store.deleteEntry(KEY_ALIAS);}catch(Exception error){throw new IllegalStateException("无法清理蓝奏云优享登录密钥",error);}

  }

  private static String normalizedName(String loginName)throws CloudException{
    String name=loginName==null?"":loginName.trim();
    if(name.isEmpty())throw new CloudException(ERROR_CREDENTIALS_REQUIRED,"请选择蓝奏云优享账号");
    return name;
  }

  private <T> List<T> runAccounts(List<String> names,boolean concurrent,AccountWork<T> work)throws CloudException{
    List<T> results=new ArrayList<>(names.size());
    if(!concurrent||names.size()<2){for(String name:names)results.add(work.run(name));return results;}
        int workers=Math.max(1,LanzouCore.adaptiveNetworkWorkers(names.size()));ExecutorService executor=Executors.newFixedThreadPool(workers);List<Future<T>> futures=new ArrayList<>(names.size());
    try{
      for(String name:names)futures.add(executor.submit(()->work.run(name)));
      for(Future<T> future:futures)results.add(future.get());
      return results;
    }catch(InterruptedException error){
      Thread.currentThread().interrupt();throw new CloudException(ERROR_NETWORK,"操作已中断",error);
    }catch(ExecutionException error){throw new CloudException(ERROR_PROTOCOL,"批量操作失败",error);
    }finally{executor.shutdownNow();}
  }

  private SecretKey key()throws Exception{
    KeyStore store=KeyStore.getInstance("AndroidKeyStore");store.load(null);
    KeyStore.Entry entry=store.getEntry(KEY_ALIAS,null);
    if(entry instanceof KeyStore.SecretKeyEntry)return ((KeyStore.SecretKeyEntry)entry).getSecretKey();
    KeyGenerator generator=KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES,"AndroidKeyStore");
    generator.init(new KeyGenParameterSpec.Builder(KEY_ALIAS,KeyProperties.PURPOSE_ENCRYPT|KeyProperties.PURPOSE_DECRYPT)
        .setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
        .setRandomizedEncryptionRequired(true).build());
    return generator.generateKey();
  }

  @SuppressLint("GetInstance") // Required only for the official web timestamp contract; credentials use AES-GCM.
  private static String encryptWebTimestamp(long timestamp)throws CloudException{
    try{
      Cipher cipher=Cipher.getInstance("AES/ECB/PKCS5Padding");
      cipher.init(Cipher.ENCRYPT_MODE,new SecretKeySpec(WEB_CIPHER_KEY.getBytes(StandardCharsets.UTF_8),"AES"));
      byte[] bytes=cipher.doFinal(Long.toString(timestamp).getBytes(StandardCharsets.UTF_8));
      String digits="0123456789ABCDEF";StringBuilder hex=new StringBuilder(bytes.length*2);for(byte value:bytes){int unsigned=value&255;hex.append(digits.charAt(unsigned>>>4)).append(digits.charAt(unsigned&15));}
      return hex.toString();
    }catch(Exception error){throw new CloudException(ERROR_PROTOCOL,"请求签名初始化失败",error);}
  }

  static String apiRequestUrl(String path,String uuid,String token,long timestamp)throws Exception{
    StringBuilder url=new StringBuilder(API_ORIGIN).append(path);
    query(url,"uuid",uuid);query(url,"devType","6");query(url,"devCode",uuid);
    query(url,"devModel","Android");query(url,"devVersion",Build.VERSION.RELEASE);
    query(url,"appVersion","");query(url,"timestamp",encryptWebTimestamp(timestamp));
    query(url,"appToken",token==null?"":token);query(url,"extra","2");return url.toString();
  }
  private static void query(StringBuilder out,String key,String value)throws Exception{
    if(out.indexOf("?")<0)out.append('?');
    else if(out.charAt(out.length()-1)!='?'&&out.charAt(out.length()-1)!='&')out.append('&');
    out.append(URLEncoder.encode(key,"UTF-8")).append('=').append(URLEncoder.encode(value==null?"":value,"UTF-8"));
  }

  private static void requireSuccess(ApiReply reply,String fallback)throws CloudException{
    if(reply.httpStatus==401||reply.httpStatus==403||reply.expired())throw new CloudException(-2,"登录已过期");
    if(reply.httpStatus<200||reply.httpStatus>=300)throw new CloudException(reply.httpStatus,fallback+" (HTTP "+reply.httpStatus+")");
    if(reply.code!=200)throw new CloudException(reply.code,reply.message.isEmpty()?fallback:reply.message);
  }

  private static URI parseOfficialUri(String raw)throws CloudException{
    try{
      URI uri=new URI(raw);String host=uri.getHost();
      if(!"https".equalsIgnoreCase(uri.getScheme())||host==null||!("ilanzou.com".equalsIgnoreCase(host)||host.toLowerCase(Locale.ROOT).endsWith(".ilanzou.com")))
        throw new CloudException(ERROR_PROTOCOL,"保存入口不是蓝奏云优享版官方地址");
      return uri;
    }catch(CloudException error){throw error;
    }catch(Exception error){throw new CloudException(ERROR_PROTOCOL,"保存入口格式无效",error);}
  }

  private static String extractAuth(String text){
    if(text==null||text.isEmpty())return "";
    String normalized=text.replace("\\u0026","&");Matcher match=AUTH_QUERY.matcher(normalized);
    if(!match.find()){match=AUTH_JSON.matcher(normalized);if(!match.find())return "";}
    try{
      String auth=URLDecoder.decode(match.group(1),"UTF-8");
      if(auth.startsWith("1-"))auth=auth.substring(2);
      if(auth.length()<4||auth.length()>2048||auth.indexOf('\n')>=0||auth.indexOf('\r')>=0)return "";
      return auth;
        }catch(IllegalArgumentException invalid){return "";}
    catch(UnsupportedEncodingException impossible){throw new AssertionError(impossible);}

  }

  private static String findOfficialUrl(String body){
    if(body==null||body.isEmpty())return "";
    try{
      JSONObject object=new JSONObject(body);String found=findUrl(object,0);if(!found.isEmpty())return found;
        }catch(org.json.JSONException invalidJson){}
    Matcher matcher=Pattern.compile("https://(?:[A-Za-z0-9-]+\\.)*ilanzou\\.com/[^\\\"'\\s<]+",Pattern.CASE_INSENSITIVE).matcher(body);
    return matcher.find()?matcher.group():"";
  }

  private static String findUrl(JSONObject object,int depth){
    if(depth>3)return "";
    Iterator<String> keys=object.keys();
    while(keys.hasNext()){
      String key=keys.next();Object value=object.opt(key);
      if(value instanceof JSONObject){String nested=findUrl((JSONObject)value,depth+1);if(!nested.isEmpty())return nested;}
      else if(value instanceof String){String text=(String)value;if((key.toLowerCase(Locale.ROOT).contains("url")||text.contains("auth="))&&text.startsWith("https://"))return text;}
    }
    return "";
  }

  private static String read(InputStream input,int maxBytes)throws Exception{
    try(InputStream stream=input;ByteArrayOutputStream output=new ByteArrayOutputStream(Math.min(maxBytes,8192))){
      byte[] buffer=new byte[4096];int total=0,read;
      while((read=stream.read(buffer))!=-1){total+=read;if(total>maxBytes)throw new CloudException(ERROR_PROTOCOL,"服务器响应过大");output.write(buffer,0,read);}
      return output.toString("UTF-8");
    }
  }

  private static String userAgent(){return "Mozilla/5.0 (Linux; Android "+Build.VERSION.RELEASE+"; "+Build.MODEL+") AppleWebKit/537.36 Chrome/138 Mobile Safari/537.36";}
  private static boolean validUuid(String value){return value!=null&&value.length()>=8&&value.length()<=128&&value.indexOf('\n')<0&&value.indexOf('\r')<0;}
  private static boolean truthy(Object value){return value instanceof Boolean?(Boolean)value:value instanceof Number?((Number)value).intValue()!=0:value!=null&&("1".equals(value.toString())||"true".equalsIgnoreCase(value.toString()));}
  static String maskAccount(String account){
    String value=account==null?"":account.trim();int length=value.length();
    if(length<2)return value;if(length<=4)return value.charAt(0)+"***"+value.charAt(length-1);
    int head=length>=8?3:1,tail=length>=8?4:1;
    return value.substring(0,head)+"****"+value.substring(length-tail);
  }

  String accountFingerprint(String account)throws CloudException{
    String name=normalizedName(account),salt=preferences.getString(PREF_UUID,"");
    try{byte[] digest=MessageDigest.getInstance("SHA-256").digest((salt+'\n'+name).getBytes(StandardCharsets.UTF_8));StringBuilder out=new StringBuilder(digest.length*2);for(byte value:digest)out.append(String.format(Locale.ROOT,"%02x",value&255));return out.toString();}
    catch(Exception error){throw new CloudException(ERROR_PROTOCOL,"账号标识生成失败",error);}
  }
  private static String redact(String message){
    if(message==null||message.isEmpty())return "操作失败";
    String safe=PHONE.matcher(message).replaceAll("1**********");
    safe=SECRET_PARAM.matcher(safe).replaceAll("$1=***");
    return LONG_TOKEN.matcher(safe).replaceAll("***");
  }

  private static final class Credentials {
    final String loginName,loginPassword,appToken;
    Credentials(String loginName,String loginPassword,String appToken){this.loginName=loginName;this.loginPassword=loginPassword;this.appToken=appToken;}
  }

  private static final class Vault {
    final LinkedHashMap<String,Credentials> accounts=new LinkedHashMap<>();
    String current="";
  }

  private static final class SessionEntry {
    final LoginState state;final long verifiedAt;
    SessionEntry(LoginState state,long verifiedAt){this.state=state;this.verifiedAt=verifiedAt;}
  }

  private interface AccountWork<T>{T run(String accountName);}

  private static final class ApiReply {
    final int httpStatus,code;final String message;final JSONObject root;
    ApiReply(int httpStatus,int code,String message,JSONObject root){this.httpStatus=httpStatus;this.code=code;this.message=message==null?"":message;this.root=root;}
    boolean expired(){return code==-2||message.contains("登录过期");}
  }
}
