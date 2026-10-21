package cc.nkbr.lanzouplus;

import android.app.AlertDialog;
import android.content.res.ColorStateList;
import android.graphics.Color;
import android.graphics.PorterDuff;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.RippleDrawable;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.ViewOutlineProvider;
import android.widget.Button;
import android.widget.ImageButton;
import android.widget.ImageView;

/**
 * Shared Material-style buttons for the classic View UI. Colours come from the host page's
 * resolved palette so light, night, enabled and destructive states stay aligned everywhere.
 */
final class AppButtons {
  private static final int RADIUS_DP = 14;
  private static final int TOUCH_SIZE_DP = 48;

  private enum Role { PRIMARY, TONAL, OUTLINED, TEXT, DESTRUCTIVE }

  private AppButtons() {}

  static Button primary(MainActivity owner, String label) {
    return create(owner, label, Role.PRIMARY, 14);
  }

  static Button tonal(MainActivity owner, String label) {
    return create(owner, label, Role.TONAL, 13);
  }

  static Button outlined(MainActivity owner, String label) {
    return create(owner, label, Role.OUTLINED, 13);
  }

  static Button text(MainActivity owner, String label) {
    return text(owner, label, 12);
  }

  static Button text(MainActivity owner, String label, float textSp) {
    return create(owner, label, Role.TEXT, textSp);
  }

  static Button destructive(MainActivity owner, String label) {
    return create(owner, label, Role.DESTRUCTIVE, 13);
  }

  static ImageButton icon(MainActivity owner, int drawable, String description) {
    ImageButton button = new ImageButton(owner);
    button.setImageResource(drawable);
    button.setScaleType(ImageView.ScaleType.CENTER_INSIDE);
    button.setPadding(size(owner, 9), size(owner, 9), size(owner, 9), size(owner, 9));
    button.setBackground(ripple(owner, owner.PRIMARY, Color.TRANSPARENT));
    button.setImageTintList(colored(owner.MUTED, owner.PRIMARY));
    button.setImageTintMode(PorterDuff.Mode.SRC_IN);
    button.setContentDescription(description);
    button.setMinimumWidth(size(owner, TOUCH_SIZE_DP));
    button.setMinimumHeight(size(owner, TOUCH_SIZE_DP));
    button.setClipToOutline(true);
    return button;
  }

  /** Dialog actions use highlighted text on the dialog surface, with no filled container. */
  static void styleDialogButtons(MainActivity owner, AlertDialog dialog) {
    for (int which : new int[]{AlertDialog.BUTTON_NEGATIVE, AlertDialog.BUTTON_NEUTRAL,
        AlertDialog.BUTTON_POSITIVE}) {
      Button button = dialog.getButton(which);
      if (button == null) continue;
      apply(owner, button, Role.TEXT);
      if (destructive(button.getText())) {
        button.setTextColor(colored(alpha(owner.MUTED, .62f),
            textColor(owner, Role.DESTRUCTIVE)));
      }
    }
  }

  private static Button create(MainActivity owner, String label, Role role, float textSp) {
    Button button = new Button(owner);
    button.setText(label);
    button.setTextSize(TypedValue.COMPLEX_UNIT_SP, textSp);
    button.setAllCaps(false);
    button.setMaxLines(1);
    button.setGravity(Gravity.CENTER);
    apply(owner, button, role);
    return button;
  }

  private static void apply(MainActivity owner, Button button, Role role) {
    int enabledText = textColor(owner, role);
    int disabledText = alpha(owner.MUTED, .62f);
    button.setTextColor(new ColorStateList(
        new int[][]{new int[]{-android.R.attr.state_enabled}, new int[0]},
        new int[]{disabledText, enabledText}));
    button.setAllCaps(false);
    button.setTypeface(android.graphics.Typeface.create("sans-serif-medium",
        android.graphics.Typeface.NORMAL));
    button.setGravity(Gravity.CENTER);
    button.setIncludeFontPadding(false);
    button.setPadding(size(owner, role == Role.TEXT ? 8 : 18), 0,
        size(owner, role == Role.TEXT ? 8 : 18), 0);
    button.setMinimumWidth(size(owner, TOUCH_SIZE_DP));
    button.setMinimumHeight(size(owner, TOUCH_SIZE_DP));
    button.setMinWidth(size(owner, TOUCH_SIZE_DP));
    button.setMinHeight(size(owner, TOUCH_SIZE_DP));
    button.setBackground(container(owner, role));
    button.setElevation(0f);
    button.setStateListAnimator(null);
    button.setClipToOutline(true);
    button.setOutlineProvider(ViewOutlineProvider.BACKGROUND);
  }

