package cc.nkbr.lanzouplus;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.SystemClock;
import android.security.keystore.KeyGenParameterSpec;
import android.security.keystore.KeyProperties;
import android.util.Base64;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.security.KeyStore;
import java.util.concurrent.atomic.AtomicBoolean;
import javax.crypto.Cipher;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

/**
 * GitHub 官方 OAuth Device Flow 与本机加密账户。只申请 public_repo，不需要 Client Secret。
 *
 * <p>OAuth 和带令牌的 API 请求仅访问 GitHub 官方 HTTPS 主机；禁止 HTTP 重定向。
 * Token/refresh token 只存在运行内存和 Android Keystore AES/GCM 密文首选项。
 */
final class GithubAccountClient {
    static final String DEVICE_URL = "https://github.com/login/device";
    private static final String OAUTH_CODE_URL = "https://github.com/login/device/code";
    private static final String OAUTH_TOKEN_URL = "https://github.com/login/oauth/access_token";
    private static final String API_URL = "https://api.github.com";
    private static final String PREFS = "github_account_encrypted_v1";
    private static final String PREF_ACCOUNT = "session";
    private static final String KEY_ALIAS = "cc.nkbr.lanzouplus.github_account_aes_v1";
    private static final Object SESSION_LOCK = new Object();
    private static final long EXPIRY_MARGIN_MS = 30_000L;

    interface Transport {
        Response execute(String method, String url, String body, String bearer) throws IOException;
    }

    /** 测试可注入凭据仓库；生产版本始终使用 Android Keystore。 */
    interface SessionStore {
        Session read() throws IOException;
        void write(Session value) throws IOException;
        void clear() throws IOException;
    }

    interface PollTime {
        long elapsedMillis();
        void sleepMillis(long millis) throws InterruptedException;
    }

    interface Progress {
        void changed(String status);
    }

    static final class Response {
        final int status;
        final String body;

        Response(int status, String body) {
            this.status = status;
            this.body = body == null ? "" : body;
        }
    }

    static final class DeviceCode {
        final String userCode;
        final String deviceCode;
        final long expiresAt;
        final long intervalMillis;

        DeviceCode(String userCode, String deviceCode, long expiresAt, long intervalMillis) {
            this.userCode = userCode;
            this.deviceCode = deviceCode;
            this.expiresAt = expiresAt;
            this.intervalMillis = intervalMillis;
        }
    }

    static final class Profile {
        final String login;
        final String avatarUrl;
        final String profileUrl;

        Profile(String login, String avatarUrl, String profileUrl) {
            this.login = login;
            this.avatarUrl = avatarUrl;
            this.profileUrl = profileUrl;
        }
    }

    static final class Session {
        final Profile profile;
        final String accessToken;
        final String refreshToken;
        final long expiresAt;
        final long refreshExpiresAt;

        Session(Profile profile, String accessToken, String refreshToken,
                long expiresAt, long refreshExpiresAt) {
            this.profile = profile;
            this.accessToken = accessToken;
            this.refreshToken = refreshToken;
            this.expiresAt = expiresAt;
            this.refreshExpiresAt = refreshExpiresAt;
        }
    }

    /** 无需重定向，所有 HTTP 调用均可通过 Transport 替身进行协议测试。 */
    static final class DirectTransport implements Transport {
        static void checkEndpoint(String endpoint, String bearer) throws IOException {
            URL url = new URL(endpoint);
            String host = url.getHost();
            if (!"https".equals(url.getProtocol())
                    || (!"github.com".equals(host) && !"api.github.com".equals(host))
                    || (url.getPort() != -1 && url.getPort() != 443)
                    || url.getUserInfo() != null || url.getRef() != null
                    || (bearer != null && !bearer.isEmpty() && !"api.github.com".equals(host))) {
                throw new IOException("已阻止非 GitHub 官方地址的认证请求");
            }
        }

