package cc.nkbr.lanzouplus;

import android.animation.ValueAnimator;
import android.content.Context;
import android.content.res.Configuration;
import android.graphics.*;
import android.os.Build;
import android.widget.CompoundButton;

final class LumaSwitch extends CompoundButton{
  private final Paint paint=new Paint(Paint.ANTI_ALIAS_FLAG);
  private final RectF rect=new RectF();
  private float progress;
  private ValueAnimator animator;
  LumaSwitch(Context context){super(context);setButtonDrawable(null);setMinWidth(dp(54));setMinHeight(dp(34));setPadding(0,0,0,0);progress=isChecked()?1f:0f;setClickable(true);}
  @Override public void setChecked(boolean checked){boolean changed=checked!=isChecked();super.setChecked(checked);float target=checked?1f:0f;if(changed&&getWindowToken()!=null&&Build.VERSION.SDK_INT>=11){if(animator!=null)animator.cancel();animator=ValueAnimator.ofFloat(progress,target);animator.setDuration(190);animator.addUpdateListener(a->{progress=(Float)a.getAnimatedValue();invalidate();});animator.start();}else{progress=target;invalidate();}}
  @Override protected void onMeasure(int widthSpec,int heightSpec){setMeasuredDimension(resolveSize(dp(54),widthSpec),resolveSize(dp(34),heightSpec));}
  @Override protected void onDraw(Canvas canvas){super.onDraw(canvas);boolean dark=(getResources().getConfiguration().uiMode&Configuration.UI_MODE_NIGHT_MASK)==Configuration.UI_MODE_NIGHT_YES;int on=dark?Color.rgb(208,188,255):Color.rgb(103,80,164),off=dark?Color.rgb(54,51,60):Color.rgb(232,226,236),offStroke=dark?Color.rgb(105,100,112):Color.rgb(121,116,126),thumbOff=dark?Color.rgb(202,196,208):Color.rgb(121,116,126),thumbOn=dark?Color.rgb(39,35,48):Color.WHITE;float p=isEnabled()?progress:progress*.45f,alpha=isEnabled()?1f:.48f;int w=getWidth(),h=getHeight();float trackH=dp(28),trackW=Math.min(w-dp(2),dp(52)),left=(w-trackW)/2f,top=(h-trackH)/2f;rect.set(left,top,left+trackW,top+trackH);paint.setStyle(Paint.Style.FILL);paint.setColor(mix(off,on,p));paint.setAlpha((int)(255*alpha));canvas.drawRoundRect(rect,trackH/2f,trackH/2f,paint);paint.setAlpha((int)(255*alpha));paint.setStyle(Paint.Style.STROKE);paint.setStrokeWidth(dp(1));paint.setColor(mix(offStroke,on,p));canvas.drawRoundRect(rect,trackH/2f,trackH/2f,paint);paint.setStyle(Paint.Style.FILL);float r=dp(10)+dp(1.5f)*p,cx=left+dp(14)+p*(trackW-dp(28)),cy=h/2f;paint.setShadowLayer(dp(1.5f),0,dp(.8f),Color.argb(90,0,0,0));setLayerType(LAYER_TYPE_SOFTWARE,paint);paint.setColor(mix(thumbOff,thumbOn,p));canvas.drawCircle(cx,cy,r,paint);paint.clearShadowLayer();}
  private int mix(int a,int b,float t){t=Math.max(0f,Math.min(1f,t));return Color.argb((int)(Color.alpha(a)+(Color.alpha(b)-Color.alpha(a))*t),(int)(Color.red(a)+(Color.red(b)-Color.red(a))*t),(int)(Color.green(a)+(Color.green(b)-Color.green(a))*t),(int)(Color.blue(a)+(Color.blue(b)-Color.blue(a))*t));}
  private int dp(float v){return(int)(v*getResources().getDisplayMetrics().density+.5f);} }
