package cc.nkbr.lanzouplus;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.GradientDrawable;
import android.view.Gravity;
import android.view.View;
import android.widget.CheckBox;
import android.widget.ImageButton;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.function.Consumer;
import org.json.JSONArray;
import org.json.JSONObject;

/**
 * Shared browsing history and the existing path picker for every host Activity.
 *
 * <p>The popup owns only presentation; entries live in the shared {@code web-browser-v2} store that
 * {@link LanzouWebActivity} and {@link MainActivity} both read. Opening an entry always uses the
 * caller-supplied callback, so a favorite tapped from the Web page is loaded by that same Web page
 * instead of leaving it. Favourites are stored in the source catalog through the path picker. The multi-select strip follows {@link SharedPopupSurface}, so every shared
 * popup is styled from one place.</p>
 */
final class SharedBrowserPopup {
  private static final String PREFS = "web-browser-v2", HISTORY = "history";
  private static final int MAX_ENTRIES = 120;

  private SharedBrowserPopup() {}

  static void show(Activity host, boolean favoritesMode, Consumer<String> openUrl) {
    if (host == null || host.isFinishing()) return;
    if (favoritesMode) {
      if (host instanceof LanzouWebActivity) {
        LanzouWebActivity web = (LanzouWebActivity) host;
        SourceDestinationPopup.showWebFavorite(host, web.currentUrl, web.currentTitle);
      } else if (host instanceof MainActivity && ((MainActivity) host).activeSource != null) {
        ((MainActivity) host).showCurrentSourceDestinationPicker(((MainActivity) host).activeSource);
      } else {
        SourceDestinationPopup.show(host, Collections.emptyList(), Collections.emptyList());
      }
      return;
    }
    SharedPopupSurface.Theme theme = new SharedPopupSurface.Theme(host);
    SharedPreferences prefs = host.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    String key = HISTORY;
    List<Entry> entries = read(prefs.getString(key, "[]"));

    LinearLayout panel = new LinearLayout(host);
    panel.setOrientation(LinearLayout.VERTICAL);
    panel.setPadding(dp(host, 12), dp(host, 2), dp(host, 12), 0);

    LinearLayout header = new LinearLayout(host);
    header.setGravity(Gravity.CENTER_VERTICAL);
    TextView summary = SharedPopupSurface.label(host, "", 12, theme.accent);
    summary.setMaxLines(1);
    TextView selectAll = SharedPopupSurface.label(host, "多选", 13, theme.accent);
    selectAll.setGravity(Gravity.CENTER);
    selectAll.setSingleLine(true);
    selectAll.setClickable(true);
    selectAll.setFocusable(true);
    selectAll.setMinWidth(dp(host, 64));
    header.addView(summary, new LinearLayout.LayoutParams(0, dp(host, 44), 1));
    header.addView(selectAll,new LinearLayout.LayoutParams(Math.max(dp(host,76),(int)Math.ceil(selectAll.getPaint().measureText("退出多选"))+dp(host,12)),dp(host,48)));
    panel.addView(header, new LinearLayout.LayoutParams(-1, dp(host, 48)));

    ScrollView scroll = new ScrollView(host);
    scroll.setClipToPadding(false);
    LinearLayout list = new LinearLayout(host);
    list.setOrientation(LinearLayout.VERTICAL);
    scroll.addView(list, new ScrollView.LayoutParams(-1, -2));
    panel.addView(scroll, new LinearLayout.LayoutParams(-1,
        Math.min(dp(host, 360), dp(host, 96) + Math.min(Math.max(entries.size(), 1), 5) * dp(host, 66))));

    LinearLayout actionBar = new LinearLayout(host);
    actionBar.setGravity(Gravity.CENTER_VERTICAL);
    actionBar.setPadding(dp(host, 6), 0, dp(host, 6), 0);
    GradientDrawable strip = SharedPopupSurface.solid(theme.surface, dp(host, 16));
    strip.setStroke(dp(host, 1), theme.divider);
    actionBar.setBackground(strip);
    actionBar.setClipToOutline(true);
    actionBar.setContentDescription("浏览历史多选操作");
    ImageButton open = SharedPopupSurface.actionButton(host, R.drawable.ic_open_with, "打开所选网页", theme.accent);
    ImageButton star = SharedPopupSurface.actionButton(host, R.drawable.ic_star,
        "将所选网页加入收藏", theme.accent);
    ImageButton path = SharedPopupSurface.actionButton(host, R.drawable.ic_folder, "将所选网页加入路径", theme.accent);
    ImageButton delete = SharedPopupSurface.actionButton(host, R.drawable.ic_delete_record,
        "删除所选浏览历史", theme.accent);
    for (ImageButton button : new ImageButton[]{open, star, path, delete}) {
      actionBar.addView(button, new LinearLayout.LayoutParams(0, dp(host, 48), 1));
    }
    LinearLayout.LayoutParams actionParams = new LinearLayout.LayoutParams(-1, 0);
    actionBar.setVisibility(View.GONE);
    panel.addView(actionBar, actionParams);

    AlertDialog dialog = new AlertDialog.Builder(host)
        .setTitle("浏览历史")
        .setView(SharedPopupSurface.dialogScroll(host,panel,500))
        .setNegativeButton("关闭", null)
        .create();

    dialog.setOwnerActivity(host);
    HistoryPopupNavigation.attach(host, dialog, panel,
        HistoryPopupNavigation.HISTORY,
        theme.accent, theme.text, openUrl);
    Session session = new Session(host, dialog, list, summary, selectAll, open, star, path, delete,
        actionBar, actionParams, key, theme, openUrl);
    session.reload();

    selectAll.setOnClickListener(v -> {
      if (session.selectionMode) {
        session.selectionMode = false;
        session.selected.clear();
        session.render();
        return;
      }
      session.selectionMode = true;
      for (Entry entry : session.entries) session.selected.add(entry.url);
      session.render();
    });
    open.setOnClickListener(v -> session.openSelected());
    star.setOnClickListener(v -> session.toggleFavoriteSelected());
    path.setOnClickListener(v -> session.addSelectedToPath());
    delete.setOnClickListener(v -> session.deleteSelected());

    SharedPopupSurface.styleDialog(host, dialog, theme, () -> session.render());
    dialog.setOnDismissListener(ignored -> notifyHost(host));
    dialog.show();
  }

