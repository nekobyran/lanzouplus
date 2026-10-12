package cc.nkbr.lanzouplus;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.drawable.ColorDrawable;
import android.text.InputType;
import android.view.Gravity;
import android.view.View;
import android.view.inputmethod.EditorInfo;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.ImageButton;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import org.json.JSONArray;
import org.json.JSONObject;

/**
 * One "join a path" picker shared by the download page, the Web page and the shared browser popup.
 *
 * <p>The picker navigates categories, self-built collections and their folders, selects several
 * destinations at once, creates folders in place and reports destinations that already contain the
 * chosen sources. Every read and write goes through {@link Catalog}, so the same dialog runs in any
 * host: {@code MainActivity} passes a catalog over its live core and category map, while a host with
 * no session state uses {@link #catalog(Activity)} and keeps the shared preference store as the
 * single source of truth.</p>
 */
final class SourceDestinationPopup {
  private static final String CATEGORY_PREFS = "source_categories", CATEGORY_KEY = "items";

  private SourceDestinationPopup() {}

  /** Everything the picker needs from its host; all calls happen on the host thread. */
  interface Catalog {
    List<Models.Source> sources() throws Exception;

    /** Category name to member source ids; "全部" is implied and never listed. */
    Map<String, Set<String>> categories();

    List<Models.SourceMember> folders(Models.Source composite, String nodeId);

    Models.Source createFolder(Models.Source composite, String nodeId, String title) throws Exception;

    void addNodes(Models.Source composite, String nodeId, List<String[]> links) throws Exception;

    Models.Source addSource(String url, String password, String title) throws Exception;

    void addCategoryMembers(String category, Set<String> sourceIds);

    /** Reports the finished submit: one summary line plus whether it needs the error styling. */
    void applied(String summary, boolean failed);

    /** Called once the user confirmed the picker, before any write starts. */
    default void submitting() {}

    /** Called when the picker closes; catalogs owning temporary resources release them here. */
    default void release() {}
  }

  /** One selectable destination: a whole category, or one folder inside a self-built collection. */
  static final class Destination {
    final String category, nodeId, path;
    final Models.Source composite;
    /** Member source ids of a category destination; null for directory destinations. */
    final Set<String> membership;

    Destination(String category, Models.Source composite, String nodeId, String path) {
      this(category, composite, nodeId, path, null);
    }

    Destination(String category, Set<String> membership, String path) {
      this(category, null, "", path, membership);
    }

    private Destination(String category, Models.Source composite, String nodeId, String path, Set<String> membership) {
      this.category = category == null ? "" : category;
      this.composite = composite;
      this.nodeId = nodeId == null ? "" : nodeId;
      this.path = path == null ? "" : path;
      this.membership = membership;
    }

    boolean categoryOnly() {
      return composite == null && !category.isEmpty();
    }

    String key() {
      return categoryOnly() ? "category:" + category
          : "directory:" + LanzouCore.sourceId(composite) + ":" + nodeId;
    }

    String name() {
      return path.substring(path.lastIndexOf('›') + 1).trim();
    }
  }