        @Override
        public Response execute(String method, String endpoint, String body, String bearer)
                throws IOException {
            checkEndpoint(endpoint, bearer);
            URL url = new URL(endpoint);
            String host = url.getHost();
            // 系统 HTTPS 代理可用；TLS 证书仍针对 github.com / api.github.com 校验。
            HttpURLConnection connection = (HttpURLConnection) url.openConnection();
            try {
                connection.setInstanceFollowRedirects(false);
                connection.setUseCaches(false);
                connection.setConnectTimeout(12_000);
                connection.setReadTimeout(20_000);
                connection.setRequestMethod(method);
                connection.setRequestProperty("User-Agent", "LanzouPlus-GitHub-DeviceFlow");
                connection.setRequestProperty("Accept",
                        "github.com".equals(host) ? "application/json" : "application/vnd.github+json");
                if ("api.github.com".equals(host)) {
                    connection.setRequestProperty("X-GitHub-Api-Version", "2022-11-28");
                }
                if (bearer != null && !bearer.isEmpty()) {
                    connection.setRequestProperty("Authorization", "Bearer " + bearer);
                }
                if (body != null) {
                    connection.setDoOutput(true);
                    connection.setRequestProperty("Content-Type",
                            "github.com".equals(host)
                                    ? "application/x-www-form-urlencoded; charset=utf-8"
                                    : "application/json; charset=utf-8");
                    byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
                    connection.setFixedLengthStreamingMode(bytes.length);
                    try (OutputStream output = connection.getOutputStream()) {
                        output.write(bytes);
                    }
                }
                int status = connection.getResponseCode();
                if (status >= 300 && status < 400) {
                    return new Response(status, "");
                }
                InputStream input = status >= 400
                        ? connection.getErrorStream() : connection.getInputStream();
                if (input == null) {
                    return new Response(status, "");
                }
                try (InputStream stream = input; ByteArrayOutputStream buffer = new ByteArrayOutputStream()) {
                    byte[] chunk = new byte[4096];
                    int length;
                    while ((length = stream.read(chunk)) != -1) {
                        if (buffer.size() + length > 8 * 1024 * 1024) {
                            throw new IOException("GitHub 响应数据超出上限");
                        }
                        buffer.write(chunk, 0, length);
                    }
                    return new Response(status, new String(buffer.toByteArray(), StandardCharsets.UTF_8));
                }
            } finally {
                connection.disconnect();
            }
        }
    }

    private final Context context;
    private final String clientId;
    private final Transport transport;
    private final PollTime time;
    private final SessionStore injectedStore;

    GithubAccountClient(Context context, String clientId) {
        this(context, clientId, new DirectTransport(), new PollTime() {
            @Override public long elapsedMillis() { return SystemClock.elapsedRealtime(); }
            @Override public void sleepMillis(long millis) throws InterruptedException {
                Thread.sleep(millis);
            }
        });
    }

    /** 主工程统一提供公开 OAuth Client ID；严禁复用 GitHub CLI 的应用身份。 */
    static GithubAccountClient production(MainActivity owner) {
        return new GithubAccountClient(owner.getApplicationContext(),
                owner.getString(R.string.github_oauth_client_id).trim());
    }

    /** 可注入 HTTP 及单调时钟，覆盖 pending/slow_down/超时/重定向测试。 */
    GithubAccountClient(Context context, String clientId, Transport transport, PollTime time) {
        this(context, clientId, transport, time, null);
    }

    GithubAccountClient(Context context, String clientId, Transport transport, PollTime time,
            SessionStore store) {
        this.context = context.getApplicationContext();
        this.clientId = clientId == null ? "" : clientId.trim();
        this.transport = transport;
        this.time = time;
        this.injectedStore = store;
    }

    boolean isConfigured() {
        return !clientId.isEmpty();
    }

    DeviceCode requestDeviceCode() throws IOException {
        requireClientId();
        Response result = send("POST", OAUTH_CODE_URL,
                form("client_id", clientId, "scope", "public_repo"), null);
        JSONObject body = json(result.body);
        if (result.status != 200 || body.has("error")) {
            throw oauthFailure(body.optString("error"), result.status);
        }
        String deviceCode = body.optString("device_code");
        String userCode = body.optString("user_code");
        long seconds = body.optLong("expires_in", 0);
        long interval = body.optLong("interval", 5);
        if (deviceCode.isEmpty() || userCode.isEmpty() || seconds <= 0 || interval <= 0
                || !DEVICE_URL.equals(body.optString("verification_uri"))) {
            throw new IOException("GitHub 返回的设备授权信息不完整");
        }
        return new DeviceCode(userCode, deviceCode,
                time.elapsedMillis() + seconds * 1000L, Math.max(5000L, interval * 1000L));
    }

