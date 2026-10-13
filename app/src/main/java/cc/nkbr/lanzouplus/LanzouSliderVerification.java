package cc.nkbr.lanzouplus;

import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.view.InputDevice;
import android.view.MotionEvent;
import android.view.View;
import android.view.WindowManager;
import android.webkit.WebView;
import org.json.JSONObject;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

/**
 * One slider verification attempt in the same WebView/cookie session.
 *
 * <p>A blank document, an in-flight navigation and a WAF transition page are ordinary states of a
 * challenge that is still being solved, so none of them decides the outcome by itself. Only the
 * Core page verdict does: {@code LanzouCore.browserUseful} accepting the document as a real share
 * page is the single success signal, and the DOM state reported by the probe is used for the
 * synthetic drag and for visibility only. A document with no visible challenge is never success on
 * its own.</p>
 *
 * <p>The mode decides who owns the end of an attempt:</p>
 *
 * <ul>
 *   <li>{@link Mode#FOREGROUND}: a visible dialog owns it. The deadline only retires the synthetic
 *       drag, because past it the slider is the user's job (the first real finger retires it as
 *       well). Polling continues until {@link #close()}, so a manual slide that succeeds after the
 *       deadline is still picked up, and Main closes the dialog from its own cancel/dismiss path.
 *   <li>{@link Mode#BOUNDED}: a background attempt owns it. Reaching the deadline is terminal and
 *       reports a failure, which is what a page render with a resolution deadline needs.
 * </ul>
 */
final class LanzouSliderVerification implements AutoCloseable {
  /** Who owns the end of a transaction. */
  enum Mode{FOREGROUND,BOUNDED}
  /**
   * Reports the document plus the state of the challenge widget. "challenge" and "ready" are
   * visibility facts (rendered box, computed style, visible ancestors), so a {@code display:none}
   * remnant of an already solved slider is not reported as a live challenge.
   */
  private static final String PROBE_SCRIPT="(function(){\nvar html='',active=false,geometry=null;\nfunction visible(el,win){if(!el)return false;var r=el.getBoundingClientRect();if(!(r.width>0&&r.height>0))return false;for(var n=el;n&&n.nodeType===1;n=n.parentElement){var st=win.getComputedStyle(n);if(st.display==='none'||st.visibility==='hidden'||parseFloat(st.opacity)<=0.01)return false;}return true;}\nfunction scan(doc,win,ox,oy,sx,sy){\nhtml+='\\n'+(doc.documentElement?doc.documentElement.outerHTML:'');\nvar nodes=doc.querySelectorAll('#nc_1_n1z,.nc_scale,#aliyunCaptcha-window,[id*=captcha][role=dialog],[id*=Captcha][role=dialog]');\nfor(var i=0;i<nodes.length;i++)if(visible(nodes[i],win))active=true;\nvar pairs=[['#aliyunCaptcha-sliding-slider','#aliyunCaptcha-sliding-body'],['.nc_scale .btn_slide','.nc_scale'],['#nc_1_n1z','#nc_1__scale_text']];\nfor(var j=0;j<pairs.length;j++){var slider=doc.querySelector(pairs[j][0]),track=doc.querySelector(pairs[j][1]);if(!visible(slider,win)||!visible(track,win))continue;active=true;var a=slider.getBoundingClientRect(),b=track.getBoundingClientRect();if(!geometry&&b.width>a.width){geometry={x:ox+(a.left+a.width/2)*sx,y:oy+(a.top+a.height/2)*sy,distance:(b.right-a.right+2)*sx};}}\nvar words=doc.body?doc.body.innerText:'';active=active||words.indexOf('请完成以下操作，验证您是真人')>=0||words.indexOf('verify that you are a real person')>=0;\nvar frames=doc.querySelectorAll('iframe');for(var k=0;k<frames.length;k++){var f=frames[k];if(!visible(f,win))continue;try{var child=f.contentDocument;if(child){var r=f.getBoundingClientRect();scan(child,f.contentWindow,ox+(r.left+f.clientLeft)*sx,oy+(r.top+f.clientTop)*sy,sx*r.width/f.offsetWidth,sy*r.height/f.offsetHeight);}}catch(e){}}\n}\nscan(document,window,0,0,1,1);var vv=window.visualViewport;\nreturn {html:html,challenge:active,ready:!!geometry,x:geometry?geometry.x-(vv?vv.offsetLeft:0):0,y:geometry?geometry.y-(vv?vv.offsetTop:0):0,distance:geometry?geometry.distance:0,viewport:vv?vv.width:window.innerWidth};\n})()";
  /** Poll cadence: while the page is young, while it is settled, and after the drag retired. */
  private static final long ACTIVE_POLL_MS=120L,LOADING_POLL_MS=400L,IDLE_POLL_MS=1000L;
  private static final long ACTIVE_WINDOW_NANOS=TimeUnit.MILLISECONDS.toNanos(8000L);
  /** If no answer arrives for a probe, the next round is scheduled anyway. */
  private static final long PROBE_WATCHDOG_MS=2500L;
  /** Backoff for a probe whose evaluation threw, so a broken view cannot spin the poll. */
  private static final long FAILURE_BACKOFF_MS=250L;
  private boolean dispatchingTouch;
  private final WebView web;
  private final Handler handler=new Handler(Looper.getMainLooper());
  private final long deadline;
  private final Consumer<Boolean> completed;
  private final Mode mode;
  private boolean closed,attempted,pressed,userTouched,roundSettled;
  private int probeRound;
  private long downAt,duration,frameDelay,startedAtNanos=System.nanoTime(),failureBackoff;
  private float startX,startY,distance;
  private LanzouCore.DirectLink verifiedPage;

