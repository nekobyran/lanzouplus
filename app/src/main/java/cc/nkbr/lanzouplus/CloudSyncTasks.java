package cc.nkbr.lanzouplus;

import android.app.job.JobInfo;
import android.app.job.JobScheduler;
import android.content.ComponentName;
import android.content.ContentValues;
import android.content.Context;
import android.content.UriPermission;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteOpenHelper;
import android.net.Uri;
import android.provider.DocumentsContract;
import android.os.PersistableBundle;
import android.security.keystore.KeyGenParameterSpec;
import android.security.keystore.KeyProperties;
import android.util.Base64;
import java.nio.charset.StandardCharsets;
import java.security.KeyStore;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import javax.crypto.Cipher;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;
import org.json.JSONObject;

/** Durable sync definitions and their upload results. Job extras contain only the task id. */
public final class CloudSyncTasks {
  private static final String KEY = "cloud_sync_password_v1";
  private static volatile Store sharedStore;
  private final Context context;
  private final Store store;

  public static final class Task {
    public long id;
    public String name = "", accountId = "", cloudType = "", cloudFolderId = "", sourceUri = "";
    public String kind = "folder", configJson = "{}", policyRevision = "";
    public long intervalMinutes, syncedFiles, syncedBytes, updatedAt;
    public String status = "idle", message = "";
  }
  public static final class Upload {
    public String path = "", state = "", message = "", accountId = "", cloudType = "", cloudFolderId = "";
    public long taskId;
    public long bytes, updatedAt;
  }
  public CloudSyncTasks(Context context) {
    this.context = context.getApplicationContext();
    Store existing = sharedStore;
    if (existing == null) {
      synchronized (CloudSyncTasks.class) {
        existing = sharedStore;
        if (existing == null) sharedStore = existing = new Store(this.context);
      }
    }
    this.store = existing;
  }
  private static final class Store extends SQLiteOpenHelper {
    Store(Context c) { super(c, "cloud_sync.db", null, 4); }
    @Override public void onCreate(SQLiteDatabase db) {
      db.execSQL("CREATE TABLE tasks (_id INTEGER PRIMARY KEY AUTOINCREMENT,name TEXT NOT NULL,kind TEXT NOT NULL DEFAULT 'folder',config_json TEXT NOT NULL DEFAULT '{}',account_id TEXT NOT NULL,cloud_type TEXT NOT NULL,folder_id TEXT NOT NULL,source_uri TEXT NOT NULL,interval_min INTEGER NOT NULL DEFAULT 0,password TEXT NOT NULL DEFAULT '',archive_password TEXT NOT NULL DEFAULT '',policy_revision TEXT NOT NULL DEFAULT '',status TEXT NOT NULL DEFAULT 'idle',message TEXT NOT NULL DEFAULT '',file_count INTEGER NOT NULL DEFAULT 0,byte_count INTEGER NOT NULL DEFAULT 0,updated_at INTEGER NOT NULL DEFAULT 0)");
      db.execSQL("CREATE TABLE uploads (task_id INTEGER NOT NULL,path TEXT NOT NULL,state TEXT NOT NULL,message TEXT NOT NULL,bytes INTEGER NOT NULL,updated_at INTEGER NOT NULL,PRIMARY KEY(task_id,path),FOREIGN KEY(task_id) REFERENCES tasks(_id) ON DELETE CASCADE)");
      db.execSQL("CREATE TABLE manual_uploads (account_id TEXT NOT NULL,cloud_type TEXT NOT NULL,folder_id TEXT NOT NULL,path TEXT NOT NULL,state TEXT NOT NULL,message TEXT NOT NULL,bytes INTEGER NOT NULL,updated_at INTEGER NOT NULL,PRIMARY KEY(account_id,cloud_type,folder_id,path))");
      db.execSQL("CREATE TABLE task_backup_identity (task_id INTEGER PRIMARY KEY,backup_id TEXT NOT NULL UNIQUE,pending_interval INTEGER NOT NULL DEFAULT 0,source_hint TEXT NOT NULL DEFAULT '',FOREIGN KEY(task_id) REFERENCES tasks(_id) ON DELETE CASCADE)");
    }
    @Override public void onConfigure(SQLiteDatabase db) { db.setForeignKeyConstraintsEnabled(true); }
    @Override public void onUpgrade(SQLiteDatabase db, int oldVersion, int newVersion) {
      if (!hasColumn(db,"kind")) db.execSQL("ALTER TABLE tasks ADD COLUMN kind TEXT NOT NULL DEFAULT 'folder'");
      if (!hasColumn(db,"config_json")) db.execSQL("ALTER TABLE tasks ADD COLUMN config_json TEXT NOT NULL DEFAULT '{}'");
      if (!hasColumn(db,"archive_password")) db.execSQL("ALTER TABLE tasks ADD COLUMN archive_password TEXT NOT NULL DEFAULT ''");
      if (!hasColumn(db,"policy_revision")) db.execSQL("ALTER TABLE tasks ADD COLUMN policy_revision TEXT NOT NULL DEFAULT ''");
      db.execSQL("CREATE TABLE IF NOT EXISTS manual_uploads (account_id TEXT NOT NULL,cloud_type TEXT NOT NULL,folder_id TEXT NOT NULL,path TEXT NOT NULL,state TEXT NOT NULL,message TEXT NOT NULL,bytes INTEGER NOT NULL,updated_at INTEGER NOT NULL,PRIMARY KEY(account_id,cloud_type,folder_id,path))");
      db.execSQL("CREATE TABLE IF NOT EXISTS task_backup_identity (task_id INTEGER PRIMARY KEY,backup_id TEXT NOT NULL UNIQUE,pending_interval INTEGER NOT NULL DEFAULT 0,source_hint TEXT NOT NULL DEFAULT '',FOREIGN KEY(task_id) REFERENCES tasks(_id) ON DELETE CASCADE)");
    }
  }
  private static boolean hasColumn(SQLiteDatabase db,String name) {
    try(Cursor c=db.rawQuery("PRAGMA table_info(tasks)",null)) {
      while(c.moveToNext()) if(name.equals(s(c,"name"))) return true;
      return false;
    }
  }
  private void validate(Task t) {
    if (t == null || t.name.trim().isEmpty() ||
        !("folder".equals(t.kind) || "github_release".equals(t.kind) || "github_backup".equals(t.kind)) ||
        (!"github_backup".equals(t.kind) && (t.accountId.trim().isEmpty() ||
        !("ordinary".equals(t.cloudType) || "premium".equals(t.cloudType)) || t.cloudFolderId.trim().isEmpty())))
      throw new IllegalArgumentException("同步任务缺少账号、云目录或来源目录");
    if (t.intervalMinutes != 0 && (t.intervalMinutes < 15 || t.intervalMinutes > Long.MAX_VALUE / 60000L)) throw new IllegalArgumentException("自动同步间隔至少为 15 分钟");
    try { new JSONObject(t.configJson); } catch (Exception error) { throw new IllegalArgumentException("任务配置格式无效"); }
    if (!"folder".equals(t.kind)) return;
    Uri uri = Uri.parse(t.sourceUri);
    if ("content".equals(uri.getScheme())) {
      if (!DocumentsContract.isTreeUri(uri)) throw new IllegalArgumentException("来源必须是目录");
      boolean granted = false;
      for (UriPermission p : context.getContentResolver().getPersistedUriPermissions())
        if (p.getUri().equals(uri) && p.isReadPermission() && p.isWritePermission()) { granted = true; break; }
      if (!granted) throw new SecurityException("来源目录没有持久读写权限");
    } else throw new IllegalArgumentException("来源目录必须是持久授权的目录 URI");
  }
  private ContentValues values(Task t) {
    ContentValues v = new ContentValues();
    v.put("name",t.name); v.put("kind",t.kind); v.put("config_json",t.configJson); v.put("account_id",t.accountId); v.put("cloud_type",t.cloudType);
    v.put("folder_id",t.cloudFolderId); v.put("source_uri",t.sourceUri); v.put("interval_min",t.intervalMinutes);
    return v;
  }
  public long create(Task task, char[] password) throws Exception { return create(task,password,null); }
  public long create(Task task, char[] password, char[] archivePassword) throws Exception {
    validate(task);
    ContentValues v = values(task); v.put("password", seal(password)); v.put("archive_password",seal(archivePassword));
    String revision=UUID.randomUUID().toString(); v.put("policy_revision",revision);
    SQLiteDatabase db=store.getWritableDatabase();
    long id=0;
    boolean scheduled=false;
    db.beginTransaction();
    try {
      id=db.insertOrThrow("tasks",null,v);
      if(task.intervalMinutes>0) {
        schedule(id,task.intervalMinutes,true);
        scheduled=true;
      }
      db.setTransactionSuccessful();
    } catch(RuntimeException error) {
      if(scheduled) scheduler().cancel(jobId(id,true));
      throw error;
    } finally {
      try {
        db.endTransaction();
      } catch(RuntimeException error) {
        if(scheduled) scheduler().cancel(jobId(id,true));
        throw error;
      }
    }
    task.id=id; task.policyRevision=revision;
    return id;
  }
  /** Null password preserves the current secret; an empty array clears it. */
  public void update(Task task, char[] password) throws Exception { update(task,password,null); }
  public void update(Task task, char[] password, char[] archivePassword) throws Exception {
    synchronized(store) { updateLocked(task,password,archivePassword); }
  }
  private void updateLocked(Task task, char[] password, char[] archivePassword) throws Exception {
    validate(task);
    ContentValues v=values(task);
    if(password!=null) v.put("password",seal(password));
    if(archivePassword!=null) v.put("archive_password",seal(archivePassword));
    SQLiteDatabase db=store.getWritableDatabase();
    db.beginTransaction();
    Task original=null;
    String revision=null;
    boolean scheduleChanged=false;
    RuntimeException failure=null;
    try {
      original=get(task.id);
      if(original==null) throw new IllegalArgumentException("同步任务不存在");
      if(!original.kind.equals(task.kind) || !original.accountId.equals(task.accountId) || !original.cloudType.equals(task.cloudType) ||
          !original.cloudFolderId.equals(task.cloudFolderId) || !original.sourceUri.equals(task.sourceUri))
        throw new IllegalArgumentException("账号和同步目录不能修改，请创建新任务");
      if(password!=null || archivePassword!=null || !original.configJson.equals(task.configJson) || original.policyRevision.isEmpty()) {
        revision=UUID.randomUUID().toString(); v.put("policy_revision",revision);
      }
      if(db.update("tasks",v,"_id=?",new String[]{Long.toString(task.id)})!=1)
        throw new IllegalArgumentException("同步任务不存在");
      scheduleChanged=true;
      if(task.intervalMinutes>0) schedule(task.id,task.intervalMinutes,true);
      else scheduler().cancel(jobId(task.id,true));
      db.setTransactionSuccessful();
    } catch(RuntimeException error) {
      failure=error;
    }
    try { db.endTransaction(); }
    catch(RuntimeException error) {
      if(failure==null) failure=error;
      else failure.addSuppressed(error);
    }
    if(failure!=null) {
      if(scheduleChanged && original!=null) {
        try {
          if(original.intervalMinutes>0) schedule(original.id,original.intervalMinutes,true);
          else scheduler().cancel(jobId(original.id,true));
        } catch(RuntimeException restoreError) {
          failure.addSuppressed(restoreError);
          setStatus(original.id,"failed","自动同步调度未能恢复，请重新设置",original.syncedFiles,original.syncedBytes);
        }
      }
      throw failure;
    }
    if(revision!=null) task.policyRevision=revision;
  }
  public Task get(long id) {
    try (Cursor c=store.getReadableDatabase().query("tasks",null,"_id=?",new String[]{Long.toString(id)},null,null,null)) { return c.moveToFirst()?read(c):null; }
  }
  public List<Task> list() {
    List<Task> out=new ArrayList<>();
    try (Cursor c=store.getReadableDatabase().query("tasks",null,null,null,null,null,"_id DESC")) { while(c.moveToNext()) out.add(read(c)); }
    return out;
  }
  private static Task read(Cursor c) {
    Task t=new Task(); t.id=c.getLong(c.getColumnIndexOrThrow("_id")); t.name=s(c,"name"); t.kind=s(c,"kind"); t.configJson=s(c,"config_json"); t.policyRevision=s(c,"policy_revision"); t.accountId=s(c,"account_id");
    t.cloudType=s(c,"cloud_type"); t.cloudFolderId=s(c,"folder_id"); t.sourceUri=s(c,"source_uri");
    t.intervalMinutes=n(c,"interval_min"); t.syncedFiles=n(c,"file_count"); t.syncedBytes=n(c,"byte_count");
    t.updatedAt=n(c,"updated_at"); t.status=s(c,"status"); t.message=s(c,"message"); return t;
  }
  /** Captures portable task configuration and secrets under a database transaction. */
  org.json.JSONArray backupTasks() throws Exception {
    SQLiteDatabase db=store.getWritableDatabase();org.json.JSONArray result=new org.json.JSONArray();
    db.beginTransaction();
    try{
      try(Cursor missing=db.rawQuery("SELECT _id FROM tasks WHERE _id NOT IN (SELECT task_id FROM task_backup_identity)",null)){
        while(missing.moveToNext()){
          ContentValues identity=new ContentValues();identity.put("task_id",missing.getLong(0));
          identity.put("backup_id",UUID.randomUUID().toString());
          db.insertOrThrow("task_backup_identity",null,identity);
        }
      }
      try(Cursor rows=db.rawQuery("SELECT t.*,b.backup_id,b.pending_interval,b.source_hint FROM tasks t JOIN task_backup_identity b ON b.task_id=t._id ORDER BY b.backup_id",null)){
        while(rows.moveToNext())result.put(taskBackupJson(rows));
      }
      db.setTransactionSuccessful();return result;
    }finally{db.endTransaction();}
  }
  private static JSONObject taskBackupJson(Cursor row)throws Exception{
    long interval=n(row,"interval_min"),pending=n(row,"pending_interval");
    String uri=s(row,"source_uri"),hint=s(row,"source_hint");
    char[] secret=unseal(s(row,"password")),archive=unseal(s(row,"archive_password"));
    try{
      return new JSONObject().put("id",s(row,"backup_id")).put("name",s(row,"name"))
          .put("kind",s(row,"kind")).put("config",s(row,"config_json"))
          .put("account",s(row,"account_id")).put("cloud",s(row,"cloud_type"))
          .put("folder",s(row,"folder_id")).put("sourceHint",uri.isEmpty()?hint:uri)
          .put("interval",interval>0?interval:pending).put("policy",s(row,"policy_revision"))
          .put("password",new String(secret)).put("archivePassword",new String(archive));
    }finally{java.util.Arrays.fill(secret,'\0');java.util.Arrays.fill(archive,'\0');}
  }
  /** Inserts only absent portable IDs; conflicts retain the existing local task. */
  boolean restoreBackupTask(JSONObject value)throws Exception{
    String id=value.getString("id"),name=value.getString("name"),kind=value.getString("kind");
    if(!UUID.fromString(id).toString().equals(id)||name.trim().isEmpty()
        ||!("folder".equals(kind)||"github_release".equals(kind)||"github_backup".equals(kind)))
      throw new IllegalArgumentException("备份任务格式无效");
    String config=value.getString("config");new JSONObject(config);
    String account=value.getString("account"),cloud=value.getString("cloud"),folder=value.getString("folder");
    if(!"github_backup".equals(kind)&&(account.trim().isEmpty()||folder.trim().isEmpty()
        ||!("ordinary".equals(cloud)||"premium".equals(cloud))))throw new IllegalArgumentException("备份任务目标无效");
    long interval=value.getLong("interval");
    if(interval!=0&&(interval<15||interval>Long.MAX_VALUE/60000L))throw new IllegalArgumentException("备份任务间隔无效");
    char[] secret=value.getString("password").toCharArray(),archive=value.getString("archivePassword").toCharArray();
    try{
      SQLiteDatabase db=store.getWritableDatabase();db.beginTransaction();
      try{
        try(Cursor existing=db.rawQuery("SELECT t.*,b.backup_id,b.pending_interval,b.source_hint FROM tasks t JOIN task_backup_identity b ON b.task_id=t._id WHERE b.backup_id=?",new String[]{id})){
          if(existing.moveToFirst()){
            boolean identical=sameBackupTask(taskBackupJson(existing),value);
            db.setTransactionSuccessful();return identical;
          }
        }
        ContentValues task=new ContentValues();task.put("name",name);task.put("kind",kind);task.put("config_json",config);
        task.put("account_id",account);task.put("cloud_type",cloud);task.put("folder_id",folder);task.put("source_uri","");
        task.put("interval_min",0);task.put("password",seal(secret));task.put("archive_password",seal(archive));
        task.put("policy_revision",value.getString("policy"));task.put("status","needs_attention");
        task.put("message","folder".equals(kind)?"已恢复：请重新授权来源目录，自动同步需手动开启":"已恢复：自动同步需手动开启");
        long taskId=db.insertOrThrow("tasks",null,task);
        ContentValues identity=new ContentValues();identity.put("task_id",taskId);identity.put("backup_id",id);
        identity.put("pending_interval",interval);identity.put("source_hint",value.getString("sourceHint"));
        db.insertOrThrow("task_backup_identity",null,identity);db.setTransactionSuccessful();return true;
      }finally{db.endTransaction();}
    }finally{java.util.Arrays.fill(secret,'\0');java.util.Arrays.fill(archive,'\0');}
  }
  private static boolean sameBackupTask(JSONObject first,JSONObject second)throws Exception{
    for(String key:new String[]{"id","name","kind","config","account","cloud","folder","sourceHint","policy","password","archivePassword"})
      if(!first.getString(key).equals(second.getString(key)))return false;
    return first.getLong("interval")==second.getLong("interval");
  }  /** Restored folder tasks remain paused until the user grants a new directory. */
  public void reauthorizeRestoredFolder(long id,Uri source)throws Exception{
    Task task=get(id);if(task==null||!"folder".equals(task.kind)||!task.sourceUri.isEmpty())
      throw new IllegalArgumentException("任务不需要重新授权");
    task.sourceUri=source==null?"":source.toString();validate(task);
    SQLiteDatabase db=store.getWritableDatabase();db.beginTransaction();
    try{
      ContentValues update=new ContentValues();update.put("source_uri",task.sourceUri);
      update.put("message","来源已授权；自动同步需手动开启");
      if(db.update("tasks",update,"_id=? AND source_uri=''",new String[]{Long.toString(id)})!=1)
        throw new IllegalStateException("任务已变化，请重试");
      ContentValues hint=new ContentValues();hint.put("source_hint",task.sourceUri);
      db.update("task_backup_identity",hint,"task_id=?",new String[]{Long.toString(id)});
      db.setTransactionSuccessful();
    }finally{db.endTransaction();}
  }
  public long pendingAutomaticInterval(long id){
    try(Cursor c=store.getReadableDatabase().query("task_backup_identity",new String[]{"pending_interval"},"task_id=?",new String[]{Long.toString(id)},null,null,null)){
      return c.moveToFirst()?c.getLong(0):0;
    }
  }
  private static String s(Cursor c,String name) { return c.getString(c.getColumnIndexOrThrow(name)); }
  private static long n(Cursor c,String name) { return c.getLong(c.getColumnIndexOrThrow(name)); }
  public void delete(long id) {
    synchronized(store) {
      scheduler().cancel(jobId(id,true)); scheduler().cancel(jobId(id,false));
      store.getWritableDatabase().delete("tasks","_id=?",new String[]{Long.toString(id)});
    }
  }
  public void setAutomatic(long id,long minutes) throws Exception {
    synchronized(store) {
      Task t=get(id); if(t==null) throw new IllegalArgumentException("同步任务不存在"); t.intervalMinutes=minutes; updateLocked(t,null,null);
      ContentValues cleared=new ContentValues();cleared.put("pending_interval",0);
      store.getWritableDatabase().update("task_backup_identity",cleared,"task_id=?",new String[]{Long.toString(id)});
    }
  }
  public void runNow(long id) {
    synchronized(store) {
      if(get(id)==null) throw new IllegalArgumentException("同步任务不存在");
      schedule(id,0,false);
    }
  }
  public void setStatus(long id,String status,String message,long files,long bytes) {
    ContentValues v=new ContentValues(); v.put("status",status); v.put("message",message); v.put("file_count",files); v.put("byte_count",bytes); v.put("updated_at",System.currentTimeMillis());
    store.getWritableDatabase().update("tasks",v,"_id=?",new String[]{Long.toString(id)});
  }
  public void recordUpload(long id,String path,String state,String message,long bytes) {
    ContentValues v=new ContentValues(); v.put("task_id",id); v.put("path",path); v.put("state",state); v.put("message",message); v.put("bytes",bytes); v.put("updated_at",System.currentTimeMillis());
    store.getWritableDatabase().insertWithOnConflict("uploads",null,v,SQLiteDatabase.CONFLICT_REPLACE);
  }
  public List<Upload> uploads(long id) {
    List<Upload> out=new ArrayList<>();
    try(Cursor c=store.getReadableDatabase().query("uploads",null,"task_id=?",new String[]{Long.toString(id)},null,null,"updated_at DESC")) {
      while(c.moveToNext()) { Upload u=new Upload(); u.path=s(c,"path");u.state=s(c,"state");u.message=s(c,"message");u.bytes=n(c,"bytes");u.updatedAt=n(c,"updated_at");out.add(u); }
    } return out;
  }
  public void recordManualUpload(String accountId,String cloudType,String folderId,String name,String state,String message,long bytes) {
    ContentValues v=new ContentValues(); v.put("account_id",accountId); v.put("cloud_type",cloudType); v.put("folder_id",folderId);
    v.put("path",name); v.put("state",state); v.put("message",message); v.put("bytes",bytes); v.put("updated_at",System.currentTimeMillis());
    store.getWritableDatabase().insertWithOnConflict("manual_uploads",null,v,SQLiteDatabase.CONFLICT_REPLACE);
  }
  public List<Upload> allUploads() {
    List<Upload> out=new ArrayList<>();
    String sql="SELECT u.task_id,u.path,u.state,u.message,u.bytes,u.updated_at,t.account_id,t.cloud_type,t.folder_id FROM uploads u JOIN tasks t ON t._id=u.task_id UNION ALL SELECT 0 AS task_id,path,state,message,bytes,updated_at,account_id,cloud_type,folder_id FROM manual_uploads ORDER BY updated_at DESC";
    try(Cursor c=store.getReadableDatabase().rawQuery(sql,null)) {
      while(c.moveToNext()) {
        Upload u=new Upload(); u.taskId=n(c,"task_id"); u.path=s(c,"path"); u.state=s(c,"state"); u.message=s(c,"message");
        u.bytes=n(c,"bytes"); u.updatedAt=n(c,"updated_at"); u.accountId=s(c,"account_id"); u.cloudType=s(c,"cloud_type"); u.cloudFolderId=s(c,"folder_id"); out.add(u);
      }
    }
    return out;
  }