    /** 按 GitHub interval 轮询；slow_down 至少增加 5 秒，直到授权、拒绝或到期。 */
    Session awaitAuthorization(DeviceCode code, AtomicBoolean cancelled, Progress progress)
            throws IOException, InterruptedException {
        long interval = code.intervalMillis;
        long next = time.elapsedMillis() + interval;
        while (time.elapsedMillis() < code.expiresAt) {
            waitUntil(Math.min(next, code.expiresAt), cancelled);
            if (time.elapsedMillis() >= code.expiresAt) break;
            Response result = send("POST", OAUTH_TOKEN_URL,
                    form("client_id", clientId,
                            "device_code", code.deviceCode,
                            "grant_type", "urn:ietf:params:oauth:grant-type:device_code"),
                    null);
            if (cancelled.get()) throw new InterruptedException("设备授权已取消");
            JSONObject payload = json(result.body);
            String error = payload.optString("error");
            if (error.isEmpty() && result.status == 200) {
                String token = payload.optString("access_token");
                if (token.isEmpty() || !"bearer".equalsIgnoreCase(payload.optString("token_type"))) {
                    throw new IOException("GitHub 未返回有效的访问令牌");
                }
                progress.changed("授权成功，正在确认 GitHub 用户身份…");
                Profile profile = fetchProfile(token);
                if (cancelled.get()) throw new InterruptedException("设备授权已取消");
                return new Session(profile, token, payload.optString("refresh_token"),
                        expiration(payload.optLong("expires_in")),
                        expiration(payload.optLong("refresh_token_expires_in")));
            }
            if ("authorization_pending".equals(error)) {
                progress.changed("等待授权中，请在浏览器输入验证码并确认…");
            } else if ("slow_down".equals(error)) {
                interval = Math.max(interval + 5000L,
                        payload.optLong("interval", 0) * 1000L);
                progress.changed("GitHub 要求降低查询频率，正在按新间隔等待…");
            } else {
                throw oauthFailure(error, result.status);
            }
            next = time.elapsedMillis() + interval;
        }
        if (cancelled.get()) throw new InterruptedException("设备授权已取消");
        throw new IOException("设备验证码已过期，请重新开始 GitHub 登录");
    }

    private void waitUntil(long deadline, AtomicBoolean cancelled) throws InterruptedException {
        while (time.elapsedMillis() < deadline) {
            if (cancelled.get() || Thread.currentThread().isInterrupted()) {
                throw new InterruptedException("设备授权已取消");
            }
            time.sleepMillis(Math.min(1000L, deadline - time.elapsedMillis()));
        }
        if (cancelled.get()) throw new InterruptedException("设备授权已取消");
    }

    Profile cachedProfile() throws IOException {
        Session session = storedSession();
        return session == null ? null : session.profile;
    }

    boolean hasUsableSession() throws IOException {
        Session session = storedSession();
        return session != null && (validUntil(session.expiresAt)
                || (!session.refreshToken.isEmpty() && validUntil(session.refreshExpiresAt)));
    }

    /** 授权完成后才持久化，取消或旧 Activity 永远不得调用此方法。 */
    void saveAuthorizedSession(Session session) throws IOException {
        synchronized (SESSION_LOCK) {
            saveSession(session);
        }
    }

    void signOut() throws IOException {
        synchronized (SESSION_LOCK) {
            if (injectedStore != null) {
                injectedStore.clear();
                return;
            }
            if (!preferences().edit().remove(PREF_ACCOUNT).commit()) {
                throw new IOException("清除本机 GitHub 账户失败");
            }
        }
    }

    /**
     * 供发布器使用的唯一带 Authorization 的业务入口。
     * 所有 URL 固定在 api.github.com；请求失败不会回显原始响应或授权头。
     */
    JSONObject api(String method, String path, JSONObject payload) throws IOException {
        return apiForAccount(null, method, path, payload);
    }

    /** 发布进程按登录身份固定 Token，避免用户中途切换账号时越权写入。 */
    JSONObject apiForAccount(String expectedLogin, String method, String path, JSONObject payload)
            throws IOException {
        if (!path.startsWith("/") || path.startsWith("//") || path.contains("://")) {
            throw new IOException("不合法的 GitHub API 路径");
        }
        Session active = sessionForRequest();
        if (expectedLogin != null && !active.profile.login.equalsIgnoreCase(expectedLogin)) {
            throw new IOException("GitHub 登录账号已切换，发布已停止，请重新操作");
        }
        return apiWithToken(method, path, payload, active.accessToken);
    }

