package cc.nkbr.lanzouplus;

import android.app.AlertDialog;
import android.content.Intent;
import android.net.Uri;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;

/** Plain source export with an explicit subscription-versus-snapshot choice. */
final class SourceRuleExport {
  static void show(MainActivity owner,Collection<String> ids){
    owner.io.execute(()->{try{boolean repositories=false;for(Models.Source source:owner.core.sources())if((ids==null||ids.contains(LanzouCore.sourceId(source)))&&source.kind==Models.SOURCE_REPOSITORY)repositories=true;final boolean needsChoice=repositories;
      owner.runOnUiThread(()->{if(!needsChoice)write(owner,ids,false);else{AlertDialog prompt=new AlertDialog.Builder(owner).setTitle("GitHub 源如何导出？").setItems(new String[]{"保留 GitHub 订阅链接","静态保存当前源内容"},(dialog,index)->write(owner,ids,index==1)).setNegativeButton("取消",null).create();owner.showRounded(prompt);}});
    }catch(Exception error){owner.runOnUiThread(()->owner.showNotice(owner.friendlyError(error),true));}});
  }
  private static void write(MainActivity owner,Collection<String> ids,boolean snapshot){owner.io.execute(()->{try{
    String raw=owner.core.exportSourceRules(ids,snapshot);File directory=new File(owner.getFilesDir(),"project-exports");if(!directory.isDirectory()&&!directory.mkdirs())throw new IOException("无法创建导出目录");File file=new File(directory,"LanzouPlus-sources.json");try(OutputStream output=new FileOutputStream(file)){output.write(raw.getBytes(StandardCharsets.UTF_8));}
    owner.runOnUiThread(()->{Intent intent=new Intent(Intent.ACTION_SEND);intent.setType("application/json");intent.putExtra(Intent.EXTRA_STREAM,new Uri.Builder().scheme("content").authority(owner.getPackageName()+".downloads").appendPath("project").appendPath(file.getName()).build());intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);owner.startActivity(intent);owner.showNotice("已导出明文源文件",false);});
  }catch(Exception error){owner.runOnUiThread(()->owner.showNotice("源导出失败："+owner.friendlyError(error),true));}});}
}
