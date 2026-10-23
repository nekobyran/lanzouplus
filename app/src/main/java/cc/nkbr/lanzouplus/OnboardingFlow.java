package cc.nkbr.lanzouplus;

import android.Manifest;
import android.app.Activity;
import android.content.Intent;
import android.content.Context;
import android.content.res.ColorStateList;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.os.PowerManager;
import android.provider.Settings;
import android.text.Editable;
import android.text.InputType;
import android.text.TextWatcher;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowInsets;
import android.widget.Button;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.RadioButton;
import android.widget.RadioGroup;
import android.widget.ScrollView;
import android.widget.TextView;

import java.util.concurrent.atomic.AtomicInteger;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** Full-screen, resumable first-run setup. The required base origin is verified by LanzouCore. */
final class OnboardingFlow {
  static final int WELCOME=0, PERMISSIONS=1, BASE=2, MARKET=3, CHANNEL=4, DONE=5;
  static final String PREFS="onboarding_v187";
  static final String CHANNEL_PREFS="update_channel_v187";
  static final String CHANNEL_KEY="channel";
  static final String DEBUG_PREVIEW_STEP="lz_onboarding_preview_step";
  static final int STORAGE_REQUEST=70;
  private static final long STAGGER_MS=45;
  private static final String STATE_STEP="onboarding.step";
  private static final String STATE_DRAFT="onboarding.draft";

  private final MainActivity activity;
  private final SharedPreferences prefs;
  private final boolean preview;
  private final AtomicInteger requestGeneration=new AtomicInteger();
  private int step;
  private int direction=1;
  private boolean active=true;
  private boolean busy;
  private boolean changing;
  private LinearLayout shellView;
  private TextView welcomeName,welcomeIntro;
  private String baseDraft;
  private String status="";
  private TextView statusView;
  private EditText baseInput;
  private EditText githubInput;
  private Button primaryButton;
  private Button secondaryButton;
  private ProgressBar workingIndicator;

  private OnboardingFlow(MainActivity activity,int step,String draft,boolean preview){
    this.activity=activity;
    this.prefs=activity.getSharedPreferences(PREFS,Activity.MODE_PRIVATE);
    this.step=step;
    this.baseDraft=draft==null?"":draft;
    this.preview=preview;
  }

  static OnboardingFlow open(MainActivity activity,Bundle saved){
    SharedPreferences prefs=activity.getSharedPreferences(PREFS,Activity.MODE_PRIVATE);
    Intent intent=activity.getIntent();
    boolean preview=BuildConfig.DEBUG&&intent!=null&&intent.hasExtra(DEBUG_PREVIEW_STEP);
    boolean hasBase=activity.hasUsableLanzouBaseOriginForStartup();
    if(!preview&&prefs.getBoolean("complete",false)&&hasBase)return null;
    int step=prefs.getInt("step",-1);
    if(step<0||step>=DONE)step=prefs.getBoolean("complete",false)?BASE:WELCOME;
    if(!preview&&!hasBase&&step>BASE)step=BASE;
    if(saved!=null){
      step=saved.getInt(STATE_STEP,step);
      if(!preview&&!hasBase&&step>BASE)step=BASE;
    }
    if(preview&&(saved==null||!saved.containsKey(STATE_STEP)))step=Math.min(CHANNEL,Math.max(WELCOME,intent.getIntExtra(DEBUG_PREVIEW_STEP,WELCOME)));
    String defaultDraft=prefs.getString("base_draft","");
    if(defaultDraft.isEmpty()&&hasBase)defaultDraft=activity.normalizeLanzouBaseOrigin(activity.sessionLanzouBaseOrigin);
    String draft=saved!=null?saved.getString(STATE_DRAFT,defaultDraft):defaultDraft;
    OnboardingFlow flow=new OnboardingFlow(activity,step,draft,preview);
    flow.render();
    return flow;
  }

  static OnboardingFlow requireBase(MainActivity activity){
    SharedPreferences prefs=activity.getSharedPreferences(PREFS,Activity.MODE_PRIVATE);
    prefs.edit().putBoolean("complete",false).putInt("step",BASE).apply();
    OnboardingFlow flow=new OnboardingFlow(activity,BASE,prefs.getString("base_draft",""),false);
    flow.render();
    return flow;
  }

