package cc.nkbr.lanzouplus;

import java.util.Locale;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Measured Lanzou routing facts.
 *
 * <p>Every origin and every origin/UA family pairing below was checked on 2026-11-26 against a live
 * password protected share: the origin counts as usable only when the response carried the real
 * share page (the {@code filemoreajax} listing call). Parked suffixes
 * ({@code lanzoua/d/s/z}) and unreachable hosts were dropped. {@code lanzoum} serves mobile UAs
 * only, {@code lanzouq} serves Android Chrome only and {@code lanzoui}/{@code lanzoup} serve
 * desktop UAs only, so a domain switch alone is not enough: the client has to move the UA with it.
 */
final class LanzouRouteCatalog {
  private LanzouRouteCatalog(){}

  static final String[] BASE_ORIGINS={
      "https://www.lanzoux.com",
      "https://wwc.lanzoux.com",
      "https://wwi.lanzoux.com",
      "https://wws.lanzoux.com",
      "https://www.lanzouw.com",
      "https://wwc.lanzouw.com",
      "https://www.lanzoum.com",
      "https://www.lanzouq.com",
      "https://www.lanzoul.com",
      "https://www.lanzouo.com",
      "https://www.lanzouu.com",
      "https://www.lanzouv.com",
      "https://www.lanzoub.com",
      "https://www.lanzouc.com",
      "https://www.lanzoue.com",
      "https://www.lanzouf.com",
      "https://www.lanzoug.com",
      "https://www.lanzouh.com",
      "https://www.lanzouj.com",
      "https://www.lanzouk.com",
      "https://www.lanzout.com",
      "https://www.lanzoui.com",
      "https://www.lanzoup.com"};

  /** Authoritative UAs: the exact strings the current release already proved against every template. */
  private static final String AUTH_MOBILE_CHROME="Mozilla/5.0 (Linux; Android 14; 23127PN0CC) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/138.0.0.0 Mobile Safari/537.36";
  private static final String AUTH_DESKTOP_CHROME="Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/138.0.0.0 Safari/537.36";
  private static final String AUTH_DESKTOP_EDGE="Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/138.0.0.0 Safari/537.36 Edg/138.0.0.0";
  private static final String AUTH_MOBILE_HUAWEI="Mozilla/5.0 (Linux; Android 14; BRQ-AN00) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/138.0.0.0 Mobile Safari/537.36";
  private static final String AUTH_MOBILE_FIREFOX="Mozilla/5.0 (Android 12; Mobile; rv:126.0) Gecko/126.0 Firefox/126.0";

  /** Random pool per preset: real device/browser signatures, newest first. */
  private static final String[] POOL_MOBILE_CHROME={
      "Mozilla/5.0 (Linux; Android 15; Pixel 9) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/141.0.0.0 Mobile Safari/537.36",
      "Mozilla/5.0 (Linux; Android 14; SM-S928B) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/140.0.0.0 Mobile Safari/537.36",
      "Mozilla/5.0 (Linux; Android 15; V2309A) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/141.0.0.0 Mobile Safari/537.36",
      "Mozilla/5.0 (Linux; Android 14; RMX3820) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/139.0.0.0 Mobile Safari/537.36",
      "Mozilla/5.0 (Linux; Android 13; 2211133C) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/136.0.0.0 Mobile Safari/537.36",
      AUTH_MOBILE_CHROME};
  private static final String[] POOL_DESKTOP_CHROME={
      "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/141.0.0.0 Safari/537.36",
      "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/141.0.0.0 Safari/537.36",
      "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/139.0.0.0 Safari/537.36",
      "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/136.0.0.0 Safari/537.36",
      AUTH_DESKTOP_CHROME};
  private static final String[] POOL_DESKTOP_EDGE={
      "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/141.0.0.0 Safari/537.36 Edg/141.0.0.0",
      "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/139.0.0.0 Safari/537.36 Edg/139.0.0.0",
      "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/140.0.0.0 Safari/537.36 Edg/140.0.0.0",
      AUTH_DESKTOP_EDGE};
  private static final String[] POOL_MOBILE_HUAWEI={
      "Mozilla/5.0 (Linux; Android 14; ALN-AL00) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/140.0.0.0 Mobile Safari/537.36",
      "Mozilla/5.0 (Linux; Android 12; HarmonyOS; NOH-AN00) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/114.0.0.0 Mobile Safari/537.36",
      "Mozilla/5.0 (Linux; Android 13; HUAWEI Mate 60 Pro) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/139.0.0.0 Mobile Safari/537.36",
      AUTH_MOBILE_HUAWEI};
  private static final String[] POOL_MOBILE_FIREFOX={
      "Mozilla/5.0 (Android 14; Mobile; rv:133.0) Gecko/133.0 Firefox/133.0",
      "Mozilla/5.0 (Android 13; Mobile; rv:129.0) Gecko/129.0 Firefox/129.0",
      AUTH_MOBILE_FIREFOX};

