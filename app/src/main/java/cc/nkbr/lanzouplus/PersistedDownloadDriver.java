package cc.nkbr.lanzouplus;

import android.content.Context;
import android.net.Uri;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.json.JSONArray;
import org.json.JSONObject;

/**
 * App-scoped retry/resume driver for download records whose owning {@link MainActivity} is gone.
 *
 * <p>When the main Activity is reclaimed the transfer engine dies with it and the persisted
 * {@code download_history/items} snapshot becomes the only state. The shared download-history popup
 * is still reachable from the Web page, so a failed/aborted record must stay actionable there.
 * This driver owns exactly those actions and never starts {@link MainActivity}: it is keyed by the
 * persisted record identity, runs at most one live job per record, and reports success back into
 * the same prefs snapshot so {@link DownloadHistoryCenter} keeps a single source of truth.</p>
 *
 * <p>Every reservation carries a generation. A callback from a job that was paused, cancelled,
 * replaced or yielded to {@link MainActivity} is stale and can neither rewrite the snapshot nor
 * release the newer reservation.</p>
 *
 * <p>The network/transfer mechanics are reached through {@link Seam}. Production uses
 * {@link EngineSeam}; tests inject a recording seam so persisted retry/resume can be proven
 * without a device and without a second engine being started while {@link MainActivity} is alive.</p>
 */
final class PersistedDownloadDriver {
  /** Result of one driver action. */
  static final int STARTED = 0, REJECTED_NO_METADATA = 1, REJECTED_DUPLICATE = 2,
      REJECTED_LIVE_OWNER = 3, REJECTED_NO_TARGET = 4, REJECTED_UNSUPPORTED = 5;

  /** Persisted state written while a driver job is running; Main must not re-import it as failed. */
  static final String STATE_DRIVER_RUNNING = "driver-running";

  /** One persisted request, re-read from the snapshot so no stale Activity state is required. */
  static final class Request {
    final String name, entryType, shareUrl, password, directUrl, uriString, parentUriString,
        sourceSizeText, batchTitle;
    final long createdAt, downloadedBytes, totalBytes, resolvedAt;
    final int percent;

    Request(String name, String entryType, String shareUrl, String password, String directUrl,
        String uriString, String parentUriString, String sourceSizeText, String batchTitle,
        long createdAt, long downloadedBytes, long totalBytes, long resolvedAt, int percent) {
      this.name = safe(name);
      this.entryType = safe(entryType).isEmpty() ? MainActivity.ENTRY_DOWNLOAD : entryType;
      this.shareUrl = safe(shareUrl);
      this.password = safe(password);
      this.directUrl = safe(directUrl);
      this.uriString = safe(uriString);
      this.parentUriString = safe(parentUriString);
      this.sourceSizeText = safe(sourceSizeText);
      this.batchTitle = safe(batchTitle);
      this.createdAt = createdAt;
      this.downloadedBytes = Math.max(0, downloadedBytes);
      this.totalBytes = Math.max(0, totalBytes);
      this.resolvedAt = resolvedAt;
      this.percent = Math.max(0, Math.min(100, percent));
    }

    private static String safe(String value) { return value == null ? "" : value; }

    /** Stable identity of the persisted record; matches {@link DownloadHistoryCenter}. */
    String key() { return createdAt + "|" + name + "|" + uriString; }

    boolean premium() { return MainActivity.ENTRY_PREMIUM_SAVE.equals(entryType); }
    boolean hasShareLink() { return !shareUrl.isEmpty(); }
    boolean hasCachedDirect() { return !directUrl.isEmpty(); }
    boolean hasTarget() { return !uriString.isEmpty(); }
    /** Retry needs the share link (fresh direct URL) or a cached direct URL plus a write target. */
    boolean retryable() { return hasTarget() && !premium() && (hasShareLink() || hasCachedDirect()); }
    /** Resume only re-uses the cached direct URL; it never re-resolves the share link. */
    boolean resumable() { return hasTarget() && !premium() && hasCachedDirect(); }
  }

  /** Transfer seam; production wires the real engine, tests record the request. */
  interface Seam {
    /**
     * Starts or continues the persisted transfer. Implementations must call exactly one terminal
     * {@link Callbacks#complete}/{@link Callbacks#fail}/{@link Callbacks#paused}/
     * {@link Callbacks#cancelled} and must be safe to call from a background thread.
     */
    void start(Request request, boolean resume, Callbacks callbacks);

