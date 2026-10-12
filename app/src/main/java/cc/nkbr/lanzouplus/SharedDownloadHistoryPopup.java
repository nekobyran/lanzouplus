package cc.nkbr.lanzouplus;

import android.app.Activity;
import android.app.AlertDialog;
import android.graphics.drawable.GradientDrawable;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.ImageButton;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;

/**
 * One shared download-history surface for every host Activity.
 *
 * <p>Rows and actions come from {@link DownloadHistoryCenter} through the shared
 * {@link DownloadAggregateView}, so batches render exactly like the download page: one aggregate row
 * with aggregate metrics, batch actions and a per-record detail page. The detail page lives in the
 * same dialog through {@link HistoryPopupNavigation.PageHost}, so entering and leaving it never
 * opens a second window and the previous scroll position and selection survive. The dialog always
 * belongs to the calling Activity: no other Activity is started and the transfer engine keeps its
 * single owner.</p>
 */
final class SharedDownloadHistoryPopup {
  private static final int MAX_ROWS = 50, REFRESH_MS = 400;

  private SharedDownloadHistoryPopup() {}

  static boolean show(Activity host) {
    if (host == null || host.isFinishing()) return false;
    SharedPopupSurface.Theme theme = new SharedPopupSurface.Theme(host);
    List<DownloadHistoryCenter.Row> initial = DownloadHistoryCenter.rows(host);

    LinearLayout panel = new LinearLayout(host);
    panel.setOrientation(LinearLayout.VERTICAL);
    panel.setPadding(dp(host, 12), dp(host, 2), dp(host, 12), 0);

    LinearLayout header = new LinearLayout(host);
    header.setGravity(Gravity.CENTER_VERTICAL);
    TextView summary = SharedPopupSurface.label(host, "", 12, theme.accent);
    summary.setMaxLines(1);
    TextView selectAll = SharedPopupSurface.label(host, "多选", 13, theme.accent);
    selectAll.setGravity(Gravity.CENTER);
    selectAll.setClickable(true);
    selectAll.setFocusable(true);
    selectAll.setMinWidth(dp(host, 64));
    header.addView(summary, new LinearLayout.LayoutParams(0, dp(host, 44), 1));
    header.addView(selectAll, new LinearLayout.LayoutParams(dp(host, 76), dp(host, 48)));

    ScrollView scroll = new ScrollView(host);
    scroll.setClipToPadding(false);
    LinearLayout list = new LinearLayout(host);
    list.setOrientation(LinearLayout.VERTICAL);
    scroll.addView(list, new ScrollView.LayoutParams(-1, -2));

    LinearLayout rootPage = new LinearLayout(host);
    rootPage.setOrientation(LinearLayout.VERTICAL);
    rootPage.addView(header, new LinearLayout.LayoutParams(-1, dp(host, 48)));
    rootPage.addView(scroll, new LinearLayout.LayoutParams(-1, 0, 1));

    FrameLayout area = new FrameLayout(host);
    area.setClipChildren(false);

    LinearLayout actionBar = new LinearLayout(host);
    actionBar.setGravity(Gravity.CENTER_VERTICAL);
    actionBar.setPadding(dp(host, 6), 0, dp(host, 6), 0);
    GradientDrawable strip = SharedPopupSurface.solid(theme.surface, dp(host, 16));
    strip.setStroke(dp(host, 1), theme.divider);
    actionBar.setBackground(strip);
    actionBar.setClipToOutline(true);
    actionBar.setContentDescription("下载历史多选操作");
    ImageButton pauseResume = SharedPopupSurface.actionButton(host, R.drawable.ic_pause, "暂停或继续所选未完成下载任务", theme.accent);
    ImageButton cancel = SharedPopupSurface.actionButton(host, R.drawable.ic_close, "取消所选未完成下载任务", theme.accent);
    ImageButton retry = SharedPopupSurface.actionButton(host, R.drawable.ic_refresh, "重试所选失败下载任务", theme.accent);
    ImageButton deleteRecords = SharedPopupSurface.actionButton(host, R.drawable.ic_delete_record, "删除所选下载记录", theme.accent);
    ImageButton deleteFiles = SharedPopupSurface.actionButton(host, R.drawable.ic_delete_file, "删除所选本地文件", theme.accent);
    for (ImageButton button : new ImageButton[]{pauseResume, cancel, retry, deleteRecords, deleteFiles}) {
      actionBar.addView(button, new LinearLayout.LayoutParams(0, dp(host, 48), 1));
    }
    LinearLayout.LayoutParams actionParams = new LinearLayout.LayoutParams(-1, 0);
    actionBar.setVisibility(View.GONE);

    LinearLayout.LayoutParams areaParams = new LinearLayout.LayoutParams(-1, listHeight(host, initial.size()));
    panel.addView(area, areaParams);
    panel.addView(actionBar, actionParams);

    AlertDialog dialog = new AlertDialog.Builder(host)
        .setTitle("下载历史 · " + initial.size())
        .setView(panel)
        .setNegativeButton("关闭", null)
        .create();

    dialog.setOwnerActivity(host);
    HistoryPopupNavigation.attach(host, dialog, panel, HistoryPopupNavigation.DOWNLOADS,
        theme.accent, theme.text, HistoryPopupNavigation.browserOpener(host));
    HistoryPopupNavigation.PageHost pages = new HistoryPopupNavigation.PageHost(host, area);
    pages.setRoot(rootPage);

    Session session = new Session(host, dialog, pages, rootPage, list, summary, selectAll, actionBar,
        actionParams, pauseResume, cancel, retry, deleteRecords, deleteFiles, theme, area, areaParams);
    session.reload(true);

    selectAll.setOnClickListener(v -> {
      session.selectionMode = !session.selectionMode;
      if (!session.selectionMode) session.selected.clear();
      session.render();
    });
    pauseResume.setOnClickListener(v -> session.applySelection(Session.KIND_PAUSE_RESUME));
    cancel.setOnClickListener(v -> session.applySelection(Session.KIND_CANCEL));
    retry.setOnClickListener(v -> session.applySelection(Session.KIND_RETRY));
    deleteRecords.setOnClickListener(v -> confirm(host, theme, "删除所选记录？",
        "将删除所选下载记录，本地文件会保留。进行中的任务会先取消。", "删除记录",
        () -> session.applySelection(Session.KIND_DELETE_RECORDS)));
    deleteFiles.setOnClickListener(v -> confirm(host, theme, "删除所选文件？",
        "将删除所选本地文件；删除成功或文件已不存在时同步清理记录，失败记录保留。", "删除文件",
        () -> session.applySelection(Session.KIND_DELETE_FILES)));

    SharedPopupSurface.styleDialog(host, dialog, theme, () -> session.render());

    final Handler handler = new Handler(Looper.getMainLooper());
    final Runnable tick = new Runnable() {
      @Override public void run() {
        if (!dialog.isShowing()) return;
        session.reload(false);
        handler.postDelayed(this, REFRESH_MS);
      }
    };
    dialog.setOnDismissListener(ignored -> handler.removeCallbacks(tick));
    dialog.show();
    handler.postDelayed(tick, REFRESH_MS);
    return true;
  }

