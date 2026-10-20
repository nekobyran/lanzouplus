package cc.nkbr.lanzouplus;

import android.content.Context;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Unified, synchronous file-management API for both LanZou account families.
 *
 * <p>The ordinary service uses the pc/up woozooo web session; the premium
 * service uses the ilanzou app contract. Call every network method from a
 * worker thread. Credentials stay inside the encrypted account vaults owned by
 * the two backends.</p>
 */
final class CloudFilesClient {
  static final int ERROR_CREDENTIALS_REQUIRED=-10001;
  static final int ERROR_CREDENTIALS_UNREADABLE=-10002;
  static final int ERROR_NETWORK=-10003;
  static final int ERROR_PROTOCOL=-10004;
  static final int ERROR_NOT_FOUND=-10005;
  static final int ERROR_INVALID=-10006;
  static final int ERROR_UNSUPPORTED=-10007;
  static final int ERROR_SESSION_EXPIRED=-2;

  private static volatile CloudFilesClient shared;

  enum Provider { ORDINARY, PREMIUM }

  static final class CloudException extends Exception {
    private final int code;
    private final boolean credentialsRequired;

    CloudException(int code,String message){
      super(message==null||message.isEmpty()?"操作失败":message);
      this.code=code;
      credentialsRequired=code==ERROR_CREDENTIALS_REQUIRED||code==ERROR_CREDENTIALS_UNREADABLE;
    }

    CloudException(int code,String message,Throwable ignored){
      this(code,message);
    }

    int code(){return code;}
    boolean needsCredentials(){return credentialsRequired;}
    boolean sessionExpired(){return code==ERROR_SESSION_EXPIRED;}
  }

  static final class CloudItem {
    final String id;
    final String name;
    final boolean folder;
    final long size;
    final String modifiedAt;
    final String description;
    final boolean hasPassword;
    final long downloads;
    final String path;
    final boolean hasCustomIcon;
    final String iconUrl;

    CloudItem(String id,String name,boolean folder,long size,String modifiedAt,String description,
              boolean hasPassword,long downloads,String path,boolean hasCustomIcon,String iconUrl){
      this.id=id==null?"":id;
      this.name=name==null?"":name;
      this.folder=folder;
      this.size=Math.max(0,size);
      this.modifiedAt=modifiedAt==null?"":modifiedAt;
      this.description=description==null?"":description;
      this.hasPassword=hasPassword;
      this.downloads=Math.max(0,downloads);
      this.path=path==null?"":path;
      this.hasCustomIcon=hasCustomIcon;
      this.iconUrl=iconUrl==null?"":iconUrl;
    }

    static CloudItem folder(String id,String name){
      return new CloudItem(id,name,true,0,"","",false,0,"",false,"");
    }

    static CloudItem file(String id,String name){
      return new CloudItem(id,name,false,0,"","",false,0,"",false,"");
    }

    CloudItem withDetails(long size,String modifiedAt,String description,boolean hasPassword,
                          long downloads,String path,boolean hasCustomIcon,String iconUrl){
      return new CloudItem(id,name,folder,size,modifiedAt,description,hasPassword,downloads,path,
          hasCustomIcon,iconUrl);
    }

    CloudItem withDescription(String value){
      return new CloudItem(id,name,folder,size,modifiedAt,value,hasPassword,downloads,path,
          hasCustomIcon,iconUrl);
    }

    CloudItem withPath(String value){
      return new CloudItem(id,name,folder,size,modifiedAt,description,hasPassword,downloads,value,
          hasCustomIcon,iconUrl);
    }
  }

  static final class CloudPage {
    final List<CloudItem> items;
    final int page;
    final boolean hasMore;
    final int total;

    CloudPage(List<CloudItem> items,int page,boolean hasMore){
      this(items,page,hasMore,items==null?0:items.size());
    }

    CloudPage(List<CloudItem> items,int page,boolean hasMore,int total){
      this.items=Collections.unmodifiableList(new ArrayList<>(items==null?Collections.emptyList():items));
      this.page=page;
      this.hasMore=hasMore;
      this.total=Math.max(0,total);
    }
  }

  static final class SessionInfo {
    final boolean authenticated;
    final String userId;

    SessionInfo(boolean authenticated,String userId){
      this.authenticated=authenticated;
      this.userId=userId==null?"":userId;
    }
  }

