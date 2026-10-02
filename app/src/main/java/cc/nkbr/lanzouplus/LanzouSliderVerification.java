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
import org.json.JSONException;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

/** A foreground verification transaction in the same WebView/cookie session. */
final class LanzouSliderVerification implements AutoCloseable {
  private static final String PROBE_SCRIPT="(function(){var h=document.documentElement?document.documentElement.outerHTML:'';"
      +"var s=document.getElementById('aliyunCaptcha-sliding-slider');var t=document.getElementById('aliyunCaptcha-sliding-body');"
      +"if(!s||!t)return {html:h,ready:false};var a=s.getBoundingClientRect(),b=t.getBoundingClientRect();"
      +"return {html:h,ready:a.width>0&&a.height>0&&b.width>a.width,x:a.left+a.width/2,y:a.top+a.height/2,"
      +"distance:b.right-a.right,viewport:window.innerWidth};})()";
  private final WebView web;
  private final Handler handler=new Handler(Looper.getMainLooper());
  private final long deadline;
  private final Consumer<Boolean> completed;
  private boolean closed,attempted,pressed;
  private long downAt,duration,frameDelay;
  private float startX,startY,distance;

  LanzouSliderVerification(WebView web,long deadline,Consumer<Boolean> completed){
    this.web=web;this.deadline=deadline;this.completed=completed;
  }

  void start(){
    if(web.getWidth()==0||web.getHeight()==0){
      android.util.DisplayMetrics metrics=web.getResources().getDisplayMetrics();
      web.measure(View.MeasureSpec.makeMeasureSpec(metrics.widthPixels,View.MeasureSpec.EXACTLY),View.MeasureSpec.makeMeasureSpec(metrics.heightPixels,View.MeasureSpec.EXACTLY));
      web.layout(0,0,metrics.widthPixels,metrics.heightPixels);
    }
    probe();
  }

  private void probe(){
    if(closed)return;
    if(System.nanoTime()>=deadline){finish(false);return;}
    web.evaluateJavascript(PROBE_SCRIPT,value->{
      if(closed)return;
      try{
        JSONObject state=new JSONObject(value);
        LanzouCore.DirectLink page=new LanzouCore.DirectLink();page.html=state.optString("html");
        if(LanzouCore.browserUseful(page)){finish(true);return;}
        if(!LanzouCore.isWafChallengePage(page.html)){finish(false);return;}
        if(!attempted&&state.optBoolean("ready")){beginGesture(state);return;}
        handler.postDelayed(this::probe,120L);
      }catch(JSONException error){finish(false);}
    });
  }

  private void beginGesture(JSONObject state){
    long remaining=TimeUnit.NANOSECONDS.toMillis(deadline-System.nanoTime());
    if(remaining<450L){finish(false);return;}
    double viewport=state.optDouble("viewport"),cssDistance=state.optDouble("distance");
    if(viewport<=0d||cssDistance<=0d){finish(false);return;}
    float scale=(float)(web.getWidth()/viewport);
    startX=(float)state.optDouble("x")*scale;startY=(float)state.optDouble("y")*scale;distance=(float)cssDistance*scale;
    duration=Math.min(remaining/2L,Math.max(450L,Math.round(cssDistance*3d)));
    WindowManager manager=(WindowManager)web.getContext().getSystemService(android.content.Context.WINDOW_SERVICE);
    frameDelay=Math.max(1L,Math.round(1000d/manager.getDefaultDisplay().getRefreshRate()));
    attempted=pressed=true;downAt=SystemClock.uptimeMillis();web.requestFocus();
    touch(MotionEvent.ACTION_DOWN,startX,startY);handler.postDelayed(this::move,frameDelay);
  }

  private void move(){
    if(closed)return;
    if(System.nanoTime()>=deadline){finish(false);return;}
    double progress=Math.min(1d,(double)(SystemClock.uptimeMillis()-downAt)/duration);
    float x=startX+distance*(float)(1d-Math.pow(1d-progress,3d));
    touch(MotionEvent.ACTION_MOVE,x,startY);
    if(progress<1d){handler.postDelayed(this::move,frameDelay);return;}
    touch(MotionEvent.ACTION_UP,startX+distance,startY);pressed=false;handler.postDelayed(this::probe,180L);
  }

  private void touch(int action,float x,float y){
    MotionEvent.PointerProperties pointer=new MotionEvent.PointerProperties();pointer.id=0;pointer.toolType=MotionEvent.TOOL_TYPE_FINGER;
    MotionEvent.PointerCoords point=new MotionEvent.PointerCoords();point.x=x;point.y=y;point.pressure=action==MotionEvent.ACTION_UP||action==MotionEvent.ACTION_CANCEL?0f:1f;point.size=0.5f;
    MotionEvent event=MotionEvent.obtain(downAt,SystemClock.uptimeMillis(),action,1,new MotionEvent.PointerProperties[]{pointer},new MotionEvent.PointerCoords[]{point},0,0,1f,1f,0,0,InputDevice.SOURCE_TOUCHSCREEN,0);
    try{web.onTouchEvent(event);}finally{event.recycle();}
  }

  private void finish(boolean passed){if(closed)return;close();completed.accept(passed);}

  @Override public void close(){
    if(closed)return;closed=true;handler.removeCallbacksAndMessages(null);
    if(pressed){touch(MotionEvent.ACTION_CANCEL,startX,startY);pressed=false;}
  }
}
