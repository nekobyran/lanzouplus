package cc.nkbr.lanzouplus;

import android.app.Activity;
import android.app.AlertDialog;
import android.graphics.drawable.ColorDrawable;
import android.view.Gravity;
import android.view.View;
import android.widget.CheckBox;
import android.widget.FrameLayout;
import android.widget.HorizontalScrollView;
import android.widget.ImageButton;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;

/**
 * Aggregate download presentation shared by the download page and the download-history popup.
 *
 * <p>A batch is the set of records carrying the same batch id. One aggregate row stands for the
 * whole batch and keeps the download page behaviour: aggregate metrics and progress, the four batch
 * actions (pause/resume, cancel, delete records, delete files with records), and a group detail
 * listing every record of the batch.</p>
 *
 * <p>The component works on {@link Record}, so both models are covered by one implementation:
 * {@link DownloadHistoryCenter.Row} for history and persisted records, and
 * {@link MainActivity.DownloadEntry} for the live download page. Hosts that need their own text or
 * action execution implement {@link Record} directly instead of using the supplied adapters.</p>
 */
final class DownloadAggregateView {
  static final int ACTION_PAUSE_RESUME = 0, ACTION_CANCEL = 1, ACTION_DELETE_RECORDS = 2, ACTION_DELETE_FILES = 3;

  private DownloadAggregateView() {}

  /** One download record, whatever model backs it. */
  interface Record {
    /** Stable identity of this record, used for selection and change detection while it stays live. */
    String key();

    String batchId();

    String batchTitle();

    String name();
    String localUri();
    String shareUrl();
    String password();
    String parentUri();

    /** Metrics text for this record, such as the history metrics block or "下载中 · 42%". */
    String metricsText();

    boolean completed();

    boolean active();

    boolean paused();

    boolean failed();

    boolean cancelled();

    /** True while the record still resolves its direct link. */
    boolean resolving();

    long downloadedBytes();

    long totalBytes();

    int percent();

    /**
     * Current transfer rate in bytes per second; 0 while nothing is being measured. Live records
     * report the measured rate, persisted records report 0.
     */
    long speedBytesPerSecond();

    /** Seconds left at the current rate, or -1 while the rate is unknown. */
    long remainingSeconds();

    /** True for a premium-cloud save task, which only matches the 保存中 state filter. */
    boolean premium();

    /** The live download-page entry behind this record, or null for a persisted record. */
    MainActivity.DownloadEntry liveEntry();

    /** Single-tap action for this record, one of the {@code DownloadHistoryCenter.ACTION_*} ids. */
    int primaryAction();

    boolean canCancel();

    boolean canRetry();

    boolean canDeleteFile();

    boolean perform(Activity host, int action);
  }

  /** Action executor for hosts whose records are not {@link DownloadHistoryCenter.Row}s. */
  interface Runner {
    boolean perform(Activity host, int action);
  }

  /** Adapts one shared history row; actions go through the shared download center. */
  static Record record(final DownloadHistoryCenter.Row row) {
    return new Record() {
      @Override public String key() {
        if (row.live && row.entry != null) return "live:" + System.identityHashCode(row.entry);
        return "saved:" + row.createdAt + "|" + row.name + "|" + row.uriString;
      }
      @Override public String batchId() { return row.batchId; }
      @Override public String batchTitle() { return row.batchTitle; }
      @Override public String name() { return row.name; }
      @Override public String localUri() { return row.uriString; }
      @Override public String shareUrl() { return row.shareUrl; }
      @Override public String password() { return row.password; }
      @Override public String parentUri() { return row.parentUriString; }
      @Override public String metricsText() { return DownloadHistoryCenter.metrics(row); }
      @Override public boolean completed() { return row.completed(); }
      @Override public boolean active() { return row.active(); }
      @Override public boolean paused() { return row.paused(); }
      @Override public boolean failed() { return row.failed(); }
      @Override public boolean cancelled() { return row.cancelled(); }
      @Override public boolean resolving() { return row.live && MainActivity.DOWNLOAD_RESOLVING.equals(row.state); }
      @Override public long downloadedBytes() { return row.downloadedBytes; }
      @Override public long totalBytes() { return row.totalBytes; }
      @Override public int percent() { return row.percent; }
      @Override public long speedBytesPerSecond() {
        if (row.entry == null) return 0;
        synchronized (row.entry) { return Math.max(0, row.entry.speedBps); }
      }
      @Override public long remainingSeconds() {
        if (row.entry == null) return -1;
        synchronized (row.entry) { return row.entry.speedBps > 0 ? row.entry.etaSeconds : -1; }
      }
      @Override public boolean premium() { return row.premium; }
      @Override public MainActivity.DownloadEntry liveEntry() { return row.entry; }
      @Override public int primaryAction() { return row.primaryAction(); }
      @Override public boolean canCancel() { return row.canCancel(); }
      @Override public boolean canRetry() { return row.canRetry(); }
      @Override public boolean canDeleteFile() { return row.canDeleteFile(); }
      @Override public boolean perform(Activity host, int action) {
        return DownloadHistoryCenter.perform(host, row, action);
      }
    };
  }

