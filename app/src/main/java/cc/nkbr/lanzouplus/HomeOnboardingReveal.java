package cc.nkbr.lanzouplus;

import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.animation.AnimatorSet;
import android.animation.ObjectAnimator;
import android.animation.ValueAnimator;
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
  private static final int RISE_MS=320,ALIGN_MS=240,CAPTION_MS=360,MORPH_MS=580,ASSEMBLE_MS=260;
  private static final int ICON_EXIT_DP=56,TITLE_OFFSET_DP=26,TITLE_RISE_DP=96,NAV_RISE_DP=76;
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
  private boolean beginStarted;
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
    if(owner.homeOnboardingReveal!=null)return null;
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
    if(finished||beginStarted)return;
    if(owner.homeStage!=stage||owner.homeOnboardingReveal!=this){settle(false);return;}
    if(stage.getWidth()<=0||search.getWidth()<=0||titleAnchor.getWidth()<=0){
      if(layoutAttempts++<MAX_LAYOUT_ATTEMPTS){stage.postDelayed(this::begin,48);return;}
      settle(true);
      return;
    }
    beginStarted=true;
    Rect titleRect=boundsIn(stage,titleAnchor);

    title.setTranslationX(-owner.dp(TITLE_OFFSET_DP));
    title.setTranslationY(owner.dp(TITLE_RISE_DP));
    icon.setTranslationX(-owner.dp(TITLE_OFFSET_DP));
    icon.setTranslationY(owner.dp(TITLE_RISE_DP));
    if(nav!=null)nav.setTranslationY(owner.dp(NAV_RISE_DP));

    int pillWidth=Math.max(owner.dp(180),Math.round(caption.getPaint().measureText(CAPTION_PLACEHOLDER))+owner.dp(36));
    int pillHeight=owner.dp(40);
    int startLeft=Math.round(titleRect.left+titleRect.width()/2f-pillWidth/2f);
    int startTop=titleRect.bottom+owner.dp(12);
    setCaptionBounds(startLeft,startTop,pillWidth,pillHeight);

    AnimatorSet rise=new AnimatorSet();
    rise.playTogether(ObjectAnimator.ofFloat(title,View.TRANSLATION_Y,owner.dp(TITLE_RISE_DP),0f),
        ObjectAnimator.ofFloat(title,View.ALPHA,0f,1f),
        ObjectAnimator.ofFloat(icon,View.TRANSLATION_Y,owner.dp(TITLE_RISE_DP),0f),
        ObjectAnimator.ofFloat(icon,View.ALPHA,0f,1f));
    rise.setDuration(RISE_MS);
    rise.setInterpolator(new DecelerateInterpolator());

    AnimatorSet align=new AnimatorSet();
    align.playTogether(ObjectAnimator.ofFloat(title,View.TRANSLATION_X,-owner.dp(TITLE_OFFSET_DP),0f),
        ObjectAnimator.ofFloat(icon,View.TRANSLATION_X,-owner.dp(TITLE_OFFSET_DP),-owner.dp(ICON_EXIT_DP)),
        ObjectAnimator.ofFloat(icon,View.ALPHA,1f,0f));
    align.setDuration(ALIGN_MS);
    align.setInterpolator(new DecelerateInterpolator());

    ObjectAnimator greeting=ObjectAnimator.ofFloat(pill,View.ALPHA,0f,1f);
    greeting.setDuration(CAPTION_MS);

    AnimatorSet assemble=new AnimatorSet();
    List<Animator> assembly=new ArrayList<>();
    assembly.add(ObjectAnimator.ofFloat(icon,View.TRANSLATION_X,-owner.dp(ICON_EXIT_DP),0f));
    assembly.add(ObjectAnimator.ofFloat(icon,View.ALPHA,0f,1f));
    if(category!=null)assembly.add(ObjectAnimator.ofFloat(category,View.ALPHA,0f,1f));
    if(recommendations!=null)assembly.add(ObjectAnimator.ofFloat(recommendations,View.ALPHA,0f,1f));
    if(nav!=null)assembly.add(ObjectAnimator.ofFloat(nav,View.TRANSLATION_Y,owner.dp(NAV_RISE_DP),0f));
    if(nav!=null)assembly.add(ObjectAnimator.ofFloat(nav,View.ALPHA,0f,1f));
    assemble.playTogether(assembly);
    assemble.setDuration(ASSEMBLE_MS);
    assemble.setInterpolator(new DecelerateInterpolator());

    timeline.playSequentially(phase(rise,"title-rise"),phase(align,"title-align-icon-exit"),
        phase(greeting,"exploration-caption"),
        phase(morph(startLeft,startTop,pillWidth,pillHeight),"search-morph"),
        phase(assemble,"home-assemble"));
    timeline.addListener(new AnimatorListenerAdapter(){
      @Override public void onAnimationEnd(Animator animation){settle(true);}
      @Override public void onAnimationCancel(Animator animation){settle(true);}
    });
    timeline.start();
  }

  private ValueAnimator morph(int startLeft,int startTop,int startWidth,int startHeight){
    final MorphGeometry geometry=new MorphGeometry();
    final float startRadius=owner.dp(20),endRadius=owner.dp(26),stroke=owner.dp(1);
    final int surface=owner.SURFACE;
    ValueAnimator morph=ValueAnimator.ofFloat(0f,1f);
    morph.addListener(new AnimatorListenerAdapter(){
      @Override public void onAnimationStart(Animator animation){
        if(finished)return;
        Rect searchRect=boundsIn(stage,search);
        geometry.endLeft=searchRect.left;
        geometry.endTop=searchRect.top;
        geometry.endWidth=Math.max(1,searchRect.width());
        geometry.endHeight=Math.max(1,searchRect.height());
        geometry.startScaleX=startWidth/(float)geometry.endWidth;
        geometry.startScaleY=startHeight/(float)geometry.endHeight;
        setCaptionBounds(geometry.endLeft,geometry.endTop,geometry.endWidth,geometry.endHeight);
        pill.setPivotX(0f);
        pill.setPivotY(0f);
        pill.setTranslationX(startLeft-geometry.endLeft);
        pill.setTranslationY(startTop-geometry.endTop);
        pill.setScaleX(geometry.startScaleX);
        pill.setScaleY(geometry.startScaleY);
        caption.setPivotX(geometry.endWidth/2f);
        caption.setPivotY(geometry.endHeight/2f);
        hint.setPivotX(0f);
        hint.setPivotY(0f);
        counterScale(geometry.startScaleX,geometry.startScaleY);
        geometry.ready=true;
      }
    });
    morph.addUpdateListener(frame->{
      if(finished||!geometry.ready)return;
      float value=((Float)frame.getAnimatedValue()).floatValue();
      float scaleX=geometry.startScaleX+(1f-geometry.startScaleX)*value;
      float scaleY=geometry.startScaleY+(1f-geometry.startScaleY)*value;
      pill.setTranslationX((startLeft-geometry.endLeft)*(1f-value));
      pill.setTranslationY((startTop-geometry.endTop)*(1f-value));
      pill.setScaleX(scaleX);
      pill.setScaleY(scaleY);
      counterScale(scaleX,scaleY);
      float radius=startRadius+(endRadius-startRadius)*value;
      captionShape.setCornerRadius(radius/(float)Math.sqrt(scaleX*scaleY));
      captionShape.setColor(Color.argb(Math.round(255*value),Color.red(surface),Color.green(surface),Color.blue(surface)));
      captionShape.setStroke(Math.round(stroke*value),owner.DIV);
      caption.setAlpha(Math.max(0f,1f-value/.4f));
      hint.setAlpha(Math.min(1f,Math.max(0f,(value-.4f)/.6f)));
    });
    morph.addListener(new AnimatorListenerAdapter(){
      @Override public void onAnimationEnd(Animator animation){
        if(!finished&&geometry.ready){
          pill.setTranslationX(0f);
          pill.setTranslationY(0f);
          pill.setScaleX(1f);
          pill.setScaleY(1f);
          counterScale(1f,1f);
          search.setAlpha(1f);
          pill.setAlpha(0f);
        }
      }
    });
    morph.setDuration(MORPH_MS);
    morph.setInterpolator(new DecelerateInterpolator());
    return morph;
  }

  private static final class MorphGeometry {
    int endLeft,endTop,endWidth,endHeight;
    float startScaleX=1f,startScaleY=1f;
    boolean ready;
  }

  /**
   * The pill is laid out at the final search size and scaled down to the greeting size, so both texts
   * receive the inverse scale: glyphs keep their size and centre while only the capsule grows.
   */
  private void counterScale(float scaleX,float scaleY){
    caption.setScaleX(1f/scaleX);
    caption.setScaleY(1f/scaleY);
    hint.setScaleX(1f/scaleX);
    hint.setScaleY(1f/scaleY);
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
    caption.setScaleX(1f);caption.setScaleY(1f);
    hint.setScaleX(1f);hint.setScaleY(1f);
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
