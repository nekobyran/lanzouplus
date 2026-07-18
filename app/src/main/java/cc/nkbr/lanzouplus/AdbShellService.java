package cc.nkbr.lanzouplus;

import android.content.Context;
import android.os.ParcelFileDescriptor;
import java.io.ByteArrayOutputStream;
import java.io.FileInputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;

/** Runs only inside Shizuku/Sui with the real shell or root identity. */
public final class AdbShellService extends IAdbShellService.Stub {
  private static final long INSTALL_TIMEOUT_MS=5*60*1000L;

  public AdbShellService(){}
  public AdbShellService(Context ignored){}

  @Override public void destroy(){System.exit(0);}

  @Override public String installApk(ParcelFileDescriptor source,long size){
    if(source==null||size<=0)return "ERROR\n安装包大小无效";
    Process process=null;
    try(ParcelFileDescriptor descriptor=source;InputStream input=new FileInputStream(descriptor.getFileDescriptor())){
      process=new ProcessBuilder("/system/bin/pm","install","-r","-S",Long.toString(size)).redirectErrorStream(true).start();
      long written=0;byte[] buffer=new byte[64*1024];
      try(OutputStream output=process.getOutputStream()){
        for(int count;(count=input.read(buffer))>=0;){if(count==0)continue;output.write(buffer,0,count);written+=count;}
      }
      if(written!=size){process.destroy();return "ERROR\n安装包读取不完整（"+written+"/"+size+"）";}
      long deadline=System.currentTimeMillis()+INSTALL_TIMEOUT_MS;int exit;
      while(true){try{exit=process.exitValue();break;}catch(IllegalThreadStateException running){if(System.currentTimeMillis()>=deadline){process.destroy();return "ERROR\n静默安装超时";}Thread.sleep(100);}}
      ByteArrayOutputStream response=new ByteArrayOutputStream();
      try(InputStream result=process.getInputStream()){for(int count;(count=result.read(buffer))>=0;){if(count>0)response.write(buffer,0,count);}}
      String message=new String(response.toByteArray(),StandardCharsets.UTF_8).trim();
      return exit==0&&message.toLowerCase(java.util.Locale.ROOT).contains("success")?"OK\n"+message:"ERROR\n"+(message.isEmpty()?"pm install 返回 "+exit:message);
    }catch(Throwable error){if(process!=null)process.destroy();String message=error.getMessage();return "ERROR\n"+(message==null||message.trim().isEmpty()?error.getClass().getSimpleName():message.trim());}
  }
}
