package cc.nkbr.lanzouplus;

import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.animation.AnimatorSet;
import android.animation.ObjectAnimator;
import android.content.Context;
import android.graphics.Color;
import android.graphics.Rect;
import android.graphics.drawable.GradientDrawable;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.animation.DecelerateInterpolator;
import android.widget.FrameLayout;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.List;

/**
 * The single first-run handoff that assembles the interactive home on the real home geometry.
 * The one-time marker is written when the reveal starts, so leaving, rotating or a background stop mid-flight
 * never replays it, while a debug preview never burns it.
 */
final class HomeOnboardingReveal {
  static final String CONSUMED_KEY="home_reveal_v188_consumed";
  private static final String CAPTION_PLACEHOLDER="开始资源探索之旅";
  private static final String SEARCH_PLACEHOLDER="搜索一下";
  private static final int MAX_LAYOUT_ATTEMPTS=6;

  private final MainActivity owner;
  private final FrameLayout stage;
  private final ViewGroup brand;
  private final boolean brandClipsChildren,brandClipsPadding;
  private final boolean stageClipsChildren;
  private final View icon,title,titleAnchor,search,nav,recommendations,category;
  private final FrameLayout overlay,pill;
  private final TextView caption,hint;
  private final GradientDrawable captionShape=new GradientDrawable();
  private final Runnable after;
  private final AnimatorSet timeline=new AnimatorSet();
  private boolean finished;
  private int layoutAttempts;

