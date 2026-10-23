package cc.nkbr.lanzouplus;

import android.app.AlertDialog;
import android.os.Environment;
import android.widget.*;
import java.io.File;
import java.util.*;

/** App-owned file browser limited to locations the application can already read. */
final class MarketFilePicker {
  private static final int IMAGE_REQUEST=18491;
  private static java.util.function.Consumer<String> imageSelected;
  static android.content.Intent imageIntent(){
    return new android.content.Intent(android.content.Intent.ACTION_OPEN_DOCUMENT)
        .addCategory(android.content.Intent.CATEGORY_OPENABLE).setType("image/*")
        .addFlags(android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION|android.content.Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION);
  }
  static void chooseImage(MainActivity owner,java.util.function.Consumer<String> selected){
    imageSelected=selected;
    try{owner.startActivityForResult(imageIntent(),IMAGE_REQUEST);}
    catch(android.content.ActivityNotFoundException unavailable){imageSelected=null;owner.showNotice("系统没有可用的图片选择器",true);}
  }
  static boolean onActivityResult(MainActivity owner,int request,int result,android.content.Intent data){
    if(request!=IMAGE_REQUEST)return false;
    java.util.function.Consumer<String> selected=imageSelected;imageSelected=null;
    if(result!=android.app.Activity.RESULT_OK||data==null||data.getData()==null)return true;
    android.net.Uri uri=data.getData();
    try{if((data.getFlags()&android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION)!=0)owner.getContentResolver().takePersistableUriPermission(uri,android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION);}catch(SecurityException ignored){}
    if(selected!=null)selected.accept(uri.toString());
    return true;
  }
  static void choose(MainActivity owner,String suffixes,java.util.function.Consumer<File> selected){
    new Browser(owner,suffixes,false,false,files->selected.accept(files.get(0))).show();
  }
  static void chooseMultiple(MainActivity owner,String suffixes,java.util.function.Consumer<List<File>> selected){
    new Browser(owner,suffixes,true,false,selected).show();
  }
  static void chooseDirectory(MainActivity owner,java.util.function.Consumer<File> selected){
    new Browser(owner,"",false,true,files->selected.accept(files.get(0))).show();
  }