  private JobScheduler scheduler() { return (JobScheduler)context.getSystemService(Context.JOB_SCHEDULER_SERVICE); }
  private static int jobId(long id,boolean periodic) {
    if(id<=0 || id>500000000L) throw new IllegalArgumentException("任务编号超出系统调度范围");
    return 1000000000+(int)id*2+(periodic?1:0);
  }
  private void schedule(long id,long minutes,boolean periodic) {
    PersistableBundle extras=new PersistableBundle(); extras.putLong("task_id",id);
    JobInfo.Builder b=new JobInfo.Builder(jobId(id,periodic),new ComponentName(context,CloudSyncJobService.class))
      .setRequiredNetworkType(JobInfo.NETWORK_TYPE_ANY).setPersisted(true).setExtras(extras);
    if(periodic) b.setPeriodic(minutes*60000L);
    if(scheduler().schedule(b.build())!=JobScheduler.RESULT_SUCCESS) throw new IllegalStateException("系统未能安排同步任务");
  }
  private static SecretKey key() throws Exception {
    KeyStore ks=KeyStore.getInstance("AndroidKeyStore"); ks.load(null);
    SecretKey present=(SecretKey)ks.getKey(KEY,null); if(present!=null) return present;
    KeyGenerator g=KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES,"AndroidKeyStore");
    g.init(new KeyGenParameterSpec.Builder(KEY,KeyProperties.PURPOSE_ENCRYPT|KeyProperties.PURPOSE_DECRYPT)
      .setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE).setKeySize(256).build());
    return g.generateKey();
  }
  private static String seal(char[] password) throws Exception {
    if(password==null || password.length==0) return "";
    Cipher c=Cipher.getInstance("AES/GCM/NoPadding"); c.init(Cipher.ENCRYPT_MODE,key());
    byte[] data=c.doFinal(new String(password).getBytes(StandardCharsets.UTF_8));
    return Base64.encodeToString(c.getIV(),Base64.NO_WRAP)+":"+Base64.encodeToString(data,Base64.NO_WRAP);
  }
  char[] password(long id) throws Exception { return readSecret(id,"password"); }
  char[] archivePassword(long id) throws Exception { return readSecret(id,"archive_password"); }
  private char[] readSecret(long id,String column) throws Exception {
    try(Cursor c=store.getReadableDatabase().query("tasks",new String[]{column},"_id=?",new String[]{Long.toString(id)},null,null,null)) {
      if(!c.moveToFirst()) throw new IllegalArgumentException("同步任务不存在");
      return unseal(c.getString(0));
    }
  }
  private static char[] unseal(String value)throws Exception{
    if(value==null||value.isEmpty())return new char[0];
    String[] parts=value.split(":",2);Cipher cipher=Cipher.getInstance("AES/GCM/NoPadding");
    cipher.init(Cipher.DECRYPT_MODE,key(),new GCMParameterSpec(128,Base64.decode(parts[0],Base64.NO_WRAP)));
    return new String(cipher.doFinal(Base64.decode(parts[1],Base64.NO_WRAP)),StandardCharsets.UTF_8).toCharArray();
  }
}


