    /** Asks a live transfer to stop and keep its partial file. */
    default void pause(Request request) {}

    /** Asks a live transfer to stop and discard its partial file. */
    default void cancel(Request request) {}
  }

  interface Callbacks {
    void progress(long downloadedBytes, long totalBytes);
    void complete(long downloadedBytes, long totalBytes);
    void fail(String error);
    default void paused(long downloadedBytes, long totalBytes) {}
    default void cancelled(long downloadedBytes, long totalBytes) {}
  }

  /** One live reservation; the generation makes callbacks from older jobs stale. */
  private static final class Job {
    final String key;
    final long generation;
    final Context context;
    final DownloadHistoryCenter.Row row;
    final Request request;

    Job(String key, long generation, Context context, DownloadHistoryCenter.Row row, Request request) {
      this.key = key;
      this.generation = generation;
      this.context = context;
      this.row = row;
      this.request = request;
    }
  }

  private static final Object LOCK = DownloadHistoryCenter.STATE_LOCK;
  private static final Map<String, Job> ACTIVE = new LinkedHashMap<>();
  private static long generation;
  private static volatile Seam seam;

  private PersistedDownloadDriver() {}

  static void setSeam(Seam value) { seam = value; }

  static Seam currentSeam() {
    Seam value = seam;
    return value == null ? EngineSeam.INSTANCE : value;
  }

  static boolean hasActiveJob(String key) {
    if (key == null || key.isEmpty()) return false;
    synchronized (LOCK) { return ACTIVE.containsKey(key); }
  }

  /** True while this persisted record is owned by a live driver job. */
  static boolean hasActiveJob(DownloadHistoryCenter.Row row) {
    Request request = requestOf(row);
    return request != null && hasActiveJob(request.key());
  }

  static List<String> activeKeys() {
    synchronized (LOCK) { return new ArrayList<>(ACTIVE.keySet()); }
  }

  static void resetForTests() {
    synchronized (LOCK) {
      ACTIVE.clear();
      generation = 0;
    }
    seam = null;
  }

  /** Retry = fresh resolution allowed; resume = cached direct URL only. */
  static int retry(Context context, DownloadHistoryCenter.Row row) { return run(context, row, false); }

  static int resume(Context context, DownloadHistoryCenter.Row row) { return run(context, row, true); }

  /** Pauses one live driver job; the partial file and the cached direct URL stay resumable. */
  static int pause(Context context, DownloadHistoryCenter.Row row) { synchronized (LOCK) {
    if (context == null || row == null || row.live) return REJECTED_LIVE_OWNER;
    Request request = requestOf(row);
    if (request == null) return REJECTED_NO_METADATA;
    // Drop ownership before signalling: any later callback from the stopped transfer is stale and
    // can neither rewrite this paused record nor release a newer reservation.
    Job job = takeActive(request.key());
    if (job == null) return REJECTED_DUPLICATE;
    try {
      currentSeam().pause(job.request);
    } catch (Exception ignored) {
    }
    DownloadHistoryCenter.markPersistedState(job.context, job.row, MainActivity.DOWNLOAD_PAUSED,
        "已暂停，点击继续");
    return STARTED;
  
    }
  }

  static int cancel(Context context, DownloadHistoryCenter.Row row) { synchronized (LOCK) {
    if (context == null || row == null || row.live) return REJECTED_LIVE_OWNER;
    Request request = requestOf(row);
    if (request == null) return REJECTED_NO_METADATA;
    // Drop ownership first: a late cancelled/failed callback from the stopped transfer is stale.
    Job job = takeActive(request.key());
    if (job == null) return REJECTED_DUPLICATE;
    try {
      currentSeam().cancel(job.request);
      DownloadHistoryCenter.markPersistedState(job.context, job.row, MainActivity.DOWNLOAD_CANCELLED,
          "已取消");
      return STARTED;
    } catch (Exception error) {
      DownloadHistoryCenter.markPersistedState(job.context, job.row, MainActivity.DOWNLOAD_FAILED,
          "取消失败：" + DownloadHistoryCenter.shortError(error));
      return REJECTED_UNSUPPORTED;
    }
  
    }
  }

  /** Drops any live driver ownership for a record that is about to be deleted. */
  static void forget(String key) {
    if (key == null) return;
    synchronized (LOCK) {
      Job job = ACTIVE.remove(key);
      if (job != null) currentSeam().cancel(job.request);
    }
  }

