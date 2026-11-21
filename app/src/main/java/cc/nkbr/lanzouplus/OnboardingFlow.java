package cc.nkbr.lanzouplus;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
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

/** Full-screen, resumable first-run setup. The required origin and UA connection is verified by LanzouCore. */
final class OnboardingFlow {
  static final int WELCOME=0, PERMISSIONS=1, BASE=2, MARKET=3, CHANNEL=4, DONE=5;
  static final String PREFS="onboarding_v187";
  static final String CHANNEL_PREFS="update_channel_v187";
  static final String CHANNEL_KEY="channel";
  static final String DEBUG_PREVIEW_STEP="lz_onboarding_preview_step";
  static final int STORAGE_REQUEST=70;
  private static final long STAGGER_MS=45;
  private static final int[] UA_PRESETS={
      LanzouCore.UA_PRESET_MOBILE_CHROME,
      LanzouCore.UA_PRESET_MOBILE_HUAWEI,
      LanzouCore.UA_PRESET_DESKTOP_CHROME,
      LanzouCore.UA_PRESET_DESKTOP_EDGE,
      LanzouCore.UA_PRESET_MOBILE_FIREFOX
  };
  private static final String STATE_STEP="onboarding.step";
  private static final String STATE_DRAFT="onboarding.draft";
  private static final String STATE_UA_PRESET="onboarding.ua_preset";
  private static final String STATE_UA_CUSTOM="onboarding.ua_custom";
  private static final String STATE_UA_USE_CUSTOM="onboarding.ua_use_custom";

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
  private int uaPreset;
  private String customUa;
  private boolean useCustomUa;
  private String status="";
  private TextView statusView;
  private EditText baseInput;
  private TextView uaChoice;
  private TextView connectionSummaryView;
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
    this.uaPreset=LanzouCore.normalizeUserAgentPreset(activity.sessionUaFileListPreset);
    this.customUa=LanzouCore.normalizeCustomUserAgent(activity.sessionCustomUserAgent);
    this.useCustomUa=!customUa.isEmpty();
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
    if(saved!=null){
      flow.uaPreset=LanzouCore.normalizeUserAgentPreset(saved.getInt(STATE_UA_PRESET,flow.uaPreset));
      flow.customUa=LanzouCore.normalizeCustomUserAgent(saved.getString(STATE_UA_CUSTOM,flow.customUa));
      flow.useCustomUa=saved.getBoolean(STATE_UA_USE_CUSTOM,flow.useCustomUa)&&!flow.customUa.isEmpty();
    }
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
    out.putInt(STATE_UA_PRESET,uaPreset);
    out.putString(STATE_UA_CUSTOM,customUa);
    out.putBoolean(STATE_UA_USE_CUSTOM,useCustomUa);
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
    uaChoice=null;
    connectionSummaryView=null;
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
        refreshConnectionSummary();
      }
      public void afterTextChanged(Editable s){}
    });
    body.addView(baseInput,new LinearLayout.LayoutParams(-1,dp(60)));
    addSpace(body,14);
    LinearLayout uaRow=new LinearLayout(activity);
    uaRow.setGravity(Gravity.CENTER_VERTICAL);
    TextView uaTitle=label(R.string.onboarding_ua_label,15,activity.TEXT);
    uaTitle.setGravity(Gravity.CENTER_VERTICAL);
    uaRow.addView(uaTitle,new LinearLayout.LayoutParams(dp(88),dp(52)));
    uaChoice=activity.uaDropdownView(selectedUaLabel());
    uaChoice.setContentDescription(activity.getString(R.string.onboarding_ua_accessibility,selectedUaLabel()));
    uaChoice.setOnClickListener(v->showUaPicker());
    uaRow.addView(uaChoice,new LinearLayout.LayoutParams(0,dp(52),1));
    body.addView(uaRow,new LinearLayout.LayoutParams(-1,dp(60)));
    addSpace(body,8);
    connectionSummaryView=activity.text(connectionSummary(),13,activity.PRIMARY);
    connectionSummaryView.setLineSpacing(dp(3),1f);
    body.addView(connectionSummaryView,new LinearLayout.LayoutParams(-1,-2));
    addSpace(body,14);
    explanation(body,R.string.onboarding_base_required);
  }

  private String selectedUaLabel(){
    return uaLabel(uaPreset,useCustomUa?customUa:"");
  }

  private String uaLabel(int preset,String custom){
    return custom==null||custom.isEmpty()
        ?LanzouCore.uaPresetLabel(preset)
        :activity.getString(R.string.onboarding_ua_custom);
  }

  private String connectionSummary(){
    String draft=baseInput==null
        ?activity.normalizeLanzouBaseOrigin(baseDraft)
        :activity.normalizeLanzouBaseOrigin(baseInput.getText().toString());
    String selectedCustom=useCustomUa?customUa:"";
    if(connectionMatches(draft,uaPreset,selectedCustom)){
      String verified=activity.normalizeLanzouBaseOrigin(activity.sessionLanzouBaseOrigin);
      String currentUa=activity.sessionCustomUserAgent.isEmpty()
          ?LanzouCore.uaPresetLabel(activity.sessionUaFileListPreset)
          :activity.getString(R.string.onboarding_ua_custom);
      return activity.getString(R.string.onboarding_current_connection,verified,currentUa);
    }
    return activity.getString(R.string.onboarding_pending_connection,
        draft.isEmpty()?activity.getString(R.string.onboarding_pending_origin):draft,
        selectedUaLabel());
  }

  private void refreshConnectionSummary(){
    if(connectionSummaryView!=null)connectionSummaryView.setText(connectionSummary());
  }

  private void showUaPicker(){
    String[] labels=new String[UA_PRESETS.length+(customUa.isEmpty()?0:1)];
    for(int i=0;i<UA_PRESETS.length;i++)labels[i]=LanzouCore.uaPresetLabel(UA_PRESETS[i]);
    if(!customUa.isEmpty())labels[labels.length-1]=activity.getString(R.string.onboarding_ua_custom);
    AlertDialog prompt=new AlertDialog.Builder(activity)
        .setTitle(R.string.onboarding_ua_title)
        .setItems(labels,(dialog,which)->{
          if(which<UA_PRESETS.length){
            uaPreset=LanzouCore.normalizeUserAgentPreset(UA_PRESETS[which]);
            useCustomUa=false;
          }else{
            useCustomUa=true;
          }
          if(uaChoice!=null){
            uaChoice.setText(selectedUaLabel());
            uaChoice.setContentDescription(activity.getString(
                R.string.onboarding_ua_accessibility,selectedUaLabel()));
          }
          refreshConnectionSummary();
          if(statusView!=null)statusView.setText("");
        }).create();
    activity.showRounded(prompt);
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
    verifyBase(random,false);
  }

  private void verifyBase(boolean random,boolean automaticRandom){
    if(busy)return;
    if(baseInput!=null)saveBaseDraft(baseInput.getText().toString());
    String normalized=activity.normalizeLanzouBaseOrigin(baseDraft);
    if(!random&&normalized.isEmpty()){
      if(baseInput!=null)baseInput.setError(activity.getString(R.string.onboarding_base_invalid));
      setStatus(activity.getString(R.string.onboarding_base_invalid));
      return;
    }
    int probePreset=LanzouCore.normalizeUserAgentPreset(uaPreset);
    String probeCustom=useCustomUa?customUa:"";
    if(!random&&connectionMatches(normalized,probePreset,probeCustom)){
      moveTo(MARKET);
      return;
    }
    if(baseInput!=null)baseInput.setError(null);
    int generation=requestGeneration.incrementAndGet();
    startBusy(random?R.string.onboarding_random_progress:R.string.onboarding_verify_progress);
    MainActivity.BaseOriginReference ref=activity.baseOriginProbeReference();
    activity.io.execute(()->{
      try{
        LanzouCore.BaseOriginProbe result=random?
            activity.core.findUsableBaseOriginRandomized(ref.url,ref.password,
                (probe,done,total)->activity.runOnUiThread(()->{
                  if(!active||step!=BASE||generation!=requestGeneration.get())return;
                  setStatus(activity.getString(R.string.onboarding_random_candidate,
                      probe.origin,uaLabel(probe.uaPreset,"")));
                })):
            activity.core.probeBaseOrigin(normalized,ref.url,ref.password,probePreset,probeCustom);
        activity.runOnUiThread(()->{
          if(!active||step!=BASE||generation!=requestGeneration.get())return;
          endBusy();
          if(activity.usableBaseOriginProbe(result)){
            String origin=activity.normalizeLanzouBaseOrigin(result.origin);
            int verifiedPreset=LanzouCore.normalizeUserAgentPreset(result.uaPreset);
            String verifiedCustom=random?"":probeCustom;
            applyVerifiedConnection(origin,verifiedPreset,verifiedCustom);
            moveTo(MARKET);
            return;
          }
          if(result!=null&&result.challenge){
            String candidate=activity.normalizeLanzouBaseOrigin(result.origin);
            if(candidate.isEmpty())candidate=normalized;
            int candidatePreset=LanzouCore.normalizeUserAgentPreset(result.uaPreset);
            String candidateCustom=random?"":probeCustom;
            saveBaseDraft(candidate);
            uaPreset=candidatePreset;
            customUa=candidateCustom;
            useCustomUa=!candidateCustom.isEmpty();
            render();
            String challenge=activity.lanzouUrlAtOrigin(ref.url,candidate);
            activity.launchLanzouVerification(challenge,()->verifyBase(false,false),
                LanzouCore.connectionUserAgent(candidatePreset,candidateCustom));
            return;
          }
          setStatus(activity.getString(R.string.onboarding_base_unavailable));
          if(baseInput!=null)baseInput.setError(activity.getString(R.string.onboarding_base_unavailable));
          if(!random&&!automaticRandom&&activity.sessionLanzouTimeoutFailover){
            verifyBase(true,true);
          }
        });
      }catch(InterruptedException interrupted){
        Thread.currentThread().interrupt();
        activity.runOnUiThread(()->{if(active&&generation==requestGeneration.get())endBusy();});
      }catch(Exception error){
        activity.runOnUiThread(()->{
          if(!active||generation!=requestGeneration.get())return;
          endBusy();
          if(!random&&!automaticRandom&&activity.sessionLanzouTimeoutFailover){
            verifyBase(true,true);
            return;
          }
          setStatus(activity.getString(R.string.onboarding_base_failed,activity.friendlyError(error)));
        });
      }
    });
  }

  private boolean connectionMatches(String origin,int preset,String custom){
    if(!activity.hasUsableLanzouBaseOriginForStartup())return false;
    String saved=activity.normalizeLanzouBaseOrigin(activity.sessionLanzouBaseOrigin);
    if(!canAdvanceFromBase(origin,saved))return false;
    String savedCustom=LanzouCore.normalizeCustomUserAgent(activity.sessionCustomUserAgent);
    if(!custom.isEmpty())return custom.equals(savedCustom);
    return savedCustom.isEmpty()
        &&LanzouCore.normalizeUserAgentPreset(activity.sessionUaFileListPreset)==preset;
  }

  private void applyVerifiedConnection(String origin,int preset,String custom){
    String normalized=activity.normalizeLanzouBaseOrigin(origin);
    if(normalized.isEmpty())return;
    String normalizedCustom=LanzouCore.normalizeCustomUserAgent(custom);
    activity.applyVerifiedConnection(normalized,preset,normalizedCustom);
    uaPreset=LanzouCore.normalizeUserAgentPreset(preset);
    customUa=normalizedCustom;
    useCustomUa=!normalizedCustom.isEmpty();
    saveBaseDraft(normalized);
    String label=uaLabel(uaPreset,normalizedCustom);
    activity.safeStartupNotice(activity.getString(
        R.string.onboarding_connection_verified,normalized,label));
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
    if(!active)return;
    if(!activity.hasUsableLanzouBaseOriginForStartup()){
      moveTo(BASE);
      setStatus(activity.getString(R.string.onboarding_base_required));
      return;
    }
    active=false;
    requestGeneration.incrementAndGet();
    if(!preview){
      saveChannel(activity,selectedChannel(activity));
      prefs.edit().putBoolean("complete",true).putInt("step",DONE).remove("base_draft").apply();
      activity.getPreferences(0).edit().remove("accepted").apply();
    }
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