  /**
   * Shows the picker for the given sources and items. {@code sources} are stored or pending
   * sources, {@code items} are catalogue items whose share link is added to the chosen folders.
   */
  static AlertDialog show(Activity host, Catalog catalog, List<Models.Source> sourceInput, List<Models.Item> itemInput) {
    if (host == null || host.isFinishing() || catalog == null) return null;
    SharedPopupSurface.Theme theme = new SharedPopupSurface.Theme(host);
    List<Models.Source> available;
    try {
      available = catalog.sources();
    } catch (Exception error) {
      catalog.release();
      catalog.applied("无法读取分类目录", true);
      return null;
    }
    final List<Models.Source> sources = new ArrayList<>(sourceInput == null ? Collections.emptyList() : sourceInput);
    final List<Models.Item> items = new ArrayList<>(itemInput == null ? Collections.emptyList() : itemInput);
    final LinkedHashMap<String, Destination> selected = new LinkedHashMap<>();
    final ArrayList<Destination> stack = new ArrayList<>();
    final Destination[] current = {null};

    LinearLayout panel = new LinearLayout(host);
    panel.setOrientation(LinearLayout.VERTICAL);
    panel.setPadding(SharedPopupSurface.dp(host, 10), SharedPopupSurface.dp(host, 4),
        SharedPopupSurface.dp(host, 10), 0);

    LinearLayout locationBar = new LinearLayout(host);
    locationBar.setGravity(Gravity.CENTER_VERTICAL);
    ImageButton back = SharedPopupSurface.actionButton(host, R.drawable.ic_back, "返回上一级", theme.accent);
    ImageButton createFolder = SharedPopupSurface.actionButton(host, R.drawable.ic_add, "在当前路径创建文件夹", theme.accent);
    TextView path = SharedPopupSurface.label(host, "当前路径：分类", 13, theme.text);
    path.setSingleLine(true);
    path.setEllipsize(android.text.TextUtils.TruncateAt.START);
    locationBar.addView(back, new LinearLayout.LayoutParams(SharedPopupSurface.dp(host, 44), SharedPopupSurface.dp(host, 44)));
    locationBar.addView(path, new LinearLayout.LayoutParams(0, SharedPopupSurface.dp(host, 44), 1));
    locationBar.addView(createFolder, new LinearLayout.LayoutParams(SharedPopupSurface.dp(host, 44), SharedPopupSurface.dp(host, 44)));
    panel.addView(locationBar, new LinearLayout.LayoutParams(-1, SharedPopupSurface.dp(host, 44)));

    TextView summary = SharedPopupSurface.label(host, "已选 0 个路径", 12, theme.accent);
    panel.addView(summary, new LinearLayout.LayoutParams(-1, SharedPopupSurface.dp(host, 34)));

    LinearLayout choices = new LinearLayout(host);
    choices.setOrientation(LinearLayout.VERTICAL);
    ScrollView scroll = new ScrollView(host);
    scroll.setVerticalScrollBarEnabled(true);
    scroll.addView(choices, new ScrollView.LayoutParams(-1, -2));
    panel.addView(scroll, new LinearLayout.LayoutParams(-1, Math.min(SharedPopupSurface.dp(host, 330),
        Math.max(SharedPopupSurface.dp(host, 180), host.getResources().getDisplayMetrics().heightPixels
            - SharedPopupSurface.dp(host, 350)))));

    AlertDialog dialog = new AlertDialog.Builder(host)
        .setTitle("批量选择加入路径")
        .setView(panel)
        .setNegativeButton("取消", null)
        .setPositiveButton("添加到所选路径", null)
        .create();

    dialog.setOwnerActivity(host);
    final Button[] submit = {null};
    final Runnable[] update = {null}, render = {null};
    update[0] = () -> {
      summary.setText("已选 " + selected.size() + " 个路径");
      summary.setContentDescription("提交前摘要，已选 " + selected.size() + " 个路径");
      if (submit[0] != null) submit[0].setEnabled(!selected.isEmpty() && (!sources.isEmpty() || !items.isEmpty()));
    };
    render[0] = () -> {
      choices.removeAllViews();
      Destination here = current[0];
      path.setText("当前路径：" + (here == null ? "分类" : here.path));
      back.setEnabled(here != null);
      createFolder.setEnabled(here != null && !here.categoryOnly());
      LinkedHashMap<String, Destination> level = new LinkedHashMap<>();
      if (here == null) {
        level.putIfAbsent("全部", new Destination("全部", null, "", "全部"));
        for (Map.Entry<String, Set<String>> category : catalog.categories().entrySet()) {
          Destination destination = new Destination(category.getKey(), category.getValue(), category.getKey());
          level.putIfAbsent(category.getKey().toLowerCase(Locale.ROOT), destination);
        }
      } else if (here.categoryOnly()) {
        Set<String> ids = here.category.equals("全部") ? null : catalog.categories().get(here.category);
        for (Models.Source source : available) {
          if (!MainActivity.compositeSource(source)) continue;
          if (ids != null && !ids.contains(LanzouCore.sourceId(source))) continue;
          String title = source.title.isEmpty() ? "自建目录" : source.title;
          Destination destination = new Destination(here.category, source, "", here.category + " › " + title);
          level.putIfAbsent(destination.key(), destination);
        }
      } else {
        for (Models.SourceMember member : catalog.folders(here.composite, here.nodeId)) {
          Destination destination = new Destination(here.category, here.composite, member.id, here.path + " › " + member.title);
          level.putIfAbsent(destination.key(), destination);
        }
      }
      final Runnable updateNow = update[0];
      for (Destination destination : level.values()) {
        Runnable enter = () -> {
          stack.add(current[0]);
          current[0] = destination;
          render[0].run();
        };
        boolean alreadyAdded = containsAll(destination, sources);
        choices.addView(destinationRow(host, theme, destination, selected, enter, updateNow, alreadyAdded),
            new LinearLayout.LayoutParams(-1, SharedPopupSurface.dp(host, 50)));
      }
      if (level.isEmpty()) {
        TextView empty = SharedPopupSurface.label(host, "此位置没有下级自建目录", 12, theme.muted);
        empty.setGravity(Gravity.CENTER);
        choices.addView(empty, new LinearLayout.LayoutParams(-1, SharedPopupSurface.dp(host, 72)));
      }
      scroll.scrollTo(0, 0);
      updateNow.run();
    };
    back.setOnClickListener(v -> {
      if (current[0] == null) return;
      current[0] = stack.isEmpty() ? null : stack.remove(stack.size() - 1);
      render[0].run();
    });
    createFolder.setOnClickListener(v -> {
      Destination here = current[0];
      if (here == null || here.categoryOnly()) return;
      createFolderDialog(host, catalog, theme, here, render[0]);
    });

    dialog.setOnDismissListener(ignored -> catalog.release());
    // Prepare destinations and the submit callback before the first frame.
    SharedPopupSurface.styleDialog(host, dialog, theme, () -> {
      submit[0] = dialog.getButton(AlertDialog.BUTTON_POSITIVE);
      submit[0].setOnClickListener(v -> {
        if (selected.isEmpty()) return;
        List<Destination> destinations = new ArrayList<>(selected.values());
        catalog.submitting();
        dialog.dismiss();
        apply(host, catalog, sources, items, destinations);
      });
      render[0].run();
    });
    dialog.show();
    return dialog;
  }

