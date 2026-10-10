package cc.nkbr.lanzouplus;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.net.Uri;
import android.os.Build;
import android.os.Environment;
import android.provider.DocumentsContract;
import android.text.format.DateFormat;
import java.io.File;
import java.io.FileNotFoundException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import org.json.JSONArray;
import org.json.JSONObject;

/**
 * Single shared owner for download-history state and download-history actions.
 *
 * <p>The live transfer engine and its task list still belong to {@link MainActivity}; this class
 * only exposes that state to every host Activity (Web page, settings, download popup) without
 * starting {@link MainActivity} and without duplicating the transfer engine. When the main
 * Activity is not alive, the persisted {@code download_history/items} snapshot is authoritative
 * and record/file operations work on that snapshot directly.</p>
 */
final class DownloadHistoryCenter {
  static final Object STATE_LOCK = new Object();
  static final String PREFS = "download_history", KEY = "items";

  static final int ACTION_NONE = -1;
  static final int ACTION_OPEN = 0;
  static final int ACTION_PAUSE = 1;
  static final int ACTION_RESUME = 2;
  static final int ACTION_CANCEL = 3;
  static final int ACTION_RETRY = 4;
  static final int ACTION_DELETE_RECORD = 5;
  static final int ACTION_DELETE_FILE = 6;

  static final int RESULT_FAILED = -1, RESULT_MISSING = 0, RESULT_OK = 1, RESULT_PERMISSION = -2;

  private DownloadHistoryCenter() {}

  /** One immutable snapshot of a download-history record, live or persisted. */
  static final class Row {
    final MainActivity.DownloadEntry entry;
    final boolean live, premium;
    final String entryType, name, state, error, uriString, mimeType, sourceSizeText, batchTitle;
    final String shareUrl, password, directUrl, parentUriString;
    final long downloadedBytes, totalBytes, createdAt, completedAt, resolvedAt;
    final int percent;

    Row(MainActivity.DownloadEntry entry, boolean premium, String entryType, String name, String state, String error,
        String uriString, String mimeType, String sourceSizeText, String batchTitle,
        long downloadedBytes, long totalBytes, long createdAt, long completedAt, int percent) {
      this(entry, premium, entryType, name, state, error, uriString, mimeType, sourceSizeText, batchTitle,
          downloadedBytes, totalBytes, createdAt, completedAt, percent, "", "", "", "", 0L);
    }

    Row(MainActivity.DownloadEntry entry, boolean premium, String entryType, String name, String state, String error,
        String uriString, String mimeType, String sourceSizeText, String batchTitle,
        long downloadedBytes, long totalBytes, long createdAt, long completedAt, int percent,
        String shareUrl, String password, String directUrl, String parentUriString, long resolvedAt) {
      this.entry = entry;
      this.live = entry != null;
      this.premium = premium;
      this.entryType = entryType == null ? "" : entryType;
      this.name = name == null || name.trim().isEmpty() ? "未命名文件" : name;
      this.state = state == null ? "" : state;
      this.error = error == null ? "" : error;
      this.uriString = uriString == null ? "" : uriString;
      this.mimeType = mimeType == null ? "" : mimeType;
      this.sourceSizeText = sourceSizeText == null ? "" : sourceSizeText;
      this.batchTitle = batchTitle == null ? "" : batchTitle;
      this.downloadedBytes = Math.max(0, downloadedBytes);
      this.totalBytes = Math.max(0, totalBytes);
      this.createdAt = createdAt;
      this.completedAt = completedAt;
      this.percent = Math.max(0, Math.min(100, percent));
      this.shareUrl = shareUrl == null ? "" : shareUrl;
      this.password = password == null ? "" : password;
      this.directUrl = directUrl == null ? "" : directUrl;
      this.parentUriString = parentUriString == null ? "" : parentUriString;
      this.resolvedAt = resolvedAt;
    }

