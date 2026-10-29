package cc.nkbr.lanzouplus;

import android.app.AlertDialog;
import android.text.InputType;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Native UI for the ordinary-account domain and sharing settings.
 *
 * <p>Shows whatever the server actually returned for the domain list: only real keys appear,
 * known domain keys get Chinese labels, nested containers are expanded by level, and sensitive
 * keys are masked. No schema is inferred beyond the observed keys.</p>
 *
 * <p>All protocol calls run on the host background executor through a
 * {@link CloudAccountSettingsProtocol.Settings} handle
 * captured once per dialog session, so an account switch cannot rebind a write to another
 * account.</p>
 */
final class CloudOrdinaryDomainUi {
  interface Factory { CloudAccountSettingsProtocol.Settings open()throws CloudFilesClient.CloudException; }

  private static final Set<String> SECRET_KEYS=new HashSet<>();
  static {
    for(String key:new String[]{"password","passwd","pwd","token","cookie","cookies","secret",
        "session","auth","authorization","apikey","api_key","formhash","vei","k3"})
      SECRET_KEYS.add(key);
  }

  private final MainActivity owner;
  private final Factory factory;
  private final List<View> controls=new ArrayList<>();
  private AlertDialog dialog;
  private LinearLayout body;
  private CloudAccountSettingsProtocol.Settings settings;
  private int generation;
  private boolean busy;

  private CloudOrdinaryDomainUi(MainActivity owner,Factory factory){
    this.owner=owner;this.factory=factory;
  }

  static void show(MainActivity owner,Factory factory){
    new CloudOrdinaryDomainUi(owner,factory).show();
  }

  private void show(){
    body=new LinearLayout(owner);
    body.setOrientation(LinearLayout.VERTICAL);
    body.setPadding(owner.dp(18),owner.dp(6),owner.dp(18),owner.dp(8));
    ScrollView scroll=new ScrollView(owner);
    scroll.setFillViewport(false);
    scroll.addView(body,new ScrollView.LayoutParams(-1,-2));
    LinearLayout panel=new LinearLayout(owner);
    panel.setOrientation(LinearLayout.VERTICAL);
    panel.addView(scroll,new LinearLayout.LayoutParams(-1,
        Math.max(owner.dp(160),Math.min(owner.dp(460),owner.safeContentHeight()-owner.dp(190)))));
    Button actions=owner.toolbarTextButton("域名操作");
    actions.setContentDescription("打开域名与共享操作");
    controls.add(actions);
    panel.addView(actions,new LinearLayout.LayoutParams(-1,owner.dp(48)));
    dialog=new AlertDialog.Builder(owner)
        .setTitle("域名与共享")
        .setView(panel)
        .setNegativeButton("关闭",null)
        .setNeutralButton("刷新",null)
        .create();
    dialog.setOnDismissListener(d->{generation++;settings=null;});
    owner.showRounded(dialog);
    dialog.getButton(AlertDialog.BUTTON_NEUTRAL).setOnClickListener(v->read());
    actions.setOnClickListener(v->actionMenu());
    read();
  }

  private boolean current(int request){
    return !owner.activityClosing()&&dialog.isShowing()&&request==generation;
  }

  private void enabled(boolean value){
    busy=!value;
    for(View control:controls)control.setEnabled(value);
    if(dialog.isShowing())dialog.getButton(AlertDialog.BUTTON_NEUTRAL).setEnabled(value);
  }

  private void read(){
    if(busy)return;
    final int request=++generation;
    enabled(false);
    body.removeAllViews();
    body.addView(owner.text("正在读取域名列表…",13,owner.MUTED));
    owner.io.execute(()->{
      CloudAccountSettingsProtocol.Settings handle=settings;
      JSONObject result=null;
      String error="";
      try{
        if(handle==null)handle=factory.open();
        result=handle.domainList();
      }catch(Exception failure){
        error=message(failure);
      }
      final CloudAccountSettingsProtocol.Settings opened=handle;
      final JSONObject found=result;
      final String problem=error;
      owner.runOnUiThread(()->{
        if(!current(request))return;
        enabled(true);
        if(found==null){
          body.removeAllViews();
          body.addView(owner.text(problem.isEmpty()?"读取域名列表失败，请重试":problem,
              13,owner.TEXT));
          return;
        }
        settings=opened;
        render(found);
      });
    });
  }

  private void render(JSONObject root){
    body.removeAllViews();
    TextView caption=owner.text("以下为官方返回的域名数据（已隐藏敏感字段）。",12,owner.MUTED);
    caption.setPadding(0,0,0,owner.dp(6));
    body.addView(caption);
    int rows=renderObject(root,0,true);
    if(rows==0){
      body.addView(owner.text("官方未返回可显示的域名数据。",13,owner.MUTED));
    }
  }

