package cc.nkbr.lanzouplus;

import android.net.Uri;
import android.view.View;
import android.webkit.WebResourceRequest;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import java.net.URI;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.commonmark.Extension;
import org.commonmark.ext.gfm.strikethrough.StrikethroughExtension;
import org.commonmark.ext.gfm.tables.TablesExtension;
import org.commonmark.ext.heading.anchor.HeadingAnchorExtension;
import org.commonmark.ext.task.list.items.TaskListItemsExtension;
import org.commonmark.node.Image;
import org.commonmark.node.Link;
import org.commonmark.node.AbstractVisitor;
import org.commonmark.node.HtmlBlock;
import org.commonmark.node.HtmlInline;
import org.commonmark.node.Node;
import org.commonmark.parser.Parser;
import org.commonmark.renderer.html.HtmlRenderer;

/** Repository Markdown stays local; only its images and explicitly opened links use the network. */
final class MarketReadmeView {
  private static final Pattern GITHUB_HTML_IMAGE=Pattern.compile(
      "(?i)(<img\\b[^>]*?\\bsrc\\s*=\\s*[\"']?)https://github\\.com/([^/\"'<>]+/[^/\"'<>]+)/(?:blob|raw)/");
  private static final List<Extension> EXTENSIONS=Arrays.asList(
      TablesExtension.create(),StrikethroughExtension.create(),
      TaskListItemsExtension.create(),HeadingAnchorExtension.create());

  static WebView create(MainActivity owner,String markdown,String rawRoot,String githubUrl){
    WebView view=new WebView(owner);
    view.setBackgroundColor(owner.SURFACE);
    view.setContentDescription(owner.getString(R.string.market_readme_content));
    WebSettings settings=view.getSettings();
    settings.setJavaScriptEnabled(false);
    settings.setAllowFileAccess(false);
    settings.setAllowContentAccess(false);
    settings.setBuiltInZoomControls(true);
    settings.setDisplayZoomControls(false);
    settings.setUseWideViewPort(true);
    settings.setMixedContentMode(WebSettings.MIXED_CONTENT_NEVER_ALLOW);
    view.setWebViewClient(new WebViewClient(){
      @Override public boolean shouldOverrideUrlLoading(WebView web,WebResourceRequest request){
        if(!request.isForMainFrame())return false;
        Uri target=request.getUrl();
        if(target.getFragment()!=null&&sameDocument(web.getUrl(),target.toString()))return false;
        String scheme=target.getScheme();
        if("https".equalsIgnoreCase(scheme)||"http".equalsIgnoreCase(scheme))
          owner.openWebPage(target.toString(),"",false);
        return true;
      }
    });
    AtomicBoolean detached=new AtomicBoolean();
    view.addOnAttachStateChangeListener(new View.OnAttachStateChangeListener(){
      @Override public void onViewAttachedToWindow(View attached){ }
      @Override public void onViewDetachedFromWindow(View detachedView){detached.set(true);}
    });
    int surface=owner.SURFACE,text=owner.TEXT,muted=owner.MUTED,accent=owner.PRIMARY;
    boolean dark=owner.darkMode;
    String loading=owner.getString(R.string.market_readme_loading);
    view.loadDataWithBaseURL(rawRoot,document("<p>"+escape(loading)+"</p>",surface,text,muted,accent,dark),"text/html","UTF-8",null);
    owner.sourceMetadataIo.execute(()->{
      String html=render(markdown,rawRoot,githubUrl,surface,text,muted,accent,dark);
      owner.runOnUiThread(()->{
        if(!detached.get()&&!owner.isFinishing()&&!owner.isDestroyed())
          view.loadDataWithBaseURL(rawRoot,html,"text/html","UTF-8",null);
      });
    });
    return view;
  }

  static String render(String markdown,String rawRoot,String githubUrl,int surface,int text,int muted,int accent,boolean dark){
    Parser parser=Parser.builder().extensions(EXTENSIONS).build();
    Node document=parser.parse(markdown);
    document.accept(new AbstractVisitor(){
      @Override public void visit(HtmlBlock block){block.setLiteral(rawImages(block.getLiteral()));}
      @Override public void visit(HtmlInline inline){inline.setLiteral(rawImages(inline.getLiteral()));}
    });
    HtmlRenderer renderer=HtmlRenderer.builder().extensions(EXTENSIONS).sanitizeUrls(true).percentEncodeUrls(true)
        .attributeProviderFactory(context->(node,tag,attributes)->{
          if(node instanceof Image){
            String image=attributes.get("src");
            if(image!=null&&!image.isEmpty())attributes.put("src",imageUrl(rawRoot,image));
            attributes.put("loading","lazy");
          }else if(node instanceof Link){
            String link=attributes.get("href");
            if(link!=null&&!link.isEmpty()&&!link.startsWith("#"))attributes.put("href",linkUrl(githubUrl,link));
          }
        }).build();
    return document(renderer.render(document),surface,text,muted,accent,dark);
  }