  /**
   * Shows the picker with the default catalog over the shared store, releasing the temporary core
   * it may create as soon as the dialog closes, whether the user submitted or cancelled.
   */
  static AlertDialog show(Activity host, List<Models.Source> sources, List<Models.Item> items) {
    if (host == null || host.isFinishing()) return null;
    DetachedCatalog catalog = new DetachedCatalog(host);
    AlertDialog dialog = show(host, catalog, sources, items);
    if (dialog == null) catalog.release();
    return dialog;
  }

  /**
   * One web page as a picker item. A favourite is a non-Lanzou web source, so the item is submitted
   * through the same picker as any other source: a chosen source category stores it as
   * {@link Models#SOURCE_WEB} in the source list through {@link Catalog#addSource}, and a chosen
   * self-built directory stores it as a member of that path. The Web page, the favourites popup and
   * the external favourite action all build the item here, so one definition owns its meaning.
   */
  static Models.Item webFavoriteItem(String url, String title) {
    String value = url == null ? "" : url.trim();
    Models.Item item = new Models.Item();
    item.title = title == null || title.trim().isEmpty() ? value : title.trim();
    item.url = item.shareUrl = value;
    item.description = "收藏链接";
    item.source = "收藏";
    return item;
  }

  /**
   * Shows the shared path picker for one web page on the calling Activity, so favouriting a page
   * really adds it to the list sources and to an optional path. The picker never starts another
   * Activity, which keeps a favourite taken on the Web page on that Web page.
   */
  static AlertDialog showWebFavorite(Activity host, String url, String title) {
    String value = url == null ? "" : url.trim();
    if (value.isEmpty()) return null;
    return show(host, Collections.emptyList(), Collections.singletonList(webFavoriteItem(value, title)));
  }

  /** True when every chosen source is already a member of the destination category. */
  private static boolean containsAll(Destination destination, List<Models.Source> sources) {
    if (!destination.categoryOnly() || destination.category.equals("全部")) return false;
    if (sources.isEmpty() || destination.membership == null) return false;
    for (Models.Source source : sources) {
      if (!destination.membership.contains(LanzouCore.sourceId(source))) return false;
    }
    return true;
  }

