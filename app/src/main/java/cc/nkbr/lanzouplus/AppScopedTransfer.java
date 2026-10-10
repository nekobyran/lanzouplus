package cc.nkbr.lanzouplus;

import android.content.Context;
import android.net.Uri;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * App-scoped transfer bridge behind {@link PersistedDownloadDriver.EngineSeam}.
 *
 * <p>Owns one lazily created {@link LanzouCore} + {@link DirectLinkResolver} pair for the whole
 * app process and runs transfers with {@link SegmentDownloader}. It is only ever reached when no
 * {@link MainActivity} owns the engine (the driver refuses otherwise), so it can never be a
 * duplicate engine next to the Activity one, and it never starts an Activity.</p>
 *
 * <p>Every transfer attempt carries an attempt token, so a resolution or download that was paused,
 * cancelled or replaced can neither start a new segment nor report into the newer attempt.</p>
 */
final class AppScopedTransfer {
  private static final Object LOCK = new Object();
  private static LanzouCore core;
  private static DirectLinkResolver resolver;
  private static final ConcurrentHashMap<String, SegmentDownloader> RUNNING = new ConcurrentHashMap<>();
  private static final ConcurrentHashMap<String, DirectLinkResolver.Ticket> RESOLVING = new ConcurrentHashMap<>();
  private static final ConcurrentHashMap<String, Long> ATTEMPTS = new ConcurrentHashMap<>();
  private static final AtomicLong NEXT_ATTEMPT = new AtomicLong();

  private AppScopedTransfer() {}

  static void transfer(PersistedDownloadDriver.Request request, boolean resume,
      PersistedDownloadDriver.Callbacks callbacks) {
    Context context = appContext();
    if (context == null) {
      callbacks.fail("无法获取应用上下文");
      return;
    }
    String key = request.key();
    long attempt = NEXT_ATTEMPT.incrementAndGet();
    ATTEMPTS.put(key, attempt);
    Uri target = Uri.parse(request.uriString);
    if ((resume || !request.hasShareLink()) && request.hasCachedDirect()) {
      // Both resume and a retry without a share link reuse the persisted direct URL.
      startSegment(context, request, request.directUrl, target, callbacks, attempt);
      return;
    }
    if (!request.hasShareLink()) {
      clearAttempt(key, attempt);
      callbacks.fail("记录缺少蓝奏云链接，无法重新解析");
      return;
    }
    try {
      DirectLinkResolver direct = resolver(context);
      // resolve() reads the access code from its own cache, so the persisted password must be
      // handed to the resolver first instead of triggering an interactive password prompt.
      if (!request.password.isEmpty()) direct.rememberPassword(request.shareUrl, request.password);
      DirectLinkResolver.Ticket ticket = direct.resolve(request.shareUrl, true,
          new DirectLinkResolver.Callback() {
            @Override public void resolved(String directUrl, long resolvedAt, boolean cached) {
              if (!isAttempt(key, attempt)) return;
              RESOLVING.remove(key);
              // Persist the fresh direct URL so a later resume reuses it instead of re-resolving.
              DownloadHistoryCenter.updatePersistedDirect(context, request, directUrl, resolvedAt);
              startSegment(context, request, directUrl, target, callbacks, attempt);
            }

            @Override public void failed(String error) {
              if (!isAttempt(key, attempt)) return;
              RESOLVING.remove(key);
              clearAttempt(key, attempt);
              callbacks.fail(error);
            }

            @Override public void verificationRequired(String url) {
              // Interactive verification needs a foreground host; the driver refuses to fake one.
              if (!isAttempt(key, attempt)) return;
              RESOLVING.remove(key);
              clearAttempt(key, attempt);
              callbacks.fail("需要滑动验证，请在应用内继续该下载");
            }
          });
      // A cache hit resolves synchronously and has already started the segment; only remember a
      // ticket that is still the pending resolution of this attempt.
      if (ticket != null && isAttempt(key, attempt) && !RUNNING.containsKey(key)) {
        RESOLVING.put(key, ticket);
      }
    } catch (Exception error) {
      clearAttempt(key, attempt);
      callbacks.fail(error.getMessage());
    }
  }