  private static final class Browser extends BaseAdapter {
    final MainActivity owner;
    final String suffixes;
    final boolean multiple,directories;
    final java.util.function.Consumer<List<File>> selected;
    final Set<File> chosen=new LinkedHashSet<>();
    final List<File> files=new ArrayList<>();
    File current;
    TextView path,message;
    ListView list;
    AlertDialog dialog;
    int revision;
    Browser(MainActivity owner,String suffixes,boolean multiple,boolean directories,java.util.function.Consumer<List<File>> selected){
      this.owner=owner;this.suffixes=suffixes;this.multiple=multiple;this.directories=directories;this.selected=selected;
      current=owner.storageAccessGranted()?Environment.getExternalStorageDirectory():owner.getExternalFilesDir(null);
    }
    void show(){
      LinearLayout panel=new LinearLayout(owner);panel.setOrientation(LinearLayout.VERTICAL);panel.setPadding(owner.dp(16),0,owner.dp(16),0);
      path=owner.text("",12,owner.MUTED);path.setMaxLines(2);panel.addView(path);
      LinearLayout actions=new LinearLayout(owner);Button up=owner.toolbarTextButton("上一级"),app=owner.toolbarTextButton("应用文件");actions.addView(up);actions.addView(app);panel.addView(actions);
      message=owner.text("",12,owner.MUTED);panel.addView(message);
      list=new ListView(owner);list.setAdapter(this);list.setDividerHeight(owner.dp(1));
      int height=Math.min(owner.dp(350),Math.max(owner.dp(100),owner.getResources().getDisplayMetrics().heightPixels-owner.dp(260)));
      panel.addView(list,new LinearLayout.LayoutParams(-1,height));
      AlertDialog.Builder builder=new AlertDialog.Builder(owner).setTitle(directories?"选择文件夹":multiple?"选择多个文件":"选择文件").setView(panel).setNegativeButton("取消",null);
      if(directories||multiple)builder.setPositiveButton(directories?"选择此文件夹":"选择 (0)",null);
      dialog=builder.create();owner.showRounded(dialog);
      if(directories||multiple)dialog.getButton(-1).setOnClickListener(v->{
        if(multiple&&chosen.isEmpty())return;
        dialog.dismiss();selected.accept(directories?Collections.singletonList(current):new ArrayList<>(chosen));
      });
      list.setOnItemClickListener((p,v,index,id)->{
        File file=files.get(index);
        if(file.isDirectory()){current=file;load();}
        else if(multiple){if(!chosen.add(file))chosen.remove(file);notifyDataSetChanged();selection();}
        else{dialog.dismiss();selected.accept(Collections.singletonList(file));}
      });
      up.setOnClickListener(v->{File parent=current.getParentFile();if(parent!=null){current=parent;load();}});
      app.setOnClickListener(v->{current=owner.getExternalFilesDir(null);load();});load();selection();
    }
    void selection(){if(multiple){Button button=dialog.getButton(-1);button.setText("选择 ("+chosen.size()+")");button.setEnabled(!chosen.isEmpty());}}
    void load(){
      int token=++revision;File directory=current;path.setText(directory.getAbsolutePath());message.setText("正在读取…");files.clear();notifyDataSetChanged();
      if(directories)dialog.getButton(-1).setEnabled(false);
      owner.io.execute(()->{
        File[] found=directory.listFiles();List<File> available=new ArrayList<>();
        if(found!=null)for(File file:found){
          boolean match=suffixes.isEmpty();for(String suffix:suffixes.toLowerCase(Locale.ROOT).split(","))if(file.getName().toLowerCase(Locale.ROOT).endsWith(suffix))match=true;
          if(file.isDirectory()||(!directories&&match))available.add(file);
        }
        available.sort((a,b)->a.isDirectory()!=b.isDirectory()?(a.isDirectory()?-1:1):a.getName().compareToIgnoreCase(b.getName()));
        owner.runOnUiThread(()->{
          if(token!=revision||!dialog.isShowing())return;files.addAll(available);notifyDataSetChanged();
          message.setText(found==null?"此目录无法访问，可在设置申请文件权限":available.isEmpty()?"此目录没有可选择的项目":available.size()+" 个项目");
          if(directories)dialog.getButton(-1).setEnabled(found!=null);
        });
      });
    }
    public int getCount(){return files.size();}public Object getItem(int position){return files.get(position);}public long getItemId(int position){return position;}
    static final class Row {TextView label;CheckBox check;}
    public android.view.View getView(int position,android.view.View recycled,android.view.ViewGroup parent){
      File file=files.get(position);LinearLayout layout;Row row;
      if(recycled instanceof LinearLayout&&recycled.getTag() instanceof Row){layout=(LinearLayout)recycled;row=(Row)layout.getTag();}
      else{
        layout=new LinearLayout(owner);layout.setGravity(android.view.Gravity.CENTER_VERTICAL);layout.setPadding(owner.dp(8),0,owner.dp(8),0);layout.setLayoutParams(new AbsListView.LayoutParams(-1,owner.dp(48)));
        row=new Row();row.label=owner.text("",14,owner.TEXT);row.label.setMaxLines(2);row.label.setEllipsize(android.text.TextUtils.TruncateAt.END);layout.addView(row.label,new LinearLayout.LayoutParams(0,-2,1));
        row.check=new CheckBox(owner);row.check.setClickable(false);row.check.setFocusable(false);row.check.setButtonTintList(new android.content.res.ColorStateList(new int[][]{new int[]{android.R.attr.state_checked},new int[0]},new int[]{owner.PRIMARY,owner.MUTED}));layout.addView(row.check,new LinearLayout.LayoutParams(owner.dp(48),owner.dp(48)));layout.setTag(row);
      }
      row.label.setText((file.isDirectory()?"目录 · ":"")+file.getName());row.check.setVisibility(multiple&&!file.isDirectory()?android.view.View.VISIBLE:android.view.View.GONE);row.check.setChecked(chosen.contains(file));
      return layout;
    }
  }
}
