package cc.nkbr.lanzouplus;

import android.content.Context;
import android.content.SharedPreferences;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.List;
import org.json.JSONException;
import org.json.JSONObject;

/** Feedback transport and local identity state. All calls are blocking and run off the UI thread. */
final class FeedbackClient {
  static final String API_BASE = "https://lanzoumarket.nkbr.cc";
  static final String HISTORY_URL =
      "https://raw.githubusercontent.com/nekobyran/lanzouplus-feedback/main/feedback.json";
  private static final String PREFS = "feedback";
  private static final String DEVICE_CODE = "device_code";
  private static final String LAST_SUBMITTED_DAY = "last_submitted_day";
  private static final String PENDING = "pending_submissions";
  private static final Object DEVICE_LOCK = new Object();
  private static final int RESPONSE_LIMIT = 256 * 1024;
  private static final int HISTORY_LIMIT = 2 * 1024 * 1024;

  interface Transport {
    Response post(String url, String json, String bearer) throws IOException;
    Response get(String url, int limit) throws IOException;
  }

  interface TokenProvider {
    String accessToken() throws Exception;
  }

  static final class Response {
    final int status;
    final String body;

    Response(int status, String body) {
      this.status = status;
      this.body = body == null ? "" : body;
    }
  }

  static final class HistoryResult {
    final FeedbackContract.History history;
    final boolean cached;
    final String warning;

    HistoryResult(FeedbackContract.History history, boolean cached, String warning) {
      this.history = history;
      this.cached = cached;
      this.warning = warning == null ? "" : warning;
    }
  }

  static final class SubmissionResult {
    final FeedbackContract.Submission submission;
    final FeedbackContract.Identity identity;

    SubmissionResult(FeedbackContract.Submission submission, FeedbackContract.Identity identity) {
      this.submission = submission;
      this.identity = identity;
    }
  }

  private final Context context;
  private final Transport transport;
  private final TokenProvider tokens;
  private final File cache;

  FeedbackClient(Context context, Transport transport) {
    this(context, transport, () -> storedGithubToken(context.getApplicationContext()));
  }

  FeedbackClient(Context context, Transport transport, TokenProvider tokens) {
    this.context = context.getApplicationContext();
    this.transport = transport;
    this.tokens = tokens;
    this.cache = new File(this.context.getFilesDir(), "feedback/history.json");
  }

  static FeedbackClient production(Context context) {
    return new FeedbackClient(context, new DirectTransport());
  }

  FeedbackContract.Identity identity() throws FeedbackContract.Failure {
    String device = deviceCode();
    String login = "";
    try {
      GithubAccountClient github = new GithubAccountClient(context,
          context.getString(R.string.github_oauth_client_id).trim());
      if (github.isConfigured()) {
        GithubAccountClient.Profile profile = github.cachedProfile();
        if (profile != null && !profile.login.trim().isEmpty()) login = profile.login.trim();
      }
    } catch (Exception ignored) {
      // An unreadable or expired account keeps the stable device identity available.
    }
    return FeedbackContract.Identity.of(login, device);
  }

  String deviceCode() throws FeedbackContract.Failure {
    synchronized (DEVICE_LOCK) {
      SharedPreferences preferences = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
      String existing = preferences.getString(DEVICE_CODE, "");
      try {
        return FeedbackContract.canonicalDeviceCode(existing);
      } catch (FeedbackContract.Failure missing) {
        String created = FeedbackContract.newDeviceCode();
        if (!preferences.edit().putString(DEVICE_CODE, created).commit()) {
          throw new FeedbackContract.Failure("device_store_failed", "无法保存本机反馈身份");
        }
        return created;
      }
    }
  }

  boolean submittedToday(long now) {
    String last = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        .getString(LAST_SUBMITTED_DAY, "");
    return FeedbackContract.dayKey(now).equals(last);
  }

  void markSubmitted(long now) {
    context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
        .putString(LAST_SUBMITTED_DAY, FeedbackContract.dayKey(now)).apply();
  }