  /**
   * Adapts one live download-page record. State and progress come from the entry fields; actions
   * run through the host runner so the download page keeps its own transfer engine entry points.
   */
  static Record record(final MainActivity.DownloadEntry entry, final Runner runner) {
    return new Record() {
      @Override public String key() { return "entry:" + System.identityHashCode(entry); }
      @Override public String batchId() { return entry.batchId; }
      @Override public String batchTitle() { return entry.batchTitle; }
      @Override public String name() { return entry.name; }
      @Override public String localUri() { return entry.uriString; }
      @Override public String shareUrl() { return entry.shareUrl; }
      @Override public String password() { return entry.password; }
      @Override public String parentUri() { return entry.parentUriString; }
      @Override public String metricsText() { return DownloadHistoryCenter.metrics(DownloadHistoryCenter.liveRow(entry)); }
      @Override public boolean completed() {
        return MainActivity.DOWNLOAD_COMPLETED.equals(entry.state) || MainActivity.SAVE_COMPLETED.equals(entry.state);
      }
      @Override public boolean failed() {
        return MainActivity.DOWNLOAD_FAILED.equals(entry.state) || MainActivity.SAVE_FAILED.equals(entry.state);
      }
      @Override public boolean cancelled() {
        return MainActivity.DOWNLOAD_CANCELLED.equals(entry.state) || MainActivity.SAVE_CANCELLED.equals(entry.state);
      }
      @Override public boolean paused() {
        return MainActivity.DOWNLOAD_PAUSED.equals(entry.state) || MainActivity.SAVE_PAUSED.equals(entry.state)
            || MainActivity.SAVE_INTERRUPTED.equals(entry.state);
      }
      @Override public boolean active() {
        return MainActivity.DOWNLOAD_WAITING.equals(entry.state) || MainActivity.DOWNLOAD_RESOLVING.equals(entry.state)
            || MainActivity.DOWNLOAD_RUNNING.equals(entry.state) || MainActivity.SAVE_WAITING.equals(entry.state)
            || MainActivity.SAVE_RUNNING.equals(entry.state);
      }
      @Override public boolean resolving() { return MainActivity.DOWNLOAD_RESOLVING.equals(entry.state); }
      @Override public long downloadedBytes() { return entry.downloadedBytes; }
      @Override public long totalBytes() { return entry.totalBytes; }
      @Override public int percent() { return Math.max(0, Math.min(100, entry.percent)); }
      @Override public long speedBytesPerSecond() { return Math.max(0, entry.speedBps); }
      @Override public long remainingSeconds() { return entry.speedBps > 0 ? entry.etaSeconds : -1; }
      @Override public boolean premium() { return MainActivity.isPremiumSaveEntry(entry); }
      @Override public MainActivity.DownloadEntry liveEntry() { return entry; }
      @Override public int primaryAction() {
        if (active()) return DownloadHistoryCenter.ACTION_PAUSE;
        if (paused()) return DownloadHistoryCenter.ACTION_RESUME;
        if (failed() || cancelled()) return DownloadHistoryCenter.ACTION_RETRY;
        if (completed()) return DownloadHistoryCenter.ACTION_OPEN;
        return DownloadHistoryCenter.ACTION_NONE;
      }
      @Override public boolean canCancel() { return active() || paused(); }
      @Override public boolean canRetry() { return failed() || cancelled(); }
      @Override public boolean canDeleteFile() { return !entry.uriString.isEmpty(); }
      @Override public boolean perform(Activity host, int action) { return runner.perform(host, action); }
    };
  }

  /** One batch, or one standalone record, in history order. */
  static final class Item {
    final String batchId;
    final List<Record> records;

    Item(String batchId, List<Record> records) {
      this.batchId = batchId == null ? "" : batchId;
      this.records = records;
    }

    boolean batched() {
      return !batchId.isEmpty();
    }

    /** Aggregate key: the batch id for a batch, the single record identity otherwise. */
    String key() {
      return batched() ? "batch:" + batchId : records.get(0).key();
    }

    String title() {
      if (batched()) {
        for (Record record : records) if (!record.batchTitle().isEmpty()) return record.batchTitle();
      }
      return records.get(0).name();
    }
  }

  /** History rows newest-first, with every batch collapsed into its aggregate item. */
  static List<Item> items(List<DownloadHistoryCenter.Row> rows) {
    List<Record> records = new ArrayList<>(rows.size());
    for (DownloadHistoryCenter.Row row : rows) records.add(record(row));
    return group(records);
  }

  /**
   * Records in host order, with every batch collapsed into its aggregate item. A batch keeps the
   * position of its newest record; standalone records stay in place.
   */
  static List<Item> group(List<Record> records) {
    List<Item> out = new ArrayList<>();
    LinkedHashMap<String, Item> batches = new LinkedHashMap<>();
    for (Record record : records) {
      String batchId = record.batchId();
      if (batchId.isEmpty()) {
        List<Record> single = new ArrayList<>(1);
        single.add(record);
        out.add(new Item("", single));
        continue;
      }
      Item item = batches.get(batchId);
      if (item == null) {
        item = new Item(batchId, new ArrayList<>());
        batches.put(batchId, item);
        out.add(item);
      }
      item.records.add(record);
    }
    return out;
  }

  static String metrics(Item item) {
    if (!item.batched()) {
      String transfer = transferLine(item);
      return transfer.isEmpty() ? item.records.get(0).metricsText()
          : item.records.get(0).metricsText() + "\n" + transfer;
    }
    int total = item.records.size(), done = 0, active = 0, paused = 0, failed = 0, cancelled = 0;
    long bytes = 0, totalBytes = 0;
    for (Record record : item.records) {
      if (record.completed()) done++;
      else if (record.active()) active++;
      else if (record.paused()) paused++;
      else if (record.failed()) failed++;
      else if (record.cancelled()) cancelled++;
      bytes += record.downloadedBytes();
      totalBytes += record.totalBytes();
    }
    StringBuilder out = new StringBuilder();
    out.append(total).append(" 项 · ").append(done).append(" 已完成");
    if (active > 0) out.append(" · ").append(active).append(" 进行中");
    if (paused > 0) out.append(" · ").append(paused).append(" 已暂停");
    if (failed > 0) out.append(" · ").append(failed).append(" 失败");
    if (cancelled > 0) out.append(" · ").append(cancelled).append(" 已取消");
    out.append('\n').append(DownloadHistoryCenter.formatSize(bytes));
    out.append(totalBytes > 0 ? " / " + DownloadHistoryCenter.formatSize(totalBytes) : " / 大小未知");
    String transfer = transferLine(item);
    if (!transfer.isEmpty()) out.append('\n').append(transfer);
    return out.toString();
  }

  /**
   * Aggregate transfer rate in bytes per second over the records that are transferring now. A
   * record that only resolves its direct link contributes nothing until it moves bytes.
   */
  static long speed(Item item) {
    long rate = 0;
    for (Record record : item.records) {
      if (record.active() && !record.resolving()) rate += Math.max(0, record.speedBytesPerSecond());
    }
    return rate;
  }

