package cc.nkbr.lanzouplus;

import android.content.Context;
import android.content.SharedPreferences;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.drawable.Drawable;
import android.security.keystore.KeyGenParameterSpec;
import android.security.keystore.KeyProperties;
import android.util.Base64;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.RandomAccessFile;
import org.apache.commons.compress.archivers.sevenz.SevenZArchiveEntry;
import org.apache.commons.compress.archivers.sevenz.SevenZFile;
import org.apache.commons.compress.archivers.sevenz.SevenZOutputFile;
import org.apache.commons.compress.archivers.sevenz.SevenZMethod;
import org.apache.commons.compress.archivers.sevenz.SevenZMethodConfiguration;
import org.tukaani.xz.LZMA2Options;
import java.net.HttpURLConnection;
import java.net.URI;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.security.KeyStore;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import java.util.zip.ZipInputStream;
import java.util.zip.ZipOutputStream;

import javax.crypto.Cipher;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;

/**
 * Synchronous client for the ordinary LanZou account pages.
 *
 * <p>Protocol details follow the LanzouYOU Rust core: login through the
 * accounts hand-off, DoUpload tasks for management, the html5 uploader, and the
 * public share/permission pages for direct links.</p>
 */
final class OrdinaryCloudClient {
  volatile CloudFilesClient.VerificationPrompt verificationPrompt;
  private String lastDownloadKey="";
  private LanzouCore.DirectLink lastDownload;
  private long lastDownloadAt;
  private static final String PC_ORIGIN="https://pc.woozooo.com";
  private static final String UP_ORIGIN="https://up.woozooo.com";
  private static final String ACCOUNTS_ORIGIN="https://accounts.woozooo.com";
  private static final String PREFS="ordinary-session-v1";
  private static final String PREF_IV="iv";
  private static final String PREF_SECRET="secret";
  private static final String PREF_CURRENT="current";
  private static final String KEY_ALIAS="cc.nkbr.lanzouplus.ordinary.v1";
  private static final String DESCRIPTION_PREFIX="lanzouyou-part:v1:";
  private static final String COMPAT_MAGIC="LZYOU001";
  private static final long SESSION_CACHE_MS=60_000L;
  private static final int MAX_JSON_BYTES=1024*1024;
  private static final int MAX_HTML_BYTES=2*1024*1024;
  private static final int MAX_REDIRECTS=8;
  private static final int MAX_DIRECTORY_DEPTH=64;
  private static final Pattern SHOULD_NOT_SURVIVE=Pattern.compile(
      "(?i)(appToken|downloadId|auth|pwd|password|signature|sign|token|websignkey)(?:=|%3D)\\s*[^&\\s,}]+");
  private static final Pattern LONG_SECRET=Pattern.compile("(?<![A-Za-z0-9_-])[A-Za-z0-9_-]{32,}(?![A-Za-z0-9_-])");

  static final class LoginState {
    final boolean configured;
    final boolean loggedIn;
    final String userId;

    LoginState(boolean configured,boolean loggedIn,String userId){
      this.configured=configured;
      this.loggedIn=loggedIn;
      this.userId=userId==null?"":userId;
    }
  }

  private final Context context;
  private final SharedPreferences preferences;
  private final Object vaultLock=new Object();
  private final Object networkLock=new Object();
  private final Map<String,SessionEntry> sessions=new HashMap<>();
  private final Map<String,Object> accountLocks=new HashMap<>();
  private final CookieJar jar=new CookieJar();
  private Session active;

  OrdinaryCloudClient(Context context){
    this.context=context.getApplicationContext();
    preferences=this.context.getSharedPreferences(PREFS,Context.MODE_PRIVATE);
  }

  boolean hasStoredLogin(){return preferences.contains(PREF_IV)&&preferences.contains(PREF_SECRET);}

  List<String> accountNames()throws CloudFilesClient.CloudException{
    synchronized(vaultLock){return Collections.unmodifiableList(new ArrayList<>(loadVault().accounts.keySet()));}
  }

  String currentAccountName()throws CloudFilesClient.CloudException{
    synchronized(vaultLock){return loadVault().current;}
  }

  boolean selectAccount(String account)throws CloudFilesClient.CloudException{
    String name=normalizedName(account);
    synchronized(vaultLock){
      Vault vault=loadVault();
      if(!vault.accounts.containsKey(name))return false;
      if(!name.equals(vault.current)){vault.current=name;persist(vault);}
      active=null;
      jar.clear();
      return true;
    }
  }

  boolean removeAccount(String account)throws CloudFilesClient.CloudException{
    String name=normalizedName(account);
    synchronized(vaultLock){
      Vault vault=loadVault();
      if(vault.accounts.remove(name)==null)return false;
      sessions.remove(name);
      accountLocks.remove(name);
      if(active!=null&&name.equals(active.loginName)){active=null;jar.clear();}
      if(vault.accounts.isEmpty()){clearStoredLogin();return true;}
      if(name.equals(vault.current))vault.current=vault.accounts.keySet().iterator().next();
      persist(vault);
      return true;
    }
  }

  LoginState loginAndRemember(String account,String password)throws CloudFilesClient.CloudException{
    if(account==null||account.trim().isEmpty()||password==null||password.isEmpty())
      throw new CloudFilesClient.CloudException(CloudFilesClient.ERROR_CREDENTIALS_REQUIRED,"请输入账号和密码");
    String name=account.trim();
    synchronized(accountLock(name)){
      Session session=login(name,password);
      synchronized(vaultLock){
        Vault vault=loadVault();
        vault.accounts.put(name,new Credentials(name,password,session.cookie,session.uid,session.vei));
        vault.current=name;
        persist(vault);
        sessions.put(name,new SessionEntry(true,session.uid,System.currentTimeMillis()));
      }
      active=session;
      return new LoginState(true,true,session.uid);
    }
  }

  LoginState queryLoginState()throws CloudFilesClient.CloudException{
    if(!hasStoredLogin())return new LoginState(false,false,"");
    String name;
    synchronized(vaultLock){name=loadVault().current;}
    if(name.isEmpty())return new LoginState(true,false,"");
    return ensureLoggedIn(name);
  }

  /**
   * Verifies one stored account through a throwaway client.
   * The shared instance keeps its active session, cookie jar and current account untouched.
   */
  LoginState verifyAccount(String account)throws CloudFilesClient.CloudException{
    return new OrdinaryCloudClient(context).ensureLoggedIn(account);
  }

  void clearLogin(){
    synchronized(vaultLock){clearStoredLogin();}
  }

  void logout(){
    synchronized(networkLock){
      try{
        if(active!=null&&!active.cookie.isEmpty())
          get(PC_ORIGIN+"/account.php?action=logout",active.cookie,false);
      }catch(Exception ignored){}
      active=null;
      jar.clear();
    }
    clearLogin();
  }

  private LoginState ensureLoggedIn(String account)throws CloudFilesClient.CloudException{
    String name=normalizedName(account);
    synchronized(accountLock(name)){
      Credentials credentials;
      SessionEntry cached;
      synchronized(vaultLock){
        credentials=loadVault().accounts.get(name);
        cached=sessions.get(name);
      }
      if(credentials==null)
        throw new CloudFilesClient.CloudException(CloudFilesClient.ERROR_CREDENTIALS_REQUIRED,"未找到所选蓝奏云账号");
      long now=System.currentTimeMillis();
      if(active!=null&&name.equals(active.loginName)&&cached!=null&&now-cached.verifiedAt<SESSION_CACHE_MS)
        return new LoginState(true,true,active.uid);
      Session candidate=new Session(name,credentials.loginName,credentials.password,credentials.cookie,
          credentials.uid,credentials.vei);
      if(!candidate.cookie.isEmpty()&&!candidate.uid.isEmpty()){
        try{
          jar.reset(candidate.cookie);
          bootstrap(candidate);
          commitSession(candidate);
          return new LoginState(true,true,candidate.uid);
        }catch(CloudFilesClient.CloudException error){
          if(!error.sessionExpired()&&error.code()!=CloudFilesClient.ERROR_CREDENTIALS_REQUIRED)throw error;
        }
      }
      Session renewed=login(credentials.loginName,credentials.password);
      synchronized(vaultLock){
        Vault vault=loadVault();
        if(!vault.accounts.containsKey(name))
          throw new CloudFilesClient.CloudException(CloudFilesClient.ERROR_CREDENTIALS_REQUIRED,"所选账号已删除");
        vault.accounts.put(name,new Credentials(name,credentials.password,renewed.cookie,renewed.uid,renewed.vei));
        persist(vault);
      }
      commitSession(renewed);
      return new LoginState(true,true,renewed.uid);
    }
  }

  private void commitSession(Session session)throws CloudFilesClient.CloudException{
    active=session;
    sessions.put(session.loginName,new SessionEntry(true,session.uid,System.currentTimeMillis()));
    synchronized(vaultLock){
      Vault vault=loadVault();
      Credentials saved=vault.accounts.get(session.loginName);
      if(saved!=null){
        vault.accounts.put(session.loginName,
            new Credentials(session.loginName,saved.password,session.cookie,session.uid,session.vei));
        persist(vault);
      }
    }
  }

  private Session requireSession()throws CloudFilesClient.CloudException{
    if(active!=null&&!active.cookie.isEmpty()&&!active.uid.isEmpty())return active;
    String current;
    synchronized(vaultLock){current=loadVault().current;}
    if(current.isEmpty())
      throw new CloudFilesClient.CloudException(CloudFilesClient.ERROR_CREDENTIALS_REQUIRED,"请先登录蓝奏云账号");
    ensureLoggedIn(current);
    if(active==null)
      throw new CloudFilesClient.CloudException(CloudFilesClient.ERROR_CREDENTIALS_REQUIRED,"请先登录蓝奏云账号");
    return active;
  }

  CloudFilesClient.CloudPage listFolder(String folderId,int page,int pageSize)
      throws CloudFilesClient.CloudException{
    String id=validId(folderId,"folderId");
    if(page<1||page>10_000)
      throw new CloudFilesClient.CloudException(CloudFilesClient.ERROR_INVALID,"页码必须在 1 到 10000 之间");
    if(pageSize<1||pageSize>200)
      throw new CloudFilesClient.CloudException(CloudFilesClient.ERROR_INVALID,"每页数量必须在 1 到 200 之间");
    synchronized(networkLock){
      requireSession();
      List<CloudFilesClient.CloudItem> items=new ArrayList<>();
      if(page==1){
        JSONObject folders=task(map("task","47","folder_id",id));
        items.addAll(parseItems(folders.opt("text"),true));
      }
      JSONObject files=task(map("task","5","folder_id",id,"pg",Integer.toString(page)));
      List<CloudFilesClient.CloudItem> fileItems=parseItems(files.opt("text"),false);
      items.addAll(fileItems);
      long marker=valueAsLong(files.opt("info"),-1);
      boolean hasMore=marker>=0?marker!=0:!fileItems.isEmpty();
      return new CloudFilesClient.CloudPage(items,page,hasMore,items.size());
    }
  }

  private List<CloudFilesClient.CloudItem> listAll(String folderId)throws CloudFilesClient.CloudException{
    List<CloudFilesClient.CloudItem> result=new ArrayList<>();
    for(int page=1;page<=10_000;page++){
      CloudFilesClient.CloudPage batch=listFolder(folderId,page,50);
      result.addAll(batch.items);
      if(!batch.hasMore)break;
      if(page==10_000)
        throw new CloudFilesClient.CloudException(CloudFilesClient.ERROR_PROTOCOL,"目录分页超过上限");
    }
    return result;
  }

  List<CloudFilesClient.CloudItem> moveTargets()throws CloudFilesClient.CloudException{
    synchronized(networkLock){
      requireSession();
      JSONObject value=task(map("task","19","file_id","-1"));
      List<CloudFilesClient.CloudItem> result=new ArrayList<>();
      result.add(new CloudFilesClient.CloudItem("-1","蓝奏云",true,0,"","",false,0,"/",false,""));
      JSONArray info=value.optJSONArray("info");
      if(info!=null)for(int index=0;index<info.length();index++){
        JSONObject entry=info.optJSONObject(index);
        if(entry==null)continue;
        String id=string(entry,"folder_id");
        String name=string(entry,"folder_name");
        if(!id.isEmpty()&&!name.isEmpty())
          result.add(new CloudFilesClient.CloudItem(id,name,true,0,"","",false,0,"/"+name,false,""));
      }
      return result;
    }
  }

  String createFolder(String parentId,String name,String description)throws CloudFilesClient.CloudException{
    String parent=validId(parentId,"parentId");
    required(name,"文件夹名称",255);
    if(description==null)description="";
    if(description.length()>4000||containsControl(description))
      throw new CloudFilesClient.CloudException(CloudFilesClient.ERROR_INVALID,"文件夹简介过长或包含非法字符");
    synchronized(networkLock){
      requireSession();
      Set<String> before=matchingFolderIds(listAll(parent),name);
      JSONObject value=task(map("task","2","parent_id",parent,"folder_name",name,
          "folder_description",description));
      String candidate=scalar(value.opt("text"));
      if(validNumericId(candidate)&&!candidate.equals("-1"))return candidate;
      Set<String> after=matchingFolderIds(listAll(parent),name);
      after.removeAll(before);
      if(after.size()!=1)
        throw new CloudFilesClient.CloudException(CloudFilesClient.ERROR_PROTOCOL,
            after.isEmpty()?"官方已确认创建但未返回新文件夹 ID":"创建后出现多个同名文件夹，无法确定新目录");
      return after.iterator().next();
    }
  }

  void rename(CloudFilesClient.CloudItem item,String name)throws CloudFilesClient.CloudException{
    validId(item.id,"id");
    required(name,"名称",255);
    synchronized(networkLock){
      requireSession();
      if(item.folder){
        ShareRecord record=shareRecord(item.id,true);
        task(map("task","4","folder_id",item.id,"folder_name",name,
            "folder_description",record.description));
      }else{
        String stem=fileStem(name);
        required(stem,"文件名",255);
        task(map("task","46","file_id",item.id,"file_name",stem,"type","2"));
      }
    }
  }

  void move(CloudFilesClient.CloudItem item,String targetId)throws CloudFilesClient.CloudException{
    validId(item.id,"id");
    String target=validId(targetId,"targetId");
    if(item.id.equals(target))
      throw new CloudFilesClient.CloudException(CloudFilesClient.ERROR_INVALID,"不能移动到自身");
    synchronized(networkLock){
      requireSession();
      if(!item.folder){
        moveFile(item.id,target);
        return;
      }
      ensureNotDescendant(item.id,target);
      Set<String> visited=new HashSet<>();
      moveFolderRecursive(item.id,target,0,visited);
    }
  }