  /** Mutable presentation state for one popup instance; records always come from the shared center. */
  private static final class Session {
    static final int KIND_PAUSE_RESUME = 0, KIND_CANCEL = 1, KIND_RETRY = 2, KIND_DELETE_RECORDS = 3, KIND_DELETE_FILES = 4;

    final Activity host;
    final AlertDialog dialog;
    final HistoryPopupNavigation.PageHost pages;
    final View rootPage;
    final LinearLayout list;
    final TextView summary, selectAll;
    final LinearLayout actionBar;
    final LinearLayout.LayoutParams actionParams;
    final ImageButton pauseResume, cancel, retry, deleteRecords, deleteFiles;
    final SharedPopupSurface.Theme theme;
    final FrameLayout area;
    final LinearLayout.LayoutParams areaParams;
    final List<DownloadAggregateView.Item> items = new ArrayList<>();
    final LinkedHashSet<String> selected = new LinkedHashSet<>();
    boolean selectionMode;
    String signature = "";
    /** Item whose detail page is on top of the page stack, or null while the list is on top. */
    DownloadAggregateView.Item detail;
    /** The shared batch detail page on top of the stack, built once and re-bound on every reload. */
    DownloadAggregateView.BatchPage detailPage;

    Session(Activity host, AlertDialog dialog, HistoryPopupNavigation.PageHost pages, View rootPage,
        LinearLayout list, TextView summary, TextView selectAll, LinearLayout actionBar,
        LinearLayout.LayoutParams actionParams, ImageButton pauseResume, ImageButton cancel,
        ImageButton retry, ImageButton deleteRecords, ImageButton deleteFiles, SharedPopupSurface.Theme theme,
        FrameLayout area, LinearLayout.LayoutParams areaParams) {
      this.host = host;
      this.dialog = dialog;
      this.pages = pages;
      this.rootPage = rootPage;
      this.list = list;
      this.summary = summary;
      this.selectAll = selectAll;
      this.actionBar = actionBar;
      this.actionParams = actionParams;
      this.pauseResume = pauseResume;
      this.cancel = cancel;
      this.retry = retry;
      this.deleteRecords = deleteRecords;
      this.deleteFiles = deleteFiles;
      this.theme = theme;
      this.area = area;
      this.areaParams = areaParams;
    }

