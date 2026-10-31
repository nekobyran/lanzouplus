package cc.nkbr.lanzouplus;

import java.io.IOException;
import java.text.ParsePosition;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.Date;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.TimeZone;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

/** Pure feedback protocol rules shared by the network client, popups and host tests. */
final class FeedbackContract {
  static final int SCHEMA = 1;
  static final int MAX_CONTENT = 2000;
  private static final TimeZone UTC = TimeZone.getTimeZone("UTC");
  private static final TimeZone BEIJING = TimeZone.getTimeZone("GMT+08:00");
  private static final Pattern RFC3339 = Pattern.compile(
      "^(\\d{4}-\\d{2}-\\d{2}T\\d{2}:\\d{2}:\\d{2})(?:\\.(\\d{1,9}))?"
          + "(Z|[+-](?:[01]\\d|2[0-3]):[0-5]\\d)$");

  private FeedbackContract() {}

  static final class Failure extends IOException {
    final int status;
    final String code;

    Failure(int status, String code, String message) {
      super(message);
      this.status = status;
      this.code = code == null ? "" : code;
    }

    Failure(String code, String message) {
      this(0, code, message);
    }
  }

  static final class Identity {
    final String githubLogin;
    final String deviceCode;

    private Identity(String githubLogin, String deviceCode) {
      this.githubLogin = githubLogin == null ? "" : githubLogin.trim();
      this.deviceCode = deviceCode == null ? "" : deviceCode.trim().toLowerCase(Locale.ROOT);
    }

    static Identity of(String githubLogin, String deviceCode) {
      return new Identity(githubLogin, deviceCode);
    }

    static Identity deviceOnly(String deviceCode) {
      return new Identity("", deviceCode);
    }

    boolean github() {
      return !githubLogin.isEmpty();
    }

    String kind() {
      return github() ? "github" : "device";
    }

    String value() {
      return github() ? githubLogin : deviceCode;
    }

    boolean owns(Entry entry) {
      if (entry == null || entry.identity == null) return false;
      if ("github".equals(entry.identity.kind) && !githubLogin.isEmpty()) {
        return githubLogin.equalsIgnoreCase(entry.identity.value);
      }
      if ("device".equals(entry.identity.kind) && !deviceCode.isEmpty()) {
        return deviceCode.equalsIgnoreCase(entry.identity.value);
      }
      return false;
    }
  }

  enum Status {
    NEW("new", "待处理"),
    READ("read", "已读"),
    DONE("done", "已完成");

    final String wire;
    final String label;

    Status(String wire, String label) {
      this.wire = wire;
      this.label = label;
    }

    static Status parse(String value) throws Failure {
      for (Status status : values()) if (status.wire.equals(value)) return status;
      throw new Failure("schema_invalid", "反馈状态不在约定范围内");
    }
  }

  static final class IdentityValue {
    final String kind;
    final String value;

    IdentityValue(String kind, String value) {
      this.kind = kind;
      this.value = value;
    }

    String label() {
      return "github".equals(kind) ? "GitHub @" + value : "设备码 " + value;
    }
  }

  static final class Entry {
    final String id;
    final String content;
    final IdentityValue identity;
    final Status status;
    final long createdAt;
    final long readAt;
    final long doneAt;
    final long updatedAt;

    Entry(String id, String content, IdentityValue identity, Status status, long createdAt,
        long readAt, long doneAt, long updatedAt) {
      this.id = id;
      this.content = content;
      this.identity = identity;
      this.status = status;
      this.createdAt = createdAt;
      this.readAt = readAt;
      this.doneAt = doneAt;
      this.updatedAt = updatedAt;
    }
  }

  static final class History {
    final int version;
    final long updatedAt;
    final List<Entry> entries;

    History(int version, long updatedAt, List<Entry> entries) {
      this.version = version;
      this.updatedAt = updatedAt;
      this.entries = Collections.unmodifiableList(new ArrayList<>(entries));
    }

