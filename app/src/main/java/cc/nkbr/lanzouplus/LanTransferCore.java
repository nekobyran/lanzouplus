package cc.nkbr.lanzouplus;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.Inet4Address;
import java.net.Inet6Address;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketException;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.text.Normalizer;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.Iterator;
import java.util.Set;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Future;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/**
 * Android-independent, bounded LAN file-transfer protocol.
 *
 * <p>The wire format is: client magic/version, server 32-byte random challenge,
 * HMAC-SHA256(token, domain || challenge || pairing-code), manifest count/size,
 * then repeated path/size/SHA-256 metadata, receiver resume offset, bytes and a
 * final acknowledgement. Metadata and offsets carry nonce-bound HMACs; content
 * is bound by its SHA-256. The challenge is generated once per TCP session and
 * the server uses a strict, non-skippable state machine, so an old auth response
 * cannot start or alter another session. All variable input is length-prefixed and bounded.
 * Storage and source access are streams so Android callers can adapt SAF without
 * exposing java.nio.file.Path to this core.</p>
 */
public final class LanTransferCore {
  private static final int MAGIC = 0x4c5a5031; // LZP1
  private static final int VERSION = 1;
  private static final int OK = 0;
  private static final int REJECTED = 1;
  private static final int BAD_FILE = 2;
  private static final int HASH_MISMATCH = 3;
  private static final byte[] AUTH_DOMAIN = "lanzouplus-lan-transfer-v1\0".getBytes(StandardCharsets.US_ASCII);
  private static final SecureRandom RANDOM = new SecureRandom();
  private static final int BUFFER_SIZE = 64 * 1024;

  private LanTransferCore() {}

  public static final class Limits {
    public final int maxFiles;
    public final long maxTotalBytes;
    public final long maxFileBytes;
    public final int maxPathBytes;
    public final long maxMetadataBytes;
    public final int workerCount;
    public final int queueCapacity;
    public final int connectTimeoutMillis;
    public final int readTimeoutMillis;

    public Limits(int maxFiles, long maxTotalBytes, long maxFileBytes, int maxPathBytes,
        long maxMetadataBytes, int workerCount, int queueCapacity,
        int connectTimeoutMillis, int readTimeoutMillis) {
      if (maxFiles < 1 || maxTotalBytes < 0 || maxFileBytes < 0 || maxPathBytes < 1
          || maxMetadataBytes < 1 || workerCount < 1 || queueCapacity < 1
          || connectTimeoutMillis < 1 || readTimeoutMillis < 1) {
        throw new IllegalArgumentException("limits must be positive (byte totals may be zero)");
      }
      this.maxFiles = maxFiles;
      this.maxTotalBytes = maxTotalBytes;
      this.maxFileBytes = maxFileBytes;
      this.maxPathBytes = maxPathBytes;
      this.maxMetadataBytes = maxMetadataBytes;
      this.workerCount = workerCount;
      this.queueCapacity = queueCapacity;
      this.connectTimeoutMillis = connectTimeoutMillis;
      this.readTimeoutMillis = readTimeoutMillis;
    }

    public static Limits defaults() {
      // Mobile-safe defaults: 4096 files, 16 GiB total, 4 GiB/file, 2 MiB metadata,
      // three active sockets and eight queued sockets.
      return new Limits(4096, 16L * 1024 * 1024 * 1024, 4L * 1024 * 1024 * 1024,
          768, 2L * 1024 * 1024, 3, 8, 10_000, 30_000);
    }
  }

  public static final class Pairing {
    private final byte[] secret;
    private final String token;
    private final String code;

    private Pairing(byte[] secret, String token, String code) {
      this.secret = secret;
      this.token = token;
      this.code = code;
    }

    public static Pairing generate() {
      byte[] bytes = new byte[32];
      RANDOM.nextBytes(bytes);
      String token = encodeBase64Url(bytes);
      String code = String.format(java.util.Locale.ROOT, "%06d", RANDOM.nextInt(1_000_000));
      return new Pairing(bytes, token, code);
    }

    public static Pairing from(String token, String code) {
      if (token == null || code == null || !code.matches("[0-9]{6}")) {
        throw new IllegalArgumentException("invalid pairing credentials");
      }
      final byte[] secret;
      try {
        secret = decodeBase64Url(token);
      } catch (IllegalArgumentException e) {
        throw new IllegalArgumentException("invalid pairing token", e);
      }
      if (secret.length != 32 || !encodeBase64Url(secret).equals(token)) {
        throw new IllegalArgumentException("pairing token must be canonical 256-bit base64url");
      }
      return new Pairing(secret.clone(), token, code);
    }