  private HomeOnboardingReveal(MainActivity owner,Runnable after){
    this.owner=owner;this.after=after;
    stage=owner.homeStage;
    stageClipsChildren=stage.getClipChildren();
    brand=owner.homeBrand;
    brandClipsChildren=brand.getClipChildren();brandClipsPadding=brand.getClipToPadding();
    icon=brand.getChildAt(0);
    title=brand.getChildAt(1);
    titleAnchor=title instanceof ViewGroup&&((ViewGroup)title).getChildCount()>0?((ViewGroup)title).getChildAt(0):title;
    search=owner.homeSearchBox;
    nav=owner.primaryNav;
    recommendations=owner.homeRecommendationsScroll;
    category=owner.homeSearchCategory;
    overlay=new FrameLayout(owner);
    overlay.setClipChildren(false);
    overlay.setClickable(true);
    overlay.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS);
    pill=new FrameLayout(owner);
    pill.setBackground(captionShape);
    pill.setClipToOutline(true);
    pill.setAlpha(0f);
    caption=owner.text(CAPTION_PLACEHOLDER,17,owner.MUTED);
    caption.setGravity(Gravity.CENTER);
    caption.setSingleLine(true);
    caption.setIncludeFontPadding(false);
    caption.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
    hint=owner.text(SEARCH_PLACEHOLDER,16,owner.MUTED);
    hint.setGravity(Gravity.START|Gravity.CENTER_VERTICAL);
    hint.setSingleLine(true);
    hint.setIncludeFontPadding(owner.search.getIncludeFontPadding());
    hint.setTypeface(owner.search.getTypeface());
    hint.setAlpha(0f);
    pill.addView(caption,new FrameLayout.LayoutParams(-1,-1));
    pill.addView(hint,new FrameLayout.LayoutParams(-1,-1));
  }

  static boolean consumed(MainActivity owner){
    return owner.getSharedPreferences(OnboardingFlow.PREFS,Context.MODE_PRIVATE)
        .getBoolean(CONSUMED_KEY,false);
  }

  static void consume(MainActivity owner){
    owner.getSharedPreferences(OnboardingFlow.PREFS,Context.MODE_PRIVATE).edit()
        .putBoolean(CONSUMED_KEY,true).apply();
  }

  /** Home geometry in the coordinate space of {@code stage}, including the translation of every ancestor. */
  static Rect boundsIn(View stage,View view){
    Rect rect=new Rect();
    if(stage==null||view==null)return rect;
    int[] origin=new int[2],position=new int[2];
    stage.getLocationInWindow(origin);
    view.getLocationInWindow(position);
    rect.set(position[0]-origin[0],position[1]-origin[1],position[0]-origin[0]+view.getWidth(),position[1]-origin[1]+view.getHeight());
    return rect;
  }

  /** Plays the handoff at most once. {@code consume} is false for preview runs, which never burn the marker. */
  static HomeOnboardingReveal play(MainActivity owner,boolean consume,Runnable after){
    Runnable completion=after==null?()->{}:after;
    if(owner.homeStage==null||owner.homeBrand==null||owner.homeSearchBox==null||owner.homeBrand.getChildCount()<2
        ||consume&&consumed(owner)){
      completion.run();
      return null;
    }
    if(consume)consume(owner);
    if(!owner.motionEnabled()){
      completion.run();
      return null;
    }
    HomeOnboardingReveal reveal=new HomeOnboardingReveal(owner,completion);
    owner.homeOnboardingReveal=reveal;
    reveal.prepare();
    return reveal;
  }

  boolean isFinished(){return finished;}
  View captionView(){return pill;}
  boolean captionAttached(){return overlay.getParent()==stage;}

  private void prepare(){
    owner.onboardingRevealPhase="prepare";
    captionShape.setColor(Color.TRANSPARENT);
    captionShape.setCornerRadius(owner.dp(20));
    caption.setPadding(owner.dp(16),0,owner.dp(16),0);
    hint.setPadding(owner.search.getPaddingLeft(),owner.search.getPaddingTop(),owner.search.getPaddingRight(),owner.search.getPaddingBottom());
    overlay.addView(pill,new FrameLayout.LayoutParams(owner.dp(200),owner.dp(40)));
    stage.addView(overlay,new FrameLayout.LayoutParams(-1,-1));
    title.setAlpha(0f);
    icon.setAlpha(0f);
    search.setAlpha(0f);
    search.setEnabled(false);
    search.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS);
    if(category!=null)category.setAlpha(0f);
    if(recommendations!=null)recommendations.setAlpha(0f);
    if(nav!=null){nav.setAlpha(0f);nav.setEnabled(false);}
    brand.setEnabled(false);
    brand.setClipChildren(false);brand.setClipToPadding(false);
    stage.setClipChildren(false);
    stage.post(this::begin);
  }

  private void begin(){
    if(finished)return;
    if(owner.homeStage!=stage||owner.homeOnboardingReveal!=this){settle(false);return;}
    if(stage.getWidth()<=0||search.getWidth()<=0||titleAnchor.getWidth()<=0){
      if(layoutAttempts++<MAX_LAYOUT_ATTEMPTS){stage.postDelayed(this::begin,48);return;}
      settle(true);
      return;
    }
    Rect titleRect=boundsIn(stage,titleAnchor);

    int pillWidth=Math.max(owner.dp(180),Math.round(caption.getPaint().measureText(CAPTION_PLACEHOLDER))+owner.dp(36));
    setCaptionBounds(Math.round(titleRect.left+titleRect.width()/2f-pillWidth/2f),titleRect.bottom+owner.dp(12),pillWidth,owner.dp(40));
    AnimatorSet reveal=new AnimatorSet();
    reveal.playTogether(ObjectAnimator.ofFloat(title,View.ALPHA,0f,1f),ObjectAnimator.ofFloat(icon,View.ALPHA,0f,1f));
    reveal.setDuration(190);
    ObjectAnimator greeting=ObjectAnimator.ofFloat(pill,View.ALPHA,0f,1f,0f);greeting.setDuration(300);
    AnimatorSet home=new AnimatorSet();List<Animator> assembly=new ArrayList<>();
    assembly.add(ObjectAnimator.ofFloat(search,View.ALPHA,0f,1f));
    if(category!=null)assembly.add(ObjectAnimator.ofFloat(category,View.ALPHA,0f,1f));
    if(recommendations!=null)assembly.add(ObjectAnimator.ofFloat(recommendations,View.ALPHA,0f,1f));
    if(nav!=null)assembly.add(ObjectAnimator.ofFloat(nav,View.ALPHA,0f,1f));
    home.playTogether(assembly);home.setDuration(190);
    timeline.playSequentially(phase(reveal,"title-fade"),phase(greeting,"exploration-caption"),phase(home,"home-fade"));
    timeline.addListener(new AnimatorListenerAdapter(){
      @Override public void onAnimationEnd(Animator animation){settle(true);}
      @Override public void onAnimationCancel(Animator animation){settle(true);}
    });
    timeline.start();
  }

  private void setCaptionBounds(int left,int top,int width,int height){
    FrameLayout.LayoutParams params=(FrameLayout.LayoutParams)pill.getLayoutParams();
    if(params==null)params=new FrameLayout.LayoutParams(width,height);
    params.gravity=Gravity.TOP|Gravity.START;
    params.leftMargin=left;
    params.topMargin=top;
    params.width=Math.max(0,width);
    params.height=Math.max(0,height);
    pill.setLayoutParams(params);
  }

  private Animator phase(Animator target,String name){
    target.addListener(new AnimatorListenerAdapter(){
      @Override public void onAnimationStart(Animator animation){
        if(!finished)owner.onboardingRevealPhase=name;
      }
    });
    return target;
  }

  /** Restores the real, interactive home and drops the overlay. Idempotent; {@code deliver} runs the pending action. */
  void cancel(){settle(false);}

  private void settle(boolean deliver){
    if(finished)return;
    finished=true;
    timeline.cancel();
    if(overlay.getParent()==stage)stage.removeView(overlay);
    title.setAlpha(1f);
    title.setTranslationX(0f);
    title.setTranslationY(0f);
    icon.setAlpha(1f);
    icon.setTranslationX(0f);
    icon.setTranslationY(0f);
    search.setAlpha(1f);
    search.setEnabled(true);
    search.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_AUTO);
    if(category!=null)category.setAlpha(1f);
    if(recommendations!=null)recommendations.setAlpha(1f);
    if(nav!=null){nav.setTranslationY(0f);nav.setAlpha(1f);nav.setEnabled(true);}
    brand.setEnabled(true);
    brand.setClipChildren(brandClipsChildren);brand.setClipToPadding(brandClipsPadding);
    stage.setClipChildren(stageClipsChildren);
    owner.onboardingRevealPhase="complete";
    if(owner.homeOnboardingReveal==this)owner.homeOnboardingReveal=null;
    if(deliver&&!owner.isFinishing()&&!owner.isDestroyed())after.run();
  }
}