  /** A saved source or collection member is the authoritative favourite. */
  static boolean isFavorite(Context context, String url) {
    if (context == null || url == null || url.trim().isEmpty()) return false;
    String value = url.trim();
    try {
      JSONObject stored = new JSONObject(context.getSharedPreferences("user-sources-v1", Context.MODE_PRIVATE)
          .getString("sources", "{\"sources\":[]}"));
      JSONArray sources = stored.optJSONArray("sources");
      if (sources == null) return false;
      for (int i = 0; i < sources.length(); i++) {
        JSONObject source = sources.getJSONObject(i);
        if (value.equals(source.optString("url"))) return true;
        JSONArray members = source.optJSONArray("members");
        if (members == null) continue;
        for (int j = 0; j < members.length(); j++) if (value.equals(members.getJSONObject(j).optString("url"))) return true;
      }
    } catch (org.json.JSONException error) {
      return false;
    }
    return false;
  }

  private static void notifyHost(Activity host) {
    if (host instanceof LanzouWebActivity) {
      ((LanzouWebActivity) host).loadState();
      ((LanzouWebActivity) host).updateFavoriteState();
    }
  }

  /** Mutable presentation state for one popup instance; entries stay in the shared store. */
  private static final class Session {
    final Activity host;
    final AlertDialog dialog;
    final LinearLayout list;
    final TextView summary, selectAll;
    final ImageButton open, star, path, delete;
    final LinearLayout actionBar;
    final LinearLayout.LayoutParams actionParams;
    final String key;
    final SharedPopupSurface.Theme theme;
    final Consumer<String> openUrl;
    final List<Entry> entries = new ArrayList<>();
    final LinkedHashSet<String> selected = new LinkedHashSet<>();
    boolean selectionMode,selectionBarRevealed;