    public static Pairing fromEncoded(String token, String code) { return from(token, code); }

    public String token() { return token; }
    public String code() { return code; }
    private byte[] secret() { return secret.clone(); }
    @Override public String toString() { return "Pairing{redacted}"; }
  }

  /** A compact copy/paste description. The pairing code is for human verification, never the sole secret. */
  public static final class ConnectionInfo {
    private static final int MAX_ENCODED_LENGTH = 512;
    public final Endpoint endpoint;
    public final Pairing pairing;

    public ConnectionInfo(Endpoint endpoint, Pairing pairing) {
      if (endpoint == null || pairing == null) throw new NullPointerException();
      if (endpoint.port() == 0) throw new IllegalArgumentException("connection port must be non-zero");
      this.endpoint = endpoint; this.pairing = pairing;
    }

    public String format() { return format(endpoint, pairing); }

    public static String format(Endpoint endpoint, Pairing pairing) {
      if (endpoint == null || pairing == null) throw new NullPointerException();
      if (endpoint.port() == 0) throw new IllegalArgumentException("connection port must be non-zero");
      String host = endpoint.address().getHostAddress();
      String encoded = "LZP1|" + host + "|" + endpoint.port() + "|" + pairing.token() + "|" + pairing.code();
      if (encoded.length() > MAX_ENCODED_LENGTH) throw new IllegalArgumentException("connection info too long");
      return encoded;
    }

    public static ConnectionInfo parse(String encoded) {
      if (encoded == null || encoded.length() < 1 || encoded.length() > MAX_ENCODED_LENGTH
          || !encoded.equals(encoded.trim())) {
        throw new IllegalArgumentException("invalid connection info");
      }
      String[] fields = encoded.split("\\|", -1);
      if (fields.length != 5 || !"LZP1".equals(fields[0]) || !isNumericAddress(fields[1])) {
        throw new IllegalArgumentException("invalid connection info");
      }
      final int port;
      try { port = Integer.parseInt(fields[2]); }
      catch (NumberFormatException e) { throw new IllegalArgumentException("invalid port", e); }
      if (port < 1 || port > 65535) throw new IllegalArgumentException("invalid port");
      try {
        InetAddress address = InetAddress.getByName(fields[1]);
        return new ConnectionInfo(Endpoint.of(address, port), Pairing.fromEncoded(fields[3], fields[4]));
      } catch (IOException e) {
        throw new IllegalArgumentException("invalid numeric address", e);
      }
    }

    private static boolean isNumericAddress(String value) {
      if (value == null || value.isEmpty() || value.indexOf('|') >= 0) return false;
      if (value.indexOf(':') >= 0) return value.matches("[0-9A-Fa-f:.%]+") && value.indexOf('.') < 0;
      if (!value.matches("[0-9.]+")) return false;
      String[] octets = value.split("\\.", -1);
      if (octets.length != 4) return false;
      for (String octet : octets) {
        if (octet.isEmpty() || (octet.length() > 1 && octet.charAt(0) == '0')) return false;
        try { if (Integer.parseInt(octet) > 255) return false; }
        catch (NumberFormatException e) { return false; }
      }
      return true;
    }
  }

  public static final class Endpoint {
    private final InetAddress address;
    private final int port;

    private Endpoint(InetAddress address, int port) { this.address = address; this.port = port; }

    public static Endpoint of(InetAddress address, int port) {
      if (!isAllowedLanAddress(address)) throw new IllegalArgumentException("endpoint is not a LAN address");
      if (port < 0 || port > 65535) throw new IllegalArgumentException("invalid port");
      return new Endpoint(address, port);
    }

    public InetAddress address() { return address; }
    public int port() { return port; }
    InetSocketAddress socketAddress() { return new InetSocketAddress(address, port); }
  }

  public interface SendFile {
    String relativePath();
    long size();
    InputStream open(long offset) throws IOException;
  }

  /** Iterable must be repeatable only within one transfer; entries are consumed one at a time. */
  public interface BoundedManifest extends Iterable<SendFile> {
    int fileCount();
    long totalSize();
  }

  public interface ReceiveStore {
    long existingSize(String relativePath, long declaredSize) throws IOException;
    /** Returns a new stream positioned at offset. Closing it must not publish the file. */
    OutputStream open(String relativePath, long offset) throws IOException;
    /** Returns a new stream positioned at zero and covering exactly the current partial file. */
    InputStream openForHash(String relativePath) throws IOException;
    void commit(String relativePath, long size, byte[] sha256) throws IOException;
    void deletePartial(String relativePath) throws IOException;
  }

  public interface Cancellation { boolean isCancelled(); }