  /** A bounded attempt: its deadline is terminal and reports a failure. */
  LanzouSliderVerification(WebView web,long deadline,Consumer<Boolean> completed){
    this(web,deadline,completed,Mode.BOUNDED);
  }

  /** A dialog attempt: the deadline retires the synthetic drag, and the monitor outlives it. */
  static LanzouSliderVerification foreground(WebView web,long gestureDeadline,Consumer<Boolean> completed){
    return new LanzouSliderVerification(web,gestureDeadline,completed,Mode.FOREGROUND);
  }

  LanzouSliderVerification(WebView web,long deadline,Consumer<Boolean> completed,Mode mode){
    this.web=web;this.deadline=deadline;this.completed=completed;this.mode=mode;
  }

  void start(){
    // Verification images are functional inputs, including in the normally image-free renderer.
    web.getSettings().setBlockNetworkImage(false);
    web.getSettings().setLoadsImagesAutomatically(true);
    if(web.getWidth()==0||web.getHeight()==0){
      android.util.DisplayMetrics metrics=web.getResources().getDisplayMetrics();
      web.measure(View.MeasureSpec.makeMeasureSpec(metrics.widthPixels,View.MeasureSpec.EXACTLY),View.MeasureSpec.makeMeasureSpec(metrics.heightPixels,View.MeasureSpec.EXACTLY));
      web.layout(0,0,metrics.widthPixels,metrics.heightPixels);
    }
    startedAtNanos=System.nanoTime();
    // A real finger takes the slider over: it retires the synthetic drag (its pointer is cancelled
    // by the pending move frame, which keeps the observation running). Returning false leaves the
    // WebView's own touch handling untouched.
    web.setOnTouchListener((view,event)->{if(!dispatchingTouch){userTouched=true;cancelPointer();}return false;});
    probe();
  }

  private void probe(){
    if(closed)return;
    if(expired()){finish(false);return;}
    final int round=++probeRound;
    roundSettled=false;
    handler.postDelayed(()->{if(closed||round!=probeRound||roundSettled)return;roundSettled=true;scheduleNext();},PROBE_WATCHDOG_MS);
    try{
      web.evaluateJavascript(PROBE_SCRIPT,value->{
        if(closed||round!=probeRound||roundSettled)return;
        roundSettled=true;
        failureBackoff=0L;
        observe(value);
      });
    }catch(Throwable error){
      if(closed||round!=probeRound)return;
      roundSettled=true;
      failureBackoff=Math.min(IDLE_POLL_MS,Math.max(FAILURE_BACKOFF_MS,failureBackoff*2L));
      scheduleNext();
    }
  }

  /** Only a bounded attempt treats its deadline as final; a foreground dialog outlives it. */
  private boolean expired(){return mode==Mode.BOUNDED&&System.nanoTime()>=deadline;}

  /**
   * Classifies one probe answer. Only the Core page verdict ends the attempt: an empty, null or
   * unparsable answer, a page that is neither a challenge nor a share page, and a page whose
   * challenge widget is gone are all "not yet" and keep the poll alive.
   */
  private void observe(String value){
    if(closed)return;
    JSONObject state=parse(value);
    String html=state==null?"":state.optString("html","");
    LanzouCore.DirectLink page=new LanzouCore.DirectLink();
    page.html=html;
    page.url=web.getUrl();
    page.requestUserAgent=web.getSettings().getUserAgentString();
    page.browserRendered=true;page.httpStatus=200;page.createdAt=System.currentTimeMillis();
    if(state!=null&&LanzouCore.verifiedBrowserUseful(page,state.optBoolean("challenge"))){
      verifiedPage=page;finish(true);return;
    }
    if(!attempted&&!userTouched&&state!=null&&state.optBoolean("ready")&&System.nanoTime()<deadline&&beginGesture(state))return;
    scheduleNext();
  }