    boolean completed() { return MainActivity.DOWNLOAD_COMPLETED.equals(state) || MainActivity.SAVE_COMPLETED.equals(state); }
    boolean failed() { return MainActivity.DOWNLOAD_FAILED.equals(state) || MainActivity.SAVE_FAILED.equals(state); }
    boolean cancelled() { return MainActivity.DOWNLOAD_CANCELLED.equals(state) || MainActivity.SAVE_CANCELLED.equals(state); }
    boolean paused() {
      return MainActivity.DOWNLOAD_PAUSED.equals(state) || MainActivity.SAVE_PAUSED.equals(state)
          || MainActivity.SAVE_INTERRUPTED.equals(state);
    }
    boolean active() {
      if (!live) return PersistedDownloadDriver.hasActiveJob(this);
      return MainActivity.DOWNLOAD_WAITING.equals(state) || MainActivity.DOWNLOAD_RESOLVING.equals(state)
          || MainActivity.DOWNLOAD_RUNNING.equals(state) || MainActivity.SAVE_WAITING.equals(state)
          || MainActivity.SAVE_RUNNING.equals(state);
    }
    /** Primary single-tap action for this row, mirroring the download page tap semantics. */
    int primaryAction() {
      if (premium) {
        if (!live) return ACTION_NONE;
        if (paused()) return ACTION_RESUME;
        if (active()) return ACTION_PAUSE;
        return ACTION_NONE;
      }
      if (active()) return ACTION_PAUSE;
      if (paused()) return live || resumableRecord() ? ACTION_RESUME : retryableRecord() ? ACTION_RETRY : ACTION_NONE;
      if (completed()) return ACTION_OPEN;
      if (failed()) return live || retryableRecord() ? ACTION_RETRY : ACTION_NONE;
      return ACTION_NONE;
    }

    private boolean resumableRecord() { return PersistedDownloadDriver.canResume(this); }
    private boolean retryableRecord() { return PersistedDownloadDriver.canRetry(this); }

    boolean canCancel() {
      if (premium) return false;
      if (live) return active() || MainActivity.DOWNLOAD_PAUSED.equals(state);
      return PersistedDownloadDriver.canCancel(this);
    }

    boolean canRetry() {
      return !premium && (live ? MainActivity.DOWNLOAD_FAILED.equals(state)
          : !active() && (failed() || paused() || cancelled()) && retryableRecord());
    }
    boolean canDeleteFile() { return !premium && !uriString.isEmpty(); }
    boolean canResume() { return !premium && paused() && (live || resumableRecord()); }
    boolean canPauseResume() {
      int action = primaryAction();
      return (live || resumableRecord() || active()) && (action == ACTION_PAUSE || action == ACTION_RESUME);
    }
  }

  static MainActivity liveOwner() {
    MainActivity owner = MainActivity.ACTIVE_OWNER;
    if (owner != null && !owner.isFinishing() && !owner.isDestroyed()) return owner;
    MainActivity fallback = MainActivity.ACTIVE_INSTANCE.get();
    if (fallback != null && !fallback.isFinishing() && !fallback.isDestroyed()) return fallback;
    return null;
  }

  static boolean hasLiveOwner() { return liveOwner() != null; }

  /** Newest-first rows from live state when the main Activity owns the engine, else from prefs. */
  static List<Row> rows(Context context) {
    MainActivity owner = liveOwner();
    List<Row> out = new ArrayList<>();
    if (owner != null) {
      for (MainActivity.DownloadEntry entry : new ArrayList<>(owner.downloadEntries)) {
        if (entry == null) continue;
        out.add(liveRow(entry));
      }
      Collections.reverse(out);
      return out;
    }
    if (context == null) return out;
    for (JSONObject item : parsed(context)) out.add(persistedRow(item));
    Collections.reverse(out);
    return out;
  }

  private static Row liveRow(MainActivity.DownloadEntry entry) {
    synchronized (entry) {
      boolean premium = MainActivity.isPremiumSaveEntry(entry);
      return new Row(entry, premium, entry.entryType, entry.name, entry.state, entry.error, entry.uriString,
          entry.mimeType, entry.sourceSizeText, entry.batchTitle, entry.downloadedBytes, entry.totalBytes,
          entry.createdAt, entry.completedAt, entry.percent, entry.shareUrl, entry.password, entry.directUrl,
          entry.parentUriString, entry.resolvedAt);
    }
  }