  private static Drawable container(MainActivity owner, Role role) {
    int radius = size(owner, RADIUS_DP);
    GradientDrawable content = rounded(radius, fill(owner, role));
    content.setColor(fillColors(owner, role));
    if (role == Role.OUTLINED) content.setStroke(size(owner, 1), owner.DIV);
    GradientDrawable mask = rounded(radius, Color.WHITE);
    return new RippleDrawable(ColorStateList.valueOf(alpha(textColor(owner, role), .18f)),
        content, mask);
  }

  private static Drawable ripple(MainActivity owner, int tint, int fill) {
    int radius = size(owner, RADIUS_DP);
    return new RippleDrawable(ColorStateList.valueOf(alpha(tint, .18f)),
        rounded(radius, fill), rounded(radius, Color.WHITE));
  }

  private static GradientDrawable rounded(int radius, int color) {
    GradientDrawable drawable = new GradientDrawable();
    drawable.setShape(GradientDrawable.RECTANGLE);
    drawable.setCornerRadius(radius);
    drawable.setColor(color);
    return drawable;
  }

  private static int fill(MainActivity owner, Role role) {
    if (role == Role.PRIMARY) return owner.PRIMARY;
    if (role == Role.TONAL) return mix(owner.SURFACE, owner.PRIMARY, owner.darkMode ? .20f : .12f);
    if (role == Role.DESTRUCTIVE) {
      return owner.darkMode ? Color.rgb(140, 29, 24) : Color.rgb(249, 222, 220);
    }
    return Color.TRANSPARENT;
  }

  private static ColorStateList fillColors(MainActivity owner, Role role) {
    int enabled = fill(owner, role);
    if (role == Role.TEXT) return ColorStateList.valueOf(enabled);
    int disabled = mix(owner.SURFACE, owner.MUTED, owner.darkMode ? .10f : .08f);
    return colored(disabled, enabled);
  }

  private static int textColor(MainActivity owner, Role role) {
    if (role == Role.PRIMARY) return owner.darkMode ? Color.rgb(56, 30, 114) : Color.WHITE;
    if (role == Role.DESTRUCTIVE) {
      return owner.darkMode ? Color.rgb(242, 184, 181) : Color.rgb(179, 38, 30);
    }
    return owner.PRIMARY;
  }

  private static boolean destructive(CharSequence label) {
    String value = label == null ? "" : label.toString();
    return value.contains("删除") || value.contains("移除") || value.contains("清空")
        || value.contains("终止") || value.contains("取消任务");
  }

  private static ColorStateList colored(int disabled, int enabled) {
    return new ColorStateList(
        new int[][]{new int[]{-android.R.attr.state_enabled}, new int[0]},
        new int[]{disabled, enabled});
  }

  private static int mix(int base, int overlay, float amount) {
    return Color.rgb(
        Math.round(Color.red(base) + (Color.red(overlay) - Color.red(base)) * amount),
        Math.round(Color.green(base) + (Color.green(overlay) - Color.green(base)) * amount),
        Math.round(Color.blue(base) + (Color.blue(overlay) - Color.blue(base)) * amount));
  }

  private static int alpha(int color, float value) {
    return Color.argb(Math.round(255 * value), Color.red(color), Color.green(color),
        Color.blue(color));
  }

  private static int size(MainActivity owner, int value) {
    return Math.round(value * owner.getResources().getDisplayMetrics().density);
  }
}
