package cc.nkbr.lanzouplus;

import android.graphics.Color;
import android.graphics.drawable.ColorDrawable;
import android.os.Build;
import android.view.Gravity;
import android.widget.LinearLayout;
import android.widget.PopupWindow;
import android.widget.TextView;

/** Home search options, anchored to the right edge of the search bar. */
final class HomeSearchQuickMenu {
  static void show(MainActivity owner, MainActivity.SearchCategoryPicker picker, Runnable chooseScope) {
    if (picker == null || picker.trigger.getWindowToken() == null) return;
    owner.ensureStaticIndexApplied();
    final PopupWindow[] holder = {null};
    LinearLayout panel = new LinearLayout(owner);
    panel.setOrientation(LinearLayout.VERTICAL);
    panel.setPadding(owner.dp(8), owner.dp(8), owner.dp(8), owner.dp(8));
    panel.setBackground(owner.solidShape(owner.SURFACE, 24));
    LinearLayout scopeRow = owner.settingsRowShell();
    scopeRow.setBackground(owner.filterRipple(new ColorDrawable(Color.TRANSPARENT)));
    scopeRow.addView(owner.settingsLeadingIcon(R.drawable.ic_folder_open), owner.settingsIconBox());
    scopeRow.addView(owner.settingsRowTitle("搜索范围…"), new LinearLayout.LayoutParams(0, -1, 1));
    TextView scopeValue = owner.text(picker.value.getText().toString(), 12, owner.MUTED);
    scopeValue.setGravity(Gravity.END | Gravity.CENTER_VERTICAL);
    scopeValue.setPadding(owner.dp(8), 0, owner.dp(6), 0);
    scopeValue.setMaxWidth(owner.dp(150));
    scopeValue.setSingleLine(true);
    scopeValue.setEllipsize(android.text.TextUtils.TruncateAt.END);
    scopeRow.addView(scopeValue, new LinearLayout.LayoutParams(-2, -1));
    scopeRow.setClickable(true);
    scopeRow.setFocusable(true);
    scopeRow.setContentDescription("搜索范围，当前 " + scopeValue.getText() + "，点击更改路径、分类或网页搜索");
    scopeRow.setOnClickListener(v -> { if (holder[0] != null) holder[0].dismiss(); chooseScope.run(); });
    panel.addView(scopeRow, new LinearLayout.LayoutParams(-1, owner.dp(52)));
    panel.addView(owner.buildStaticIndexPopupRow(holder), new LinearLayout.LayoutParams(-1, owner.dp(64)));
    int boxWidth = owner.homeSearchBox != null ? owner.homeSearchBox.getWidth() : 0;
    int width = Math.max(owner.dp(232), Math.min(boxWidth > 0 ? boxWidth : picker.trigger.getWidth(), owner.dp(280)));
    PopupWindow popup = new PopupWindow(panel, width, -2, true);
    holder[0] = popup;
    popup.setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
    popup.setOutsideTouchable(true);
    popup.setElevation(owner.dp(14));
    if (Build.VERSION.SDK_INT >= 23) popup.setOverlapAnchor(false);
    popup.showAsDropDown(owner.homeSearchBox != null && boxWidth > 0 ? owner.homeSearchBox : picker.trigger, 0, owner.dp(2), Gravity.END);
  }
}
