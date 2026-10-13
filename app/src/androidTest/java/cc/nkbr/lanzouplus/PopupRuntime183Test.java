package cc.nkbr.lanzouplus;

import static org.junit.Assert.*;
import android.app.Instrumentation;
import android.app.UiAutomation;
import android.content.Context;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.Rect;
import android.os.SystemClock;
import android.view.accessibility.AccessibilityNodeInfo;
import androidx.test.core.app.ActivityScenario;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import java.io.File;
import java.io.FileOutputStream;
import java.util.Collections;
import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;
import org.junit.runner.RunWith;

/** Real Android windows, using only synthetic source and download records. */
@RunWith(AndroidJUnit4.class)
public class PopupRuntime183Test {
  final Instrumentation instrumentation=InstrumentationRegistry.getInstrumentation();
  final Context context=instrumentation.getTargetContext();
  final UiAutomation ui=instrumentation.getUiAutomation();
  final String variant=InstrumentationRegistry.getArguments().getString("caseName","portrait-light");
  final File output=new File(context.getExternalFilesDir(null),"popup183/"+variant);

  void seedStores()throws Exception{
    context.getSharedPreferences("user-sources-v1",0).edit().clear().commit();
    JSONObject folder=new JSONObject().put("id","folder183").put("parent","").put("searchable",false).put("kind",Models.MEMBER_FOLDER).put("title","软件").put("url","");
    JSONObject source=new JSONObject().put("v",2).put("id","local:composite183").put("kind",Models.SOURCE_COMPOSITE).put("title","自建合集").put("url","").put("members",new JSONArray().put(folder));
    context.getSharedPreferences("user-sources-v1",0).edit().putString("sources",new JSONObject().put("version",2).put("sources",new JSONArray().put(source)).toString()).commit();
    context.getSharedPreferences("source_categories",0).edit().putString("items",new JSONArray().put(new JSONObject().put("name","工具").put("ids",new JSONArray().put("local:composite183"))).toString()).commit();
    context.getSharedPreferences("web-browser-v2",0).edit().clear().putString("history","[{\"t\":\"测试网页\",\"u\":\"https://example.invalid/history183\",\"a\":183}]").commit();
    context.getSharedPreferences("MainActivity",0).edit().putBoolean("accepted",true).commit();
    context.getSharedPreferences("LegacyPopup183RuntimeActivity",0).edit().putBoolean("accepted",true).commit();
    context.getSharedPreferences("search_settings-v1",0).edit().putBoolean("auto_expand",false).putString("lanzou_base_origin","https://verify183.lanzoum.com").putBoolean("lanzou_base_origin_verified",true).putBoolean("lanzou_base_origin_explicit",true).commit();
  }
  void seedDownloads(MainActivity main){
    main.downloadEntries.clear();
    for(int i=0;i<2;i++){MainActivity.DownloadEntry entry=new MainActivity.DownloadEntry();entry.name="示例软件"+i+".apk";entry.state=MainActivity.DOWNLOAD_COMPLETED;entry.batchId="batch183";entry.batchTitle="测试批量183";entry.createdAt=183000+i;entry.completedAt=183100+i;entry.percent=100;entry.downloadedBytes=entry.totalBytes=4096;main.downloadEntries.add(entry);}
  }
  void idle(){instrumentation.waitForIdleSync();SystemClock.sleep(350);}
  AccessibilityNodeInfo root(){idle();AccessibilityNodeInfo root=ui.getRootInActiveWindow();assertNotNull("Active Android window",root);return root;}
  AccessibilityNodeInfo find(AccessibilityNodeInfo node,String value,boolean description){
    CharSequence text=description?node.getContentDescription():node.getText();if(text!=null&&value.contentEquals(text))return node;
    for(int i=0;i<node.getChildCount();i++){AccessibilityNodeInfo child=node.getChild(i);if(child==null)continue;AccessibilityNodeInfo result=find(child,value,description);if(result!=null)return result;child.recycle();}return null;
  }
  AccessibilityNodeInfo required(String value,boolean description){AccessibilityNodeInfo found=find(root(),value,description);assertNotNull(value,found);return found;}
  void click(String value,boolean description,boolean hold){
    AccessibilityNodeInfo node=required(value,description);
    while(node!=null&&!(hold?node.isLongClickable():node.isClickable()))node=node.getParent();
    assertNotNull("Interactive parent: "+value,node);assertTrue(node.performAction(hold?AccessibilityNodeInfo.ACTION_LONG_CLICK:AccessibilityNodeInfo.ACTION_CLICK));idle();
  }
  void visible(String value,boolean description){AccessibilityNodeInfo node=required(value,description);Rect rect=new Rect();node.getBoundsInScreen(rect);Rect viewport=bounds();assertTrue("Control is on the visible Android window: "+value,node.isVisibleToUser());assertTrue("Control bounds fit the window: "+value,viewport.contains(rect));}
  Rect bounds(){Rect bounds=new Rect();root().getBoundsInScreen(bounds);return bounds;}
  void screenshot(String name)throws Exception{
    idle();output.mkdirs();Bitmap screen=ui.takeScreenshot();assertNotNull(screen);
    try(FileOutputStream stream=new FileOutputStream(new File(output,name+"-screen.png"))){assertTrue(screen.compress(Bitmap.CompressFormat.PNG,100,stream));}
    Rect area=bounds();area.intersect(0,0,screen.getWidth(),screen.getHeight());assertTrue(area.width()>0&&area.height()>0);
    Bitmap window=Bitmap.createBitmap(screen,area.left,area.top,area.width(),area.height());
    try(FileOutputStream stream=new FileOutputStream(new File(output,name+"-window.png"))){assertTrue(window.compress(Bitmap.CompressFormat.PNG,100,stream));}
    window.recycle();screen.recycle();
  }
  void stable(){Rect before=bounds();SystemClock.sleep(400);assertEquals("Window geometry must not jump after opening",before,bounds());}
  void dismiss(){click("取消",false,false);}