  public static final class CancellationSource implements Cancellation {
    private final AtomicBoolean cancelled = new AtomicBoolean();
    private final Set<Socket> sockets = Collections.newSetFromMap(new ConcurrentHashMap<Socket, Boolean>());
    @Override public boolean isCancelled() { return cancelled.get(); }
    public void cancel() {
      if (!cancelled.compareAndSet(false, true)) return;
      for (Socket socket : sockets) closeQuietly(socket);
      sockets.clear();
    }
    private void register(Socket socket) {
      if (cancelled.get()) { closeQuietly(socket); return; }
      sockets.add(socket);
      if (cancelled.get() && sockets.remove(socket)) closeQuietly(socket);
    }
    private void unregister(Socket socket) { sockets.remove(socket); }
  }

  public enum Status { QUEUED, CONNECTING, AUTHENTICATING, TRANSFERRING, VERIFYING, COMPLETED, CANCELLED, FAILED }

  public static final class Event {
    public final Status status;
    public final String relativePath;
    public final long transferredBytes;
    public final long totalBytes;
    public final String error;
    Event(Status status, String relativePath, long transferredBytes, long totalBytes, String error) {
      this.status = status; this.relativePath = relativePath; this.transferredBytes = transferredBytes;
      this.totalBytes = totalBytes; this.error = error;
    }
  }

  public interface StatusListener { void onStatus(Event event); }

  public static final class TransferResult {
    public final Status status;
    public final int files;
    public final long bytes;
    public final String error;
    TransferResult(Status status, int files, long bytes, String error) {
      this.status = status; this.files = files; this.bytes = bytes; this.error = error;
    }
    public boolean succeeded() { return status == Status.COMPLETED; }
  }

  public static final class TransferHandle {
    private final CancellationSource cancellation;
    private final Future<TransferResult> future;
    TransferHandle(CancellationSource cancellation, Future<TransferResult> future) {
      this.cancellation = cancellation; this.future = future;
    }
    public void cancel() { cancellation.cancel(); future.cancel(true); }
    public Future<TransferResult> future() { return future; }
  }

  /** Multi-transfer client with a hard worker cap and bounded rejection queue. */
  public static final class Client implements AutoCloseable {
    private final Limits limits;
    private final ThreadPoolExecutor executor;
    private final Set<CancellationSource> activeTransfers =
        Collections.newSetFromMap(new ConcurrentHashMap<CancellationSource, Boolean>());

    public Client(Limits limits) {
      this.limits = requireLimits(limits);
      executor = boundedExecutor(limits.workerCount, limits.queueCapacity, "lan-client");
    }

    public TransferResult transfer(Endpoint endpoint, Pairing pairing, BoundedManifest manifest,
        Cancellation cancellation, StatusListener listener) {
      if (endpoint == null || pairing == null || manifest == null) throw new NullPointerException();
      Cancellation actual = cancellation == null ? new Cancellation() { public boolean isCancelled() { return false; } } : cancellation;
      return transferNow(endpoint, pairing, manifest, actual, listener, limits);
    }

    public TransferHandle submit(Endpoint endpoint, Pairing pairing, BoundedManifest manifest,
        StatusListener listener) {
      if (endpoint == null || pairing == null || manifest == null) throw new NullPointerException();
      CancellationSource cancellation = new CancellationSource();
      emit(listener, Status.QUEUED, null, 0, manifest == null ? 0 : manifest.totalSize(), null);
      activeTransfers.add(cancellation);
      try {
        Future<TransferResult> future = executor.submit(() -> {
          try { return transfer(endpoint, pairing, manifest, cancellation, listener); }
          finally { activeTransfers.remove(cancellation); }
        });
        return new TransferHandle(cancellation, future);
      } catch (RejectedExecutionException e) {
        activeTransfers.remove(cancellation);
        cancellation.cancel();
        emit(listener, Status.FAILED, null, 0, 0, "client queue full");
        throw e;
      }
    }

    @Override public void close() {
      for (CancellationSource cancellation : activeTransfers) cancellation.cancel();
      executor.shutdownNow();
    }
    public boolean awaitStopped(long timeout, TimeUnit unit) throws InterruptedException {
      return executor.awaitTermination(timeout, unit);
    }
  }

  /** Multi-client server. Rejected work closes the connection; work never runs on the accept thread. */
  public static final class Server implements AutoCloseable {
    private final Endpoint bind;
    private final Pairing pairing;
    private final ReceiveStore store;
    private final Limits limits;
    private final StatusListener listener;
    private final ThreadPoolExecutor acceptExecutor;
    private final ThreadPoolExecutor ioExecutor;
    private final Set<Socket> sockets = Collections.newSetFromMap(new ConcurrentHashMap<Socket, Boolean>());
    private final Set<String> activePaths = Collections.newSetFromMap(new ConcurrentHashMap<String, Boolean>());
    private final AtomicBoolean running = new AtomicBoolean();
    private volatile ServerSocket serverSocket;
    private volatile int localPort;