  private static String document(String body,int surface,int text,int muted,int accent,boolean dark){
    return "<!doctype html><html lang=\"zh-CN\"><head><meta charset=\"UTF-8\">"
        +"<meta name=\"viewport\" content=\"width=device-width,initial-scale=1\">"
        +"<meta http-equiv=\"Content-Security-Policy\" content=\"default-src 'none'; img-src https: data:; "
        +"style-src 'unsafe-inline'; script-src 'none'; frame-src 'none'; object-src 'none'; base-uri 'none'; form-action 'none'\">"
        +"<style>:root{color-scheme:"+(dark?"dark":"light")+"}*{box-sizing:border-box}"
        +"html,body{margin:0;background:"+color(surface)+";color:"+color(text)+"}"
        +"body{padding:4px 18px 20px;font:15px/1.65 system-ui,sans-serif;overflow-wrap:anywhere}"
        +"h1,h2,h3,h4,h5,h6{line-height:1.35;margin:1.2em 0 .55em}h1{font-size:1.75em}h2{font-size:1.4em}"
        +"h1,h2{border-bottom:1px solid "+color(muted)+"40;padding-bottom:.3em}"
        +"a{color:"+color(accent)+";text-decoration:underline}img{max-width:100%;height:auto;vertical-align:middle}"
        +"p,ul,ol,blockquote,pre,table{margin:.7em 0}ul,ol{padding-left:1.65em}"
        +"li>p{margin:.3em 0}blockquote{margin-left:0;padding:.1em 1em;color:"+color(muted)+";border-left:3px solid "+color(accent)+"}"
        +"pre{overflow-x:auto;padding:12px;border-radius:10px;background:"+color(muted)+"16;white-space:pre}"
        +"code{font: .88em/1.6 monospace;background:"+color(muted)+"16;border-radius:4px;padding:2px 4px}"
        +"pre code{padding:0;background:transparent}table{display:block;overflow-x:auto;border-collapse:collapse;max-width:100%}"
        +"th,td{padding:7px 10px;border:1px solid "+color(muted)+"60;text-align:left;min-width:64px}"
        +"th{background:"+color(muted)+"16}hr{border:0;border-top:1px solid "+color(muted)+"50;margin:18px 0}"
        +"input[type=checkbox]{accent-color:"+color(accent)+"}details{padding:6px 0}summary{cursor:pointer}"
        +"svg,video{max-width:100%}</style></head><body>"+body+"</body></html>";
  }

  static String imageUrl(String rawRoot,String value){
    URI uri=URI.create(rawRoot).resolve(value);
    String path=uri.getPath();
    if("github.com".equalsIgnoreCase(uri.getHost())&&path!=null&&path.matches("/[^/]+/[^/]+/(?:blob|raw)/.+"))
      return "https://raw.githubusercontent.com"+path.replaceFirst("^/([^/]+/[^/]+)/(?:blob|raw)/","/$1/");
    return uri.toString();
  }

  static String linkUrl(String githubUrl,String value){
    return URI.create(githubUrl+"/blob/HEAD/").resolve(value).toString();
  }

  private static String rawImages(String html){
    Matcher images=GITHUB_HTML_IMAGE.matcher(html);
    StringBuffer output=new StringBuffer();
    while(images.find())images.appendReplacement(output,Matcher.quoteReplacement(
        images.group(1)+"https://raw.githubusercontent.com/"+images.group(2)+"/"));
    images.appendTail(output);
    return output.toString();
  }

  private static boolean sameDocument(String current,String target){
    if(current==null)return false;
    return current.split("#",2)[0].equals(target.split("#",2)[0]);
  }
  private static String color(int value){return String.format(Locale.ROOT,"#%06x",value&0xffffff);}
  private static String escape(String value){return value.replace("&","&amp;").replace("<","&lt;").replace(">","&gt;");}
}
