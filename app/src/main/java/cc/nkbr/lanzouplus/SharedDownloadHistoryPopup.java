package cc.nkbr.lanzouplus;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.res.ColorStateList;
import android.graphics.Color;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.RippleDrawable;
import android.os.Handler;
import android.os.Looper;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.view.WindowManager;
import android.widget.CheckBox;
import android.widget.ImageButton;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;

/**
 * One shared download-history surface for every host Activity.
 *
 * <p>Rows and actions come from {@link DownloadHistoryCenter}, so the Web page, the settings page
 * and the download page all expose the same state-driven actions (open / pause / resume / cancel /
 * retry / delete record / delete file). The dialog always belongs to the calling Activity: no other
 * Activity is started to show it and the transfer engine keeps its single owner.</p>
 */
final class SharedDownloadHistoryPopup {
  private static final int MAX_ROWS = 50, REFRESH_MS = 400;

  private SharedDownloadHistoryPopup() {}

  static boolean show(Activity host) {
    if (host == null || host.isFinishing()) return false;
    List<DownloadHistoryCenter.Row> initial = DownloadHistoryCenter.rows(host);
    int accent = themeColor(host, android.R.attr.colorAccent, 0xff6750a4);
    int text = themeColor(host, android.R.attr.textColorPrimary, Color.DKGRAY);
    int surface = themeColor(host, android.R.attr.colorBackground, Color.WHITE);
    int divider = Color.argb(60, Color.red(text), Color.green(text), Color.blue(text));
    int muted = Color.argb(190, Color.red(text), Color.green(text), Color.blue(text));

    LinearLayout panel = new LinearLayout(host);
    panel.setOrientation(LinearLayout.VERTICAL);
    panel.setPadding(dp(host, 12), dp(host, 2), dp(host, 12), 0);

    LinearLayout header = new LinearLayout(host);
    header.setGravity(Gravity.CENTER_VERTICAL);
    TextView summary = label(host, "", 12, accent);
    summary.setMaxLines(1);
    TextView selectAll = label(host, "多选", 13, accent);
    selectAll.setGravity(Gravity.CENTER);
    selectAll.setClickable(true);
    selectAll.setFocusable(true);
    selectAll.setMinWidth(dp(host, 64));
    header.addView(summary, new LinearLayout.LayoutParams(0, dp(host, 44), 1));
    header.addView(selectAll, new LinearLayout.LayoutParams(dp(host, 76), dp(host, 48)));
    panel.addView(header, new LinearLayout.LayoutParams(-1, dp(host, 48)));

    ScrollView scroll = new ScrollView(host);
    scroll.setClipToPadding(false);
    LinearLayout list = new LinearLayout(host);
    list.setOrientation(LinearLayout.VERTICAL);
    scroll.addView(list, new ScrollView.LayoutParams(-1, -2));
    panel.addView(scroll, new LinearLayout.LayoutParams(-1, listHeight(host, initial.size())));

    LinearLayout actionBar = new LinearLayout(host);
    actionBar.setGravity(Gravity.CENTER_VERTICAL);
    actionBar.setPadding(dp(host, 6), 0, dp(host, 6), 0);
    GradientDrawable strip = solid(surface, dp(host, 16));
    strip.setStroke(dp(host, 1), divider);
    actionBar.setBackground(strip);
    actionBar.setClipToOutline(true);
    actionBar.setContentDescription("下载历史多选操作");
    ImageButton pauseResume = actionButton(host, R.drawable.ic_pause, "暂停或继续所选未完成下载任务", accent);
    ImageButton cancel = actionButton(host, R.drawable.ic_close, "取消所选未完成下载任务", accent);
    ImageButton retry = actionButton(host, R.drawable.ic_refresh, "重试所选失败下载任务", accent);
    ImageButton deleteRecords = actionButton(host, R.drawable.ic_delete_record, "删除所选下载记录", accent);
    ImageButton deleteFiles = actionButton(host, R.drawable.ic_delete_file, "删除所选本地文件", accent);
    for (ImageButton button : new ImageButton[]{pauseResume, cancel, retry, deleteRecords, deleteFiles}) {
      actionBar.addView(button, new LinearLayout.LayoutParams(0, dp(host, 48), 1));
    }
    panel.addView(actionBar, new LinearLayout.LayoutParams(-1, dp(host, 56)));

    AlertDialog dialog = new AlertDialog.Builder(host)
        .setTitle("下载历史 · " + initial.size())
        .setView(panel)
        .setNegativeButton("关闭", null)
        .create();

    dialog.setOwnerActivity(host);
    HistoryPopupNavigation.attach(host,dialog,panel,HistoryPopupNavigation.DOWNLOADS,accent,text,HistoryPopupNavigation.browserOpener(host));
    Session session = new Session(host, dialog, list, summary, selectAll,
        pauseResume, cancel, retry, deleteRecords, deleteFiles, accent, text, surface, muted, divider);
    session.reload(true);

    selectAll.setOnClickListener(v -> {
      session.selectionMode = !session.selectionMode;
      if (!session.selectionMode) session.selected.clear();
      session.render();
    });
    pauseResume.setOnClickListener(v -> session.applySelection(Session.KIND_PAUSE_RESUME));
    cancel.setOnClickListener(v -> session.applySelection(Session.KIND_CANCEL));
    retry.setOnClickListener(v -> session.applySelection(Session.KIND_RETRY));
    deleteRecords.setOnClickListener(v -> confirm(host, surface, accent, muted, "删除所选记录？",
        "将删除所选下载记录，本地文件会保留。进行中的任务会先取消。", "删除记录",
        () -> session.applySelection(Session.KIND_DELETE_RECORDS)));
    deleteFiles.setOnClickListener(v -> confirm(host, surface, accent, muted, "删除所选文件？",
        "将删除所选本地文件；删除成功或文件已不存在时同步清理记录，失败记录保留。", "删除文件",
        () -> session.applySelection(Session.KIND_DELETE_FILES)));

    styleDialog(host, dialog, surface, accent, muted, () -> session.render());

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

  /** Mutable presentation state for one popup instance; rows always come from the shared center. */
  private static final class Session {
    static final int KIND_PAUSE_RESUME = 0, KIND_CANCEL = 1, KIND_RETRY = 2, KIND_DELETE_RECORDS = 3, KIND_DELETE_FILES = 4;

    final Activity host;
    final AlertDialog dialog;
    final LinearLayout list;
    final TextView summary, selectAll;
    final ImageButton pauseResume, cancel, retry, deleteRecords, deleteFiles;
    final int accent, text, surface, muted, divider;
    final List<DownloadHistoryCenter.Row> rows = new ArrayList<>();
    final LinkedHashSet<String> selected = new LinkedHashSet<>();
    boolean selectionMode;
    String signature = "";

    Session(Activity host, AlertDialog dialog, LinearLayout list, TextView summary, TextView selectAll,
        ImageButton pauseResume, ImageButton cancel, ImageButton retry, ImageButton deleteRecords, ImageButton deleteFiles,
        int accent, int text, int surface, int muted, int divider) {
      this.host = host;
      this.dialog = dialog;
      this.list = list;
      this.summary = summary;
      this.selectAll = selectAll;
      this.pauseResume = pauseResume;
      this.cancel = cancel;
      this.retry = retry;
      this.deleteRecords = deleteRecords;
      this.deleteFiles = deleteFiles;
      this.accent = accent;
      this.text = text;
      this.surface = surface;
      this.muted = muted;
      this.divider = divider;
    }

    void reload(boolean force) {
      List<DownloadHistoryCenter.Row> latest = DownloadHistoryCenter.rows(host);
      String next = signature(latest);
      if (!force && next.equals(signature)) {
        sync();
        return;
      }
      signature = next;
      rows.clear();
      rows.addAll(latest);
      dialog.setTitle("下载历史 · " + rows.size());
      render();
    }

    void render() {
      list.removeAllViews();
      LinkedHashSet<String> alive = new LinkedHashSet<>();
      for (DownloadHistoryCenter.Row row : rows) alive.add(key(row));
      selected.retainAll(alive);
      int shown = 0;
      for (DownloadHistoryCenter.Row row : rows) {
        if (shown++ >= MAX_ROWS) break;
        list.addView(buildRow(row), new LinearLayout.LayoutParams(-1, -2));
      }
      if (rows.size() > MAX_ROWS) {
        TextView footer = label(host, "仅显示最近 " + MAX_ROWS + " 条记录", 11, muted);
        footer.setGravity(Gravity.CENTER);
        list.addView(footer, new LinearLayout.LayoutParams(-1, dp(host, 40)));
      }
      sync();
    }

    private View buildRow(DownloadHistoryCenter.Row row) {
      String rowKey = key(row);
      LinearLayout rowView = new LinearLayout(host);
      rowView.setGravity(Gravity.CENTER_VERTICAL);
      rowView.setPadding(dp(host, 4), dp(host, 4), dp(host, 4), dp(host, 4));
      rowView.setMinimumHeight(dp(host, 74));
      CheckBox check = new CheckBox(host);
      check.setClickable(false);
      check.setFocusable(false);
      check.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
      check.setChecked(selected.contains(rowKey));
      check.setVisibility(selectionMode ? View.VISIBLE : View.INVISIBLE);
      rowView.addView(check, new LinearLayout.LayoutParams(dp(host, 48), dp(host, 48)));
      ImageView icon = new ImageView(host);
      icon.setImageResource(DownloadHistoryCenter.fallbackIcon(row.name, row.mimeType));
      icon.setColorFilter(accent);
      icon.setScaleType(ImageView.ScaleType.CENTER_INSIDE);
      icon.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
      rowView.addView(icon, new LinearLayout.LayoutParams(dp(host, 38), dp(host, 38)));
      LinearLayout words = new LinearLayout(host);
      words.setOrientation(LinearLayout.VERTICAL);
      words.setPadding(dp(host, 10), 0, dp(host, 4), 0);
      TextView name = label(host, row.name, 13, text);
      name.setSingleLine(true);
      name.setEllipsize(android.text.TextUtils.TruncateAt.END);
      TextView metrics = label(host, DownloadHistoryCenter.metrics(row), 10, muted);
      metrics.setMaxLines(3);
      metrics.setEllipsize(android.text.TextUtils.TruncateAt.END);
      ProgressBar bar = new ProgressBar(host, null, android.R.attr.progressBarStyleHorizontal);
      bar.setMax(100);
      bar.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
      boolean parsing = row.live && MainActivity.DOWNLOAD_RESOLVING.equals(row.state);
      bar.setIndeterminate(parsing);
      if (!parsing) bar.setProgress(row.percent);
      words.addView(name, new LinearLayout.LayoutParams(-1, dp(host, 22)));
      words.addView(metrics, new LinearLayout.LayoutParams(-1, dp(host, 40)));
      words.addView(bar, new LinearLayout.LayoutParams(-1, dp(host, 4)));
      rowView.addView(words, new LinearLayout.LayoutParams(0, dp(host, 70), 1));
      rowView.setBackground(ripple(accent, new ColorDrawable(Color.TRANSPARENT)));
      rowView.setClickable(true);
      rowView.setFocusable(true);
      rowView.setContentDescription(describe(row, selected.contains(rowKey)));
      rowView.setOnClickListener(v -> {
        if (selectionMode) {
          if (!selected.remove(rowKey)) selected.add(rowKey);
          render();
          return;
        }
        int action = row.primaryAction();
        if (action == DownloadHistoryCenter.ACTION_NONE) {
          Toast.makeText(host, DownloadHistoryCenter.displayState(row) + "，当前没有可执行的下载操作", Toast.LENGTH_SHORT).show();
          return;
        }
        if (DownloadHistoryCenter.perform(host, row, action)) {
          Toast.makeText(host, actionLabel(action, row), Toast.LENGTH_SHORT).show();
          reload(true);
        } else {
          Toast.makeText(host, "该操作当前不可用", Toast.LENGTH_SHORT).show();
        }
      });
      rowView.setOnLongClickListener(v -> {
        selectionMode = true;
        selected.add(rowKey);
        render();
        return true;
      });
      return rowView;
    }

    void sync() {
      List<DownloadHistoryCenter.Row> chosen = chosenRows();
      boolean anyPausable = false, anyCancellable = false, anyRetryable = false, anyFile = false;
      boolean anyActive = false, anyPaused = false;
      for (DownloadHistoryCenter.Row row : chosen) {
        if (row.canPauseResume()) anyPausable = true;
        if (row.canCancel()) anyCancellable = true;
        if (row.canRetry()) anyRetryable = true;
        if (row.canDeleteFile()) anyFile = true;
        if (row.active()) anyActive = true;
        else if (row.paused()) anyPaused = true;
      }
      boolean has = !chosen.isEmpty();
      styleButton(pauseResume, accent, muted, has && anyPausable);
      styleButton(cancel, accent, muted, has && anyCancellable);
      styleButton(retry, accent, muted, has && anyRetryable);
      styleButton(deleteRecords, accent, muted, has);
      styleButton(deleteFiles, accent, muted, has && anyFile);
      pauseResume.setImageResource(anyActive ? R.drawable.ic_pause : R.drawable.ic_play);
      pauseResume.setContentDescription(anyActive ? "暂停所选未完成下载任务"
          : anyPaused ? "继续所选暂停下载任务" : "所选记录没有可暂停或继续的下载任务");
      summary.setText(selectionMode ? "已选 " + chosen.size() + " / " + rows.size()
          : "共 " + rows.size() + " 条记录");
      selectAll.setText(selectionMode ? "退出多选" : "多选");
      selectAll.setContentDescription(selectionMode ? "退出下载历史多选" : "进入下载历史多选");
    }

    void applySelection(int kind) {
      List<DownloadHistoryCenter.Row> chosen = chosenRows();
      if (chosen.isEmpty()) return;
      int done = 0, blocked = 0;
      if (kind == KIND_PAUSE_RESUME) {
        boolean pause = false;
        for (DownloadHistoryCenter.Row row : chosen) if (row.active()) pause = true;
        for (DownloadHistoryCenter.Row row : chosen) {
          if (DownloadHistoryCenter.perform(host, row, pause ? DownloadHistoryCenter.ACTION_PAUSE : DownloadHistoryCenter.ACTION_RESUME)) done++;
        }
        Toast.makeText(host, done == 0 ? "所选记录没有可暂停或继续的任务" : (pause ? "已暂停 " : "已继续 ") + done + " 项", Toast.LENGTH_SHORT).show();
      } else if (kind == KIND_CANCEL) {
        for (DownloadHistoryCenter.Row row : chosen) if (DownloadHistoryCenter.perform(host, row, DownloadHistoryCenter.ACTION_CANCEL)) done++;
        Toast.makeText(host, done == 0 ? "所选记录没有可取消的任务" : "已取消 " + done + " 项任务", Toast.LENGTH_SHORT).show();
      } else if (kind == KIND_RETRY) {
        for (DownloadHistoryCenter.Row row : chosen) if (DownloadHistoryCenter.perform(host, row, DownloadHistoryCenter.ACTION_RETRY)) done++;
        Toast.makeText(host, done == 0 ? "所选记录没有可重试的任务" : "已重试 " + done + " 项任务", Toast.LENGTH_SHORT).show();
      } else if (kind == KIND_DELETE_RECORDS) {
        for (DownloadHistoryCenter.Row row : chosen) if (DownloadHistoryCenter.perform(host, row, DownloadHistoryCenter.ACTION_DELETE_RECORD)) done++;
        Toast.makeText(host, "已删除 " + done + " 条下载记录", Toast.LENGTH_SHORT).show();
      } else {
        for (DownloadHistoryCenter.Row row : chosen) {
          if (!row.canDeleteFile()) continue;
          if (DownloadHistoryCenter.perform(host, row, DownloadHistoryCenter.ACTION_DELETE_FILE)) done++;
          else blocked++;
        }
        Toast.makeText(host, blocked == 0 ? "已删除 " + done + " 项本地文件"
            : "已删除 " + done + " 项，另有 " + blocked + " 项缺少删除权限或删除失败", Toast.LENGTH_SHORT).show();
      }
      selected.clear();
      reload(true);
    }

    List<DownloadHistoryCenter.Row> chosenRows() {
      List<DownloadHistoryCenter.Row> chosen = new ArrayList<>();
      for (DownloadHistoryCenter.Row row : rows) if (selected.contains(key(row))) chosen.add(row);
      return chosen;
    }
  }

  private static String describe(DownloadHistoryCenter.Row row, boolean selected) {
    StringBuilder out = new StringBuilder();
    out.append(row.name).append('，').append(DownloadHistoryCenter.displayState(row));
    if (row.percent > 0 && !row.completed()) out.append('，').append(row.percent).append('%');
    if (selected) out.append("，已选择");
    out.append("，轻点执行操作，长按进入多选");
    return out.toString();
  }

  private static String actionLabel(int action, DownloadHistoryCenter.Row row) {
    switch (action) {
      case DownloadHistoryCenter.ACTION_OPEN: return "打开 " + row.name;
      case DownloadHistoryCenter.ACTION_PAUSE: return "已暂停 " + row.name;
      case DownloadHistoryCenter.ACTION_RESUME: return "已继续 " + row.name;
      case DownloadHistoryCenter.ACTION_RETRY: return "已重试 " + row.name;
      default: return "已处理 " + row.name;
    }
  }

  private static String signature(List<DownloadHistoryCenter.Row> rows) {
    StringBuilder out = new StringBuilder();
    for (DownloadHistoryCenter.Row row : rows) {
      out.append(key(row)).append('|').append(row.state).append('|').append(row.percent).append('|')
          .append(row.downloadedBytes).append('|').append(DownloadHistoryCenter.displayState(row)).append(';');
    }
    return out.toString();
  }

  private static String key(DownloadHistoryCenter.Row row) {
    if (row == null) return "";
    if (row.live && row.entry != null) return "live:" + System.identityHashCode(row.entry);
    return "saved:" + row.createdAt + "|" + row.name + "|" + row.uriString;
  }

  private static int listHeight(Activity host, int size) {
    return Math.min(dp(host, 360), dp(host, 96) + Math.min(Math.max(size, 1), 5) * dp(host, 76));
  }

  private static void confirm(Activity host, int surface, int accent, int muted, String title, String message,
      String positive, Runnable action) {
    AlertDialog prompt = new AlertDialog.Builder(host)
        .setTitle(title)
        .setMessage(message)
        .setNegativeButton("取消", null)
        .setPositiveButton(positive, (dialog, which) -> action.run())
        .create();
    styleDialog(host, prompt, surface, accent, muted, null);
    prompt.show();
  }

  /** Rounded, themed dialog surface shared by every popup in this class. */
  private static void styleDialog(Activity host, AlertDialog dialog, int surface, int accent, int muted, Runnable after) {
    dialog.setOnShowListener(ignored -> {
      Window window = dialog.getWindow();
      if (window != null) {
        window.setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
        WindowManager.LayoutParams params = window.getAttributes();
        params.gravity = Gravity.CENTER;
        params.x = 0;
        params.y = 0;
        params.width = Math.max(dp(host, 280),
            Math.min(dp(host, 560), host.getResources().getDisplayMetrics().widthPixels - dp(host, 28)));
        params.height = WindowManager.LayoutParams.WRAP_CONTENT;
        window.setAttributes(params);
      }
      View content = dialog.findViewById(android.R.id.content);
      View surfaceView = content;
      if (content instanceof ViewGroup && ((ViewGroup) content).getChildCount() > 0) {
        surfaceView = ((ViewGroup) content).getChildAt(0);
      }
      if (surfaceView != null) {
        surfaceView.setBackground(solid(surface, dp(host, 22)));
        surfaceView.setClipToOutline(true);
        surfaceView.setElevation(dp(host, 10));
      }
      android.widget.Button close = dialog.getButton(AlertDialog.BUTTON_NEGATIVE);
      if (close != null) close.setTextColor(muted);
      android.widget.Button positive = dialog.getButton(AlertDialog.BUTTON_POSITIVE);
      if (positive != null) positive.setTextColor(accent);
      if (after != null) after.run();
    });
  }

  private static void styleButton(ImageButton button, int tint, int muted, boolean enabled) {
    button.setEnabled(enabled);
    button.setAlpha(enabled ? 1f : .38f);
    button.setColorFilter(enabled ? tint : muted);
  }

  private static ImageButton actionButton(Activity host, int icon, String description, int tint) {
    ImageButton button = new ImageButton(host);
    button.setImageResource(icon);
    button.setColorFilter(tint);
    button.setScaleType(ImageView.ScaleType.CENTER_INSIDE);
    button.setPadding(dp(host, 12), dp(host, 12), dp(host, 12), dp(host, 12));
    button.setBackgroundColor(Color.TRANSPARENT);
    button.setContentDescription(description);
    button.setMinimumWidth(0);
    button.setMinimumHeight(0);
    return button;
  }

  private static Drawable ripple(int tint, Drawable content) {
    return new RippleDrawable(ColorStateList.valueOf(Color.argb(36, Color.red(tint), Color.green(tint), Color.blue(tint))), content, null);
  }

  private static GradientDrawable solid(int color, int radius) {
    GradientDrawable shape = new GradientDrawable();
    shape.setColor(color);
    shape.setCornerRadius(radius);
    return shape;
  }

  private static TextView label(Activity host, String value, int size, int color) {
    TextView text = new TextView(host);
    text.setText(value);
    text.setTextColor(color);
    text.setTextSize(TypedValue.COMPLEX_UNIT_SP, size);
    text.setGravity(Gravity.START | Gravity.CENTER_VERTICAL);
    return text;
  }

  private static int themeColor(Activity host, int attribute, int fallback) {
    TypedValue result = new TypedValue();
    if (!host.getTheme().resolveAttribute(attribute, result, true)) return fallback;
    if (result.resourceId != 0) {
      try {
        return host.getResources().getColor(result.resourceId, host.getTheme());
      } catch (android.content.res.Resources.NotFoundException ignored) {
        return fallback;
      }
    }
    return result.data;
  }

  private static int dp(Activity host, float value) {
    return (int) (value * host.getResources().getDisplayMetrics().density + .5f);
  }
}
