package cc.nkbr.lanzouplus;

import android.app.AlertDialog;
import android.os.Environment;
import android.widget.*;
import java.io.File;
import java.util.*;

/** App-owned file browser limited to locations the application can already read. */
final class MarketFilePicker {
  static void choose(MainActivity owner,String suffixes,java.util.function.Consumer<File> selected){
    LinearLayout panel=new LinearLayout(owner);panel.setOrientation(LinearLayout.VERTICAL);panel.setPadding(owner.dp(16),owner.dp(6),owner.dp(16),owner.dp(6));
    TextView path=owner.text("",12,owner.MUTED);panel.addView(path);LinearLayout actions=new LinearLayout(owner);Button up=owner.toolbarTextButton("上一级"),app=owner.toolbarTextButton("应用文件");actions.addView(up);actions.addView(app);panel.addView(actions);
    LinearLayout list=new LinearLayout(owner);list.setOrientation(LinearLayout.VERTICAL);panel.addView(owner.limitedDialogScroll(list,350));
    AlertDialog prompt=new AlertDialog.Builder(owner).setTitle("选择文件").setView(panel).setNegativeButton("取消",null).create();File initial=owner.storageAccessGranted()?Environment.getExternalStorageDirectory():owner.getExternalFilesDir(null);File[] current={initial};int[] revision={0};Runnable[] render={null};
    render[0]=()->{int token=++revision[0];File directory=current[0];path.setText(directory.getAbsolutePath());list.removeAllViews();list.addView(owner.text("正在读取…",13,owner.MUTED));owner.io.execute(()->{File[] files=directory.listFiles();if(files!=null)Arrays.sort(files,(a,b)->a.isDirectory()!=b.isDirectory()?(a.isDirectory()?-1:1):a.getName().compareToIgnoreCase(b.getName()));owner.runOnUiThread(()->{if(token!=revision[0]||!prompt.isShowing())return;list.removeAllViews();if(files==null){list.addView(owner.text("此路径暂时无法访问，可在设置中申请文件权限",13,owner.MUTED));return;}for(File file:files){boolean match=suffixes.isEmpty();for(String suffix:suffixes.split(","))if(file.getName().toLowerCase(Locale.ROOT).endsWith(suffix))match=true;if(!file.isDirectory()&&!match)continue;Button row=owner.toolbarTextButton((file.isDirectory()?"目录 · ":"")+file.getName());row.setGravity(android.view.Gravity.START|android.view.Gravity.CENTER_VERTICAL);row.setOnClickListener(v->{if(file.isDirectory()){current[0]=file;render[0].run();}else{prompt.dismiss();selected.accept(file);}});list.addView(row,new LinearLayout.LayoutParams(-1,owner.dp(48)));}});});};
    up.setOnClickListener(v->{File parent=current[0].getParentFile();if(parent!=null){current[0]=parent;render[0].run();}});app.setOnClickListener(v->{current[0]=owner.getExternalFilesDir(null);render[0].run();});owner.showRounded(prompt);render[0].run();
  }
}