    public Server(Endpoint bind, Pairing pairing, ReceiveStore store, Limits limits, StatusListener listener) {
      if (bind == null || pairing == null || store == null) throw new NullPointerException();
      this.bind = bind; this.pairing = pairing; this.store = store; this.limits = requireLimits(limits);
      this.listener = listener;
      this.acceptExecutor = boundedExecutor(1, 1, "lan-accept");
      this.ioExecutor = boundedExecutor(limits.workerCount, limits.queueCapacity, "lan-server-io");
    }

    public synchronized void start() throws IOException {
      if (!running.compareAndSet(false, true)) throw new IllegalStateException("server already started");
      ServerSocket socket = new ServerSocket();
      try {
        socket.setReuseAddress(true);
        socket.bind(bind.socketAddress(), Math.min(128, limits.queueCapacity));
        serverSocket = socket;
        localPort = socket.getLocalPort();
        acceptExecutor.execute(this::acceptLoop);
      } catch (IOException | RuntimeException e) {
        running.set(false); closeQuietly(socket);
        acceptExecutor.shutdownNow(); ioExecutor.shutdownNow();
        throw e;
      }
    }

    public int port() { return localPort; }
    public int boundPort() { return localPort; }
    public Endpoint boundEndpoint() {
      if (!running.get() || localPort == 0) throw new IllegalStateException("server is not started");
      return Endpoint.of(bind.address(), localPort);
    }
    public boolean isRunning() { return running.get(); }

    private void acceptLoop() {
      while (running.get()) {
        Socket socket = null;
        try {
          socket = serverSocket.accept();
          if (!isAllowedLanAddress(socket.getInetAddress())) { closeQuietly(socket); continue; }
          socket.setSoTimeout(limits.readTimeoutMillis);
          sockets.add(socket);
          final Socket accepted = socket;
          try {
            ioExecutor.execute(() -> {
              try { receive(accepted); }
              finally { sockets.remove(accepted); closeQuietly(accepted); }
            });
          } catch (RejectedExecutionException full) {
            sockets.remove(socket); closeQuietly(socket);
          }
        } catch (SocketException e) {
          if (running.get()) emit(listener, Status.FAILED, null, 0, 0, "accept failed");
        } catch (IOException e) {
          if (running.get()) emit(listener, Status.FAILED, null, 0, 0, "accept failed");
          closeQuietly(socket);
        }
      }
    }

