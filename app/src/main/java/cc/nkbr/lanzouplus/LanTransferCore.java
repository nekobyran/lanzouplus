package cc.nkbr.lanzouplus;

import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;

/** Bounded, resumable LAN transfer protocol using authenticated AES-GCM records. */
public final class LanTransferCore {
  private static final int MAGIC=0x4c5a5032,VERSION=2;
  private static final byte HELLO=1,HELLO_OK=2,MANIFEST=3,MANIFEST_OK=4,FILE=5,OFFSET=6,DATA=7,FILE_END=8,FILE_OK=9,ALL_END=10,COMPLETE=11,FAIL=12;
  private static final int IV_BYTES=12,CHALLENGE_BYTES=16,BUFFER_BYTES=64*1024,MAX_RECORD_BYTES=BUFFER_BYTES+8192;
  private static final SecureRandom RANDOM=new SecureRandom();
  private LanTransferCore(){}

  public static final class Limits {
    public final int maxFiles,maxPathBytes,workerCount,queueCapacity,connectTimeoutMillis,readTimeoutMillis;
    public final long maxTotalBytes,maxFileBytes,maxMetadataBytes;
    public Limits(int maxFiles,long maxTotalBytes,long maxFileBytes,int maxPathBytes,long maxMetadataBytes,int workerCount,int queueCapacity,int connectTimeoutMillis,int readTimeoutMillis){
      if(maxFiles<1||maxTotalBytes<0||maxFileBytes<0||maxPathBytes<1||maxMetadataBytes<1||workerCount<1||queueCapacity<1||connectTimeoutMillis<1||readTimeoutMillis<1)throw new IllegalArgumentException("invalid limits");
      this.maxFiles=maxFiles;this.maxTotalBytes=maxTotalBytes;this.maxFileBytes=maxFileBytes;this.maxPathBytes=maxPathBytes;this.maxMetadataBytes=maxMetadataBytes;this.workerCount=workerCount;this.queueCapacity=queueCapacity;this.connectTimeoutMillis=connectTimeoutMillis;this.readTimeoutMillis=readTimeoutMillis;
    }
    public static Limits adaptive(){
      int cpu=Math.max(1,Runtime.getRuntime().availableProcessors());long memory=Math.max(1L,Runtime.getRuntime().maxMemory());
      int memoryWorkers=(int)Math.max(1L,memory/(96L*1024*1024));int workers=Math.max(1,Math.min(cpu,memoryWorkers));int queue=Math.max(workers,workers+cpu);
      return new Limits(4096,16L*1024*1024*1024,4L*1024*1024*1024,768,2L*1024*1024,workers,queue,10_000,30_000);
    }
  }

  public static final class Pairing {
    private final byte[] key;private final String token,code;
    private Pairing(byte[] key,String token,String code){this.key=key;this.token=token;this.code=code;}
    public static Pairing generate(){byte[] key=new byte[32];RANDOM.nextBytes(key);return new Pairing(key,toHex(key),String.format(Locale.ROOT,"%06d",RANDOM.nextInt(1_000_000)));}
    public static Pairing from(String token,String code){if(token==null||!token.matches("[0-9a-fA-F]{64}")||code==null||!code.matches("[0-9]{6}"))throw new IllegalArgumentException("invalid pairing");byte[] key=fromHex(token);return new Pairing(key,toHex(key),code);}
    public String token(){return token;}public String code(){return code;}byte[] key(){return key.clone();}
    @Override public String toString(){return "Pairing{redacted}";}
  }

  public static final class Endpoint {
    private final InetAddress address;private final int port;
    private Endpoint(InetAddress address,int port){this.address=address;this.port=port;}
    public static Endpoint of(InetAddress address,int port){if(address==null||!isAllowedLanAddress(address))throw new IllegalArgumentException("not a LAN address");if(port<1||port>65535)throw new IllegalArgumentException("invalid port");return new Endpoint(address,port);}
    public InetAddress address(){return address;}public int port(){return port;}InetSocketAddress socketAddress(){return new InetSocketAddress(address,port);}
  }