  private static Row persistedRow(JSONObject item) {
    String type = item.optString("type", MainActivity.ENTRY_DOWNLOAD);
    boolean premium = MainActivity.ENTRY_PREMIUM_SAVE.equals(type);
    String state = item.optString("state", premium ? MainActivity.SAVE_FAILED : MainActivity.DOWNLOAD_FAILED);
    String error = item.optString("error", "");
    if (!premium && (MainActivity.DOWNLOAD_WAITING.equals(state) || MainActivity.DOWNLOAD_RESOLVING.equals(state)
        || MainActivity.DOWNLOAD_RUNNING.equals(state) || PersistedDownloadDriver.STATE_DRIVER_RUNNING.equals(state))) {
      if (PersistedDownloadDriver.STATE_DRIVER_RUNNING.equals(state)
          && PersistedDownloadDriver.hasActiveJob(recordKey(item))) {
        // The app-scoped driver still owns this record; it stays active until the job settles.
      } else {
        // Persisted record left behind by a finished process (or a driver job that died with it):
        // it is no longer transferable as a live task, but it stays retryable from the snapshot.
        state = MainActivity.DOWNLOAD_FAILED;
        if (error.isEmpty()) error = "下载中断，点击继续";
      }
    } else if (premium && (MainActivity.SAVE_WAITING.equals(state) || MainActivity.SAVE_RUNNING.equals(state))) {
      state = MainActivity.SAVE_PAUSED;
      if (error.isEmpty()) error = "保存中断，点击继续";
    }
    long total = item.optLong("totalBytes", 0);
    long done = item.optLong("doneBytes", item.optLong("downloaded", 0));
    int percent = item.optInt("percent", total > 0 ? (int) Math.min(100, done * 100 / total) : 0);
    return new Row(null, premium, type, item.optString("name", ""), state, error, item.optString("uri", ""),
        item.optString("mime", ""), item.optString("sourceSize", ""), item.optString("batchTitle", ""),
        done, total, item.optLong("created", 0), item.optLong("completed", 0), percent,
        item.optString("share", ""), item.optString("password", ""), item.optString("direct", ""),
        item.optString("parent", ""), item.optLong("resolvedAt", item.optLong("resolved", 0)));
  }

  /** Persisted-record identity shared by the driver, the popup and the snapshot writer. */
  static String recordKey(JSONObject item) {
    return item == null ? "" : item.optLong("created", 0) + "|" + item.optString("name", "")
        + "|" + item.optString("uri", "");
  }

  private static List<JSONObject> parsed(Context context) {
    List<JSONObject> out = new ArrayList<>();
    try {
      JSONArray array = new JSONArray(prefs(context).getString(KEY, "[]"));
      for (int i = 0; i < array.length(); i++) {
        JSONObject item = array.optJSONObject(i);
        if (item != null && !item.optString("name", "").isEmpty()) out.add(item);
      }
    } catch (Exception ignored) {
    }
    return out;
  }

  private static SharedPreferences prefs(Context context) {
    return context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
  }

  /** Human readable display state, honest about records that lost their live task. */
  static String displayState(Row row) {
    if (row == null) return "";
    if (row.active() && !row.live) return MainActivity.DOWNLOAD_RUNNING;
    if (!row.live && !row.premium && row.failed() && !row.error.isEmpty()) return "已中断";
    if (!row.live && row.premium && row.paused()) return "保存已暂停";
    return row.state;
  }

