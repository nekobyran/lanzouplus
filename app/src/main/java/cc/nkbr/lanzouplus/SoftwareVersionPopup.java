package cc.nkbr.lanzouplus;

import android.app.*;
import android.view.*;
import android.widget.*;
import java.util.*;

/** Official multi-version software groups use the download page's actual aggregate component. */
final class SoftwareVersionPopup {
  static boolean handles(Models.Source source,Models.Item item){return source!=null&&"local:lanzoumax-single-software".equals(source.id)&&item.folder&&item.folderId.startsWith("software-");}
  static void show(MainActivity owner,Models.Source source,Models.Item item){
    owner.io.execute(()->{try{List<Models.Item> files=owner.core.collectFiles(source,Collections.singletonList(item),null);owner.runOnUiThread(()->render(owner,item.title,files));}catch(Exception error){owner.runOnUiThread(()->owner.showNotice(owner.friendlyError(error),true));}});
  }
  private static void render(MainActivity owner,String title,List<Models.Item> files){
    Map<String,Models.Item> byUrl=new LinkedHashMap<>();List<DownloadAggregateView.Record> records=new ArrayList<>();for(Models.Item file:files){byUrl.put(file.url,file);records.add(new Version(owner,file,title));}
    AlertDialog[] prompt={null};DownloadAggregateView.Action download=(item,chosen)->{List<Models.Item> selected=new ArrayList<>();for(DownloadAggregateView.Record record:chosen){Models.Item file=byUrl.get(record.shareUrl());if(file!=null)selected.add(file);}if(!selected.isEmpty()){if(prompt[0]!=null)prompt[0].dismiss();owner.startBulkDownloads(selected);}};
    DownloadAggregateView.BatchButton selected=DownloadAggregateView.BatchButton.of(R.drawable.ic_download,"下载所选版本",chosen->!chosen.isEmpty(),download),all=DownloadAggregateView.BatchButton.of(R.drawable.ic_download,"下载全部版本",download);
    DownloadAggregateView.BatchHost host=new DownloadAggregateView.BatchHost(Collections.singletonList(selected),Collections.singletonList(all),record->{ImageView icon=new ImageView(owner);icon.setImageResource(R.drawable.ic_file);Models.Item file=byUrl.get(record.shareUrl());if(file!=null&&!file.iconUrl.isEmpty())owner.loadImage(file.iconUrl,icon);return icon;});
    DownloadAggregateView.BatchPage page=DownloadAggregateView.batchPage(owner,new SharedPopupSurface.Theme(owner),new DownloadAggregateView.Item("versions",records),host);
    prompt[0]=new AlertDialog.Builder(owner).setTitle(title+" · "+files.size()+" 个版本").setView(owner.limitedDialogScroll(page.root,420)).setNegativeButton("关闭",null).create();owner.showRounded(prompt[0]);
  }
  private static final class Version implements DownloadAggregateView.Record {
    final MainActivity owner;final Models.Item item;final String title;Version(MainActivity owner,Models.Item item,String title){this.owner=owner;this.item=item;this.title=title;}
    public String key(){return item.url;}public String batchId(){return "versions";}public String batchTitle(){return title;}public String name(){return item.title;}public String localUri(){return "";}public String shareUrl(){return item.url;}public String password(){return item.password;}public String parentUri(){return "";}public String metricsText(){return item.size+" · "+item.time;}
    public boolean completed(){return false;}public boolean active(){return false;}public boolean paused(){return false;}public boolean failed(){return false;}public boolean cancelled(){return false;}public boolean resolving(){return false;}public long downloadedBytes(){return 0;}public long totalBytes(){return owner.parseSize(item.size);}public int percent(){return 0;}public long speedBytesPerSecond(){return 0;}public long remainingSeconds(){return -1;}public boolean premium(){return false;}public MainActivity.DownloadEntry liveEntry(){return null;}public int primaryAction(){return DownloadHistoryCenter.ACTION_OPEN;}public boolean canCancel(){return false;}public boolean canRetry(){return false;}public boolean canDeleteFile(){return false;}public boolean perform(Activity host,int action){owner.requestItemDownload(item,false);return true;}
  }
}
