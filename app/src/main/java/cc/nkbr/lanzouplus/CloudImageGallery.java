package cc.nkbr.lanzouplus;

import android.content.Context;
import android.content.SharedPreferences;
import java.io.File;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.json.JSONObject;

/** Downloads cloud images once and resolves adjacent images as the viewer requests them. */
final class CloudImageGallery {
  private static final Object DOWNLOAD_LOCK=new Object();
  private static final long UNKNOWN_MODIFIED_CACHE_MS=5*60*1000L;
  private CloudImageGallery(){}
  static boolean isImage(CloudFilesClient.CloudItem item){
    if(item==null||item.folder)return false;
    String n=item.name.toLowerCase(Locale.ROOT);
    return n.endsWith(".jpg")||n.endsWith(".jpeg")||n.endsWith(".png")||n.endsWith(".webp")||n.endsWith(".gif")||n.endsWith(".bmp")||n.endsWith(".heic")||n.endsWith(".heif")||n.endsWith(".avif");
  }
  static void show(MainActivity owner,CloudFilesClient client,CloudFilesClient.Provider provider,List<CloudFilesClient.CloudItem> source,CloudFilesClient.CloudItem selected){
    List<File> keys=new ArrayList<>();Map<File,CloudFilesClient.CloudItem> items=new LinkedHashMap<>();int selectedIndex=-1;
    for(CloudFilesClient.CloudItem item:source){if(!isImage(item))continue;File key=new File(owner.getCacheDir(),"cloud-image-slot-"+keys.size());if(item.id.equals(selected.id))selectedIndex=keys.size();keys.add(key);items.put(key,item);}
    if(selectedIndex<0)return;final int index=selectedIndex;
    owner.io.execute(()->{try{String account=client.currentAccountName(provider);if(client.provider()!=provider)throw new java.io.IOException("\u4e91\u8d26\u53f7\u5df2\u5207\u6362");
      CloudFilesClient.BoundSession bound=client.bindAccount(provider,account);owner.runOnUiThread(()->CloudImageViewer.show(owner,keys,index,new CloudImageViewer.ImageResolver(){
        public File resolve(File key)throws Exception{return CloudImageGallery.resolve(owner,client,provider,account,bound,items.get(key),false);}
        public boolean supportsRefresh(){return true;}
        public File refresh(File key)throws Exception{return CloudImageGallery.resolve(owner,client,provider,account,bound,items.get(key),true);}
      }));
    }catch(Exception error){owner.runOnUiThread(()->owner.showNotice("\u65e0\u6cd5\u8bfb\u53d6\u4e91\u8d26\u53f7",true));}});
  }
  static boolean isArchive(CloudFilesClient.CloudItem item){return item!=null&&!item.folder&&item.name.toLowerCase(Locale.ROOT).endsWith(".zip");}
  static void showArchive(MainActivity owner,CloudFilesClient client,CloudFilesClient.Provider provider,CloudFilesClient.CloudItem item,Runnable details){
    android.widget.EditText input=new android.widget.EditText(owner);input.setSingleLine(true);input.setInputType(android.text.InputType.TYPE_CLASS_TEXT|android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD);input.setHint("\u538b\u7f29\u5305\u5bc6\u7801");
    android.app.AlertDialog dialog=new android.app.AlertDialog.Builder(owner).setTitle("\u67e5\u770b\u538b\u7f29\u5305\u56fe\u7247").setView(input).setNegativeButton("\u53d6\u6d88",null).setNeutralButton("\u6587\u4ef6\u8be6\u60c5",(d,w)->details.run()).setPositiveButton("\u67e5\u770b",(d,w)->{
      char[] password=input.getText().toString().toCharArray();input.setText("");
      owner.io.execute(()->{try{
        String account=client.currentAccountName(provider);CloudFilesClient.BoundSession bound=client.bindAccount(provider,account);File archive=resolve(owner,client,provider,account,bound,item,false);
        List<CloudEncryptedZip.ImageEntry> images=CloudEncryptedZip.listImages(archive,password);if(images.isEmpty())throw new java.io.IOException("\u538b\u7f29\u5305\u5185\u6ca1\u6709\u56fe\u7247");
        String encoded=android.util.Base64.encodeToString((provider.name()+"|"+account+"|"+item.id).getBytes(java.nio.charset.StandardCharsets.UTF_8),android.util.Base64.URL_SAFE|android.util.Base64.NO_WRAP|android.util.Base64.NO_PADDING);
        File root=new File(owner.getCacheDir(),"cloud-archive/"+encoded+"-"+archive.lastModified()+"-"+archive.length());List<File> keys=new ArrayList<>();Map<File,String> names=new LinkedHashMap<>();
        for(CloudEncryptedZip.ImageEntry image:images){File key=new File(root,"slot-"+keys.size());keys.add(key);names.put(key,image.name());}
        owner.runOnUiThread(()->{if(owner.activityClosing()){java.util.Arrays.fill(password,'\0');return;}CloudImageViewer.show(owner,keys,0,new CloudImageViewer.ImageResolver(){
          public File resolve(File key)throws Exception{synchronized(DOWNLOAD_LOCK){String name=names.get(key);if(name==null)throw new java.io.IOException("\u56fe\u7247\u4e0d\u5b58\u5728");File target=new File(root,name).getCanonicalFile();String prefix=root.getCanonicalPath()+File.separator;if(!target.getPath().startsWith(prefix))throw new java.io.IOException("\u538b\u7f29\u5305\u8def\u5f84\u4e0d\u5b89\u5168");if(target.isFile())return target;return CloudEncryptedZip.extractImageToCache(archive,name,password,root);}}
          public void close(){java.util.Arrays.fill(password,'\0');}
        });});
      }catch(Exception error){java.util.Arrays.fill(password,'\0');owner.runOnUiThread(()->owner.showNotice("\u65e0\u6cd5\u6253\u5f00\u538b\u7f29\u5305\u56fe\u7247\uff0c\u8bf7\u68c0\u67e5\u5bc6\u7801\u548c\u6587\u4ef6",true));}});
    }).create();owner.showRounded(dialog);
  }
  private static String stableModified(String value){
    if(value==null)return "";
    return value.matches("(?s).*\\d{4}[-/\u5e74]\\d{1,2}.*")||value.matches("\\d{10,13}")?value:"";
  }
  private static File resolve(MainActivity owner,CloudFilesClient client,CloudFilesClient.Provider provider,String account,CloudFilesClient.BoundSession bound,CloudFilesClient.CloudItem item,boolean force)throws Exception{
    if(item==null)throw new java.io.IOException("\u56fe\u7247\u4e0d\u5b58\u5728");
    synchronized(DOWNLOAD_LOCK){
      if(client.provider()!=provider||!account.equals(client.currentAccountName(provider)))throw new java.io.IOException("\u4e91\u8d26\u53f7\u5df2\u5207\u6362");
      SharedPreferences prefs=owner.getSharedPreferences("cloud-image-files-v1",Context.MODE_PRIVATE);
      String key=provider.name()+"|"+account+"|"+item.id;
      String saved=prefs.getString(key,"");
      if(!force&&!saved.isEmpty())try{JSONObject value=new JSONObject(saved);File existing=new File(value.optString("path",""));String modified=stableModified(item.modifiedAt);long cachedAt=value.optLong("cachedAt",0);boolean fresh=!modified.isEmpty()||(cachedAt>0&&System.currentTimeMillis()>=cachedAt&&System.currentTimeMillis()-cachedAt<UNKNOWN_MODIFIED_CACHE_MS);if(existing.isFile()&&fresh&&value.optLong("size",-1)==item.size&&value.optString("modified","").equals(modified))return existing;}catch(org.json.JSONException ignored){}
      File directory=owner.selectedDownloadDirectory();
      String name=item.name;
      if(name.isEmpty()||name.equals(".")||name.equals("..")||name.contains("/")||name.contains("\\"))throw new java.io.IOException("文件名无效");
      File target=new File(directory,name);
      if(target.exists()){
        int dot=name.lastIndexOf('.');String suffix=" ("+java.util.UUID.randomUUID().toString()+")";
        target=new File(directory,dot>0?name.substring(0,dot)+suffix+name.substring(dot):name+suffix);
      }
      CloudFilesClient.DownloadResult result=bound.download(item,target.getAbsolutePath());
      if(client.provider()!=provider||!account.equals(client.currentAccountName(provider)))throw new java.io.IOException("\u4e91\u8d26\u53f7\u5df2\u5207\u6362");
      File file=new File(result.path);if(!file.isFile())throw new java.io.IOException("\u56fe\u7247\u4e0b\u8f7d\u5931\u8d25");
      JSONObject value=new JSONObject().put("path",file.getAbsolutePath()).put("size",item.size).put("modified",stableModified(item.modifiedAt)).put("cachedAt",System.currentTimeMillis());
      prefs.edit().putString(key,value.toString()).apply();owner.runOnUiThread(()->owner.recordCloudDownload(item,result));return file;
    }
  }
}