    void reload(boolean force) {
      List<DownloadHistoryCenter.Row> records = DownloadHistoryCenter.rows(host);
      List<DownloadAggregateView.Item> latest = DownloadAggregateView.items(records);
      String next = signature(latest);
      if (!force && next.equals(signature)) {
        sync();
        return;
      }
      signature = next;
      items.clear();
      items.addAll(latest);
      dialog.setTitle("下载历史 · " + records.size());
      areaParams.height = listHeight(host, items.size());
      area.setLayoutParams(areaParams);
      detail = findDetail();
      if (pages.current() != rootPage && detail == null) leaveDetail();
      render();
      renderDetail();
    }

    /** Keeps the open detail page bound to the reloaded item with the same key. */
    private DownloadAggregateView.Item findDetail() {
      if (detail == null) return null;
      String key = detail.key();
      for (DownloadAggregateView.Item item : items) if (item.key().equals(key)) return item;
      return null;
    }

    void render() {
      list.removeAllViews();
      LinkedHashSet<String> alive = new LinkedHashSet<>();
      for (DownloadAggregateView.Item item : items) alive.add(item.key());
      selected.retainAll(alive);
      int shown = 0;
      for (final DownloadAggregateView.Item item : items) {
        if (shown++ >= MAX_ROWS) break;
        list.addView(DownloadAggregateView.rowView(host, theme, item, icon(item),
            selectionMode, selected.contains(item.key()),
            () -> openItem(item), () -> toggle(item), () -> actions(item)),
            new LinearLayout.LayoutParams(-1, -2));
      }
      if (items.size() > MAX_ROWS) {
        TextView footer = SharedPopupSurface.label(host, "仅显示最近 " + MAX_ROWS + " 项", 11, theme.muted);
        footer.setGravity(Gravity.CENTER);
        list.addView(footer, new LinearLayout.LayoutParams(-1, dp(host, 40)));
      }
      if (items.isEmpty()) {
        TextView empty = SharedPopupSurface.label(host, "暂无下载记录", 13, theme.muted);
        empty.setGravity(Gravity.CENTER);
        list.addView(empty, new LinearLayout.LayoutParams(-1, dp(host, 80)));
      }
      sync();
    }

    private ImageView icon(DownloadAggregateView.Item item) {
      DownloadAggregateView.Record first = item.records.get(0);
      ImageView view = new ImageView(host);
      view.setImageResource(DownloadHistoryCenter.fallbackIcon(item.title(), ""));
      view.setColorFilter(theme.accent);
      view.setScaleType(ImageView.ScaleType.CENTER_INSIDE);
      view.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
      view.setContentDescription(first.name());
      return view;
    }

    private void toggle(DownloadAggregateView.Item item) {
      if (!selected.remove(item.key())) selected.add(item.key());
      if (selected.isEmpty()) selectionMode = false;
      render();
    }

    private void actions(DownloadAggregateView.Item item) {
      DownloadAggregateView.showActions(host, theme, item, () -> reload(true));
    }

    /** Enters the record detail page inside the same dialog, keeping the list page alive below. */
    void openDetail(DownloadAggregateView.Item item) {
      detail = item;
      detailPage = null;
      pages.push(describe(item));
      renderDetail();
    }

    /** Single records keep their one-tap action; batches open the record detail page. */
    private void openItem(DownloadAggregateView.Item item) {
      if (item.batched() || item.records.size() > 1) openDetail(item);
      else tapRecord(item.records.get(0));
    }

