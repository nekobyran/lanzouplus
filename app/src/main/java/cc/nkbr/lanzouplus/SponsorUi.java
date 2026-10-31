package cc.nkbr.lanzouplus;

import android.app.AlertDialog;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Color;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;
import java.io.IOException;

/** Sponsor popup backed by the website's already published QR asset. */
final class SponsorUi {
  /** Verified existing GitHub file corresponding to website/assets/sponsor.jpg. */
  private static final String REMOTE_URL =
      "https://raw.githubusercontent.com/nekobyran/lanzouplus/main/"
          + "Screenshot_2026-07-17-22-38-28-48_3915bacb930634b7e206116f9dc9486f.jpg";
  private static final int MAX_BYTES = 4 * 1024 * 1024;

  private SponsorUi() {}

  static View settingsRow(MainActivity owner) {
    return owner.settingsAction(R.drawable.ic_star, owner.getString(R.string.sponsor_entry),
        view -> show(owner));
  }

  static void show(MainActivity owner) {
    LinearLayout panel = new LinearLayout(owner);
    panel.setOrientation(LinearLayout.VERTICAL);
    panel.setPadding(owner.dp(20), owner.dp(8), owner.dp(20), 0);

    TextView description = owner.text(owner.getString(R.string.sponsor_description), 13, owner.MUTED);
    description.setLineSpacing(owner.dp(2), 1f);
    panel.addView(description, new LinearLayout.LayoutParams(-1, -2));

    ImageView image = new ImageView(owner);
    image.setScaleType(ImageView.ScaleType.FIT_CENTER);
    image.setAdjustViewBounds(true);
    image.setContentDescription(owner.getString(R.string.sponsor_image_description));
    image.setPadding(owner.dp(8), owner.dp(8), owner.dp(8), owner.dp(8));
    image.setBackground(SharedPopupSurface.solid(Color.WHITE, owner.dp(16)));
    LinearLayout.LayoutParams imageParams = new LinearLayout.LayoutParams(-1, owner.dp(310));
    imageParams.setMargins(0, owner.dp(14), 0, owner.dp(10));
    panel.addView(image, imageParams);

    TextView status = owner.text(owner.getString(R.string.sponsor_loading), 12, owner.MUTED);
    status.setGravity(Gravity.CENTER);
    status.setLineSpacing(owner.dp(2), 1f);
    panel.addView(status, new LinearLayout.LayoutParams(-1, -2));

    Button retry = AppButtons.text(owner, owner.getString(R.string.sponsor_retry), 14);
    retry.setVisibility(View.GONE);
    LinearLayout.LayoutParams retryParams = new LinearLayout.LayoutParams(-1, owner.dp(48));
    retryParams.setMargins(0, owner.dp(10), 0, owner.dp(4));
    panel.addView(retry, retryParams);

    AlertDialog dialog = new AlertDialog.Builder(owner)
        .setTitle(R.string.sponsor_title)
        .setView(SharedPopupSurface.dialogScroll(owner, panel, 600))
        .setNegativeButton(R.string.sponsor_close, null)
        .create();
    owner.showRounded(dialog);

    final boolean[] loading = {false};
    Runnable load = () -> {
      if (loading[0]) return;
      loading[0] = true;
      status.setText(R.string.sponsor_loading);
      status.setTextColor(owner.MUTED);
      retry.setVisibility(View.GONE);
      Bitmap cached = owner.imageCache.get(REMOTE_URL);
      if (cached != null) {
        image.setImageBitmap(cached);
        status.setText("");
        loading[0] = false;
        return;
      }
      owner.imageIo.execute(() -> {
        Bitmap bitmap = null;
        String failure = "";
        try {
          bitmap = decode();
          if (bitmap == null) throw new IOException("图片无效或为空");
        } catch (Exception error) {
          failure = error.getMessage() == null ? "无法读取赞助码" : error.getMessage();
        }
        Bitmap ready = bitmap;
        String problem = failure;
        owner.ui.post(() -> {
          loading[0] = false;
          if (!dialog.isShowing()) return;
          if (ready != null) {
            owner.imageCache.put(REMOTE_URL, ready);
            image.setImageBitmap(ready);
            status.setText("");
            retry.setVisibility(View.GONE);
          } else {
            status.setText(owner.getString(R.string.sponsor_failed, problem));
            status.setTextColor(owner.darkMode ? Color.rgb(242, 184, 181) : Color.rgb(179, 38, 30));
            retry.setVisibility(View.VISIBLE);
          }
        });
      });
    };
    retry.setOnClickListener(view -> load.run());
    load.run();
  }

  private static Bitmap decode() throws Exception {
    byte[] bytes = MarketRepositoryClient.httpGet(REMOTE_URL, MAX_BYTES);
    return BitmapFactory.decodeByteArray(bytes, 0, bytes.length);
  }
}