    Session(Activity host, AlertDialog dialog, LinearLayout list, TextView summary, TextView selectAll,
        ImageButton open, ImageButton star, ImageButton path, ImageButton delete, LinearLayout actionBar,
        LinearLayout.LayoutParams actionParams, String key, SharedPopupSurface.Theme theme,
        Consumer<String> openUrl) {
      this.host = host;
      this.dialog = dialog;
      this.list = list;
      this.summary = summary;
      this.selectAll = selectAll;
      this.open = open;
      this.star = star;
      this.path = path;
      this.delete = delete;
      this.actionBar = actionBar;
      this.actionParams = actionParams;
      this.key = key;
      this.theme = theme;
      this.openUrl = openUrl;
    }

    void reload() {
      entries.clear();
      entries.addAll(read(host.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(key, "[]")));
      dialog.setTitle(("浏览历史") + " · " + entries.size());
      render();
    }

    void render() {
      list.removeAllViews();
      LinkedHashSet<String> alive = new LinkedHashSet<>();
      for (Entry entry : entries) alive.add(entry.url);
      selected.retainAll(alive);
      for (Entry entry : entries) list.addView(buildRow(entry), new LinearLayout.LayoutParams(-1, -2));
      if (entries.isEmpty()) {
        TextView empty = SharedPopupSurface.label(host, "暂无浏览历史", 13, theme.muted);
        empty.setGravity(Gravity.CENTER);
        list.addView(empty, new LinearLayout.LayoutParams(-1, dp(host, 80)));
      }
      sync();
    }

    private View buildRow(Entry entry) {
      boolean checked = selected.contains(entry.url);
      LinearLayout row = new LinearLayout(host);
      row.setGravity(Gravity.CENTER_VERTICAL);
      row.setPadding(dp(host, 4), dp(host, 3), dp(host, 4), dp(host, 3));
      row.setMinimumHeight(dp(host, 66));
      CheckBox check = new CheckBox(host);
      check.setClickable(false);
      check.setFocusable(false);
      check.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
      check.setChecked(checked);
      check.setVisibility(selectionMode ? View.VISIBLE : View.INVISIBLE);
      row.addView(check, new LinearLayout.LayoutParams(dp(host, 48), dp(host, 48)));
      LinearLayout words = new LinearLayout(host);
      words.setOrientation(LinearLayout.VERTICAL);
      TextView title = SharedPopupSurface.label(host, entry.title, 14, theme.text);
      title.setSingleLine(true);
      title.setEllipsize(android.text.TextUtils.TruncateAt.END);
      TextView url = SharedPopupSurface.label(host, entry.url, 11, theme.muted);
      url.setSingleLine(true);
      url.setEllipsize(android.text.TextUtils.TruncateAt.MIDDLE);
      TextView moment = SharedPopupSurface.label(host, SharedPopupSurface.moment(entry.at), 10, theme.muted);
      moment.setSingleLine(true);
      title.setMinHeight(dp(host,24));words.addView(title,new LinearLayout.LayoutParams(-1,-2));
      url.setMinHeight(dp(host,20));words.addView(url,new LinearLayout.LayoutParams(-1,-2));
      moment.setMinHeight(dp(host,18));words.addView(moment,new LinearLayout.LayoutParams(-1,-2));
      words.setMinimumHeight(dp(host,62));
      row.addView(words,new LinearLayout.LayoutParams(0,-2,1));
      row.setBackground(SharedPopupSurface.ripple(theme.accent, new ColorDrawable(Color.TRANSPARENT)));
      row.setClickable(true);
      row.setFocusable(true);
      row.setContentDescription(entry.title + "，" + entry.url + (checked ? "，已选择" : "") + "，轻点打开，长按多选");
      row.setOnClickListener(v -> {
        if (selectionMode) {
          if (!selected.remove(entry.url)) selected.add(entry.url);
          if (selected.isEmpty()) selectionMode = false;
          render();
          return;
        }
        openEntry(entry.url);
      });
      row.setOnLongClickListener(v -> {
        selectionMode = true;
        selected.add(entry.url);
        render();
        return true;
      });
      return row;
    }