  static String authoritativeUserAgent(int preset){
    switch(LanzouCore.normalizeUserAgentPreset(preset)){
      case LanzouCore.UA_PRESET_DESKTOP_CHROME:return AUTH_DESKTOP_CHROME;
      case LanzouCore.UA_PRESET_DESKTOP_EDGE:return AUTH_DESKTOP_EDGE;
      case LanzouCore.UA_PRESET_MOBILE_HUAWEI:return AUTH_MOBILE_HUAWEI;
      case LanzouCore.UA_PRESET_MOBILE_FIREFOX:return AUTH_MOBILE_FIREFOX;
      default:return AUTH_MOBILE_CHROME;
    }
  }

  /** Random UA of the given family, used while probing a new origin so each attempt looks new. */
  static String randomUserAgent(int preset){
    String[] pool=pool(preset);
    return pool[ThreadLocalRandom.current().nextInt(pool.length)];
  }

  private static String[] pool(int preset){
    switch(LanzouCore.normalizeUserAgentPreset(preset)){
      case LanzouCore.UA_PRESET_DESKTOP_CHROME:return POOL_DESKTOP_CHROME;
      case LanzouCore.UA_PRESET_DESKTOP_EDGE:return POOL_DESKTOP_EDGE;
      case LanzouCore.UA_PRESET_MOBILE_HUAWEI:return POOL_MOBILE_HUAWEI;
      case LanzouCore.UA_PRESET_MOBILE_FIREFOX:return POOL_MOBILE_FIREFOX;
      default:return POOL_MOBILE_CHROME;
    }
  }

  /**
   * 0 when the origin is known to serve the given UA family, 1 when nothing is known, 2 when the
   * pair was measured to fail. Route ordering uses this so a probe does not burn a request on a
   * combination that provably returns the "domain moved" shell.
   */
  static int presetRank(String rawOrigin,int preset){
    String host=host(rawOrigin);
    if(host.isEmpty())return 1;
    String suffix=suffix(host);
    boolean desktop=isDesktopPreset(preset),firefox=LanzouCore.normalizeUserAgentPreset(preset)==LanzouCore.UA_PRESET_MOBILE_FIREFOX;
    switch(suffix){
      case "lanzoum.com":return desktop?2:0;
      case "lanzouq.com":return desktop||firefox?2:0;
      case "lanzoui.com":
      case "lanzoup.com":return desktop?0:2;
      case "lanzouo.com":return firefox?2:0;
      case "lanzoux.com":
      case "lanzouw.com":
      case "lanzoul.com":
      case "lanzouu.com":
      case "lanzouv.com":
      case "lanzoub.com":
      case "lanzouc.com":
      case "lanzoue.com":
      case "lanzouf.com":
      case "lanzoug.com":
      case "lanzouh.com":
      case "lanzouj.com":
      case "lanzouk.com":
      case "lanzout.com":return 0;
      default:return 1;
    }
  }

  private static boolean isDesktopPreset(int preset){
    int normalized=LanzouCore.normalizeUserAgentPreset(preset);
    return normalized==LanzouCore.UA_PRESET_DESKTOP_CHROME||normalized==LanzouCore.UA_PRESET_DESKTOP_EDGE;
  }

  private static String host(String rawOrigin){
    if(rawOrigin==null||rawOrigin.isEmpty())return "";
    try{return new java.net.URL(rawOrigin).getHost();}catch(Exception ignored){return "";}
  }

  /** {@code wwc.lanzoux.com} and {@code abcde.lanzoux.com} both key off {@code lanzoux.com}. */
  private static String suffix(String host){
    String value=host.toLowerCase(Locale.ROOT);
    int dot=value.indexOf('.');
    while(dot>=0){
      String candidate=value.substring(dot+1);
      if(candidate.matches("lanzou[a-z]\\.com"))return candidate;
      dot=value.indexOf('.',dot+1);
    }
    return value;
  }
}