  /**
   * Seconds left for the item. A single record reports the transfer engine's own estimate; a batch
   * divides the bytes still transferring by its aggregate rate. -1 while either is unknown.
   */
  static long remaining(Item item) {
    if (!item.batched()) return item.records.get(0).remainingSeconds();
    long bytesLeft = 0, rate = 0;
    for (Record record : item.records) {
      if (!record.active() || record.resolving()) continue;
      bytesLeft += Math.max(0, record.totalBytes() - record.downloadedBytes());
      rate += Math.max(0, record.speedBytesPerSecond());
    }
    return rate <= 0 || bytesLeft <= 0 ? -1 : (bytesLeft + rate - 1) / rate;
  }

  /** Transfer-rate text shared by every download surface; "测速中" until a rate is measured. */
  static String speedText(long bytesPerSecond) {
    return bytesPerSecond <= 0 ? "测速中" : DownloadHistoryCenter.formatSize(bytesPerSecond) + "/s";
  }

  /** Remaining-time text shared by every download surface. */
  static String remainingText(long seconds) {
    if (seconds < 0) return "剩余时间计算中";
    if (seconds < 60) return "剩余 " + seconds + " 秒";
    if (seconds < 3600) return "剩余 " + (seconds / 60) + " 分 " + (seconds % 60) + " 秒";
    return "剩余 " + (seconds / 3600) + " 小时 " + ((seconds % 3600) / 60) + " 分";
  }

  /**
   * One transfer line for an item that is moving bytes: rate and remaining time. Empty while
   * nothing transfers, so a host can append it without checking state first.
   */
  static String transferLine(Item item) {
    if (!transferring(item)) return "";
    return speedText(speed(item)) + " · " + remainingText(remaining(item));
  }

  /** True while at least one record transfers bytes instead of only resolving its direct link. */
  private static boolean transferring(Item item) {
    for (Record record : item.records) if (record.active() && !record.resolving()) return true;
    return false;
  }

  static int percent(Item item) {
    if (!item.batched()) return item.records.get(0).percent();
    long bytes = 0, totalBytes = 0;
    int sum = 0, completed = 0;
    for (Record record : item.records) {
      bytes += record.downloadedBytes();
      totalBytes += record.totalBytes();
      sum += record.percent();
      if (record.completed()) completed++;
    }
    if (completed == item.records.size()) return 100;
    if (totalBytes > 0) return (int) Math.min(100, bytes * 100 / totalBytes);
    return sum / item.records.size();
  }

  /** True while at least one record of the batch still resolves its direct link. */
  static boolean indeterminate(Item item) {
    for (Record record : item.records) if (record.resolving()) return true;
    return false;
  }

  static String[] actionLabels() {
    return new String[]{"暂停/继续未完成项", "取消未完成项", "删除任务记录", "删除本地文件与记录"};
  }

  /**
   * Applies one batch action to every record of the item and returns the user-facing result text.
   * Semantics match the download page: pause resumes when nothing is running, deleting records
   * cancels running transfers first, deleting files keeps records whose file could not be removed.
   */
  static String applyAction(Activity host, Item item, int action) {
    int done = 0, blocked = 0;
    if (action == ACTION_PAUSE_RESUME) {
      boolean pause = false;
      for (Record record : item.records) if (record.active()) pause = true;
      for (Record record : item.records) {
        if (pause ? !record.active() : !record.paused()) continue;
        if (record.perform(host, pause ? DownloadHistoryCenter.ACTION_PAUSE : DownloadHistoryCenter.ACTION_RESUME)) done++;
      }
      if (done == 0) return "所选记录没有可暂停或继续的任务";
      return (pause ? "已暂停 " : "已继续 ") + done + " 项";
    }
    if (action == ACTION_CANCEL) {
      for (Record record : item.records) {
        if (!record.canCancel()) continue;
        if (record.perform(host, DownloadHistoryCenter.ACTION_CANCEL)) done++;
      }
      return done == 0 ? "所选记录没有可取消的任务" : "已取消 " + done + " 项任务";
    }
    if (action == ACTION_DELETE_RECORDS) {
      for (Record record : item.records) {
        if (record.active() || record.paused()) record.perform(host, DownloadHistoryCenter.ACTION_CANCEL);
        if (record.perform(host, DownloadHistoryCenter.ACTION_DELETE_RECORD)) done++;
      }
      return "已删除 " + done + " 条下载记录";
    }
    for (Record record : item.records) {
      if (!record.canDeleteFile()) continue;
      if (record.perform(host, DownloadHistoryCenter.ACTION_DELETE_FILE)) done++;
      else blocked++;
    }
    return blocked == 0 ? "已删除 " + done + " 项本地文件"
        : "已删除 " + done + " 项，另有 " + blocked + " 项缺少删除权限或删除失败";
  }

  /** The four batch actions, matching the download page menu. */
  static void showActions(Activity host, SharedPopupSurface.Theme theme, Item item, Runnable after) {
    AlertDialog menu = new AlertDialog.Builder(host)
        .setTitle(item.title())
        .setItems(actionLabels(), (dialog, which) -> {
          if (which == ACTION_PAUSE_RESUME) {
            Toast.makeText(host, applyAction(host, item, which), Toast.LENGTH_SHORT).show();
            if (after != null) after.run();
            return;
          }
          confirm(host, theme, which, () -> {
            Toast.makeText(host, applyAction(host, item, which), Toast.LENGTH_SHORT).show();
            if (after != null) after.run();
          });
        })
        .setNegativeButton("关闭", null)
        .create();
    SharedPopupSurface.styleDialog(host, menu, theme, null);
    menu.show();
  }