  static UpdateChannel selectedChannel(Context activity){
    String value=activity.getSharedPreferences(CHANNEL_PREFS,Activity.MODE_PRIVATE)
        .getString(CHANNEL_KEY,"stable");
    return UpdateChannel.fromPreference(value);
  }

  static void saveChannel(Activity activity,UpdateChannel channel){
    activity.getSharedPreferences(CHANNEL_PREFS,Activity.MODE_PRIVATE).edit()
        .putString(CHANNEL_KEY,channel.queryValue()).apply();
  }

  static boolean canAdvanceFromBase(String entered,String verified){
    return entered!=null&&!entered.isEmpty()&&entered.equals(verified);
  }

  boolean isActive(){return active;}
  int currentStep(){return step;}

  void saveInstanceState(Bundle out){
    if(!active)return;
    if(baseInput!=null)baseDraft=baseInput.getText().toString();
    out.putInt(STATE_STEP,step);
    out.putString(STATE_DRAFT,baseDraft);
  }

  void onConfigurationChanged(){
    if(active)render();
  }

  void onResume(){
    if(active&&step==PERMISSIONS)render();
  }

  void onPermissionResult(){
    if(active&&step==PERMISSIONS)render();
  }

  void destroy(){
    active=false;
    requestGeneration.incrementAndGet();
  }

  void back(){
    if(!active)return;
    if(busy||changing)return;
    if(step==WELCOME){activity.finishAfterTransition();return;}
    moveTo(step-1);
  }

  private void moveTo(int next){
    if(baseInput!=null)saveBaseDraft(baseInput.getText().toString());
    busy=false;
    status="";
    requestGeneration.incrementAndGet();
    direction=next<step?-1:1;
    step=next;
    if(!preview)prefs.edit().putInt("step",step).apply();
    fadeTo(this::render);
  }

  private void fadeTo(Runnable show){
    LinearLayout previous=shellView;
    if(previous==null||!activity.motionEnabled()){show.run();return;}
    changing=true;
    previous.animate().cancel();
    previous.animate().alpha(0f).setDuration(110)
        .setInterpolator(new android.view.animation.AccelerateDecelerateInterpolator())
        .withEndAction(()->{if(active&&shellView==previous)show.run();}).start();
  }

  private void saveBaseDraft(String draft){
    baseDraft=draft;
    if(!preview)prefs.edit().putString("base_draft",baseDraft).apply();
  }

  private int dp(int size){return activity.dp(size);}
  private boolean landscape(){return activity.getResources().getConfiguration().orientation==android.content.res.Configuration.ORIENTATION_LANDSCAPE;}

  private TextView label(int stringId,int size,int color){
    TextView label=activity.text(activity.getString(stringId),size,color);
    label.setIncludeFontPadding(true);
    return label;
  }

  private LinearLayout vertical(){
    LinearLayout view=new LinearLayout(activity);
    view.setOrientation(LinearLayout.VERTICAL);
    return view;
  }

  private void addSpace(LinearLayout parent,int height){
    parent.addView(new View(activity),new LinearLayout.LayoutParams(1,dp(height)));
  }

  private Button button(int title,int fill,int textColor,boolean outline,View.OnClickListener click){
    Button button=new Button(activity);
    button.setText(title);
    button.setTextSize(15);
    button.setAllCaps(false);
    button.setGravity(Gravity.CENTER);
    button.setMinHeight(dp(52));
    button.setMinimumHeight(dp(52));
    button.setTextColor(textColor);
    button.setElevation(0f);
    button.setStateListAnimator(null);
    GradientDrawable surface=outline?activity.shape(fill,26):activity.solidShape(fill,26);
    button.setBackground(activity.filterRipple(surface));
    button.setOnClickListener(click);
    return button;
  }

  private TextView heading(LinearLayout parent,int id){
    TextView heading=label(id,25,activity.TEXT);
    heading.setTypeface(android.graphics.Typeface.DEFAULT,android.graphics.Typeface.BOLD);
    parent.addView(heading,new LinearLayout.LayoutParams(-1,-2));
    return heading;
  }

  private void explanation(LinearLayout parent,int id){
    TextView paragraph=label(id,15,activity.MUTED);
    paragraph.setLineSpacing(dp(4),1f);
    parent.addView(paragraph,new LinearLayout.LayoutParams(-1,-2));
  }

