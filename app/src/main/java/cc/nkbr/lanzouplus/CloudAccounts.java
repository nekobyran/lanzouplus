package cc.nkbr.lanzouplus;

import android.app.AlertDialog;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.GradientDrawable;
import android.text.InputType;
import android.view.Gravity;
import android.view.View;
import android.view.inputmethod.InputMethodManager;
import android.widget.Button;
import android.widget.EditText;
import android.widget.HorizontalScrollView;
import android.widget.LinearLayout;
import android.widget.RadioButton;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Unified account management for the ordinary and premium cloud providers.
 *
 * <p>Both providers are reached through {@link CloudFilesClient#shared}, so premium login data
 * written here is the same encrypted account vault used by the existing save and transfer flows.
 */
final class CloudAccounts {
  private static final String UI_PREFS="cloud_accounts_ui";
  private static final String UI_PROVIDER="provider";

  private final MainActivity owner;
  private final CloudFilesClient client;
  private final Map<CloudFilesClient.Provider,Map<String,Boolean>> verified=
      new EnumMap<>(CloudFilesClient.Provider.class);
  private CloudFilesClient.Provider provider;
  private LinearLayout stage;
  private LinearLayout tabs;
  private TextView verifySummary;
  private boolean busy;

  CloudAccounts(MainActivity owner){
    this.owner=owner;
    client=CloudFilesClient.shared(owner);
    provider=storedProvider(owner);
  }

  static void openLogin(MainActivity owner,CloudFilesClient.Provider provider,Runnable afterLogin){
    owner.selectedSettingsCategory="账号与权限";
    owner.showSettings();
    owner.ui.post(()->new CloudAccounts(owner).showLogin(provider,afterLogin));
  }

  LinearLayout settingsCard(){
    LinearLayout card=new LinearLayout(owner);
    card.setOrientation(LinearLayout.VERTICAL);
    card.setPadding(owner.dp(14),owner.dp(10),owner.dp(10),owner.dp(10));
    GradientDrawable background=owner.solidShape(owner.SURFACE,20);
    background.setStroke(owner.dp(1),owner.PRIMARY);
    card.setBackground(background);

    TextView title=owner.text("云盘账号",16,owner.TEXT);
    TextView hint=owner.text(
        "普通蓝奏云与优享版账号都在这里管理；登录和切换会同步到云盘管理与优享版保存。",
        11,owner.MUTED);
    hint.setMaxLines(2);
    card.addView(title,new LinearLayout.LayoutParams(-1,owner.dp(32)));
    card.addView(hint,new LinearLayout.LayoutParams(-1,owner.dp(42)));

    tabs=new LinearLayout(owner);
    tabs.setGravity(Gravity.CENTER_VERTICAL);
    renderTabs();
    HorizontalScrollView tabScroll=new HorizontalScrollView(owner);
    tabScroll.setHorizontalScrollBarEnabled(false);
    tabScroll.addView(tabs);
    card.addView(tabScroll,new LinearLayout.LayoutParams(-1,owner.dp(48)));

    stage=new LinearLayout(owner);
    stage.setOrientation(LinearLayout.VERTICAL);
    card.addView(stage,new LinearLayout.LayoutParams(-1,-2));
    renderProvider();
    return card;
  }

  private void renderTabs(){
    if(tabs==null)return;
    tabs.removeAllViews();
    for(CloudFilesClient.Provider value:CloudFilesClient.Provider.values()){
      View tab=owner.underlineFilter(providerLabel(value),value==provider,()->{
        if(busy||value==provider)return;
        provider=value;
        rememberProvider();
        renderTabs();
        renderProvider();
      });
      tabs.addView(tab,new LinearLayout.LayoutParams(-2,owner.dp(46)));
    }
  }

  private void renderProvider(){
    if(stage==null)return;
    stage.removeAllViews();
    List<String> names=new ArrayList<>();
    String current="";
    String loadError="";
    try{
      if(client.hasStoredLogin(provider)){
        names.addAll(client.accountNames(provider));
        current=client.currentAccountName(provider);
      }
    }catch(CloudFilesClient.CloudException error){
      loadError="无法读取本机账号";
    }

    TextView state=owner.text(loadError.isEmpty()
        ?(names.isEmpty()?"尚未添加账号":"已保存 "+names.size()+" 个账号")
        :loadError,12,loadError.isEmpty()?owner.MUTED:owner.PRIMARY);
    state.setMaxLines(2);
    stage.addView(state,new LinearLayout.LayoutParams(-1,owner.dp(34)));

    if(names.isEmpty()){
      LinearLayout empty=new LinearLayout(owner);
      empty.setGravity(Gravity.CENTER_VERTICAL);
      TextView copy=owner.text(
          provider==CloudFilesClient.Provider.PREMIUM
              ?"优享版账号可继续用于保存分享与转存。"
              :"普通蓝奏云账号用于管理和下载私人云盘文件。",
          12,owner.MUTED);
      copy.setMaxLines(3);
      empty.addView(copy,new LinearLayout.LayoutParams(-1,-2));
      stage.addView(empty,new LinearLayout.LayoutParams(-1,owner.dp(64)));
    }else{
      Map<String,Boolean> checked=verified.get(provider);
      for(String name:names)stage.addView(accountRow(name,name.equals(current),checked),new LinearLayout.LayoutParams(-1,owner.dp(56)));
    }

    LinearLayout actions=new LinearLayout(owner);
    actions.setGravity(Gravity.END|Gravity.CENTER_VERTICAL);
    Button verifyButton=owner.toolbarTextButton(busy?"验证中…":"验证");
    verifyButton.setEnabled(!busy&&!names.isEmpty());
    verifyButton.setOnClickListener(v->verifyAccounts());
    Button addButton=owner.toolbarTextButton("添加账号");
    addButton.setEnabled(!busy);
    addButton.setOnClickListener(v->showLogin(provider,null));
    actions.addView(verifyButton,new LinearLayout.LayoutParams(owner.dp(60),owner.dp(46)));
    actions.addView(addButton,new LinearLayout.LayoutParams(owner.dp(78),owner.dp(46)));
    if(provider==CloudFilesClient.Provider.PREMIUM){
      Button register=owner.toolbarTextButton("注册优享版");
      register.setEnabled(!busy);
      register.setOnClickListener(v->owner.openPremiumRegistration());
      actions.addView(register,new LinearLayout.LayoutParams(owner.dp(96),owner.dp(46)));
    }
    stage.addView(actions,new LinearLayout.LayoutParams(-1,owner.dp(50)));

    verifySummary=owner.text("",11,owner.MUTED);
    verifySummary.setMaxLines(2);
    verifySummary.setVisibility(View.GONE);
    stage.addView(verifySummary,new LinearLayout.LayoutParams(-1,owner.dp(36)));
    showVerificationSummary();
  }

  private LinearLayout accountRow(String name,boolean current,Map<String,Boolean> checked){
    LinearLayout row=owner.settingsRowShell();
    row.setBackground(owner.filterRipple(new ColorDrawable(Color.TRANSPARENT)));
    row.setClickable(true);
    row.setFocusable(true);
    row.setEnabled(!busy);
    String masked=PremiumCloudClient.maskAccount(name);
    row.setContentDescription(masked+(current?"，当前账号":"")+". 点击切换当前账号");

    RadioButton radio=new RadioButton(owner);
    radio.setChecked(current);
    radio.setClickable(false);
    radio.setFocusable(false);
    radio.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
    row.addView(radio,new LinearLayout.LayoutParams(owner.dp(42),owner.dp(48)));

    TextView label=owner.text(masked,14,current?owner.PRIMARY:owner.TEXT);
    label.setMaxLines(1);
    row.addView(label,new LinearLayout.LayoutParams(0,-1,1));

    boolean failed=checked!=null&&Boolean.FALSE.equals(checked.get(name));
    TextView status=owner.text(
        failed?(current?"当前 · 未通过":"未通过"):current?"当前账号":
            checked!=null&&Boolean.TRUE.equals(checked.get(name))?"登录有效":"",
        11,failed?owner.MUTED:owner.PRIMARY);
    status.setGravity(Gravity.END|Gravity.CENTER_VERTICAL);
    row.addView(status,new LinearLayout.LayoutParams(owner.dp(72),-1));

    Button remove=owner.toolbarTextButton("移除");
    remove.setEnabled(!busy);
    remove.setOnClickListener(v->confirmRemove(name));
    row.addView(remove,new LinearLayout.LayoutParams(owner.dp(54),owner.dp(46)));
    row.setOnClickListener(v->selectAccount(name));
    return row;
  }

  private void selectAccount(String name){
    if(busy)return;
    final CloudFilesClient.Provider target=provider;
    busy=true;
    renderProvider();
    owner.io.execute(()->{
      String error="";
      try{
        synchronized(client){
          if(!client.selectAccount(target,name))error="账号不存在";
        }
      }catch(CloudFilesClient.CloudException ignored){
        error="切换账号失败";
      }
      final String problem=error;
      owner.runOnUiThread(()->{
        busy=false;
        if(owner.pageKind!=4)return;
        renderProvider();
        owner.showNotice(problem.isEmpty()
            ?"已切换当前账号 · "+PremiumCloudClient.maskAccount(name)
            :problem,false);
      });
    });
  }

  private void confirmRemove(String name){
    if(busy)return;
    AlertDialog prompt=new AlertDialog.Builder(owner)
        .setTitle("移除账号？")
        .setMessage("将移除 "+PremiumCloudClient.maskAccount(name)+" 的本机加密登录信息，不影响云端账号。")
        .setNegativeButton("取消",null)
        .setPositiveButton("移除",(dialog,which)->removeAccount(name))
        .create();
    owner.showRounded(prompt);
  }

  private void removeAccount(String name){
    final CloudFilesClient.Provider target=provider;
    busy=true;
    renderProvider();
    owner.io.execute(()->{
      String error="";
      try{
        synchronized(client){client.removeAccount(target,name);}
      }catch(CloudFilesClient.CloudException ignored){
        error="移除账号失败";
      }
      final String problem=error;
      owner.runOnUiThread(()->{
        busy=false;
        if(owner.pageKind!=4)return;
        if(problem.isEmpty())owner.refreshSettingsFade("账号已移除");
        else{
          renderProvider();
          owner.showNotice(problem,true);
        }
      });
    });
  }

  private void verifyAccounts(){
    if(busy)return;
    final CloudFilesClient.Provider target=provider;
    busy=true;
    renderProvider();
    owner.io.execute(()->{
      Map<String,Boolean> states=new LinkedHashMap<>();
      String error="";
      List<String> names=new ArrayList<>();
      try{
        names.addAll(client.accountNames(target));
      }catch(CloudFilesClient.CloudException ignored){
        error="无法读取本机账号";
      }
      for(String name:names){
        boolean valid=false;
        try{
          valid=client.verifyAccount(target,name).loggedIn;
        }catch(CloudFilesClient.CloudException ignored){}
        states.put(name,valid);
      }
      final Map<String,Boolean> result=states;
      final String problem=error;
      owner.runOnUiThread(()->{
        busy=false;
        if(owner.pageKind!=4)return;
        if(!result.isEmpty())verified.put(target,result);
        renderProvider();
        if(!problem.isEmpty())owner.showNotice(problem,true);
      });
    });
  }

  private void showVerificationSummary(){
    if(verifySummary==null)return;
    Map<String,Boolean> states=verified.get(provider);
    if(states==null||states.isEmpty()){
      verifySummary.setVisibility(View.GONE);
      return;
    }
    int valid=0;
    for(Boolean value:states.values())if(Boolean.TRUE.equals(value))valid++;
    int total=states.size();
    verifySummary.setText("验证结果：已登录 "+valid+"/"+total+(valid==total?" · 全部可用":" · 其余账号未通过验证"));
    verifySummary.setTextColor(valid==total?owner.PRIMARY:owner.MUTED);
    verifySummary.setVisibility(View.VISIBLE);
  }

  private void showLogin(CloudFilesClient.Provider selected,Runnable afterLogin){
    if(selected==null)selected=provider;
    provider=selected;
    rememberProvider();
    final CloudFilesClient.Provider target=selected;
    String label=providerLabel(target);

    LinearLayout panel=new LinearLayout(owner);
    panel.setOrientation(LinearLayout.VERTICAL);
    panel.setPadding(owner.dp(22),owner.dp(6),owner.dp(22),0);
    EditText account=new EditText(owner);
    account.setSingleLine();
    account.setSaveEnabled(false);
    account.setTextColor(owner.TEXT);
    account.setHintTextColor(owner.MUTED);
    account.setHint("账号");
    account.setInputType(InputType.TYPE_CLASS_TEXT);
    EditText password=new EditText(owner);
    password.setSingleLine();
    password.setSaveEnabled(false);
    password.setTextColor(owner.TEXT);
    password.setHintTextColor(owner.MUTED);
    password.setHint("密码");
    password.setInputType(InputType.TYPE_CLASS_TEXT|InputType.TYPE_TEXT_VARIATION_PASSWORD);
    TextView note=owner.text("密码仅提交至蓝奏云官方接口，登录信息加密保存在本机。",11,owner.MUTED);
    note.setMaxLines(2);
    panel.addView(account,new LinearLayout.LayoutParams(-1,owner.dp(54)));
    panel.addView(password,new LinearLayout.LayoutParams(-1,owner.dp(54)));
    panel.addView(note,new LinearLayout.LayoutParams(-1,owner.dp(42)));

    AlertDialog.Builder builder=new AlertDialog.Builder(owner)
        .setTitle("登录"+label)
        .setView(panel)
        .setNegativeButton("取消",null)
        .setPositiveButton("登录",null);
    if(target==CloudFilesClient.Provider.PREMIUM){
      builder.setNeutralButton("注册",(dialog,which)->owner.openPremiumRegistration());
    }
    AlertDialog prompt=builder.create();
    prompt.setOnDismissListener(ignored->{
      account.setText("");
      password.setText("");
    });
    prompt.setOnShowListener(ignored->{
      Button login=prompt.getButton(AlertDialog.BUTTON_POSITIVE);
      login.setOnClickListener(v->{
        String name=account.getText().toString().trim();
        String secret=password.getText().toString();
        if(name.isEmpty()){
          account.setError("请输入账号");
          account.requestFocus();
          return;
        }
        if(secret.isEmpty()){
          password.setError("请输入密码");
          password.requestFocus();
          return;
        }
        password.setText("");
        setLoginEnabled(account,password,login,false);
        login.setText("登录中…");
        owner.io.execute(()->{
          CloudFilesClient.LoginState state=null;
          boolean failed=false;
          try{
            state=client.login(target,name,secret);
            failed=!state.loggedIn;
          }catch(CloudFilesClient.CloudException ignoredError){
            failed=true;
          }
          final boolean loginFailed=failed;
          owner.runOnUiThread(()->{
            if(owner.isFinishing()||owner.isDestroyed())return;
            if(loginFailed){
              setLoginEnabled(account,password,login,true);
              login.setText("登录");
              password.setError("账号或密码错误，请重新输入");
              password.requestFocus();
              InputMethodManager keyboard=
                  (InputMethodManager)owner.getSystemService(MainActivity.INPUT_METHOD_SERVICE);
              if(keyboard!=null)keyboard.showSoftInput(password,InputMethodManager.SHOW_IMPLICIT);
              return;
            }
            prompt.dismiss();
            owner.refreshSettingsFade(label+"已登录");
            if(afterLogin!=null)owner.ui.post(afterLogin);
          });
        });
      });
    });
    owner.showRounded(prompt);
    owner.prepareRoundedInputDialog(prompt,account);
  }

  private void setLoginEnabled(EditText account,EditText password,Button action,boolean enabled){
    account.setEnabled(enabled);
    account.setClickable(enabled);
    account.setFocusable(enabled);
    account.setFocusableInTouchMode(enabled);
    password.setEnabled(enabled);
    password.setClickable(enabled);
    password.setFocusable(enabled);
    password.setFocusableInTouchMode(enabled);
    action.setEnabled(enabled);
  }

  private static String providerLabel(CloudFilesClient.Provider value){
    return value==CloudFilesClient.Provider.PREMIUM?"蓝奏云优享版":"普通蓝奏云";
  }

  private static CloudFilesClient.Provider storedProvider(MainActivity owner){
    SharedPreferences preferences=owner.getSharedPreferences(UI_PREFS,0);
    String stored=preferences.getString(UI_PROVIDER,"");
    if(CloudFilesClient.Provider.PREMIUM.name().equals(stored))return CloudFilesClient.Provider.PREMIUM;
    if(CloudFilesClient.Provider.ORDINARY.name().equals(stored))return CloudFilesClient.Provider.ORDINARY;
    return owner.getSharedPreferences("cloud_management",0).getBoolean("premium",false)
        ?CloudFilesClient.Provider.PREMIUM:CloudFilesClient.Provider.ORDINARY;
  }

  private void rememberProvider(){
    owner.getSharedPreferences(UI_PREFS,0).edit().putString(UI_PROVIDER,provider.name()).apply();
  }
}