    private View describe(DownloadAggregateView.Item item) {
      LinearLayout page = new LinearLayout(host);
      page.setOrientation(LinearLayout.VERTICAL);
      LinearLayout bar = new LinearLayout(host);
      bar.setGravity(Gravity.CENTER_VERTICAL);
      ImageButton back = SharedPopupSurface.actionButton(host, R.drawable.ic_back, "返回下载历史列表", theme.accent);
      back.setOnClickListener(v -> leaveDetail());
      TextView title = SharedPopupSurface.label(host, item.title(), 15, theme.text);
      title.setSingleLine(true);
      title.setEllipsize(android.text.TextUtils.TruncateAt.END);
      bar.addView(back, new LinearLayout.LayoutParams(dp(host, 48), dp(host, 48)));
      bar.addView(title, new LinearLayout.LayoutParams(0, dp(host, 48), 1));
      page.addView(bar, new LinearLayout.LayoutParams(-1, dp(host, 48)));
      ScrollView scroll = new ScrollView(host);
      scroll.setClipToPadding(false);
      detailPage = DownloadAggregateView.batchPage(host, theme, item, detailHost());
      scroll.addView(detailPage.root, new ScrollView.LayoutParams(-1, -2));
      page.addView(scroll, new LinearLayout.LayoutParams(-1, 0, 1));
      return page;
    }

    private void leaveDetail() {
      detail = null;
      detailPage = null;
      pages.pop();
    }

    /** Re-binds the shared detail page when it is the visible page, keeping filters and selection. */
    void renderDetail() {
      if (detail == null || detailPage == null || pages.current() == rootPage) return;
      detailPage.bind(detail);
    }

    /**
     * The batch detail page wiring of this popup: the same four batch-wide actions as the download
     * page plus the history-only retry, and a selection bar that keeps every history capability.
     */
    private DownloadAggregateView.BatchHost detailHost() {
      List<DownloadAggregateView.BatchButton> batchButtons = new ArrayList<>();
      batchButtons.add(DownloadAggregateView.BatchButton.of(R.drawable.ic_pause, "暂停批量下载全部未完成项目",
          (item, chosen) -> runDetailAction(chosen, KIND_PAUSE_RESUME)));
      batchButtons.add(DownloadAggregateView.BatchButton.of(R.drawable.ic_close, "取消批量下载全部未完成项目",
          (item, chosen) -> runDetailAction(chosen, KIND_CANCEL)));
      batchButtons.add(DownloadAggregateView.BatchButton.of(R.drawable.ic_delete_record, "删除批量下载全部记录",
          (item, chosen) -> runDetailAction(chosen, KIND_DELETE_RECORDS)));
      batchButtons.add(DownloadAggregateView.BatchButton.of(R.drawable.ic_delete_file, "删除批量下载全部文件",
          (item, chosen) -> runDetailAction(chosen, KIND_DELETE_FILES)));

      List<DownloadAggregateView.BatchButton> selectionButtons = new ArrayList<>();
      selectionButtons.add(DownloadAggregateView.BatchButton.of(R.drawable.ic_pause,
          "暂停或继续所选未完成下载任务", SharedDownloadHistoryPopup::anyPausable,
          (item, chosen) -> runDetailAction(chosen, KIND_PAUSE_RESUME)));
      selectionButtons.add(DownloadAggregateView.BatchButton.of(R.drawable.ic_close, "取消所选未完成下载任务",
          SharedDownloadHistoryPopup::anyCancellable, (item, chosen) -> runDetailAction(chosen, KIND_CANCEL)));
      selectionButtons.add(DownloadAggregateView.BatchButton.of(R.drawable.ic_refresh, "重试所选失败下载任务",
          SharedDownloadHistoryPopup::anyRetryable, (item, chosen) -> runDetailAction(chosen, KIND_RETRY)));
      selectionButtons.add(DownloadAggregateView.BatchButton.of(R.drawable.ic_delete_record, "删除所选下载记录",
          chosen -> !chosen.isEmpty(), (item, chosen) -> runDetailAction(chosen, KIND_DELETE_RECORDS)));
      selectionButtons.add(DownloadAggregateView.BatchButton.of(R.drawable.ic_delete_file, "删除所选本地文件",
          SharedDownloadHistoryPopup::anyFile, (item, chosen) -> runDetailAction(chosen, KIND_DELETE_FILES)));
      return new DownloadAggregateView.BatchHost(selectionButtons, batchButtons);
    }