  static String metrics(Row row) {
    if (row == null) return "";
    String total = row.totalBytes > 0 ? formatSize(row.totalBytes)
        : (row.sourceSizeText.isEmpty() ? "大小未知" : row.sourceSizeText);
    String size = formatSize(row.downloadedBytes) + " / " + total;
    if (row.premium) {
      String progress = "保存进度 " + row.percent + "%";
      if (row.active()) return "保存中 · " + progress + "\n" + firstNonEmpty(row.error, "任务进行中");
      if (row.paused()) return "保存已暂停 · " + progress + "\n" + firstNonEmpty(row.error, "再次点击继续");
      if (row.completed()) return "保存完成 · " + progress + "\n完成 " + moment(row.completedAt);
      if (row.cancelled()) return "保存已取消 · " + progress;
      return "保存失败 · " + progress + (row.error.isEmpty() ? "" : " · " + row.error);
    }
    if (row.active()) {
      if (MainActivity.DOWNLOAD_RESOLVING.equals(row.state)) return "解析中 · " + total + "\n创建 " + moment(row.createdAt);
      return "下载中 · " + row.percent + "% · " + size + "\n" + firstNonEmpty(row.error, "正在传输");
    }
    if (MainActivity.DOWNLOAD_PAUSED.equals(row.state)) return "已暂停 · " + row.percent + "% · " + size + "\n再次点击继续";
    if (row.completed()) return "下载完成 · " + size + "\n完成 " + moment(row.completedAt);
    if (row.cancelled()) return "已取消 · " + size + "\n创建 " + moment(row.createdAt);
    if (row.failed()) return "失败" + (row.error.isEmpty() ? "" : " · " + row.error) + " · " + size + "\n创建 " + moment(row.createdAt);
    return row.state + " · " + size + "\n创建 " + moment(row.createdAt);
  }

  static String firstNonEmpty(String first, String fallback) {
    return first == null || first.trim().isEmpty() ? fallback : first;
  }

  static String moment(long time) {
    if (time <= 0) return "时间未知";
    try {
      return DateFormat.format("yyyy-MM-dd HH:mm", time).toString();
    } catch (Exception ignored) {
      return "时间未知";
    }
  }

  static String formatSize(long bytes) {
    if (bytes <= 0) return "0 B";
    String[] units = {"B", "KB", "MB", "GB", "TB"};
    double value = bytes;
    int unit = 0;
    while (value >= 1024 && unit < units.length - 1) {
      value /= 1024;
      unit++;
    }
    return String.format(Locale.ROOT, value >= 100 ? "%.0f %s" : "%.1f %s", value, units[unit]);
  }

  static int fallbackIcon(String name, String mimeType) {
    String ext = extensionOf(name);
    String mime = mimeType == null ? "" : mimeType.toLowerCase(Locale.ROOT);
    switch (ext) {
      case "apk": case "apks": case "apkm": case "xapk": case "aab": return R.drawable.ic_install;
      case "mp4": case "mkv": case "avi": case "mov": case "webm": case "m4v": case "3gp": case "ts": return R.drawable.ic_play;
      case "mp3": case "flac": case "m4a": case "aac": case "ogg": case "opus": case "wav": case "ape": case "wma": return R.drawable.ic_audio;
      case "png": case "jpg": case "jpeg": case "webp": case "gif": case "bmp": case "heic": case "heif": case "avif": return R.drawable.ic_image;
      case "pdf": return R.drawable.ic_pdf;
      case "zip": case "rar": case "7z": case "tar": case "gz": case "xz": case "zst": case "tgz": case "iso": return R.drawable.ic_archive;
      case "txt": case "md": case "log": case "csv": case "json": case "xml": case "doc": case "docx": case "xls": case "xlsx": case "ppt": case "pptx": case "epub": return R.drawable.ic_document;
      default:
        if (mime.startsWith("image/")) return R.drawable.ic_image;
        if (mime.startsWith("video/")) return R.drawable.ic_play;
        if (mime.startsWith("audio/")) return R.drawable.ic_audio;
        if (mime.equals("application/pdf")) return R.drawable.ic_pdf;
        if (mime.equals("application/vnd.android.package-archive")) return R.drawable.ic_install;
        if (mime.contains("zip") || mime.contains("rar") || mime.contains("compressed") || mime.contains("tar")) return R.drawable.ic_archive;
        if (mime.startsWith("text/") || mime.contains("document") || mime.contains("sheet") || mime.contains("presentation")) return R.drawable.ic_document;
        return R.drawable.ic_file;
    }
  }