  @Test public void realWindowsKeepPathBatchMaterialAndNavigationContracts()throws Exception{
    seedStores();
    try(ActivityScenario<MainActivity> scenario=ActivityScenario.launch(MainActivity.class)){
      scenario.onActivity(main->{main.ui.removeCallbacksAndMessages(null);main.loadSourceCategories();seedDownloads(main);main.showSourceDestinationPicker(Collections.emptyList(),Collections.singletonList(SourceDestinationPopup.webFavoriteItem("https://example.invalid/current183","当前网页")));});
      required("当前路径：分类",false);stable();screenshot("main-path");Rect original=bounds();dismiss();
      scenario.onActivity(main->SourceDestinationPopup.showWebFavorite(main,"https://example.invalid/current183","当前网页"));
      required("当前路径：分类",false);assertEquals(original,bounds());stable();screenshot("favourite-path");
      click("进入 工具",true,false);click("进入 自建合集",true,false);required("软件",false);click("进入 软件",true,false);required("当前路径：工具 › 自建合集 › 软件",false);screenshot("favourite-folder");click("返回上一级",true,false);dismiss();
      assertFalse(SharedBrowserPopup.isFavorite(context,"https://example.invalid/current183"));
      scenario.onActivity(main->main.showBatchDownloadDetails("batch183"));required("保存中",false);required("安装包",false);stable();screenshot("main-batch");
      click("示例软件0.apk",false,true);visible("分享所选原链接",true);screenshot("main-batch-selection");click("关闭",false,false);
      scenario.onActivity(main->SharedDownloadHistoryPopup.show(main));required("测试批量183",false);stable();screenshot("download-history");int window=root().getWindowId();
      click("测试批量183",false,false);assertEquals("Batch details use the same Android window",window,root().getWindowId());required("返回下载历史列表",true);required("下载完成",false);screenshot("download-history-batch");
      click("示例软件0.apk",false,true);visible("分享所选原链接",true);screenshot("download-history-selection");click("返回下载历史列表",true,false);assertEquals(window,root().getWindowId());required("测试批量183",false);click("关闭",false,false);
      scenario.onActivity(main->SharedBrowserPopup.show(main,false,url->{}));required("测试网页",false);stable();screenshot("browsing-history");click("测试网页",false,true);visible("将所选网页加入路径",true);screenshot("browsing-history-selection");click("关闭",false,false);
      scenario.onActivity(main->main.showSoftwareDetailsDialog(false));required("隐私政策",false);stable();screenshot("general-about");click("关闭",false,false);
    }
    seedStores();
    Intent reference=new Intent(context,LegacyPopup183RuntimeActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
    try(ActivityScenario<LegacyPopup183RuntimeActivity> scenario=ActivityScenario.launch(reference)){
      scenario.onActivity(main->{main.ui.removeCallbacksAndMessages(null);main.loadSourceCategories();seedDownloads(main);main.showSourceDestinationPicker(Collections.emptyList(),Collections.singletonList(SourceDestinationPopup.webFavoriteItem("https://example.invalid/reference183","当前网页")));});
      required("当前路径：分类",false);screenshot("legacy181-path");dismiss();scenario.onActivity(main->main.showBatchDownloadDetails("batch183"));required("保存中",false);screenshot("legacy181-batch");click("示例软件0.apk",false,true);required("分享所选原链接",true);screenshot("legacy181-batch-selection");click("关闭",false,false);
    }
    Intent webIntent=new Intent(context,LanzouWebActivity.class).putExtra("url","https://example.invalid/popup185").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
    try(ActivityScenario<LanzouWebActivity> scenario=ActivityScenario.launch(webIntent)){
      scenario.onActivity(web->web.showRounded(new android.app.AlertDialog.Builder(web).setTitle("Web 普通弹窗").setMessage("验证统一圆角窗口").setNegativeButton("关闭",null).create()));
      required("Web 普通弹窗",false);stable();screenshot("web-general");click("关闭",false,false);
    }
    if(variant.equals("portrait-light"))realWebViewResumesWithoutCookie();
  }
  void realWebViewResumesWithoutCookie()throws Exception{
    final java.util.concurrent.atomic.AtomicInteger retries=new java.util.concurrent.atomic.AtomicInteger();
    final java.util.concurrent.atomic.AtomicBoolean closed=new java.util.concurrent.atomic.AtomicBoolean();
    final String origin="https://verify183.lanzoum.com/b0test183";
    try(ActivityScenario<MainActivity> scenario=ActivityScenario.launch(MainActivity.class)){
      scenario.onActivity(main->{
        main.ui.removeCallbacksAndMessages(null);main.verificationLaunching=true;main.verificationTargetUrl=origin;main.pendingVerificationRetries.add(retries::incrementAndGet);
        android.webkit.WebView web=new android.webkit.WebView(main);web.getSettings().setJavaScriptEnabled(true);main.verificationWebView=web;
        web.setWebViewClient(new android.webkit.WebViewClient(){@Override public void onPageFinished(android.webkit.WebView view,String url){main.scheduleLanzouVerificationCheck();}});
        android.app.Dialog dialog=new android.app.Dialog(main);main.verificationDialog=dialog;dialog.setContentView(web);dialog.show();dialog.getWindow().setLayout(main.dp(340),main.dp(360));
        web.loadDataWithBaseURL(origin,"<html><body><div id='nc_1_n1z' style='width:280px;height:80px'>合成验证页面，测试前台等待</div></body></html>","text/html","UTF-8",origin);
        main.scheduleLanzouVerificationCheck();
      });
      idle();assertEquals(0,retries.get());screenshot("synthetic-verification-waiting");
      scenario.onActivity(main->{assertTrue(main.verificationLaunching);main.verificationWebView.evaluateJavascript("document.getElementById('nc_1_n1z').style.display='none';document.body.insertAdjacentHTML('beforeend','<div>目录已加载</div><script>filemoreajax.php?file=183</script>');",null);});
      long deadline=SystemClock.uptimeMillis()+6000;while(retries.get()==0&&SystemClock.uptimeMillis()<deadline)SystemClock.sleep(100);
      assertEquals("A real WebView page without cookies resumes exactly once",1,retries.get());
      scenario.onActivity(main->closed.set(!main.verificationLaunching&&main.verificationDialog==null&&main.verificationWebView==null));assertTrue("Successful verification closes and releases the actual window",closed.get());screenshot("synthetic-verification-closed");
    }
  }
}
