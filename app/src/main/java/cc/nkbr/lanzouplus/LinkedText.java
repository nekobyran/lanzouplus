package cc.nkbr.lanzouplus;

import android.text.Spannable;
import android.text.SpannableString;
import android.text.Spanned;
import android.text.TextPaint;
import android.text.method.LinkMovementMethod;
import android.text.style.ClickableSpan;
import android.text.style.URLSpan;
import android.text.util.Linkify;
import android.util.Patterns;
import android.view.View;
import android.widget.TextView;
import java.util.function.Consumer;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Selectable description text with links opened by the host's existing navigation policy. */
final class LinkedText {
  // Platform Linkify's word boundary omits bare ASCII domains adjacent to Chinese prose.
  // This small authority matcher supplies that boundary; Patterns still validates the URL.
  private static final Pattern ASCII_AUTHORITY=Pattern.compile(
      "(?i)(?:https?://)?(?:[a-z0-9](?:[a-z0-9-]*[a-z0-9])?\\.)+[a-z]{2,63}(?::[0-9]{1,5})?");
  private static final String PROSE_PUNCT="，。；：！？、）】》〉”’";

  private LinkedText() {}

  static void attach(TextView view,int linkColor,Consumer<String> opener) {
    SpannableString text=new SpannableString(view.getText());
    for(URLSpan span:text.getSpans(0,text.length(),URLSpan.class)) {
      int start=text.getSpanStart(span),end=text.getSpanEnd(span),flags=text.getSpanFlags(span);
      text.removeSpan(span);
      text.setSpan(new Link(span.getURL(),linkColor,opener),start,end,flags);
    }
    // Reattaching updates the owner and theme without accumulating spans.
    for(Link span:text.getSpans(0,text.length(),Link.class)) {
      span.color=linkColor;
      span.opener=opener;
    }
    SpannableString detected=new SpannableString(text.toString());
    Linkify.addLinks(detected,Linkify.WEB_URLS);
    for(URLSpan candidate:detected.getSpans(0,detected.length(),URLSpan.class)) {
      int start=detected.getSpanStart(candidate),end=detected.getSpanEnd(candidate);
      Matcher authority=ASCII_AUTHORITY.matcher(text).region(start,end);
      if(authority.find()) {
        start=authority.start();
        if(authority.end()<end&&isHan(text.charAt(authority.end())))end=authority.end();
      }
      add(text,start,urlEnd(text,start,end),linkColor,opener);
    }
    Matcher authorities=ASCII_AUTHORITY.matcher(text);
    while(authorities.find()) {
      int start=authorities.start(),end=authorities.end();
      if(start>0&&(asciiWord(text.charAt(start-1))||text.charAt(start-1)=='@'))continue;
      if(end<text.length()&&"/?#".indexOf(text.charAt(end))>=0) {
        while(end<text.length()&&!Character.isWhitespace(text.charAt(end))
            &&"<>\"".indexOf(text.charAt(end))<0&&PROSE_PUNCT.indexOf(text.charAt(end))<0)end++;
      }
      add(text,start,urlEnd(text,start,end),linkColor,opener);
    }
    view.setText(text);
    view.setTextIsSelectable(true);
    view.setLinksClickable(true);
    view.setMovementMethod(LinkMovementMethod.getInstance());
  }

  private static void add(Spannable text,int start,int end,int color,Consumer<String> opener) {
    if(end<=start||text.getSpans(start,end,ClickableSpan.class).length!=0)return;
    String visible=text.subSequence(start,end).toString();
    if(!Patterns.WEB_URL.matcher(visible).matches())return;
    String target=visible.regionMatches(true,0,"http://",0,7)
        ||visible.regionMatches(true,0,"https://",0,8)?visible:"https://"+visible;
    text.setSpan(new Link(target,color,opener),start,end,Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
  }

  /** Excludes prose punctuation while retaining balanced brackets inside a URL. */
  static int urlEnd(CharSequence text,int start,int end) {
    for(int i=start;i<end;i++)if(PROSE_PUNCT.indexOf(text.charAt(i))>=0){end=i;break;}
    boolean changed=true;
    while(changed&&end>start) {
      changed=false;
      char last=text.charAt(end-1);
      if(".,;:!?\"'".indexOf(last)>=0){end--;changed=true;continue;}
      String opens="([{",closes=")]}";
      int bracket=closes.indexOf(last);
      if(bracket>=0) {
        int balance=0;
        for(int i=start;i<end;i++) {
          if(text.charAt(i)==opens.charAt(bracket))balance++;
          else if(text.charAt(i)==last)balance--;
        }
        if(balance<0){end--;changed=true;}
      }
    }
    return end;
  }

  private static boolean asciiWord(char value) {
    return value>='a'&&value<='z'||value>='A'&&value<='Z'
        ||value>='0'&&value<='9'||value=='_'||value=='-';
  }
  private static boolean isHan(char value) {
    return Character.UnicodeScript.of(value)==Character.UnicodeScript.HAN;
  }
  private static final class Link extends ClickableSpan {
    final String target;
    int color;
    Consumer<String> opener;
    Link(String target,int color,Consumer<String> opener){this.target=target;this.color=color;this.opener=opener;}
    @Override public void onClick(View widget){opener.accept(target);}
    @Override public void updateDrawState(TextPaint paint){paint.setColor(color);paint.setUnderlineText(true);}
  }
}