    private void receive(Socket socket) {
      String currentPath = null;
      try {
        DataInputStream in = new DataInputStream(new BufferedInputStream(socket.getInputStream()));
        DataOutputStream out = new DataOutputStream(new BufferedOutputStream(socket.getOutputStream()));
        if (in.readInt() != MAGIC || in.readUnsignedByte() != VERSION) throw new ProtocolException("bad protocol");
        byte[] nonce = new byte[32]; RANDOM.nextBytes(nonce);
        out.writeInt(MAGIC); out.writeByte(VERSION); out.write(nonce); out.flush();
        byte[] supplied = readFixed(in, 32);
        byte[] expected = authMac(pairing, nonce);
        if (!MessageDigest.isEqual(expected, supplied)) {
          out.writeInt(REJECTED); out.flush();
          emit(listener, Status.FAILED, null, 0, 0, "authentication rejected");
          return;
        }
        out.writeInt(OK); out.flush();
        int count = in.readInt();
        long declaredTotal = in.readLong();
        validateManifestHeader(count, declaredTotal, limits);
        Set<String> paths = new HashSet<String>(Math.min(count * 2 + 1, limits.maxFiles * 2));
        long metadataBytes = 0;
        long observedTotal = 0;
        long completedBytes = 0;
        for (int i = 0; i < count; i++) {
          byte[] pathBytes = readBytes(in, limits.maxPathBytes);
          metadataBytes = checkedAdd(metadataBytes, pathBytes.length + 8L + 64L, limits.maxMetadataBytes, "metadata too large");
          currentPath = decodeUtf8(pathBytes);
          validateRelativePath(currentPath, limits);
          if (!paths.add(currentPath)) throw new ProtocolException("duplicate path");
          if (!activePaths.add(currentPath)) {
            out.writeInt(BAD_FILE); out.flush();
            emit(listener, Status.FAILED, currentPath, 0, 0, "path is already being received");
            return;
          }
          try {
            long size = in.readLong();
            if (size < 0 || size > limits.maxFileBytes) throw new ProtocolException("file too large");
            observedTotal = checkedAdd(observedTotal, size, limits.maxTotalBytes, "total too large");
            byte[] expectedHash = readFixed(in, 32);
            byte[] suppliedFrameMac = readFixed(in, 32);
            if (!MessageDigest.isEqual(suppliedFrameMac,
                frameMac(pairing, nonce, i, pathBytes, size, expectedHash, -1L))) {
              throw new ProtocolException("metadata authentication failed");
            }
            long offset = store.existingSize(currentPath, size);
            if (offset < 0 || offset > size) throw new ProtocolException("invalid store offset");
            out.writeInt(OK); out.writeLong(offset);
            out.write(frameMac(pairing, nonce, i, pathBytes, size, expectedHash, offset)); out.flush();
            emit(listener, Status.TRANSFERRING, currentPath, offset, size, null);
            try (OutputStream target = new BufferedOutputStream(store.open(currentPath, offset))) {
              copyExact(in, target, size - offset, null, listener, currentPath, offset, size);
            }
            emit(listener, Status.VERIFYING, currentPath, size, size, null);
            byte[] actualHash;
            try (InputStream check = new BufferedInputStream(store.openForHash(currentPath))) {
              actualHash = hashExact(check, size, null);
            }
            if (!MessageDigest.isEqual(expectedHash, actualHash)) {
              try { store.deletePartial(currentPath); } catch (IOException ignored) {}
              out.writeInt(HASH_MISMATCH); out.flush();
              emit(listener, Status.FAILED, currentPath, size, size, "SHA-256 mismatch");
              return;
            }
            store.commit(currentPath, size, actualHash);
            completedBytes += size;
            out.writeInt(OK); out.flush();
          } finally {
            activePaths.remove(currentPath);
          }
        }
        if (observedTotal != declaredTotal) throw new ProtocolException("declared total mismatch");
        out.writeInt(OK); out.flush();
        emit(listener, Status.COMPLETED, null, completedBytes, declaredTotal, null);
      } catch (IOException | RuntimeException e) {
        if (running.get()) emit(listener, Status.FAILED, currentPath, 0, 0, safeError(e));
      }
    }

    public void stop() { stop(5, TimeUnit.SECONDS); }

    public void stop(long timeout, TimeUnit unit) {
      running.set(false);
      closeQuietly(serverSocket);
      for (Socket socket : sockets) closeQuietly(socket);
      sockets.clear();
      acceptExecutor.shutdownNow();
      ioExecutor.shutdownNow();
      try {
        long nanos = unit.toNanos(timeout);
        long start = System.nanoTime();
        acceptExecutor.awaitTermination(nanos, TimeUnit.NANOSECONDS);
        long remaining = Math.max(0, nanos - (System.nanoTime() - start));
        ioExecutor.awaitTermination(remaining, TimeUnit.NANOSECONDS);
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
      }
    }

    public boolean awaitStopped(long timeout, TimeUnit unit) throws InterruptedException {
      long nanos = unit.toNanos(timeout);
      long start = System.nanoTime();
      if (!acceptExecutor.awaitTermination(nanos, TimeUnit.NANOSECONDS)) return false;
      long remaining = Math.max(0, nanos - (System.nanoTime() - start));
      return ioExecutor.awaitTermination(remaining, TimeUnit.NANOSECONDS);
    }

    @Override public void close() { stop(); }
  }

  public static void validateRelativePath(String path, Limits limits) throws IOException {
    requireLimits(limits);
    if (path == null || path.isEmpty()) throw new ProtocolException("empty path");
    byte[] bytes = path.getBytes(StandardCharsets.UTF_8);
    if (bytes.length > limits.maxPathBytes) throw new ProtocolException("path too long");
    if (!Normalizer.isNormalized(path, Normalizer.Form.NFC)) throw new ProtocolException("non-canonical Unicode path");
    if (path.charAt(0) == '/' || path.indexOf('\\') >= 0 || path.indexOf('%') >= 0 || path.indexOf(':') >= 0) {
      throw new ProtocolException("ambiguous or absolute path");
    }
    for (int i = 0; i < path.length(); i++) {
      char c = path.charAt(i);
      if (c == 0 || c < 0x20 || c == 0x7f) throw new ProtocolException("control character in path");
    }
    String[] parts = path.split("/", -1);
    for (String part : parts) {
      if (part.isEmpty() || part.equals(".") || part.equals("..")) throw new ProtocolException("invalid path segment");
    }
    if (parts[0].length() >= 2 && Character.isLetter(parts[0].charAt(0)) && parts[0].charAt(1) == ':') {
      throw new ProtocolException("drive path rejected");
    }
  }

