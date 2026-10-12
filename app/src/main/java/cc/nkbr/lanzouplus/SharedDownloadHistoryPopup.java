package cc.nkbr.lanzouplus;
import android.app.Activity;
import android.app.AlertDialog;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.ImageButton;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import java.util.ArrayList;
import java.util.List;
/** History uses the batch popup, grouped rows, and an in-window detail/back stack. */
final class SharedDownloadHistoryPopup {
  private SharedDownloadHistoryPopup(){}
  static boolean show(Activity host){
    if(host.isFinishing())return false;
    SharedPopupSurface.Theme theme=new SharedPopupSurface.Theme(host);
    LinearLayout panel=new LinearLayout(host);panel.setOrientation(LinearLayout.VERTICAL);
    FrameLayout area=new FrameLayout(host);
    int height=Math.max(SharedPopupSurface.dp(host,140),Math.min(SharedPopupSurface.dp(host,480),host.getResources().getDisplayMetrics().heightPixels-SharedPopupSurface.dp(host,230)));
    panel.addView(area,new LinearLayout.LayoutParams(-1,height));
    AlertDialog dialog=new AlertDialog.Builder(host).setTitle("下载历史").setView(panel).setNegativeButton("关闭",null).create();dialog.setOwnerActivity(host);
    HistoryPopupNavigation.attach(host,dialog,panel,HistoryPopupNavigation.DOWNLOADS,theme.accent,theme.text,HistoryPopupNavigation.browserOpener(host));
    Session session=new Session(host,dialog,theme,area);session.start();SharedPopupSurface.styleDialog(host,dialog,theme,null);
    dialog.setOnDismissListener(d->session.stop());dialog.show();session.schedule();return true;
  }
  static final class Session {
    final Activity host;final AlertDialog dialog;final SharedPopupSurface.Theme theme;
    final HistoryPopupNavigation.PageHost pages;final Handler handler=new Handler(Looper.getMainLooper());
    final DownloadAggregateView.BatchHost actions;final Runnable tick;DownloadAggregateView.BatchPage root,detail;
    ScrollView rootScroll;String detailId="",lastState="";
    Session(Activity host,AlertDialog dialog,SharedPopupSurface.Theme theme,FrameLayout area){
      this.host=host;this.dialog=dialog;this.theme=theme;pages=new HistoryPopupNavigation.PageHost(host,area);actions=SharedBatchActions.host(host);
      tick=()->{if(dialog.isShowing()){refresh();schedule();}};
    }
    List<DownloadAggregateView.Record> records(){List<DownloadAggregateView.Record> result=new ArrayList<>();for(DownloadHistoryCenter.Row row:DownloadHistoryCenter.rows(host))result.add(DownloadAggregateView.record(row));return result;}
    void start(){List<DownloadAggregateView.Record> records=records();root=DownloadAggregateView.historyPage(host,theme,records,actions,this::openDetail);rootScroll=scroll(root.root);pages.setRoot(rootScroll);lastState=state(records);}
    ScrollView scroll(View body){ScrollView scroll=new ScrollView(host);scroll.setClipToPadding(false);scroll.addView(body,new ScrollView.LayoutParams(-1,-2));return scroll;}
    void openDetail(DownloadAggregateView.Item item){
      detailId=item.batchId;detail=DownloadAggregateView.batchPage(host,theme,item,actions);
      LinearLayout page=new LinearLayout(host);page.setOrientation(LinearLayout.VERTICAL);LinearLayout header=new LinearLayout(host);header.setGravity(Gravity.CENTER_VERTICAL);
      ImageButton back=SharedPopupSurface.actionButton(host,R.drawable.ic_back,"返回下载历史列表",theme.accent);back.setOnClickListener(v->back());header.addView(back,new LinearLayout.LayoutParams(SharedPopupSurface.dp(host,48),SharedPopupSurface.dp(host,48)));
      TextView title=SharedPopupSurface.label(host,item.title(),15,theme.text);title.setSingleLine(true);title.setEllipsize(android.text.TextUtils.TruncateAt.END);header.addView(title,new LinearLayout.LayoutParams(0,SharedPopupSurface.dp(host,48),1));
      page.addView(header,new LinearLayout.LayoutParams(-1,SharedPopupSurface.dp(host,48)));page.addView(scroll(detail.root),new LinearLayout.LayoutParams(-1,0,1));pages.push(page);dialog.setTitle(item.title());
    }
    void back(){detail=null;detailId="";pages.pop();dialog.setTitle("下载历史");}
    void refresh(){
      List<DownloadAggregateView.Record> records=records();String next=state(records);if(lastState.equals(next))return;lastState=next;root.bind(new DownloadAggregateView.Item("history",records));
      if(detail!=null){DownloadAggregateView.Item found=null;for(DownloadAggregateView.Item item:DownloadAggregateView.group(records))if(detailId.equals(item.batchId)){found=item;break;}if(found==null)back();else detail.bind(found);}
    }
    static String state(List<DownloadAggregateView.Record> records){StringBuilder value=new StringBuilder();for(DownloadAggregateView.Record r:records)value.append(r.key()).append('|').append(r.metricsText()).append('|').append(r.percent()).append(';');return value.toString();}
    void schedule(){handler.postDelayed(tick,400);}void stop(){handler.removeCallbacksAndMessages(null);}
  }
}