  SubmissionResult send(String rawContent, FeedbackContract.Identity current, long now)
      throws FeedbackContract.Failure {
    String content = FeedbackContract.validateContent(rawContent);
    FeedbackContract.Identity source = current == null
        ? FeedbackContract.Identity.deviceOnly(deviceCode()) : current;
    if (source.deviceCode.isEmpty()) {
      source = FeedbackContract.Identity.of(source.githubLogin, deviceCode());
    }
    String token = source.github() ? githubToken() : "";
    if (source.github() && token.isEmpty()) {
      throw new FeedbackContract.Failure(401, "github_login_required",
          "GitHub 登录已失效，请重新登录或选择以设备码提交");
    }
    Response response = post(content, source.deviceCode, token);
    if (!token.isEmpty() && response.status == 401
        && "github_identity_invalid".equals(code(response.body))) {
      throw responseFailure(response);
    }
    if (response.status != 201) {
      FeedbackContract.Failure failure = responseFailure(response);
      if ("feedback_daily_limit".equals(failure.code)) markSubmitted(now);
      throw failure;
    }
    FeedbackContract.Submission submission = FeedbackContract.parseSubmission(response.body);
    markSubmitted(now);
    savePending(submission, source);
    return new SubmissionResult(submission, source);
  }

  HistoryResult history() throws FeedbackContract.Failure {
    try {
      return mergePending(historyFromNetwork());
    } catch (FeedbackContract.Failure failure) {
      HistoryResult fallback = cacheFallback(failure);
      if (fallback != null) return mergePending(fallback);
      HistoryResult local = pendingOnly("无法读取公开记录，显示本机刚提交的内容："
          + failure.getMessage());
      if (local != null) return local;
      throw failure;
    }
  }

  private HistoryResult historyFromNetwork() throws FeedbackContract.Failure {
    Response response;
    try {
      response = transport.get(fresh(HISTORY_URL), HISTORY_LIMIT);
    } catch (IOException network) {
      throw networkFailure(network);
    }
    if (response.status == 404) {
      return new HistoryResult(FeedbackContract.History.empty(), false,
          "公开反馈仓库暂时没有记录");
    }
    if (response.status != 200) throw responseFailure(response);
    try {
      FeedbackContract.History parsed = FeedbackContract.parseHistory(response.body);
      writeCache(response.body);
      return new HistoryResult(parsed, false, "");
    } catch (FeedbackContract.Failure invalid) {
      throw invalid;
    }
  }

  private Response post(String content, String deviceCode, String token)
      throws FeedbackContract.Failure {
    JSONObject payload = new JSONObject();
    try {
      payload.put("content", content).put("deviceCode", deviceCode);
    } catch (JSONException impossible) {
      throw new FeedbackContract.Failure("invalid_body", "反馈请求格式无效");
    }
    try {
      return transport.post(API_BASE + "/api/v1/feedback", payload.toString(), token);
    } catch (IOException network) {
      throw networkFailure(network);
    }
  }

  private String githubToken() {
    try {
      String value = tokens.accessToken();
      return value == null ? "" : value;
    } catch (Exception unavailable) {
      return "";
    }
  }

  private static String storedGithubToken(Context context) throws Exception {
    GithubAccountClient github = new GithubAccountClient(context,
        context.getString(R.string.github_oauth_client_id).trim());
    return github.isConfigured() ? github.accessTokenForFeedbackIdentity() : "";
  }

  private HistoryResult cacheFallback(FeedbackContract.Failure original) {
    if (!cache.isFile()) return null;
    try {
      String raw = readFile(cache, HISTORY_LIMIT);
      FeedbackContract.History cached = FeedbackContract.parseHistory(raw);
      return new HistoryResult(cached, true,
          "刷新失败，显示上次缓存：" + original.getMessage());
    } catch (Exception invalidCache) {
      return null;
    }
  }