  public static boolean isAllowedLanAddress(InetAddress address) {
    if (address == null || address.isAnyLocalAddress() || address.isMulticastAddress()) return false;
    if (address.isLoopbackAddress() || address.isLinkLocalAddress() || address.isSiteLocalAddress()) return true;
    byte[] b = address.getAddress();
    if (address instanceof Inet4Address && b.length == 4) {
      int a = b[0] & 255, c = b[1] & 255;
      return a == 10 || (a == 172 && c >= 16 && c <= 31) || (a == 192 && c == 168) || (a == 169 && c == 254);
    }
    if (address instanceof Inet6Address && b.length == 16) return (b[0] & 0xfe) == 0xfc;
    return false;
  }

  private static TransferResult transferNow(Endpoint endpoint, Pairing pairing, BoundedManifest manifest,
      Cancellation cancellation, StatusListener listener, Limits limits) {
    Socket socket = new Socket();
    CancellationSource source = cancellation instanceof CancellationSource ? (CancellationSource) cancellation : null;
    int files = 0;
    long transferred = 0;
    try {
      validateManifestHeader(manifest.fileCount(), manifest.totalSize(), limits);
      checkCancelled(cancellation);
      emit(listener, Status.CONNECTING, null, 0, manifest.totalSize(), null);
      if (source != null) source.register(socket);
      socket.connect(endpoint.socketAddress(), limits.connectTimeoutMillis);
      socket.setSoTimeout(limits.readTimeoutMillis);
      DataOutputStream out = new DataOutputStream(new BufferedOutputStream(socket.getOutputStream()));
      DataInputStream in = new DataInputStream(new BufferedInputStream(socket.getInputStream()));
      emit(listener, Status.AUTHENTICATING, null, 0, manifest.totalSize(), null);
      out.writeInt(MAGIC); out.writeByte(VERSION); out.flush();
      if (in.readInt() != MAGIC || in.readUnsignedByte() != VERSION) throw new ProtocolException("bad server protocol");
      byte[] nonce = readFixed(in, 32);
      out.write(authMac(pairing, nonce)); out.flush();
      if (in.readInt() != OK) throw new ProtocolException("authentication rejected");
      out.writeInt(manifest.fileCount()); out.writeLong(manifest.totalSize());
      Iterator<SendFile> iterator = manifest.iterator();
      if (iterator == null) throw new ProtocolException("null manifest iterator");
      Set<String> paths = new HashSet<String>();
      long metadataBytes = 0;
      long observedTotal = 0;
      while (iterator.hasNext()) {
        checkCancelled(cancellation);
        if (files >= manifest.fileCount()) throw new ProtocolException("manifest count exceeded");
        SendFile file = iterator.next();
        if (file == null) throw new ProtocolException("null manifest entry");
        String path = file.relativePath();
        validateRelativePath(path, limits);
        if (!paths.add(path)) throw new ProtocolException("duplicate path");
        long size = file.size();
        if (size < 0 || size > limits.maxFileBytes) throw new ProtocolException("file too large");
        observedTotal = checkedAdd(observedTotal, size, limits.maxTotalBytes, "total too large");
        byte[] pathBytes = path.getBytes(StandardCharsets.UTF_8);
        metadataBytes = checkedAdd(metadataBytes, pathBytes.length + 8L + 64L, limits.maxMetadataBytes, "metadata too large");
        byte[] digest;
        try (InputStream hashInput = new BufferedInputStream(file.open(0))) { digest = hashExact(hashInput, size, cancellation); }
        writeBytes(out, pathBytes); out.writeLong(size); out.write(digest);
        out.write(frameMac(pairing, nonce, files, pathBytes, size, digest, -1L)); out.flush();
        if (in.readInt() != OK) throw new ProtocolException("file rejected");
        long offset = in.readLong();
        if (offset < 0 || offset > size) throw new ProtocolException("bad resume offset");
        byte[] offsetMac = readFixed(in, 32);
        if (!MessageDigest.isEqual(offsetMac, frameMac(pairing, nonce, files, pathBytes, size, digest, offset))) {
          throw new ProtocolException("resume offset authentication failed");
        }
        emit(listener, Status.TRANSFERRING, path, offset, size, null);
        try (InputStream content = new BufferedInputStream(file.open(offset))) {
          copyExact(content, out, size - offset, cancellation, listener, path, offset, size);
        }
        out.flush();
        emit(listener, Status.VERIFYING, path, size, size, null);
        int result = in.readInt();
        if (result == HASH_MISMATCH) throw new ProtocolException("SHA-256 mismatch");
        if (result != OK) throw new ProtocolException("file commit rejected");
        files++;
        transferred += size;
      }
      if (files != manifest.fileCount() || observedTotal != manifest.totalSize()) throw new ProtocolException("manifest declaration mismatch");
      out.flush();
      if (in.readInt() != OK) throw new ProtocolException("transfer rejected");
      emit(listener, Status.COMPLETED, null, transferred, manifest.totalSize(), null);
      return new TransferResult(Status.COMPLETED, files, transferred, null);
    } catch (CancelledException e) {
      emit(listener, Status.CANCELLED, null, transferred, manifest.totalSize(), null);
      return new TransferResult(Status.CANCELLED, files, transferred, null);
    } catch (IOException | RuntimeException e) {
      Status status = cancellation.isCancelled() ? Status.CANCELLED : Status.FAILED;
      String error = status == Status.CANCELLED ? null : safeError(e);
      emit(listener, status, null, transferred, manifest.totalSize(), error);
      return new TransferResult(status, files, transferred, error);
    } finally {
      if (source != null) source.unregister(socket);
      closeQuietly(socket);
    }
  }