  void render(){
    if(!active||activity.isFinishing()||activity.isDestroyed())return;
    if(shellView!=null)shellView.animate().cancel();
    welcomeName=welcomeIntro=null;
    statusView=null;
    baseInput=null;
    primaryButton=null;
    secondaryButton=null;
    workingIndicator=null;

    FrameLayout screen=new FrameLayout(activity);
    screen.setId(R.id.onboarding_page);
    screen.setTag("onboarding:"+step);
    screen.setBackgroundColor(activity.BG);
    LinearLayout shell=vertical();
    shellView=shell;
    shell.setPadding(dp(20),0,dp(20),dp(16));
    screen.addView(shell,new FrameLayout.LayoutParams(-1,-1));
    screen.setOnApplyWindowInsetsListener((view,insets)->{
      int l=0,t=0,r=0,b=0;
      if(Build.VERSION.SDK_INT>=30){
        android.graphics.Insets bars=insets.getInsets(
            WindowInsets.Type.systemBars()|WindowInsets.Type.displayCutout()|WindowInsets.Type.ime());
        l=bars.left;t=bars.top;r=bars.right;b=bars.bottom;
      }else{
        l=insets.getSystemWindowInsetLeft();t=insets.getSystemWindowInsetTop();
        r=insets.getSystemWindowInsetRight();b=insets.getSystemWindowInsetBottom();
      }
      shell.setPadding(dp(20)+l,t+dp(12),dp(20)+r,b+dp(16));
      return insets;
    });
    activity.setContentView(screen);
    activity.applySystemColors();
    if(Build.VERSION.SDK_INT>=30){
      android.view.WindowInsetsController bars=activity.getWindow().getInsetsController();
      int light=android.view.WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS
          |android.view.WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS;
      bars.setSystemBarsAppearance(activity.darkMode?0:light,light);
    }
    screen.requestApplyInsets();

    TextView progressLabel=activity.text((step+1)+" / "+DONE,12,activity.PRIMARY);
    progressLabel.setGravity(Gravity.END|Gravity.CENTER_VERTICAL);
    shell.addView(progressLabel,new LinearLayout.LayoutParams(-1,dp(32)));
    ProgressBar progress=new ProgressBar(activity,null,android.R.attr.progressBarStyleHorizontal);
    progress.setMax(DONE);
    progress.setProgress(step+1);
    shell.addView(progress,new LinearLayout.LayoutParams(-1,dp(3)));
    addSpace(shell,8);

    ScrollView scroll=new ScrollView(activity);
    scroll.setFillViewport(true);
    scroll.setClipToPadding(false);
    LinearLayout.LayoutParams scrollParams=new LinearLayout.LayoutParams(-1,0,1);
    shell.addView(scroll,scrollParams);
    FrameLayout centered=new FrameLayout(activity);
    scroll.addView(centered,new ScrollView.LayoutParams(-1,-2));
    LinearLayout body=vertical();
    int available=Math.max(dp(1),activity.getResources().getDisplayMetrics().widthPixels-dp(40));
    int width=Math.min(available,dp(600));
    FrameLayout.LayoutParams centeredParams=new FrameLayout.LayoutParams(width,-2,Gravity.TOP|Gravity.CENTER_HORIZONTAL);
    centered.addView(body,centeredParams);
    addSpace(body,landscape()?4:22);

    ImageView icon=new ImageView(activity);
    icon.setImageResource(step==WELCOME?R.mipmap.app_icon:
        step==PERMISSIONS?R.drawable.ic_folder:
        step==BASE?R.drawable.ic_globe:
        step==MARKET?R.drawable.ic_sources:R.drawable.ic_refresh);
    icon.setScaleType(ImageView.ScaleType.FIT_CENTER);
    icon.setContentDescription(activity.getString(R.string.onboarding_illustration));
    if(step!=WELCOME)icon.setColorFilter(activity.PRIMARY);
    if(landscape()&&step!=WELCOME)icon.setVisibility(View.GONE);
    body.addView(icon,new LinearLayout.LayoutParams(dp(68),dp(68)));
    addSpace(body,16);

    switch(step){
      case WELCOME: renderWelcome(body);break;
      case PERMISSIONS: renderPermissions(body);break;
      case BASE: renderBase(body);break;
      case MARKET: renderMarket(body);break;
      case CHANNEL: renderChannel(body);break;
      default:break;
    }
    addSpace(body,16);

    statusView=activity.text(status,13,activity.MUTED);
    statusView.setId(R.id.onboarding_status);
    statusView.setLineSpacing(dp(3),1f);
    body.addView(statusView,new LinearLayout.LayoutParams(-1,-2));

    LinearLayout footer=vertical();
    footer.setPadding(0,dp(8),0,0);
    shell.addView(footer,new LinearLayout.LayoutParams(-1,-2));
    workingIndicator=new ProgressBar(activity,null,android.R.attr.progressBarStyleHorizontal);
    workingIndicator.setIndeterminate(true);
    workingIndicator.setVisibility(busy?View.VISIBLE:View.GONE);
    footer.addView(workingIndicator,new LinearLayout.LayoutParams(-1,dp(3)));
    addSpace(footer,10);
    int action=step==WELCOME?R.string.onboarding_start:
        step==PERMISSIONS?R.string.onboarding_permissions_continue:
        step==BASE?R.string.onboarding_verify_base:
        step==MARKET?R.string.onboarding_add_github:
        R.string.onboarding_finish;
    primaryButton=button(action,activity.PRIMARY,activity.darkMode?Color.rgb(56,30,114):Color.WHITE,false,v->primaryAction());
    primaryButton.setId(R.id.onboarding_primary);
    primaryButton.setEnabled(!busy);
    footer.addView(primaryButton,new LinearLayout.LayoutParams(-1,dp(52)));
    if(step==PERMISSIONS||step==MARKET||step==BASE){
      int secondary=step==PERMISSIONS?R.string.onboarding_skip_permissions:
          step==MARKET?R.string.onboarding_skip_market:R.string.onboarding_random_base;
      secondaryButton=button(secondary,activity.SURFACE,activity.TEXT,true,v->secondaryAction());
      secondaryButton.setId(R.id.onboarding_secondary);
      secondaryButton.setEnabled(!busy);
      footer.addView(secondaryButton,new LinearLayout.LayoutParams(-1,dp(52)));
    }
    changing=false;
    long delay=0;
    for(View target:orderedMotionTargets(shell,direction)){reveal(target,delay);delay+=STAGGER_MS;}
  }