  private void moveFolderRecursive(String sourceId,String targetId,int depth,Set<String> visited)
      throws CloudFilesClient.CloudException{
    if(depth>MAX_DIRECTORY_DEPTH||!visited.add(sourceId))
      throw new CloudFilesClient.CloudException(CloudFilesClient.ERROR_INVALID,"目录层级过深或存在循环");
    ShareRecord source=shareRecord(sourceId,true);
    String newId=mkdirId(targetId,source.name,source.description);
    if(!source.password.isEmpty())setPasswordRaw(newId,true,source.password);
    for(CloudFilesClient.CloudItem item:listAll(sourceId)){
      if(item.folder)moveFolderRecursive(item.id,newId,depth+1,visited);
      else moveFile(item.id,newId);
    }
    deleteRaw(sourceId,true);

    visited.remove(sourceId);
  }

  private void ensureNotDescendant(String sourceId,String targetId)throws CloudFilesClient.CloudException{
    if("-1".equals(targetId))return;
    JSONObject value=task(map("task","47","folder_id",targetId));
    JSONArray parents=value.optJSONArray("info");
    if(parents==null)return;
    for(int index=0;index<parents.length();index++){
      JSONObject parent=parents.optJSONObject(index);
      if(parent!=null&&sourceId.equals(string(parent,"folderid")))
        throw new CloudFilesClient.CloudException(CloudFilesClient.ERROR_INVALID,"不能把目录移动到其子目录");
    }
  }

  CloudFilesClient.CopyResult copy(CloudFilesClient.CloudItem item,String targetId,String tempDirectory)
      throws CloudFilesClient.CloudException{
    validId(item.id,"id");
    String target=validId(targetId,"targetId");
    File root=prepareTempDirectory(tempDirectory);
    int[] counts=new int[]{0,0};
    long[] bytes=new long[]{0};
    String createdId="";
    if(item.folder){
      Set<String> visited=new HashSet<>();
      createdId=copyFolderRecursive(item.id,item.name,target,0,visited,root,counts,bytes);
    }else{
      createdId=copyFile(item.id,item.name,target,root,counts,bytes);
    }
    return new CloudFilesClient.CopyResult(item.id,createdId,target,counts[0],counts[1],bytes[0]);
  }

  private String copyFolderRecursive(String sourceId,String name,String targetId,int depth,
                                     Set<String> visited,File tempRoot,int[] counts,long[] bytes)
      throws CloudFilesClient.CloudException{
    if(depth>MAX_DIRECTORY_DEPTH||!visited.add(sourceId))
      throw new CloudFilesClient.CloudException(CloudFilesClient.ERROR_INVALID,"目录层级过深或存在循环");
    ShareRecord source=shareRecord(sourceId,true);
    String newId=mkdirId(targetId,name,source.description);
    if(!source.password.isEmpty())setPasswordRaw(newId,true,source.password);
    counts[1]++;
    for(CloudFilesClient.CloudItem item:listAll(sourceId)){
      if(item.folder)copyFolderRecursive(item.id,item.name,newId,depth+1,visited,tempRoot,counts,bytes);
      else copyFile(item.id,item.name,newId,tempRoot,counts,bytes);
    }
    visited.remove(sourceId);
    return newId;
  }

  private String copyFile(String sourceId,String name,String targetId,File tempRoot,int[] counts,long[] bytes)
      throws CloudFilesClient.CloudException{
    safeName(name);
    File work=new File(tempRoot,"copy-"+sourceId+"-"+UUID.randomUUID().toString());
    if(!work.mkdirs()&&!work.isDirectory())
      throw new CloudFilesClient.CloudException(CloudFilesClient.ERROR_NETWORK,"无法创建复制临时目录");
    File temp=new File(work,name);
    try{
      CloudFilesClient.DownloadResult downloaded=downloadRaw(sourceId,name,temp.getAbsolutePath());
      CloudFilesClient.UploadResult uploaded=upload(targetId,temp.getAbsolutePath(),
          CloudFilesClient.UploadOptions.defaults().compatibilityWrap(false).kind("file"));
      ManagedRecord metadata=managedMetadata(sourceId,false);
      if(!metadata.description.isEmpty())task(map("task","11","file_id",uploaded.id,"desc",metadata.description));
      counts[0]++;
      bytes[0]+=downloaded.bytesDownloaded;
      return uploaded.id;
    }finally{
      deleteQuietly(temp);
      deleteQuietly(work);
    }
  }

  void delete(CloudFilesClient.CloudItem item)throws CloudFilesClient.CloudException{
    validId(item.id,"id");
    synchronized(networkLock){
      requireSession();
      deleteRaw(item.id,item.folder);
    }
  }

  void moveMany(List<CloudFilesClient.CloudItem> items,String targetId)throws CloudFilesClient.CloudException{
    String target=validId(targetId,"targetId");
    for(CloudFilesClient.CloudItem item:items){
      if(item==null)continue;
      if(target.equals(item.id))
        throw new CloudFilesClient.CloudException(CloudFilesClient.ERROR_INVALID,"不能把文件夹移动到自身");
      move(item,target);
    }
  }

  void deleteMany(List<CloudFilesClient.CloudItem> items)throws CloudFilesClient.CloudException{
    for(CloudFilesClient.CloudItem item:items)if(item!=null)delete(item);
  }

  private void deleteRaw(String id,boolean folder)throws CloudFilesClient.CloudException{
    task(folder?map("task","3","folder_id",id):map("task","6","file_id",id));
  }

  private void moveFile(String id,String targetId)throws CloudFilesClient.CloudException{
    task(map("task","20","file_id",id,"folder_id",targetId));
  }

  private CloudFilesClient.ManagedDescription getDescriptionInternal(CloudFilesClient.CloudItem item)
      throws CloudFilesClient.CloudException{
    ManagedRecord record=managedMetadata(item.id,item.folder);
    String description=item.folder?visibleDescription(record.description):record.description;
    return new CloudFilesClient.ManagedDescription(item.id,item.folder,record.name,description);
  }

  CloudFilesClient.ManagedDescription getDescription(CloudFilesClient.CloudItem item)
      throws CloudFilesClient.CloudException{
    validId(item.id,"id");
    synchronized(networkLock){
      requireSession();
      return getDescriptionInternal(item);
    }
  }

  private ManagedRecord managedMetadata(String id,boolean folder)throws CloudFilesClient.CloudException{
    JSONObject value=folder
        ?task(map("task","18","folder_id",id))
        :task(map("task","12","file_id",id));
    if(folder){
      JSONObject info=value.optJSONObject("info");
      if(info==null)
        throw new CloudFilesClient.CloudException(CloudFilesClient.ERROR_PROTOCOL,"目录信息缺少 info");
      String name=string(info,"name");
      String description=string(info,"des");
      if(name.isEmpty())
        throw new CloudFilesClient.CloudException(CloudFilesClient.ERROR_PROTOCOL,"目录信息缺少 name");
      return new ManagedRecord(name,description);
    }
    Object text=value.opt("text");
    Object info=value.opt("info");
    if(!(text instanceof String))
      throw new CloudFilesClient.CloudException(CloudFilesClient.ERROR_PROTOCOL,"文件信息缺少 text");
    if(!(info instanceof String))
      throw new CloudFilesClient.CloudException(CloudFilesClient.ERROR_PROTOCOL,"文件信息缺少 info");
    return new ManagedRecord((String)text,(String)info);
  }

  void setDescription(CloudFilesClient.CloudItem item,String description)throws CloudFilesClient.CloudException{
    validId(item.id,"id");
    if(description==null)description="";
    if(description.length()>4000||description.indexOf('\0')>=0)
      throw new CloudFilesClient.CloudException(CloudFilesClient.ERROR_INVALID,"简介过长或包含非法字符");
    synchronized(networkLock){
      requireSession();
      if(item.folder){
        ShareRecord record=shareRecord(item.id,true);
        String stored=preserveFragmentDescription(record.description,description);
        if(stored.length()>4000)
          throw new CloudFilesClient.CloudException(CloudFilesClient.ERROR_INVALID,"简介与分片标记合并后过长");
        task(map("task","4","folder_id",item.id,"folder_name",record.name,
            "folder_description",stored));
      }else{
        task(map("task","11","file_id",item.id,"desc",description));
      }
    }
  }

  private String mkdirId(String parentId,String name,String description)throws CloudFilesClient.CloudException{
    Set<String> before=matchingFolderIds(listAll(parentId),name);
    JSONObject value=task(map("task","2","parent_id",parentId,"folder_name",name,
        "folder_description",description==null?"":description));
    String candidate=scalar(value.opt("text"));
    if(validNumericId(candidate)&&!candidate.equals("-1"))return candidate;
    Set<String> after=matchingFolderIds(listAll(parentId),name);
    after.removeAll(before);
    if(after.size()!=1)
      throw new CloudFilesClient.CloudException(CloudFilesClient.ERROR_PROTOCOL,
          after.isEmpty()?"官方已确认创建但未返回新文件夹 ID":"创建后出现多个同名文件夹，无法确定新目录");
    return after.iterator().next();
  }

  private Set<String> matchingFolderIds(List<CloudFilesClient.CloudItem> items,String name){
    Set<String> ids=new LinkedHashSet<>();
    for(CloudFilesClient.CloudItem item:items)
      if(item.folder&&name.equals(item.name))ids.add(item.id);
    return ids;
  }

  private static String fileStem(String name){
    int dot=name.lastIndexOf('.');
    if(dot>0&&dot<name.length()-1)return name.substring(0,dot);
    return name;
  }

  private static String normalizedName(String account)throws CloudFilesClient.CloudException{
    String name=account==null?"":account.trim();
    if(name.isEmpty())
      throw new CloudFilesClient.CloudException(CloudFilesClient.ERROR_CREDENTIALS_REQUIRED,"请选择蓝奏云账号");
    return name;
  }

  private Object accountLock(String name){
    synchronized(vaultLock){
      Object lock=accountLocks.get(name);
      if(lock==null){lock=new Object();accountLocks.put(name,lock);}
      return lock;
    }
  }

  private void persist(Vault vault)throws CloudFilesClient.CloudException{
    try{
      JSONArray accounts=new JSONArray();
      for(Credentials credentials:vault.accounts.values()){
        accounts.put(new JSONObject()
            .put("loginName",credentials.loginName)
            .put("loginPwd",credentials.password)
            .put("cookie",credentials.cookie)
            .put("uid",credentials.uid)
            .put("vei",credentials.vei));
      }
      JSONObject object=new JSONObject().put("version",1).put("current",vault.current)
          .put("accounts",accounts);
      Cipher cipher=Cipher.getInstance("AES/GCM/NoPadding");
      cipher.init(Cipher.ENCRYPT_MODE,key());
      byte[] encrypted=cipher.doFinal(object.toString().getBytes(StandardCharsets.UTF_8));
      boolean committed=preferences.edit()
          .putString(PREF_IV,Base64.encodeToString(cipher.getIV(),Base64.NO_WRAP))
          .putString(PREF_SECRET,Base64.encodeToString(encrypted,Base64.NO_WRAP))
          .putString(PREF_CURRENT,vault.current)
          .commit();
      if(!committed)
        throw new CloudFilesClient.CloudException(CloudFilesClient.ERROR_CREDENTIALS_UNREADABLE,
            "无法安全保存登录信息");
    }catch(CloudFilesClient.CloudException error){
      throw error;
    }catch(Exception error){
      throw new CloudFilesClient.CloudException(CloudFilesClient.ERROR_CREDENTIALS_UNREADABLE,
          "无法安全保存登录信息",error);
    }
  }

  private Vault loadVault()throws CloudFilesClient.CloudException{
    String iv=preferences.getString(PREF_IV,"");
    String secret=preferences.getString(PREF_SECRET,"");
    if(iv.isEmpty()||secret.isEmpty())return new Vault();
    try{
      Cipher cipher=Cipher.getInstance("AES/GCM/NoPadding");
      cipher.init(Cipher.DECRYPT_MODE,key(),new GCMParameterSpec(128,Base64.decode(iv,Base64.NO_WRAP)));
      String json=new String(cipher.doFinal(Base64.decode(secret,Base64.NO_WRAP)),StandardCharsets.UTF_8);
      JSONObject object=new JSONObject(json);
      Vault vault=new Vault();
      JSONArray array=object.optJSONArray("accounts");
      if(array==null)throw new IllegalStateException("missing accounts");
      for(int index=0;index<array.length();index++){
        JSONObject item=array.optJSONObject(index);
        if(item==null)continue;
        String name=item.optString("loginName","").trim();
        String password=item.optString("loginPwd","");
        if(name.isEmpty()||password.isEmpty())continue;
        vault.accounts.put(name,new Credentials(name,password,item.optString("cookie",""),
            item.optString("uid",""),item.optString("vei","")));
      }
      if(vault.accounts.isEmpty())throw new IllegalStateException("empty credentials");
      vault.current=object.optString("current",preferences.getString(PREF_CURRENT,""));
      if(!vault.accounts.containsKey(vault.current))
        vault.current=vault.accounts.keySet().iterator().next();
      return vault;
    }catch(Exception error){
      clearStoredLogin();
      throw new CloudFilesClient.CloudException(CloudFilesClient.ERROR_CREDENTIALS_UNREADABLE,
          "本机登录凭据已失效，请重新输入",error);
    }
  }

  private void clearStoredLogin(){
    preferences.edit().remove(PREF_IV).remove(PREF_SECRET).remove(PREF_CURRENT).apply();
    sessions.clear();
    accountLocks.clear();
    active=null;
    jar.clear();
    try{
      KeyStore store=KeyStore.getInstance("AndroidKeyStore");
      store.load(null);
      if(store.containsAlias(KEY_ALIAS))store.deleteEntry(KEY_ALIAS);
    }catch(Exception ignored){}
  }