  static final class LoginState {
    final boolean configured;
    final boolean loggedIn;
    final boolean premium;
    final String userId;

    LoginState(boolean configured,boolean loggedIn,boolean premium){
      this(configured,loggedIn,premium,"");
    }

    LoginState(boolean configured,boolean loggedIn,boolean premium,String userId){
      this.configured=configured;
      this.loggedIn=loggedIn;
      this.premium=premium;
      this.userId=userId==null?"":userId;
    }
  }

  static final class ShareInfo {
    final String id;
    final boolean folder;
    final String name;
    final String shareUrl;
    final String password;
    final String description;
    final String directUrl;
    final String resourceUrl;
    final boolean fragmented;
    final String fragmentMode;
    final int fragmentPartCount;
    final String fragmentExternalLinks;

    ShareInfo(String id,boolean folder,String name,String shareUrl,String password,String description,
              String directUrl,String resourceUrl,boolean fragmented,String fragmentMode,
              int fragmentPartCount,String fragmentExternalLinks){
      this.id=id==null?"":id;
      this.folder=folder;
      this.name=name==null?"":name;
      this.shareUrl=shareUrl==null?"":shareUrl;
      this.password=password==null?"":password;
      this.description=description==null?"":description;
      this.directUrl=directUrl==null?"":directUrl;
      this.resourceUrl=resourceUrl==null?"":resourceUrl;
      this.fragmented=fragmented;
      this.fragmentMode=fragmentMode==null?"":fragmentMode;
      this.fragmentPartCount=Math.max(0,fragmentPartCount);
      this.fragmentExternalLinks=fragmentExternalLinks==null?"":fragmentExternalLinks;
    }
  }

  static final class ManagedDescription {
    final String id;
    final boolean folder;
    final String name;
    final String description;

    ManagedDescription(String id,boolean folder,String name,String description){
      this.id=id==null?"":id;
      this.folder=folder;
      this.name=name==null?"":name;
      this.description=description==null?"":description;
    }
  }

  static final class UploadResult {
    final String id;
    final String folderId;
    final String originalName;
    final String uploadedName;
    final long bytesUploaded;
    final boolean compatibilityWrapped;
    final String fragmentMode;
    final int fragmentPartCount;
    final int fragmentBinaryWidth;
    final String fragmentExternalLinks;

    UploadResult(String id,String folderId,String originalName,String uploadedName,long bytesUploaded,
                 boolean compatibilityWrapped){
      this(id,folderId,originalName,uploadedName,bytesUploaded,compatibilityWrapped,"",0,0,"");
    }

    UploadResult(String id,String folderId,String originalName,String uploadedName,long bytesUploaded,
                 boolean compatibilityWrapped,String fragmentMode,int fragmentPartCount,
                 int fragmentBinaryWidth,String fragmentExternalLinks){
      this.id=id==null?"":id;
      this.folderId=folderId==null?"":folderId;
      this.originalName=originalName==null?"":originalName;
      this.uploadedName=uploadedName==null?"":uploadedName;
      this.bytesUploaded=Math.max(0,bytesUploaded);
      this.compatibilityWrapped=compatibilityWrapped;
      this.fragmentMode=fragmentMode==null?"":fragmentMode;
      this.fragmentPartCount=Math.max(0,fragmentPartCount);
      this.fragmentBinaryWidth=Math.max(0,fragmentBinaryWidth);
      this.fragmentExternalLinks=fragmentExternalLinks==null?"":fragmentExternalLinks;
    }
  }

  static final class DirectoryUploadResult {
    final String rootFolderId;
    final String sourceName;
    final int filesUploaded;
    final int foldersCreated;
    final long bytesUploaded;

    DirectoryUploadResult(String rootFolderId,String sourceName,int filesUploaded,int foldersCreated,
                          long bytesUploaded){
      this.rootFolderId=rootFolderId==null?"":rootFolderId;
      this.sourceName=sourceName==null?"":sourceName;
      this.filesUploaded=Math.max(0,filesUploaded);
      this.foldersCreated=Math.max(0,foldersCreated);
      this.bytesUploaded=Math.max(0,bytesUploaded);
    }
  }

  static final class CopyResult {
    final String id;
    final String createdId;
    final String targetId;
    final int filesCopied;
    final int foldersCopied;
    final long bytesCopied;

