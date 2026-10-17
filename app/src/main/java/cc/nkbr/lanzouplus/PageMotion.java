package cc.nkbr.lanzouplus;

import android.animation.*;
import android.graphics.Path;
import android.view.View;
import android.view.animation.PathInterpolator;

/** Short independent-page motion; source navigation selects the existing fade branch. */
final class PageMotion {
  static ValueAnimator start(MainActivity owner,View previous,View next,int direction){
    int width=Math.max(owner.pageHost.getWidth(),owner.getResources().getDisplayMetrics().widthPixels);
    float travel=width*.16f;
    Path path=new Path();path.moveTo(0,0);path.cubicTo(.05f,0,.133333f,.06f,.166666f,.4f);path.cubicTo(.208333f,.82f,.25f,1,1,1);
    PathInterpolator emphasized=new PathInterpolator(path);
    ValueAnimator motion=ValueAnimator.ofFloat(0,1);motion.setInterpolator(new android.view.animation.LinearInterpolator());motion.setDuration(direction<0?300:260);
    next.setAlpha(0);next.setTranslationX(direction<0?-travel:travel);
    motion.addUpdateListener(value->{
      if(owner.pageFrame!=next)return;
      float t=(float)value.getAnimatedValue();
      if(direction<0){
        previous.setTranslationX(Math.round(travel*(float)Math.sin(Math.min(1,t/.22f)*Math.PI/2)));
        previous.setAlpha(1-(float)Math.sin(Math.min(1,t/.26f)*Math.PI/2));
        next.setTranslationX(Math.round(-travel*(1-t*t*t)));
        float opacity=Math.max(0,(t-.15f)/.85f);next.setAlpha(opacity*opacity*opacity);
      }else{
        float position=emphasized.getInterpolation(t);
        next.setTranslationX(Math.round(travel*(1-position)));previous.setTranslationX(Math.round(-travel*position));
        float enter=Math.min(1,t/.85f),exit=Math.min(1,t/.58f);
        next.setAlpha(1-(1-enter)*(1-enter)*(1-enter));previous.setAlpha((1-exit)*(1-exit)*(1-exit));
      }
    });
    motion.addListener(new AnimatorListenerAdapter(){boolean cancelled;@Override public void onAnimationCancel(Animator value){cancelled=true;}@Override public void onAnimationEnd(Animator value){if(!cancelled){owner.pageAnimation=null;owner.finishPageTransition(previous,next);}}});
    motion.start();return motion;
  }
}
