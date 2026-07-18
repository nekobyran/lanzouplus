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
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URI;
import java.net.URL;
import java.net.URLDecoder;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.security.KeyStore;
import java.security.MessageDigest;
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
    LoginState(boolean configured,boolean loggedIn,boolean premium){
      this.configured=configured;this.loggedIn=loggedIn;this.premium=premium;
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
    void cancel(){List<HttpURLConnection> active;synchronized(lock){if(cancelled)return;cancelled=true;active=new ArrayList<>(connections);connections.clear();}for(HttpURLConnection connection:active)try{connection.disconnect();}catch(RuntimeException ignored){}}
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
    return new LoginState(true,true,truthy(map.opt("isVip")));
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
      StringBuilder url=new StringBuilder(API_ORIGIN).append(path).append('?');
      query(url,"uuid",uuid);query(url,"devType","6");query(url,"devCode",uuid);
      query(url,"devModel","Android");query(url,"devVersion",Build.VERSION.RELEASE);
      query(url,"appVersion","");query(url,"timestamp",encryptWebTimestamp(System.currentTimeMillis()));
      query(url,"appToken",token==null?"":token);query(url,"extra","2");
      connection=(HttpURLConnection)new URL(url.toString()).openConnection();if(cancel!=null)cancel.register(connection);
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
    try{KeyStore store=KeyStore.getInstance("AndroidKeyStore");store.load(null);if(store.containsAlias(KEY_ALIAS))store.deleteEntry(KEY_ALIAS);}catch(Exception ignored){}
  }

  private static String normalizedName(String loginName)throws CloudException{
    String name=loginName==null?"":loginName.trim();
    if(name.isEmpty())throw new CloudException(ERROR_CREDENTIALS_REQUIRED,"请选择蓝奏云优享账号");
    return name;
  }

  private <T> List<T> runAccounts(List<String> names,boolean concurrent,AccountWork<T> work)throws CloudException{
    List<T> results=new ArrayList<>(names.size());
    if(!concurrent||names.size()<2){for(String name:names)results.add(work.run(name));return results;}
    ExecutorService executor=Executors.newFixedThreadPool(names.size());List<Future<T>> futures=new ArrayList<>(names.size());
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
      String digits="0123456789abcdef";StringBuilder hex=new StringBuilder(bytes.length*2);for(byte value:bytes){int unsigned=value&255;hex.append(digits.charAt(unsigned>>>4)).append(digits.charAt(unsigned&15));}
      return hex.toString();
    }catch(Exception error){throw new CloudException(ERROR_PROTOCOL,"请求签名初始化失败",error);}
  }

  private static void query(StringBuilder out,String key,String value)throws Exception{
    if(out.charAt(out.length()-1)!='?')out.append('&');
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
    }catch(Exception ignored){return "";}
  }

  private static String findOfficialUrl(String body){
    if(body==null||body.isEmpty())return "";
    try{
      JSONObject object=new JSONObject(body);String found=findUrl(object,0);if(!found.isEmpty())return found;
    }catch(Exception ignored){}
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