  private static void confirm(Activity host, SharedPopupSurface.Theme theme, int action, Runnable run) {
    boolean files = action == ACTION_DELETE_FILES;
    String title = files ? "删除所选文件？" : action == ACTION_CANCEL ? "取消所选任务？" : "删除所选记录？";
    String message = files
        ? "将删除所选本地文件；删除成功或文件已不存在时同步清理记录，失败记录保留。"
        : action == ACTION_CANCEL ? "将取消该批次中所有未完成的下载任务。"
        : "将删除该批次的下载记录，本地文件会保留。进行中的任务会先取消。";
    AlertDialog prompt = new AlertDialog.Builder(host)
        .setTitle(title)
        .setMessage(message)
        .setNegativeButton("取消", null)
        .setPositiveButton(files ? "删除文件" : action == ACTION_CANCEL ? "取消任务" : "删除记录",
            (dialog, which) -> run.run())
        .create();
    SharedPopupSurface.styleDialog(host, prompt, theme, null);
    prompt.show();
  }

  /**
   * The aggregate row itself with its controls named, so a host updates the row through explicit
   * fields instead of walking it by child index. {@link #rowView} fills every field while building.
   *
   * <p>{@code thumbnail} and {@code actions} stay null when the row has no thumbnail and when the
   * item is a single record without a batch action menu.</p>
   */
  static final class RowBinding extends LinearLayout {
    CheckBox check;
    View thumbnail;
    LinearLayout words;
    TextView title, metrics;
    ProgressBar progress;
    ImageButton actions, details;

    RowBinding(Activity host) {
      super(host);
    }
  }

  /**
   * The aggregate row: thumbnail, title, aggregate metrics and progress, the batch action menu and
   * the detail entry. A null thumbnail keeps the row text-only; single records get no action menu.
   */
  static RowBinding rowView(Activity host, SharedPopupSurface.Theme theme, Item item, View thumbnail,
      boolean selectionMode, boolean selected, Runnable onOpenDetail, Runnable onToggleSelection,
      Runnable onActions) {
    RowBinding row = new RowBinding(host);
    row.setGravity(Gravity.CENTER_VERTICAL);
    row.setPadding(SharedPopupSurface.dp(host, 4), SharedPopupSurface.dp(host, 4),
        SharedPopupSurface.dp(host, 4), SharedPopupSurface.dp(host, 4));
    row.setMinimumHeight(SharedPopupSurface.dp(host, 76));

    CheckBox check = new CheckBox(host);
    check.setClickable(false);
    check.setFocusable(false);
    check.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
    check.setChecked(selected);
    check.setVisibility(selectionMode ? View.VISIBLE : View.GONE);
    row.addView(check, new LinearLayout.LayoutParams(SharedPopupSurface.dp(host, 44), SharedPopupSurface.dp(host, 48)));
    row.check = check;

    if (thumbnail != null) {
      row.addView(thumbnail, new LinearLayout.LayoutParams(SharedPopupSurface.dp(host, 40), SharedPopupSurface.dp(host, 40)));
      row.thumbnail = thumbnail;
    }

    LinearLayout words = new LinearLayout(host);
    words.setOrientation(LinearLayout.VERTICAL);
    words.setPadding(SharedPopupSurface.dp(host, 8), 0, SharedPopupSurface.dp(host, 4), 0);
    TextView name = SharedPopupSurface.label(host, item.title(), 14, theme.text);
    name.setSingleLine(true);
    name.setEllipsize(android.text.TextUtils.TruncateAt.END);
    TextView metrics = SharedPopupSurface.label(host, metrics(item), 10, theme.muted);
    metrics.setMaxLines(3);
    metrics.setEllipsize(android.text.TextUtils.TruncateAt.END);
    ProgressBar bar = new ProgressBar(host, null, android.R.attr.progressBarStyleHorizontal);
    bar.setMax(100);
    bar.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
    boolean parsing = indeterminate(item);
    bar.setIndeterminate(parsing);
    if (!parsing) bar.setProgress(percent(item));
    words.addView(name, new LinearLayout.LayoutParams(-1, SharedPopupSurface.dp(host, 24)));
    words.addView(metrics, new LinearLayout.LayoutParams(-1, SharedPopupSurface.dp(host, 40)));
    words.addView(bar, new LinearLayout.LayoutParams(-1, SharedPopupSurface.dp(host, 4)));
    row.addView(words, new LinearLayout.LayoutParams(0, SharedPopupSurface.dp(host, 72), 1));
    row.words = words;
    row.title = name;
    row.metrics = metrics;
    row.progress = bar;

    if (item.batched()) {
      ImageButton actions = SharedPopupSurface.actionButton(host, R.drawable.ic_more, "批次操作", theme.accent);
      actions.setOnClickListener(v -> onActions.run());
      row.addView(actions, new LinearLayout.LayoutParams(SharedPopupSurface.dp(host, 40), SharedPopupSurface.dp(host, 46)));
      row.actions = actions;
    }
    ImageButton details = SharedPopupSurface.actionButton(host, R.drawable.ic_expand,
        item.batched() ? "查看批次详情" : "查看详情", theme.accent);
    details.setOnClickListener(v -> onOpenDetail.run());
    row.addView(details, new LinearLayout.LayoutParams(SharedPopupSurface.dp(host, 38), SharedPopupSurface.dp(host, 46)));
    row.details = details;

    row.setBackground(SharedPopupSurface.ripple(theme.accent, new ColorDrawable(android.graphics.Color.TRANSPARENT)));
    row.setClickable(true);
    row.setFocusable(true);
    row.setContentDescription(item.title() + "，" + metrics(item).replace('\n', '，')
        + (selected ? "，已选择" : "") + "，轻点查看详情，长按进入多选");
    row.setOnClickListener(v -> {
      if (selectionMode) onToggleSelection.run();
      else onOpenDetail.run();
    });
    row.setOnLongClickListener(v -> {
      if (selectionMode) return false;
      onToggleSelection.run();
      return true;
    });
    return row;
  }

  // --- shared batch detail ---------------------------------------------------------------------

  /** State filter strip of every batch detail page, in strip order. */
  static final String[] STATE_FILTERS = {"全部", "保存中", "解析中", "下载中", "下载完成", "下载失败"};

  /** File-type filter strip of every batch detail page, in strip order. */
  static final String[] TYPE_FILTERS = {"全部", "安装包", "应用程序", "压缩包", "文本", "视频", "音乐", "其他"};

