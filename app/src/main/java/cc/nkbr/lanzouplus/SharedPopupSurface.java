package cc.nkbr.lanzouplus;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.res.ColorStateList;
import android.graphics.Color;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.RippleDrawable;
import android.text.format.DateUtils;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.view.WindowManager;
import android.widget.ImageButton;
import android.widget.ImageView;
import android.widget.TextView;

/**
 * One definition of the popup surface used by every shared history popup: theme colours, corner
 * radius, dialog chrome and small view factories. Changing a value here changes all popups.
 */
final class SharedPopupSurface {
  private SharedPopupSurface() {}

  /**
   * Resolved colours of the caller's page, shared by the three collection popups.
   *
   * <p>The app's own semantic palette wins over the platform attributes: {@code colorBackground}
   * and {@code colorAccent} resolve to the framework grey surface and accent on this theme, which
   * turned every shared popup grey-white instead of the Material surface of the page behind it.
   * {@link #appPalette} reads BG/SURFACE/PRIMARY/TEXT/MUTED/DIV exactly as the hosting page
   * resolved them for the current night mode.</p>
   */
  static final class Theme {
    final int accent, text, surface, muted, divider, background;

    Theme(Activity host) {
      int[] palette = appPalette(host);
      surface = palette[0];
      accent = palette[1];
      text = palette[2];
      muted = palette[3];
      divider = palette[4];
      background = palette[5];
    }
  }

  static int dp(Activity host, float value) {
    return (int) (value * host.getResources().getDisplayMetrics().density + .5f);
  }

  /**
   * The semantic colours of the hosting page. {@code MainActivity} and {@link LanzouWebActivity}
   * already resolve BG/SURFACE/PRIMARY/TEXT/MUTED/DIV for the current night mode, so a popup shown
   * over either page uses the same surface, accent and text as that page. A host outside those two
   * receives today's Material defaults for its night mode and can never fall back to grey.
   */
  static int[] appPalette(Activity host) {
    if (host instanceof MainActivity) {
      MainActivity main = (MainActivity) host;
      return new int[]{main.SURFACE, main.PRIMARY, main.TEXT, main.MUTED, main.DIV, main.BG};
    }
    if (host instanceof LanzouWebActivity) {
      LanzouWebActivity web = (LanzouWebActivity) host;
      return new int[]{web.SURFACE, web.PRIMARY, web.TEXT, web.MUTED, web.DIV, web.BG};
    }
    boolean dark = (host.getResources().getConfiguration().uiMode
        & android.content.res.Configuration.UI_MODE_NIGHT_MASK)
        == android.content.res.Configuration.UI_MODE_NIGHT_YES;
    return new int[]{
        dark ? Color.rgb(33, 31, 38) : Color.rgb(243, 237, 247),
        dark ? Color.rgb(208, 188, 255) : Color.rgb(103, 80, 164),
        dark ? Color.rgb(230, 225, 229) : Color.rgb(29, 27, 32),
        dark ? Color.rgb(202, 196, 208) : Color.rgb(73, 69, 79),
        dark ? Color.rgb(73, 69, 79) : Color.rgb(202, 196, 208),
        dark ? Color.rgb(28, 27, 31) : Color.rgb(255, 251, 254)};
  }

  static GradientDrawable solid(int color, int radius) {
    GradientDrawable shape = new GradientDrawable();
    shape.setColor(color);
    shape.setCornerRadius(radius);
    return shape;
  }

  static Drawable ripple(int tint, Drawable content) {
    return new RippleDrawable(ColorStateList.valueOf(Color.argb(36, Color.red(tint), Color.green(tint), Color.blue(tint))), content, null);
  }

  static TextView label(Activity host, String value, int size, int color) {
    TextView text = new TextView(host);
    text.setText(value);
    text.setTextColor(color);
    text.setTextSize(TypedValue.COMPLEX_UNIT_SP, size);
    text.setGravity(Gravity.START | Gravity.CENTER_VERTICAL);
    return text;
  }

  static ImageButton actionButton(Activity host, int icon, String description, int tint) {
    ImageButton button = new ImageButton(host);
    button.setImageResource(icon);
    button.setColorFilter(tint);
    button.setScaleType(ImageView.ScaleType.CENTER_INSIDE);
    button.setPadding(dp(host, 12), dp(host, 12), dp(host, 12), dp(host, 12));
    button.setBackgroundColor(Color.TRANSPARENT);
    button.setContentDescription(description);
    button.setMinimumWidth(0);
    button.setMinimumHeight(0);
    return button;
  }

