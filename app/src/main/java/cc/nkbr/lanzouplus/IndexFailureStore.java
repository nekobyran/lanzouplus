package cc.nkbr.lanzouplus;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.net.SocketTimeoutException;
import java.net.UnknownHostException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Persisted, detailed index-failure ledger.
 *
 * <p>Keyed by {@code sourceId + folder + page + stage} so a single retry can be
 * scoped to the exact unit that failed and a later success can remove exactly
 * that record. An append journal makes each observed failure durable; batched
 * SharedPreferences snapshots keep compaction separate from network work.</p>
 *
 * <p>Diagnostics are sanitized before they are written: URLs, passwords,
 * cookies, signs and tokens never reach the store. Callers must not record
 * cancellation as a failure.</p>
 */
final class IndexFailureStore {
  static final String PREFS = "v180_index_failures";
  static final String KEY = "items";
  static final String CHECKPOINT = "sequence";
  /**
   * Records are batched to disk instead of rewriting the whole ledger for every
   * single failure. There is deliberately no record cap: silently evicting real
   * failures is forbidden, so the ledger grows with the real number of failed
   * units and is bounded only by what actually failed.
   */
  private static final int FLUSH_BATCH = 32;

  private final SharedPreferences preferences;
  private final Context appContext;
  private final File journal;
  private static final Object LEDGER_LOCK = new Object();
  private static long revision;
  private final Object lock = LEDGER_LOCK;
  private final LinkedHashMap<String, IndexEvent.Failure> records = new LinkedHashMap<>();
  private boolean loaded;
  private long loadedRevision = -1L;
  /** Records written since the last durable commit; drives batching and {@link #flush()}. */
  private int dirtyRecords;
  private long sequence;

  IndexFailureStore(Context context) {
    appContext = context.getApplicationContext();
    preferences = appContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    journal = new File(appContext.getFilesDir(), "index-failures-v180.journal");
  }

  static String recordKey(IndexEvent.Failure failure) {
    return key(failure.sourceId, failure.folder, failure.page, failure.stage);
  }

  static String key(String sourceId, String folder, int page, String stage) {
    return (sourceId == null ? "" : sourceId) + '\u0001' + (folder == null ? "" : folder) + '\u0001' + page + '\u0001' + (stage == null ? "" : stage);
  }

  /** Insert or update one record; existing retry counters are preserved unless bumped. */
  void record(IndexEvent.Failure failure) {
    if (failure == null) return;
    IndexEvent.Failure previous;
    synchronized (lock) {
      ensureLoadedLocked();
      previous = records.get(recordKey(failure));
      int retries = Math.max(failure.retries, previous == null ? 0 : previous.retries);
      IndexEvent.Failure stored = new IndexEvent.Failure(failure.sourceId, failure.source, failure.folder, failure.stage, failure.page, retries, sanitizeReason(failure.reason), sanitizeCode(failure.errorCode), failure.retryable, failure.at > 0 ? failure.at : System.currentTimeMillis());
      appendLocked(stored);
      records.put(recordKey(stored), stored);
      // Batch: a burst of failures costs one ledger rewrite per FLUSH_BATCH
      // records instead of one per record. Callers open a durable boundary with
      // flush() when a run ends.
      if (++dirtyRecords >= FLUSH_BATCH) persistLocked();
    }
  }

  /** Removes exactly one unit after it succeeded. Returns true when a record existed. */
  boolean removeUnit(String sourceId, String folder, int page, String stage) {
    synchronized (lock) {
      ensureLoadedLocked();
      boolean removed = records.remove(key(sourceId, folder, page, stage)) != null;
      if (removed) persistLocked();
      return removed;
    }
  }

  /** Removes every record belonging to a source after a fully completed walk. */
  int removeSource(String sourceId) {
    if (sourceId == null || sourceId.isEmpty()) return 0;
    synchronized (lock) {
      ensureLoadedLocked();
      int removed = 0;
      java.util.Iterator<Map.Entry<String, IndexEvent.Failure>> iterator = records.entrySet().iterator();
      while (iterator.hasNext()) {
        if (sourceId.equals(iterator.next().getValue().sourceId)) {
          iterator.remove();
          removed++;
        }
      }
      if (removed > 0) persistLocked();
      return removed;
    }
  }

  int clear() {
    synchronized (lock) {
      ensureLoadedLocked();
      int size = records.size();
      records.clear();
      persistLocked();
      return size;
    }
  }

  List<IndexEvent.Failure> list() {
    synchronized (lock) {
      ensureLoadedLocked();
      return new ArrayList<>(records.values());
    }
  }

  List<IndexEvent.Failure> listForSource(String sourceId) {
    List<IndexEvent.Failure> out = new ArrayList<>();
    if (sourceId == null || sourceId.isEmpty()) return out;
    for (IndexEvent.Failure failure : list()) if (sourceId.equals(failure.sourceId)) out.add(failure);
    return out;
  }

  List<IndexEvent.Failure> listRetryable() {
    List<IndexEvent.Failure> out = new ArrayList<>();
    for (IndexEvent.Failure failure : list()) if (failure.retryable) out.add(failure);
    return out;
  }