    static History empty() {
      return new History(1, 0, Collections.emptyList());
    }
  }

  static final class Submission {
    final String id;
    final Status status;
    final long createdAt;
    final String content;

    Submission(String id, Status status, long createdAt, String content) {
      this.id = id;
      this.status = status;
      this.createdAt = createdAt;
      this.content = content;
    }
  }

  static String validateContent(String raw) throws Failure {
    String value = raw == null ? "" : raw.trim();
    int length = value.codePointCount(0, value.length());
    if (length < 1 || length > MAX_CONTENT) {
      throw new Failure(400, "invalid_content", "反馈内容需为 1–2000 字");
    }
    return value;
  }

  static String canonicalDeviceCode(String raw) throws Failure {
    String value = raw == null ? "" : raw.trim().toLowerCase(Locale.ROOT);
    try {
      String canonical = UUID.fromString(value).toString();
      if (canonical.equals(value)) return canonical;
    } catch (IllegalArgumentException ignored) {
      // Report one stable protocol failure instead of exposing parser details.
    }
    throw new Failure(400, "invalid_device_code", "本机反馈身份无效，请重新打开反馈页面");
  }

  static String newDeviceCode() {
    return UUID.randomUUID().toString().toLowerCase(Locale.ROOT);
  }

  static String dayKey(long millis) {
    return formatDate(millis, "yyyy-MM-dd", BEIJING);
  }

  static long parseTime(Object value, String field, boolean nullable) throws Failure {
    if (value == null || value == JSONObject.NULL) {
      if (nullable) return 0;
      throw new Failure("schema_invalid", "反馈记录缺少" + field);
    }
    String raw = String.valueOf(value).trim();
    if (raw.isEmpty()) {
      if (nullable) return 0;
      throw new Failure("schema_invalid", "反馈记录缺少" + field);
    }
    Matcher match = RFC3339.matcher(raw);
    if (!match.matches()) throw new Failure("schema_invalid", "反馈记录时间格式无效");
    String fraction = match.group(2);
    String normalized = match.group(1) + "."
        + (fraction == null ? "000" : (fraction + "000").substring(0, 3))
        + match.group(3);
    SimpleDateFormat format = new SimpleDateFormat(
        "yyyy-MM-dd'T'HH:mm:ss.SSSXXX", Locale.ROOT);
    format.setLenient(false);
    format.setTimeZone(UTC);
    ParsePosition position = new ParsePosition(0);
    Date parsed = format.parse(normalized, position);
    if (parsed == null || position.getIndex() != normalized.length()) {
      throw new Failure("schema_invalid", "反馈记录时间格式无效");
    }
    return parsed.getTime();
  }

  static String formatTime(long millis) {
    return millis <= 0 ? "时间未知" : formatDate(millis, "yyyy-MM-dd HH:mm", BEIJING);
  }

  static String rfc3339(long millis) {
    return formatDate(millis,
        millis % 1000 == 0 ? "yyyy-MM-dd'T'HH:mm:ss'Z'" : "yyyy-MM-dd'T'HH:mm:ss.SSS'Z'",
        UTC);
  }

  private static String formatDate(long millis, String pattern, TimeZone zone) {
    SimpleDateFormat format = new SimpleDateFormat(pattern, Locale.ROOT);
    format.setTimeZone(zone);
    return format.format(new Date(millis));
  }

