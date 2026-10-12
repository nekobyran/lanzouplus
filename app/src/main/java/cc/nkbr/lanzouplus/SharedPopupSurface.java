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

  /** Resolved colours of the caller's theme, shared by the three collection popups. */
  static final class Theme {
    final int accent, text, surface, muted, divider;

    Theme(Activity host) {
      accent = themeColor(host, android.R.attr.colorAccent, 0xff6750a4);
      text = themeColor(host, android.R.attr.textColorPrimary, Color.DKGRAY);
      surface = themeColor(host, android.R.attr.colorBackground, Color.WHITE);
      muted = Color.argb(190, Color.red(text), Color.green(text), Color.blue(text));
      divider = Color.argb(60, Color.red(text), Color.green(text), Color.blue(text));
    }
  }

  static int dp(Activity host, float value) {
    return (int) (value * host.getResources().getDisplayMetrics().density + .5f);
  }

  static int themeColor(Activity host, int attribute, int fallback) {
    TypedValue result = new TypedValue();
    if (!host.getTheme().resolveAttribute(attribute, result, true)) return fallback;
    if (result.resourceId != 0) {
      try {
        return host.getResources().getColor(result.resourceId, host.getTheme());
      } catch (android.content.res.Resources.NotFoundException ignored) {
        return fallback;
      }
    }
    return result.data;
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

  /** Rounded, themed dialog surface shared by every popup. */
  static void styleDialog(Activity host, AlertDialog dialog, Theme theme, Runnable after) {
    dialog.setOnShowListener(ignored -> {
      Window window = dialog.getWindow();
      if (window != null) {
        window.setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
        WindowManager.LayoutParams params = window.getAttributes();
        params.gravity = Gravity.CENTER;
        params.x = 0;
        params.y = 0;
        params.width = Math.max(dp(host, 280),
            Math.min(dp(host, 560), host.getResources().getDisplayMetrics().widthPixels - dp(host, 28)));
        params.height = WindowManager.LayoutParams.WRAP_CONTENT;
        window.setAttributes(params);
      }
      View content = dialog.findViewById(android.R.id.content);
      View surfaceView = content;
      if (content instanceof ViewGroup && ((ViewGroup) content).getChildCount() > 0) {
        surfaceView = ((ViewGroup) content).getChildAt(0);
      }
      if (surfaceView != null) {
        surfaceView.setBackground(solid(theme.surface, dp(host, 22)));
        surfaceView.setClipToOutline(true);
        surfaceView.setElevation(dp(host, 10));
      }
      android.widget.Button close = dialog.getButton(AlertDialog.BUTTON_NEGATIVE);
      if (close != null) close.setTextColor(theme.muted);
      android.widget.Button positive = dialog.getButton(AlertDialog.BUTTON_POSITIVE);
      if (positive != null) positive.setTextColor(theme.accent);
      if (after != null) after.run();
    });
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