  private HistoryResult mergePending(HistoryResult remote) {
    List<Pending> pending = pending();
    java.util.Set<String> remoteIds = new java.util.HashSet<>();
    for (FeedbackContract.Entry entry : remote.history.entries) remoteIds.add(entry.id);
    List<Pending> keep = new java.util.ArrayList<>();
    boolean changed = false;
    for (Pending item : pending) {
      if (remoteIds.contains(item.entry.id)) changed = true;
      else keep.add(item);
    }
    if (changed) writePending(keep);
    if (keep.isEmpty()) return remote;
    List<FeedbackContract.Entry> merged = new java.util.ArrayList<>(remote.history.entries);
    long updatedAt = remote.history.updatedAt;
    for (Pending item : keep) {
      merged.add(item.entry);
      updatedAt = Math.max(updatedAt, item.entry.updatedAt);
    }
    merged.sort(java.util.Comparator.comparingLong(
        (FeedbackContract.Entry entry) -> entry.createdAt).reversed());
    String warning = remote.warning;
    String waiting = "静态仓库尚未发布，显示本机刚提交的记录";
    warning = warning.isEmpty() ? waiting : warning + "；" + waiting;
    return new HistoryResult(new FeedbackContract.History(remote.history.version, updatedAt,
        merged), remote.cached, warning);
  }

  private HistoryResult pendingOnly(String warning) {
    List<Pending> pending = pending();
    if (pending.isEmpty()) return null;
    List<FeedbackContract.Entry> entries = new java.util.ArrayList<>();
    long updatedAt = 0;
    for (Pending item : pending) {
      entries.add(item.entry);
      updatedAt = Math.max(updatedAt, item.entry.updatedAt);
    }
    entries.sort(java.util.Comparator.comparingLong(
        (FeedbackContract.Entry entry) -> entry.createdAt).reversed());
    return new HistoryResult(new FeedbackContract.History(1, updatedAt, entries), true, warning);
  }

  private void savePending(FeedbackContract.Submission submission,
      FeedbackContract.Identity identity) {
    List<Pending> values = pending();
    List<Pending> next = new java.util.ArrayList<>();
    for (Pending item : values) if (!item.entry.id.equals(submission.id)) next.add(item);
    try {
      org.json.JSONObject raw = new org.json.JSONObject()
          .put("id", submission.id)
          .put("content", submission.content)
          .put("status", "new")
          .put("createdAt", FeedbackContract.rfc3339(submission.createdAt))
          .put("readAt", org.json.JSONObject.NULL)
          .put("doneAt", org.json.JSONObject.NULL)
          .put("updatedAt", FeedbackContract.rfc3339(submission.createdAt))
          .put("identity", new org.json.JSONObject()
              .put("kind", identity.kind()).put("value", identity.value()));
      next.add(new Pending(raw, FeedbackContract.parseEntry(raw)));
      writePending(next);
    } catch (Exception unavailable) {
      // The server already accepted the feedback; a local preview must never turn that into failure.
    }
  }

  private List<Pending> pending() {
    String stored = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        .getString(PENDING, "");
    List<Pending> values = new java.util.ArrayList<>();
    if (stored.isEmpty()) return values;
    try {
      org.json.JSONArray array = new org.json.JSONArray(stored);
      for (int i = 0; i < array.length(); i++) {
        org.json.JSONObject raw = array.optJSONObject(i);
        if (raw != null) values.add(new Pending(raw, FeedbackContract.parseEntry(raw)));
      }
    } catch (Exception invalid) {
      return new java.util.ArrayList<>();
    }
    return values;
  }

  private void writePending(List<Pending> values) {
    org.json.JSONArray array = new org.json.JSONArray();
    for (Pending item : values) array.put(item.raw);
    context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
        .putString(PENDING, array.toString()).apply();
  }

  private static final class Pending {
    final org.json.JSONObject raw;
    final FeedbackContract.Entry entry;

    Pending(org.json.JSONObject raw, FeedbackContract.Entry entry) {
      this.raw = raw;
      this.entry = entry;
    }
  }

  private void writeCache(String raw) {
    File parent = cache.getParentFile();
    if (parent == null || (!parent.isDirectory() && !parent.mkdirs())) return;
    File temporary = new File(parent, cache.getName() + ".tmp");
    try (OutputStream output = new FileOutputStream(temporary)) {
      output.write(raw.getBytes(StandardCharsets.UTF_8));
    } catch (IOException ignored) {
      temporary.delete();
      return;
    }
    if (!temporary.renameTo(cache)) {
      cache.delete();
      temporary.renameTo(cache);
    }
  }