    CopyResult(String id,String createdId,String targetId,int filesCopied,int foldersCopied,long bytesCopied){
      this.id=id==null?"":id;
      this.createdId=createdId==null?"":createdId;
      this.targetId=targetId==null?"":targetId;
      this.filesCopied=Math.max(0,filesCopied);
      this.foldersCopied=Math.max(0,foldersCopied);
      this.bytesCopied=Math.max(0,bytesCopied);
    }
  }

  static final class DownloadResult {
    final String id;
    final String path;
    final long bytesDownloaded;
    final boolean compatibilityUnwrapped;
    final String originalName;

    DownloadResult(String id,String path,long bytesDownloaded,boolean compatibilityUnwrapped,String originalName){
      this.id=id==null?"":id;
      this.path=path==null?"":path;
      this.bytesDownloaded=Math.max(0,bytesDownloaded);
      this.compatibilityUnwrapped=compatibilityUnwrapped;
      this.originalName=originalName==null?"":originalName;
    }
  }

  static final class ReplaceResult {
    final String oldFileId;
    final String newFileId;
    final String folderId;
    final String name;
    final String uploadedName;
    final boolean iconApplied;

    ReplaceResult(String oldFileId,String newFileId,String folderId,String name,String uploadedName,boolean iconApplied){
      this.oldFileId=oldFileId==null?"":oldFileId;
      this.newFileId=newFileId==null?"":newFileId;
      this.folderId=folderId==null?"":folderId;
      this.name=name==null?"":name;
      this.uploadedName=uploadedName==null?"":uploadedName;
      this.iconApplied=iconApplied;
    }
  }

  static final class UploadOptions {
    boolean compatibilityWrap;
    String kind="auto";
    boolean autoIcon;
    String iconPath="";
    String fragmentMode="byte_split";
    int partCount=2;

    static UploadOptions defaults(){return new UploadOptions();}

    UploadOptions compatibilityWrap(boolean value){compatibilityWrap=value;return this;}
    UploadOptions kind(String value){kind=value==null||value.isEmpty()?"auto":value;return this;}
    UploadOptions autoIcon(boolean value){autoIcon=value;return this;}
    UploadOptions iconPath(String value){iconPath=value==null?"":value;return this;}
    UploadOptions fragment(String mode,int parts){
      fragmentMode=mode==null||mode.isEmpty()?"byte_split":mode;
      partCount=parts;
      return this;
    }
  }

  private final OrdinaryCloudClient ordinary;
  private final PremiumCloudClient premium;
  private volatile Provider selection=Provider.ORDINARY;

  CloudFilesClient(Context context){
    Context application=context.getApplicationContext();
    ordinary=new OrdinaryCloudClient(application);
    premium=new PremiumCloudClient(application);
  }

  static CloudFilesClient shared(Context context){
    CloudFilesClient current=shared;
    if(current==null){
      synchronized(CloudFilesClient.class){
        current=shared;
        if(current==null){current=new CloudFilesClient(context);shared=current;}
      }
    }
    return current;
  }

  Provider provider(){return selection;}

  void selectProvider(Provider provider){
    if(provider!=null)selection=provider;
  }

  boolean hasStoredLogin(Provider provider){
    try{
      if(provider==Provider.PREMIUM)return premium.hasStoredLogin();
      return ordinary.hasStoredLogin();
    }catch(Exception ignored){
      return false;
    }
  }

  List<String> accountNames(Provider provider)throws CloudException{
    try{
      return provider==Provider.PREMIUM?premium.accountNames():ordinary.accountNames();
    }catch(PremiumCloudClient.CloudException error){
      throw translate(error);
    }
  }

  String currentAccountName(Provider provider)throws CloudException{
    try{
      return provider==Provider.PREMIUM?premium.currentAccountName():ordinary.currentAccountName();
    }catch(PremiumCloudClient.CloudException error){
      throw translate(error);
    }
  }

  LoginState login(Provider provider,String account,String password)throws CloudException{
    if(provider==Provider.PREMIUM){
      try{
        PremiumCloudClient.LoginState state=premium.loginAndRemember(account,password);
        return new LoginState(state.configured,state.loggedIn,state.premium,state.userId);
      }catch(PremiumCloudClient.CloudException error){
        throw translate(error);
      }
    }
    OrdinaryCloudClient.LoginState state=ordinary.loginAndRemember(account,password);
    return new LoginState(state.configured,state.loggedIn,false,state.userId);
  }