  /**
   * {@link MainActivity} is becoming the live engine owner again: every active driver job yields
   * the snapshot exactly once. Ownership is dropped before the stop is signalled, so no callback
   * from the yielded job can rewrite the snapshot afterwards, and Main is the only writer.
   */
  static void yieldToMain() { synchronized (LOCK) {
    List<Job> jobs;
    synchronized (LOCK) {
      if (ACTIVE.isEmpty()) return;
      jobs = new ArrayList<>(ACTIVE.values());
      ACTIVE.clear();
    }
    for (Job job : jobs) {
      try {
        currentSeam().pause(job.request);
      } catch (Exception ignored) {
      }
      DownloadHistoryCenter.markPersistedState(job.context, job.row, MainActivity.DOWNLOAD_PAUSED,
          "已暂停，点击继续");
    }
  
    }
  }

  /**
   * Keeps snapshot items still owned by an active driver job. {@link MainActivity} rewrites the
   * whole array from its in-memory entries; without this, a flush racing a live driver job would
   * clobber the driver's progress and freshly resolved direct URL.
   */
  static String preserveActiveItems(String persisted, String written) {
    Map<String, Job> active;
    synchronized (LOCK) {
      if (ACTIVE.isEmpty()) return written;
      active = new LinkedHashMap<>(ACTIVE);
    }
    try {
      JSONArray writtenItems = new JSONArray(written == null ? "[]" : written);
      JSONArray persistedItems = new JSONArray(persisted == null ? "[]" : persisted);
      Map<String, JSONObject> owned = new LinkedHashMap<>();
      for (int i = 0; i < persistedItems.length(); i++) {
        JSONObject item = persistedItems.optJSONObject(i);
        if (item == null) continue;
        String key = itemKey(item);
        if (active.containsKey(key)) owned.put(key, item);
      }
      JSONArray out = new JSONArray();
      for (int i = 0; i < writtenItems.length(); i++) {
        JSONObject item = writtenItems.optJSONObject(i);
        if (item == null) continue;
        JSONObject replacement = owned.get(itemKey(item));
        out.put(replacement == null ? item : replacement);
      }
      return out.toString();
    } catch (Exception ignored) {
      return written;
    }
  }

  private static int run(Context context, DownloadHistoryCenter.Row row, boolean resume) { synchronized (LOCK) {
    if (context == null || row == null || row.live) return REJECTED_LIVE_OWNER;
    Context app = context.getApplicationContext();
    if (app == null) app = context;
    Request request = requestOf(row);
    if (request == null) return REJECTED_NO_METADATA;
    if (request.premium()) return REJECTED_UNSUPPORTED;
    if (!request.hasTarget()) return REJECTED_NO_TARGET;
    if (resume ? !request.resumable() : !request.retryable()) return REJECTED_NO_METADATA;
    if (DownloadHistoryCenter.hasLiveOwner()) return REJECTED_LIVE_OWNER;
    Job job = newJob(request.key(), app, row, request);
    if (!reserve(job)) return REJECTED_DUPLICATE;
    AppScopedTransfer.ApplicationContextHolder.set(app);
    DownloadHistoryCenter.markPersistedState(app, row, STATE_DRIVER_RUNNING,
        resume ? "正在继续下载" : "正在重试下载");
    try {
      currentSeam().start(request, resume, guard(job));
      return STARTED;
    } catch (Exception error) {
      // The transfer never started: drop the reservation so the record stays retryable.
      release(job);
      DownloadHistoryCenter.markPersistedState(app, row, MainActivity.DOWNLOAD_FAILED,
          "重试失败：" + DownloadHistoryCenter.shortError(error));
      return REJECTED_UNSUPPORTED;
    }
  
    }
  }