  static String extensionOf(String name) {
    String value = name == null ? "" : name.trim();
    int slash = Math.max(value.lastIndexOf('/'), value.lastIndexOf('\\'));
    int dot = value.lastIndexOf('.');
    if (dot <= slash || dot >= value.length() - 1) return "";
    String ext = value.substring(dot + 1).toLowerCase(Locale.ROOT);
    return ext.matches("[a-z0-9]{1,12}") ? ext : "";
  }

  /** Executes one shared action. Returns false when the action is unavailable for this row. */
  static boolean perform(Activity host, Row row, int action) {
    if (row == null || action == ACTION_NONE) return false;
    MainActivity owner = row.live ? liveOwner() : null;
    if (row.live && owner == null) return false;
    Context context = host != null ? host.getApplicationContext() : null;
    if (context == null) context = AppScopedTransfer.ApplicationContextHolder.get();
    switch (action) {
      case ACTION_OPEN:
        if (row.live && !row.premium) { owner.installEntry(row.entry); return true; }
        if (row.live) return false;
        return openPersisted(host, row);
      case ACTION_PAUSE:
        if (row.live) {
          if (row.premium) { owner.pausePremiumSave(row.entry); return true; }
          owner.pauseDownload(row.entry);
          return true;
        }
        if (row.premium || context == null) return false;
        return PersistedDownloadDriver.pause(context, row) == PersistedDownloadDriver.STARTED;
      case ACTION_RESUME:
        if (row.live) {
          if (row.premium) { owner.resumePremiumSave(row.entry); return true; }
          owner.resumeDownload(row.entry);
          return true;
        }
        if (row.premium || context == null) return false;
        return PersistedDownloadDriver.resume(context, row) == PersistedDownloadDriver.STARTED;
      case ACTION_CANCEL:
        if (!row.canCancel()) return false;
        if (row.live) { owner.cancelDownload(row.entry); return true; }
        return context != null && PersistedDownloadDriver.cancel(context, row) == PersistedDownloadDriver.STARTED;
      case ACTION_RETRY:
        if (!row.canRetry()) return false;
        if (row.live) { owner.requestRetryDownload(row.entry); return true; }
        return context != null && PersistedDownloadDriver.retry(context, row) == PersistedDownloadDriver.STARTED;
      case ACTION_DELETE_RECORD:
        PersistedDownloadDriver.forget(row.createdAt + "|" + row.name + "|" + row.uriString);
        return row.live ? deleteLiveRecord(owner, row) : deletePersistedRecord(host, row);
      case ACTION_DELETE_FILE:
        PersistedDownloadDriver.forget(row.createdAt + "|" + row.name + "|" + row.uriString);
        return row.live ? deleteLiveFile(owner, row) : deletePersistedFile(host, row);
      default:
        return false;
    }
  }

  /** Persisted-only writes used by {@link PersistedDownloadDriver}; never touches a live entry. */
  static boolean markPersistedState(Context context, Row row, String state, String error) {
    return updatePersisted(context, row, state, error, -1, -1, true);
  }

  static boolean updatePersistedProgress(Context context, Row row, long downloadedBytes, long totalBytes) {
    return updatePersisted(context, row, MainActivity.DOWNLOAD_RUNNING, "", downloadedBytes, totalBytes, false);
  }

  /**
   * Records a freshly resolved direct URL into the snapshot so a later resume (in the popup or in
   * {@link MainActivity}) reuses it instead of re-resolving the share link.
   */
  static boolean updatePersistedDirect(Context context, PersistedDownloadDriver.Request request,
      String directUrl, long resolvedAt) { synchronized (STATE_LOCK) {
    if (context == null || request == null || directUrl == null || directUrl.isEmpty()) return false;
    try {
      JSONArray source = new JSONArray(prefs(context).getString(KEY, "[]"));
      for (int i = 0; i < source.length(); i++) {
        JSONObject item = source.optJSONObject(i);
        if (item == null || !matches(item, request)) continue;
        item.put("direct", directUrl);
        item.put("resolvedAt", resolvedAt);
        return prefs(context).edit().putString(KEY, source.toString()).commit();
      }
    } catch (Exception ignored) {
    }
    return false;
  
    }
  }