  private static View destinationRow(Activity host, SharedPopupSurface.Theme theme, final Destination destination,
      final Map<String, Destination> selected, final Runnable enter, final Runnable changed, final boolean alreadyAdded) {
    LinearLayout row = new LinearLayout(host);
    row.setGravity(Gravity.CENTER_VERTICAL);
    row.setPadding(SharedPopupSurface.dp(host, 4), 0, SharedPopupSurface.dp(host, 4), 0);
    row.setBackground(SharedPopupSurface.ripple(theme.accent, new ColorDrawable(android.graphics.Color.TRANSPARENT)));
    if (alreadyAdded) {
      TextView checked = SharedPopupSurface.label(host, "✓", 19, theme.accent);
      checked.setGravity(Gravity.CENTER);
      checked.setContentDescription("已添加到 " + destination.path);
      row.addView(checked, new LinearLayout.LayoutParams(SharedPopupSurface.dp(host, 44), SharedPopupSurface.dp(host, 48)));
    } else {
      CheckBox pick = new CheckBox(host);
      pick.setButtonTintList(android.content.res.ColorStateList.valueOf(theme.accent));
      pick.setChecked(selected.containsKey(destination.key()));
      pick.setContentDescription("选择路径 " + destination.path);
      pick.setOnCheckedChangeListener((button, value) -> {
        if (value) selected.put(destination.key(), destination);
        else selected.remove(destination.key());
        changed.run();
      });
      row.addView(pick, new LinearLayout.LayoutParams(SharedPopupSurface.dp(host, 44), SharedPopupSurface.dp(host, 48)));
    }
    ImageView folder = new ImageView(host);
    folder.setImageResource(R.drawable.ic_folder);
    folder.setColorFilter(theme.accent);
    folder.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
    row.addView(folder, new LinearLayout.LayoutParams(SharedPopupSurface.dp(host, 24), SharedPopupSurface.dp(host, 24)));
    TextView name = SharedPopupSurface.label(host, destination.name(), 13, theme.text);
    name.setSingleLine(true);
    name.setEllipsize(android.text.TextUtils.TruncateAt.END);
    name.setPadding(SharedPopupSurface.dp(host, 10), 0, SharedPopupSurface.dp(host, 6), 0);
    row.addView(name, new LinearLayout.LayoutParams(0, SharedPopupSurface.dp(host, 48), 1));
    ImageButton open = SharedPopupSurface.actionButton(host, R.drawable.ic_expand,
        "进入 " + destination.name(), theme.accent);
    open.setRotation(-90f);
    open.setOnClickListener(v -> enter.run());
    row.addView(open, new LinearLayout.LayoutParams(SharedPopupSurface.dp(host, 42), SharedPopupSurface.dp(host, 48)));
    name.setClickable(true);
    name.setOnClickListener(v -> enter.run());
    row.setContentDescription("路径 " + destination.path + (alreadyAdded ? "，已添加" : "，可勾选或进入"));
    return row;
  }