  public static final class ConnectionInfo {
    public final Endpoint endpoint;public final Pairing pairing;
    public ConnectionInfo(Endpoint endpoint,Pairing pairing){if(endpoint==null||pairing==null)throw new NullPointerException();this.endpoint=endpoint;this.pairing=pairing;}
    public String format(){String host=endpoint.address().getHostAddress();String out="LZP2|"+host+"|"+endpoint.port()+"|"+pairing.token()+"|"+pairing.code();if(out.length()>512)throw new IllegalArgumentException("connection info too long");return out;}
    public static ConnectionInfo parse(String value){if(value==null||!value.equals(value.trim())||value.length()>512)throw new IllegalArgumentException("invalid connection info");String[] f=value.split("\\|",-1);if(f.length!=5||!"LZP2".equals(f[0])||!numericAddress(f[1]))throw new IllegalArgumentException("invalid connection info");try{int port=Integer.parseInt(f[2]);return new ConnectionInfo(Endpoint.of(InetAddress.getByName(f[1]),port),Pairing.from(f[3],f[4]));}catch(Exception e){throw new IllegalArgumentException("invalid connection info",e);}}
  }

  public interface SendFile {String relativePath();long size();InputStream open(long offset)throws IOException;}
  public interface BoundedManifest extends Iterable<SendFile>{int fileCount();long totalSize();}
  public interface ReceiveStore {long existingSize(String relativePath,long declaredSize)throws IOException;OutputStream open(String relativePath,long offset)throws IOException;void commit(String relativePath,long size)throws IOException;}
  public interface Cancellation {boolean isCancelled();}
  public interface Listener {void onStatus(String phase,String relativePath,String error);void onProgress(String relativePath,long transferred,long total);}

  public static void send(ConnectionInfo info,BoundedManifest manifest,Limits limits,Cancellation cancellation,Listener listener)throws IOException{
    Objects.requireNonNull(info);Objects.requireNonNull(manifest);Objects.requireNonNull(limits);validateManifestSummary(manifest,limits);
    try(Socket socket=new Socket()){
      status(listener,"CONNECTING","","");socket.connect(info.endpoint.socketAddress(),limits.connectTimeoutMillis);socket.setSoTimeout(limits.readTimeoutMillis);
      DataInputStream in=new DataInputStream(new BufferedInputStream(socket.getInputStream()));DataOutputStream out=new DataOutputStream(new BufferedOutputStream(socket.getOutputStream()));
      out.writeInt(MAGIC);out.writeInt(VERSION);out.flush();byte[] challenge=readFixed(in,CHALLENGE_BYTES);
      status(listener,"AUTHENTICATING","","");writeRecord(out,info.pairing.key(),challenge,payload(d->{d.writeByte(HELLO);d.writeUTF(info.pairing.code());}));
      expectType(readRecord(in,info.pairing.key(),challenge),HELLO_OK);
      writeRecord(out,info.pairing.key(),challenge,payload(d->{d.writeByte(MANIFEST);d.writeInt(manifest.fileCount());d.writeLong(manifest.totalSize());}));expectType(readRecord(in,info.pairing.key(),challenge),MANIFEST_OK);
      int count=0;long declaredTotal=0;
      for(SendFile file:manifest){checkCancelled(cancellation);if(file==null)throw new IOException("manifest contains null file");String path=validatePath(file.relativePath(),limits);long size=file.size();if(size<0||size>limits.maxFileBytes)throw new IOException("file exceeds limit");if(++count>limits.maxFiles||declaredTotal>limits.maxTotalBytes-size)throw new IOException("manifest exceeds limit");declaredTotal+=size;
        writeRecord(out,info.pairing.key(),challenge,payload(d->{d.writeByte(FILE);writeString(d,path,limits.maxPathBytes);d.writeLong(size);}));byte[] offsetFrame=readRecord(in,info.pairing.key(),challenge);DataInputStream offsetIn=payloadIn(offsetFrame);expectType(offsetIn,OFFSET);long offset=offsetIn.readLong();if(offset<0||offset>size)throw new IOException("invalid resume offset");
        status(listener,"TRANSFERRING",path,"");long position=offset;try(InputStream source=new BufferedInputStream(file.open(offset))){byte[] buffer=new byte[BUFFER_BYTES];while(position<size){checkCancelled(cancellation);int wanted=(int)Math.min(buffer.length,size-position),n=readSome(source,buffer,wanted);if(n<1)throw new EOFException("source ended early");long frameOffset=position;byte[] copy=Arrays.copyOf(buffer,n);writeRecord(out,info.pairing.key(),challenge,payload(d->{d.writeByte(DATA);d.writeLong(frameOffset);d.writeInt(copy.length);d.write(copy);}));position+=n;progress(listener,path,position,size);}}
        long finalSize=size;writeRecord(out,info.pairing.key(),challenge,payload(d->{d.writeByte(FILE_END);d.writeLong(finalSize);}));byte[] ack=readRecord(in,info.pairing.key(),challenge);DataInputStream ackIn=payloadIn(ack);byte type=ackIn.readByte();if(type==FAIL)throw new IOException(readString(ackIn,4096));if(type!=FILE_OK)throw new IOException("invalid file acknowledgement");status(listener,"COMPLETED",path,"");
      }
      if(count!=manifest.fileCount()||declaredTotal!=manifest.totalSize())throw new IOException("manifest summary changed during transfer");writeRecord(out,info.pairing.key(),challenge,payload(d->d.writeByte(ALL_END)));expectType(readRecord(in,info.pairing.key(),challenge),COMPLETE);
    }catch(GeneralSecurityException e){throw new IOException("authenticated transport failed",e);}
  }

