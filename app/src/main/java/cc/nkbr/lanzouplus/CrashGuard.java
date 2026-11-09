package cc.nkbr.lanzouplus;

import android.content.Context;
import java.io.File;
import java.io.FileOutputStream;
import java.io.FileInputStream;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/** Keeps the last uncaught exception on disk so a user can copy the real stack after a crash. */
final class CrashGuard {
  private static final String FILE_NAME="last-crash.txt";
  private static final int MAX_CHARS=64*1024;
  private static volatile boolean installed;

  private CrashGuard(){}

  static void install(final Context context){
    if(installed||context==null)return;
    installed=true;
    final Context app=context.getApplicationContext();
    final Thread.UncaughtExceptionHandler previous=Thread.getDefaultUncaughtExceptionHandler();
    Thread.setDefaultUncaughtExceptionHandler((thread,error)->{
      try{write(app,thread,error);}catch(Throwable ignored){}
      if(previous!=null)previous.uncaughtException(thread,error);
    });
  }

  private static void write(Context context,Thread thread,Throwable error){
    try{
      writeReport(context,thread,error);
    }catch(Throwable ignored){
    }
  }

  private static void writeReport(Context context,Thread thread,Throwable error)throws Exception{
    StringWriter buffer=new StringWriter();
    PrintWriter writer=new PrintWriter(buffer);
    String time=new SimpleDateFormat("yyyy-MM-dd HH:mm:ss",Locale.ROOT).format(new Date());
    writer.println("时间：" + time);
    writer.println("线程：" + (thread==null?"":thread.getName()));
    writer.println("版本：" + BuildConfig.VERSION_NAME + " (" + BuildConfig.VERSION_CODE + ")");
    writer.println();
    if(error!=null)error.printStackTrace(writer);
    writer.flush();
    String text=buffer.toString();
    if(text.length()>MAX_CHARS)text=text.substring(0,MAX_CHARS);
    File target=new File(context.getFilesDir(),FILE_NAME);
    try(FileOutputStream output=new FileOutputStream(target)){
      output.write(text.getBytes("UTF-8"));
    }
  }

  static String read(Context context){
    if(context==null)return "";
    File source=new File(context.getFilesDir(),FILE_NAME);
    if(!source.isFile())return "";
    try(InputStream input=new FileInputStream(source)){
      ByteArrayOutputStream output=new ByteArrayOutputStream(Math.max(256,(int)Math.min(source.length(),MAX_CHARS)));
      byte[] chunk=new byte[8192];
      int read;
      while((read=input.read(chunk))>0)output.write(chunk,0,read);
      return output.toString("UTF-8");
    }catch(Exception ignored){
      return "";
    }
  }

  static void clear(Context context){
    if(context==null)return;
    try{
      File source=new File(context.getFilesDir(),FILE_NAME);
      if(source.exists())source.delete();
    }catch(Exception ignored){}
  }
}