  private static String readFile(File file, int limit) throws IOException {
    try (InputStream input = new FileInputStream(file)) {
      return new String(bytes(input, limit), StandardCharsets.UTF_8);
    }
  }

  private static String fresh(String url) {
    return url + (url.indexOf('?') < 0 ? "?" : "&") + "refresh=" + System.currentTimeMillis();
  }

  static FeedbackContract.Failure networkFailure(IOException error) {
    if (error instanceof FeedbackContract.Failure) return (FeedbackContract.Failure) error;
    return new FeedbackContract.Failure("network", "无法连接反馈服务，请检查网络后重试");
  }

  static FeedbackContract.Failure responseFailure(Response response) {
    String code = code(response.body);
    if (code.isEmpty()) code = response.status >= 500 ? "internal_error" : "http_" + response.status;
    String message = FeedbackContract.errorMessage(code);
    if (response.status == 401 && "github_identity_invalid".equals(code)) {
      message = "GitHub 身份核对失败";
    }
    return new FeedbackContract.Failure(response.status, code, message);
  }

  private static String code(String body) {
    try {
      JSONObject value = new JSONObject(body == null ? "" : body);
      JSONObject error = value.optJSONObject("error");
      String nested = error == null ? "" : error.optString("code", "").trim();
      return nested.isEmpty() ? value.optString("code", "").trim() : nested;
    } catch (JSONException invalid) {
      return "";
    }
  }

  private static byte[] bytes(InputStream input, int limit) throws IOException {
    ByteArrayOutputStream output = new ByteArrayOutputStream();
    byte[] buffer = new byte[8192];
    int count;
    while ((count = input.read(buffer)) != -1) {
      if (output.size() + count > limit) throw new IOException("反馈响应超出容量上限");
      output.write(buffer, 0, count);
    }
    return output.toByteArray();
  }

  private static final class DirectTransport implements Transport {
    @Override public Response post(String url, String json, String bearer) throws IOException {
      byte[] body = json.getBytes(StandardCharsets.UTF_8);
      HttpURLConnection connection = open(url, "POST");
      try {
        connection.setDoOutput(true);
        connection.setRequestProperty("Content-Type", "application/json; charset=UTF-8");
        connection.setFixedLengthStreamingMode(body.length);
        if (bearer != null && !bearer.isEmpty()) {
          connection.setRequestProperty("Authorization", "Bearer " + bearer);
        }
        try (OutputStream output = connection.getOutputStream()) {
          output.write(body);
        }
        return response(connection);
      } finally {
        connection.disconnect();
      }
    }

    @Override public Response get(String url, int limit) throws IOException {
      HttpURLConnection connection = open(url, "GET");
      try {
        int status = connection.getResponseCode();
        if (status >= 300 && status < 400) throw new IOException("反馈历史返回了重定向");
        InputStream input = status >= 400 ? connection.getErrorStream() : connection.getInputStream();
        String value = input == null ? "" : new String(bytes(input, limit), StandardCharsets.UTF_8);
        if (input != null) input.close();
        return new Response(status, value);
      } finally {
        connection.disconnect();
      }
    }

    private static Response response(HttpURLConnection connection) throws IOException {
      int status = connection.getResponseCode();
      if (status >= 300 && status < 400) throw new IOException("反馈服务返回了重定向");
      InputStream input = status >= 400 ? connection.getErrorStream() : connection.getInputStream();
      String body = input == null ? ""
          : new String(bytes(input, RESPONSE_LIMIT), StandardCharsets.UTF_8);
      if (input != null) input.close();
      return new Response(status, body);
    }

    private static HttpURLConnection open(String raw, String method) throws IOException {
      URL url = new URL(raw);
      if (!"https".equalsIgnoreCase(url.getProtocol())) {
        throw new IOException("反馈连接必须使用 HTTPS");
      }
      HttpURLConnection connection = (HttpURLConnection) url.openConnection();
      connection.setConnectTimeout(12_000);
      connection.setReadTimeout(20_000);
      connection.setInstanceFollowRedirects(false);
      connection.setRequestMethod(method);
      connection.setRequestProperty("Accept", "application/json");
      connection.setRequestProperty("User-Agent", "LanzouPlus-Android-Feedback");
      return connection;
    }
  }
}