  private static void validateManifestHeader(int count, long total, Limits limits) throws IOException {
    if (count < 0 || count > limits.maxFiles) throw new ProtocolException("too many files");
    if (total < 0 || total > limits.maxTotalBytes) throw new ProtocolException("total too large");
  }

  private static byte[] authMac(Pairing pairing, byte[] nonce) throws IOException {
    try {
      Mac mac = Mac.getInstance("HmacSHA256");
      mac.init(new SecretKeySpec(pairing.secret(), "HmacSHA256"));
      mac.update(AUTH_DOMAIN); mac.update(nonce); mac.update(pairing.code.getBytes(StandardCharsets.US_ASCII));
      return mac.doFinal();
    } catch (GeneralSecurityException e) {
      throw new IOException("HMAC unavailable", e);
    }
  }

  private static byte[] frameMac(Pairing pairing, byte[] nonce, int index, byte[] path,
      long size, byte[] digest, long offset) throws IOException {
    try {
      ByteArrayOutputStream bytes = new ByteArrayOutputStream(path.length + 96);
      DataOutputStream frame = new DataOutputStream(bytes);
      frame.write(AUTH_DOMAIN); frame.write(nonce); frame.writeInt(index);
      frame.writeInt(path.length); frame.write(path); frame.writeLong(size); frame.write(digest); frame.writeLong(offset);
      frame.flush();
      Mac mac = Mac.getInstance("HmacSHA256");
      mac.init(new SecretKeySpec(pairing.secret(), "HmacSHA256"));
      return mac.doFinal(bytes.toByteArray());
    } catch (GeneralSecurityException e) {
      throw new IOException("HMAC unavailable", e);
    }
  }

  private static final char[] BASE64_URL =
      "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789-_".toCharArray();

  private static String encodeBase64Url(byte[] input) {
    StringBuilder out = new StringBuilder((input.length * 4 + 2) / 3);
    for (int i = 0; i < input.length; i += 3) {
      int remaining = input.length - i;
      int value = (input[i] & 255) << 16;
      if (remaining > 1) value |= (input[i + 1] & 255) << 8;
      if (remaining > 2) value |= input[i + 2] & 255;
      out.append(BASE64_URL[(value >>> 18) & 63]).append(BASE64_URL[(value >>> 12) & 63]);
      if (remaining > 1) out.append(BASE64_URL[(value >>> 6) & 63]);
      if (remaining > 2) out.append(BASE64_URL[value & 63]);
    }
    return out.toString();
  }

  private static byte[] decodeBase64Url(String input) {
    if (input == null || input.indexOf('=') >= 0 || (input.length() & 3) == 1) {
      throw new IllegalArgumentException("invalid base64url");
    }
    byte[] output = new byte[input.length() * 6 / 8];
    int accumulator = 0, bits = 0, position = 0;
    for (int i = 0; i < input.length(); i++) {
      int value = base64UrlValue(input.charAt(i));
      if (value < 0) throw new IllegalArgumentException("invalid base64url");
      accumulator = (accumulator << 6) | value;
      bits += 6;
      if (bits >= 8) {
        bits -= 8;
        output[position++] = (byte) (accumulator >>> bits);
        accumulator &= (1 << bits) - 1;
      }
    }
    if (bits != 0 && accumulator != 0) throw new IllegalArgumentException("non-canonical base64url");
    return position == output.length ? output : Arrays.copyOf(output, position);
  }