    private Session sessionForRequest() throws IOException {
        synchronized (SESSION_LOCK) {
            Session saved = storedSession();
            if (saved == null) throw new IOException("请先登录 GitHub");
            if (validUntil(saved.expiresAt)) return saved;
            if (saved.refreshToken.isEmpty() || !validUntil(saved.refreshExpiresAt)) {
                throw new IOException("GitHub 登录已过期，请重新授权");
            }
            requireClientId();
            Response result = send("POST", OAUTH_TOKEN_URL,
                    form("client_id", clientId,
                            "grant_type", "refresh_token",
                            "refresh_token", saved.refreshToken), null);
            JSONObject response = json(result.body);
            if (result.status != 200 || response.has("error")) {
                throw new IOException("GitHub 登录已失效，请重新授权");
            }
            String freshToken = response.optString("access_token");
            String refreshToken = response.optString("refresh_token");
            if (freshToken.isEmpty() || refreshToken.isEmpty()
                    || !"bearer".equalsIgnoreCase(response.optString("token_type"))) {
                throw new IOException("GitHub 返回的刷新凭据不完整，请重新授权");
            }
            Profile user = fetchProfile(freshToken);
            if (!saved.profile.login.equalsIgnoreCase(user.login)) {
                throw new IOException("GitHub 用户身份发生变化，请重新登录");
            }
            Session renewed = new Session(user, freshToken, refreshToken,
                    expiration(response.optLong("expires_in")),
                    expiration(response.optLong("refresh_token_expires_in")));
            saveSession(renewed);
            return renewed;
        }
    }

    private Profile fetchProfile(String token) throws IOException {
        JSONObject user = apiWithToken("GET", "/user", null, token);
        String login = user.optString("login").trim();
        if (login.isEmpty()) throw new IOException("GitHub 未返回用户名称");
        return new Profile(login, user.optString("avatar_url"),
                user.optString("html_url", "https://github.com/" + login));
    }

    private JSONObject apiWithToken(String method, String path, JSONObject body, String token)
            throws IOException {
        Response result = send(method, API_URL + path, body == null ? null : body.toString(), token);
        if (result.status < 200 || result.status >= 300) {
            throw apiFailure(result);
        }
        return json(result.body);
    }

    static final class ApiFailure extends IOException {
        final int status;
        final boolean nameAlreadyExists;

        ApiFailure(int status, boolean nameAlreadyExists, String message) {
            super(message);
            this.status = status;
            this.nameAlreadyExists = nameAlreadyExists;
        }
    }

    private static ApiFailure apiFailure(Response result) {
        boolean duplicate = false;
        try {
            JSONArray problems = new JSONObject(result.body).optJSONArray("errors");
            if (problems != null) for (int i = 0; i < problems.length(); i++) {
                JSONObject detail = problems.optJSONObject(i);
                if (detail != null && "already_exists".equals(detail.optString("code"))) {
                    duplicate = true;
                }
            }
        } catch (JSONException ignored) {
            // 服务端原始响应不可出现在异常消息、日志或 UI 中。
        }
        String message;
        switch (result.status) {
            case 401: message = "GitHub 授权已失效，请重新登录"; break;
            case 403: message = "GitHub 拒绝操作，请确认已授予 public_repo 权限及账户权限"; break;
            case 404: message = "GitHub 仓库或 API 不存在（HTTP 404）"; break;
            case 409: message = "GitHub 仓库写入冲突（HTTP 409）"; break;
            case 422:
                message = duplicate ? "GitHub 已有同名仓库，不会覆盖原仓库"
                        : "GitHub 拒绝了仓库名称或文件内容（HTTP 422）";
                break;
            default: message = "GitHub 请求失败（HTTP " + result.status + "）";
        }
        return new ApiFailure(result.status, duplicate, message);
    }

    private static IOException oauthFailure(String error, int status) {
        switch (error) {
            case "expired_token":
            case "token_expired":
                return new IOException("设备验证码已过期，请重新申请");
            case "access_denied":
                return new IOException("已在 GitHub 拒绝此次授权");
            case "device_flow_disabled":
                return new IOException("GitHub OAuth 应用尚未启用 Device Flow");
            case "incorrect_client_credentials":
                return new IOException("GitHub OAuth Client ID 无效，请检查应用配置");
            case "incorrect_device_code":
                return new IOException("GitHub 设备授权码无效，请重新登录");
            case "unsupported_grant_type":
                return new IOException("GitHub 不接受设备授权类型");
            case "bad_refresh_token":
                return new IOException("GitHub 登录已失效，请重新授权");
            default:
                return new IOException("GitHub 认证失败（HTTP " + status + "）");
        }
    }