  static History parseHistory(String raw) throws Failure {
    JSONObject root;
    try {
      root = new JSONObject(raw == null ? "" : raw);
    } catch (JSONException invalid) {
      throw new Failure("schema_invalid", "反馈历史不是有效 JSON");
    }
    int schema = root.optInt("schema", -1);
    if (schema != SCHEMA) {
      throw new Failure("schema_invalid", "反馈历史 schema 不兼容");
    }
    JSONArray array = root.optJSONArray("entries");
    if (array == null) throw new Failure("schema_invalid", "反馈历史缺少 entries 数组");
    int version = root.optInt("version", 0);
    if (version < 1) throw new Failure("schema_invalid", "反馈历史版本号必须从 1 开始");
    long updatedAt = parseTime(root.opt("updatedAt"), "更新时间", false);
    List<Entry> entries = new ArrayList<>();
    Set<String> ids = new HashSet<>();
    for (int i = 0; i < array.length(); i++) {
      JSONObject item = array.optJSONObject(i);
      if (item == null) throw new Failure("schema_invalid", "反馈条目格式无效");
      Entry entry = parseEntry(item);
      if (!ids.add(entry.id)) throw new Failure("schema_invalid", "反馈条目 id 重复");
      entries.add(entry);
    }
    entries.sort(Comparator.comparingLong((Entry entry) -> entry.createdAt).reversed()
        .thenComparing(entry -> entry.id));
    return new History(version, updatedAt, entries);
  }

  static Entry parseEntry(JSONObject item) throws Failure {
    String id = item.optString("id", "").trim();
    if (id.isEmpty()) throw new Failure("schema_invalid", "反馈条目缺少 id");
    String content = validateContent(item.optString("content", ""));
    JSONObject identity = item.optJSONObject("identity");
    if (identity == null) throw new Failure("schema_invalid", "反馈条目缺少身份标记");
    String kind = identity.optString("kind", "").trim();
    String value = identity.optString("value", "").trim();
    if (!"github".equals(kind) && !"device".equals(kind)) {
      throw new Failure("schema_invalid", "反馈身份类型无效");
    }
    if (value.isEmpty()) throw new Failure("schema_invalid", "反馈身份标记为空");
    if ("device".equals(kind)) value = canonicalDeviceCode(value);
    return new Entry(id, content, new IdentityValue(kind, value),
        Status.parse(item.optString("status", "")),
        parseTime(item.opt("createdAt"), "createdAt", false),
        parseTime(item.opt("readAt"), "readAt", true),
        parseTime(item.opt("doneAt"), "doneAt", true),
        parseTime(item.opt("updatedAt"), "updatedAt", false));
  }

  static Submission parseSubmission(String raw) throws Failure {
    JSONObject root;
    try {
      root = new JSONObject(raw == null ? "" : raw);
    } catch (JSONException invalid) {
      throw new Failure("response_invalid", "反馈服务响应格式无效");
    }
    String id = root.optString("id", "").trim();
    if (id.isEmpty()) throw new Failure("response_invalid", "反馈服务未返回记录编号");
    return new Submission(id, Status.parse(root.optString("status", "")),
        parseTime(root.opt("createdAt"), "createdAt", false),
        validateContent(root.optString("content", "")));
  }

  static String errorMessage(String code) {
    if ("invalid_body".equals(code)) return "反馈请求格式无效";
    if ("invalid_content".equals(code)) return "反馈内容需为 1–2000 字";
    if ("invalid_device_code".equals(code)) return "本机反馈身份无效，请重新打开反馈页面";
    if ("github_identity_invalid".equals(code)) return "GitHub 身份已失效，请重新登录或选择以设备码提交";
    if ("github_login_required".equals(code)) return "GitHub 登录已失效，请重新登录或选择以设备码提交";
    if ("origin_rejected".equals(code)) return "反馈服务拒绝了当前来源";
    if ("feedback_daily_limit".equals(code)) return "今天已提交过反馈，请在明天 00:00 后重试";
    if ("rate_limited".equals(code)) return "请求过于频繁，请稍后重试";
    if ("feedback_identity_unavailable".equals(code)) return "GitHub 身份核对服务暂时不可用";
    if ("internal_error".equals(code)) return "反馈服务暂时不可用，请稍后重试";
    if ("schema_invalid".equals(code)) return "反馈历史格式不兼容，已保留旧缓存";
    if ("response_invalid".equals(code)) return "反馈服务响应格式无效";
    if ("network".equals(code)) return "无法连接反馈服务，请检查网络后重试";
    return "反馈服务暂时不可用，请稍后重试";
  }
}