  /**
   * Every visible content node of the step in document order, reversed when the step moves backwards.
   * Nested rows such as the channel radios are included element by element.
   */
  static List<View> orderedMotionTargets(View root,int direction){
    List<View> targets=new ArrayList<>();
    collectMotionTargets(root,targets);
    if(direction<0)Collections.reverse(targets);
    return targets;
  }

  private static void collectMotionTargets(View view,List<View> targets){
    if(view==null||view.getVisibility()!=View.VISIBLE)return;
    if(view instanceof ViewGroup){
      ViewGroup group=(ViewGroup)view;
      for(int i=0;i<group.getChildCount();i++)collectMotionTargets(group.getChildAt(i),targets);
      if(group.getChildCount()>0)return;
    }
    if(view instanceof TextView||view instanceof ImageView||view instanceof ProgressBar||view.getBackground()!=null){
      targets.add(view);
    }
  }

  private void reveal(View view,long delay){
    view.animate().cancel();
    if(!activity.motionEnabled()){view.setAlpha(1f);view.setTranslationX(0f);return;}
    view.setAlpha(0f);
    view.setTranslationX(0f);
    view.animate().alpha(1f).setStartDelay(delay).setDuration(230)
        .setInterpolator(new android.view.animation.DecelerateInterpolator()).start();
  }

  private void renderWelcome(LinearLayout body){
    welcomeName=heading(body,R.string.onboarding_welcome_plus);
    addSpace(body,12);
    welcomeIntro=label(R.string.onboarding_intro,15,activity.MUTED);
    welcomeIntro.setLineSpacing(dp(4),1f);
    body.addView(welcomeIntro,new LinearLayout.LayoutParams(-1,-2));
    addSpace(body,16);
    TextView version=activity.text("v"+BuildConfig.VERSION_NAME,13,activity.PRIMARY);
    body.addView(version,new LinearLayout.LayoutParams(-1,-2));
  }