  static void styleButton(ImageButton button, int tint, int muted, boolean enabled) {
    button.setEnabled(enabled);
    button.setAlpha(enabled ? 1f : .38f);
    button.setColorFilter(enabled ? tint : muted);
  }

  /**
   * Rounded, themed dialog surface shared by every popup.
   *
   * <p>The window receives its final width and centred gravity before it is shown. Sizing it inside
   * the show callback relaid the dialog after its first frame, and the platform's own dialog
   * animation stacked a window scale on top of the content fade, which is the open jitter. The
   * platform dialog animation is off for these popups only; unrelated app animations are untouched.
   * Content, colours and callbacks are prepared before the first frame.</p>
   */
  static void styleDialog(Activity host, AlertDialog dialog, Theme theme, Runnable after) {
    dialog.create();
    final int radius = dp(host, 22);
    Window window = dialog.getWindow();
    if (window != null) {
      window.setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
      window.setWindowAnimations(0);
      WindowManager.LayoutParams params = window.getAttributes();
      params.gravity = Gravity.CENTER;
      params.x = 0;
      params.y = 0;
      params.width = dialogWidth(host);
      params.height = WindowManager.LayoutParams.WRAP_CONTENT;
      params.windowAnimations = 0;
      window.setAttributes(params);
    }
    {
      View content = dialog.findViewById(android.R.id.content);
      View surfaceView = content;
      if (content instanceof ViewGroup && ((ViewGroup) content).getChildCount() > 0) {
        surfaceView = ((ViewGroup) content).getChildAt(0);
      }
      if (surfaceView != null) {
        surfaceView.setBackground(solid(theme.surface, radius));
        surfaceView.setClipToOutline(true);
        surfaceView.setElevation(dp(host, 10));
      }
      int titleId = host.getResources().getIdentifier("alertTitle", "id", "android");
      TextView title = dialog.findViewById(titleId);
      if (title != null) title.setTextColor(theme.text);
      TextView message=dialog.findViewById(android.R.id.message);if(message!=null)message.setTextColor(theme.text);
      android.widget.Button close = dialog.getButton(AlertDialog.BUTTON_NEGATIVE);
      if (close != null) close.setTextColor(theme.muted);
      android.widget.Button positive = dialog.getButton(AlertDialog.BUTTON_POSITIVE);
      if (positive != null) positive.setTextColor(theme.accent);
      if (after != null) after.run();
    }
  }

  /** The one dialog width every popup uses: the host viewport minus the platform's 28dp margin. */
  static int dialogWidth(Activity host) {
    int viewport = host instanceof MainActivity ? ((MainActivity) host).safeContentWidth()
        : host.getResources().getDisplayMetrics().widthPixels;
    return Math.max(1, Math.min(dp(host, 560), viewport - dp(host, 28)));
  }

  /** Scroll the complete custom panel when font scaling makes it taller than the viewport. */
  static android.widget.ScrollView dialogScroll(Activity host,View body,int maxDp){
    android.widget.ScrollView scroll=new android.widget.ScrollView(host){
      @Override protected void onMeasure(int widthSpec,int heightSpec){
        int room=host.getResources().getDisplayMetrics().heightPixels-dp(host,176*host.getResources().getConfiguration().fontScale);
        int limit=Math.max(dp(host,48),Math.min(dp(host,maxDp),room));if(View.MeasureSpec.getMode(heightSpec)!=View.MeasureSpec.UNSPECIFIED)limit=Math.min(limit,View.MeasureSpec.getSize(heightSpec));
        super.onMeasure(widthSpec,View.MeasureSpec.makeMeasureSpec(limit,View.MeasureSpec.AT_MOST));
      }
    };
    scroll.setFillViewport(false);scroll.setVerticalScrollBarEnabled(true);scroll.addView(body,new android.widget.ScrollView.LayoutParams(-1,-2));return scroll;
  }

  static String moment(long at) {
    if (at <= 0) return "时间未知";
    try {
      return DateUtils.getRelativeTimeSpanString(at, System.currentTimeMillis(), DateUtils.MINUTE_IN_MILLIS).toString();
    } catch (Exception ignored) {
      return "时间未知";
    }
  }
}