  private static boolean updatePersisted(Context context, Row row, String state, String error,
      long downloadedBytes, long totalBytes, boolean writeState) { synchronized (STATE_LOCK) {
    if (context == null || row == null) return false;
    try {
      JSONArray source = new JSONArray(prefs(context).getString(KEY, "[]"));
      for (int i = 0; i < source.length(); i++) {
        JSONObject item = source.optJSONObject(i);
        if (item == null || !matches(item, row)) continue;
        if (writeState) {
          item.put("state", state == null ? "" : state);
          item.put("error", error == null ? "" : error);
          if (MainActivity.DOWNLOAD_COMPLETED.equals(state)) item.put("completed", System.currentTimeMillis());
        }
        if (downloadedBytes >= 0) item.put("doneBytes", downloadedBytes);
        if (totalBytes > 0) item.put("totalBytes", totalBytes);
        long total = item.optLong("totalBytes", 0);
        long done = item.optLong("doneBytes", 0);
        item.put("percent", MainActivity.DOWNLOAD_COMPLETED.equals(item.optString("state")) ? 100
            : total > 0 ? (int) Math.min(100, done * 100 / total) : 0);
        return prefs(context).edit().putString(KEY, source.toString()).commit();
      }
    } catch (Exception ignored) {
    }
    return false;
  
    }
  }

  /** Short, non-diagnostic error text: never leaks URLs, passwords or cookies. */
  static String shortError(Exception error) {
    String message = error == null || error.getMessage() == null ? "" : error.getMessage();
    message = message.replaceAll("(?i)https?://[^\\s\"'<>)]*", "[url]")
        .replaceAll("(?i)(pwd|password|passwd|cookie|sign|token|authorization|uk|key)=([^&\\s;,]+)", "$1=[redacted]")
        .replace('\r', ' ').replace('\n', ' ').trim();
    return message.length() > 120 ? message.substring(0, 120) + "…" : message;
  }

  private static boolean openPersisted(Activity host, Row row) {
    if (host == null || row.uriString.isEmpty()) return false;
    try {
      Uri uri = Uri.parse(row.uriString);
      Intent intent = new Intent(Intent.ACTION_VIEW);
      intent.setDataAndType(uri, persistedMime(row));
      intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
      host.startActivity(intent);
      return true;
    } catch (Exception error) {
      return false;
    }
  }

  private static String persistedMime(Row row) {
    String mime = row.mimeType == null ? "" : row.mimeType.trim();
    if (!mime.isEmpty() && !mime.equalsIgnoreCase("application/octet-stream")) return mime;
    String ext = extensionOf(row.name);
    if (ext.isEmpty()) return "*/*";
    String mapped = android.webkit.MimeTypeMap.getSingleton().getMimeTypeFromExtension(ext);
    return mapped == null || mapped.trim().isEmpty() ? "*/*" : mapped;
  }

  private static boolean deleteLiveRecord(MainActivity owner, Row row) {
    if (row.premium) {
      owner.premiumSaveRevisions.remove(row.entry);
      owner.downloadEntries.remove(row.entry);
      owner.persistDownloadHistory();
      refreshDownloadPage(owner);
      return true;
    }
    owner.deleteDownloadRecordNow(row.entry);
    return true;
  }

  private static boolean deleteLiveFile(MainActivity owner, Row row) {
    int result = owner.deleteDownloadedFileNow(row.entry);
    if (result == RESULT_PERMISSION || result == RESULT_FAILED) return false;
    owner.deleteDownloadRecordNow(row.entry);
    return true;
  }

  private static void refreshDownloadPage(MainActivity owner) {
    if (!owner.downloadsPage) return;
    owner.renderDownloadFilters();
    owner.renderDownloads(owner.downloadQuery);
  }