  /**
   * File-type bucket of one name, the same mapping the download page filters with. Names without a
   * usable extension and unknown extensions share the 其他 bucket.
   */
  static String typeCategory(String name) {
    String value = name == null ? "" : name.toLowerCase(Locale.ROOT);
    int dot = value.lastIndexOf('.');
    String extension = dot >= 0 && dot < value.length() - 1
        ? value.substring(dot + 1).toUpperCase(Locale.ROOT) : "无后缀";
    switch (extension) {
      case "APK": case "APKS": case "APKM": case "XAPK": case "AAB": return "安装包";
      case "EXE": case "MSI": case "MSIX": case "APPX": case "APPXBUNDLE": case "DMG": case "PKG":
      case "DEB": case "RPM": case "APPIMAGE": case "JAR": return "应用程序";
      case "ZIP": case "RAR": case "7Z": case "TAR": case "GZ": case "BZ2": case "XZ": case "ZST":
      case "TGZ": case "TBZ": case "CAB": case "ISO": return "压缩包";
      case "TXT": case "MD": case "LOG": case "CSV": case "JSON": case "XML": case "YAML": case "YML":
      case "INI": case "CONF": case "RTF": case "PDF": case "DOC": case "DOCX": case "ODT": case "XLS":
      case "XLSX": case "PPT": case "PPTX": case "EPUB": case "MOBI": return "文本";
      case "MP4": case "MKV": case "AVI": case "MOV": case "WMV": case "FLV": case "WEBM": case "M4V":
      case "TS": case "3GP": case "MPEG": case "MPG": return "视频";
      case "MP3": case "FLAC": case "WAV": case "AAC": case "M4A": case "OGG": case "OPUS": case "WMA":
      case "APE": case "MID": case "MIDI": return "音乐";
      default: return "其他";
    }
  }

  /**
   * State bucket of one record for the state strip; "" when no state filter matches it. A save task
   * is always 保存中 and never appears under a download state, exactly like the download page.
   */
  static String stateBucket(Record record) {
    if (record.premium()) return "保存中";
    if (record.resolving()) return "解析中";
    if (record.completed()) return "下载完成";
    if (record.failed() || record.cancelled()) return "下载失败";
    if (record.active() || record.paused()) return "下载中";
    return "";
  }

  /** Type bucket of one record for the type strip; "" for records that only match 全部. */
  static String typeBucket(Record record) {
    return record.premium() ? "" : typeCategory(record.name());
  }

  /**
   * Metrics of one record row: its own text plus its transfer rate and remaining time while it
   * actually moves bytes, so a single row carries the same information as the download page row.
   */
  static String recordMetrics(Record record) {
    String base = record.metricsText();
    if (!record.active() || record.resolving()) return base;
    long rate = Math.max(0, record.speedBytesPerSecond());
    if (rate <= 0) return base;
    return base + "\n" + speedText(rate) + " · " + remainingText(record.remainingSeconds());
  }

  /** One selection or batch action of a batch detail page. */
  interface Action {
    void run(Item item, List<Record> chosen);
  }

  /** Availability of one batch detail button for the current selection. */
  interface Enabled {
    boolean test(List<Record> chosen);
  }

  /** Draws the icon view of one record row; returning null uses the shared fallback icon. */
  interface IconLoader {
    View icon(Record record);
  }

  /** One button of a batch detail page: icon, accessible description, availability and work. */
  static final class BatchButton {
    final int icon;
    final String description;
    final Enabled enabled;
    final Action action;

    private BatchButton(int icon, String description, Enabled enabled, Action action) {
      this.icon = icon;
      this.description = description;
      this.enabled = enabled;
      this.action = action;
    }

    /** A button that stays available for every selection. */
    static BatchButton of(int icon, String description, Action action) {
      return new BatchButton(icon, description, chosen -> true, action);
    }

    static BatchButton of(int icon, String description, Enabled enabled, Action action) {
      return new BatchButton(icon, description, enabled, action);
    }
  }

  /**
   * Host wiring of one batch detail page: the buttons that act on the chosen records, the batch-wide
   * quick actions beside the type strip, and how one record row draws its icon.
   *
   * <p>The component appends its own 退出多选 button, so a host supplies only the actions it owns.</p>
   */
  static final class BatchHost {
    final List<BatchButton> selectionButtons;
    final List<BatchButton> batchButtons;
    final IconLoader icons;

    BatchHost(List<BatchButton> selectionButtons, List<BatchButton> batchButtons) {
      this(selectionButtons, batchButtons, null);
    }

    BatchHost(List<BatchButton> selectionButtons, List<BatchButton> batchButtons, IconLoader icons) {
      this.selectionButtons = selectionButtons == null ? new ArrayList<>() : selectionButtons;
      this.batchButtons = batchButtons == null ? new ArrayList<>() : batchButtons;
      this.icons = icons;
    }
  }

  /**
   * One batch detail page: the state and type filter strips, the batch-wide quick
   * actions, one row per record and the multi-select bar. The download page shows it inside its
   * dialog and the download-history popup pushes it onto the in-dialog page stack, so filters,
   * selection and rows behave identically for live and persisted records.
   *
   * <p>The page never ticks on its own. A host that keeps its data fresh calls {@link #bind} with the
   * refreshed aggregate, which keeps the chosen filters and the still-alive selection.</p>
   */
  static BatchPage batchPage(Activity host, SharedPopupSurface.Theme theme, Item item, BatchHost batchHost) {
    return new BatchPage(host, theme, item, batchHost);
  }

  static BatchPage historyPage(Activity host, SharedPopupSurface.Theme theme, List<Record> records,
      BatchHost batchHost, java.util.function.Consumer<Item> openBatch) {
    BatchPage page=new BatchPage(host,theme,new Item("history",records),batchHost);
    page.openBatch=openBatch;page.render();return page;
  }

  static final class BatchPage {
    private static final int BUTTONS_PER_ROW = 6, BUTTON_HEIGHT_DP = 44;
    private static final String EXIT_DESCRIPTION = "退出批量下载内部多选";