    void sync() {
      actionParams.height = selectionMode ? dp(host, 56) : 0;
      actionBar.setVisibility(selectionMode ? View.VISIBLE : View.GONE);
      if(selectionMode&&!selectionBarRevealed){selectionBarRevealed=true;actionBar.post(()->actionBar.requestRectangleOnScreen(new android.graphics.Rect(0,0,actionBar.getWidth(),actionBar.getHeight()),true));}else if(!selectionMode)selectionBarRevealed=false;
      boolean single = selected.size() == 1, any = !selected.isEmpty();
      SharedPopupSurface.styleButton(open, theme.accent, theme.muted, single);
      SharedPopupSurface.styleButton(star, theme.accent, theme.muted, any);
      SharedPopupSurface.styleButton(path, theme.accent, theme.muted, any);
      SharedPopupSurface.styleButton(delete, theme.accent, theme.muted, any);
      star.setImageResource(R.drawable.ic_star);
      star.setContentDescription("将所选网页加入收藏");
      summary.setText(selectionMode ? "已选 " + selected.size() + " / " + entries.size()
          : "共 " + entries.size() + " 项");
      selectAll.setText(selectionMode ? "退出多选" : "全选");
      selectAll.setContentDescription(selectionMode ? "退出浏览记录多选" : "全选浏览记录");
    }

    void openSelected() {
      if (selected.size() != 1) return;
      openEntry(selected.iterator().next());
    }

    private void openEntry(String url) {
      if (url == null || url.isEmpty()) return;
      dialog.dismiss();
      if (openUrl != null) openUrl.accept(url);
    }

    /**
     * Sends the chosen pages to the shared path picker in this Activity. The picker never starts
     * another Activity, so a favorite picked on the Web page stays on the Web page.
     */
    void addSelectedToPath() {
      if (selected.isEmpty()) return;
      List<Models.Item> items = new ArrayList<>();
      for (Entry entry : entries) {
        if (!selected.contains(entry.url)) continue;
        items.add(SourceDestinationPopup.webFavoriteItem(entry.url, entry.title));
      }
      if (items.isEmpty()) return;
      SourceDestinationPopup.show(host, Collections.emptyList(), items);
    }

    void toggleFavoriteSelected() {
      addSelectedToPath();
    }

    void deleteSelected() {
      if (selected.isEmpty()) return;
      SharedPreferences prefs = host.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
      List<Entry> kept = new ArrayList<>();
      for (Entry entry : entries) if (!selected.contains(entry.url)) kept.add(entry);
      prefs.edit().putString(key, write(kept)).apply();
      Toast.makeText(host, "已删除 " + selected.size() + " 项", Toast.LENGTH_SHORT).show();
      selected.clear();
      reload();
    }
  }

  private static final class Entry {
    final String title, url;
    final long at;

    Entry(String title, String url, long at) {
      this.title = title == null || title.trim().isEmpty() ? (url == null ? "" : url) : title;
      this.url = url == null ? "" : url;
      this.at = at;
    }
  }

  private static List<Entry> read(String raw) {
    List<Entry> entries = new ArrayList<>();
    try {
      JSONArray array = new JSONArray(raw);
      for (int i = 0; i < array.length() && entries.size() < MAX_ENTRIES; i++) {
        JSONObject item = array.optJSONObject(i);
        if (item == null) continue;
        String url = item.optString("u", "");
        if (!url.isEmpty()) entries.add(new Entry(item.optString("t", url), url, item.optLong("a")));
      }
    } catch (Exception ignored) {
    }
    return entries;
  }

  private static String write(List<Entry> entries) {
    JSONArray output = new JSONArray();
    for (int i = 0; i < entries.size() && i < MAX_ENTRIES; i++) {
      Entry entry = entries.get(i);
      try {
        output.put(new JSONObject().put("t", entry.title).put("u", entry.url).put("a", entry.at));
      } catch (Exception ignored) {
      }
    }
    return output.toString();
  }

  private static int dp(Activity host, int value) {
    return SharedPopupSurface.dp(host, value);
  }
}