  boolean selectAccount(Provider provider,String account)throws CloudException{
    try{
      return provider==Provider.PREMIUM?premium.selectAccount(account):ordinary.selectAccount(account);
    }catch(PremiumCloudClient.CloudException error){
      throw translate(error);
    }
  }

  boolean removeAccount(Provider provider,String account)throws CloudException{
    try{
      return provider==Provider.PREMIUM?premium.removeAccount(account):ordinary.removeAccount(account);
    }catch(PremiumCloudClient.CloudException error){
      throw translate(error);
    }
  }

  void logout(Provider provider)throws CloudException{
    if(provider==Provider.PREMIUM){premium.clearLogin();return;}
    ordinary.clearLogin();
  }

  SessionInfo sessionInfo()throws CloudException{
    try{
      if(selection==Provider.PREMIUM){
        PremiumCloudClient.LoginState state=premium.queryLoginState();
        return new SessionInfo(state.loggedIn,state.userId);
      }
      OrdinaryCloudClient.LoginState state=ordinary.queryLoginState();
      return new SessionInfo(state.loggedIn,state.userId);
    }catch(PremiumCloudClient.CloudException error){
      throw translate(error);
    }
  }

  CloudPage listFolder(String folderId,int page,int pageSize)throws CloudException{
    try{
      return selection==Provider.PREMIUM
          ?premium.cloudListFolder(folderId,page,pageSize)
          :ordinary.listFolder(folderId,page,pageSize);
    }catch(PremiumCloudClient.CloudException error){
      throw translate(error);
    }
  }

  List<CloudItem> moveTargets()throws CloudException{
    try{
      return selection==Provider.PREMIUM?premium.cloudMoveTargets():ordinary.moveTargets();
    }catch(PremiumCloudClient.CloudException error){
      throw translate(error);
    }
  }

  String createFolder(String parentId,String name,String description)throws CloudException{
    try{
      return selection==Provider.PREMIUM
          ?premium.cloudCreateFolder(parentId,name,description)
          :ordinary.createFolder(parentId,name,description);
    }catch(PremiumCloudClient.CloudException error){
      throw translate(error);
    }
  }

  void rename(CloudItem item,String name)throws CloudException{
    checkItem(item);
    try{
      if(selection==Provider.PREMIUM)premium.cloudRename(item,name);
      else ordinary.rename(item,name);
    }catch(PremiumCloudClient.CloudException error){
      throw translate(error);
    }
  }

  void move(CloudItem item,String targetId)throws CloudException{
    checkItem(item);
    try{
      if(selection==Provider.PREMIUM)premium.cloudMove(item,targetId);
      else ordinary.move(item,targetId);
    }catch(PremiumCloudClient.CloudException error){
      throw translate(error);
    }
  }

  CopyResult copy(CloudItem item,String targetId,String tempDirectory)throws CloudException{
    checkItem(item);
    try{
      return selection==Provider.PREMIUM
          ?premium.cloudCopy(item,targetId,tempDirectory)
          :ordinary.copy(item,targetId,tempDirectory);
    }catch(PremiumCloudClient.CloudException error){
      throw translate(error);
    }
  }

  void delete(CloudItem item)throws CloudException{
    checkItem(item);
    try{
      if(selection==Provider.PREMIUM)premium.cloudDelete(item);
      else ordinary.delete(item);
    }catch(PremiumCloudClient.CloudException error){
      throw translate(error);
    }
  }

  void moveMany(List<CloudItem> items,String targetId)throws CloudException{
    checkItems(items);
    try{
      if(selection==Provider.PREMIUM)premium.cloudMoveMany(items,targetId);
      else ordinary.moveMany(items,targetId);
    }catch(PremiumCloudClient.CloudException error){
      throw translate(error);
    }
  }

  void deleteMany(List<CloudItem> items)throws CloudException{
    checkItems(items);
    try{
      if(selection==Provider.PREMIUM)premium.cloudDeleteMany(items);
      else ordinary.deleteMany(items);
    }catch(PremiumCloudClient.CloudException error){
      throw translate(error);
    }
  }