  /** Renders real keys only; returns the number of visible value rows. */
  private int renderObject(JSONObject object,int depth,boolean rootLevel){
    int rows=0;
    Iterator<String> keys=object.keys();
    List<String> ordered=new ArrayList<>();
    while(keys.hasNext()){
      String key=keys.next();
      if(rootLevel&&"zt".equals(key))continue;
      ordered.add(key);
    }
    for(String key:ordered){
      rows+=renderEntry(key,object.opt(key),depth);
    }
    return rows;
  }

  private int renderEntry(String key,Object value,int depth){
    if(key!=null&&SECRET_KEYS.contains(key.toLowerCase(Locale.ROOT)))return 0;
    String label=labelFor(key);
    if(value instanceof JSONObject){
      JSONObject nested=(JSONObject)value;
      body.addView(section(label,depth));
      int nestedRows=renderObject(nested,depth+1,false);
      if(nestedRows==0){body.addView(row("（空）","—",depth+1));return 1;}
      return nestedRows;
    }
    if(value instanceof JSONArray){
      JSONArray array=(JSONArray)value;
      int containerRows=0;
      for(int index=0;index<array.length();index++){
        Object item=array.opt(index);
        String itemLabel=label+(array.length()>1?" "+(index+1):"");
        if(item instanceof JSONObject){
          body.addView(section(itemLabel,depth));
          containerRows+=renderObject((JSONObject)item,depth+1,false);
        }else if(item instanceof JSONArray){
          body.addView(section(itemLabel,depth));
          containerRows+=renderArray((JSONArray)item,depth+1,itemLabel);
        }else{
          body.addView(row(itemLabel,scalar(item),depth));
          containerRows++;
        }
      }
      if(containerRows==0&&array.length()==0)body.addView(row(label,"（空）",depth));
      return containerRows;
    }
    body.addView(row(label,scalar(value),depth));
    return 1;
  }

  private int renderArray(JSONArray array,int depth,String prefix){
    int rows=0;
    for(int index=0;index<array.length();index++){
      Object item=array.opt(index);
      String itemLabel=prefix+" "+(index+1);
      if(item instanceof JSONObject){
        body.addView(section(itemLabel,depth));
        rows+=renderObject((JSONObject)item,depth+1,false);
      }else if(item instanceof JSONArray){
        rows+=renderArray((JSONArray)item,depth,itemLabel);
      }else{
        body.addView(row(itemLabel,scalar(item),depth));
        rows++;
      }
    }
    return rows;
  }

  private TextView section(String label,int depth){
    TextView view=owner.text(label,13,owner.TEXT);
    view.setPadding(owner.dp(8)*depth,owner.dp(10),0,owner.dp(2));
    view.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
    return view;
  }

  private LinearLayout row(String label,String value,int depth){
    LinearLayout line=new LinearLayout(owner);
    line.setGravity(Gravity.CENTER_VERTICAL);
    line.setPadding(owner.dp(8)*depth,owner.dp(2),0,owner.dp(2));
    TextView name=owner.text(label,12,owner.MUTED);
    name.setSingleLine(true);
    name.setMaxWidth(owner.dp(160));
    line.addView(name,new LinearLayout.LayoutParams(0,-2,1));
    TextView text=owner.text(value,13,owner.TEXT);
    text.setTextIsSelectable(true);
    text.setGravity(Gravity.END|Gravity.CENTER_VERTICAL);
    line.addView(text,new LinearLayout.LayoutParams(0,-2,1));
    return line;
  }

  private static String scalar(Object value){
    if(value==null||value==JSONObject.NULL)return "—";
    if(value instanceof Boolean)return ((Boolean)value)?"是":"否";
    String text=value.toString();
    return text.isEmpty()?"—":text;
  }

  private static String labelFor(String key){
    if(key==null)return "";
    String lower=key.toLowerCase(Locale.ROOT);
    switch(lower){
      case "domain":return "域名";
      case "id":case "domainid":return "ID";
      case "name":case "domainname":return "名称";
      case "isdefault":case "default":case "is_default":return "默认";
      case "status":case "zt":return "状态";
      case "info":case "inf":return "提示";
      case "domains":case "list":case "data":return "域名列表";
      default:return key;
    }
  }

  private void actionMenu(){
    if(busy)return;
    String[] labels={"设置域名","设置默认域名","开启共享","关闭共享"};
    AlertDialog menu=new AlertDialog.Builder(owner).setTitle("域名与共享操作")
        .setItems(labels,(d,which)->{
          if(which==0)promptDomain();
          else if(which==1)promptDefaultDomain();
          else confirmSharing(which==2);
        }).setNegativeButton("取消",null).create();
    owner.showRounded(menu);
  }