  public static final class Receiver implements AutoCloseable {
    private final Pairing pairing;private final ReceiveStore store;private final Limits limits;private final Listener listener;private final AtomicBoolean closed=new AtomicBoolean();private final Set<Socket> clients=Collections.newSetFromMap(new ConcurrentHashMap<Socket,Boolean>());
    private final ThreadPoolExecutor workers;private ServerSocket server;private Thread acceptThread;
    public Receiver(Pairing pairing,ReceiveStore store,Limits limits,Listener listener){this.pairing=Objects.requireNonNull(pairing);this.store=Objects.requireNonNull(store);this.limits=Objects.requireNonNull(limits);this.listener=listener;this.workers=new ThreadPoolExecutor(limits.workerCount,limits.workerCount,0L,TimeUnit.MILLISECONDS,new ArrayBlockingQueue<Runnable>(limits.queueCapacity),namedFactory("lan-recv"),new ThreadPoolExecutor.AbortPolicy());}
    public synchronized int start()throws IOException{if(server!=null)throw new IOException("receiver already started");server=new ServerSocket();server.setReuseAddress(true);server.bind(new InetSocketAddress(0));acceptThread=new Thread(this::acceptLoop,"lan-accept");acceptThread.setDaemon(true);acceptThread.start();return server.getLocalPort();}
    private void acceptLoop(){while(!closed.get()){try{Socket socket=server.accept();socket.setSoTimeout(limits.readTimeoutMillis);clients.add(socket);try{workers.execute(()->handle(socket));}catch(RejectedExecutionException e){clients.remove(socket);closeQuietly(socket);}}catch(SocketException e){if(!closed.get())status(listener,"FAILED","",e.getMessage());}catch(IOException e){if(!closed.get())status(listener,"FAILED","",e.getMessage());}}}
    private void handle(Socket socket){try(Socket s=socket){DataInputStream in=new DataInputStream(new BufferedInputStream(s.getInputStream()));DataOutputStream out=new DataOutputStream(new BufferedOutputStream(s.getOutputStream()));if(in.readInt()!=MAGIC||in.readInt()!=VERSION)throw new IOException("protocol mismatch");byte[] challenge=new byte[CHALLENGE_BYTES];RANDOM.nextBytes(challenge);out.write(challenge);out.flush();byte[] hello=readRecord(in,pairing.key(),challenge);DataInputStream helloIn=payloadIn(hello);expectType(helloIn,HELLO);if(!pairing.code().equals(helloIn.readUTF()))throw new IOException("pairing code mismatch");writeRecord(out,pairing.key(),challenge,payload(d->d.writeByte(HELLO_OK)));status(listener,"AUTHENTICATED","","");
        DataInputStream manifestIn=payloadIn(readRecord(in,pairing.key(),challenge));expectType(manifestIn,MANIFEST);int files=manifestIn.readInt();long total=manifestIn.readLong();if(files<0||files>limits.maxFiles||total<0||total>limits.maxTotalBytes)throw new IOException("manifest exceeds limit");writeRecord(out,pairing.key(),challenge,payload(d->d.writeByte(MANIFEST_OK)));
        int seen=0;long declared=0;while(true){DataInputStream frame=payloadIn(readRecord(in,pairing.key(),challenge));byte type=frame.readByte();if(type==ALL_END){if(seen!=files||declared!=total)throw new IOException("manifest count mismatch");writeRecord(out,pairing.key(),challenge,payload(d->d.writeByte(COMPLETE)));break;}if(type!=FILE)throw new IOException("unexpected record");String path=validatePath(readString(frame,limits.maxPathBytes),limits);long size=frame.readLong();if(size<0||size>limits.maxFileBytes||++seen>files||declared>limits.maxTotalBytes-size)throw new IOException("file exceeds limit");declared+=size;long offset=store.existingSize(path,size);if(offset<0||offset>size)throw new IOException("invalid local resume offset");long replyOffset=offset;writeRecord(out,pairing.key(),challenge,payload(d->{d.writeByte(OFFSET);d.writeLong(replyOffset);}));status(listener,"TRANSFERRING",path,"");long position=offset;try(OutputStream target=new BufferedOutputStream(store.open(path,offset))){while(true){DataInputStream data=payloadIn(readRecord(in,pairing.key(),challenge));byte dataType=data.readByte();if(dataType==FILE_END){long expected=data.readLong();if(expected!=size||position!=size)throw new IOException("incomplete file");target.flush();break;}if(dataType!=DATA)throw new IOException("unexpected data record");long frameOffset=data.readLong();int length=data.readInt();if(frameOffset!=position||length<1||length>BUFFER_BYTES||position>size-length)throw new IOException("invalid data offset");byte[] bytes=new byte[length];data.readFully(bytes);target.write(bytes);position+=length;progress(listener,path,position,size);}}
        status(listener,"VERIFYING",path,"");store.commit(path,size);writeRecord(out,pairing.key(),challenge,payload(d->d.writeByte(FILE_OK)));status(listener,"COMPLETED",path,"");}
      }catch(Exception e){status(listener,"FAILED","",e.getMessage()==null?"transfer failed":e.getMessage());}finally{clients.remove(socket);}}
    public synchronized boolean isRunning(){return server!=null&&!closed.get()&&!server.isClosed();}
    @Override public void close(){if(!closed.compareAndSet(false,true))return;ServerSocket current; synchronized(this){current=server;}closeQuietly(current);for(Socket socket:new ArrayList<>(clients))closeQuietly(socket);workers.shutdownNow();try{workers.awaitTermination(Math.max(1,limits.readTimeoutMillis),TimeUnit.MILLISECONDS);}catch(InterruptedException e){Thread.currentThread().interrupt();}if(acceptThread!=null)try{acceptThread.join(Math.max(1,limits.connectTimeoutMillis));}catch(InterruptedException e){Thread.currentThread().interrupt();}}
  }