  /** A null, empty or non-object evaluation result means "no information", never "failed". */
  private static JSONObject parse(String value){
    if(value==null)return null;
    String text=value.trim();
    if(text.isEmpty()||"null".equals(text))return null;
    try{return new JSONObject(text);}catch(Throwable ignored){return null;}
  }

  private void scheduleNext(){
    if(closed)return;
    handler.postDelayed(this::probe,Math.max(pollDelay(),failureBackoff));
  }

  /** Fast while the dialog is young, slower once the page has settled, so the poll is bounded. */
  private long pollDelay(){
    long now=System.nanoTime();
    if(now-startedAtNanos<ACTIVE_WINDOW_NANOS)return ACTIVE_POLL_MS;
    return now<deadline?LOADING_POLL_MS:IDLE_POLL_MS;
  }

  /** @return true when the synthetic drag started; the drag itself reschedules the next probe. */
  private boolean beginGesture(JSONObject state){
    long remaining=TimeUnit.NANOSECONDS.toMillis(deadline-System.nanoTime());
    if(remaining<450L)return false;
    double viewport=state.optDouble("viewport"),cssDistance=state.optDouble("distance");
    if(viewport<=0d||cssDistance<=0d)return false;
    float scale=(float)(web.getWidth()/viewport);
    startX=(float)state.optDouble("x")*scale;startY=(float)state.optDouble("y")*scale;distance=(float)cssDistance*scale;
    duration=Math.min(remaining/2L,Math.max(450L,Math.round(cssDistance*3d)));
    WindowManager manager=(WindowManager)web.getContext().getSystemService(android.content.Context.WINDOW_SERVICE);
    frameDelay=Math.max(1L,Math.round(1000d/manager.getDefaultDisplay().getRefreshRate()));
    attempted=true;web.requestFocus();
    web.postVisualStateCallback(SystemClock.uptimeMillis(),new WebView.VisualStateCallback(){
      @Override public void onComplete(long requestId){web.postOnAnimation(()->{
        if(closed)return;
        if(userTouched||System.nanoTime()>=deadline){scheduleNext();return;}
        pressed=true;downAt=SystemClock.uptimeMillis();
        touch(MotionEvent.ACTION_DOWN,startX,startY);handler.postDelayed(LanzouSliderVerification.this::move,frameDelay);
      });}
    });
    return true;
  }

  private void move(){
    if(closed)return;
    if(!pressed){scheduleNext();return;}
    if(expired()){finish(false);return;}
    if(userTouched||System.nanoTime()>=deadline){cancelPointer();scheduleNext();return;}
    double progress=Math.min(1d,(double)(SystemClock.uptimeMillis()-downAt)/duration);
    float x=startX+distance*(float)(1d-Math.pow(1d-progress,3d));
    touch(MotionEvent.ACTION_MOVE,x,startY);
    if(progress<1d){handler.postDelayed(this::move,frameDelay);return;}
    lift(startX+distance,startY);handler.postDelayed(this::probe,180L);
  }

  private void lift(float x,float y){
    if(!pressed)return;
    pressed=false;
    try{touch(MotionEvent.ACTION_UP,x,y);}catch(Throwable ignored){}
  }

  private void cancelPointer(){
    if(!pressed)return;
    pressed=false;
    try{touch(MotionEvent.ACTION_CANCEL,startX,startY);}catch(Throwable ignored){}
  }

  LanzouCore.DirectLink verifiedPage(){return verifiedPage;}

  private void touch(int action,float x,float y){
    MotionEvent.PointerProperties pointer=new MotionEvent.PointerProperties();pointer.id=0;pointer.toolType=MotionEvent.TOOL_TYPE_FINGER;
    MotionEvent.PointerCoords point=new MotionEvent.PointerCoords();point.x=x;point.y=y;point.pressure=action==MotionEvent.ACTION_UP||action==MotionEvent.ACTION_CANCEL?0f:1f;point.size=0.5f;
    MotionEvent event=MotionEvent.obtain(downAt,SystemClock.uptimeMillis(),action,1,new MotionEvent.PointerProperties[]{pointer},new MotionEvent.PointerCoords[]{point},0,0,1f,1f,0,0,InputDevice.SOURCE_TOUCHSCREEN,0);
    dispatchingTouch=true;
    try{web.dispatchTouchEvent(event);}finally{dispatchingTouch=false;event.recycle();}
  }

  /** Terminal outcome: a real share page verdict, or an expired bounded deadline. */
  private void finish(boolean passed){
    if(closed)return;
    close();
    completed.accept(passed);
  }

  /** Ends the poll without reporting an outcome; Main decides what a closed dialog means. */
  @Override public void close(){
    if(closed)return;
    closed=true;
    handler.removeCallbacksAndMessages(null);
    cancelPointer();
  }
}