  private static boolean deletePersistedRecord(Context context, Row row) { synchronized (STATE_LOCK) {
    if (context == null) return false;
    try {
      JSONArray next = removeItem(new JSONArray(prefs(context).getString(KEY, "[]")), row);
      return prefs(context).edit().putString(KEY, next.toString()).commit();
    } catch (Exception error) {
      return false;
    }
  
    }
  }

  private static boolean deletePersistedFile(Context host, Row row) {
    if (host == null || !row.canDeleteFile()) return false;
    int result = deleteStoredFile(host, row.uriString);
    if (result == RESULT_PERMISSION || result == RESULT_FAILED) return false;
    return deletePersistedRecord(host, row);
  }

  private static JSONArray removeItem(JSONArray source, Row row) {
    JSONArray out = new JSONArray();
    boolean removed = false;
    for (int i = 0; i < source.length(); i++) {
      JSONObject item = source.optJSONObject(i);
      if (item == null) continue;
      if (!removed && matches(item, row)) {
        removed = true;
        continue;
      }
      out.put(item);
    }
    return out;
  }

  static boolean matches(JSONObject item, Row row) {
    if (item == null || row == null) return false;
    return item.optLong("created") == row.createdAt && row.name.equals(item.optString("name", ""))
        && row.uriString.equals(item.optString("uri", ""));
  }

  static boolean matches(JSONObject item, PersistedDownloadDriver.Request request) {
    if (item == null || request == null) return false;
    return item.optLong("created") == request.createdAt && request.name.equals(item.optString("name", ""))
        && request.uriString.equals(item.optString("uri", ""));
  }

  static int deleteStoredFile(Context context, String raw) {
    if (raw == null || raw.trim().isEmpty()) return RESULT_MISSING;
    Uri uri;
    try {
      uri = Uri.parse(raw.trim());
    } catch (Exception error) {
      return RESULT_FAILED;
    }
    String scheme = uri.getScheme();
    try {
      if ("file".equalsIgnoreCase(scheme)) {
        String path = uri.getPath();
        if (path == null) return RESULT_FAILED;
        File file = new File(path);
        if (!file.exists()) return RESULT_MISSING;
        return file.delete() ? RESULT_OK : RESULT_FAILED;
      }
      if (!"content".equalsIgnoreCase(scheme)) return RESULT_FAILED;
      if (Build.VERSION.SDK_INT >= 30 && !Environment.isExternalStorageManager()) return RESULT_PERMISSION;
      File direct = documentFile(uri);
      if (direct != null) {
        if (!direct.exists()) return RESULT_MISSING;
        return direct.delete() ? RESULT_OK : RESULT_FAILED;
      }
      if (context.getContentResolver().delete(uri, null, null) > 0) return RESULT_OK;
      try (android.os.ParcelFileDescriptor remaining = context.getContentResolver().openFileDescriptor(uri, "r")) {
        return remaining == null ? RESULT_MISSING : RESULT_FAILED;
      } catch (FileNotFoundException missing) {
        return RESULT_MISSING;
      }
    } catch (SecurityException denied) {
      return RESULT_PERMISSION;
    } catch (Exception error) {
      return RESULT_FAILED;
    }
  }

  static File documentFile(Uri uri) {
    if (uri == null) return null;
    if ("file".equals(uri.getScheme()) && uri.getPath() != null) return new File(uri.getPath());
    if (!"content".equals(uri.getScheme())) return null;
    try {
      String id = DocumentsContract.getDocumentId(uri);
      if (id.startsWith("raw:")) return new File(id.substring(4));
      int split = id.indexOf(':');
      String volume = split < 0 ? id : id.substring(0, split), relative = split < 0 ? "" : id.substring(split + 1);
      String root = volume.equalsIgnoreCase("primary") ? Environment.getExternalStorageDirectory().getAbsolutePath()
          : volume.equalsIgnoreCase("home") ? new File(Environment.getExternalStorageDirectory(), "Documents").getAbsolutePath()
          : "/storage/" + volume;
      return relative.isEmpty() ? new File(root) : new File(root, relative);
    } catch (Exception ignored) {
      return null;
    }
  }
}