  private void promptDomain(){
    LinearLayout panel=dialogPanel();
    panel.addView(owner.text("填写要设置的官方域名，例如 yourname.lanzou.com。",12,owner.MUTED));
    EditText input=owner.sourceInput("官方域名",InputType.TYPE_CLASS_TEXT);
    input.setSingleLine(true);
    panel.addView(input,new LinearLayout.LayoutParams(-1,owner.dp(52)));
    AlertDialog prompt=new AlertDialog.Builder(owner).setTitle("设置域名").setView(panel)
        .setNegativeButton("取消",null).setPositiveButton("下一步",null).create();
    owner.showRounded(prompt);
    owner.prepareRoundedInputDialog(prompt,input);
    prompt.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v->{
      String value=input.getText().toString().trim();
      if(value.isEmpty()){input.setError("请输入域名");return;}
      prompt.dismiss();
      confirm("设置域名？","将提交官方域名：“"+value+"”。写入后重新读取域名列表核对。",
          ()->writeDomain(value));
    });
  }

  private void promptDefaultDomain(){
    LinearLayout panel=dialogPanel();
    panel.addView(owner.text("填写域名列表中的 ID（上面的「ID」行），而不是域名本身。",12,owner.MUTED));
    EditText input=owner.sourceInput("域名 ID",InputType.TYPE_CLASS_TEXT);
    input.setSingleLine(true);
    panel.addView(input,new LinearLayout.LayoutParams(-1,owner.dp(52)));
    AlertDialog prompt=new AlertDialog.Builder(owner).setTitle("设置默认域名").setView(panel)
        .setNegativeButton("取消",null).setPositiveButton("下一步",null).create();
    owner.showRounded(prompt);
    owner.prepareRoundedInputDialog(prompt,input);
    prompt.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v->{
      String value=input.getText().toString().trim();
      if(value.isEmpty()){input.setError("请输入域名 ID");return;}
      prompt.dismiss();
      confirm("设置默认域名？","将把 ID 为 “"+value+"” 的域名设为默认。写入后重新读取域名列表核对。",
          ()->writeDefaultDomain(value));
    });
  }

  private void confirmSharing(boolean enable){
    confirm(enable?"开启共享？":"关闭共享？",
        enable?"将开启文件共享。当前共享状态没有独立读取接口，请以官方返回结果为准。"
            :"将关闭文件共享。当前共享状态没有独立读取接口，请以官方返回结果为准。",
        ()->writeSharing(enable));
  }

  private void confirm(String title,String message,Runnable apply){
    AlertDialog prompt=new AlertDialog.Builder(owner).setTitle(title).setMessage(message)
        .setNegativeButton("取消",null)
        .setPositiveButton("确认",(d,w)->apply.run()).create();
    owner.showRounded(prompt);
  }

  private LinearLayout dialogPanel(){
    LinearLayout panel=new LinearLayout(owner);
    panel.setOrientation(LinearLayout.VERTICAL);
    panel.setPadding(owner.dp(18),owner.dp(8),owner.dp(18),owner.dp(8));
    return panel;
  }

  private void writeDomain(final String domain){
    write("域名已提交，已重新读取域名列表",s->s.setDomain(domain));
  }

  private void writeDefaultDomain(final String domainId){
    write("默认域名已提交，已重新读取域名列表",s->s.setDefaultDomain(domainId));
  }

  private void writeSharing(final boolean enable){
    write(enable?"官方已确认开启共享":"官方已确认关闭共享",s->{
      s.setSharing(enable);
      return null;
    });
  }

  /**
   * Runs one already-confirmed write on the host executor with the settings handle captured at
   * click time, so a later account switch or dialog dismissal cannot rebind or drop the request.
   * A returned domain list is rendered; {@code null} means the reply carried no readable list.
   */
  private void write(final String successMessage,final Work action){
    if(busy)return;
    final CloudAccountSettingsProtocol.Settings handle=settings;
    if(handle==null){
      owner.showNotice("请先刷新域名列表",true);
      return;
    }
    final int request=++generation;
    enabled(false);
    owner.io.execute(()->{
      JSONObject outcome=null;
      boolean succeeded=false;
      String error="";
      try{
        outcome=action.run(handle);
        succeeded=true;
      }catch(Exception failure){
        error=message(failure);
      }
      final JSONObject done=outcome;
      final boolean ok=succeeded;
      final String problem=error;
      owner.runOnUiThread(()->{
        if(!current(request))return;
        enabled(true);
        if(!ok){
          owner.showNotice(problem.isEmpty()?"操作失败，请稍后重试":problem,true);
          return;
        }
        owner.showNotice(successMessage,false);
        if(done!=null)render(done);
      });
    });
  }

  /** Protocol messages are already user-facing Chinese; keep them instead of a generic fallback. */
  private String message(Exception failure){
    if(failure instanceof CloudFilesClient.CloudException){
      String text=failure.getMessage();
      if(text!=null&&!text.trim().isEmpty())return text.trim();
    }
    return owner.friendlyError(failure);
  }

  interface Work { JSONObject run(CloudAccountSettingsProtocol.Settings settings)throws Exception; }
}
