package cc.nkbr.lanzouplus;

import android.app.AlertDialog;
import android.content.ActivityNotFoundException;
import android.content.Intent;
import android.net.Uri;
import android.text.InputType;
import android.view.View;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;

/** Native controls for account-page models; protocol fields never become a WebView. */
final class CloudAccountPagesUi {
  interface Factory { CloudOrdinaryAccountPages.Pages open()throws CloudFilesClient.CloudException; }
  private final MainActivity owner;
  private final Factory factory;
  private final Runnable passwordChanged;
  private final List<View> controls=new ArrayList<>();
  private final List<EditText> passwordInputs=new ArrayList<>();
  private AlertDialog dialog;
  private LinearLayout body;
  private CloudOrdinaryAccountPages.Pages pages;
  private CloudOrdinaryAccountPages.Section section;
  private int generation;
  private boolean busy;
  private CloudAccountPagesUi(MainActivity owner,Factory factory,Runnable passwordChanged){
    this.owner=owner;this.factory=factory;this.passwordChanged=passwordChanged;
  }
  static void choose(MainActivity owner,Factory factory,Runnable passwordChanged){
    CloudOrdinaryAccountPages.Section[] sections=CloudOrdinaryAccountPages.Section.values();
    String[] labels=new String[sections.length];for(int i=0;i<labels.length;i++)labels[i]=title(sections[i]);
    AlertDialog picker=new AlertDialog.Builder(owner).setTitle("账号管理").setItems(labels,
        (d,index)->new CloudAccountPagesUi(owner,factory,passwordChanged).show(sections[index]))
        .setNegativeButton("取消",null).create();owner.showRounded(picker);
  }
  private static String title(CloudOrdinaryAccountPages.Section section){
    switch(section){
      case PROFILE:return "个人设置";case MEMBERSHIP:return "会员充值";
      case BALANCE:return "余额与充值";case FILE_UPGRADE:return "文件升级";
      case CASHOUT:return "收益提现";case ORDERS:return "订单记录";
      case PASSWORD:return "修改密码";case INVITE:return "邀请与奖励";
      default:throw new IllegalArgumentException("section");
    }
  }
  private void show(CloudOrdinaryAccountPages.Section requested){
    section=requested;body=new LinearLayout(owner);body.setOrientation(LinearLayout.VERTICAL);
    body.setPadding(owner.dp(18),owner.dp(6),owner.dp(18),owner.dp(8));
    ScrollView scroll=new ScrollView(owner);scroll.addView(body);
    LinearLayout panel=new LinearLayout(owner);panel.addView(scroll,new LinearLayout.LayoutParams(-1,
        Math.max(owner.dp(128),Math.min(owner.dp(460),owner.safeContentHeight()-owner.dp(180)))));
    dialog=new AlertDialog.Builder(owner).setTitle(title(section)).setView(panel)
        .setNegativeButton("关闭",null).setNeutralButton("刷新",null).create();
    dialog.setOnDismissListener(d->{generation++;clearPasswords();});owner.showRounded(dialog);
    dialog.getButton(AlertDialog.BUTTON_NEUTRAL).setOnClickListener(v->read());read();
  }
  private boolean current(int request){return !owner.activityClosing()&&dialog.isShowing()&&request==generation;}
  private void enabled(boolean value){
    busy=!value;for(View control:controls)control.setEnabled(value);
    dialog.getButton(AlertDialog.BUTTON_NEUTRAL).setEnabled(value);
  }
  private void read(){
    if(busy)return;final int request=++generation;enabled(false);
    body.removeAllViews();body.addView(owner.text("正在读取…",13,owner.MUTED));
    owner.io.execute(()->{
      CloudOrdinaryAccountPages.Pages opened=pages;CloudOrdinaryAccountPages.Page result=null;String error="";
      try{if(opened==null)opened=factory.open();result=opened.read(section);}catch(Exception failure){error=owner.friendlyError(failure);}
      final CloudOrdinaryAccountPages.Pages handle=opened;final CloudOrdinaryAccountPages.Page found=result;final String problem=error;
      owner.runOnUiThread(()->{
        if(!current(request))return;enabled(true);
        if(found==null){body.removeAllViews();body.addView(owner.text(problem,13,owner.TEXT));return;}
        pages=handle;render(found);
      });
    });
  }
  private void render(CloudOrdinaryAccountPages.Page page){
    clearPasswords();passwordInputs.clear();body.removeAllViews();controls.clear();
    for(CloudOrdinaryAccountPages.Fact fact:page.facts){
      TextView row=owner.text(fact.label+"\n"+fact.value,13,owner.TEXT);
      row.setTextIsSelectable(true);row.setPadding(0,owner.dp(8),0,owner.dp(8));body.addView(row);
    }
    for(CloudOrdinaryAccountPages.Form form:page.forms){
      Map<String,Supplier<String>> inputs=new LinkedHashMap<>();List<EditText> secrets=new ArrayList<>();
      for(CloudOrdinaryAccountPages.Field field:form.fields)field(field,inputs,secrets);
      Button submit=owner.toolbarTextButton(form.submitLabel);controls.add(submit);
      body.addView(submit,new LinearLayout.LayoutParams(-1,owner.dp(48)));
      submit.setOnClickListener(v->submit(form,inputs,secrets));
    }
    if(page.facts.isEmpty()&&page.forms.isEmpty())body.addView(owner.text("暂无可显示的设置",13,owner.MUTED));
  }
  private void field(CloudOrdinaryAccountPages.Field field,Map<String,Supplier<String>> inputs,List<EditText> secrets){
    String label=field.label+(field.required?" *":"");
    if(field.kind.equals("checkbox")){
      CheckBox check=new CheckBox(owner);check.setText(label);check.setTextColor(owner.TEXT);
      check.setButtonTintList(android.content.res.ColorStateList.valueOf(owner.PRIMARY));
      check.setChecked(!field.value.isEmpty());body.addView(check,new LinearLayout.LayoutParams(-1,owner.dp(48)));
      inputs.put(field.name,()->check.isChecked()?field.checkedValue:"");controls.add(check);return;
    }
    TextView caption=owner.text(label,12,owner.MUTED);caption.setPadding(0,owner.dp(12),0,owner.dp(4));body.addView(caption);
    if(field.kind.equals("select")||field.kind.equals("radio")){
      String[] value={field.value};Button choice=owner.toolbarTextButton(optionLabel(field,value[0]));
      choice.setContentDescription(field.label+"，"+choice.getText());controls.add(choice);
      body.addView(choice,new LinearLayout.LayoutParams(-1,owner.dp(48)));
      choice.setOnClickListener(v->{
        String[] labels=new String[field.options.size()];int selected=-1;
        for(int i=0;i<labels.length;i++){CloudOrdinaryAccountPages.Option option=field.options.get(i);labels[i]=option.label;if(option.value.equals(value[0]))selected=i;}
        AlertDialog picker=new AlertDialog.Builder(owner).setTitle(field.label).setSingleChoiceItems(labels,selected,(d,index)->{
          value[0]=field.options.get(index).value;choice.setText(optionLabel(field,value[0]));
          choice.setContentDescription(field.label+"，"+choice.getText());d.dismiss();
        }).setNegativeButton("取消",null).create();owner.showRounded(picker);
      });
      inputs.put(field.name,()->value[0]);return;
    }
    int type=InputType.TYPE_CLASS_TEXT;
    if(field.kind.equals("password"))type|=InputType.TYPE_TEXT_VARIATION_PASSWORD;
    else if(field.kind.equals("email"))type|=InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS;
    else if(field.kind.equals("tel"))type=InputType.TYPE_CLASS_PHONE;
    else if(field.kind.equals("number"))type=InputType.TYPE_CLASS_NUMBER|InputType.TYPE_NUMBER_FLAG_DECIMAL|InputType.TYPE_NUMBER_FLAG_SIGNED;
    else if(field.kind.equals("textarea"))type|=InputType.TYPE_TEXT_FLAG_MULTI_LINE;
    EditText input=owner.sourceInput(field.label,type);input.setText(field.value);input.setSingleLine(!field.kind.equals("textarea"));
    input.setContentDescription(field.label);input.setSaveEnabled(!field.kind.equals("password"));
    if(field.kind.equals("textarea"))input.setMinLines(3);
    body.addView(input,new LinearLayout.LayoutParams(-1,field.kind.equals("textarea")?-2:owner.dp(52)));
    controls.add(input);inputs.put(field.name,()->input.getText().toString());
    if(field.kind.equals("password")){secrets.add(input);passwordInputs.add(input);}
  }
  private void clearPasswords(){for(EditText input:passwordInputs)input.setText("");}
  private static String optionLabel(CloudOrdinaryAccountPages.Field field,String value){
    for(CloudOrdinaryAccountPages.Option option:field.options)if(option.value.equals(value))return option.label;
    return "请选择";
  }
  private void submit(CloudOrdinaryAccountPages.Form form,Map<String,Supplier<String>> inputs,List<EditText> secrets){
    if(busy)return;Map<String,String> values=new LinkedHashMap<>();
    for(Map.Entry<String,Supplier<String>> input:inputs.entrySet())values.put(input.getKey(),input.getValue().get());
    for(EditText input:secrets)input.setText("");final int request=++generation;enabled(false);
    owner.io.execute(()->{
      CloudOrdinaryAccountPages.Result result=null;String error="";
      try{result=pages.submit(section,form.id,values);}catch(Exception failure){error=owner.friendlyError(failure);}
      values.clear();final CloudOrdinaryAccountPages.Result saved=result;final String problem=error;
      owner.runOnUiThread(()->{
        if(!current(request))return;enabled(true);
        if(saved==null){owner.showNotice(problem,true);return;}
        if(saved.paymentRequired){dialog.dismiss();payment(saved.paymentUrl);return;}
        owner.showNotice(saved.message,false);
        if(section==CloudOrdinaryAccountPages.Section.PASSWORD){dialog.dismiss();passwordChanged.run();return;}
        if(saved.page!=null)render(saved.page);else read();
      });
    });
  }
  private void payment(String url){
    AlertDialog confirm=new AlertDialog.Builder(owner).setTitle("前往支付")
        .setMessage("官方已生成支付入口。完成支付后可在订单记录中核对结果。")
        .setNegativeButton("稍后",null).setPositiveButton("前往支付",(d,w)->{
          try{owner.startActivity(new Intent(Intent.ACTION_VIEW,Uri.parse(url)));}
          catch(ActivityNotFoundException missing){owner.showNotice("未找到可打开支付页面的应用",true);}
        }).create();owner.showRounded(confirm);
  }
}