  private static void createFolderDialog(Activity host, final Catalog catalog, SharedPopupSurface.Theme theme,
      final Destination parent, final Runnable onCreated) {
    EditText name = new EditText(host);
    name.setSingleLine(true);
    name.setHint("文件夹名称");
    name.setContentDescription("文件夹名称");
    name.setTextColor(theme.text);
    name.setHintTextColor(theme.muted);
    name.setTextSize(14);
    name.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_CAP_SENTENCES);
    name.setImeOptions(EditorInfo.IME_ACTION_NEXT | EditorInfo.IME_FLAG_NO_EXTRACT_UI);
    LinearLayout panel = new LinearLayout(host);
    panel.setPadding(SharedPopupSurface.dp(host, 22), SharedPopupSurface.dp(host, 8), SharedPopupSurface.dp(host, 22), 0);
    panel.addView(name, new LinearLayout.LayoutParams(-1, SharedPopupSurface.dp(host, 56)));
    AlertDialog prompt = new AlertDialog.Builder(host)
        .setTitle("在当前路径创建文件夹")
        .setView(panel)
        .setNegativeButton("取消", null)
        .setPositiveButton("创建", null)
        .create();
    SharedPopupSurface.styleDialog(host, prompt, theme, () -> prompt.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v -> {
      String value = name.getText().toString().trim();
      if (value.isEmpty()) {
        name.setError("请输入文件夹名称");
        return;
      }
      Button create = prompt.getButton(AlertDialog.BUTTON_POSITIVE);
      create.setEnabled(false);
      run(host, () -> {
        Models.Source updated = catalog.createFolder(parent.composite, parent.nodeId, value);
        prompt.dismiss();
        if (updated != null) copySource(updated, parent.composite);
        onCreated.run();
        catalog.applied("文件夹已创建", false);
      }, error -> {
        create.setEnabled(true);
        name.setError(friendly(error));
      });
    }));
    prompt.show();
  }

  /**
   * Applies the chosen destinations: stores pending sources for category targets, adds every share
   * link to every chosen folder, and reports added, duplicate and failed counts.
   */
  static void apply(final Activity host, final Catalog catalog, final List<Models.Source> sources,
      final List<Models.Item> items, final List<Destination> destinations) {
    if (destinations.isEmpty()) return;
    run(host, () -> {
      boolean categoryTarget = false;
      for (Destination destination : destinations) categoryTarget |= destination.categoryOnly();
      LinkedHashMap<String, String[]> links = new LinkedHashMap<>();
      LinkedHashSet<String> sourceIds = new LinkedHashSet<>();
      int addedDirectories = 0, duplicates = 0, failed = 0;
      List<Models.Source> known = catalog.sources();
      LinkedHashMap<String, Models.Source> knownById = new LinkedHashMap<>(), knownByUrl = new LinkedHashMap<>();
      for (Models.Source source : known) {
        knownById.put(LanzouCore.sourceId(source), source);
        if (!source.url.isEmpty()) knownByUrl.put(source.url, source);
      }
      for (Models.Source source : sources) {
        String requestedId = source.kind == Models.SOURCE_COMPOSITE && !source.id.isEmpty()
            ? source.id : LanzouCore.sourceId(source);
        Models.Source knownSource = knownById.get(requestedId);
        if (knownSource != null) {
          sourceIds.add(LanzouCore.sourceId(knownSource));
        } else if (categoryTarget && !source.url.isEmpty()) {
          try {
            Models.Source stored = catalog.addSource(source.url, source.password, source.title);
            sourceIds.add(LanzouCore.sourceId(stored));
            knownById.put(LanzouCore.sourceId(stored), stored);
            knownByUrl.put(stored.url, stored);
          } catch (Exception error) {
            failed++;
          }
        }
        if (!source.url.isEmpty()) links.putIfAbsent(source.url + '\n' + source.password, new String[]{source.url, source.password});
      }
      for (Models.Item item : items) {
        if (item.sourceEntry) {
          Models.Source stored = knownById.get(item.sourceId);
          if (stored != null) {
            sourceIds.add(LanzouCore.sourceId(stored));
            if (!stored.url.isEmpty()) links.putIfAbsent(stored.url + '\n' + stored.password, new String[]{stored.url, stored.password});
            continue;
          }
        }
        String url = item.shareUrl.isEmpty() ? item.url : item.shareUrl;
        if (url.isEmpty() || !url.toLowerCase(Locale.ROOT).startsWith("http")) continue;
        links.putIfAbsent(url + '\n' + item.password, new String[]{url, item.password});
        if (!categoryTarget) continue;
        Models.Source knownSource = knownByUrl.get(url);
        if (knownSource != null) {
          sourceIds.add(LanzouCore.sourceId(knownSource));
          continue;
        }
        try {
          Models.Source stored = catalog.addSource(url, item.password, item.title);
          sourceIds.add(LanzouCore.sourceId(stored));
          knownByUrl.put(stored.url, stored);
        } catch (Exception error) {
          failed++;
        }
      }
      for (Destination destination : destinations) {
        if (destination.categoryOnly()) continue;
        for (String[] link : links.values()) {
          try {
            catalog.addNodes(destination.composite, destination.nodeId, Collections.singletonList(link));
            addedDirectories++;
          } catch (Exception error) {
            String message = error.getMessage() == null ? "" : error.getMessage();
            if (message.contains("已在当前文件夹")) duplicates++;
            else failed++;
          }
        }
      }
      int categoryAdds = 0;
      for (Destination destination : destinations) {
        if (!destination.categoryOnly()) continue;
        Set<String> members = catalog.categories().get(destination.category);
        if (members == null) continue;
        int before = members.size();
        catalog.addCategoryMembers(destination.category, sourceIds);
        Set<String> updated = catalog.categories().get(destination.category);
        if (updated != null) categoryAdds += updated.size() - before;
      }
      StringBuilder summary = new StringBuilder();
      summary.append("已处理 ").append(destinations.size()).append(" 个路径")
          .append(" · 分类新增 ").append(categoryAdds)
          .append(" · 目录新增 ").append(addedDirectories);
      if (duplicates > 0) summary.append(" · 重复 ").append(duplicates);
      if (failed > 0) summary.append(" · 失败 ").append(failed);
      catalog.applied(summary.toString(), failed > 0);
    }, error -> catalog.applied("批量加入失败：" + friendly(error), true));
  }

  /** Runs work off the UI thread and delivers the result back on the host thread. */
  private static void run(Activity host, Task task, Failure failure) {
    Thread worker = new Thread(() -> {
      try {
        task.run();
      } catch (Exception error) {
        host.runOnUiThread(() -> failure.failed(error));
      }
    }, "source-destination");
    worker.setDaemon(true);
    worker.setPriority(Thread.MIN_PRIORITY);
    worker.start();
  }

  private interface Task {
    void run() throws Exception;
  }

  private interface Failure {
    void failed(Exception error);
  }

  private static String friendly(Exception error) {
    String message = error == null ? "" : error.getMessage();
    if (message == null || message.trim().isEmpty()) message = error == null ? "未知错误" : error.getClass().getSimpleName();
    message = message.replace('\n', ' ').replace('\r', ' ').trim();
    return message.length() > 96 ? message.substring(0, 96) + "…" : message;
  }

  private static void copySource(Models.Source from, Models.Source to) {
    MainActivity.copySource(from, to);
  }

  /**
   * Default catalog for hosts without a live session: the shared {@code source_categories} store
   * plus the given core. The main Activity uses its own catalog so its in-memory category map stays
   * authoritative.
   */
  static Catalog catalog(Activity host, LanzouCore core) {
    return new StoredCatalog(host, core);
  }

  /**
   * Default catalog for hosts without a live session. It borrows the running main core when one
   * exists and otherwise creates its own, which it closes on {@link Catalog#release()}. A submit in
   * flight keeps the catalog open until the work reports back through {@link Catalog#applied}, so a
   * dismissal never pulls the core out from under an apply.
   */
  static final class DetachedCatalog extends StoredCatalog {
    private final LanzouCore owned;
    private boolean submitted, released;

    /** Shutdown performed on release; the default closes the owned core. Tests observe this call. */
    Runnable shutdown = this::closeOwnedCore;

    private void closeOwnedCore() {
      if (owned != null) owned.close();
    }

    DetachedCatalog(Activity host) {
      this(host, resolveCore(host));
    }

    DetachedCatalog(Activity host, LanzouCore core) {
      super(host, core);
      MainActivity owner = DownloadHistoryCenter.liveOwner();
      owned = owner != null && owner.core == core ? null : core;
    }

    private static LanzouCore resolveCore(Activity host) {
      MainActivity owner = DownloadHistoryCenter.liveOwner();
      if (owner != null && owner.core != null) return owner.core;
      return new LanzouCore(host.getApplicationContext());
    }

    /** True once the owned core was handed back; tests use it to prove the cancel path closes. */
    boolean released() {
      return released;
    }

    /** The core this catalog closes on release, or null when it borrowed the live main core. */
    LanzouCore ownedCore() {
      return owned;
    }

    @Override public void submitting() {
      submitted = true;
    }

    @Override public void applied(String summary, boolean failed) {
      super.applied(summary, failed);
      close();
    }

    @Override public void release() {
      if (submitted) return;
      close();
    }

    private void close() {
      if (released) return;
      released = true;
      shutdown.run();
    }
  }

  /**
   * Preference codec for the shared category store. It keeps the whole group record — name,
   * description and member ids — so adding members never drops a description the source page wrote,
   * and it migrates the legacy {@code urls} array and stored aliases into member ids.
   */
  static final class Categories {
    final LinkedHashMap<String, LinkedHashSet<String>> members = new LinkedHashMap<>();
    final LinkedHashMap<String, String> descriptions = new LinkedHashMap<>();

    static Categories parse(String raw, Map<String, String> aliases) {
      Categories store = new Categories();
      JSONArray groups = new JSONArray();
      try {
        groups = new JSONArray(raw == null ? "[]" : raw);
      } catch (Exception ignored) {
      }
      for (int i = 0; i < groups.length(); i++) {
        JSONObject group = groups.optJSONObject(i);
        if (group == null) continue;
        String name = group.optString("name").trim();
        if (name.isEmpty() || name.equals("全部") || store.members.containsKey(name)) continue;
        LinkedHashSet<String> ids = new LinkedHashSet<>();
        JSONArray values = group.optJSONArray("ids");
        if (values == null) values = group.optJSONArray("urls");
        if (values != null) {
          for (int j = 0; j < values.length(); j++) {
            String stored = values.optString(j);
            if (stored.isEmpty()) continue;
            ids.add(aliases == null ? stored : aliases.getOrDefault(stored, stored));
          }
        }
        store.members.put(name, ids);
        store.descriptions.put(name, group.optString("description"));
      }
      return store;
    }

    String write() {
      JSONArray groups = new JSONArray();
      try {
        for (Map.Entry<String, LinkedHashSet<String>> group : members.entrySet()) {
          groups.put(new JSONObject()
              .put("name", group.getKey())
              .put("description", descriptions.getOrDefault(group.getKey(), ""))
              .put("ids", new JSONArray(group.getValue())));
        }
      } catch (Exception ignored) {
      }
      return groups.toString();
    }
  }

  /** Preference-backed catalog: LanzouCore for sources and composites, prefs for categories. */
  private static class StoredCatalog implements Catalog {
    private final Activity host;
    private final LanzouCore core;
    private final SharedPreferences prefs;
    private final Categories store;

    StoredCatalog(Activity host, LanzouCore core) {
      this.host = host;
      this.core = core;
      prefs = host.getApplicationContext().getSharedPreferences(CATEGORY_PREFS, Context.MODE_PRIVATE);
      LinkedHashMap<String, String> aliases = new LinkedHashMap<>();
      try {
        for (Models.Source source : core.sources()) {
          aliases.put(source.url, LanzouCore.sourceId(source));
          if (!source.id.isEmpty()) aliases.put(source.id, LanzouCore.sourceId(source));
        }
      } catch (Exception ignored) {
      }
      store = Categories.parse(prefs.getString(CATEGORY_KEY, "[]"), aliases);
    }

    @Override public List<Models.Source> sources() throws Exception {
      return core.sources();
    }

    @Override public Map<String, Set<String>> categories() {
      return Collections.unmodifiableMap(new LinkedHashMap<String, Set<String>>(store.members));
    }

    @Override public List<Models.SourceMember> folders(Models.Source composite, String nodeId) {
      List<Models.SourceMember> out = new ArrayList<>();
      if (composite == null) return out;
      for (Models.SourceMember member : composite.members) {
        if (member.kind != Models.MEMBER_FOLDER || member.title.isEmpty() || !member.parentId.equals(nodeId)) continue;
        out.add(member);
      }
      return out;
    }

    @Override public Models.Source createFolder(Models.Source composite, String nodeId, String title) throws Exception {
      return core.addCompositeFolder(LanzouCore.sourceId(composite), nodeId, title);
    }

    @Override public void addNodes(Models.Source composite, String nodeId, List<String[]> links) throws Exception {
      for (String[] link : links) core.addCompositeNode(LanzouCore.sourceId(composite), nodeId, link[0], link[1]);
    }

    @Override public Models.Source addSource(String url, String password, String title) throws Exception {
      Models.Source source=core.addUserSource(url,password);return source.kind==Models.SOURCE_WEB&&!title.isEmpty()?core.updateUserSourceMetadata(LanzouCore.sourceId(source),title,source.avatarUrl,source.description,false):source;
    }

    @Override public void addCategoryMembers(String category, Set<String> sourceIds) {
      LinkedHashSet<String> members = store.members.get(category);
      if (members == null) return;
      members.addAll(sourceIds);
      prefs.edit().putString(CATEGORY_KEY, store.write()).apply();
    }

    @Override public void applied(final String summary, final boolean failed) {
      host.runOnUiThread(() -> {
        if (host instanceof LanzouWebActivity) ((LanzouWebActivity) host).updateFavoriteState();
        MainActivity owner = DownloadHistoryCenter.liveOwner();
        if (owner != null) {
          owner.loadSourceCategories();
          owner.sourceDataRevision++;
        }
        if (!MainActivity.showWebNotice(summary, failed)) {
          Toast.makeText(host, summary, failed ? Toast.LENGTH_LONG : Toast.LENGTH_SHORT).show();
        }
      });
    }
  }
}
