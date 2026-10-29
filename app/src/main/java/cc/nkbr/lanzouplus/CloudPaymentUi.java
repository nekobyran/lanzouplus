package cc.nkbr.lanzouplus;

import android.app.AlertDialog;
import android.content.ActivityNotFoundException;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.net.Uri;
import android.view.View;
import android.widget.Button;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;
import io.nayuki.qrcodegen.QrCode;
import java.io.File;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import java.net.URLEncoder;
import org.json.JSONArray;
import org.json.JSONObject;

/** Final payment boundary. Cloud credentials never enter the external document or intent. */
final class CloudPaymentUi {
  private CloudPaymentUi(){}
  static void show(MainActivity owner,JSONObject checkout){
    JSONObject payment=checkout.optJSONObject("payment");
    if(payment==null){owner.showNotice("订单缺少支付入口，请重新查询",true);return;}
    LinearLayout body=new LinearLayout(owner);body.setOrientation(LinearLayout.VERTICAL);
    body.setPadding(owner.dp(20),owner.dp(8),owner.dp(20),owner.dp(8));
    TextView note=owner.text("订单："+checkout.optString("orderId","")+"\n付款后请返回订单记录查询结果。",13,owner.TEXT);
    body.addView(note,new LinearLayout.LayoutParams(-1,-2));
    AlertDialog dialog=new AlertDialog.Builder(owner).setTitle("订单支付").setView(body)
        .setNegativeButton("关闭",null).create();owner.showRounded(dialog);
    String kind=payment.optString("kind","");
    if("qr".equals(kind)){
      TextView loading=owner.text("正在生成支付二维码…",13,owner.MUTED);body.addView(loading);
      owner.io.execute(()->{
        Bitmap image=null;String error="";
        try{image=qrBitmap(payment.optString("content",""));}catch(Exception failure){error=owner.friendlyError(failure);}
        final Bitmap bitmap=image;final String problem=error;
        owner.runOnUiThread(()->{
          if(owner.activityClosing()||!dialog.isShowing())return;
          body.removeView(loading);
          if(bitmap==null){body.addView(owner.text(problem,13,owner.TEXT));return;}
          ImageView qr=new ImageView(owner);qr.setImageBitmap(bitmap);qr.setScaleType(ImageView.ScaleType.FIT_CENTER);
          qr.setContentDescription("此订单的支付二维码");
          int side=Math.min(owner.dp(300),Math.max(owner.dp(120),owner.safeContentWidth()-owner.dp(80)));
          body.addView(qr,new LinearLayout.LayoutParams(-1,side));
          Button share=owner.toolbarTextButton("分享二维码");body.addView(share,new LinearLayout.LayoutParams(-1,owner.dp(48)));
          share.setOnClickListener(v->shareQr(owner,dialog,share,bitmap));
        });
      });
    }else if("url".equals(kind)||"form".equals(kind)){
      Button open=owner.toolbarTextButton("继续支付");body.addView(open,new LinearLayout.LayoutParams(-1,owner.dp(48)));
      open.setOnClickListener(v->{
        if("url".equals(kind))launch(owner,new Intent(Intent.ACTION_VIEW,Uri.parse(payment.optString("url",""))));
        else openForm(owner,dialog,open,payment);
      });
    }else body.addView(owner.text("暂未识别该支付入口，请在订单记录中核对。",13,owner.TEXT));
  }

  static Bitmap qrBitmap(String content){
    QrCode code=QrCode.encodeText(content,QrCode.Ecc.MEDIUM);
    int border=4,scale=4,size=(code.size+border*2)*scale;
    int[] pixels=new int[size*size];
    for(int y=0;y<size;y++)for(int x=0;x<size;x++)
      pixels[y*size+x]=code.getModule(x/scale-border,y/scale-border)?Color.BLACK:Color.WHITE;
    return Bitmap.createBitmap(pixels,size,size,Bitmap.Config.ARGB_8888);
  }

  private static void shareQr(MainActivity owner,AlertDialog dialog,Button action,Bitmap image){
    final String name="checkout-"+java.util.UUID.randomUUID()+".png";
    action.setEnabled(false);
    owner.io.execute(()->{
      String error="";
      try{
        File file=paymentFile(owner,name);
        try(FileOutputStream out=new FileOutputStream(file)){if(!image.compress(Bitmap.CompressFormat.PNG,100,out))throw new java.io.IOException("二维码保存失败");}
      }catch(Exception failure){error=owner.friendlyError(failure);}
      final String problem=error;
      owner.runOnUiThread(()->{
        if(owner.activityClosing()||!dialog.isShowing())return;action.setEnabled(true);
        if(!problem.isEmpty()){owner.showNotice(problem,true);return;}
        Intent share=new Intent(Intent.ACTION_SEND).setType("image/png")
            .putExtra(Intent.EXTRA_STREAM,paymentUri(owner,name))
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        launch(owner,Intent.createChooser(share,"分享支付二维码"));
      });
    });
  }