  private void renderPermissions(LinearLayout body){
    heading(body,R.string.onboarding_permissions_title);
    addSpace(body,10);
    explanation(body,R.string.onboarding_permissions_body);
    addSpace(body,22);
    Button storage=button(R.string.onboarding_request_storage,activity.SURFACE,activity.TEXT,true,v->requestStoragePermission(activity));
    storage.setId(R.id.onboarding_storage);
    storage.setText(activity.getString(R.string.onboarding_request_storage)+
        " · "+activity.getString(storageGranted()?R.string.onboarding_granted:R.string.onboarding_not_granted));
    body.addView(storage,new LinearLayout.LayoutParams(-1,dp(58)));
    addSpace(body,12);
    Button battery=button(R.string.onboarding_request_battery,activity.SURFACE,activity.TEXT,true,v->requestBatteryPermission(activity));
    battery.setId(R.id.onboarding_battery);
    battery.setText(activity.getString(R.string.onboarding_request_battery)+
        " · "+activity.getString(batteryGranted()?R.string.onboarding_granted:R.string.onboarding_not_granted));
    body.addView(battery,new LinearLayout.LayoutParams(-1,dp(58)));
    addSpace(body,16);
    explanation(body,R.string.onboarding_permissions_optional);
  }

  private void renderBase(LinearLayout body){
    heading(body,R.string.onboarding_base_title);
    addSpace(body,10);
    explanation(body,R.string.onboarding_base_description);
    addSpace(body,22);
    baseInput=new EditText(activity);
    baseInput.setId(R.id.onboarding_base_input);
    baseInput.setSingleLine(true);
    baseInput.setTextColor(activity.TEXT);
    baseInput.setHintTextColor(activity.MUTED);
    baseInput.setBackgroundTintList(ColorStateList.valueOf(activity.PRIMARY));
    baseInput.setInputType(InputType.TYPE_CLASS_TEXT|InputType.TYPE_TEXT_VARIATION_URI);
    baseInput.setHint(R.string.onboarding_base_hint);
    baseInput.setText(baseDraft);
    baseInput.setSelectAllOnFocus(false);
    baseInput.setSelection(baseInput.length());
    baseInput.addTextChangedListener(new TextWatcher(){
      public void beforeTextChanged(CharSequence s,int start,int count,int after){}
      public void onTextChanged(CharSequence s,int start,int before,int count){
        saveBaseDraft(s.toString());
        if(statusView!=null)statusView.setText("");
      }
      public void afterTextChanged(Editable s){}
    });
    body.addView(baseInput,new LinearLayout.LayoutParams(-1,dp(60)));
    addSpace(body,14);
    String verified=activity.normalizeLanzouBaseOrigin(activity.sessionLanzouBaseOrigin);
    if(activity.hasUsableLanzouBaseOriginForStartup()){
      TextView current=activity.text(
          activity.getString(R.string.onboarding_current_base,verified),13,activity.PRIMARY);
      body.addView(current,new LinearLayout.LayoutParams(-1,-2));
    }
    addSpace(body,16);
    explanation(body,R.string.onboarding_base_required);
  }

  private void renderMarket(LinearLayout body){
    heading(body,R.string.onboarding_market_title);
    addSpace(body,10);
    explanation(body,R.string.onboarding_market_body);
    addSpace(body,12);
    githubInput=new EditText(activity);githubInput.setSingleLine(true);githubInput.setTextColor(activity.TEXT);githubInput.setHintTextColor(activity.MUTED);githubInput.setText("https://github.com/nekobyran/lzpmarket-lanzoumax");githubInput.setHint("GitHub 标准仓库链接");githubInput.setContentDescription("GitHub 仓库源链接");body.addView(githubInput,new LinearLayout.LayoutParams(-1,dp(56)));
    Button open=button(R.string.onboarding_visit_github,activity.SURFACE,activity.TEXT,true,
        v->activity.openInBrowser(MainActivity.LANZOU_PLUS_REPOSITORY,""));
    body.addView(open,new LinearLayout.LayoutParams(-1,dp(52)));
    addSpace(body,16);
    explanation(body,R.string.onboarding_market_future);
  }