    private Response send(String method, String url, String body, String bearer)
            throws IOException {
        Response result;
        try {
            result = transport.execute(method, url, body, bearer);
        } catch (IOException network) {
            throw new IOException("连接 GitHub 失败，请检查网络后重试");
        }
        if (result.status >= 300 && result.status < 400) {
            throw new IOException("GitHub 返回了重定向，已停止请求");
        }
        return result;
    }

    private void requireClientId() throws IOException {
        if (!isConfigured()) {
            throw new IOException("尚未配置 GitHub OAuth Client ID，暂时无法登录");
        }
    }

    private static JSONObject json(String body) throws IOException {
        try {
            return new JSONObject(body);
        } catch (JSONException malformed) {
            throw new IOException("GitHub 返回的数据格式不正确");
        }
    }

    private static String form(String... pairs) throws IOException {
        StringBuilder encoded = new StringBuilder();
        for (int i = 0; i < pairs.length; i += 2) {
            if (i > 0) encoded.append('&');
            encoded.append(URLEncoder.encode(pairs[i], "UTF-8")).append('=')
                    .append(URLEncoder.encode(pairs[i + 1], "UTF-8"));
        }
        return encoded.toString();
    }

    private static long expiration(long seconds) {
        return seconds <= 0 ? 0 : System.currentTimeMillis() + seconds * 1000L;
    }

    private static boolean validUntil(long timestamp) {
        return timestamp == 0 || System.currentTimeMillis() + EXPIRY_MARGIN_MS < timestamp;
    }

    private SharedPreferences preferences() {
        return context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    private SecretKey key() throws Exception {
        KeyStore store = KeyStore.getInstance("AndroidKeyStore");
        store.load(null);
        SecretKey present = (SecretKey) store.getKey(KEY_ALIAS, null);
        if (present != null) return present;
        KeyGenerator generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore");
        generator.init(new KeyGenParameterSpec.Builder(KEY_ALIAS,
                KeyProperties.PURPOSE_ENCRYPT | KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build());
        return generator.generateKey();
    }

    private Session storedSession() throws IOException {
        synchronized (SESSION_LOCK) {
            if (injectedStore != null) return injectedStore.read();
            String ciphertext = preferences().getString(PREF_ACCOUNT, "");
            if (ciphertext.isEmpty()) return null;
            try {
                byte[] sealed = Base64.decode(ciphertext, Base64.NO_WRAP);
                if (sealed.length <= 12) throw new IOException("密文格式不正确");
                byte[] nonce = new byte[12];
                System.arraycopy(sealed, 0, nonce, 0, nonce.length);
                Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
                cipher.init(Cipher.DECRYPT_MODE, key(), new GCMParameterSpec(128, nonce));
                byte[] plain = cipher.doFinal(sealed, nonce.length, sealed.length - nonce.length);
                JSONObject account = new JSONObject(new String(plain, StandardCharsets.UTF_8));
                String token = account.getString("access");
                Profile profile = new Profile(account.getString("login"),
                        account.optString("avatar"), account.optString("profile"));
                return new Session(profile, token, account.optString("refresh"),
                        account.optLong("expiry"), account.optLong("refreshExpiry"));
            } catch (Exception secret) {
                throw new IOException("本机 GitHub 安全凭据无法读取，请退出后重新登录");
            }
        }
    }

    private void saveSession(Session session) throws IOException {
        if (injectedStore != null) {
            injectedStore.write(session);
            return;
        }
        try {
            JSONObject content = new JSONObject()
                    .put("access", session.accessToken)
                    .put("refresh", session.refreshToken)
                    .put("expiry", session.expiresAt)
                    .put("refreshExpiry", session.refreshExpiresAt)
                    .put("login", session.profile.login)
                    .put("avatar", session.profile.avatarUrl)
                    .put("profile", session.profile.profileUrl);
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.ENCRYPT_MODE, key());
            byte[] nonce = cipher.getIV();
            byte[] encrypted = cipher.doFinal(content.toString().getBytes(StandardCharsets.UTF_8));
            ByteArrayOutputStream sealed = new ByteArrayOutputStream(nonce.length + encrypted.length);
            sealed.write(nonce);
            sealed.write(encrypted);
            if (!preferences().edit().putString(PREF_ACCOUNT,
                    Base64.encodeToString(sealed.toByteArray(), Base64.NO_WRAP)).commit()) {
                throw new IOException("GitHub 本机账户保存失败");
            }
        } catch (Exception secret) {
            throw new IOException("无法使用 Android Keystore 加密保存 GitHub 登录信息");
        }
    }
}