  static void pause(PersistedDownloadDriver.Request request) {
    String key = request.key();
    ATTEMPTS.remove(key);
    DirectLinkResolver.Ticket ticket = RESOLVING.remove(key);
    if (ticket != null) ticket.cancel();
    SegmentDownloader downloader = RUNNING.get(key);
    if (downloader != null) {
      // Keep the entry until the downloader reports paused, so the stop is acknowledged once.
      downloader.pause();
    }
  }

  static void cancel(PersistedDownloadDriver.Request request) {
    String key = request.key();
    ATTEMPTS.remove(key);
    DirectLinkResolver.Ticket ticket = RESOLVING.remove(key);
    if (ticket != null) ticket.cancel();
    SegmentDownloader downloader = RUNNING.get(key);
    if (downloader != null) downloader.cancel();
  }

  private static void startSegment(Context context, PersistedDownloadDriver.Request request,
      String directUrl, Uri target, PersistedDownloadDriver.Callbacks callbacks, long attempt) {
    String key = request.key();
    if (!isAttempt(key, attempt)) return;
    SegmentDownloader downloader = new SegmentDownloader(context);
    SegmentDownloader previous = RUNNING.put(key, downloader);
    if (previous != null) previous.cancel();
    // Track the bytes the transfer really produced: completed() carries no size of its own.
    final long[] last = {Math.max(0, request.downloadedBytes), Math.max(0, request.totalBytes)};
    downloader.startResolved(directUrl, target, request.totalBytes, new SegmentDownloader.Listener() {
      @Override public void progress(long done, long total) {
        if (total > 0) last[1] = total;
        last[0] = Math.max(0, done);
        if (!isCurrent(key, downloader)) return;
        callbacks.progress(last[0], last[1]);
      }

      @Override public void completed() {
        if (!isCurrent(key, downloader)) return;
        RUNNING.remove(key, downloader);
        clearAttempt(key, attempt);
        callbacks.complete(last[0], last[1]);
      }

      @Override public void failed(String error) {
        if (!isCurrent(key, downloader)) return;
        RUNNING.remove(key, downloader);
        clearAttempt(key, attempt);
        callbacks.fail(error);
      }

      @Override public void paused(long done, long total) {
        if (total > 0) last[1] = total;
        last[0] = Math.max(0, done);
        if (!isCurrent(key, downloader)) return;
        RUNNING.remove(key, downloader);
        clearAttempt(key, attempt);
        callbacks.paused(last[0], last[1]);
      }

      @Override public void cancelled(long done, long total) {
        if (!isCurrent(key, downloader)) return;
        RUNNING.remove(key, downloader);
        clearAttempt(key, attempt);
        callbacks.cancelled(last[0], last[1]);
      }
    });
  }

  private static boolean isAttempt(String key, long attempt) {
    Long current = ATTEMPTS.get(key);
    return current != null && current == attempt;
  }

  private static boolean isCurrent(String key, SegmentDownloader downloader) {
    return RUNNING.get(key) == downloader;
  }

  private static void clearAttempt(String key, long attempt) {
    ATTEMPTS.remove(key, attempt);
  }

  private static Context appContext() { return ApplicationContextHolder.get(); }

  private static synchronized DirectLinkResolver resolver(Context context) {
    if (resolver == null) {
      core = new LanzouCore(context.getApplicationContext() == null ? context : context.getApplicationContext());
      resolver = new DirectLinkResolver(context, core);
    }
    return resolver;
  }

  /** Populated by {@link MainActivity#onCreate} so the driver can reach an app Context without an Activity owner. */
  static final class ApplicationContextHolder {
    private static volatile Context context;

    private ApplicationContextHolder() {}

    static void set(Context value) {
      if (value == null) return;
      context = value.getApplicationContext() == null ? value : value.getApplicationContext();
    }

    static Context get() { return context; }
  }
}