  private void renderChannel(LinearLayout body){
    heading(body,R.string.onboarding_channel_title);
    addSpace(body,10);
    explanation(body,R.string.onboarding_channel_body);
    addSpace(body,20);
    UpdateChannel selected=selectedChannel(activity);
    RadioGroup group=new RadioGroup(activity);
    group.setOrientation(landscape()?RadioGroup.HORIZONTAL:RadioGroup.VERTICAL);
    ColorStateList choiceTint=new ColorStateList(new int[][]{new int[]{android.R.attr.state_checked},new int[]{}},new int[]{activity.PRIMARY,activity.MUTED});
    RadioButton stable=new RadioButton(activity);
    stable.setId(R.id.onboarding_stable);
    stable.setText(R.string.onboarding_channel_stable);
    stable.setTextColor(activity.TEXT);
    stable.setButtonTintList(choiceTint);
    stable.setPadding(dp(8),dp(6),dp(8),dp(6));
    stable.setMinHeight(dp(64));
    group.addView(stable,landscape()?new RadioGroup.LayoutParams(0,-2,1):new RadioGroup.LayoutParams(-1,-2));
    RadioButton test=new RadioButton(activity);
    test.setId(R.id.onboarding_test);
    test.setText(R.string.onboarding_channel_test);
    test.setTextColor(activity.TEXT);
    test.setButtonTintList(choiceTint);
    test.setPadding(dp(8),dp(6),dp(8),dp(6));
    test.setMinHeight(dp(64));
    group.addView(test,landscape()?new RadioGroup.LayoutParams(0,-2,1):new RadioGroup.LayoutParams(-1,-2));
    group.check(selected==UpdateChannel.TEST?test.getId():stable.getId());
    group.setOnCheckedChangeListener((g,checkedId)->
        saveChannel(activity,checkedId==test.getId()?UpdateChannel.TEST:UpdateChannel.STABLE));
    body.addView(group,new LinearLayout.LayoutParams(-1,-2));
    addSpace(body,16);
    explanation(body,R.string.onboarding_channel_note);
  }

  private void primaryAction(){
    if(busy||changing)return;
    switch(step){
      case WELCOME:moveTo(PERMISSIONS);break;
      case PERMISSIONS:moveTo(BASE);break;
      case BASE:verifyBase(false);break;
      case MARKET:addGithubSource();break;
      case CHANNEL:complete();break;
      default:break;
    }
  }

  private void secondaryAction(){
    if(busy||changing)return;
    switch(step){
      case PERMISSIONS:moveTo(BASE);break;
      case BASE:verifyBase(true);break;
      case MARKET:moveTo(CHANNEL);break;
      default:break;
    }
  }

  private void setStatus(String value){
    status=value;
    if(statusView!=null)statusView.setText(value);
  }

  private void startBusy(int message){
    busy=true;
    setStatus(activity.getString(message));
    if(primaryButton!=null)primaryButton.setEnabled(false);
    if(secondaryButton!=null)secondaryButton.setEnabled(false);
    if(workingIndicator!=null)workingIndicator.setVisibility(View.VISIBLE);
  }

  private void endBusy(){
    busy=false;
    if(primaryButton!=null)primaryButton.setEnabled(true);
    if(secondaryButton!=null)secondaryButton.setEnabled(true);
    if(workingIndicator!=null)workingIndicator.setVisibility(View.GONE);
  }

  private void verifyBase(boolean random){
    if(busy)return;
    if(baseInput!=null)saveBaseDraft(baseInput.getText().toString());
    String normalized=activity.normalizeLanzouBaseOrigin(baseDraft);
    if(!random&&normalized.isEmpty()){
      if(baseInput!=null)baseInput.setError(activity.getString(R.string.onboarding_base_invalid));
      setStatus(activity.getString(R.string.onboarding_base_invalid));
      return;
    }
    String saved=activity.normalizeLanzouBaseOrigin(activity.sessionLanzouBaseOrigin);
    if(!random&&activity.hasUsableLanzouBaseOriginForStartup()&&canAdvanceFromBase(normalized,saved)){
      moveTo(MARKET);
      return;
    }
    int generation=requestGeneration.incrementAndGet();
    startBusy(random?R.string.onboarding_random_progress:R.string.onboarding_verify_progress);
    MainActivity.BaseOriginReference ref=activity.baseOriginProbeReference();
    activity.io.execute(()->{
      try{
        LanzouCore.BaseOriginProbe result=random?
            activity.core.findUsableBaseOriginRandomized(ref.url,ref.password,null):
            activity.core.probeBaseOrigin(normalized,ref.url,ref.password);
        activity.runOnUiThread(()->{
          if(!active||step!=BASE||generation!=requestGeneration.get())return;
          endBusy();
          if(activity.usableBaseOriginProbe(result)){
            String origin=activity.normalizeLanzouBaseOrigin(result.origin);
            activity.applyVerifiedBaseOrigin(origin);
            saveBaseDraft(origin);
            moveTo(MARKET);
            return;
          }
          if(result!=null&&result.challenge){
            String candidate=activity.normalizeLanzouBaseOrigin(result.origin);
            saveBaseDraft(candidate);
            render();
            String challenge=activity.lanzouUrlAtOrigin(ref.url,candidate);
            activity.launchLanzouVerification(challenge,()->verifyBase(false));
            return;
          }
          setStatus(activity.getString(R.string.onboarding_base_unavailable));
          if(baseInput!=null)baseInput.setError(activity.getString(R.string.onboarding_base_unavailable));
        });
      }catch(InterruptedException interrupted){
        Thread.currentThread().interrupt();
        activity.runOnUiThread(()->{if(active&&generation==requestGeneration.get())endBusy();});
      }catch(Exception error){
        activity.runOnUiThread(()->{
          if(!active||generation!=requestGeneration.get())return;
          endBusy();
          setStatus(activity.getString(R.string.onboarding_base_failed,activity.friendlyError(error)));
        });
      }
    });
  }