  /** Wraps the seam callbacks so only the job that still owns the reservation may report. */
  private static Callbacks guard(final Job job) {
    return new Callbacks() {
      @Override public void progress(long downloadedBytes, long totalBytes) {
        synchronized (LOCK) {
          if (!isCurrent(job)) return;
          DownloadHistoryCenter.updatePersistedProgress(job.context, job.row, downloadedBytes, totalBytes);
        }
      }

      @Override public void complete(long downloadedBytes, long totalBytes) {
        if (!isCurrent(job)) return;
        settle(job, MainActivity.DOWNLOAD_COMPLETED, "", downloadedBytes, totalBytes);
      }

      @Override public void fail(String error) {
        if (!isCurrent(job)) return;
        settle(job, MainActivity.DOWNLOAD_FAILED,
            DownloadHistoryCenter.shortError(new java.io.IOException(error == null ? "" : error)),
            -1, -1);
      }

      @Override public void paused(long downloadedBytes, long totalBytes) {
        if (!isCurrent(job)) return;
        settle(job, MainActivity.DOWNLOAD_PAUSED, "已暂停，点击继续", downloadedBytes, totalBytes);
      }

      @Override public void cancelled(long downloadedBytes, long totalBytes) {
        if (!isCurrent(job)) return;
        settle(job, MainActivity.DOWNLOAD_CANCELLED, "已取消", -1, -1);
      }
    };
  }

  /** Writes the terminal state of the current job and drops its reservation exactly once. */
  private static void settle(Job job, String state, String error, long downloadedBytes, long totalBytes) {
    synchronized (LOCK) {
    if (!isCurrent(job)) return;
    try {
      DownloadHistoryCenter.markPersistedState(job.context, job.row, state, error);
      if (downloadedBytes >= 0 || totalBytes > 0) {
        DownloadHistoryCenter.updatePersistedProgress(job.context, job.row, downloadedBytes, totalBytes);
      }
    } finally {
      release(job);
    }
    }
  }

  static Request requestOf(DownloadHistoryCenter.Row row) {
    if (row == null) return null;
    return new Request(row.name, row.entryType, row.shareUrl, row.password, row.directUrl,
        row.uriString, row.parentUriString, row.sourceSizeText, row.batchTitle, row.createdAt,
        row.downloadedBytes, row.totalBytes, row.resolvedAt, row.percent);
  }

  private static Job newJob(String key, Context context, DownloadHistoryCenter.Row row, Request request) {
    synchronized (LOCK) { return new Job(key, ++generation, context, row, request); }
  }

  private static boolean reserve(Job job) {
    synchronized (LOCK) {
      if (ACTIVE.containsKey(job.key)) return false;
      ACTIVE.put(job.key, job);
      return true;
    }
  }

  private static Job takeActive(String key) {
    synchronized (LOCK) { return ACTIVE.remove(key); }
  }

  private static boolean isCurrent(Job job) {
    synchronized (LOCK) {
      Job current = ACTIVE.get(job.key);
      return current != null && current.generation == job.generation;
    }
  }

  private static void release(Job job) {
    synchronized (LOCK) {
      Job current = ACTIVE.get(job.key);
      if (current != null && current.generation == job.generation) ACTIVE.remove(job.key);
    }
  }

  private static String itemKey(JSONObject item) {
    return item.optLong("created", 0) + "|" + item.optString("name", "") + "|" + item.optString("uri", "");
  }

  /**
   * Production seam: hands the persisted request to the existing single-owner machinery
   * ({@link DirectLinkResolver} + {@link SegmentDownloader} + {@link TransferCoordinator}) through
   * an app-scoped holder, never through a hidden Activity and never as a second engine while
   * {@link MainActivity} is alive (the driver refuses to start in that case).
   */
  static final class EngineSeam implements Seam {
    static final EngineSeam INSTANCE = new EngineSeam();

    @Override public void start(Request request, boolean resume, Callbacks callbacks) {
      AppScopedTransfer.transfer(request, resume, callbacks);
    }

    @Override public void pause(Request request) { AppScopedTransfer.pause(request); }

    @Override public void cancel(Request request) { AppScopedTransfer.cancel(request); }
  }

  /** Validates that a persisted record really carries what a retry needs; used by the popup. */
  static boolean canRetry(DownloadHistoryCenter.Row row) {
    Request request = requestOf(row);
    return request != null && !row.live && !hasActiveJob(request.key()) && request.retryable();
  }

  static boolean canResume(DownloadHistoryCenter.Row row) {
    Request request = requestOf(row);
    return request != null && !row.live && !hasActiveJob(request.key()) && request.resumable();
  }

  static boolean canCancel(DownloadHistoryCenter.Row row) {
    return row != null && !row.live && hasActiveJob(row);
  }

  static Uri target(DownloadHistoryCenter.Row row) {
    Request request = requestOf(row);
    return request == null || !request.hasTarget() ? null : Uri.parse(request.uriString);
  }
}
