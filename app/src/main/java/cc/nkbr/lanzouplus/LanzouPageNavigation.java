package cc.nkbr.lanzouplus;

import java.net.URL;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Static navigation emitted by share/transfer pages; the caller owns cookies and the deadline. */
final class LanzouPageNavigation {
  private static final Pattern REFRESH=Pattern.compile("(?is)<meta\\b(?=[^>]*http-equiv\\s*=\\s*['\"]?refresh\\b)(?=[^>]*content\\s*=\\s*['\"][^'\"]*?url\\s*=\\s*([^'\"]+))[^>]*>");
  private static final Pattern LOCATION=Pattern.compile("(?is)(?:window\\.|top\\.|self\\.|document\\.)?location(?:\\.href)?\\s*=\\s*['\"]([^'\"]+)['\"]|(?:window\\.|top\\.|self\\.)?location\\.(?:replace|assign)\\(\\s*['\"]([^'\"]+)['\"]\\s*\\)");
  private static final Pattern DOWNLOAD_FRAME=Pattern.compile("(?is)<iframe\\b[^>]*\\bsrc\\s*=\\s*['\"]([^'\"]*(?:\\bfn(?:\\?|/|[-_])|/tp/)[^'\"]*)['\"]");
  private static final Pattern MOBILE_TRANSFER=Pattern.compile("(?is)<a\\b(?=[^>]*\\bid=['\"]ddown['\"])(?=[^>]*\\bhref=['\"]([^'\"]*/tp/#[0-9]+)['\"])[^>]*>");
  private static final Pattern EXPLAINED_SHARE=Pattern.compile("(?i)^(https?://(?:[a-z0-9-]+\\.)?(?:lanzou[a-z]*|lanzo)[.]com/(?:[ib][a-z0-9_-]+|[su]/[a-z0-9_-]+))(?=[\\p{IsHan}，。；、])");
  private LanzouPageNavigation() {}

  static String cleanShareInput(String value) {
    Matcher explained=EXPLAINED_SHARE.matcher(value.trim());
    return explained.find()?explained.group(1):value.trim();
  }

  static String redirect(String html,String base) {
    Matcher refresh=REFRESH.matcher(html);
    if(refresh.find())return absolute(base,refresh.group(1));
    Matcher location=LOCATION.matcher(html);
    // A download script can contain navigation in a click handler. Its explicit protocol owns it.
    if(!downloadProtocol(html)&&location.find())return absolute(base,location.group(1)==null?location.group(2):location.group(1));
    return "";
  }

  static String downloadFrame(String html) {
    Matcher frame=DOWNLOAD_FRAME.matcher(html);
    return frame.find()?frame.group(1).replace("&amp;","&"):"";
  }

  static boolean mobileHashTransfer(String html){return MOBILE_TRANSFER.matcher(html).find();}

  static boolean downloadProtocol(String html) {
    return mobileHashTransfer(html)||html.contains("downprocess")||html.contains("vkjxld")&&html.contains("hyggid")
        ||!downloadFrame(html).isEmpty();
  }

  private static String absolute(String base,String target) {
    try {
      URL url=new URL(new URL(base),target.trim().replace("&amp;","&"));
      return url.getProtocol().equals("http")||url.getProtocol().equals("https")?url.toString():"";
    } catch(java.net.MalformedURLException error) {return "";}
  }
}