    final LinearLayout root;
    private final Activity host;
    private final SharedPopupSurface.Theme theme;
    private final BatchHost batchHost;
    private final LinearLayout stateStrip, typeStrip, list, selectionBar;
    private final LinkedHashSet<String> selected = new LinkedHashSet<>();
    private Item item;
    private boolean selectionMode;
    private String stateFilter = "全部", typeFilter = "全部";
    private java.util.function.Consumer<Item> openBatch;

    BatchPage(Activity host, SharedPopupSurface.Theme theme, Item item, BatchHost batchHost) {
      this.host = host;
      this.theme = theme;
      this.item = item;
      this.batchHost = batchHost;

      root = new LinearLayout(host);
      root.setOrientation(LinearLayout.VERTICAL);
      root.setPadding(dp(14), dp(6), dp(14), dp(8));

      stateStrip = new LinearLayout(host);
      HorizontalScrollView stateScroll = new HorizontalScrollView(host);
      stateScroll.setHorizontalScrollBarEnabled(false);
      stateScroll.addView(stateStrip, new HorizontalScrollView.LayoutParams(-2, dp(38)));
      root.addView(stateScroll, new LinearLayout.LayoutParams(-1, dp(38)));

      LinearLayout extensionRow = new LinearLayout(host);
      extensionRow.setGravity(Gravity.CENTER_VERTICAL);
      typeStrip = new LinearLayout(host);
      HorizontalScrollView typeScroll = new HorizontalScrollView(host);
      typeScroll.setHorizontalScrollBarEnabled(false);
      typeScroll.addView(typeStrip, new HorizontalScrollView.LayoutParams(-2, dp(38)));
      extensionRow.addView(typeScroll, new LinearLayout.LayoutParams(0, dp(38), 1));
      for (final BatchButton button : batchHost.batchButtons) {
        ImageButton action = SharedPopupSurface.actionButton(host, button.icon, button.description, theme.accent);
        SharedPopupSurface.styleButton(action, theme.accent, theme.muted, button.enabled.test(records()));
        action.setPadding(dp(12), dp(12), dp(12), dp(12));
        action.setOnClickListener(v -> {
          button.action.run(item, records());
          render();
        });
        extensionRow.addView(action, new LinearLayout.LayoutParams(dp(38), dp(38)));
      }
      root.addView(extensionRow, new LinearLayout.LayoutParams(-1, dp(38)));

      list = new LinearLayout(host);
      list.setOrientation(LinearLayout.VERTICAL);
      root.addView(list, new LinearLayout.LayoutParams(-1, -2));

      selectionBar = new LinearLayout(host);
      selectionBar.setOrientation(LinearLayout.VERTICAL);
      selectionBar.setContentDescription("批量下载内部多选操作，随列表滚动");
      root.addView(selectionBar, new LinearLayout.LayoutParams(-1, 0));

      render();
    }

    /** The aggregate this page shows; a batch of records, or one standalone record. */
    Item item() {
      return item;
    }

    /** Every record of the aggregate, in host order. */
    List<Record> records() {
      return item == null ? new ArrayList<>() : item.records;
    }

    /** Records currently chosen in the multi-select bar, in host order. */
    List<Record> chosen() {
      List<Record> out = new ArrayList<>();
      for (Record record : records()) if (selected.contains(record.key())) out.add(record);
      return out;
    }

    /** Records left after both filter strips, in host order. */
    List<Record> visible() {
      List<Record> out = new ArrayList<>();
      for (Record record : records()) {
        if (!stateFilter.equals("全部") && !stateFilter.equals(stateBucket(record))) continue;
        if (!typeFilter.equals("全部") && !typeFilter.equals(typeBucket(record))) continue;
        out.add(record);
      }
      return out;
    }

    boolean selectionMode() {
      return selectionMode;
    }

    String stateFilter() {
      return stateFilter;
    }

    String typeFilter() {
      return typeFilter;
    }

    /** Picks one state strip label; the same label keeps the page unchanged. */
    void selectState(String label) {
      if (label == null || label.equals(stateFilter)) return;
      for (int i = 0; i < stateStrip.getChildCount(); i++) {
        View tab = stateStrip.getChildAt(i);
        if (tab.getTag() instanceof FilterTab && label.contentEquals(((FilterTab) tab.getTag()).label.getText())) {
          transferUnderline(stateStrip, tab);
          stateFilter = label;
          renderSelection();
          return;
        }
      }
      stateFilter = label;
      render();
    }

    /** Picks one type strip label; the same label keeps the page unchanged. */
    void selectType(String label) {
      if (label == null || label.equals(typeFilter)) return;
      for (int i = 0; i < typeStrip.getChildCount(); i++) {
        TextView chip = (TextView) typeStrip.getChildAt(i);
        if (label.contentEquals(chip.getText())) {
          selectRounded(typeStrip, chip);
          typeFilter = label;
          renderSelection();
          return;
        }
      }
      typeFilter = label;
      render();
    }

    /** Enters multi-select with one record chosen. */
    void select(Record record) {
      selectionMode = true;
      selected.add(record.key());
      renderSelection();
    }

    /** Leaves multi-select and clears the selection. */
    void exitSelection() {
      selectionMode = false;
      selected.clear();
      renderSelection();
    }

    /**
     * Re-points the page at a refreshed aggregate, keeping both filters and every still-alive
     * selection. Leaving multi-select with nothing chosen keeps the bar out of the way.
     */
    void bind(Item next) {
      item = next;
      LinkedHashSet<String> alive = new LinkedHashSet<>();
      for (Record record : records()) alive.add(record.key());
      selected.retainAll(alive);
      if (selected.isEmpty()) selectionMode = false;
      render();
    }

    void render() {
      renderStateStrip();
      renderTypeStrip();
      renderSelection();
    }

    /** Repaints the record list and the multi-select bar, leaving both filter strips untouched. */
    private void renderSelection() {
      renderList();
      renderSelectionBar();
    }

    private void renderStateStrip() {
      stateStrip.removeAllViews();
      for (final String label : STATE_FILTERS) {
        boolean active = label.equals(stateFilter);
        View tab = underlineTab(label, active);
        tab.setOnClickListener(v -> selectState(label));
        stateStrip.addView(tab, new LinearLayout.LayoutParams(-2, dp(38)));
      }
    }

