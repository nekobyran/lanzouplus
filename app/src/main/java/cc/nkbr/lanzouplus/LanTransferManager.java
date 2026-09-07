package cc.nkbr.lanzouplus;

import android.content.*;
import android.database.Cursor;
import android.net.Uri;
import android.os.ParcelFileDescriptor;
import android.provider.DocumentsContract;
import android.provider.OpenableColumns;
import java.io.*;
import java.net.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

/** Android SAF bridge and lifecycle owner for LanTransferCore. */
public final class LanTransferManager implements AutoCloseable {
  public enum Phase { IDLE, RECEIVER_STARTING, RECEIVER_RUNNING, QUEUED, CONNECTING, AUTHENTICATING, TRANSFERRING, VERIFYING, COMPLETED, CANCELLED, FAILED, CLOSED }
  public static final class Status { public final Phase phase;public final String relativePath,error,connectionInfo;Status(Phase p,String path,String e,String info){phase=p;relativePath=path==null?"":path;error=e==null?"":e;connectionInfo=info==null?"":info;} }
  public static final class Progress { public final String relativePath;public final long transferredBytes,totalBytes;Progress(String p,long done,long total){relativePath=p==null?"":p;transferredBytes=done;totalBytes=total;} }
  public interface Listener {void onStatus(Status value);void onProgress(Progress value);}

  private final Context context;private final ContentResolver resolver;private final Uri receiveTree;private final Listener listener;private final LanTransferCore.Limits limits;
  private final ThreadPoolExecutor sends;private final Set<Future<?>> sendJobs=Collections.newSetFromMap(new ConcurrentHashMap<Future<?>,Boolean>());private final AtomicInteger cancelEpoch=new AtomicInteger();
  private volatile LanTransferCore.Receiver receiver;private volatile String connection="";private volatile boolean closed;

  public LanTransferManager(Context value,Uri tree,Listener target){context=value.getApplicationContext();resolver=context.getContentResolver();receiveTree=tree;listener=target;limits=LanTransferCore.Limits.adaptive();sends=new ThreadPoolExecutor(limits.workerCount,limits.workerCount,0L,TimeUnit.MILLISECONDS,new ArrayBlockingQueue<Runnable>(limits.queueCapacity),namedFactory("lan-send"),new ThreadPoolExecutor.AbortPolicy());}

  public synchronized String startReceiver()throws IOException{
    ensureOpen();if(receiveTree==null)throw new IOException("未选择接收目录");if(receiver!=null&&receiver.isRunning())return connection;
    emit(Phase.RECEIVER_STARTING,"","","");LanTransferCore.Pairing pairing=LanTransferCore.Pairing.generate();LanTransferCore.Receiver next=new LanTransferCore.Receiver(pairing,new SafReceiveStore(receiveTree),limits,coreListener());
    try{int port=next.start();InetAddress address=advertisedAddress();connection=new LanTransferCore.ConnectionInfo(LanTransferCore.Endpoint.of(address,port),pairing).format();receiver=next;emit(Phase.RECEIVER_RUNNING,"","",connection);return connection;}catch(Exception error){next.close();connection="";emit(Phase.FAILED,"",message(error),"");throw asIo(error);}
  }

  public synchronized void stopReceiver(){LanTransferCore.Receiver current=receiver;receiver=null;connection="";if(current!=null)current.close();if(!closed)emit(Phase.IDLE,"","","");}
  public String connectionInfo(){return connection;}

  public void sendFiles(String info,List<Uri> files)throws IOException{if(files==null||files.isEmpty())throw new IOException("没有可发送的文件");enqueue(info,()->manifestForFiles(files));}
  public void sendFolder(String info,Uri tree)throws IOException{if(tree==null)throw new IOException("没有可发送的文件夹");enqueue(info,()->manifestForTree(tree));}

  private void enqueue(String raw,ManifestFactory factory)throws IOException{
    ensureOpen();final LanTransferCore.ConnectionInfo target;try{target=LanTransferCore.ConnectionInfo.parse(raw==null?"":raw.trim());}catch(IllegalArgumentException error){throw new IOException("连接信息无效",error);}final int epoch=cancelEpoch.get();emit(Phase.QUEUED,"","",connection);
    AtomicReference<FutureTask<Void>> holder=new AtomicReference<>();FutureTask<Void> task=new FutureTask<Void>(()->{try{LanTransferCore.BoundedManifest manifest=factory.build();LanTransferCore.send(target,manifest,limits,()->Thread.currentThread().isInterrupted()||cancelEpoch.get()!=epoch,coreListener());if(cancelEpoch.get()==epoch)emit(Phase.COMPLETED,"","",connection);}catch(Exception error){if(Thread.currentThread().isInterrupted()||cancelEpoch.get()!=epoch)emit(Phase.CANCELLED,"","",connection);else emit(Phase.FAILED,"",message(error),connection);}finally{sendJobs.remove(holder.get());}return null;});holder.set(task);sendJobs.add(task);try{sends.execute(task);}catch(RejectedExecutionException error){sendJobs.remove(task);throw new IOException("发送队列已满，请等待当前任务完成",error);}
  }