    /** One detail page action: run it over the given records, then re-read the shared store. */
    private void runDetailAction(List<DownloadAggregateView.Record> chosen, int kind) {
      applyRecords(chosen, kind);
      reload(true);
    }

    private void tapRecord(DownloadAggregateView.Record record) {
      int action = record.primaryAction();
      if (action == DownloadHistoryCenter.ACTION_NONE) {
        Toast.makeText(host, record.metricsText().replace('\n', '，') + "，当前没有可执行的下载操作",
            Toast.LENGTH_SHORT).show();
        return;
      }
      if (record.perform(host, action)) {
        Toast.makeText(host, actionLabel(action, record.name()), Toast.LENGTH_SHORT).show();
        reload(true);
      } else {
        Toast.makeText(host, "该操作当前不可用", Toast.LENGTH_SHORT).show();
      }
    }

    void sync() {
      List<DownloadAggregateView.Item> chosen = chosenItems();
      boolean anyPausable = false, anyCancellable = false, anyRetryable = false, anyFile = false;
      boolean anyActive = false, anyPaused = false;
      for (DownloadAggregateView.Item item : chosen) {
        for (DownloadAggregateView.Record record : item.records) {
          if (record.canCancel()) anyCancellable = true;
          if (record.canRetry()) anyRetryable = true;
          if (record.canDeleteFile()) anyFile = true;
          if (record.active()) anyActive = true;
          else if (record.paused()) anyPaused = true;
          if (record.active() || record.paused()) anyPausable = true;
        }
      }
      boolean has = !chosen.isEmpty();
      SharedPopupSurface.styleButton(pauseResume, theme.accent, theme.muted, has && anyPausable);
      SharedPopupSurface.styleButton(cancel, theme.accent, theme.muted, has && anyCancellable);
      SharedPopupSurface.styleButton(retry, theme.accent, theme.muted, has && anyRetryable);
      SharedPopupSurface.styleButton(deleteRecords, theme.accent, theme.muted, has);
      SharedPopupSurface.styleButton(deleteFiles, theme.accent, theme.muted, has && anyFile);
      pauseResume.setImageResource(anyActive ? R.drawable.ic_pause : R.drawable.ic_play);
      pauseResume.setContentDescription(anyActive ? "暂停所选未完成下载任务"
          : anyPaused ? "继续所选暂停下载任务" : "所选记录没有可暂停或继续的下载任务");
      actionParams.height = selectionMode ? dp(host, 56) : 0;
      actionBar.setVisibility(selectionMode ? View.VISIBLE : View.GONE);
      summary.setText(selectionMode ? "已选 " + chosen.size() + " / " + items.size()
          : "共 " + items.size() + " 项");
      selectAll.setText(selectionMode ? "退出多选" : "多选");
      selectAll.setContentDescription(selectionMode ? "退出下载历史多选" : "进入下载历史多选");
    }

    List<DownloadAggregateView.Item> chosenItems() {
      List<DownloadAggregateView.Item> chosen = new ArrayList<>();
      for (DownloadAggregateView.Item item : items) if (selected.contains(item.key())) chosen.add(item);
      return chosen;
    }

    void applySelection(int kind) {
      List<DownloadAggregateView.Record> records = new ArrayList<>();
      for (DownloadAggregateView.Item item : chosenItems()) records.addAll(item.records);
      if (records.isEmpty()) return;
      applyRecords(records, kind);
      selected.clear();
      selectionMode = false;
      reload(true);
    }

