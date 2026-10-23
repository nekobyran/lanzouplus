package cc.nkbr.lanzouplus;

import android.animation.*;
import android.view.View;

/** All page destinations share the same fade, including forward and back navigation. */
final class PageMotion {
  static ValueAnimator start(MainActivity owner,View previous,View next,int direction){
    ValueAnimator motion=ValueAnimator.ofFloat(0f,1f);
    motion.setDuration(190);motion.setInterpolator(new android.view.animation.DecelerateInterpolator());
    previous.setTranslationX(0f);next.setTranslationX(0f);next.setAlpha(0f);
    motion.addUpdateListener(value->{
      if(owner.pageFrame!=next)return;
      float opacity=(float)value.getAnimatedValue();
      previous.setAlpha(1f-opacity);next.setAlpha(opacity);
    });
    motion.addListener(new AnimatorListenerAdapter(){
      boolean cancelled;
      @Override public void onAnimationCancel(Animator value){cancelled=true;}
      @Override public void onAnimationEnd(Animator value){if(!cancelled){owner.pageAnimation=null;owner.finishPageTransition(previous,next);}}
    });
    motion.start();return motion;
  }
}