  public void cancel(){cancelEpoch.incrementAndGet();for(Future<?> job:new ArrayList<>(sendJobs))job.cancel(true);sendJobs.clear();if(!closed)emit(Phase.CANCELLED,"","",connection);}

  private LanTransferCore.Listener coreListener(){return new LanTransferCore.Listener(){public void onStatus(String phase,String path,String error){emit(mapPhase(phase),path,error,connection);}public void onProgress(String path,long done,long total){if(listener!=null)listener.onProgress(new Progress(path,done,total));}};}
  private static Phase mapPhase(String value){if(value==null)return Phase.IDLE;try{return Phase.valueOf(value);}catch(IllegalArgumentException ignored){if("AUTHENTICATED".equals(value))return Phase.AUTHENTICATING;return Phase.IDLE;}}
  private void emit(Phase phase,String path,String error,String info){if(listener!=null)listener.onStatus(new Status(phase,path,error,info));}
  private void ensureOpen()throws IOException{if(closed)throw new IOException("传输管理器已关闭");}

  private interface ManifestFactory{LanTransferCore.BoundedManifest build()throws IOException;}
  private LanTransferCore.BoundedManifest manifestForFiles(List<Uri> values)throws IOException{ArrayList<LanTransferCore.SendFile> files=new ArrayList<>();LinkedHashSet<String> names=new LinkedHashSet<>();long total=0;for(Uri uri:values){if(uri==null)continue;String name=uniqueRelativeName(safeComponent(displayName(uri)),names);long size=contentSize(uri);if(size<0)size=countBytes(uri);if(size>limits.maxFileBytes||total>limits.maxTotalBytes-size)throw new IOException("所选文件超出局域网传输限制");files.add(new UriSendFile(uri,name,size));total+=size;if(files.size()>limits.maxFiles)throw new IOException("所选文件数量过多");}if(files.isEmpty())throw new IOException("没有可读取的文件");return new Manifest(files,total);}
  private LanTransferCore.BoundedManifest manifestForTree(Uri tree)throws IOException{ArrayList<LanTransferCore.SendFile> files=new ArrayList<>();String root=safeComponent(documentName(tree,DocumentsContract.getTreeDocumentId(tree)));long[] total={0};walkTree(tree,DocumentsContract.getTreeDocumentId(tree),root,files,total);if(files.isEmpty())throw new IOException("所选文件夹中没有文件");return new Manifest(files,total[0]);}

  private void walkTree(Uri tree,String parentId,String prefix,List<LanTransferCore.SendFile> out,long[] total)throws IOException{
    Uri children=DocumentsContract.buildChildDocumentsUriUsingTree(tree,parentId);String[] columns={DocumentsContract.Document.COLUMN_DOCUMENT_ID,DocumentsContract.Document.COLUMN_DISPLAY_NAME,DocumentsContract.Document.COLUMN_MIME_TYPE,DocumentsContract.Document.COLUMN_SIZE};
    try(Cursor cursor=resolver.query(children,columns,null,null,null)){if(cursor==null)throw new IOException("无法读取文件夹");while(cursor.moveToNext()){String id=cursor.getString(0),name=safeComponent(cursor.getString(1)),mime=cursor.getString(2);String relative=prefix+"/"+name;if(DocumentsContract.Document.MIME_TYPE_DIR.equals(mime)){walkTree(tree,id,relative,out,total);continue;}long size=cursor.isNull(3)?-1:cursor.getLong(3);Uri uri=DocumentsContract.buildDocumentUriUsingTree(tree,id);if(size<0)size=countBytes(uri);if(size>limits.maxFileBytes||total[0]>limits.maxTotalBytes-size)throw new IOException("文件夹超出局域网传输限制");out.add(new UriSendFile(uri,relative,size));total[0]+=size;if(out.size()>limits.maxFiles)throw new IOException("文件夹文件数量过多");}}
  }

