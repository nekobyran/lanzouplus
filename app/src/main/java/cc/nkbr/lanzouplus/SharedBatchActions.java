package cc.nkbr.lanzouplus;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ResolveInfo;
import android.net.Uri;
import android.webkit.MimeTypeMap;
import android.widget.Toast;
import java.util.ArrayList;
import java.util.List;

/** Original batch toolbar actions, shared by live downloads and history on the calling Activity. */
final class SharedBatchActions {
  private SharedBatchActions(){}
  static DownloadAggregateView.BatchHost host(Activity host){
    List<DownloadAggregateView.BatchButton> selection=new ArrayList<>(),batch=new ArrayList<>();
    selection.add(button(R.drawable.ic_folder,"打开保存路径",r->savedDirectory(host)));
    selection.add(button(R.drawable.ic_open_with,"更多方式打开所选文件",r->more(host,r)));
    selection.add(button(R.drawable.ic_copy,"复制所选原链接",r->copy(host,r)));
    selection.add(button(R.drawable.ic_share_file,"分享所选本地文件",r->shareFiles(host,r)));
    selection.add(button(R.drawable.ic_folder_open,"打开所选文件所在路径",r->locations(host,r)));
    selection.add(button(R.drawable.ic_install,"安装所选 APK",r->{for(DownloadAggregateView.Record item:r)if(item.completed()&&item.name().toLowerCase(java.util.Locale.ROOT).endsWith(".apk"))item.perform(host,DownloadHistoryCenter.ACTION_OPEN);}));
    selection.add(button(R.drawable.ic_pause,"暂停或继续所选未完成下载任务",r->apply(host,r,0)));
    selection.add(button(R.drawable.ic_close,"取消所选未完成下载任务",r->apply(host,r,1)));
    selection.add(button(R.drawable.ic_delete_record,"删除所选记录",r->confirm(host,r,2)));
    selection.add(button(R.drawable.ic_delete_file,"删除所选本地文件",r->confirm(host,r,3)));
    selection.add(button(R.drawable.ic_share,"分享所选原链接",r->chooseApp(host,new Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT,links(r)),"分享所选原链接")));
    batch.add(button(R.drawable.ic_pause,"暂停批量下载全部未完成项目",r->apply(host,r,0)));
    batch.add(button(R.drawable.ic_close,"取消批量下载全部未完成项目",r->apply(host,r,1)));
    batch.add(button(R.drawable.ic_delete_record,"删除批量下载全部记录",r->confirm(host,r,2)));
    batch.add(button(R.drawable.ic_delete_file,"删除批量下载全部文件",r->confirm(host,r,3)));
    return new DownloadAggregateView.BatchHost(selection,batch,record->{
      MainActivity owner=host instanceof MainActivity?(MainActivity)host:DownloadHistoryCenter.liveOwner();
      MainActivity.DownloadEntry entry=record.liveEntry();if(owner==null||entry==null)return null;
      android.widget.ImageView icon=new android.widget.ImageView(host);icon.setImageResource(owner.downloadFallbackIconResource(entry));icon.setScaleType(android.widget.ImageView.ScaleType.CENTER_CROP);owner.loadImage(entry.iconUrl,icon);return icon;
    });
  }
  private static DownloadAggregateView.BatchButton button(int icon,String text,java.util.function.Consumer<List<DownloadAggregateView.Record>> action){
    return DownloadAggregateView.BatchButton.of(icon,text,(item,records)->action.accept(new ArrayList<>(records)));
  }
  private static void apply(Activity host,List<DownloadAggregateView.Record> records,int action){
    notice(host,DownloadAggregateView.applyAction(host,new DownloadAggregateView.Item("selection",records),action));
  }
  private static void confirm(Activity host,List<DownloadAggregateView.Record> records,int action){
    AlertDialog dialog=new AlertDialog.Builder(host).setTitle(action==2?"删除所选记录？":"删除所选本地文件？")
        .setMessage(action==2?"本地文件保留；进行中的任务会先取消。":"删除成功后清理对应记录，删除失败的记录会保留。")
        .setNegativeButton("取消",null).setPositiveButton("删除",(d,w)->apply(host,records,action)).create();show(host,dialog);
  }
  private static String links(List<DownloadAggregateView.Record> records){
    StringBuilder text=new StringBuilder();for(DownloadAggregateView.Record r:records)if(!r.shareUrl().isEmpty()){
      if(text.length()>0)text.append('\n');text.append(r.shareUrl());if(!r.password().isEmpty())text.append("\n密码：").append(r.password());}return text.toString();
  }
  private static void copy(Activity host,List<DownloadAggregateView.Record> records){
    ((ClipboardManager)host.getSystemService(Context.CLIPBOARD_SERVICE)).setPrimaryClip(ClipData.newPlainText("下载原链接",links(records)));notice(host,"已复制所选原链接");
  }
  private static void shareFiles(Activity host,List<DownloadAggregateView.Record> records){
    ArrayList<Uri> uris=new ArrayList<>();boolean allApk=true;for(DownloadAggregateView.Record r:records)if(r.completed()&&!r.localUri().isEmpty()){uris.add(fileUri(host,r.localUri()));allApk&=r.name().toLowerCase(java.util.Locale.ROOT).endsWith(".apk");}
    if(uris.isEmpty()){notice(host,"所选项目没有本地文件");return;}
    Intent intent=new Intent(uris.size()==1?Intent.ACTION_SEND:Intent.ACTION_SEND_MULTIPLE).setType(allApk?"application/vnd.android.package-archive":"application/octet-stream").addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
    if(uris.size()==1)intent.putExtra(Intent.EXTRA_STREAM,uris.get(0));else intent.putParcelableArrayListExtra(Intent.EXTRA_STREAM,uris);
    ClipData clip=ClipData.newRawUri("下载文件",uris.get(0));for(int i=1;i<uris.size();i++)clip.addItem(new ClipData.Item(uris.get(i)));intent.setClipData(clip);chooseApp(host,intent,"分享所选本地文件");
  }
  private static void more(Activity host,List<DownloadAggregateView.Record> records){
    List<DownloadAggregateView.Record> files=new ArrayList<>();for(DownloadAggregateView.Record r:records)if(!r.localUri().isEmpty())files.add(r);
    if(files.isEmpty()){notice(host,"所选项目没有本地文件");return;}if(files.size()==1){openMore(host,files.get(0));return;}
    String[] names=new String[files.size()];for(int i=0;i<names.length;i++)names[i]=files.get(i).name();
    show(host,new AlertDialog.Builder(host).setTitle("选择要打开的文件").setItems(names,(d,w)->openMore(host,files.get(w))).setNegativeButton("取消",null).create());
  }
  private static void openMore(Activity host,DownloadAggregateView.Record record){
    String mime=MimeTypeMap.getSingleton().getMimeTypeFromExtension(DownloadHistoryCenter.extensionOf(record.name()));
    chooseApp(host,new Intent(Intent.ACTION_VIEW).setDataAndType(fileUri(host,record.localUri()),mime==null?"application/octet-stream":mime).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION),"更多方式打开");
  }
  private static void savedDirectory(Activity host){
    String path=host.getSharedPreferences("download_destination",Context.MODE_PRIVATE).getString("path","");
    openDirectory(host,Uri.fromFile(path.isEmpty()?android.os.Environment.getExternalStoragePublicDirectory(android.os.Environment.DIRECTORY_DOWNLOADS):new java.io.File(path)));
  }
  private static void locations(Activity host,List<DownloadAggregateView.Record> records){
    java.util.LinkedHashSet<Uri> paths=new java.util.LinkedHashSet<>();
    for(DownloadAggregateView.Record record:records){
      if(!record.parentUri().isEmpty()){paths.add(Uri.parse(record.parentUri()));continue;}
      if(record.localUri().isEmpty())continue;Uri uri=Uri.parse(record.localUri());
      if("file".equals(uri.getScheme())||uri.getScheme()==null){java.io.File parent=new java.io.File(uri.getPath()).getParentFile();if(parent!=null)paths.add(Uri.fromFile(parent));}
    }
    if(paths.isEmpty()){notice(host,"无法识别所选文件所在路径");return;}
    Uri[] values=paths.toArray(new Uri[0]);if(values.length==1){openDirectory(host,values[0]);return;}
    String[] labels=new String[values.length];for(int i=0;i<values.length;i++)labels[i]=values[i].toString();
    show(host,new AlertDialog.Builder(host).setTitle("选择保存路径").setItems(labels,(d,w)->openDirectory(host,values[w])).setNegativeButton("取消",null).create());
  }
  private static void openDirectory(Activity host,Uri uri){
    if("file".equals(uri.getScheme())){
      java.io.File directory=new java.io.File(uri.getPath()),downloads=android.os.Environment.getExternalStoragePublicDirectory(android.os.Environment.DIRECTORY_DOWNLOADS);
      if(directory.equals(downloads)){host.startActivity(new Intent(android.app.DownloadManager.ACTION_VIEW_DOWNLOADS));return;}
      ((ClipboardManager)host.getSystemService(Context.CLIPBOARD_SERVICE)).setPrimaryClip(ClipData.newPlainText("下载保存路径",directory.getAbsolutePath()));notice(host,"自定义保存路径已复制："+directory.getAbsolutePath());
      chooseApp(host,new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_APP_FILES),"打开保存路径");return;
    }
    chooseApp(host,new Intent(Intent.ACTION_VIEW).setDataAndType(uri,"vnd.android.document/directory").addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION),"打开保存路径");
  }
  private static void chooseApp(Activity host,Intent intent,String title){
    List<ResolveInfo> handlers=host.getPackageManager().queryIntentActivities(intent,0);if(handlers.isEmpty()){notice(host,"没有可处理此项目的应用");return;}
    String[] names=new String[handlers.size()];for(int i=0;i<names.length;i++)names[i]=handlers.get(i).loadLabel(host.getPackageManager()).toString();
    show(host,new AlertDialog.Builder(host).setTitle(title).setItems(names,(d,w)->{ResolveInfo app=handlers.get(w);host.startActivity(new Intent(intent).setComponent(new ComponentName(app.activityInfo.packageName,app.activityInfo.name)));}).setNegativeButton("取消",null).create());
  }
  private static void show(Activity host,AlertDialog dialog){SharedPopupSurface.styleDialog(host,dialog,new SharedPopupSurface.Theme(host),null);dialog.show();}
  private static Uri fileUri(Activity host,String raw){
    Uri uri=Uri.parse(raw);if(uri.getScheme()!=null&&!"file".equals(uri.getScheme()))return uri;
    String path=uri.getPath();String encoded=android.util.Base64.encodeToString(path.getBytes(java.nio.charset.StandardCharsets.UTF_8),android.util.Base64.URL_SAFE|android.util.Base64.NO_WRAP|android.util.Base64.NO_PADDING);
    return Uri.parse("content://"+host.getPackageName()+".downloads/shared/"+encoded);
  }
  private static void notice(Activity host,String text){Toast.makeText(host,text,Toast.LENGTH_SHORT).show();}
}