  private SecretKey key()throws Exception{
    KeyStore store=KeyStore.getInstance("AndroidKeyStore");
    store.load(null);
    KeyStore.Entry entry=store.getEntry(KEY_ALIAS,null);
    if(entry instanceof KeyStore.SecretKeyEntry)
      return ((KeyStore.SecretKeyEntry)entry).getSecretKey();
    KeyGenerator generator=KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES,"AndroidKeyStore");
    generator.init(new KeyGenParameterSpec.Builder(KEY_ALIAS,
        KeyProperties.PURPOSE_ENCRYPT|KeyProperties.PURPOSE_DECRYPT)
        .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
        .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
        .setRandomizedEncryptionRequired(true).build());
    return generator.generateKey();
  }

  private Session login(String account,String password)throws CloudFilesClient.CloudException{
    synchronized(networkLock){
      String entry=ACCOUNTS_ORIGIN+"/accounts.php?action=login&ref=up.woozooo.com";
      get(entry,"",false);
      Response response=postForm(ACCOUNTS_ORIGIN+"/accounts.php","",
          map("task","uselogin","username",account,"password",password,"ref","up.woozooo.com"),
          entry,false);
      JSONObject value=parseJson(response.text);
      if(value.optInt("zt",0)!=1)
        throw new CloudFilesClient.CloudException(CloudFilesClient.ERROR_CREDENTIALS_REQUIRED,"账号或密码错误");
      String handoff=value.optString("msgs","");
      if(handoff.isEmpty())
        throw new CloudFilesClient.CloudException(CloudFilesClient.ERROR_PROTOCOL,"登录响应缺少授权转接地址");
      URI target;
      try{
        target=new URI(entry).resolve(handoff);
      }catch(Exception error){
        throw new CloudFilesClient.CloudException(CloudFilesClient.ERROR_PROTOCOL,"登录授权转接地址无效",error);
      }
      String host=target.getHost();
      if(!"https".equalsIgnoreCase(target.getScheme())||host==null
          ||!("woozooo.com".equalsIgnoreCase(host)||host.toLowerCase(Locale.ROOT).endsWith(".woozooo.com"))
          ||target.getUserInfo()!=null)
        throw new CloudFilesClient.CloudException(CloudFilesClient.ERROR_PROTOCOL,"官方授权转接地址不属于蓝奏云");
      get(target.toString(),"",true);
      String cookie=jar.mergedCookies(PC_ORIGIN,UP_ORIGIN);
      if(cookie.isEmpty())
        throw new CloudFilesClient.CloudException(CloudFilesClient.ERROR_PROTOCOL,"登录成功但未收到会话 cookie");
      Session session=new Session(account,account,password,cookie,"","");
      bootstrap(session);
      session.cookie=mergeCookie(session.cookie,jar.cookiesFor(PC_ORIGIN));
      return session;
    }
  }

  private void bootstrap(Session session)throws CloudFilesClient.CloudException{
    Response response=get(PC_ORIGIN+"/mydisk.php?item=files&action=index",session.cookie,true);
    String html=response.text;
    ensureSessionHtml(html);
    Matcher uid=Pattern.compile("uid=([^'\"&;\\s]+)").matcher(html);
    if(!uid.find())
      throw new CloudFilesClient.CloudException(CloudFilesClient.ERROR_SESSION_EXPIRED,"登录已过期");
    String vei=jsDataFields(html).getOrDefault("vei","");
    if(vei.isEmpty())
      throw new CloudFilesClient.CloudException(CloudFilesClient.ERROR_PROTOCOL,"登录页面缺少 vei");
    session.uid=uid.group(1);
    session.vei=vei;
  }

  private void ensureSessionHtml(String html)throws CloudFilesClient.CloudException{
    String compact=html==null?"":html.replaceAll("\\s+","");
    boolean loginTitle=compact.contains("网盘用户登录")||compact.contains("用户登录")
        ||compact.contains("登录蓝奏云");
    boolean hasPassword=html!=null&&(html.contains("type=\"password\"")||html.contains("type='password'")
        ||html.contains("name=\"password\"")||html.contains("name='password'"));
    boolean hasUser=html!=null&&(html.contains("name=\"username\"")||html.contains("name='username'")
        ||html.contains("name=\"account\"")||html.contains("name='account'"));
    if(loginTitle||(hasPassword&&hasUser&&(compact.contains("登录")||compact.contains("登陆"))))
      throw new CloudFilesClient.CloudException(CloudFilesClient.ERROR_SESSION_EXPIRED,"登录已过期");
  }

  private JSONObject task(Map<String,String> form)throws CloudFilesClient.CloudException{
    Session session=requireSession();
    Response response=postForm(taskUrl(session.cookie),session.cookie,form,taskUrl(""),true);
    return parseApi(parseJson(response.text));
  }

  private JSONObject taskWithCookie(Map<String,String> form,String cookie)
      throws CloudFilesClient.CloudException{
    Response response=postForm(taskUrl(cookie),cookie,form,taskUrl(""),true);
    return parseApi(parseJson(response.text));
  }

  private JSONObject taskRawWithCookie(Map<String,String> form,String cookie)
      throws CloudFilesClient.CloudException{
    Response response=postForm(taskUrl(cookie),cookie,form,taskUrl(""),true);
    return parseJson(response.text);
  }

  private String taskUrl(String ignored)throws CloudFilesClient.CloudException{
    Session session=requireSession();
    return PC_ORIGIN+"/doupload.php?uid="+encode(session.uid)+"&vei="+encode(session.vei);
  }

  private JSONObject parseApi(JSONObject value)throws CloudFilesClient.CloudException{
    int status=value.optInt("zt",0);
    if(status==1||status==2||status==4)return value;
    if(status==9)
      throw new CloudFilesClient.CloudException(CloudFilesClient.ERROR_SESSION_EXPIRED,"登录已过期");
    String message=firstNonEmpty(value.optString("inf",""),value.optString("info",""),
        value.optString("msgs",""),"蓝奏云操作失败");
    throw new CloudFilesClient.CloudException(status==4?CloudFilesClient.ERROR_NETWORK:CloudFilesClient.ERROR_PROTOCOL,
        sanitize(message));
  }

  private List<CloudFilesClient.CloudItem> parseItems(Object raw,boolean folder)
      throws CloudFilesClient.CloudException{
    if(!(raw instanceof JSONArray))
      throw new CloudFilesClient.CloudException(CloudFilesClient.ERROR_PROTOCOL,"文件列表字段缺失");
    JSONArray array=(JSONArray)raw;
    List<CloudFilesClient.CloudItem> items=new ArrayList<>();
    for(int index=0;index<array.length();index++){
      JSONObject entry=array.optJSONObject(index);
      if(entry==null)continue;
      String id=string(entry,folder?"fol_id":"id");
      String name=string(entry,folder?"name":"name_all").replace("&amp;","&");
      if(id.isEmpty()||name.isEmpty())continue;
      long size=parseSize(string(entry,"size"));
      String modified=string(entry,"time");
      String description=trimBrackets(string(entry,"folder_des"));
      boolean hasPassword=valueAsLong(entry.opt("onof"),0)==1;
      long downloads=Math.max(0,valueAsLong(entry.opt("downs"),0));
      boolean custom=false;
      String icon="";
      if(!folder){
        custom=valueAsLong(entry.opt("is_ico"),valueAsLong(entry.opt("p_ico"),0))==1;
        if(custom)icon=trustedIconUrl(string(entry,"ico"));
      }
      items.add(new CloudFilesClient.CloudItem(id,name,folder,size,modified,description,
          hasPassword,downloads,"",custom,icon));
    }
    return items;
  }

  private static long parseSize(String value){
    if(value==null||value.isEmpty())return 0;
    String text=value.trim().toUpperCase(Locale.ROOT);
    double scale=1;
    if(text.endsWith("G")||text.endsWith("GB"))scale=1024d*1024d*1024d;
    else if(text.endsWith("M")||text.endsWith("MB"))scale=1024d*1024d;
    else if(text.endsWith("K")||text.endsWith("KB"))scale=1024d;
    String number=text.replaceAll("[^0-9.]","");
    try{return (long)Math.max(0,Double.parseDouble(number)*scale);}catch(Exception ignored){return 0;}
  }

  private static String trimBrackets(String value){
    String text=value==null?"":value.trim();
    if(text.length()>=2&&text.startsWith("[")&&text.endsWith("]"))
      return text.substring(1,text.length()-1).trim();
    return text;
  }

  private static String trustedIconUrl(String value){
    if(value==null||value.isEmpty()||value.length()>16*1024||containsControl(value))return "";
    String candidate=value.startsWith("https://")?value:
        value.contains("://")?"":"https://"+value.replaceFirst("^/+","");
    try{
      URI uri=new URI(candidate);
      String host=uri.getHost();
      if(!"https".equalsIgnoreCase(uri.getScheme())||!"image.dmpdmp.com".equalsIgnoreCase(host)
          ||uri.getUserInfo()!=null||(uri.getPort()!=-1&&uri.getPort()!=443))return "";
      return candidate;
    }catch(Exception ignored){return "";}
  }

  CloudFilesClient.ShareInfo shareInfo(CloudFilesClient.CloudItem item)
      throws CloudFilesClient.CloudException{
    validId(item.id,"id");
    synchronized(networkLock){
      requireSession();
      ShareRecord record=shareRecord(item.id,item.folder);
      String direct="";
      if(!item.folder){
        try{direct=resolveDownload(record.url,record.password).url;}catch(CloudFilesClient.CloudException ignored){}
      }
      String mode="";
      int parts=0;
      String links="";
      if(item.folder){
        FragmentManifest manifest=FragmentManifest.parse(record.description);
        if(manifest!=null){
          FragmentSnapshot snapshot=fragmentShareInfo(item.id,manifest);
          mode=manifest.mode;
          parts=manifest.partCount;
          links=snapshot.links;
        }
      }
      String resource=resourceUrl(record.url,record.password);
      return new CloudFilesClient.ShareInfo(item.id,item.folder,record.name,record.url,record.password,
          visibleDescription(record.description),direct,resource,parts>0,mode,parts,links);
    }
  }

  private ShareRecord shareRecord(String id,boolean folder)throws CloudFilesClient.CloudException{
    JSONObject value=folder
        ?task(map("task","18","folder_id",id))
        :task(map("task","22","file_id",id));
    JSONObject info=value.optJSONObject("info");
    if(info==null)
      throw new CloudFilesClient.CloudException(CloudFilesClient.ERROR_PROTOCOL,"分享信息字段缺失");
    boolean enabled="1".equals(string(info,"onof"));
    String password=enabled?string(info,"pwd"):"";
    String name;
    String url;
    String description="";
    if(folder){
      name=string(info,"name");
      url=absoluteShareUrl(string(info,"new_url"),string(info,"is_newd"));
      description=string(info,"des");
    }else{
      String shareId=string(info,"f_id");
      if("i".equals(shareId))
        throw new CloudFilesClient.CloudException(CloudFilesClient.ERROR_NOT_FOUND,"文件不存在");
      url=absoluteShareUrl(shareId,string(info,"is_newd"));
      ManagedRecord metadata=managedMetadata(id,false);
      name=metadata.name;
      description=metadata.description;
    }
    if(url.isEmpty())
      throw new CloudFilesClient.CloudException(CloudFilesClient.ERROR_PROTOCOL,"分享地址为空");
    return new ShareRecord(name,url,password,description);
  }

  private static String absoluteShareUrl(String path,String origin){
    String value=path==null?"":path.trim();
    if(value.startsWith("http://")||value.startsWith("https://"))return value;
    if(value.startsWith("//"))return "https:"+value;
    String base="";
    if(origin!=null&&!origin.trim().isEmpty()){
      try{
        URI uri=new URI(origin.trim());
        String host=uri.getHost();
        if(host!=null&&("http".equalsIgnoreCase(uri.getScheme())||"https".equalsIgnoreCase(uri.getScheme())))
          base="https://"+host;
      }catch(Exception ignored){}
    }
    if(base.isEmpty())base="https://pan.lanzoui.com";
    return base+"/"+value.replaceFirst("^/+","");
  }

  private static String resourceUrl(String shareUrl,String password){
    if(shareUrl==null||shareUrl.isEmpty())return "";
    if(password==null||password.isEmpty())return shareUrl;
    try{
      String fragment="";
      int hash=shareUrl.indexOf('#');
      String base=shareUrl;
      if(hash>=0){fragment=shareUrl.substring(hash);base=shareUrl.substring(0,hash);}
      String query="pwd="+encode(password);
      int question=base.indexOf('?');
      if(question>=0){
        String existing=base.substring(question+1);
        StringBuilder filtered=new StringBuilder();
        for(String pair:existing.split("&")){
          if(pair.isEmpty())continue;
          String key=pair.split("=",2)[0];
          if("pwd".equals(key)||"password".equals(key)||"code".equals(key))continue;
          if(filtered.length()>0)filtered.append('&');
          filtered.append(pair);
        }
        if(filtered.length()>0)filtered.append('&');
        filtered.append(query);
        return base.substring(0,question+1)+filtered+fragment;
      }
      return base+"?"+query+fragment;
    }catch(Exception ignored){return shareUrl;}
  }

  void setPassword(CloudFilesClient.CloudItem item,String password)throws CloudFilesClient.CloudException{
    validId(item.id,"id");
    synchronized(networkLock){
      requireSession();
      setPasswordRaw(item.id,item.folder,password==null?"":password);
    }
  }

  private void setPasswordRaw(String id,boolean folder,String password)
      throws CloudFilesClient.CloudException{
    if(password.length()>12||containsControl(password))
      throw new CloudFilesClient.CloudException(CloudFilesClient.ERROR_INVALID,"提取码过长或包含非法字符");
    String enabled=password.isEmpty()?"0":"1";
    if(folder){
      task(map("task","16","folder_id",id,"shows",enabled,"shownames",password));
    }else{
      if(!password.isEmpty()&&(password.length()<2||password.length()>6))
        throw new CloudFilesClient.CloudException(CloudFilesClient.ERROR_INVALID,"文件提取码长度必须为 2 到 6 位");
      task(map("task","23","file_id",id,"shows",enabled,"shownames",password));
    }
  }

  CloudFilesClient.DownloadResult download(CloudFilesClient.CloudItem item,String destination)
      throws CloudFilesClient.CloudException{
    validId(item.id,"id");
    synchronized(networkLock){
      requireSession();
      return downloadRaw(item.id,item.name,destination);
    }
  }

  private CloudFilesClient.DownloadResult downloadRaw(String id,String requestedName,String destination)
      throws CloudFilesClient.CloudException{
    ShareRecord share=shareRecord(id,false);
    String name=requestedName==null||requestedName.trim().isEmpty()
        ?(share.name.isEmpty()?"download.bin":share.name):requestedName;
    safeName(name);
    File requested=new File(destination);
    boolean directoryTarget=requested.isDirectory()||destination.endsWith("/")||destination.endsWith("\\");
    File target=directoryTarget?new File(requested,name):requested;
    if(directoryTarget&&!requested.isDirectory()&&!requested.mkdirs())
      throw new CloudFilesClient.CloudException(CloudFilesClient.ERROR_NETWORK,"无法创建下载目录");
    File parent=target.getParentFile();
    if(parent!=null&&!parent.isDirectory()&&!parent.mkdirs())
      throw new CloudFilesClient.CloudException(CloudFilesClient.ERROR_NETWORK,"无法创建下载目录");
    if(target.exists())
      throw new CloudFilesClient.CloudException(CloudFilesClient.ERROR_INVALID,"目标文件已存在");
    LanzouCore.DirectLink direct=resolveDownload(share.url,share.password);
    File part=new File(target.getAbsolutePath()+".part");
    deleteQuietly(part);
    long downloaded;
    try{
      downloaded=downloadToFile(direct,part,share.url);
    }catch(CloudFilesClient.CloudException error){
      deleteQuietly(part);
      throw error;
    }
    UnwrapInfo unwrapped=unwrapInPlace(part);
    File finalPath=target;
    if(directoryTarget&&unwrapped.unwrapped&&unwrapped.originalName!=null
        &&!unwrapped.originalName.equals(target.getName())){
      safeName(unwrapped.originalName);
      File renamed=new File(requested,unwrapped.originalName);
      if(renamed.exists()){
        deleteQuietly(part);
        throw new CloudFilesClient.CloudException(CloudFilesClient.ERROR_INVALID,"目标文件已存在");
      }
      finalPath=renamed;
    }
    if(!part.renameTo(finalPath)){
      try{
        copyFile(part,finalPath,true);
      }catch(IOException error){
        deleteQuietly(part);
        throw new CloudFilesClient.CloudException(CloudFilesClient.ERROR_NETWORK,"无法保存下载文件",error);
      }finally{deleteQuietly(part);}
    }
    long written=finalPath.length();
    return new CloudFilesClient.DownloadResult(id,finalPath.getAbsolutePath(),
        unwrapped.unwrapped?written:downloaded,unwrapped.unwrapped,unwrapped.originalName);
  }

  private LanzouCore.DirectLink resolveDownload(String shareUrl,String password)
      throws CloudFilesClient.CloudException{
    try{
      String key=shareUrl+"\n"+password;
      if(key.equals(lastDownloadKey)&&lastDownload!=null&&System.currentTimeMillis()-lastDownloadAt<30_000)return lastDownload;
      LanzouCore core=new LanzouCore(context);
      LanzouCore.DirectLink result;
      try{result=core.resolveDirect(shareUrl,password);}
      catch(Exception error){
        LanzouCore.UserVerificationRequiredException challenge=LanzouCore.userVerificationRequired(error);
        CloudFilesClient.VerificationPrompt prompt=verificationPrompt;
        String target=challenge==null?"":challenge.url;
        if(target.isEmpty()||prompt==null||!prompt.verify(target))throw error;
        result=core.resolveDirect(shareUrl,password);
      }
      lastDownloadKey=key;lastDownload=result;lastDownloadAt=System.currentTimeMillis();return result;
    }
    catch(Exception error){
      throw new CloudFilesClient.CloudException(CloudFilesClient.ERROR_PROTOCOL,
          "下载链接解析失败："+sanitize(error.getMessage()),error);
    }
  }

  private static String resolveHttp(String base,String value)throws CloudFilesClient.CloudException{
    try{
      URI resolved=new URI(base).resolve(value.trim());
      if(resolved.getHost()==null
          ||(!"http".equalsIgnoreCase(resolved.getScheme())&&!"https".equalsIgnoreCase(resolved.getScheme())))
        throw new CloudFilesClient.CloudException(CloudFilesClient.ERROR_PROTOCOL,"下载重定向地址不受信任");
      return resolved.toString();
    }catch(CloudFilesClient.CloudException error){throw error;
    }catch(Exception error){
      throw new CloudFilesClient.CloudException(CloudFilesClient.ERROR_PROTOCOL,"下载重定向地址无效",error);
    }
  }

  private static Map<String,String> jsDataFields(String html)throws CloudFilesClient.CloudException{
    if(html==null)throw new CloudFilesClient.CloudException(CloudFilesClient.ERROR_PROTOCOL,"页面缺少下载参数");
    Matcher data=Pattern.compile("(?s)data\\s*[:=]\\s*\\{([^}]+)\\}").matcher(html);
    String content=null;
    while(data.find()){
      if(content==null||data.group(1).length()>content.length())content=data.group(1);
    }
    if(content==null)
      throw new CloudFilesClient.CloudException(CloudFilesClient.ERROR_PROTOCOL,"页面缺少下载参数");
    Map<String,String> result=new LinkedHashMap<>();
    Matcher pair=Pattern.compile("['\"]?([A-Za-z0-9_]+)['\"]?\\s*:\\s*([^,}]+)").matcher(content);
    while(pair.find()){
      String key=pair.group(1);
      String raw=pair.group(2).trim();
      String value;
      if(raw.matches("\\d+")||raw.startsWith("'")||raw.startsWith("\""))
        value=raw.replaceAll("^['\"]|['\"]$","");
      else value=lastVariable(html,raw);
      result.put(key,value);
    }
    if(result.isEmpty())
      throw new CloudFilesClient.CloudException(CloudFilesClient.ERROR_PROTOCOL,"页面下载参数为空");
    return result;
  }

  CloudFilesClient.UploadResult upload(String folderId,String path,CloudFilesClient.UploadOptions options)
      throws CloudFilesClient.CloudException{
    validId(folderId,"folderId");
    synchronized(networkLock){
      requireSession();
      return uploadPrepared(folderId,path,options==null?CloudFilesClient.UploadOptions.defaults():options);
    }
  }

  private CloudFilesClient.UploadResult uploadPrepared(String folderId,String path,
                                                       CloudFilesClient.UploadOptions options)
      throws CloudFilesClient.CloudException{
    PreparedUpload prepared=prepareUpload(path,options.compatibilityWrap,options.kind);
    try{
      Map<String,String> fields=map("task","1","vie","2","ve","2","id","WU_FILE_0",
          "name",prepared.remoteName,"folder_id_bb_n",folderId);
      Response response=postMultipart(PC_ORIGIN+"/html5up.php","",fields,"upload_file",
          prepared.file,prepared.remoteName,"application/octet-stream",
          PC_ORIGIN+"/mydisk.php?item=files&action=index");
      JSONObject value=parseApi(parseJson(response.text));
      String id=uploadFileId(value);
      if(id.isEmpty())
        throw new CloudFilesClient.CloudException(CloudFilesClient.ERROR_PROTOCOL,
            "上传完成但无法安全确认新文件 ID");
      String warning="";
      boolean iconSource=!options.iconPath.isEmpty()
          ||prepared.originalName.toLowerCase(Locale.ROOT).endsWith(".apk");
      if(options.autoIcon&&iconSource){
        try{setIcon(id,options.iconPath.isEmpty()?path:options.iconPath);}
        catch(CloudFilesClient.CloudException iconFailure){
          warning="图标未设置："+iconFailure.getMessage();
        }
      }
      return new CloudFilesClient.UploadResult(id,folderId,prepared.originalName,prepared.remoteName,
          prepared.uploadBytes,prepared.wrapped,"",0,0,"",warning);
    }finally{
      if(prepared.temporary)deleteQuietly(prepared.file);
    }
  }

  private long officialSingleFileLimit()throws CloudFilesClient.CloudException{
      Session session=requireSession();
      Response mypower=get(PC_ORIGIN+"/mydisk.php?item=profile&action=mypower",session.cookie,false);
      ensureSessionHtml(mypower.text);
      long limit=parseSingleFileLimit(mypower.text);
      if(limit>0)return limit;
      Response huiyuan=get(PC_ORIGIN+"/mydisk.php?item=profile&action=huiyuan",session.cookie,false);
      ensureSessionHtml(huiyuan.text);
      return parseSingleFileLimit(huiyuan.text);
  }

  static long parseSingleFileLimit(String html){
    if(html==null||html.isEmpty())return 0;
    Matcher tableMatcher=Pattern.compile("<t[hd][^>]*>[^<]*单个文件大小[^<]*</t[hd]>\\s*<t[hd][^>]*>([^<]+)</t[hd]>",
        Pattern.CASE_INSENSITIVE).matcher(html);
    if(tableMatcher.find()){
      long parsed=parseSize(tableMatcher.group(1));
      if(parsed>0)return parsed;
    }
    Matcher dlMatcher=Pattern.compile("<dt[^>]*>[^<]*单个文件大小[^<]*</dt>\\s*<dd[^>]*>([^<]+)</dd>",
        Pattern.CASE_INSENSITIVE).matcher(html);
    if(dlMatcher.find()){
      long parsed=parseSize(dlMatcher.group(1));
      if(parsed>0)return parsed;
    }
    Matcher generalMatcher=Pattern.compile("单个文件大小[^0-9A-Za-z]{0,20}([0-9]+(?:\\.[0-9]+)?\\s*(?:[KMG]B?|G|M|K))",
        Pattern.CASE_INSENSITIVE).matcher(html);
    if(generalMatcher.find()){
      long parsed=parseSize(generalMatcher.group(1));
      if(parsed>0)return parsed;
    }
    return 0;
  }

  CloudFilesClient.UploadResult uploadFragmented(String folderId,String path,
                                                 CloudFilesClient.UploadOptions options)
      throws CloudFilesClient.CloudException{
    validId(folderId,"folderId");
    if(options==null)options=CloudFilesClient.UploadOptions.defaults();
    File source=new File(path);
    if(!source.isFile())
      throw new CloudFilesClient.CloudException(CloudFilesClient.ERROR_INVALID,"分片上传源必须是普通文件");
    if(source.length()==0)
      throw new CloudFilesClient.CloudException(CloudFilesClient.ERROR_INVALID,"空文件不能执行分片上传");
    synchronized(networkLock){
      requireSession();
      File tempRoot=new File(context.getCacheDir(),"fragments/"+UUID.randomUUID().toString());
      if(!tempRoot.mkdirs()&&!tempRoot.isDirectory())
        throw new CloudFilesClient.CloudException(CloudFilesClient.ERROR_NETWORK,"无法创建分片临时目录");
      try{
        String mode="archive_volume".equalsIgnoreCase(options.fragmentMode)
            ?"archive_volume":"byte_split";
        boolean archiveInput=false;
        File splitSource=source;
        if("archive_volume".equals(mode)){
          int kind=archiveKind(source);
          archiveInput=kind!=0;
          splitSource=new File(tempRoot,"volume-source.zip");
          buildVolumeArchive(source,splitSource,archiveInput);
        }
        long confirmedLimitBytes="automatic".equals(options.fragmentSplitPolicy)?officialSingleFileLimit():0;
        int partCount=options.plannedFragmentCount(splitSource.length(),confirmedLimitBytes);
        if(partCount>splitSource.length())
          throw new CloudFilesClient.CloudException(CloudFilesClient.ERROR_INVALID,
              "archive_volume".equals(mode)
                  ?"压缩后的数据不足以生成指定数量的非空分卷"
                  :"分片数量不能超过源文件字节数");
        FragmentManifest manifest=new FragmentManifest(mode,source.getName(),source.length(),
            partCount,binaryWidth(partCount),archiveInput);
        List<File> pieces=splitFile(splitSource,tempRoot,manifest);
        String rootId=mkdirId(folderId,manifest.originalName,"");
        List<String> links=new ArrayList<>();
        for(int index=0;index<pieces.size();index++){
          int part=index+1;
          String childId=mkdirId(rootId,Integer.toString(part),"");
          CloudFilesClient.UploadResult uploaded=uploadPrepared(childId,pieces.get(index).getAbsolutePath(),
              CloudFilesClient.UploadOptions.defaults().compatibilityWrap(false).kind("file"));
          ShareRecord share=shareRecord(uploaded.id,false);
          links.add(manifest.partUri(part,share.url));
        }
        task(map("task","4","folder_id",rootId,"folder_name",manifest.originalName,
            "folder_description",manifest.descriptionMarker()));
        String external=joinLines(links);
        return new CloudFilesClient.UploadResult(rootId,folderId,manifest.originalName,
            manifest.originalName,sumLengths(pieces),false,mode,manifest.partCount,
            manifest.binaryWidth,external);
      }finally{
        deleteTree(tempRoot);
      }
    }
  }

  CloudFilesClient.DirectoryUploadResult uploadDirectory(String folderId,String path,
                                                         CloudFilesClient.UploadOptions options)
      throws CloudFilesClient.CloudException{
    validId(folderId,"folderId");
    File source=new File(path);
    if(!source.isDirectory())
      throw new CloudFilesClient.CloudException(CloudFilesClient.ERROR_INVALID,"目录上传源必须是文件夹");
    if(options==null)options=CloudFilesClient.UploadOptions.defaults();
    synchronized(networkLock){
      requireSession();
      String rootName=safeName(source.getName());
      String rootId=mkdirId(folderId,rootName,"");
      int[] counts=new int[]{0,0};
      int[] iconWarnings=new int[]{0};
      long[] bytes=new long[]{0};
      uploadTree(rootId,source,0,options,counts,bytes,iconWarnings);
      return new CloudFilesClient.DirectoryUploadResult(rootId,source.getName(),counts[0],counts[1],bytes[0],
          iconWarnings[0]==0?"":iconWarnings[0]+" 个文件的图标未设置");
    }
  }

  private void uploadTree(String remoteParent,File directory,int depth,
                          CloudFilesClient.UploadOptions options,int[] counts,long[] bytes,int[] iconWarnings)
      throws CloudFilesClient.CloudException{
    if(depth>MAX_DIRECTORY_DEPTH)
      throw new CloudFilesClient.CloudException(CloudFilesClient.ERROR_INVALID,"目录层级过深");
    File[] children=directory.listFiles();
    if(children==null)
      throw new CloudFilesClient.CloudException(CloudFilesClient.ERROR_INVALID,"无法读取本地目录");
    java.util.Arrays.sort(children,Comparator.comparing(File::getName));
    for(File child:children){
      if(child.isDirectory()){
        try{if(android.system.OsConstants.S_ISLNK(android.system.Os.lstat(child.getAbsolutePath()).st_mode))continue;}catch(android.system.ErrnoException error){throw new CloudFilesClient.CloudException(CloudFilesClient.ERROR_INVALID,"无法读取本地目录",error);}
        String remote=mkdirId(remoteParent,safeName(child.getName()),"");
        counts[1]++;
        uploadTree(remote,child,depth+1,options,counts,bytes,iconWarnings);
      }else if(child.isFile()){
        CloudFilesClient.UploadResult uploaded=uploadPrepared(remoteParent,child.getAbsolutePath(),options);
        counts[0]++;
        bytes[0]+=uploaded.bytesUploaded;
        if(!uploaded.warning.isEmpty())iconWarnings[0]++;
      }
    }
  }

  CloudFilesClient.ReplaceResult replaceApk(CloudFilesClient.CloudItem oldFile,String parentFolderId,
                                            String path,CloudFilesClient.UploadOptions options)
      throws CloudFilesClient.CloudException{
    validId(oldFile.id,"oldFileId");
    if(oldFile.folder||!oldFile.name.toLowerCase(Locale.ROOT).endsWith(".apk"))
      throw new CloudFilesClient.CloudException(CloudFilesClient.ERROR_INVALID,"只能替换 APK 文件");
    File replacement=new File(path);
    if(!replacement.isFile()||!replacement.getName().toLowerCase(Locale.ROOT).endsWith(".apk"))
      throw new CloudFilesClient.CloudException(CloudFilesClient.ERROR_INVALID,"换包文件必须是本地 APK");
    if(options==null)options=CloudFilesClient.UploadOptions.defaults();
    synchronized(networkLock){
      requireSession();
      String folderId=validId(parentFolderId,"parentFolderId");
      boolean listed=false;
      for(CloudFilesClient.CloudItem item:listAll(folderId))
        if(!item.folder&&item.id.equals(oldFile.id)){listed=true;break;}
      if(!listed)
        throw new CloudFilesClient.CloudException(CloudFilesClient.ERROR_NOT_FOUND,
            "待换包文件不在当前文件夹");
      ManagedRecord metadata=managedMetadata(oldFile.id,false);
      CloudFilesClient.UploadResult uploaded=uploadPrepared(folderId,path,
          CloudFilesClient.UploadOptions.defaults().compatibilityWrap(options.compatibilityWrap).kind("file"));
      String newId=uploaded.id;
      if(newId.equals(oldFile.id))
        throw new CloudFilesClient.CloudException(CloudFilesClient.ERROR_PROTOCOL,
            "上传响应错误地返回了旧文件 ID，已阻止换包写入");
      task(map("task","46","file_id",newId,"file_name",fileStem(metadata.name),"type","2"));
      if(!metadata.description.isEmpty())
        task(map("task","11","file_id",newId,"desc",metadata.description));
      boolean iconApplied=false;
      if(!options.iconPath.isEmpty()||options.autoIcon){
        setIcon(newId,options.iconPath.isEmpty()?path:options.iconPath);
        iconApplied=true;
      }
      ManagedRecord confirmed=managedMetadata(newId,false);
      if(!metadata.name.equals(confirmed.name)||!metadata.description.equals(confirmed.description))
        throw new CloudFilesClient.CloudException(CloudFilesClient.ERROR_PROTOCOL,"新文件标题或简介回读不一致，旧文件仍保留");
      deleteRaw(oldFile.id,false);
      boolean stillPresent=false;
      for(CloudFilesClient.CloudItem item:listAll(folderId))
        if(!item.folder&&item.id.equals(oldFile.id)){stillPresent=true;break;}
      if(stillPresent)
        throw new CloudFilesClient.CloudException(CloudFilesClient.ERROR_PROTOCOL,
            "蓝奏云未确认旧文件已移入回收站，新文件 ID "+newId);
      return new CloudFilesClient.ReplaceResult(oldFile.id,newId,folderId,metadata.name,
          uploaded.uploadedName,iconApplied);
    }
  }

  void setIcon(String fileId,String path)throws CloudFilesClient.CloudException{
    validId(fileId,"fileId");
    synchronized(networkLock){
      requireSession();
      File icon=prepareIconFile(path);
      try{
        Map<String,String> binding=map("task","41","file_id",fileId);
        JSONObject initial=taskRawWithCookie(binding,active.cookie);
        BindingInfo info=bindingInfo(initial,false);
        String cookie=mergeCookie(active.cookie,"file_id_ico="+jsEscape(info.token));
        Response policyResponse=getWithCookie(PC_ORIGIN+"/upimg/php/get.php?type=2",cookie,true,PC_ORIGIN+"/");
        JSONObject policy=parseJson(policyResponse.text);
        IconPolicy parsed=parseIconPolicy(policy);
        String extension="png";
        String objectKey=objectKey(parsed.directory,extension);
        Map<String,String> fields=new LinkedHashMap<>();
        fields.put("key",objectKey);
        fields.put("policy",parsed.policy);
        fields.put("OSSAccessKeyId",parsed.accessId);
        fields.put("success_action_status","200");
        fields.put("callback",parsed.callback);
        fields.put("signature",parsed.signature);
        uploadIconObject(parsed.host,fields,icon,"file","image/png");
        try{
          taskWithCookie(map("task","38","img",objectKey,"type","2"),cookie);
        }catch(CloudFilesClient.CloudException error){
          JSONObject readback=taskRawWithCookie(binding,cookie);
          BindingInfo verified;
          try{verified=bindingInfo(readback,true);}catch(CloudFilesClient.CloudException ignored){throw error;}
          if(info.url.equals(verified.url))throw error;
          return;
        }
        JSONObject readback=taskRawWithCookie(binding,cookie);
        bindingInfo(readback,true);
      }finally{
        deleteQuietly(icon);
      }
    }
  }

  private static BindingInfo bindingInfo(JSONObject value,boolean requireIcon)
      throws CloudFilesClient.CloudException{
    long status=valueAsLong(value.opt("zt"),-1);
    if(!(status==0||status==1)||(requireIcon&&status!=1)){
      String message=firstNonEmpty(value.opt("inf") instanceof String?value.optString("inf"):"",
          value.opt("info") instanceof String?value.optString("info"):"",
          value.opt("msgs") instanceof String?value.optString("msgs"):"","图标绑定回读未确认成功");
      throw new CloudFilesClient.CloudException(CloudFilesClient.ERROR_PROTOCOL,sanitize(message));
    }
    JSONArray info=value.optJSONArray("info");
    if(info==null||info.length()==0)
      throw new CloudFilesClient.CloudException(CloudFilesClient.ERROR_PROTOCOL,"图标绑定状态缺少 info");
    String token=scalar(info.opt(0));
    if(token.isEmpty()||token.length()>512||containsControl(token))
      throw new CloudFilesClient.CloudException(CloudFilesClient.ERROR_PROTOCOL,"图标绑定 token 无效");
    String url=info.length()>1?trustedIconUrl(scalar(info.opt(1))):"";
    if(requireIcon&&url.isEmpty())
      throw new CloudFilesClient.CloudException(CloudFilesClient.ERROR_PROTOCOL,"图标绑定回读地址无效");
    return new BindingInfo(token,url);
  }

  private static IconPolicy parseIconPolicy(JSONObject value)throws CloudFilesClient.CloudException{
    for(String key:new String[]{"host","policy","accessid","signature","callback","dir"})
      if(value.optString(key,"").isEmpty())
        throw new CloudFilesClient.CloudException(CloudFilesClient.ERROR_PROTOCOL,"图标上传策略缺少 "+key);
    if(valueAsLong(value.opt("expire"),0)<=0)
      throw new CloudFilesClient.CloudException(CloudFilesClient.ERROR_PROTOCOL,"图标上传策略缺少 expire");
    String directory=value.optString("dir","");
    if(directory.contains("..")||directory.startsWith("/")||directory.startsWith("\\"))
      throw new CloudFilesClient.CloudException(CloudFilesClient.ERROR_PROTOCOL,"图标上传目录无效");
    String host=value.optString("host","");
    String lower=host.toLowerCase(Locale.ROOT);
    if(!lower.startsWith("https://")||!(lower.contains(".aliyuncs.com")||lower.contains("dmpdmp")))
      throw new CloudFilesClient.CloudException(CloudFilesClient.ERROR_PROTOCOL,"图标上传地址不受信任");
    return new IconPolicy(host,value.optString("policy"),value.optString("accessid"),
        value.optString("signature"),value.optString("callback"),directory);
  }

  private static String objectKey(String directory,String extension)throws CloudFilesClient.CloudException{
    if(directory.isEmpty()||directory.length()>2048||containsControl(directory)||directory.contains(".."))
      throw new CloudFilesClient.CloudException(CloudFilesClient.ERROR_PROTOCOL,"图标上传目录无效");
    long seed=System.nanoTime()^(System.currentTimeMillis()<<16);
    int first=(int)(Math.abs(seed)%8)+1;
    int second=(int)(Math.abs(seed/8)%8)+1;
    return directory+first+second+"."+extension;
  }

  private Response get(String url,String cookie,boolean follow)throws CloudFilesClient.CloudException{
    return request("GET",url,cookie,null,null,follow,PC_ORIGIN+"/");
  }

  private Response getWithCookie(String url,String cookie,boolean follow,String referer)
      throws CloudFilesClient.CloudException{
    return request("GET",url,cookie,null,null,follow,referer);
  }

  private Response postForm(String url,String cookie,Map<String,String> form,String referer,boolean follow)
      throws CloudFilesClient.CloudException{
    StringBuilder body=new StringBuilder();
    for(Map.Entry<String,String> entry:form.entrySet()){
      if(body.length()>0)body.append('&');
      body.append(encode(entry.getKey())).append('=').append(encode(entry.getValue()));
    }
    return request("POST",url,cookie,body.toString().getBytes(StandardCharsets.UTF_8),
        "application/x-www-form-urlencoded;charset=UTF-8",follow,referer);
  }

  private Response request(String method,String url,String cookie,byte[] body,String contentType,
                            boolean follow,String referer)throws CloudFilesClient.CloudException{
    String current=url;
    redirects: for(int hop=0;hop<=MAX_REDIRECTS;hop++){
      for(int attempt=0;attempt<3;attempt++){
      HttpURLConnection connection=null;
      try{
        connection=(HttpURLConnection)new URL(current).openConnection();
        connection.setInstanceFollowRedirects(false);
        connection.setConnectTimeout(15_000);
        connection.setReadTimeout(30_000);
        connection.setRequestMethod(method);
        connection.setRequestProperty("User-Agent",userAgent());
        connection.setRequestProperty("Accept","application/json,text/html;q=0.9,*/*;q=0.8");
        connection.setRequestProperty("Referer",referer==null?PC_ORIGIN+"/":referer);
        String header=mergeCookie(cookie,jar.cookiesFor(current));
        if(header!=null&&!header.isEmpty())connection.setRequestProperty("Cookie",header);
        if(body!=null){
          connection.setDoOutput(true);
          connection.setRequestProperty("Content-Type",contentType);
          if(contentType!=null&&contentType.startsWith("application/x-www-form-urlencoded")){
            URL source=new URL(referer==null?PC_ORIGIN+"/":referer);
            connection.setRequestProperty("Origin",source.getProtocol()+"://"+source.getAuthority());
            connection.setRequestProperty("X-Requested-With","XMLHttpRequest");
          }
          connection.setFixedLengthStreamingMode(body.length);
          try(OutputStream output=connection.getOutputStream()){output.write(body);}
        }
        int status=connection.getResponseCode();
        jar.absorb(current,connection.getHeaderFields());
        if(follow&&status>=300&&status<400){
          String location=connection.getHeaderField("Location");
          if(location==null||location.isEmpty())
            throw new CloudFilesClient.CloudException(CloudFilesClient.ERROR_PROTOCOL,"服务器重定向缺少地址");
          current=resolveHttp(current,location);
          continue redirects;
        }
        InputStream input=status>=400?connection.getErrorStream():connection.getInputStream();
        String text=input==null?"":read(input,MAX_HTML_BYTES);
        String challenge=LanzouCore.acwCookie(text);
        if(!challenge.isEmpty()){
          jar.absorbCookie(new URL(current).getHost(),"acw_sc__v2="+challenge+"; Path=/");
          if(attempt<2)continue;
          throw new CloudFilesClient.CloudException(CloudFilesClient.ERROR_PROTOCOL,"蓝奏云 ACW 验证未完成，请稍后重试");
        }
        if(status<200||status>=300)
          throw new CloudFilesClient.CloudException(CloudFilesClient.ERROR_NETWORK,
              "蓝奏云服务返回 HTTP "+status);
        return new Response(status,text,connection.getHeaderField("Location"),current);
      }catch(CloudFilesClient.CloudException error){
        throw error;
      }catch(Exception error){
        throw new CloudFilesClient.CloudException(CloudFilesClient.ERROR_NETWORK,"网络请求失败，请稍后重试",error);
      }finally{
        if(connection!=null)connection.disconnect();
      }
      }
    }
    throw new CloudFilesClient.CloudException(CloudFilesClient.ERROR_PROTOCOL,"重定向次数过多");
  }

  private Response postMultipart(String url,String cookie,Map<String,String> fields,String fileField,
                                 File file,String filename,String mime,String referer)
      throws CloudFilesClient.CloudException{
    String boundary="----LanzouPlus"+UUID.randomUUID().toString().replace("-","");
    HttpURLConnection connection=null;
    try{
      connection=(HttpURLConnection)new URL(url).openConnection();
      connection.setInstanceFollowRedirects(false);
      connection.setConnectTimeout(15_000);
      connection.setReadTimeout(900_000);
      connection.setRequestMethod("POST");
      connection.setDoOutput(true);
      connection.setChunkedStreamingMode(64*1024);
      connection.setRequestProperty("User-Agent",userAgent());
      connection.setRequestProperty("Accept","application/json,text/html;q=0.9,*/*;q=0.8");
      connection.setRequestProperty("Referer",referer);
      connection.setRequestProperty("Content-Type","multipart/form-data; boundary="+boundary);
      String header=cookie==null||cookie.isEmpty()?jar.cookiesFor(url):cookie;
      if(header!=null&&!header.isEmpty())connection.setRequestProperty("Cookie",header);
      try(OutputStream output=connection.getOutputStream()){
        for(Map.Entry<String,String> entry:fields.entrySet()){
          writeAscii(output,"--"+boundary+"\r\n");
          writeUtf8(output,"Content-Disposition: form-data; name=\""+entry.getKey()+"\"\r\n\r\n");
          writeUtf8(output,entry.getValue());
          writeAscii(output,"\r\n");
        }
        writeAscii(output,"--"+boundary+"\r\n");
        writeUtf8(output,"Content-Disposition: form-data; name=\""+fileField
            +"\"; filename=\""+escapeQuoted(filename)+"\"\r\n");
        writeAscii(output,"Content-Type: "+mime+"\r\n\r\n");
        try(InputStream input=new FileInputStream(file)){
          byte[] buffer=new byte[64*1024];
          int read;
          while((read=input.read(buffer))!=-1)output.write(buffer,0,read);
        }
        writeAscii(output,"\r\n--"+boundary+"--\r\n");
      }
      int status=connection.getResponseCode();
      jar.absorb(url,connection.getHeaderFields());
      InputStream input=status>=400?connection.getErrorStream():connection.getInputStream();
      String text=input==null?"":read(input,MAX_HTML_BYTES);
      if(status<200||status>=300)
        throw new CloudFilesClient.CloudException(CloudFilesClient.ERROR_NETWORK,
            "上传服务返回 HTTP "+status);
      return new Response(status,text,connection.getHeaderField("Location"),url);
    }catch(CloudFilesClient.CloudException error){
      throw error;
    }catch(Exception error){
      throw new CloudFilesClient.CloudException(CloudFilesClient.ERROR_NETWORK,"上传连接失败",error);
    }finally{
      if(connection!=null)connection.disconnect();
    }
  }

  private void uploadIconObject(String url,Map<String,String> fields,File file,String field,String mime)
      throws CloudFilesClient.CloudException{
    postMultipart(url,"",fields,field,file,file.getName(),mime,"https://pc.woozooo.com/");
  }

  private long downloadToFile(LanzouCore.DirectLink direct,File destination,String referer)
      throws CloudFilesClient.CloudException{
    String current=direct.url;
    for(int hop=0;hop<=MAX_REDIRECTS;hop++){
      HttpURLConnection connection=null;
      try{
        connection=(HttpURLConnection)new URL(current).openConnection();
        connection.setInstanceFollowRedirects(false);
        connection.setConnectTimeout(15_000);
        connection.setReadTimeout(60_000);
        connection.setRequestProperty("User-Agent",direct.requestUserAgent);
        connection.setRequestProperty("Referer",referer);
        if(direct.cookie!=null&&!direct.cookie.isEmpty())connection.setRequestProperty("Cookie",direct.cookie);
        int status=connection.getResponseCode();
        if(status>=300&&status<400){
          String location=connection.getHeaderField("Location");
          if(location==null||location.isEmpty())
            throw new CloudFilesClient.CloudException(CloudFilesClient.ERROR_PROTOCOL,"下载重定向缺少地址");
          current=resolveHttp(current,location);
          continue;
        }
        if(status<200||status>=300)
          throw new CloudFilesClient.CloudException(CloudFilesClient.ERROR_NETWORK,
              "下载服务返回 HTTP "+status);
        long total=0;
        try(InputStream input=connection.getInputStream();
            OutputStream output=new FileOutputStream(destination)){
          byte[] buffer=new byte[64*1024];
          int read;
          while((read=input.read(buffer))!=-1){output.write(buffer,0,read);total+=read;}
        }
        return total;
      }catch(CloudFilesClient.CloudException error){
        throw error;
      }catch(Exception error){
        throw new CloudFilesClient.CloudException(CloudFilesClient.ERROR_NETWORK,"下载连接失败",error);
      }finally{
        if(connection!=null)connection.disconnect();
      }
    }
    throw new CloudFilesClient.CloudException(CloudFilesClient.ERROR_PROTOCOL,"下载重定向次数过多");
  }

  private PreparedUpload prepareUpload(String path,boolean requestedWrap,String kind)
      throws CloudFilesClient.CloudException{
    File source=new File(path);
    if(!source.isFile())
      throw new CloudFilesClient.CloudException(CloudFilesClient.ERROR_INVALID,"上传路径不是文件");
    String original=safeName(source.getName());
    boolean mustWrap=requestedWrap||!supportedUploadName(original);
    if(!mustWrap)
      return new PreparedUpload(source,original,original,source.length(),false,false);
    File directory=new File(context.getCacheDir(),"upload-wrap");
    if(!directory.isDirectory()&&!directory.mkdirs())
      throw new CloudFilesClient.CloudException(CloudFilesClient.ERROR_NETWORK,"无法创建上传临时目录");
    String remote=sanitizeStem(fileStem(original))+".lzyou.zip";
    File temp=new File(directory,UUID.randomUUID().toString()+"-"+remote);
    try{
      try(InputStream input=new FileInputStream(source);OutputStream output=new FileOutputStream(temp)){
        byte[] buffer=new byte[64*1024];
        int read;
        while((read=input.read(buffer))!=-1)output.write(buffer,0,read);
        byte[] name=original.getBytes(StandardCharsets.UTF_8);
        output.write(name);
        writeLongLe(output,source.length());
        writeIntLe(output,name.length);
        output.write(COMPAT_MAGIC.getBytes(StandardCharsets.US_ASCII));
      }
      return new PreparedUpload(temp,remote,original,temp.length(),true,true);
    }catch(IOException error){
      deleteQuietly(temp);
      throw new CloudFilesClient.CloudException(CloudFilesClient.ERROR_NETWORK,"无法准备兼容上传文件",error);
    }
  }

  private static boolean supportedUploadName(String name){
    int dot=name.lastIndexOf('.');
    String extension=dot<0?"":name.substring(dot+1).toLowerCase(Locale.ROOT);
    return SUPPORTED_EXTENSIONS.contains(extension);
  }

  private static String sanitizeStem(String value){
    StringBuilder out=new StringBuilder();
    for(int index=0;index<value.length()&&out.length()<80;index++){
      char ch=value.charAt(index);
      if(Character.isLetterOrDigit(ch)||ch=='_'||ch=='-')out.append(ch);
    }
    return out.length()==0?"file":out.toString();
  }

  private static UnwrapInfo unwrapInPlace(File file)throws CloudFilesClient.CloudException{
    long total=file.length();
    if(total<20)return new UnwrapInfo(false,null,total);
    try(java.io.RandomAccessFile raf=new java.io.RandomAccessFile(file,"rw")){
      raf.seek(total-20);
      byte[] footer=new byte[20];
      raf.readFully(footer);
      String magic=new String(footer,12,8,StandardCharsets.US_ASCII);
      if(!COMPAT_MAGIC.equals(magic))return new UnwrapInfo(false,null,total);
      long originalLength=readLongLe(footer,0);
      long nameLength=readIntLe(footer,8)&0xffffffffL;
      if(originalLength<0||nameLength<0||originalLength+nameLength+20!=total)
        throw new CloudFilesClient.CloudException(CloudFilesClient.ERROR_PROTOCOL,"兼容包尾校验失败");
      raf.seek(originalLength);
      byte[] nameBytes=new byte[(int)nameLength];
      raf.readFully(nameBytes);
      String original=new String(nameBytes,StandardCharsets.UTF_8);
      safeName(original);
      raf.setLength(originalLength);
      return new UnwrapInfo(true,original,originalLength);
    }catch(CloudFilesClient.CloudException error){
      throw error;
    }catch(Exception error){
      throw new CloudFilesClient.CloudException(CloudFilesClient.ERROR_PROTOCOL,"无法解包兼容文件",error);
    }
  }

  private File prepareIconFile(String path)throws CloudFilesClient.CloudException{
    File source=new File(path);
    if(!source.isFile())
      throw new CloudFilesClient.CloudException(CloudFilesClient.ERROR_INVALID,"图标源文件不存在");
    Bitmap bitmap=null;
    try{
      if(source.getName().toLowerCase(Locale.ROOT).endsWith(".apk"))
        bitmap=extractApkIcon(source);
      else bitmap=decodeBitmap(source);
      if(bitmap==null||bitmap.getWidth()<=0||bitmap.getHeight()<=0)
        throw new CloudFilesClient.CloudException(CloudFilesClient.ERROR_INVALID,"图标文件无法解码");
      File directory=new File(context.getCacheDir(),"icons");
      if(!directory.isDirectory()&&!directory.mkdirs())
        throw new CloudFilesClient.CloudException(CloudFilesClient.ERROR_NETWORK,"无法创建图标临时目录");
      File output=new File(directory,UUID.randomUUID().toString()+".png");
      Bitmap scaled=scaleToIcon(bitmap);
      ByteArrayOutputStream encoded=new ByteArrayOutputStream();
      scaled.compress(Bitmap.CompressFormat.PNG,100,encoded);
      if(scaled!=bitmap)scaled.recycle();
      byte[] bytes=encoded.toByteArray();
      if(bytes.length==0||bytes.length>=100_000)
        throw new CloudFilesClient.CloudException(CloudFilesClient.ERROR_INVALID,"图标必须非空且严格小于 100KB");
      try(OutputStream outputStream=new FileOutputStream(output)){outputStream.write(bytes);}catch(IOException error){throw new CloudFilesClient.CloudException(CloudFilesClient.ERROR_NETWORK,"无法保存图标文件",error);}
      return output;
    }finally{
      if(bitmap!=null)bitmap.recycle();
    }
  }

  private static Bitmap decodeBitmap(File file){
    BitmapFactory.Options bounds=new BitmapFactory.Options();
    bounds.inJustDecodeBounds=true;
    BitmapFactory.decodeFile(file.getAbsolutePath(),bounds);
    if(bounds.outWidth<=0||bounds.outHeight<=0)return null;
    BitmapFactory.Options options=new BitmapFactory.Options();
    int sample=1;
    while(bounds.outWidth/(sample*2)>=144&&bounds.outHeight/(sample*2)>=144)sample*=2;
    options.inSampleSize=sample;
    return BitmapFactory.decodeFile(file.getAbsolutePath(),options);
  }

  private Bitmap extractApkIcon(File apk)throws CloudFilesClient.CloudException{
    try{
      PackageManager manager=context.getPackageManager();
      String path=apk.getAbsolutePath();
      PackageInfo archive=manager.getPackageArchiveInfo(path,0);
      if(archive==null||archive.applicationInfo==null)
        throw new CloudFilesClient.CloudException(CloudFilesClient.ERROR_INVALID,"无法读取 APK manifest");
      if(archive.applicationInfo.icon==0)
        throw new CloudFilesClient.CloudException(CloudFilesClient.ERROR_INVALID,"APK manifest 未声明应用图标");
      archive.applicationInfo.sourceDir=path;
      archive.applicationInfo.publicSourceDir=path;
      Drawable icon=archive.applicationInfo.loadIcon(manager);
      if(icon==null)
        throw new CloudFilesClient.CloudException(CloudFilesClient.ERROR_INVALID,"APK manifest 未声明可用图标");
      int width=icon.getIntrinsicWidth(),height=icon.getIntrinsicHeight();
      if(width<=0||height<=0){width=144;height=144;}
      if(width>144||height>144){
        float ratio=144f/Math.max(width,height);
        width=Math.max(1,Math.round(width*ratio));
        height=Math.max(1,Math.round(height*ratio));
      }
      Bitmap bitmap=Bitmap.createBitmap(width,height,Bitmap.Config.ARGB_8888);
      icon.setBounds(0,0,width,height);
      icon.draw(new Canvas(bitmap));
      return bitmap;
    }catch(CloudFilesClient.CloudException error){
      throw error;
    }catch(Exception error){
      throw new CloudFilesClient.CloudException(CloudFilesClient.ERROR_INVALID,"无法读取 APK 图标",error);
    }
  }

  private static Bitmap scaleToIcon(Bitmap source){
    int width=source.getWidth();
    int height=source.getHeight();
    if(width<=144&&height<=144)return source;
    float ratio=Math.min(144f/width,144f/height);
    int targetWidth=Math.max(1,Math.round(width*ratio));
    int targetHeight=Math.max(1,Math.round(height*ratio));
    return Bitmap.createScaledBitmap(source,targetWidth,targetHeight,true);
  }

  private static String userAgent(){
    return "Mozilla/5.0 (Linux; Android "+android.os.Build.VERSION.RELEASE+"; "
        +android.os.Build.MODEL+") AppleWebKit/537.36 Chrome/138 Mobile Safari/537.36";
  }

  private static JSONObject parseJson(String text)throws CloudFilesClient.CloudException{
    try{
      String value=text==null?"":text.trim();
      if(value.isEmpty())throw new IllegalArgumentException();
      return new JSONObject(value);
    }catch(Exception error){
      throw new CloudFilesClient.CloudException(CloudFilesClient.ERROR_PROTOCOL,
          "蓝奏云返回了无法识别的数据",error);
    }
  }

  private static String uploadFileId(JSONObject value)throws CloudFilesClient.CloudException{
    Object text=value.opt("text");
    Object candidate=null;
    if(text instanceof JSONArray){
      JSONArray array=(JSONArray)text;
      candidate=array.length()==0?null:array.optJSONObject(0)==null?array.opt(0)
          :array.optJSONObject(0).opt("id");
    }else if(text instanceof JSONObject){
      candidate=((JSONObject)text).opt("id");
    }else candidate=text;
    if(candidate==null||candidate==JSONObject.NULL)return "";
    String id=scalar(candidate);
    if(!validNumericId(id))
      throw new CloudFilesClient.CloudException(CloudFilesClient.ERROR_PROTOCOL,"上传响应 id 无效");
    return id;
  }

  private static String read(InputStream input,int maxBytes)throws IOException{
    try(InputStream stream=input;ByteArrayOutputStream output=new ByteArrayOutputStream()){
      byte[] buffer=new byte[8192];
      int total=0,read;
      while((read=stream.read(buffer))!=-1){
        total+=read;
        if(total>maxBytes)throw new IOException("response too large");
        output.write(buffer,0,read);
      }
      return output.toString("UTF-8");
    }
  }

  private static byte[] readBytes(InputStream input,int maxBytes)throws IOException{
    try(InputStream stream=input;ByteArrayOutputStream output=new ByteArrayOutputStream()){
      byte[] buffer=new byte[8192];
      int total=0,read;
      while((read=stream.read(buffer))!=-1){
        total+=read;
        if(total>maxBytes)throw new IOException("response too large");
        output.write(buffer,0,read);
      }
      return output.toByteArray();
    }
  }

  private static void writeAscii(OutputStream output,String value)throws IOException{
    output.write(value.getBytes(StandardCharsets.US_ASCII));
  }

  private static void writeUtf8(OutputStream output,String value)throws IOException{
    output.write(value.getBytes(StandardCharsets.UTF_8));
  }

  private static String escapeQuoted(String value){
    return value.replace("\\","\\\\").replace("\"","\\\"")
        .replace("\r","").replace("\n","");
  }

  private static void writeLongLe(OutputStream output,long value)throws IOException{
    for(int index=0;index<8;index++)output.write((int)((value>>> (index*8))&0xff));
  }

  private static void writeIntLe(OutputStream output,int value)throws IOException{
    for(int index=0;index<4;index++)output.write((value>>>(index*8))&0xff);
  }

  private static long readLongLe(byte[] value,int offset){
    long result=0;
    for(int index=0;index<8;index++)result|=(long)(value[offset+index]&0xff)<<(index*8);
    return result;
  }

  private static int readIntLe(byte[] value,int offset){
    int result=0;
    for(int index=0;index<4;index++)result|=(value[offset+index]&0xff)<<(index*8);
    return result;
  }

  private static Map<String,String> map(String... pairs){
    Map<String,String> out=new LinkedHashMap<>();
    for(int index=0;index+1<pairs.length;index+=2)out.put(pairs[index],pairs[index+1]);
    return out;
  }

  private static String string(JSONObject object,String key){
    if(object==null)return "";
    Object value=object.opt(key);
    return value==null||value==JSONObject.NULL?"":value.toString();
  }

  private static String scalar(Object value){
    if(value==null||value==JSONObject.NULL)return "";
    if(value instanceof String)return ((String)value).trim();
    if(value instanceof Number)return Long.toString(((Number)value).longValue());
    return value.toString().trim();
  }

  private static long valueAsLong(Object value,long fallback){
    if(value==null||value==JSONObject.NULL)return fallback;
    if(value instanceof Number)return ((Number)value).longValue();
    try{return Long.parseLong(value.toString().trim());}catch(Exception ignored){return fallback;}
  }

  private static String firstNonEmpty(String... values){
    if(values!=null)for(String value:values)
      if(value!=null&&!value.trim().isEmpty())return value;
    return "";
  }

  private static String validId(String value,String field)throws CloudFilesClient.CloudException{
    String id=value==null?"":value.trim();
    if(id.isEmpty())throw new CloudFilesClient.CloudException(CloudFilesClient.ERROR_INVALID,field+" 不能为空");
    if(id.length()>64)throw new CloudFilesClient.CloudException(CloudFilesClient.ERROR_INVALID,field+" 过长");
    if(!id.equals("-1")&&!validNumericId(id))
      throw new CloudFilesClient.CloudException(CloudFilesClient.ERROR_INVALID,field+" 必须是数字 ID");
    return id;
  }

  private static boolean validNumericId(String value){
    if(value==null||value.isEmpty())return false;
    for(int index=0;index<value.length();index++){
      char ch=value.charAt(index);
      if(ch<'0'||ch>'9')return false;
    }
    return true;
  }

  private static String required(String value,String field,int max)throws CloudFilesClient.CloudException{
    String text=value==null?"":value.trim();
    if(text.isEmpty())throw new CloudFilesClient.CloudException(CloudFilesClient.ERROR_INVALID,field+" 不能为空");
    if(text.length()>max)throw new CloudFilesClient.CloudException(CloudFilesClient.ERROR_INVALID,field+" 过长");
    if(containsControl(value))
      throw new CloudFilesClient.CloudException(CloudFilesClient.ERROR_INVALID,field+" 包含非法字符");
    return value;
  }

  private static String safeName(String name)throws CloudFilesClient.CloudException{
    required(name,"名称",255);
    if(name.length()>255||name.contains("/")||name.contains("\\")||name.contains(":")
        ||name.contains("*")||name.contains("?")||name.contains("\"")||name.contains("<")
        ||name.contains(">")||name.contains("|")
        ||!name.equals(name.trim())||name.endsWith(".")||name.endsWith(" "))
      throw new CloudFilesClient.CloudException(CloudFilesClient.ERROR_INVALID,"名称不是安全文件名");
    String stem=name.split("\\.",2)[0].toUpperCase(Locale.ROOT);
    if("CON".equals(stem)||"PRN".equals(stem)||"AUX".equals(stem)||"NUL".equals(stem)
        ||"CLOCK$".equals(stem)||"CONIN$".equals(stem)||"CONOUT$".equals(stem)
        ||stem.matches("COM[1-9]")||stem.matches("LPT[1-9]"))
      throw new CloudFilesClient.CloudException(CloudFilesClient.ERROR_INVALID,"名称不是安全文件名");
    return name;
  }

  private static boolean containsControl(String value){
    if(value==null)return false;
    for(int index=0;index<value.length();index++){
      char ch=value.charAt(index);
      if(ch=='\r'||ch=='\n'||ch=='\0'||Character.isISOControl(ch))return true;
    }
    return false;
  }

  private static String encode(String value){
    try{return URLEncoder.encode(value==null?"":value,"UTF-8");}
    catch(Exception impossible){throw new AssertionError(impossible);}
  }

  private static String mergeCookie(String first,String second){
    LinkedHashMap<String,String> values=new LinkedHashMap<>();
    for(String header:new String[]{first,second}){
      if(header==null)continue;
      for(String part:header.split(";")){
        String text=part.trim();
        int equals=text.indexOf('=');
        if(equals<=0)continue;
        values.put(text.substring(0,equals),text.substring(equals+1));
      }
    }
    StringBuilder out=new StringBuilder();
    for(Map.Entry<String,String> entry:values.entrySet()){
      if(out.length()>0)out.append("; ");
      out.append(entry.getKey()).append('=').append(entry.getValue());
    }
    return out.toString();
  }

  private static String lastVariable(String html,String name){
    if(html==null||html.isEmpty())return "";
    Matcher matcher=Pattern.compile("(?:(?:var|let|const)\\s+)?"+Pattern.quote(name)
        +"\\s*=\\s*['\"]?([^;'\"\\r\\n]*)").matcher(html);
    String last="";
    while(matcher.find())if(!matcher.group(1).trim().isEmpty())last=matcher.group(1).trim();
    return last;
  }

  private static String sanitize(String message){
    if(message==null||message.isEmpty())return "操作失败";
    String safe=message.replaceAll("[\\p{Cntrl}&&[^\\r\\n\\t]]"," ").replaceAll("\\s+"," ").trim();
    safe=SHOULD_NOT_SURVIVE.matcher(safe).replaceAll("$1=***");
    safe=LONG_SECRET.matcher(safe).replaceAll("***");
    return safe.length()>160?safe.substring(0,160):safe;
  }

  static String visibleDescription(String value){
    String text=value==null?"":value;
    int newline=text.indexOf('\n');
    String first=newline<0?text:text.substring(0,newline);
    if(first.trim().startsWith(DESCRIPTION_PREFIX))
      return newline<0?"":text.substring(newline+1).trim();
    return text;
  }

  static String preserveFragmentDescription(String current,String visible)
      throws CloudFilesClient.CloudException{
    if(FragmentManifest.parse(current)==null)return visible==null?"":visible;
    String marker=current==null?"":current.split("\\r?\\n",2)[0].trim();
    if(visible==null||visible.trim().isEmpty())return marker;
    return marker+"\n"+visible.trim();
  }

  private static String jsEscape(String value){
    StringBuilder out=new StringBuilder();
    for(int index=0;index<value.length();index++){
      char ch=value.charAt(index);
      if(ch<=0x7f){
        if(Character.isLetterOrDigit(ch)||"@*_+-./".indexOf(ch)>=0)out.append(ch);
        else out.append('%').append(String.format(Locale.ROOT,"%02X",(int)ch));
      }else if(ch<=0xff){
        out.append('%').append(String.format(Locale.ROOT,"%02X",(int)ch));
      }else{
        out.append(String.format(Locale.ROOT,"%%u%04X",(int)ch));
      }
    }
    return out.toString();
  }

  private static File prepareTempDirectory(String path)throws CloudFilesClient.CloudException{
    File directory=new File(path==null?"":path);
    if(path==null||path.trim().isEmpty())
      throw new CloudFilesClient.CloudException(CloudFilesClient.ERROR_INVALID,"请选择复制临时目录");
    if(!directory.isDirectory()&&!directory.mkdirs())
      throw new CloudFilesClient.CloudException(CloudFilesClient.ERROR_NETWORK,"无法创建复制临时目录");
    return directory;
  }

  private static void copyFile(File source,File target,boolean overwrite)throws IOException{
    if(target.exists()&&!overwrite)throw new IOException("destination exists");
    try(InputStream input=new FileInputStream(source);OutputStream output=new FileOutputStream(target)){
      byte[] buffer=new byte[64*1024];
      int read;
      while((read=input.read(buffer))!=-1)output.write(buffer,0,read);
    }
  }

  private static void deleteQuietly(File file){
    if(file==null)return;
    try{if(file.exists()&&file.isFile())file.delete();}catch(Exception ignored){}
  }

  static void deleteTree(File file){
    if(file==null||!file.exists())return;
    try{
      if(file.isDirectory()){
        File[] children=file.listFiles();
        if(children!=null)for(File child:children)deleteTree(child);
      }
      file.delete();
    }catch(Exception ignored){}
  }

  static int binaryWidth(int partCount){
    int natural=Integer.SIZE-Integer.numberOfLeadingZeros(partCount);
    return Math.max(4,natural);
  }

  static String joinLines(List<String> values){
    StringBuilder out=new StringBuilder();
    for(String value:values){
      if(value==null||value.isEmpty())continue;
      if(out.length()>0)out.append('\n');
      out.append(value);
    }
    return out.toString();
  }

  static long sumLengths(List<File> files){
    long total=0;
    for(File file:files)total+=file.length();
    return total;
  }

  static List<File> splitFile(File source,File root,FragmentManifest manifest)
      throws CloudFilesClient.CloudException{
    long total=source.length();
    long chunk=(total+manifest.partCount-1)/manifest.partCount;
    List<File> pieces=new ArrayList<>();
    try(InputStream input=new FileInputStream(source)){
      long remaining=total;
      byte[] buffer=new byte[64*1024];
      for(int index=1;index<=manifest.partCount;index++){
        File piece=new File(root,manifest.uploadFileName(index));
        long target=Math.min(remaining,chunk);
        long written=0;
        try(OutputStream output=new FileOutputStream(piece)){
          while(written<target){
            int wanted=(int)Math.min(buffer.length,target-written);
            int read=input.read(buffer,0,wanted);
            if(read<=0)
              throw new CloudFilesClient.CloudException(CloudFilesClient.ERROR_NETWORK,"源文件在切片过程中提前结束");
            output.write(buffer,0,read);
            written+=read;
          }
        }
        remaining-=written;
        pieces.add(piece);
      }
      if(remaining!=0)
        throw new CloudFilesClient.CloudException(CloudFilesClient.ERROR_NETWORK,"分片未覆盖完整源文件");
      return pieces;
    }catch(CloudFilesClient.CloudException error){
      throw error;
    }catch(Exception error){
      throw new CloudFilesClient.CloudException(CloudFilesClient.ERROR_NETWORK,"无法生成分片文件",error);
    }
  }

  static int archiveKind(File source)throws CloudFilesClient.CloudException{
    try(InputStream input=new FileInputStream(source)){
      byte[] magic=new byte[6];
      int count=input.read(magic);
      if(count>=4&&magic[0]=='P'&&magic[1]=='K'
          &&((magic[2]==3&&magic[3]==4)||(magic[2]==5&&magic[3]==6)||(magic[2]==7&&magic[3]==8)))
        return 1;
      if(count==6&&(magic[0]&0xff)==0x37&&(magic[1]&0xff)==0x7a&&(magic[2]&0xff)==0xbc
          &&(magic[3]&0xff)==0xaf&&(magic[4]&0xff)==0x27&&(magic[5]&0xff)==0x1c)
        return 2;
      return 0;
    }catch(Exception error){
      throw new CloudFilesClient.CloudException(CloudFilesClient.ERROR_NETWORK,"无法读取分片源文件",error);
    }
  }

  static void buildVolumeArchive(File source,File target,boolean archiveInput)
      throws CloudFilesClient.CloudException{
    try{
      if(archiveInput&&archiveKind(source)==2){
        rebuildSevenZip(source,target);
        return;
      }
      try(OutputStream file=new FileOutputStream(target);
        ZipOutputStream zip=new ZipOutputStream(file)){
      byte[] buffer=new byte[64*1024];
      if(archiveInput){
        try(InputStream input=new FileInputStream(source);
            ZipInputStream sourceZip=new ZipInputStream(input)){
          ZipEntry entry;
          while((entry=sourceZip.getNextEntry())!=null){
            String name=entry.getName().replace('\\','/');
            if(!safeArchiveEntry(name))continue;
            ZipEntry out=new ZipEntry(name);
            zip.putNextEntry(out);
            if(!entry.isDirectory()){
              int read;
              while((read=sourceZip.read(buffer))!=-1)zip.write(buffer,0,read);
            }
            zip.closeEntry();
          }
        }
      }else{
        ZipEntry entry=new ZipEntry(safeName(source.getName()));
        zip.putNextEntry(entry);
        try(InputStream input=new FileInputStream(source)){
          int read;
          while((read=input.read(buffer))!=-1)zip.write(buffer,0,read);
        }
        zip.closeEntry();
      }
      }
    }catch(CloudFilesClient.CloudException error){
      throw error;
    }catch(Exception error){
      throw new CloudFilesClient.CloudException(CloudFilesClient.ERROR_NETWORK,"无法生成分卷压缩包",error);
    }
  }

  private static void rebuildSevenZip(File source,File target)throws IOException{
    int memoryKiB=(int)Math.min(Integer.MAX_VALUE,Runtime.getRuntime().maxMemory()/2048L);
    try(RandomAccessFile input=new RandomAccessFile(source,"r");
        SevenZFile seven=SevenZFile.builder().setSeekableByteChannel(input.getChannel())
            .setMaxMemoryLimitKiB(memoryKiB).get();
        RandomAccessFile targetFile=new RandomAccessFile(target,"rw");
        SevenZOutputFile output=new SevenZOutputFile(targetFile.getChannel().truncate(0))){
      output.setContentMethods(Collections.singletonList(
          new SevenZMethodConfiguration(SevenZMethod.LZMA2,new LZMA2Options(1))));
      byte[] buffer=new byte[64*1024];
      SevenZArchiveEntry entry;
      while((entry=seven.getNextEntry())!=null){
        String name=entry.getName();
        if(name!=null)name=name.replace('\\','/');
        if(!safeArchiveEntry(name))throw new IOException("源 7z 包含无效路径");
        SevenZArchiveEntry rebuilt=new SevenZArchiveEntry();
        rebuilt.setName(name);rebuilt.setDirectory(entry.isDirectory());
        output.putArchiveEntry(rebuilt);
        if(!entry.isDirectory()){
          int read;
          while((read=seven.read(buffer))!=-1)output.write(buffer,0,read);
        }
        output.closeArchiveEntry();
      }
    }
  }

  private static boolean safeArchiveEntry(String name){
    return name!=null&&!name.isEmpty()&&!name.startsWith("/")&&!name.contains("../")
        &&!name.contains("/..")&&!name.contains(":")&&!name.contains("\\");
  }

  private static final Set<String> SUPPORTED_EXTENSIONS=new HashSet<>(java.util.Arrays.asList(
      "ppt","xapk","ke","azw","cpk","gho","dwg","db","docx","deb","e","ttf","xls","bat","crx",
      "rpm","txf","pdf","apk","ipa","txt","mobi","osk","dmg","rp","osz","jar","ttc","z","w3x",
      "xlsx","cetrainer","ct","rar","mp3","pptx","mobileconfig","epub","imazingapp","doc","iso",
      "img","appimage","7z","rplib","lolgezi","exe","azw3","zip","conf","tar","dll","flac","xpa",
      "lua","cad","hwt","accdb","ce","xmind","enc","bds","bdi","ssf","it","gz"));

  private static final class Credentials {
    final String loginName;
    final String password;
    final String cookie;
    final String uid;
    final String vei;

    Credentials(String loginName,String password,String cookie,String uid,String vei){
      this.loginName=loginName;
      this.password=password;
      this.cookie=cookie;
      this.uid=uid;
      this.vei=vei;
    }
  }

  private static final class Vault {
    final LinkedHashMap<String,Credentials> accounts=new LinkedHashMap<>();
    String current="";
  }

  private static final class SessionEntry {
    final boolean authenticated;
    final String userId;
    final long verifiedAt;

    SessionEntry(boolean authenticated,String userId,long verifiedAt){
      this.authenticated=authenticated;
      this.userId=userId;
      this.verifiedAt=verifiedAt;
    }
  }

  private static final class Session {
    final String loginName;
    final String account;
    final String password;
    String cookie;
    String uid;
    String vei;

    Session(String loginName,String account,String password,String cookie,String uid,String vei){
      this.loginName=loginName;
      this.account=account;
      this.password=password;
      this.cookie=cookie==null?"":cookie;
      this.uid=uid==null?"":uid;
      this.vei=vei==null?"":vei;
    }
  }

  private static final class Response {
    final int status;
    final String text;
    final String location;
    final String url;

    Response(int status,String text,String location,String url){
      this.status=status;
      this.text=text==null?"":text;
      this.location=location;
      this.url=url;
    }
  }

  private static final class CookieJar {
    private final Map<String,LinkedHashMap<String,String>> values=new HashMap<>();

    void absorb(String url,Map<String,List<String>> headers){
      if(url==null||headers==null)return;
      String host=hostOf(url);
      if(host.isEmpty())return;
      for(Map.Entry<String,List<String>> header:headers.entrySet()){
        if(header.getKey()==null||!"set-cookie".equalsIgnoreCase(header.getKey()))continue;
        List<String> lines=header.getValue();
        if(lines==null)continue;
        for(String line:lines)absorbCookie(host,line);
      }
    }

    private void absorbCookie(String requestHost,String line){
      if(line==null||line.isEmpty())return;
      String[] parts=line.split(";");
      if(parts.length==0)return;
      int equals=parts[0].indexOf('=');
      if(equals<=0)return;
      String name=parts[0].substring(0,equals).trim();
      String value=parts[0].substring(equals+1).trim();
      if(name.isEmpty())return;
      String domain=requestHost;
      boolean expired=false;
      for(int index=1;index<parts.length;index++){
        String attribute=parts[index].trim();
        int separator=attribute.indexOf('=');
        String key=separator<0?attribute:attribute.substring(0,separator).trim();
        String data=separator<0?"":attribute.substring(separator+1).trim();
        if("domain".equalsIgnoreCase(key)&&!data.isEmpty())domain=data.replaceFirst("^\\.","").toLowerCase(Locale.ROOT);
        if("max-age".equalsIgnoreCase(key)){
          try{if(Long.parseLong(data)<=0)expired=true;}catch(Exception ignored){}
        }
      }
      LinkedHashMap<String,String> bucket=values.get(domain);
      if(bucket==null){bucket=new LinkedHashMap<>();values.put(domain,bucket);}
      if(expired)bucket.remove(name);else bucket.put(name,value);
    }

    String cookiesFor(String url){
      String host=hostOf(url);
      if(host.isEmpty())return "";
      LinkedHashMap<String,String> collected=new LinkedHashMap<>();
      String current=host;
      while(!current.isEmpty()){
        LinkedHashMap<String,String> bucket=values.get(current);
        if(bucket!=null)for(Map.Entry<String,String> entry:bucket.entrySet())
          if(!collected.containsKey(entry.getKey()))collected.put(entry.getKey(),entry.getValue());
        int dot=current.indexOf('.');
        if(dot<0)break;
        current=current.substring(dot+1);
      }
      StringBuilder out=new StringBuilder();
      for(Map.Entry<String,String> entry:collected.entrySet()){
        if(out.length()>0)out.append("; ");
        out.append(entry.getKey()).append('=').append(entry.getValue());
      }
      return out.toString();
    }

    String mergedCookies(String... urls){
      StringBuilder merged=new StringBuilder();
      for(String url:urls)merged.append(';').append(cookiesFor(url));
      return mergeCookie("",merged.toString());
    }

    void reset(String header){
      clear();
      if(header==null||header.trim().isEmpty())return;
      for(String domain:new String[]{"pc.woozooo.com","up.woozooo.com","accounts.woozooo.com"}){
        LinkedHashMap<String,String> bucket=new LinkedHashMap<>();
        for(String pair:header.split(";")){
          String text=pair.trim();
          int equals=text.indexOf('=');
          if(equals>0)bucket.put(text.substring(0,equals),text.substring(equals+1));
        }
        if(!bucket.isEmpty())values.put(domain,bucket);
      }
    }

    void clear(){values.clear();}

    private static String hostOf(String url){
      try{
        String host=new URI(url).getHost();
        return host==null?"":host.toLowerCase(Locale.ROOT);
      }catch(Exception ignored){return "";}
    }
  }

  private static final class ShareRecord {
    final String name;
    final String url;
    final String password;
    final String description;

    ShareRecord(String name,String url,String password,String description){
      this.name=name==null?"":name;
      this.url=url==null?"":url;
      this.password=password==null?"":password;
      this.description=description==null?"":description;
    }
  }

  private static final class ManagedRecord {
    final String name;
    final String description;

    ManagedRecord(String name,String description){
      this.name=name==null?"":name;
      this.description=description==null?"":description;
    }
  }

  private static final class PreparedUpload {
    final File file;
    final String remoteName;
    final String originalName;
    final long uploadBytes;
    final boolean wrapped;
    final boolean temporary;

    PreparedUpload(File file,String remoteName,String originalName,long uploadBytes,
                   boolean wrapped,boolean temporary){
      this.file=file;
      this.remoteName=remoteName;
      this.originalName=originalName;
      this.uploadBytes=uploadBytes;
      this.wrapped=wrapped;
      this.temporary=temporary;
    }
  }

  private static final class UnwrapInfo {
    final boolean unwrapped;
    final String originalName;
    final long bytes;

    UnwrapInfo(boolean unwrapped,String originalName,long bytes){
      this.unwrapped=unwrapped;
      this.originalName=originalName;
      this.bytes=bytes;
    }
  }

  private static final class BindingInfo {
    final String token;
    final String url;

    BindingInfo(String token,String url){
      this.token=token;
      this.url=url;
    }
  }

  private static final class IconPolicy {
    final String host;
    final String policy;
    final String accessId;
    final String signature;
    final String callback;
    final String directory;

    IconPolicy(String host,String policy,String accessId,String signature,String callback,String directory){
      this.host=host;
      this.policy=policy;
      this.accessId=accessId;
      this.signature=signature;
      this.callback=callback;
      this.directory=directory;
    }
  }

  private static final class FragmentSnapshot {
    final String links;

    FragmentSnapshot(String links){this.links=links;}
  }

  static final class FragmentManifest {
    final String mode;
    final String originalName;
    final long originalBytes;
    final int partCount;
    final int binaryWidth;
    final boolean archiveInput;

    FragmentManifest(String mode,String originalName,long originalBytes,int partCount,
                     int binaryWidth,boolean archiveInput){
      this.mode="archive_volume".equals(mode)?"archive_volume":"byte_split";
      this.originalName=originalName;
      this.originalBytes=originalBytes;
      this.partCount=partCount;
      this.binaryWidth=binaryWidth;
      this.archiveInput=archiveInput;
    }

    static FragmentManifest parse(String description)throws CloudFilesClient.CloudException{
      if(description==null||description.isEmpty())return null;
      String first=description.split("\\r?\\n",2)[0].trim();
      if(!first.startsWith(DESCRIPTION_PREFIX))return null;
      try{
        String encoded=first.substring(DESCRIPTION_PREFIX.length());
        byte[] bytes=Base64.decode(encoded,Base64.URL_SAFE|Base64.NO_WRAP|Base64.NO_PADDING);
        JSONObject object=new JSONObject(new String(bytes,StandardCharsets.UTF_8));
        int version=object.optInt("version",0);
        if(version!=1)
          throw new CloudFilesClient.CloudException(CloudFilesClient.ERROR_UNSUPPORTED,
              "该分片协议版本暂不受支持");
        String name=object.optString("originalName","");
        safeName(name);
        long originalBytes=object.optLong("originalBytes",0);
        int parts=object.optInt("partCount",0);
        int width=object.optInt("binaryWidth",0);
        if(originalBytes<=0||parts<2||width!=binaryWidth(parts))
          throw new CloudFilesClient.CloudException(CloudFilesClient.ERROR_PROTOCOL,"分片清单参数无效");
        String mode=object.optString("mode","");
        if(!"byte_split".equals(mode)&&!"archive_volume".equals(mode))
          throw new CloudFilesClient.CloudException(CloudFilesClient.ERROR_PROTOCOL,"分片模式无效");
        return new FragmentManifest(mode,name,originalBytes,parts,width,
            object.optBoolean("archiveInput",false));
      }catch(CloudFilesClient.CloudException error){throw error;
      }catch(Exception error){
        throw new CloudFilesClient.CloudException(CloudFilesClient.ERROR_PROTOCOL,"分片目录标记损坏",error);
      }
    }

    String descriptionMarker()throws CloudFilesClient.CloudException{
      try{
        JSONObject object=new JSONObject()
            .put("version",1)
            .put("mode",mode)
            .put("originalName",originalName)
            .put("originalBytes",originalBytes)
            .put("partCount",partCount)
            .put("binaryWidth",binaryWidth)
            .put("archiveInput",archiveInput);
        return DESCRIPTION_PREFIX+Base64.encodeToString(
            object.toString().getBytes(StandardCharsets.UTF_8),
            Base64.URL_SAFE|Base64.NO_WRAP|Base64.NO_PADDING);
      }catch(Exception error){
        throw new CloudFilesClient.CloudException(CloudFilesClient.ERROR_PROTOCOL,"分片描述序列化失败",error);
      }
    }

    String binaryIndex(int index)throws CloudFilesClient.CloudException{
      if(index<=0||index>partCount)
        throw new CloudFilesClient.CloudException(CloudFilesClient.ERROR_INVALID,"分片序号超出范围");
      StringBuilder bits=new StringBuilder(Integer.toBinaryString(index));
      while(bits.length()<binaryWidth)bits.insert(0,'0');
      return bits.toString();
    }

    String uploadFileName(int index)throws CloudFilesClient.CloudException{
      String bits=binaryIndex(index);
      int dot=originalName.lastIndexOf('.');
      String suffix=dot>=0&&dot<originalName.length()-1?originalName.substring(dot+1):"bin";
      return bits+"."+suffix+".apk";
    }

    String partUri(int index,String shareUrl)throws CloudFilesClient.CloudException{
      String bits=binaryIndex(index);
      StringBuilder total=new StringBuilder(Integer.toBinaryString(partCount));
      while(total.length()<binaryWidth)total.insert(0,'0');
      String name=Base64.encodeToString(originalName.getBytes(StandardCharsets.UTF_8),
          Base64.URL_SAFE|Base64.NO_WRAP|Base64.NO_PADDING);
      String code="archive_volume".equals(mode)?"z":"b";
      return "part:v1:"+code+":"+(archiveInput?"a":"f")+":"+bits+":"+total+":"+originalBytes
          +":"+name+":"+shareUrl;
    }
  }

  private FragmentSnapshot fragmentShareInfo(String folderId,FragmentManifest manifest)
      throws CloudFilesClient.CloudException{
    Map<Integer,String> childIds=new LinkedHashMap<>();
    for(CloudFilesClient.CloudItem item:listAll(folderId)){
      if(!item.folder)continue;
      try{
        int index=Integer.parseInt(item.name);
        if(index>0&&index<=manifest.partCount){
          if(childIds.put(index,item.id)!=null)
            throw new CloudFilesClient.CloudException(CloudFilesClient.ERROR_PROTOCOL,
                "分片目录存在重复数字子目录");
        }
      }catch(NumberFormatException ignored){}
    }
    if(childIds.size()!=manifest.partCount)
      throw new CloudFilesClient.CloudException(CloudFilesClient.ERROR_PROTOCOL,"分片目录缺少数字子目录");
    List<String> links=new ArrayList<>();
    for(int index=1;index<=manifest.partCount;index++){
      String child=childIds.get(index);
      String expected=manifest.uploadFileName(index);
      String fileId="";
      for(CloudFilesClient.CloudItem item:listAll(child))
        if(!item.folder&&expected.equals(item.name)){fileId=item.id;break;}
      if(fileId.isEmpty())
        throw new CloudFilesClient.CloudException(CloudFilesClient.ERROR_PROTOCOL,
            "分片子目录 "+index+" 中未找到 "+expected);
      ShareRecord record=shareRecord(fileId,false);
      links.add(manifest.partUri(index,record.url));
    }
    return new FragmentSnapshot(joinLines(links));
  }

}