  public static boolean isAllowedLanAddress(InetAddress address){if(address==null||address.isAnyLocalAddress()||address.isMulticastAddress())return false;if(address.isLoopbackAddress()||address.isLinkLocalAddress()||address.isSiteLocalAddress())return true;byte[] raw=address.getAddress();return raw.length==16&&(raw[0]&0xfe)==0xfc;}
  private static ThreadFactory namedFactory(String prefix){return new ThreadFactory(){int n;public synchronized Thread newThread(Runnable r){Thread t=new Thread(r,prefix+"-"+(++n));t.setDaemon(true);return t;}};}
  private static void validateManifestSummary(BoundedManifest manifest,Limits limits)throws IOException{if(manifest.fileCount()<0||manifest.fileCount()>limits.maxFiles||manifest.totalSize()<0||manifest.totalSize()>limits.maxTotalBytes)throw new IOException("manifest exceeds limit");}
  private static String validatePath(String path,Limits limits)throws IOException{if(path==null||path.isEmpty()||path.startsWith("/")||path.startsWith("\\")||path.indexOf('\0')>=0)throw new IOException("invalid relative path");byte[] raw=path.getBytes(StandardCharsets.UTF_8);if(raw.length>limits.maxPathBytes)throw new IOException("path too long");String normalized=path.replace('\\','/');for(String part:normalized.split("/",-1))if(part.isEmpty()||part.equals(".")||part.equals("..")||part.indexOf(':')>=0)throw new IOException("invalid relative path");return normalized;}
  private interface PayloadWriter{void write(DataOutputStream out)throws IOException;}
  private static byte[] payload(PayloadWriter writer)throws IOException{ByteArrayOutputStream bytes=new ByteArrayOutputStream();DataOutputStream out=new DataOutputStream(bytes);writer.write(out);out.flush();return bytes.toByteArray();}
  private static DataInputStream payloadIn(byte[] payload){return new DataInputStream(new ByteArrayInputStream(payload));}
  private static void writeRecord(DataOutputStream out,byte[] key,byte[] aad,byte[] plain)throws IOException,GeneralSecurityException{byte[] iv=new byte[IV_BYTES];RANDOM.nextBytes(iv);Cipher cipher=Cipher.getInstance("AES/GCM/NoPadding");cipher.init(Cipher.ENCRYPT_MODE,new SecretKeySpec(key,"AES"),new GCMParameterSpec(128,iv));if(aad!=null)cipher.updateAAD(aad);byte[] encrypted=cipher.doFinal(plain);if(encrypted.length>MAX_RECORD_BYTES)throw new IOException("record too large");out.write(iv);out.writeInt(encrypted.length);out.write(encrypted);out.flush();}
  private static byte[] readRecord(DataInputStream in,byte[] key,byte[] aad)throws IOException,GeneralSecurityException{byte[] iv=readFixed(in,IV_BYTES);int length=in.readInt();if(length<16||length>MAX_RECORD_BYTES)throw new IOException("invalid record length");byte[] encrypted=readFixed(in,length);Cipher cipher=Cipher.getInstance("AES/GCM/NoPadding");cipher.init(Cipher.DECRYPT_MODE,new SecretKeySpec(key,"AES"),new GCMParameterSpec(128,iv));if(aad!=null)cipher.updateAAD(aad);return cipher.doFinal(encrypted);}
  private static void expectType(byte[] payload,byte expected)throws IOException{expectType(payloadIn(payload),expected);}private static void expectType(DataInputStream in,byte expected)throws IOException{byte type=in.readByte();if(type==FAIL)throw new IOException(readString(in,4096));if(type!=expected)throw new IOException("unexpected protocol record");}
  private static byte[] readFixed(DataInputStream in,int length)throws IOException{byte[] out=new byte[length];in.readFully(out);return out;}
  private static int readSome(InputStream in,byte[] buffer,int wanted)throws IOException{int n=in.read(buffer,0,wanted);while(n==0)n=in.read(buffer,0,wanted);return n;}
  private static void writeString(DataOutputStream out,String value,int maxBytes)throws IOException{byte[] bytes=value.getBytes(StandardCharsets.UTF_8);if(bytes.length>maxBytes)throw new IOException("string too long");out.writeInt(bytes.length);out.write(bytes);}
  private static String readString(DataInputStream in,int maxBytes)throws IOException{int length=in.readInt();if(length<0||length>maxBytes)throw new IOException("invalid string length");return new String(readFixed(in,length),StandardCharsets.UTF_8);}
  private static void checkCancelled(Cancellation c)throws IOException{if(c!=null&&c.isCancelled())throw new IOException("transfer cancelled");}
  private static void status(Listener l,String phase,String path,String error){if(l!=null)l.onStatus(phase,path,error);}private static void progress(Listener l,String path,long done,long total){if(l!=null)l.onProgress(path,done,total);}
  private static void closeQuietly(Closeable value){if(value!=null)try{value.close();}catch(IOException ignored){}}
  private static boolean numericAddress(String value){if(value==null||value.isEmpty())return false;if(value.indexOf(':')>=0)return value.matches("[0-9A-Fa-f:.%]+");if(!value.matches("[0-9.]+"))return false;String[] parts=value.split("\\.",-1);if(parts.length!=4)return false;for(String part:parts)try{if(part.isEmpty()||Integer.parseInt(part)>255)return false;}catch(NumberFormatException e){return false;}return true;}
  private static String toHex(byte[] bytes){char[] table="0123456789abcdef".toCharArray(),out=new char[bytes.length*2];for(int i=0;i<bytes.length;i++){int v=bytes[i]&255;out[i*2]=table[v>>>4];out[i*2+1]=table[v&15];}return new String(out);}
  private static byte[] fromHex(String value){byte[] out=new byte[value.length()/2];for(int i=0;i<out.length;i++){int a=Character.digit(value.charAt(i*2),16),b=Character.digit(value.charAt(i*2+1),16);if(a<0||b<0)throw new IllegalArgumentException("invalid token");out[i]=(byte)((a<<4)|b);}return out;}
}