package cc.nkbr.lanzouplus;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.res.ColorStateList;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.RippleDrawable;
import android.util.TypedValue;
import android.view.Gravity;
import android.widget.LinearLayout;
import android.widget.TextView;
import java.util.function.Consumer;

/** Navigation shared by all three record collections, on the caller's Activity. */
final class HistoryPopupNavigation {
  static final int DOWNLOADS=0, HISTORY=1, FAVORITES=2;

  static void attach(Activity host, AlertDialog dialog, LinearLayout panel, int current,
      int accent, int text, Consumer<String> openUrl) {
    LinearLayout tabs=new LinearLayout(host);
    String[] labels={"下载历史","浏览历史","网页收藏"};
    for(int index=0;index<labels.length;index++) {
      final int destination=index;
      TextView tab=new TextView(host);
      tab.setText(labels[index]);
      tab.setTextSize(TypedValue.COMPLEX_UNIT_SP,13);
      tab.setTextColor(index==current?accent:text);
      tab.setGravity(Gravity.CENTER);
      tab.setSelected(index==current);
      tab.setContentDescription(labels[index]+(index==current?"，当前":"，切换"));
      tab.setBackground(new RippleDrawable(ColorStateList.valueOf((accent&0x00ffffff)|0x22000000),
          new ColorDrawable(android.graphics.Color.TRANSPARENT),null));
      tab.setOnClickListener(view->{
        if(destination==current)return;
        dialog.dismiss();
        if(destination==DOWNLOADS)SharedDownloadHistoryPopup.show(host);
        else SharedBrowserPopup.show(host,destination==FAVORITES,openUrl);
      });
      tabs.addView(tab,new LinearLayout.LayoutParams(0,dp(host,48),1));
    }
    panel.addView(tabs,0,new LinearLayout.LayoutParams(-1,dp(host,48)));
  }

  static Consumer<String> browserOpener(Activity host) {
    if(host instanceof LanzouWebActivity)return ((LanzouWebActivity)host)::load;
    return url->((MainActivity)host).openWebPage(url,"",false);
  }

  private static int dp(Activity host,int value) {
    return Math.round(value*host.getResources().getDisplayMetrics().density);
  }
}