  /**
   * Marks the given units as retried and returns the distinct source ids that
   * should be re-walked. Selected units whose source is already in the list are
   * still bumped so the UI can show the retry count.
   */
  List<String> markRetry(Collection<String> sourceIds) {
    List<String> out = new ArrayList<>();
    if (sourceIds == null || sourceIds.isEmpty()) return out;
    synchronized (lock) {
      ensureLoadedLocked();
      for (String sourceId : sourceIds) {
        if (sourceId == null || sourceId.isEmpty()) continue;
        boolean seen = false;
        for (Map.Entry<String, IndexEvent.Failure> entry : records.entrySet()) {
          IndexEvent.Failure failure = entry.getValue();
          if (!sourceId.equals(failure.sourceId)) continue;
          seen = true;
          entry.setValue(new IndexEvent.Failure(failure.sourceId, failure.source, failure.folder, failure.stage, failure.page, failure.retries + 1, failure.reason, failure.errorCode, failure.retryable, System.currentTimeMillis()));
        }
        if (seen) out.add(sourceId);
      }
      if (!out.isEmpty()) persistLocked();
    }
    return out;
  }

  int size() {
    synchronized (lock) {
      ensureLoadedLocked();
      return records.size();
    }
  }

  Set<String> sourceIds() {
    java.util.LinkedHashSet<String> ids = new java.util.LinkedHashSet<>();
    for (IndexEvent.Failure failure : list()) if (!failure.sourceId.isEmpty()) ids.add(failure.sourceId);
    return Collections.unmodifiableSet(ids);
  }

  static boolean retryable(Throwable error) {
    if (error == null) return true;
    if (error instanceof InterruptedException) return false;
    if (error instanceof SocketTimeoutException || error instanceof UnknownHostException) return true;
    String name = error.getClass().getSimpleName();
    return !("ShareCancelledException".equals(name) || "UserVerificationRequiredException".equals(name) || "DirectPasswordException".equals(name));
  }

  static String errorCode(Throwable error) {
    if (error == null) return "unknown";
    if (error instanceof SocketTimeoutException) return "timeout";
    if (error instanceof UnknownHostException) return "dns";
    if (error instanceof InterruptedException) return "cancelled";
    if (error instanceof java.io.FileNotFoundException) return "not-found";
    String name = error.getClass().getSimpleName();
    if (name == null || name.isEmpty()) return "io";
    StringBuilder out = new StringBuilder();
    for (int i = 0; i < name.length(); i++) {
      char value = name.charAt(i);
      if (Character.isUpperCase(value) && i > 0) out.append('-');
      out.append(Character.toLowerCase(value));
    }
    return out.length() > 48 ? out.substring(0, 48) : out.toString();
  }

  /** Strips URLs and credential-bearing parameters from a diagnostic message. */
  static String sanitizeReason(String reason) {
    if (reason == null) return "";
    String value = reason.replace('\r', ' ').replace('\n', ' ').replace('\t', ' ').trim();
    value = value.replaceAll("(?i)https?://[^\\s\"'<>)]*", "[url]");
    value = value.replaceAll("(?i)//[a-z0-9.-]*lanzou[a-z0-9.-]*\\.com[^\\s\"'<>)]*", "[url]");
    value = value.replaceAll("(?i)(pwd|password|passwd|cookie|sign|token|authorization|uk|key)=([^&\\s;,]+)", "$1=[redacted]");
    if (value.length() > 160) value = value.substring(0, 160) + "\u2026";
    return value;
  }

  static String sanitizeCode(String code) {
    if (code == null) return "";
    String value = code.replaceAll("[^A-Za-z0-9_-]", "");
    return value.length() > 48 ? value.substring(0, 48) : value;
  }

  private void ensureLoadedLocked() {
    if (loaded && loadedRevision == revision) return;
    dirtyRecords = 0;
    sequence = preferences.getLong(CHECKPOINT, 0L);
    records.clear();
    try {
      JSONArray values = new JSONArray(preferences.getString(KEY, "[]"));
      for (int i = 0; i < values.length(); i++) {
        JSONObject value = values.optJSONObject(i);
        if (value == null) continue;
        IndexEvent.Failure failure = new IndexEvent.Failure(value.optString("sourceId"), value.optString("source"), value.optString("folder"), value.optString("stage"), value.optInt("page"), value.optInt("retries"), sanitizeReason(value.optString("reason")), sanitizeCode(value.optString("errorCode")), value.optBoolean("retryable", true), value.optLong("at"));
        records.put(recordKey(failure), failure);
      }
    } catch (org.json.JSONException error) {
      throw new IllegalStateException("索引失败记录内容无效", error);
    }
    replayJournalLocked();
    loaded = true;
    loadedRevision = revision;
  }