  private final class UriSendFile implements LanTransferCore.SendFile{final Uri uri;final String path;final long size;UriSendFile(Uri uri,String path,long size){this.uri=uri;this.path=path;this.size=size;}public String relativePath(){return path;}public long size(){return size;}public InputStream open(long offset)throws IOException{InputStream in=resolver.openInputStream(uri);if(in==null)throw new IOException("无法打开 "+path);try{skipFully(in,offset);return in;}catch(IOException error){try{in.close();}catch(IOException ignored){}throw error;}}}
  private static final class Manifest implements LanTransferCore.BoundedManifest{final List<LanTransferCore.SendFile> files;final long total;Manifest(List<LanTransferCore.SendFile> files,long total){this.files=Collections.unmodifiableList(new ArrayList<>(files));this.total=total;}public int fileCount(){return files.size();}public long totalSize(){return total;}public Iterator<LanTransferCore.SendFile> iterator(){return files.iterator();}}

  private final class SafReceiveStore implements LanTransferCore.ReceiveStore{
    final Uri tree;final String rootId;SafReceiveStore(Uri tree)throws IOException{this.tree=tree;try{rootId=DocumentsContract.getTreeDocumentId(tree);}catch(Exception error){throw new IOException("接收目录无效",error);}}
    public long existingSize(String relative,long declared)throws IOException{DocumentRef ref=partial(relative,false);if(ref==null)return 0;long size=documentSize(ref.uri);if(size<0||size>declared){DocumentsContract.deleteDocument(resolver,ref.uri);return 0;}return size;}
    public OutputStream open(String relative,long offset)throws IOException{DocumentRef ref=partial(relative,true);ParcelFileDescriptor descriptor=resolver.openFileDescriptor(ref.uri,"rw");if(descriptor==null)throw new IOException("无法打开接收文件");FileOutputStream out=new FileOutputStream(descriptor.getFileDescriptor());try{if(offset==0)out.getChannel().truncate(0);out.getChannel().position(offset);}catch(IOException error){out.close();descriptor.close();throw error;}return new FilterOutputStream(out){public void close()throws IOException{IOException failure=null;try{super.close();}catch(IOException e){failure=e;}try{descriptor.close();}catch(IOException e){if(failure==null)failure=e;}if(failure!=null)throw failure;}};}
    public void commit(String relative,long size)throws IOException{DocumentRef ref=partial(relative,false);if(ref==null||documentSize(ref.uri)!=size)throw new IOException("接收文件长度不完整");String[] parts=relative.split("/");Uri parent=ensureDirectory(Arrays.copyOf(parts,parts.length-1));String finalName=uniqueChildName(parent,parts[parts.length-1]);Uri renamed=DocumentsContract.renameDocument(resolver,ref.uri,finalName);if(renamed==null)throw new IOException("无法完成接收文件");}
    private DocumentRef partial(String relative,boolean create)throws IOException{String[] parts=relative.split("/");Uri parent=ensureDirectory(Arrays.copyOf(parts,parts.length-1));String name=parts[parts.length-1]+".lzp-part";Uri existing=findChild(parent,name,null);if(existing==null&&create)existing=DocumentsContract.createDocument(resolver,parent,"application/octet-stream",name);return existing==null?null:new DocumentRef(existing,name);}
    private Uri ensureDirectory(String[] components)throws IOException{Uri current=DocumentsContract.buildDocumentUriUsingTree(tree,rootId);for(String component:components){Uri child=findChild(current,component,DocumentsContract.Document.MIME_TYPE_DIR);if(child==null)child=DocumentsContract.createDocument(resolver,current,DocumentsContract.Document.MIME_TYPE_DIR,component);if(child==null)throw new IOException("无法创建接收文件夹");current=child;}return current;}
    private String uniqueChildName(Uri parent,String requested)throws IOException{if(findChild(parent,requested,null)==null)return requested;int dot=requested.lastIndexOf('.');String base=dot>0?requested.substring(0,dot):requested,ext=dot>0?requested.substring(dot):"";for(int i=1;i<Integer.MAX_VALUE;i++){String name=base+" ("+i+")"+ext;if(findChild(parent,name,null)==null)return name;}throw new IOException("无法生成接收文件名");}
    private Uri findChild(Uri parent,String name,String requiredMime)throws IOException{String parentId=DocumentsContract.getDocumentId(parent);Uri children=DocumentsContract.buildChildDocumentsUriUsingTree(tree,parentId);String[] columns={DocumentsContract.Document.COLUMN_DOCUMENT_ID,DocumentsContract.Document.COLUMN_DISPLAY_NAME,DocumentsContract.Document.COLUMN_MIME_TYPE};try(Cursor cursor=resolver.query(children,columns,null,null,null)){if(cursor==null)return null;while(cursor.moveToNext())if(name.equals(cursor.getString(1))&&(requiredMime==null||requiredMime.equals(cursor.getString(2))))return DocumentsContract.buildDocumentUriUsingTree(tree,cursor.getString(0));}return null;}
  }

