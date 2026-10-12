package cc.nkbr.lanzouplus;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.res.ColorStateList;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.RippleDrawable;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;
import java.util.ArrayList;
import java.util.function.Consumer;

/** Navigation shared by all three record collections, on the caller's Activity. */
final class HistoryPopupNavigation {
  static final int DOWNLOADS=0, HISTORY=1, FAVORITES=2;

  /** In-dialog page switch duration; reduced-motion hosts switch instantly. */
  static final long FADE_MS=150;

  /** Forced motion answer; null reads the system animator scale, values pin a branch for tests. */
  private static Boolean motionOverride;

  private HistoryPopupNavigation() {}

  static void motionOverride(Boolean value) {
    motionOverride=value;
  }

  /** One collection tab row; inserted at the top of the caller-supplied page container. */
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

  /** True unless the user turned animations off in system settings. */
  static boolean motionEnabled(Activity host) {
    if (motionOverride != null) return motionOverride;
    try {
      return android.provider.Settings.Global.getFloat(host.getContentResolver(),
          android.provider.Settings.Global.ANIMATOR_DURATION_SCALE,1f)>0f;
    } catch(Exception ignored) {
      return true;
    }
  }

  /**
   * In-dialog page stack. Pages below the top stay attached but hidden, so returning from a detail
   * page restores the previous scroll position and row state; the switch cross-fades in 150 ms, or
   * happens instantly when motion is reduced.
   */
  static final class PageHost {
    private final Activity host;
    private final FrameLayout area;
    private final ArrayList<View> below=new ArrayList<>();
    private View current;

    PageHost(Activity host,FrameLayout area) {
      this.host=host;
      this.area=area;
    }

    void setRoot(View page) {
      if(page==null||page==current)return;
      area.removeAllViews();
      below.clear();
      current=page;
      area.addView(page,areaParams());
    }

    void push(View page) {
      if(page==null||page==current)return;
      View previous=current;
      if(previous!=null)below.add(previous);
      current=page;
      area.addView(page,areaParams());
      if(previous==null||!motionEnabled(host)) {
        if(previous!=null)previous.setVisibility(View.GONE);
        page.setAlpha(1f);
        return;
      }
      page.setAlpha(0f);
      page.animate().alpha(1f).setDuration(FADE_MS).start();
      previous.animate().cancel();
      previous.animate().alpha(0f).setDuration(FADE_MS)
          .withEndAction(()->previous.setVisibility(View.GONE)).start();
    }

    /** Returns to the page below the current one; false when the current page is the root. */
    boolean pop() {
      if(below.isEmpty())return false;
      View leaving=current;
      View restored=below.remove(below.size()-1);
      current=restored;
      restored.setVisibility(View.VISIBLE);
      if(leaving==null||!motionEnabled(host)) {
        restored.setAlpha(1f);
        if(leaving!=null)area.removeView(leaving);
        return true;
      }
      restored.setAlpha(0f);
      restored.animate().alpha(1f).setDuration(FADE_MS).start();
      leaving.animate().cancel();
      leaving.animate().alpha(0f).setDuration(FADE_MS)
          .withEndAction(()->area.removeView(leaving)).start();
      return true;
    }

    boolean canPop() {
      return !below.isEmpty();
    }

    View current() {
      return current;
    }

    private FrameLayout.LayoutParams areaParams() {
      return new FrameLayout.LayoutParams(-1,-2);
    }
  }

  private static int dp(Activity host,int value) {
    return Math.round(value*host.getResources().getDisplayMetrics().density);
  }
}