  ShareInfo shareInfo(CloudItem item)throws CloudException{
    checkItem(item);
    try{
      return selection==Provider.PREMIUM?premium.cloudShareInfo(item):ordinary.shareInfo(item);
    }catch(PremiumCloudClient.CloudException error){
      throw translate(error);
    }
  }

  ManagedDescription getDescription(CloudItem item)throws CloudException{
    checkItem(item);
    try{
      return selection==Provider.PREMIUM?premium.cloudDescription(item):ordinary.getDescription(item);
    }catch(PremiumCloudClient.CloudException error){
      throw translate(error);
    }
  }

  void setPassword(CloudItem item,String password)throws CloudException{
    checkItem(item);
    try{
      if(selection==Provider.PREMIUM)premium.cloudSetPassword(item,password);
      else ordinary.setPassword(item,password);
    }catch(PremiumCloudClient.CloudException error){
      throw translate(error);
    }
  }

  void setDescription(CloudItem item,String description)throws CloudException{
    checkItem(item);
    try{
      if(selection==Provider.PREMIUM)premium.cloudSetDescription(item,description);
      else ordinary.setDescription(item,description);
    }catch(PremiumCloudClient.CloudException error){
      throw translate(error);
    }
  }

  UploadResult upload(String folderId,String path,UploadOptions options)throws CloudException{
    try{
      UploadOptions use=options==null?UploadOptions.defaults():options;
      return selection==Provider.PREMIUM
          ?premium.cloudUpload(folderId,path,use)
          :ordinary.upload(folderId,path,use);
    }catch(PremiumCloudClient.CloudException error){
      throw translate(error);
    }
  }

  UploadResult uploadFragmented(String folderId,String path,UploadOptions options)throws CloudException{
    UploadOptions use=options==null?UploadOptions.defaults():options;
    if(selection==Provider.PREMIUM)return upload(folderId,path,use);
    return ordinary.uploadFragmented(folderId,path,use);
  }

  DirectoryUploadResult uploadDirectory(String folderId,String path,UploadOptions options)throws CloudException{
    try{
      UploadOptions use=options==null?UploadOptions.defaults():options;
      return selection==Provider.PREMIUM
          ?premium.cloudUploadDirectory(folderId,path,use)
          :ordinary.uploadDirectory(folderId,path,use);
    }catch(PremiumCloudClient.CloudException error){
      throw translate(error);
    }
  }

  void setIcon(String fileId,String path)throws CloudException{
    if(selection==Provider.PREMIUM)
      throw new CloudException(ERROR_UNSUPPORTED,"蓝奏云优享版不支持自定义文件图标");
    ordinary.setIcon(fileId,path);
  }

  DownloadResult download(CloudItem item,String destination)throws CloudException{
    checkItem(item);
    if(item.folder)throw new CloudException(ERROR_INVALID,"请选择文件下载");
    try{
      return selection==Provider.PREMIUM
          ?premium.cloudDownload(item,destination)
          :ordinary.download(item,destination);
    }catch(PremiumCloudClient.CloudException error){
      throw translate(error);
    }
  }

  ReplaceResult replaceApk(CloudItem item,String path,UploadOptions options)throws CloudException{
    checkItem(item);
    if(item.folder||!item.name.toLowerCase(java.util.Locale.ROOT).endsWith(".apk"))
      throw new CloudException(ERROR_INVALID,"只能替换 APK 文件");
    UploadOptions use=options==null?UploadOptions.defaults():options;
    use.kind="apk";
    if(selection==Provider.ORDINARY)return ordinary.replaceApk(item,path,use);
    try{
      return premium.cloudReplaceFile(item,path,use);
    }catch(PremiumCloudClient.CloudException error){
      throw translate(error);
    }
  }

  private static void checkItem(CloudItem item)throws CloudException{
    if(item==null||item.id.isEmpty())throw new CloudException(ERROR_INVALID,"请选择文件或文件夹");
  }

  private static void checkItems(List<CloudItem> items)throws CloudException{
    if(items==null||items.isEmpty())throw new CloudException(ERROR_INVALID,"请选择要操作的项目");
  }

  private static CloudException translate(PremiumCloudClient.CloudException error){
    return new CloudException(error.code(),error.getMessage());
  }
}
