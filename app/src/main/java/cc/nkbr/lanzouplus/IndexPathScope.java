package cc.nkbr.lanzouplus;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;

/**
 * Persisted directory-index scope.
 *
 * <p>An empty entry list means the historical "all Lanzou sources" behaviour. Otherwise the
 * scope keeps the exact picker destinations (for reopening the picker in place) and the frozen
 * official-source snapshots the index walk may visit. A self-built composite is expanded to its
 * directory members only: local folders are grouping nodes, never network roots, and a selected
 * child folder stays a child folder instead of widening to its parent source.</p>
 */
final class IndexPathScope {
  static final int VERSION = 1;
  private static final IndexPathScope ALL = new IndexPathScope(Collections.emptyList(), Collections.emptyList());

  static final class Entry {
    final String category, nodeId, path;
    final Models.Source composite;

    Entry(String category, String nodeId, String path, Models.Source composite) {
      this.category = category == null ? "" : category;
      this.nodeId = nodeId == null ? "" : nodeId;
      this.path = path == null ? "" : path;
      this.composite = composite;
    }
  }

  private final List<Entry> entries;
  private final List<Models.Source> tasks;

  private IndexPathScope(List<Entry> entries, List<Models.Source> tasks) {
    this.entries = Collections.unmodifiableList(new ArrayList<>(entries));
    this.tasks = Collections.unmodifiableList(new ArrayList<>(tasks));
  }

  static IndexPathScope all() {
    return ALL;
  }

  static IndexPathScope of(Collection<Entry> entries, Collection<Models.Source> selected) {
    List<Entry> frozenEntries = new ArrayList<>();
    if (entries != null) for (Entry entry : entries) {
      if (entry == null) continue;
      Models.Source composite = entry.composite == null ? null : LanzouCore.copySource(entry.composite);
      frozenEntries.add(new Entry(entry.category, entry.nodeId, entry.path, composite));
    }
    List<Models.Source> frozenTasks = expandTasks(selected);
    if (frozenEntries.isEmpty()) return ALL;
    return new IndexPathScope(frozenEntries, frozenTasks);
  }

  boolean isAll() {
    return entries.isEmpty();
  }

  List<Entry> entries() {
    return entries;
  }

  /** Frozen official-source snapshots; empty only for {@link #all()}. */
  List<Models.Source> tasks() {
    return tasks;
  }

  String summary() {
    if (isAll()) return "全部蓝奏源";
    return "已选 " + entries.size() + " 个路径 · " + tasks.size() + " 个索引源";
  }

  String detail() {
    if (isAll()) return "全部蓝奏源";
    StringBuilder out = new StringBuilder();
    int shown = Math.min(4, entries.size());
    for (int i = 0; i < shown; i++) {
      if (i > 0) out.append('\n');
      String path = entries.get(i).path == null ? "" : entries.get(i).path.trim();
      out.append(path.isEmpty() ? entries.get(i).category : path);
    }
    if (entries.size() > shown) out.append("\n…另 ").append(entries.size() - shown).append(" 个路径");
    return out.toString();
  }

  String encode() {
    if (isAll()) return "";
    try {
      JSONArray rawEntries = new JSONArray();
      for (Entry entry : entries) {
        JSONObject value = new JSONObject().put("category", entry.category)
            .put("nodeId", entry.nodeId).put("path", entry.path);
        if (entry.composite == null) value.put("composite", JSONObject.NULL);
        else value.put("composite", LanzouCore.sourceJson(entry.composite));
        rawEntries.put(value);
      }
      JSONArray rawTasks = new JSONArray();
      for (Models.Source task : tasks) rawTasks.put(LanzouCore.sourceJson(task));
      return new JSONObject().put("version", VERSION).put("entries", rawEntries)
          .put("tasks", rawTasks).toString();
    } catch (Exception error) {
      return "";
    }
  }

  static IndexPathScope decode(String raw) {
    if (raw == null || raw.trim().isEmpty()) return ALL;
    try {
      JSONObject root = new JSONObject(raw);
      if (root.optInt("version", 0) != VERSION) return ALL;
      JSONArray rawEntries = root.optJSONArray("entries");
      JSONArray rawTasks = root.optJSONArray("tasks");
      if (rawEntries == null || rawEntries.length() == 0) return ALL;
      List<Entry> entries = new ArrayList<>();
      for (int i = 0; i < rawEntries.length(); i++) {
        JSONObject value = rawEntries.optJSONObject(i);
        if (value == null) continue;
        try {
          Models.Source composite = null;
          JSONObject rawComposite = value.optJSONObject("composite");
          if (rawComposite != null) composite = LanzouCore.indexScopeSourceFromJson(rawComposite);
          entries.add(new Entry(value.optString("category"), value.optString("nodeId"),
              value.optString("path"), composite));
        } catch (Exception ignored) {
        }
      }
      List<Models.Source> tasks = new ArrayList<>();
      if (rawTasks != null) for (int i = 0; i < rawTasks.length(); i++) {
        JSONObject value = rawTasks.optJSONObject(i);
        if (value == null) continue;
        try {
          Models.Source task = LanzouCore.indexScopeSourceFromJson(value);
          if (task.kind == Models.SOURCE_OFFICIAL && !task.url.isEmpty()) tasks.add(task);
        } catch (Exception ignored) {
        }
      }
      if (entries.isEmpty()) return ALL;
      return new IndexPathScope(entries, tasks);
    } catch (Exception error) {
      return ALL;
    }
  }

  /**
   * Converts resolved picker sources into index roots. Composite snapshots are already trimmed
   * by SourcePathCatalog, so only their own directory members may become network tasks.
   */
  static List<Models.Source> expandTasks(Collection<Models.Source> selected) {
    LinkedHashMap<String, Models.Source> out = new LinkedHashMap<>();
    if (selected == null) return new ArrayList<>();
    for (Models.Source source : selected) {
      if (source == null) continue;
      if (source.kind == Models.SOURCE_OFFICIAL) {
        addTask(out, source.id, source.url, source.password, source.title, source.searchable,
            source.originPath);
      } else if (source.kind == Models.SOURCE_COMPOSITE) {
        for (Models.SourceMember member : source.members) {
          if (member == null) continue;
          if (member.kind != Models.MEMBER_DIRECTORY && member.kind != Models.MEMBER_REMOTE_FOLDER) continue;
          String url = member.url == null ? "" : member.url.trim();
          if (url.isEmpty() || !LanzouCore.indexableLanzouUrl(url)) continue;
          String title = member.title == null || member.title.trim().isEmpty() ? url : member.title.trim();
          String origin = source.title == null || source.title.isEmpty()
              ? title : source.title + " / " + title;
          addTask(out, url, url, member.password, title, member.searchable, origin);
        }
      }
    }
    return new ArrayList<>(out.values());
  }

  private static void addTask(LinkedHashMap<String, Models.Source> out, String id, String url,
      String password, String title, boolean searchable, String originPath) {
    String address = url == null ? "" : url.trim();
    if (address.isEmpty()) return;
    String identity = id == null || id.trim().isEmpty() ? address : id.trim();
    String pwd = password == null ? "" : password;
    String key = identity + '\n' + address + '\n' + pwd;
    if (out.containsKey(key)) return;
    Models.Source task = new Models.Source();
    task.kind = Models.SOURCE_OFFICIAL;
    task.id = identity;
    task.url = address;
    task.originUrl = address;
    task.password = pwd;
    task.title = title == null ? "" : title.trim();
    task.searchable = searchable;
    task.originPath = originPath == null ? "" : originPath;
    out.put(key, task);
  }
}
