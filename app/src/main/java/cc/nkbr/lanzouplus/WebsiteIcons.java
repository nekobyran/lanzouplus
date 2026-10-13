package cc.nkbr.lanzouplus;

import android.app.Activity;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.util.LruCache;
import android.widget.ImageView;
import java.io.*;
import java.net.*;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

/** Website icons received by WebView are reused by history, including custom favicon locations. */
final class WebsiteIcons {
  private static final LruCache<String,Bitmap> images=new LruCache<String,Bitmap>(2*1024*1024){
    @Override protected int sizeOf(String key,Bitmap image){return image.getByteCount();}
  };
  private static final ThreadPoolExecutor iconIo=new ThreadPoolExecutor(2,2,30L,TimeUnit.SECONDS,
      new LinkedBlockingQueue<>(),run->{Thread worker=new Thread(run,"website-icons");worker.setDaemon(true);return worker;});
  static { iconIo.allowCoreThreadTimeOut(true); }
  private WebsiteIcons(){}
  static String origin(String value){
    try{URL url=new URL(value);if(!url.getProtocol().equals("http")&&!url.getProtocol().equals("https"))return "";
      return url.getProtocol()+"://"+url.getHost()+(url.getPort()<0?"":":"+url.getPort());
    }catch(MalformedURLException error){return "";}
  }
  private static File file(Activity host,String key)throws MalformedURLException{
    URL url=new URL(key);return new File(new File(host.getCacheDir(),"website-icons"),url.getHost()+(url.getPort()<0?"":"_"+url.getPort())+".png");
  }
  static void remember(Activity host,String pageUrl,Bitmap favicon){
    String key=origin(pageUrl);if(key.isEmpty()||favicon==null)return;
    int width=favicon.getWidth(),height=favicon.getHeight();float scale=Math.min(1f,64f/Math.max(width,height));
    Bitmap image=Bitmap.createScaledBitmap(favicon,Math.max(1,Math.round(width*scale)),Math.max(1,Math.round(height*scale)),true).copy(Bitmap.Config.ARGB_8888,false);
    images.put(key,image);
    iconIo.execute(()->{try{File target=file(host,key);target.getParentFile().mkdirs();try(OutputStream stream=new FileOutputStream(target)){image.compress(Bitmap.CompressFormat.PNG,100,stream);}}catch(IOException ignored){}});
  }
  static void bind(Activity host,ImageView view,String pageUrl,int tint){
    String key=origin(pageUrl);view.setImageResource(R.drawable.ic_globe);view.setColorFilter(tint);view.setTag(key);
    if(key.isEmpty())return;Bitmap cached=images.get(key);if(cached!=null){view.clearColorFilter();view.setImageBitmap(cached);return;}
    iconIo.execute(()->{
      Bitmap image=null;
      try{File target=file(host,key);if(target.isFile())image=BitmapFactory.decodeFile(target.getPath());}catch(MalformedURLException ignored){}
      if(image==null){
        HttpURLConnection request=null;
        try{request=(HttpURLConnection)new URL(key+"/favicon.ico").openConnection();request.setConnectTimeout(1500);request.setReadTimeout(2500);
          try(InputStream stream=request.getInputStream();ByteArrayOutputStream bytes=new ByteArrayOutputStream()){
            byte[] buffer=new byte[4096];for(int count;(count=stream.read(buffer))>=0;){if(bytes.size()+count>256*1024)throw new IOException("icon size");bytes.write(buffer,0,count);}
            byte[] data=bytes.toByteArray();BitmapFactory.Options bounds=new BitmapFactory.Options();bounds.inJustDecodeBounds=true;BitmapFactory.decodeByteArray(data,0,data.length,bounds);
            BitmapFactory.Options options=new BitmapFactory.Options();options.inSampleSize=Math.max(1,Math.max(bounds.outWidth,bounds.outHeight)/64);image=BitmapFactory.decodeByteArray(data,0,data.length,options);
          }
        }catch(IOException ignored){}finally{if(request!=null)request.disconnect();}
      }
      if(image==null)return;images.put(key,image);Bitmap ready=image;
      host.runOnUiThread(()->{if(key.equals(view.getTag())){view.clearColorFilter();view.setImageBitmap(ready);}});
    });
  }
}