  private void addGithubSource(){
    if(busy)return;
    String github=githubInput.getText().toString().trim();
    int generation=requestGeneration.incrementAndGet();
    startBusy(R.string.onboarding_adding_github);
    activity.io.execute(()->{
      try{
        activity.core.addUserSource(github,"");
        activity.runOnUiThread(()->{
          if(!active||step!=MARKET||generation!=requestGeneration.get())return;
          endBusy();
          moveTo(CHANNEL);
        });
      }catch(Exception error){
        activity.runOnUiThread(()->{
          if(!active||step!=MARKET||generation!=requestGeneration.get())return;
          endBusy();
          setStatus(activity.getString(R.string.onboarding_github_failed,activity.friendlyError(error)));
        });
      }
    });
  }

  private void complete(){
    if(!activity.hasUsableLanzouBaseOriginForStartup()){
      moveTo(BASE);
      setStatus(activity.getString(R.string.onboarding_base_required));
      return;
    }
    if(!preview){
      saveChannel(activity,selectedChannel(activity));
      prefs.edit().putBoolean("complete",true).putInt("step",DONE).remove("base_draft").apply();
      activity.getPreferences(0).edit().remove("accepted").apply();
    }
    active=false;
    requestGeneration.incrementAndGet();
    activity.onboarding=null;
    activity.safeStartMainExperience();
    Intent original=activity.getIntent();
    activity.pendingOnboardingAction=()->activity.handleExternalAction(original);
    HomeOnboardingReveal.play(activity,!preview,activity::completeOnboardingAction);
  }

  private boolean storageGranted(){
    return activity.storageAccessGranted();
  }

  private boolean batteryGranted(){
    if(Build.VERSION.SDK_INT<23)return true;
    PowerManager manager=(PowerManager)activity.getSystemService(Activity.POWER_SERVICE);
    return manager!=null&&manager.isIgnoringBatteryOptimizations(activity.getPackageName());
  }

  static void requestStoragePermission(MainActivity activity){
    if(activity.storageAccessGranted())return;
    if(Build.VERSION.SDK_INT<30){
      activity.requestPermissions(
          new String[]{Manifest.permission.READ_EXTERNAL_STORAGE,Manifest.permission.WRITE_EXTERNAL_STORAGE},
          STORAGE_REQUEST);
      return;
    }
    Uri uri=Uri.parse("package:"+activity.getPackageName());
    try{
      activity.startActivity(new Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION,uri));
    }catch(Exception first){
      try{
        activity.startActivity(new Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION));
      }catch(Exception second){
        activity.safeStartupNotice(activity.getString(R.string.onboarding_cannot_open_settings));
      }
    }
  }

  static void requestBatteryPermission(MainActivity activity){
    if(Build.VERSION.SDK_INT<23)return;
    try{
      activity.startActivity(new Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
          Uri.parse("package:"+activity.getPackageName())));
    }catch(Exception error){
      activity.safeStartupNotice(activity.getString(R.string.onboarding_cannot_open_settings));
    }
  }
}