  private static JSONObject toJson(IndexEvent.Failure failure) throws org.json.JSONException {
    return new JSONObject().put("sourceId", failure.sourceId).put("source", failure.source)
        .put("folder", failure.folder).put("stage", failure.stage).put("page", failure.page)
        .put("retries", failure.retries).put("reason", failure.reason).put("errorCode", failure.errorCode)
        .put("retryable", failure.retryable).put("at", failure.at);
  }

  private void appendLocked(IndexEvent.Failure failure) {
    try (FileOutputStream output = new FileOutputStream(journal, true)) {
      output.write((toJson(failure).put(CHECKPOINT, ++sequence).toString() + "\n").getBytes(StandardCharsets.UTF_8));
      loadedRevision = ++revision;
    } catch (IOException | org.json.JSONException error) {
      loaded = false;
      throw new IllegalStateException("索引失败记录追加失败", error);
    }
  }

  private void replayJournalLocked() {
    if (!journal.isFile()) return;
    try {
      byte[] bytes;
      try (FileInputStream input = new FileInputStream(journal);
           java.io.ByteArrayOutputStream output = new java.io.ByteArrayOutputStream()) {
        byte[] buffer = new byte[4096];
        for (int count; (count = input.read(buffer)) != -1;) output.write(buffer, 0, count);
        bytes = output.toByteArray();
      }
      String text = new String(bytes, StandardCharsets.UTF_8);
      int completeEnd = text.lastIndexOf('\n') + 1;
      long checkpoint = sequence;
      for (String line : text.substring(0, completeEnd).split("\n")) {
        if (line.isEmpty()) continue;
        JSONObject value = new JSONObject(line);
        long position = value.getLong(CHECKPOINT);
        sequence = Math.max(sequence, position);
        if (position <= checkpoint) continue;
        IndexEvent.Failure failure = new IndexEvent.Failure(value.getString("sourceId"), value.getString("source"),
            value.getString("folder"), value.getString("stage"), value.getInt("page"), value.getInt("retries"),
            sanitizeReason(value.getString("reason")), sanitizeCode(value.getString("errorCode")),
            value.getBoolean("retryable"), value.getLong("at"));
        records.put(recordKey(failure), failure);
        dirtyRecords++;
      }
      // A killed process can leave an unfinished final write. Keep complete rows
      // and remove only that unfinished tail before the next append.
      if (completeEnd != text.length()) {
        try (FileOutputStream output = new FileOutputStream(journal)) {
          output.write(text.substring(0, completeEnd).getBytes(StandardCharsets.UTF_8));
        }
      }
    } catch (IOException | org.json.JSONException error) {
      throw new IllegalStateException("索引失败记录恢复失败", error);
    }
  }

  private void persistLocked() {
    JSONArray values = new JSONArray();
    try {
      for (IndexEvent.Failure failure : records.values()) {
        values.put(toJson(failure));
      }
    } catch (Exception error) {
      throw new IllegalStateException("索引失败项序列化失败", error);
    }
    // commit(): the ledger must be intact for the next core process.
    if (!preferences.edit().putString(KEY, values.toString()).putLong(CHECKPOINT, sequence).commit()) throw new IllegalStateException("索引失败项写入失败");
    loadedRevision = ++revision;
    try (FileOutputStream ignored = new FileOutputStream(journal)) {
      // The complete snapshot is committed before its covered journal is reset.
    } catch (IOException error) {
      throw new IllegalStateException("索引失败记录整理失败", error);
    }
    dirtyRecords = 0;
  }

  /**
   * Durable boundary. Flushes any batched records to disk. Core index runs call
   * this when a source walk and when the whole run ends so the ledger is
   * complete after a process restart while still amortizing disk writes.
   */
  /**
   * One-time bounded import of the pre-1.8.0 title-keyed failure list.
   *
   * <p>The old UI persisted display labels, so a source id is unavailable; the label is used for
   * both fields and the record stays retryable so the user can still re-walk it. A non-empty ledger
   * is never overwritten and the legacy key is cleared after a successful import.</p>
   */
  int importLegacyFailures(java.util.Collection<IndexEvent.Failure> imported, String legacyPrefs, String legacyKey) {
    if (imported == null || imported.isEmpty()) return 0;
    int added = 0;
    synchronized (lock) {
      ensureLoadedLocked();
      if (!records.isEmpty()) return 0;
      for (IndexEvent.Failure failure : imported) {
        records.put(recordKey(failure), failure);
        added++;
      }
      if (added > 0) persistLocked();
    }
    if (added > 0) appContext.getSharedPreferences(legacyPrefs, Context.MODE_PRIVATE).edit().remove(legacyKey).commit();
    return added;
  }

  void flush() {
    synchronized (lock) {
      ensureLoadedLocked();
      if (dirtyRecords > 0) persistLocked();
    }
  }

  /** Records written since the last durable commit (test/diagnostic aid). */
  int pendingWrites() {
    synchronized (lock) {
      ensureLoadedLocked();
      return dirtyRecords;
    }
  }

  /** Host/smoke helper: fails fast when the ledger cannot be round-tripped. */
  void verifyPersistence() throws IOException {
    synchronized (lock) {
      ensureLoadedLocked();
      persistLocked();
    }
  }
}
