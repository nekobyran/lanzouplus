package cc.nkbr.lanzouplus;

import android.net.Uri;
import android.content.Intent;
import java.net.URLEncoder;

/**
 * Pure policy for the built-in browser: how typed input becomes a URL and which component owns a
 * navigation target.
 *
 * <p>It keeps no Activity, WebView, cookie or storage state, so every branch can be exercised by
 * host tests without an Android runtime session.</p>
 */
final class WebNavigationPolicy {

  /** Bing home used for empty input and for the browser home action. */
  static final String HOME_URL = "https://www.bing.com/";
  /** Bing result page used for free-text input. */
  static final String SEARCH_URL = "https://www.bing.com/search?q=";

  private static final String HOST_PATTERN =
      "(?i)(?:localhost|(?:[a-z0-9-]+\\.)+[a-z]{2,})(?:[:/].*)?";

  /** Which component owns one navigation target. */
  enum Route {
    WEB,
    EXTERNAL_VIEW,
    EXTERNAL_INTENT
  }

  private WebNavigationPolicy() {}

  static Uri parse(String value) {
    if (value == null) {
      return null;
    }
    try {
      return Uri.parse(value);
    } catch (Exception ignored) {
      return null;
    }
  }

  static String schemeOf(Uri uri) {
    if (uri == null) {
      return null;
    }
    String scheme = uri.getScheme();
    return scheme == null || scheme.isEmpty() ? null : scheme;
  }

  static boolean isHttpScheme(String scheme) {
    return scheme != null && (scheme.equalsIgnoreCase("http") || scheme.equalsIgnoreCase("https"));
  }

  /**
   * Document schemes the WebView renders itself. Treating these as external-app intents cancelled
   * the navigation and left the page stalled behind a "leave the in-app page?" prompt.
   */
  static boolean isBrowserDocumentScheme(String scheme) {
    return scheme != null && (scheme.equalsIgnoreCase("about") || scheme.equalsIgnoreCase("data")
        || scheme.equalsIgnoreCase("blob") || scheme.equalsIgnoreCase("javascript"));
  }

  static Route routeFor(Uri uri) {
    String scheme = schemeOf(uri);
    if (scheme == null || isHttpScheme(scheme) || isBrowserDocumentScheme(scheme)) {
      return Route.WEB;
    }
    return scheme.equalsIgnoreCase("intent") ? Route.EXTERNAL_INTENT : Route.EXTERNAL_VIEW;
  }

  static String resolveInput(String raw) {
    String value = raw == null ? "" : raw.trim();
    if (value.isEmpty()) {
      return HOME_URL;
    }
    Uri parsed = parse(value);
    if (parsed != null && isHttpScheme(parsed.getScheme())) {
      return value;
    }
    if (!value.contains(" ") && value.matches(HOST_PATTERN)) {
      return "https://" + value;
    }
    try {
      return SEARCH_URL + URLEncoder.encode(value, "UTF-8");
    } catch (Exception ignored) {
      return HOME_URL;
    }
  }

  static boolean isSearchInput(String raw) {
    String value = raw == null ? "" : raw.trim();
    if (value.isEmpty()) {
      return false;
    }
    Uri parsed = parse(value);
    if (parsed != null && isHttpScheme(parsed.getScheme())) {
      return false;
    }
    return value.contains(" ") || !value.matches(HOST_PATTERN);
  }

  static String safeWebUrl(String raw) {
    if (raw == null) {
      return "";
    }
    String value = raw.trim();
    Uri uri = parse(value);
    return uri != null && isHttpScheme(uri.getScheme()) ? value : "";
  }

  /**
   * In-page fallback for an app-style URI: the first http(s) query parameter, or the browser
   * fallback an app link carries in its {@code #Intent;...} fragment.
   */
  static String nestedWebUrl(Uri uri) {
    if (uri == null) {
      return "";
    }
    try {
      for (String name : uri.getQueryParameterNames()) {
        String candidate = safeWebUrl(uri.getQueryParameter(name));
        if (!candidate.isEmpty()) {
          return candidate;
        }
      }
    } catch (Exception ignored) {
    }
    return intentFallback(uri);
  }

  /**
   * Reads {@code browser_fallback_url} from an app link. Intent-style links put their extras in the
   * fragment instead of the query, so a query-only lookup left the in-page fallback empty and the
   * "打开网页备用链接" action had nothing to open.
   */
  static String intentFallback(Uri uri) {
    try {
      if ("intent".equalsIgnoreCase(schemeOf(uri))) {
        String declared = safeWebUrl(Intent.parseUri(uri.toString(), Intent.URI_INTENT_SCHEME)
            .getStringExtra("browser_fallback_url"));
        if (!declared.isEmpty()) {
          return declared;
        }
      }
    } catch (Exception ignored) {
    }
    String fragment = uri.getFragment();
    if (fragment == null || fragment.isEmpty()) {
      return "";
    }
    for (String part : fragment.split(";")) {
      String entry = part.trim();
      for (String key : new String[]{"S.browser_fallback_url=", "browser_fallback_url="}) {
        if (!entry.startsWith(key)) {
          continue;
        }
        String candidate = safeWebUrl(Uri.decode(entry.substring(key.length())));
        if (!candidate.isEmpty()) {
          return candidate;
        }
      }
    }
    return "";
  }

  static boolean isLanzouVerificationUrl(String raw) {
    try {
      Uri uri = parse(raw == null ? "" : raw.trim());
      String scheme = schemeOf(uri);
      String host = uri == null ? null : uri.getHost();
      return isHttpScheme(scheme) && host != null
          && host.matches("(?i)(?:[a-z0-9-]+[.])*(?:lanzou[a-z0-9-]*|lanzov)[.]com");
    } catch (Exception ignored) {
      return false;
    }
  }

  /** Scheme plus host only, so timing records never carry a query string or share password. */
  static String displayHost(String url) {
    Uri uri = parse(url == null ? "" : url.trim());
    String host = uri == null ? null : uri.getHost();
    return host == null || host.isEmpty() ? "local" : host;
  }
}