  private static int base64UrlValue(char c) {
    if (c >= 'A' && c <= 'Z') return c - 'A';
    if (c >= 'a' && c <= 'z') return c - 'a' + 26;
    if (c >= '0' && c <= '9') return c - '0' + 52;
    if (c == '-') return 62;
    if (c == '_') return 63;
    return -1;
  }

  private static byte[] hashExact(InputStream in, long length, Cancellation cancellation) throws IOException {
    final MessageDigest digest;
    try { digest = MessageDigest.getInstance("SHA-256"); }
    catch (GeneralSecurityException e) { throw new IOException("SHA-256 unavailable", e); }
    byte[] buffer = new byte[BUFFER_SIZE];
    long remaining = length;
    while (remaining > 0) {
      if (cancellation != null) checkCancelled(cancellation);
      else if (Thread.currentThread().isInterrupted()) throw new CancelledException();
      int read = in.read(buffer, 0, (int) Math.min(buffer.length, remaining));
      if (read < 0) throw new EOFException("source shorter than declared");
      if (read == 0) continue;
      digest.update(buffer, 0, read); remaining -= read;
    }
    if (in.read() != -1) throw new IOException("source longer than declared");
    return digest.digest();
  }

  private static void copyExact(InputStream in, OutputStream out, long length, Cancellation cancellation,
      StatusListener listener, String path, long initial, long total) throws IOException {
    byte[] buffer = new byte[BUFFER_SIZE];
    long remaining = length, done = initial;
    while (remaining > 0) {
      if (cancellation != null) checkCancelled(cancellation);
      int read = in.read(buffer, 0, (int) Math.min(buffer.length, remaining));
      if (read < 0) throw new EOFException("stream shorter than declared");
      if (read == 0) continue;
      out.write(buffer, 0, read); remaining -= read; done += read;
      emit(listener, Status.TRANSFERRING, path, done, total, null);
    }
  }

  private static void checkCancelled(Cancellation cancellation) throws CancelledException {
    if (cancellation.isCancelled() || Thread.currentThread().isInterrupted()) throw new CancelledException();
  }

  private static byte[] readFixed(DataInputStream in, int length) throws IOException {
    byte[] bytes = new byte[length]; in.readFully(bytes); return bytes;
  }

  private static byte[] readBytes(DataInputStream in, int max) throws IOException {
    int length = in.readInt();
    if (length < 0 || length > max) throw new ProtocolException("field length out of bounds");
    return readFixed(in, length);
  }

  private static void writeBytes(DataOutputStream out, byte[] bytes) throws IOException {
    out.writeInt(bytes.length); out.write(bytes);
  }

  private static String decodeUtf8(byte[] bytes) throws IOException {
    try {
      return StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
          .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes)).toString();
    } catch (CharacterCodingException e) { throw new ProtocolException("invalid UTF-8"); }
  }

  private static long checkedAdd(long a, long b, long maximum, String error) throws IOException {
    if (b < 0 || a > maximum - b) throw new ProtocolException(error);
    return a + b;
  }

  private static Limits requireLimits(Limits limits) {
    if (limits == null) throw new NullPointerException("limits");
    return limits;
  }

  private static ThreadPoolExecutor boundedExecutor(int workers, int queue, String prefix) {
    ThreadPoolExecutor executor = new ThreadPoolExecutor(workers, workers, 30, TimeUnit.SECONDS,
        new ArrayBlockingQueue<Runnable>(queue), new NamedThreadFactory(prefix), new ThreadPoolExecutor.AbortPolicy());
    executor.allowCoreThreadTimeOut(true);
    return executor;
  }

  private static void emit(StatusListener listener, Status status, String path, long done, long total, String error) {
    if (listener == null) return;
    try { listener.onStatus(new Event(status, path, done, total, error)); }
    catch (RuntimeException ignored) {}
  }

  private static String safeError(Throwable error) {
    if (error instanceof ProtocolException) return error.getMessage();
    if (error instanceof EOFException) return "connection ended early";
    return error.getClass().getSimpleName();
  }

  private static void closeQuietly(java.io.Closeable closeable) {
    if (closeable == null) return;
    try { closeable.close(); } catch (IOException ignored) {}
  }

  private static final class ProtocolException extends IOException {
    ProtocolException(String message) { super(message); }
  }
  private static final class CancelledException extends IOException {}
  private static final class NamedThreadFactory implements ThreadFactory {
    private final String prefix; private final AtomicInteger sequence = new AtomicInteger();
    NamedThreadFactory(String prefix) { this.prefix = prefix; }
    @Override public Thread newThread(Runnable runnable) {
      Thread thread = new Thread(runnable, prefix + "-" + sequence.incrementAndGet());
      thread.setDaemon(true); return thread;
    }
  }
}