    private void renderTypeStrip() {
      typeStrip.removeAllViews();
      for (final String label : TYPE_FILTERS) {
        boolean active = label.equals(typeFilter);
        TextView tab = roundedTab(label, active);
        tab.setOnClickListener(v -> selectType(label));
        typeStrip.addView(tab, new LinearLayout.LayoutParams(-2, dp(34)));
      }
    }

    private void renderList() {
      list.removeAllViews();
      List<Record> shown = visible();
      if(openBatch!=null){
        for(Item aggregate:group(shown)){
          View row=recordRow(aggregate.records.get(0),aggregate);
          row.setMinimumHeight(dp(66));list.addView(row,new LinearLayout.LayoutParams(-1,-2));
        }
      }else{
      for (Record record : shown) {
        View row = recordRow(record,null);
        row.setMinimumHeight(dp(66));
        list.addView(row, new LinearLayout.LayoutParams(-1, -2));
      }
      }
      if (shown.isEmpty()) {
        TextView empty = SharedPopupSurface.label(host, "没有匹配的批量下载项目", 12, theme.muted);
        empty.setGravity(Gravity.CENTER);
        list.addView(empty, new LinearLayout.LayoutParams(-1, dp(72)));
      }
    }

    private void renderSelectionBar() {
      selectionBar.removeAllViews();
      List<BatchButton> buttons = new ArrayList<>(batchHost.selectionButtons);
      buttons.add(BatchButton.of(R.drawable.ic_close, EXIT_DESCRIPTION, (item, chosen) -> exitSelection()));
      List<Record> chosen = chosen();
      int rows = 0;
      for (int start = 0; start < buttons.size(); start += BUTTONS_PER_ROW) {
        LinearLayout bar = new LinearLayout(host);
        bar.setGravity(Gravity.CENTER_VERTICAL);
        for (int slot = 0; slot < BUTTONS_PER_ROW && start + slot < buttons.size(); slot++) {
          final BatchButton button = buttons.get(start + slot);
          ImageButton view = SharedPopupSurface.actionButton(host, button.icon, button.description, theme.accent);
          SharedPopupSurface.styleButton(view, theme.accent, theme.muted, button.enabled.test(chosen));
          view.setPadding(dp(12), dp(12), dp(12), dp(12));
          view.setOnClickListener(v -> {
            button.action.run(item, chosen());
            renderSelection();
          });
          bar.addView(view, new LinearLayout.LayoutParams(0, dp(BUTTON_HEIGHT_DP), 1));
        }
        selectionBar.addView(bar, new LinearLayout.LayoutParams(-1, dp(BUTTON_HEIGHT_DP)));
        rows++;
      }
      LinearLayout.LayoutParams params = (LinearLayout.LayoutParams) selectionBar.getLayoutParams();
      params.height = selectionMode ? rows * dp(BUTTON_HEIGHT_DP) : 0;
      selectionBar.setLayoutParams(params);
      selectionBar.setVisibility(selectionMode ? View.VISIBLE : View.GONE);
    }

    private View recordRow(final Record record,final Item aggregate) {
      LinearLayout row = new LinearLayout(host);
      row.setGravity(Gravity.CENTER_VERTICAL);

      CheckBox check = new CheckBox(host);
      check.setClickable(false);
      check.setFocusable(false);
      check.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
      check.setVisibility(selectionMode ? View.VISIBLE : View.GONE);
      check.setChecked(selected.contains(record.key()));
      check.setButtonTintList(android.content.res.ColorStateList.valueOf(theme.accent));
      row.addView(check, new LinearLayout.LayoutParams(dp(40), dp(44)));

      View icon = batchHost.icons == null ? null : batchHost.icons.icon(record);
      if (icon == null) icon = fallbackIcon(record);
      row.addView(icon, new LinearLayout.LayoutParams(dp(36), dp(36)));

      LinearLayout words = new LinearLayout(host);
      words.setOrientation(LinearLayout.VERTICAL);
      words.setPadding(dp(10), 0, 0, 0);
      TextView title = SharedPopupSurface.label(host, aggregate==null?record.name():aggregate.title(), 13, theme.text);
      title.setSingleLine(true);
      title.setEllipsize(android.text.TextUtils.TruncateAt.END);
      title.setMinHeight(dp(24));
      TextView status = SharedPopupSurface.label(host, (aggregate==null?recordMetrics(record):metrics(aggregate)).replace('\n', ' '), 10, theme.muted);
      status.setMaxLines(2);
      status.setEllipsize(android.text.TextUtils.TruncateAt.END);
      status.setMinHeight(dp(34));
      words.setMinimumHeight(dp(62));
      words.addView(title, new LinearLayout.LayoutParams(-1, -2));
      words.addView(status, new LinearLayout.LayoutParams(-1, -2));
      row.addView(words, new LinearLayout.LayoutParams(0, -2, 1));

      row.setBackground(SharedPopupSurface.ripple(theme.accent, new ColorDrawable(android.graphics.Color.TRANSPARENT)));
      row.setClickable(true);
      row.setFocusable(true);
      row.setContentDescription(record.name() + "，" + record.metricsText().replace('\n', '，')
          + (selected.contains(record.key()) ? "，已选择" : "") + "，轻点执行操作，长按进入多选");
      row.setOnClickListener(v -> {
        if (selectionMode) {
          if(aggregate!=null){toggleAggregate(aggregate);return;}
          if (!selected.remove(record.key())) selected.add(record.key());
          if (selected.isEmpty()) selectionMode = false;
          renderSelection();
          return;
        }
        if(aggregate!=null&&aggregate.batched()){openBatch.accept(aggregate);return;}
        int action = record.primaryAction();
        if (action != DownloadHistoryCenter.ACTION_NONE) record.perform(host, action);
      });
      row.setOnLongClickListener(v -> {
        if (!selectionMode) {
          if(aggregate!=null){selectionMode=true;for(Record child:aggregate.records)selected.add(child.key());renderSelection();return true;}
          select(record);
          return true;
        }
        return false;
      });
      return row;
    }

