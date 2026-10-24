package cc.nkbr.lanzouplus;

import android.content.Context;
import android.content.SharedPreferences;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import org.json.JSONArray;
import org.json.JSONObject;

/** Compact, shared navigation visits. Domain objects are resolved again when a visit is opened. */
final class BrowserHistory {
  static final String PREFS = "web-browser-v2", KEY = "history";
  static final int LIMIT = 120;

  enum Type { WEB, INLINE, FOLDER, PARSE }

  static final class Route {
    final String id, nodeId, title, url, password;
    final int kind;

    Route(String id, String nodeId, String title, String url, int kind, String password) {
      this.id = clean(id);
      this.nodeId = clean(nodeId);
      this.title = clean(title);
      this.url = clean(url);
      this.kind = kind;
      this.password = clean(password);
    }

    static Route of(Models.Source source) {
      return new Route(source.id.isEmpty() ? source.url : source.id, source.nodeId,
          source.title, source.url, source.kind, source.childDirectory ? source.password : "");
    }

    String key() { return id + "\n" + nodeId; }

    JSONObject json() throws org.json.JSONException {
      JSONObject out = new JSONObject().put("i", id).put("n", nodeId).put("t", title)
          .put("u", url).put("k", kind);
      if (!password.isEmpty()) out.put("p", password);
      return out;
    }

    static Route read(JSONObject json) {
      return new Route(json.optString("i"), json.optString("n"), json.optString("t"),
          json.optString("u"), json.optInt("k"), json.optString("p"));
    }
  }

  static final class Entry {
    final String id, title, url, query, password;
    final Type type;
    final int scroll;
    final long at;
    final List<Route> routes;

    Entry(String id, String title, String url, Type type, String query, int scroll,
        long at, List<Route> routes, String password) {
      this.id = id;
      this.title = clean(title).isEmpty() ? clean(url) : title;
      this.url = clean(url);
      this.type = type;
      this.query = clean(query);
      this.scroll = Math.max(0, scroll);
      this.at = at;
      this.routes = Collections.unmodifiableList(new ArrayList<>(routes));
      this.password = clean(password);
    }

    Entry withPosition(String query, int scroll) {
      return new Entry(id, title, url, type, query, scroll, at, routes, password);
    }

    Entry withTimestamp(long time) {
      return new Entry(id, title, url, type, query, scroll, time, routes, password);
    }

    JSONObject json() throws org.json.JSONException {
      JSONObject out = new JSONObject().put("id", id).put("type", type.name())
          .put("t", title).put("u", url).put("a", at)
          .put("q", query).put("s", scroll);
      if (!password.isEmpty()) out.put("p", password);
      if (!routes.isEmpty()) {
        JSONArray path = new JSONArray();
        for (Route route : routes) path.put(route.json());
        out.put("r", path);
      }
      return out;
    }
  }

  private BrowserHistory() {}

  static Entry web(String title, String url) {
    String value = clean(url);
    return new Entry("WEB:" + value, title, value, Type.WEB, "", 0,
        System.currentTimeMillis(), Collections.emptyList(), "");
  }

  static Entry inline(List<Route> levels, String query, int scroll) {
    return routed(Type.INLINE, levels, query, scroll);
  }

  static Entry folder(List<Route> trail, String query, int scroll) {
    return routed(Type.FOLDER, trail, query, scroll);
  }

  static Entry parse(String url, String password) {
    String value = clean(url), secret = clean(password);
    return new Entry("PARSE:" + value + "\n" + secret, "蓝奏云解析 · " + value, value,
        Type.PARSE, "", 0, System.currentTimeMillis(), Collections.emptyList(), secret);
  }

  private static Entry routed(Type type, List<Route> routes, String query, int scroll) {
    if (routes.isEmpty()) throw new IllegalArgumentException("Empty browser history route");
    StringBuilder identity = new StringBuilder(type.name()).append(':');
    for (Route route : routes) { String key = route.key(); identity.append(key.length()).append(":").append(key); }
    Route last = routes.get(routes.size() - 1);
    return new Entry(identity.toString(), last.title, last.url.isEmpty() ? last.id : last.url, type, query, scroll,
        System.currentTimeMillis(), routes, "");
  }

  static synchronized List<Entry> read(Context context) {
    SharedPreferences prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    return read(prefs.getString(KEY, "[]"));
  }

  /** The one migration boundary for legacy {t,u,a} web visits. */
  static List<Entry> read(String raw) {
    ArrayList<Entry> entries = new ArrayList<>();
    try {
      JSONArray saved = new JSONArray(raw);
      for (int i = 0; i < saved.length() && entries.size() < LIMIT; i++) {
        JSONObject item = saved.optJSONObject(i);
        if (item == null) continue;
        String url = item.optString("u");
        if (url.isEmpty()) continue;
        Type type;
        try { type = Type.valueOf(item.optString("type", "WEB")); }
        catch (IllegalArgumentException error) { continue; }
        ArrayList<Route> routes = new ArrayList<>();
        JSONArray path = item.optJSONArray("r");
        if (path != null) for (int j = 0; j < path.length(); j++) {
          JSONObject part = path.optJSONObject(j);
          if (part != null) routes.add(Route.read(part));
        }
        if ((type == Type.INLINE || type == Type.FOLDER) && routes.isEmpty()) continue;
        Entry canonical = type == Type.WEB ? web(item.optString("t", url), url)
            : type == Type.PARSE ? parse(url, item.optString("p"))
            : routed(type, routes, item.optString("q"), item.optInt("s"));
        String id = item.optString("id", canonical.id);
        entries.add(new Entry(id, item.optString("t", canonical.title), url, type,
            item.optString("q"), item.optInt("s"), item.optLong("a"), routes,
            item.optString("p")));
      }
    } catch (org.json.JSONException ignored) {
    }
    return entries;
  }

  static synchronized void record(Context context, Entry entry) {
    if (entry == null || entry.url.isEmpty()) return;
    List<Entry> entries = read(context);
    Entry previous = null;
    for (Entry saved : entries) if (saved.id.equals(entry.id)) { previous = saved; break; }
    entries.removeIf(saved -> saved.id.equals(entry.id));
    Entry next = entry;
    if (previous != null && entry.scroll == 0 && entry.query.isEmpty())
      next = entry.withPosition(previous.query, previous.scroll);
    entries.add(0, next.withTimestamp(System.currentTimeMillis()));
    write(context, entries);
  }

  /** Persist UI position without creating an extra visit or changing its ordering. */
  static synchronized void savePosition(Context context, Entry entry) {
    if (entry == null) return;
    List<Entry> entries = read(context);
    for (int i = 0; i < entries.size(); i++) {
      if (!entries.get(i).id.equals(entry.id)) continue;
      Entry old = entries.get(i);
      entries.set(i, new Entry(old.id, old.title, old.url, old.type,
          entry.query, entry.scroll, old.at, old.routes, old.password));
      write(context, entries);
      return;
    }
  }

  static synchronized void delete(Context context, java.util.Set<String> ids) {
    if (ids.isEmpty()) return;
    List<Entry> entries = read(context);
    entries.removeIf(entry -> ids.contains(entry.id));
    write(context, entries);
  }

  private static void write(Context context, List<Entry> entries) {
    JSONArray output = new JSONArray();
    for (int i = 0; i < Math.min(entries.size(), LIMIT); i++) {
      try { output.put(entries.get(i).json()); }
      catch (org.json.JSONException ignored) {}
    }
    context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        .edit().putString(KEY, output.toString()).apply();
  }

  private static String clean(String value) { return value == null ? "" : value.trim(); }
}