  private static final class DocumentRef{final Uri uri;final String name;DocumentRef(Uri uri,String name){this.uri=uri;this.name=name;}}
  private String displayName(Uri uri){try(Cursor c=resolver.query(uri,new String[]{OpenableColumns.DISPLAY_NAME},null,null,null)){if(c!=null&&c.moveToFirst()&&!c.isNull(0))return c.getString(0);}return "file";}
  private String documentName(Uri tree,String id){Uri doc=DocumentsContract.buildDocumentUriUsingTree(tree,id);try(Cursor c=resolver.query(doc,new String[]{DocumentsContract.Document.COLUMN_DISPLAY_NAME},null,null,null)){if(c!=null&&c.moveToFirst()&&!c.isNull(0))return c.getString(0);}return "folder";}
  private long contentSize(Uri uri){try(Cursor c=resolver.query(uri,new String[]{OpenableColumns.SIZE},null,null,null)){if(c!=null&&c.moveToFirst()&&!c.isNull(0))return c.getLong(0);}catch(Exception ignored){}try(ParcelFileDescriptor p=resolver.openFileDescriptor(uri,"r")){if(p!=null&&p.getStatSize()>=0)return p.getStatSize();}catch(Exception ignored){}return -1;}
  private long documentSize(Uri uri)throws IOException{try(Cursor c=resolver.query(uri,new String[]{DocumentsContract.Document.COLUMN_SIZE},null,null,null)){if(c!=null&&c.moveToFirst()&&!c.isNull(0))return c.getLong(0);}return -1;}
  private long countBytes(Uri uri)throws IOException{long count=0;byte[] buffer=new byte[64*1024];try(InputStream in=resolver.openInputStream(uri)){if(in==null)throw new IOException("无法读取文件");for(int n;(n=in.read(buffer))>=0;){count+=n;if(count>limits.maxFileBytes)throw new IOException("文件超出传输限制");}}return count;}
  private static void skipFully(InputStream in,long offset)throws IOException{long remaining=offset;byte[] scratch=new byte[8192];while(remaining>0){long skipped=in.skip(remaining);if(skipped>0){remaining-=skipped;continue;}int n=in.read(scratch,0,(int)Math.min(scratch.length,remaining));if(n<0)throw new EOFException("无法恢复发送位置");remaining-=n;}}
  private static String safeComponent(String raw)throws IOException{String value=raw==null?"":raw.trim();value=value.replace('/','_').replace('\\','_').replace(':','_');if(value.isEmpty()||value.equals(".")||value.equals(".."))throw new IOException("文件名无效");return value;}
  private static String uniqueRelativeName(String requested,Set<String> used){String value=requested;if(used.add(value))return value;int dot=requested.lastIndexOf('.');String base=dot>0?requested.substring(0,dot):requested,ext=dot>0?requested.substring(dot):"";for(int i=1;;i++){value=base+" ("+i+")"+ext;if(used.add(value))return value;}}
  private static InetAddress advertisedAddress()throws IOException{InetAddress ipv6=null;Enumeration<NetworkInterface> interfaces=NetworkInterface.getNetworkInterfaces();if(interfaces!=null)while(interfaces.hasMoreElements()){NetworkInterface network=interfaces.nextElement();if(!network.isUp())continue;Enumeration<InetAddress> addresses=network.getInetAddresses();while(addresses.hasMoreElements()){InetAddress address=addresses.nextElement();if(!LanTransferCore.isAllowedLanAddress(address)||address.isLoopbackAddress())continue;if(address instanceof Inet4Address)return address;if(ipv6==null)ipv6=address;}}if(ipv6!=null)return ipv6;return InetAddress.getLoopbackAddress();}
  private static ThreadFactory namedFactory(String prefix){return new ThreadFactory(){int index;public synchronized Thread newThread(Runnable r){Thread t=new Thread(r,prefix+"-"+(++index));t.setDaemon(true);return t;}};}
  private static String message(Throwable error){String value=error==null?"":error.getMessage();return value==null||value.trim().isEmpty()?"局域网传输失败":value.trim();}
  private static IOException asIo(Throwable error){return error instanceof IOException?(IOException)error:new IOException(message(error),error);}
  @Override public synchronized void close(){if(closed)return;closed=true;cancel();LanTransferCore.Receiver current=receiver;receiver=null;connection="";if(current!=null)current.close();sends.shutdownNow();try{sends.awaitTermination(Math.max(1,limits.readTimeoutMillis),TimeUnit.MILLISECONDS);}catch(InterruptedException e){Thread.currentThread().interrupt();}emit(Phase.CLOSED,"","","");}
}