    private void toggleAggregate(Item aggregate){
      boolean all=true;for(Record child:aggregate.records)all&=selected.contains(child.key());
      for(Record child:aggregate.records){if(all)selected.remove(child.key());else selected.add(child.key());}
      if(selected.isEmpty())selectionMode=false;renderSelection();
    }

    private View fallbackIcon(Record record) {
      android.widget.ImageView icon = new android.widget.ImageView(host);
      icon.setImageResource(DownloadHistoryCenter.fallbackIcon(record.name(), ""));
      icon.setColorFilter(theme.accent);
      icon.setScaleType(android.widget.ImageView.ScaleType.CENTER_INSIDE);
      icon.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
      return icon;
    }

    /** One state strip tab: label plus the underline that slides to the picked tab. */
    private View underlineTab(String label, boolean selected) {
      FrameLayout tab = new FrameLayout(host);
      tab.setClickable(true);
      tab.setFocusable(true);
      tab.setSelected(selected);
      tab.setBackground(SharedPopupSurface.ripple(theme.accent, new ColorDrawable(android.graphics.Color.TRANSPARENT)));
      TextView title = SharedPopupSurface.label(host, label, 12, selected ? theme.accent : theme.muted);
      title.setGravity(Gravity.CENTER);
      title.setMaxLines(1);
      title.setEllipsize(android.text.TextUtils.TruncateAt.END);
      title.setSelected(selected);
      title.setPadding(dp(14), 0, dp(14), dp(3));
      tab.addView(title, new FrameLayout.LayoutParams(-2, -1, Gravity.CENTER));
      View underline = new View(host);
      underline.setBackground(SharedPopupSurface.solid(theme.accent, 2));
      underline.setAlpha(selected ? 1f : 0f);
      underline.setScaleX(selected ? 1f : 0f);
      FrameLayout.LayoutParams line = new FrameLayout.LayoutParams(-1, dp(2), Gravity.BOTTOM);
      line.setMargins(dp(10), 0, dp(10), dp(1));
      tab.addView(underline, line);
      tab.setTag(new FilterTab(title, underline));
      tab.setContentDescription(label + (selected ? "，已选择" : ""));
      return tab;
    }

    /** Slides the underline to the picked state tab, or snaps it when motion is reduced. */
    private void transferUnderline(LinearLayout strip, View target) {
      int oldIndex = -1, newIndex = strip.indexOfChild(target);
      for (int i = 0; i < strip.getChildCount(); i++) {
        if (strip.getChildAt(i).isSelected()) {
          oldIndex = i;
          break;
        }
      }
      boolean right = oldIndex < newIndex;
      for (int i = 0; i < strip.getChildCount(); i++) {
        View tab = strip.getChildAt(i);
        if (!(tab.getTag() instanceof FilterTab)) continue;
        FilterTab holder = (FilterTab) tab.getTag();
        boolean active = tab == target;
        tab.setSelected(active);
        holder.label.setSelected(active);
        holder.label.setTextColor(active ? theme.accent : theme.muted);
        tab.setContentDescription(holder.label.getText() + (active ? "，已选择" : ""));
        holder.underline.animate().cancel();
        if (!HistoryPopupNavigation.motionEnabled(host)) {
          holder.underline.setAlpha(active ? 1f : 0f);
          holder.underline.setScaleX(active ? 1f : 0f);
          continue;
        }
        if (tab == strip.getChildAt(oldIndex < 0 ? newIndex : oldIndex) && !active) {
          holder.underline.setPivotX(right ? holder.underline.getWidth() : 0);
          holder.underline.animate().alpha(0f).scaleX(0f).setDuration(140).start();
        } else if (active) {
          holder.underline.setPivotX(right ? 0 : holder.underline.getWidth());
          holder.underline.setAlpha(1f);
          holder.underline.setScaleX(0f);
          holder.underline.animate().scaleX(1f).setDuration(140).start();
        } else {
          holder.underline.setAlpha(0f);
          holder.underline.setScaleX(0f);
        }
      }
    }

    /** One type strip tab: rounded chip, filled with the accent while picked. */
    private TextView roundedTab(String label, boolean selected) {
      TextView filter = SharedPopupSurface.label(host, label, 12, selected ? theme.background : theme.muted);
      filter.setGravity(Gravity.CENTER);
      filter.setPadding(dp(14), 0, dp(14), 0);
      filter.setBackground(SharedPopupSurface.ripple(theme.accent,
          SharedPopupSurface.solid(selected ? theme.accent : theme.surface, dp(16))));
      filter.setClickable(true);
      filter.setFocusable(true);
      filter.setSelected(selected);
      filter.setContentDescription(label + (selected ? "，已选择" : ""));
      return filter;
    }

    /** Repaints the rounded chips so only the picked one stays filled. */
    private void selectRounded(LinearLayout strip, TextView target) {
      for (int i = 0; i < strip.getChildCount(); i++) {
        TextView filter = (TextView) strip.getChildAt(i);
        boolean active = filter == target;
        filter.setSelected(active);
        filter.setTextColor(active ? theme.background : theme.muted);
        filter.setBackground(SharedPopupSurface.ripple(theme.accent,
            SharedPopupSurface.solid(active ? theme.accent : theme.surface, dp(16))));
        filter.setContentDescription(filter.getText() + (active ? "，已选择" : ""));
        if (active && HistoryPopupNavigation.motionEnabled(host)) {
          filter.animate().cancel();
          filter.setScaleX(.92f);
          filter.setScaleY(.92f);
          filter.animate().scaleX(1f).scaleY(1f).setDuration(140).start();
        }
      }
    }

    private int dp(int value) {
      return SharedPopupSurface.dp(host, value);
    }
  }

  /** One state strip tab's animated pieces, kept off the view hierarchy. */
  private static final class FilterTab {
    final TextView label;
    final View underline;

    FilterTab(TextView label, View underline) {
      this.label = label;
      this.underline = underline;
    }
  }
}