  private static void openForm(MainActivity owner,AlertDialog dialog,Button action,JSONObject payment){
    if("GET".equals(payment.optString("method",""))){
      try{launch(owner,new Intent(Intent.ACTION_VIEW,Uri.parse(formGetUrl(payment))));}
      catch(Exception failure){owner.showNotice(owner.friendlyError(failure),true);}return;
    }
    final String name="checkout-"+java.util.UUID.randomUUID()+".html";
    action.setEnabled(false);
    owner.io.execute(()->{
      String error="";
      try{
        File file=paymentFile(owner,name);
        try(FileOutputStream out=new FileOutputStream(file)){out.write(formHtml(payment).getBytes(StandardCharsets.UTF_8));}
      }catch(Exception failure){error=owner.friendlyError(failure);}
      final String problem=error;
      owner.runOnUiThread(()->{
        if(owner.activityClosing()||!dialog.isShowing())return;action.setEnabled(true);
        if(!problem.isEmpty()){owner.showNotice(problem,true);return;}
        Intent browser=new Intent(Intent.ACTION_VIEW).setDataAndType(paymentUri(owner,name),"text/html")
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        launch(owner,browser);
      });
    });
  }

  static String formGetUrl(JSONObject payment)throws Exception{
    String url=payment.getString("url");int fragment=url.indexOf('#');
    String suffix=fragment<0?"":url.substring(fragment);if(fragment>=0)url=url.substring(0,fragment);
    JSONArray fields=payment.getJSONArray("fields");
    for(int i=0;i<fields.length();i++){
      JSONObject field=fields.getJSONObject(i);
      url+=(url.contains("?")?(url.endsWith("?")||url.endsWith("&")?"":"&"):"?")
          +URLEncoder.encode(field.getString("name"),"UTF-8")+"="+URLEncoder.encode(field.getString("value"),"UTF-8");
    }
    return url+suffix;
  }

  /** Script-free document: only the official parsed payment form, submitted by a user tap. */
  static String formHtml(JSONObject payment)throws Exception{
    StringBuilder html=new StringBuilder("<!doctype html><html lang='zh-CN'><meta charset='utf-8'>"
        +"<meta name='viewport' content='width=device-width,initial-scale=1'><title>继续支付</title>"
        +"<style>body{font:16px system-ui;margin:32px;background:#f8f5ff;color:#292333}"
        +"main{max-width:32rem;margin:auto}button{border:0;border-radius:28px;padding:16px 28px;"
        +"font:inherit;background:#6750a4;color:white}p{line-height:1.6}</style><main>"
        +"<h1>继续支付</h1><p>点击后进入此订单的支付页面。付款完成后，返回 LanzouPlus 查询订单结果。</p>");
    html.append("<form method='post' action='").append(escape(payment.getString("url"))).append("'>");
    JSONArray fields=payment.getJSONArray("fields");
    for(int i=0;i<fields.length();i++){
      JSONObject field=fields.getJSONObject(i);
      html.append("<input type='hidden' name='").append(escape(field.getString("name")))
          .append("' value='").append(escape(field.getString("value"))).append("'>");
    }
    return html.append("<button type='submit'>进入支付页面</button></form></main></html>").toString();
  }
  private static String escape(String text){return text.replace("&","&amp;").replace("'","&#39;")
      .replace("\"","&quot;").replace("<","&lt;").replace(">","&gt;");}
  private static File paymentFile(MainActivity owner,String name)throws java.io.IOException{
    File directory=new File(owner.getCacheDir(),"cloud-payments");
    if(!directory.isDirectory()&&!directory.mkdirs())throw new java.io.IOException("无法创建支付临时文件");
    return new File(directory,name);
  }
  private static Uri paymentUri(MainActivity owner,String name){
    return Uri.parse("content://"+owner.getPackageName()+".downloads/payment/"+name);
  }
  private static void launch(MainActivity owner,Intent intent){
    try{owner.startActivity(intent);}catch(ActivityNotFoundException missing){owner.showNotice("未找到可打开此支付入口的应用",true);}
  }
}