    /** Runs one action over the given records, reports the outcome, and leaves multi-select. */
    private void applyRecords(List<DownloadAggregateView.Record> chosen, int kind) {
      if (chosen.isEmpty()) return;
      int done = 0, blocked = 0;
      if (kind == KIND_PAUSE_RESUME) {
        boolean pause = false;
        for (DownloadAggregateView.Record record : chosen) if (record.active()) pause = true;
        for (DownloadAggregateView.Record record : chosen) {
          if (pause ? !record.active() : !record.paused()) continue;
          if (record.perform(host, pause ? DownloadHistoryCenter.ACTION_PAUSE : DownloadHistoryCenter.ACTION_RESUME)) done++;
        }
        Toast.makeText(host, done == 0 ? "所选记录没有可暂停或继续的任务"
            : (pause ? "已暂停 " : "已继续 ") + done + " 项", Toast.LENGTH_SHORT).show();
      } else if (kind == KIND_CANCEL) {
        for (DownloadAggregateView.Record record : chosen) {
          if (!record.canCancel()) continue;
          if (record.perform(host, DownloadHistoryCenter.ACTION_CANCEL)) done++;
        }
        Toast.makeText(host, done == 0 ? "所选记录没有可取消的任务" : "已取消 " + done + " 项任务", Toast.LENGTH_SHORT).show();
      } else if (kind == KIND_RETRY) {
        for (DownloadAggregateView.Record record : chosen) {
          if (record.perform(host, DownloadHistoryCenter.ACTION_RETRY)) done++;
        }
        Toast.makeText(host, done == 0 ? "所选记录没有可重试的任务" : "已重试 " + done + " 项任务", Toast.LENGTH_SHORT).show();
      } else if (kind == KIND_DELETE_RECORDS) {
        for (DownloadAggregateView.Record record : chosen) {
          if (record.active() || record.paused()) record.perform(host, DownloadHistoryCenter.ACTION_CANCEL);
          if (record.perform(host, DownloadHistoryCenter.ACTION_DELETE_RECORD)) done++;
        }
        Toast.makeText(host, "已删除 " + done + " 条下载记录", Toast.LENGTH_SHORT).show();
      } else {
        for (DownloadAggregateView.Record record : chosen) {
          if (!record.canDeleteFile()) continue;
          if (record.perform(host, DownloadHistoryCenter.ACTION_DELETE_FILE)) done++;
          else blocked++;
        }
        Toast.makeText(host, blocked == 0 ? "已删除 " + done + " 项本地文件"
            : "已删除 " + done + " 项，另有 " + blocked + " 项缺少删除权限或删除失败", Toast.LENGTH_SHORT).show();
      }
      if (detailPage != null) detailPage.exitSelection();
    }
  }

  private static boolean anyPausable(List<DownloadAggregateView.Record> chosen) {
    for (DownloadAggregateView.Record record : chosen) if (record.active() || record.paused()) return true;
    return false;
  }

  private static boolean anyCancellable(List<DownloadAggregateView.Record> chosen) {
    for (DownloadAggregateView.Record record : chosen) if (record.canCancel()) return true;
    return false;
  }

  private static boolean anyRetryable(List<DownloadAggregateView.Record> chosen) {
    for (DownloadAggregateView.Record record : chosen) if (record.canRetry()) return true;
    return false;
  }

  private static boolean anyFile(List<DownloadAggregateView.Record> chosen) {
    for (DownloadAggregateView.Record record : chosen) if (record.canDeleteFile()) return true;
    return false;
  }

  private static String actionLabel(int action, String name) {
    switch (action) {
      case DownloadHistoryCenter.ACTION_OPEN: return "打开 " + name;
      case DownloadHistoryCenter.ACTION_PAUSE: return "已暂停 " + name;
      case DownloadHistoryCenter.ACTION_RESUME: return "已继续 " + name;
      case DownloadHistoryCenter.ACTION_RETRY: return "已重试 " + name;
      default: return "已处理 " + name;
    }
  }

  private static String signature(List<DownloadAggregateView.Item> items) {
    StringBuilder out = new StringBuilder();
    for (DownloadAggregateView.Item item : items) {
      out.append(item.key()).append('|').append(DownloadAggregateView.percent(item)).append('|')
          .append(DownloadAggregateView.metrics(item)).append(';');
    }
    return out.toString();
  }

  private static int listHeight(Activity host, int size) {
    return dp(host, 96) + Math.min(Math.max(size, 1), 5) * dp(host, 76);
  }

  private static void confirm(Activity host, SharedPopupSurface.Theme theme, String title, String message,
      String positive, Runnable action) {
    AlertDialog prompt = new AlertDialog.Builder(host)
        .setTitle(title)
        .setMessage(message)
        .setNegativeButton("取消", null)
        .setPositiveButton(positive, (dialog, which) -> action.run())
        .create();
    SharedPopupSurface.styleDialog(host, prompt, theme, null);
    prompt.show();
  }

  private static int dp(Activity host, int value) {
    return SharedPopupSurface.dp(host, value);
  }
}
