package cc.nkbr.lanzouplus;

import android.content.Context;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import org.json.JSONObject;

/** Blocking repository comment requests, called from the activity IO executor. */
final class RepositoryCommentsClient {
  private final Context context;
  private static final String URL_BASE = FeedbackClient.API_BASE + "/api/v1/repository-comments";

  RepositoryCommentsClient(Context context) {
    this.context = context.getApplicationContext();
  }

  JSONObject page(String githubUrl, int offset) throws Exception {
    return request("GET", URL_BASE + "?githubUrl=" + URLEncoder.encode(githubUrl, "UTF-8")
        + "&offset=" + offset + "&limit=20", null, "");
  }

  void submit(String githubUrl, String content) throws Exception {
    String token = "";
    try {
      GithubAccountClient github = new GithubAccountClient(context,
          context.getString(R.string.github_oauth_client_id).trim());
      if (github.isConfigured()) token = github.accessTokenForFeedbackIdentity();
    } catch (Exception unavailable) {
      // Anonymous comments are supported when no account is available.
    }
    request("POST", URL_BASE, new JSONObject().put("githubUrl", githubUrl)
        .put("content", content).toString(), token);
  }

  private JSONObject request(String method, String url, String body, String token) throws Exception {
    HttpURLConnection connection = (HttpURLConnection) new URL(url).openConnection();
    connection.setConnectTimeout(12000);
    connection.setReadTimeout(20000);
    connection.setInstanceFollowRedirects(false);
    connection.setRequestMethod(method);
    connection.setRequestProperty("Accept", "application/json");
    try {
    if (body != null) {
      byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
      connection.setDoOutput(true);
      connection.setRequestProperty("Content-Type", "application/json; charset=UTF-8");
      if (token != null && !token.isEmpty()) connection.setRequestProperty("Authorization", "Bearer " + token);
      connection.setFixedLengthStreamingMode(bytes.length);
      try (OutputStream output = connection.getOutputStream()) { output.write(bytes); }
    }
      int status = connection.getResponseCode();
      InputStream stream = status >= 400 ? connection.getErrorStream() : connection.getInputStream();
      ByteArrayOutputStream output = new ByteArrayOutputStream();
      if (stream != null) {
        try (InputStream input = stream) {
          byte[] buffer = new byte[4096];
          int count;
          while ((count = input.read(buffer)) != -1) output.write(buffer, 0, count);
        }
      }
      JSONObject result = new JSONObject(new String(output.toByteArray(), StandardCharsets.UTF_8));
      if (status != (body == null ? 200 : 201)) {
        JSONObject error = result.optJSONObject("error");
        throw new Exception(error == null ? "评论服务暂时不可用" : error.optString("message", "评论服务暂时不可用"));
      }
      return result;
    } finally { connection.disconnect(); }
  }
}
