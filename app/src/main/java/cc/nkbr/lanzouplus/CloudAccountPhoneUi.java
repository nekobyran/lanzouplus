package cc.nkbr.lanzouplus;

import android.app.AlertDialog;
import android.text.InputType;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import org.json.JSONObject;

/** Account-bound ordinary cloud phone change flow. */
final class CloudAccountPhoneUi {
  interface Factory { CloudAccountSettingsProtocol.Settings open()throws CloudFilesClient.CloudException; }

  private final MainActivity owner;
  private final Factory factory;
  private CloudAccountSettingsProtocol.Settings settings;
  private AlertDialog dialog;
  private TextView status;
  private EditText currentPhone,newPhone,code;
  private Button submit;
  private boolean busy,verificationRequired,finished;
  private String capturedCurrent="",capturedNew="";
  private int generation;

  private CloudAccountPhoneUi(MainActivity owner,Factory factory){this.owner=owner;this.factory=factory;}
  static void show(MainActivity owner,Factory factory){new CloudAccountPhoneUi(owner,factory).show();}

  private void show(){
    LinearLayout body=new LinearLayout(owner);body.setOrientation(LinearLayout.VERTICAL);
    body.setPadding(owner.dp(18),owner.dp(6),owner.dp(18),owner.dp(8));
    status=owner.text(owner.getString(R.string.account_phone_reading),13,owner.MUTED);body.addView(status);
    currentPhone=input(R.string.account_phone_old);newPhone=input(R.string.account_phone_new);
    code=input(R.string.account_phone_code);code.setSaveEnabled(false);
    body.addView(currentPhone,new LinearLayout.LayoutParams(-1,owner.dp(52)));
    body.addView(newPhone,new LinearLayout.LayoutParams(-1,owner.dp(52)));
    body.addView(code,new LinearLayout.LayoutParams(-1,owner.dp(52)));
    submit=owner.toolbarTextButton(owner.getString(R.string.account_phone_request));
    body.addView(submit,new LinearLayout.LayoutParams(-1,owner.dp(48)));
    ScrollView scroll=new ScrollView(owner);scroll.addView(body);
    dialog=new AlertDialog.Builder(owner).setTitle(R.string.account_phone_title).setView(scroll)
        .setNegativeButton(R.string.account_phone_close,null)
        .setNeutralButton(R.string.account_phone_refresh,null).create();
    dialog.setOnDismissListener(ignored->{generation++;code.setText("");settings=null;});
    owner.showRounded(dialog);
    dialog.getButton(AlertDialog.BUTTON_NEUTRAL).setOnClickListener(v->read());
    submit.setOnClickListener(v->submit());
    read();
  }

  private EditText input(int label){
    String text=owner.getString(label);
    EditText input=owner.sourceInput(text,InputType.TYPE_CLASS_PHONE);
    input.setContentDescription(text);input.setSingleLine(true);return input;
  }

  private boolean current(int request){
    return !owner.activityClosing()&&dialog.isShowing()&&generation==request;
  }

  private void enabled(boolean enabled){
    busy=!enabled;
    currentPhone.setEnabled(enabled&&!verificationRequired&&!finished);
    newPhone.setEnabled(enabled&&!verificationRequired&&!finished);
    code.setVisibility(verificationRequired?View.VISIBLE:View.GONE);
    code.setEnabled(enabled&&verificationRequired&&!finished);
    submit.setEnabled(enabled&&!finished&&settings!=null);
    submit.setText(verificationRequired?R.string.account_phone_confirm:R.string.account_phone_request);
    dialog.getButton(AlertDialog.BUTTON_NEUTRAL).setEnabled(enabled);
  }

  private void read(){
    if(busy)return;
    int request=++generation;enabled(false);
    owner.io.execute(()->{
      CloudAccountSettingsProtocol.Settings opened=settings;String phone=null,error="";
      try{if(opened==null)opened=factory.open();phone=opened.phoneStatus();}
      catch(Exception failure){error=owner.friendlyError(failure);}
      CloudAccountSettingsProtocol.Settings handle=opened;String found=phone,problem=error;
      owner.runOnUiThread(()->{
        if(!current(request))return;
        settings=handle;
        status.setText(found!=null?owner.getString(R.string.account_phone_status,found):problem);
        enabled(true);
      });
    });
  }

  private void submit(){
    if(busy||finished||settings==null)return;
    if(verificationRequired){
      String verificationCode=code.getText().toString();
      if(verificationCode.trim().isEmpty()){owner.showNotice(owner.getString(R.string.account_phone_code_required),false);return;}
      change(capturedCurrent,capturedNew,verificationCode,true);return;
    }
    String oldValue=currentPhone.getText().toString(),newValue=newPhone.getText().toString();
    if(oldValue.trim().isEmpty()||newValue.trim().isEmpty()){
      owner.showNotice(owner.getString(R.string.account_phone_required),false);return;
    }
    AlertDialog confirm=new AlertDialog.Builder(owner).setTitle(R.string.account_phone_confirm_title)
        .setMessage(owner.getString(R.string.account_phone_confirm_message,oldValue,newValue))
        .setNegativeButton(R.string.account_phone_cancel,null)
        .setPositiveButton(R.string.account_phone_request,(d,w)->change(oldValue,newValue,"",false)).create();
    owner.showRounded(confirm);
  }

  private void change(String oldValue,String newValue,String verificationCode,boolean confirm){
    if(busy||finished||settings==null)return;
    int request=++generation;enabled(false);
    CloudAccountSettingsProtocol.Settings handle=settings;
    owner.io.execute(()->{
      JSONObject result=null;String error="";
      try{result=confirm?handle.confirmPhoneChange(oldValue,newValue,verificationCode)
          :handle.requestPhoneChange(oldValue,newValue);}
      catch(Exception failure){error=owner.friendlyError(failure);}
      JSONObject reply=result;String problem=error;
      owner.runOnUiThread(()->{
        if(!current(request))return;
        if(reply==null)status.setText(problem);
        else if("verification_required".equals(reply.optString("state"))){
          capturedCurrent=oldValue;capturedNew=newValue;verificationRequired=true;
          status.setText(reply.optString("message"));code.setText("");
        }else if("updated".equals(reply.optString("state"))){
          finished=true;verificationRequired=false;
          status.setText(owner.getString(R.string.account_phone_updated,reply.optString("phone")));
          currentPhone.setText("");newPhone.setText("");code.setText("");
        }else status.setText(R.string.account_phone_invalid_response);
        enabled(true);
      });
    });
  }
}
