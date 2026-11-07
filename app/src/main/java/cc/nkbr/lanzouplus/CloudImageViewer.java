package cc.nkbr.lanzouplus;

import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.animation.ValueAnimator;
import android.annotation.SuppressLint;
import android.app.Activity;
import android.app.Dialog;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.DialogInterface;
import android.content.res.ColorStateList;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Color;
import android.graphics.Matrix;
import android.graphics.RectF;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.RippleDrawable;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.text.TextUtils;
import android.util.DisplayMetrics;
import android.util.LruCache;
import android.util.TypedValue;
import android.view.GestureDetector;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.ScaleGestureDetector;
import android.view.VelocityTracker;
import android.view.View;
import android.view.ViewConfiguration;
import android.view.ViewGroup;
import android.view.Window;
import android.view.WindowInsets;
import android.view.WindowInsetsController;
import android.view.WindowManager;
import android.view.animation.DecelerateInterpolator;
import android.widget.FrameLayout;
import android.widget.ImageButton;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

import java.io.File;
import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * 独立的本地图片全屏查看器。
 * <p>特性：
 * <ul>
 *   <li>Android 原生全屏无边框黑色背景沉浸式体验</li>
 *   <li>右上角半透明关闭按钮及返回键安全关闭</li>
 *   <li>双指手势缩放（1.0x - 5.0x）与双击快速放大/重置</li>
 *   <li>全尺寸状态下流畅左右滑动切换相邻图片（1:1跟手与吸附动画）</li>
 *   <li>仅加载本地已有文件，带内存 LRU 缓存与降采样防 OOM，无任何网络行为</li>
 *   <li>底部展示本地保存完整绝对路径与文件大小，支持点击一键复制路径</li>
 *   <li>生命周期安全：Activity 销毁检测、异步解码取消、弱引用与弹窗安全回收</li>
 * </ul>
 */
public final class CloudImageViewer {

  private final WeakReference<Activity> activityRef;
  private final List<File> imageFiles;
  private final int initialIndex;
  interface ImageResolver { File resolve(File key) throws Exception; default boolean supportsRefresh(){return false;} default File refresh(File key)throws Exception{return resolve(key);} default void close() {} }
  private ImageResolver imageResolver;

  private Dialog dialog;
  private Handler mainHandler;
  private ExecutorService decodeExecutor;
  private LruCache<String, Bitmap> memoryCache;
  private final java.util.Map<String,Integer> imageGenerations=new java.util.HashMap<>();

  private int currentIndex;

  // UI 组件
  private FrameLayout rootLayout;
  private View topBar;
  private View bottomBar;
  private TextView titleText;
  private TextView subtitleText;
  private TextView pathText;
  private TextView sizeText;
  private ProgressBar loadingProgress;
  private TextView errorPrompt;

  // 三槽视图复用（前一张、当前张、下一张）
  private PinchZoomImageView currView;
  private PinchZoomImageView prevView;
  private PinchZoomImageView nextView;

  // 手势与滑动参数
  private int touchSlop;
  private float downX;
  private float downY;
  private float lastX;
  private boolean isSwiping;
  private VelocityTracker velocityTracker;
  private ValueAnimator snapAnimator;

  /**
   * 静态展示入口：展示多张本地图片
   *
   * @param host 宿主 Activity
   * @param files 已下载的本地图片文件列表
   * @param initialIndex 初始定位索引（从 0 开始）
   */
  public static void show(Activity host, List<File> files, int initialIndex) {
    if (host == null || host.isFinishing()) return;
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.JELLY_BEAN_MR1 && host.isDestroyed()) return;
    if (files == null || files.isEmpty()) return;
    new CloudImageViewer(host, files, initialIndex).showInternal();
  }

  /**
   * 静态展示入口：展示单张本地图片
   *
   * @param host 宿主 Activity
   * @param file 已下载的单张本地图片
   */
  static void show(Activity host, List<File> keys, int initialIndex, ImageResolver resolver) {
    if (host == null || host.isFinishing() || keys == null || keys.isEmpty()) return;
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.JELLY_BEAN_MR1 && host.isDestroyed()) return;
    CloudImageViewer viewer = new CloudImageViewer(host, keys, initialIndex);
    viewer.imageResolver = resolver;
    viewer.showInternal();
  }

  public static void show(Activity host, File file) {
    if (file == null) return;
    show(host, Collections.singletonList(file), 0);
  }

  private CloudImageViewer(Activity host, List<File> files, int initialIndex) {
    this.activityRef = new WeakReference<>(host);
    this.imageFiles = new ArrayList<>(files);
    this.initialIndex = Math.max(0, Math.min(initialIndex, files.size() - 1));
    this.currentIndex = this.initialIndex;
    this.mainHandler = new Handler(Looper.getMainLooper());
    this.decodeExecutor = Executors.newFixedThreadPool(2, r -> {
      Thread t = new Thread(r, "cloud-image-viewer-decode");
      t.setDaemon(true);
      t.setPriority(Thread.NORM_PRIORITY - 1);
      return t;
    });

    int maxMemoryKb = (int) (Runtime.getRuntime().maxMemory() / 1024);
    int cacheSizeKb = Math.max(16 * 1024, maxMemoryKb / 8);
    this.memoryCache = new LruCache<String, Bitmap>(cacheSizeKb) {
      @Override
      protected int sizeOf(String key, Bitmap value) {
        return value != null ? value.getByteCount() / 1024 : 0;
      }
    };
  }

  @SuppressLint("ClickableViewAccessibility")
  private void showInternal() {
    Activity host = activityRef.get();
    if (host == null || host.isFinishing()) return;
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.JELLY_BEAN_MR1 && host.isDestroyed()) return;

    this.touchSlop = ViewConfiguration.get(host).getScaledTouchSlop();

    dialog = new Dialog(host, android.R.style.Theme_Black_NoTitleBar_Fullscreen);
    dialog.requestWindowFeature(Window.FEATURE_NO_TITLE);

    Window window = dialog.getWindow();
    if (window != null) {
      window.setBackgroundDrawable(new ColorDrawable(Color.BLACK));
      window.setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT);
      if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
        window.setDecorFitsSystemWindows(false);
        WindowInsetsController controller = window.getInsetsController();
        if (controller != null) {
          controller.hide(WindowInsets.Type.statusBars() | WindowInsets.Type.navigationBars());
          controller.setSystemBarsBehavior(WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE);
        }
      } else {
        int flags = View.SYSTEM_UI_FLAG_LAYOUT_STABLE
            | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
            | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
            | View.SYSTEM_UI_FLAG_FULLSCREEN
            | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
            | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY;
        window.getDecorView().setSystemUiVisibility(flags);
      }
      window.addFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN);
    }

    buildViewHierarchy(host);
    dialog.setContentView(rootLayout);

    dialog.setOnDismissListener(d -> cleanup());
    dialog.setOnCancelListener(d -> cleanup());

    try {
      dialog.show();
      bindIndex(currentIndex, false);
    } catch (Throwable ignored) {
      cleanup();
    }
  }

  private void buildViewHierarchy(Activity host) {
    rootLayout = new FrameLayout(host);
    rootLayout.setBackgroundColor(Color.BLACK);

    // 三槽图片容器
    FrameLayout pagerContainer = new FrameLayout(host);
    pagerContainer.setLayoutParams(new FrameLayout.LayoutParams(
        ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

    prevView = new PinchZoomImageView(host);
    currView = new PinchZoomImageView(host);
    nextView = new PinchZoomImageView(host);

    Runnable toggleBarsAction = this::toggleControls;
    prevView.setOnSingleTapListener(toggleBarsAction);
    currView.setOnSingleTapListener(toggleBarsAction);
    nextView.setOnSingleTapListener(toggleBarsAction);
    if(imageResolver!=null&&imageResolver.supportsRefresh()){
      currView.setOnImageLongPressListener(this::refreshCurrentImage);
    }

    pagerContainer.addView(prevView);
    pagerContainer.addView(nextView);
    pagerContainer.addView(currView);
    rootLayout.addView(pagerContainer);

    // 加载指示器
    loadingProgress = new ProgressBar(host);
    FrameLayout.LayoutParams lpProg = new FrameLayout.LayoutParams(dp(44), dp(44));
    lpProg.gravity = Gravity.CENTER;
    loadingProgress.setLayoutParams(lpProg);
    loadingProgress.setVisibility(View.GONE);
    rootLayout.addView(loadingProgress);

    // 错误文字提示
    errorPrompt = new TextView(host);
    errorPrompt.setTextColor(Color.rgb(255, 180, 171));
    errorPrompt.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
    errorPrompt.setGravity(Gravity.CENTER);
    FrameLayout.LayoutParams lpErr = new FrameLayout.LayoutParams(
        ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
    lpErr.gravity = Gravity.CENTER;
    errorPrompt.setLayoutParams(lpErr);
    errorPrompt.setVisibility(View.GONE);
    rootLayout.addView(errorPrompt);

    // 顶部操作栏
    topBar = createTopBar(host);
    rootLayout.addView(topBar);

    // 底部信息栏
    bottomBar = createBottomBar(host);
    bottomBar.setVisibility(View.GONE);
    rootLayout.addView(bottomBar);

    // 触摸监听用于左右切换
    setupPagerTouch(pagerContainer);
  }

  private View createTopBar(Activity host) {
    LinearLayout bar = new LinearLayout(host);
    bar.setOrientation(LinearLayout.HORIZONTAL);
    bar.setGravity(Gravity.CENTER_VERTICAL);
    bar.setBackgroundColor(Color.TRANSPARENT);
    int padH = dp(16);
    int padT = dp(Build.VERSION.SDK_INT >= Build.VERSION_CODES.P ? 40 : 24);
    int padB = dp(16);
    bar.setPadding(padH, padT, padH, padB);

    FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(
        ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
    lp.gravity = Gravity.TOP;
    bar.setLayoutParams(lp);

    // 标题区域
    LinearLayout titleLayout = new LinearLayout(host);
    titleLayout.setOrientation(LinearLayout.VERTICAL);
    LinearLayout.LayoutParams lpTitle = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1.0f);
    titleLayout.setLayoutParams(lpTitle);

    titleText = new TextView(host);
    titleText.setTextColor(Color.WHITE);
    titleText.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16);
    titleText.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
    titleLayout.addView(titleText);

    subtitleText = new TextView(host);
    subtitleText.setTextColor(Color.rgb(200, 200, 200));
    subtitleText.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
    subtitleText.setSingleLine(true);
    subtitleText.setEllipsize(TextUtils.TruncateAt.MIDDLE);
    titleLayout.addView(subtitleText);

    titleLayout.setVisibility(View.INVISIBLE);
    bar.addView(titleLayout);

    // 右上角关闭按钮
    ImageButton closeButton = new ImageButton(host);
    closeButton.setImageResource(R.drawable.ic_close);
    closeButton.setColorFilter(Color.WHITE);
    closeButton.setScaleType(ImageView.ScaleType.CENTER_INSIDE);
    closeButton.setBackground(createCircularRipple(host, Color.argb(80, 255, 255, 255)));
    closeButton.setContentDescription("关闭图片查看");
    int btnSize = dp(40);
    LinearLayout.LayoutParams lpBtn = new LinearLayout.LayoutParams(btnSize, btnSize);
    closeButton.setLayoutParams(lpBtn);
    closeButton.setOnClickListener(v -> dismiss());

    bar.addView(closeButton);
    return bar;
  }

  private View createBottomBar(Activity host) {
    LinearLayout bar = new LinearLayout(host);
    bar.setOrientation(LinearLayout.VERTICAL);
    bar.setBackground(createBottomGradient());
    int padH = dp(16);
    int padT = dp(16);
    int padB = dp(Build.VERSION.SDK_INT >= Build.VERSION_CODES.P ? 32 : 16);
    bar.setPadding(padH, padT, padH, padB);

    FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(
        ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
    lp.gravity = Gravity.BOTTOM;
    bar.setLayoutParams(lp);

    // 位置标签与提示
    LinearLayout headRow = new LinearLayout(host);
    headRow.setOrientation(LinearLayout.HORIZONTAL);
    headRow.setGravity(Gravity.CENTER_VERTICAL);

    TextView label = new TextView(host);
    label.setText("保存位置（点击复制）");
    label.setTextColor(Color.rgb(208, 188, 255));
    label.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
    label.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
    headRow.addView(label, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1.0f));

    sizeText = new TextView(host);
    sizeText.setTextColor(Color.rgb(180, 180, 180));
    sizeText.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
    headRow.addView(sizeText);

    bar.addView(headRow);

    // 路径详情
    pathText = new TextView(host);
    pathText.setTextColor(Color.rgb(240, 240, 240));
    pathText.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
    pathText.setMaxLines(3);
    pathText.setEllipsize(TextUtils.TruncateAt.MIDDLE);
    LinearLayout.LayoutParams lpPath = new LinearLayout.LayoutParams(
        ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
    lpPath.topMargin = dp(4);
    pathText.setLayoutParams(lpPath);
    bar.addView(pathText);

    // 点击底部区域一键复制保存路径
    bar.setOnClickListener(v -> {
      File current = getCurrentFile();
      if (current != null && host != null && !host.isFinishing()) {
        try {
          ClipboardManager cm = (ClipboardManager) host.getSystemService(Context.CLIPBOARD_SERVICE);
          if (cm != null) {
            cm.setPrimaryClip(ClipData.newPlainText("ImagePath", current.getAbsolutePath()));
            Toast.makeText(host, "已复制图片保存位置", Toast.LENGTH_SHORT).show();
          }
        } catch (Throwable ignored) {}
      }
    });

    return bar;
  }

  @SuppressLint("ClickableViewAccessibility")
  private void setupPagerTouch(View container) {
    container.setOnTouchListener((v, event) -> {
      if (currView.getScale() > 1.05f) {
        // 放大状态下不截获左右切图，优先手势平移图片
        return currView.dispatchTouchEvent(event);
      }

      if (velocityTracker == null) {
        velocityTracker = VelocityTracker.obtain();
      }
      velocityTracker.addMovement(event);

      int action = event.getActionMasked();
      switch (action) {
        case MotionEvent.ACTION_DOWN:
          downX = event.getX();
          downY = event.getY();
          lastX = downX;
          isSwiping = false;
          if (snapAnimator != null && snapAnimator.isRunning()) {
            snapAnimator.cancel();
          }
          currView.dispatchTouchEvent(event);
          return true;

        case MotionEvent.ACTION_MOVE:
          float dx = event.getX() - downX;
          float dy = event.getY() - downY;
          if (!isSwiping) {
            if (Math.abs(dx) > touchSlop && Math.abs(dx) > Math.abs(dy)) {
              isSwiping = true;
              MotionEvent cancel = MotionEvent.obtain(event);
              cancel.setAction(MotionEvent.ACTION_CANCEL);currView.dispatchTouchEvent(cancel);cancel.recycle();
            }
          }
          if (isSwiping) {
            float moveX = event.getX() - downX;
            // 边缘阻尼
            if ((moveX > 0 && currentIndex == 0) || (moveX < 0 && currentIndex == imageFiles.size() - 1)) {
              moveX *= 0.35f;
            }
            int width = container.getWidth();
            if (width > 0) {
              currView.setTranslationX(moveX);
              prevView.setTranslationX(-width + moveX);
              nextView.setTranslationX(width + moveX);
            }
            return true;
          }
          currView.dispatchTouchEvent(event);
          return true;

        case MotionEvent.ACTION_UP:
        case MotionEvent.ACTION_CANCEL:
          if (isSwiping) {
            velocityTracker.computeCurrentVelocity(1000);
            float vx = velocityTracker.getXVelocity();
            float finalDx = currView.getTranslationX();
            int width = container.getWidth();
            boolean toNext = (finalDx < -width * 0.2f || vx < -800) && currentIndex < imageFiles.size() - 1;
            boolean toPrev = (finalDx > width * 0.2f || vx > 800) && currentIndex > 0;

            if (toNext) {
              animateSnap(finalDx, -width, () -> {
                currentIndex++;
                bindIndex(currentIndex, true);
              });
            } else if (toPrev) {
              animateSnap(finalDx, width, () -> {
                currentIndex--;
                bindIndex(currentIndex, true);
              });
            } else {
              animateSnap(finalDx, 0, null);
            }
            isSwiping = false;
            if (velocityTracker != null) {
              velocityTracker.recycle();
              velocityTracker = null;
            }
            return true;
          }
          if (velocityTracker != null) {
            velocityTracker.recycle();
            velocityTracker = null;
          }
          return currView.dispatchTouchEvent(event);
      }
      return false;
    });
  }

  private void animateSnap(float fromX, float toX, Runnable onEnd) {
    if (snapAnimator != null && snapAnimator.isRunning()) {
      snapAnimator.cancel();
    }
    int width = rootLayout != null ? rootLayout.getWidth() : 1080;
    snapAnimator = ValueAnimator.ofFloat(fromX, toX);
    snapAnimator.setDuration(220);
    snapAnimator.setInterpolator(new DecelerateInterpolator());
    snapAnimator.addUpdateListener(animation -> {
      float current = (Float) animation.getAnimatedValue();
      if (currView != null) currView.setTranslationX(current);
      if (prevView != null) prevView.setTranslationX(-width + current);
      if (nextView != null) nextView.setTranslationX(width + current);
    });
    snapAnimator.addListener(new AnimatorListenerAdapter() {
      @Override
      public void onAnimationEnd(Animator animation) {
        if (currView != null) currView.setTranslationX(0);
        if (prevView != null) prevView.setTranslationX(-width);
        if (nextView != null) nextView.setTranslationX(width);
        if (onEnd != null) onEnd.run();
      }
    });
    snapAnimator.start();
  }

  private void bindIndex(int index, boolean animate) {
    if (index < 0 || index >= imageFiles.size()) return;
    this.currentIndex = index;

    File currentFile = imageFiles.get(index);
    titleText.setText(String.format(Locale.getDefault(), "%d / %d", index + 1, imageFiles.size()));
    subtitleText.setText(currentFile.getName());
    pathText.setText(currentFile.getAbsolutePath());
    sizeText.setText(formatFileSize(currentFile.length()));

    int width = rootLayout != null && rootLayout.getWidth() > 0 ? rootLayout.getWidth() : 1080;
    currView.setTranslationX(0);
    prevView.setTranslationX(-width);
    nextView.setTranslationX(width);

    currView.resetZoom(false);
    prevView.resetZoom(false);
    nextView.resetZoom(false);

    // 绑定中心图
    loadImageInto(currentFile, currView, true);

    // 预加载前后相邻图，左右滑动零延迟
    if (index > 0) {
      loadImageInto(imageFiles.get(index - 1), prevView, false);
    } else {
      prevView.setImageBitmap(null);
    }
    if (index < imageFiles.size() - 1) {
      loadImageInto(imageFiles.get(index + 1), nextView, false);
    } else {
      nextView.setImageBitmap(null);
    }
  }

  private void refreshCurrentImage(){
    File file=getCurrentFile();if(file==null||imageResolver==null)return;
    String path=file.getAbsolutePath();imageGenerations.put(path,imageGenerations.containsKey(path)?imageGenerations.get(path)+1:1);
    memoryCache.remove(path);currView.setImageBitmap(null);loadImageInto(file,currView,true,true);
  }
  private void loadImageInto(File file, PinchZoomImageView targetView, boolean isCurrent) {
    loadImageInto(file,targetView,isCurrent,false);
  }
  private void loadImageInto(File file, PinchZoomImageView targetView, boolean isCurrent,boolean force) {
    if (file == null) {
      targetView.setImageBitmap(null);
      return;
    }

    String path = file.getAbsolutePath();
    targetView.setTag(path);
    final int generation=imageGenerations.containsKey(path)?imageGenerations.get(path):0;

    Bitmap cached = memoryCache.get(path);
    if (cached != null && !cached.isRecycled()) {
      targetView.setImageBitmap(cached);
      if (isCurrent) {
        loadingProgress.setVisibility(View.GONE);
        errorPrompt.setVisibility(View.GONE);
      }
      return;
    }

    if (isCurrent) {
      loadingProgress.setVisibility(View.VISIBLE);
      errorPrompt.setVisibility(View.GONE);
    }

    decodeExecutor.execute(() -> {
      File resolved = file;
      try { if (imageResolver != null) resolved = force?imageResolver.refresh(file):imageResolver.resolve(file); }
      catch (Exception ignored) { resolved = null; }
      Bitmap bitmap = decodeSampledBitmap(resolved);
      mainHandler.post(() -> {
        Activity host = activityRef.get();
        if (dialog == null || !dialog.isShowing() || host == null || host.isFinishing()) return;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.JELLY_BEAN_MR1 && host.isDestroyed()) return;

        if ((imageGenerations.containsKey(path)?imageGenerations.get(path):0)!=generation) return;
        if (bitmap != null) {
          memoryCache.put(path, bitmap);
          if (path.equals(targetView.getTag())) {
            targetView.setImageBitmap(bitmap);
          }
          if (isCurrent && path.equals(getCurrentFile() != null ? getCurrentFile().getAbsolutePath() : null)) {
            loadingProgress.setVisibility(View.GONE);
            errorPrompt.setVisibility(View.GONE);
          }
        } else if (isCurrent) {
          loadingProgress.setVisibility(View.GONE);
          errorPrompt.setText("无法解析该本地图片");
          errorPrompt.setVisibility(View.VISIBLE);
        }
      });
    });
  }

  private Bitmap decodeSampledBitmap(File file) {
    if (file == null || !file.exists() || !file.isFile() || file.length() == 0) {
      return null;
    }
    try {
      DisplayMetrics dm = activityRef.get() != null
          ? activityRef.get().getResources().getDisplayMetrics()
          : null;
      int targetW = dm != null ? dm.widthPixels * 2 : 2160;
      int targetH = dm != null ? dm.heightPixels * 2 : 3840;

      BitmapFactory.Options opts = new BitmapFactory.Options();
      opts.inJustDecodeBounds = true;
      BitmapFactory.decodeFile(file.getAbsolutePath(), opts);

      if (opts.outWidth <= 0 || opts.outHeight <= 0) return null;

      int sample = 1;
      while ((opts.outWidth / (sample * 2)) >= targetW && (opts.outHeight / (sample * 2)) >= targetH) {
        sample *= 2;
      }
      opts.inSampleSize = sample;
      opts.inJustDecodeBounds = false;
      opts.inPreferredConfig = Bitmap.Config.RGB_565; // 减半内存，避免大图 OOM
      return BitmapFactory.decodeFile(file.getAbsolutePath(), opts);
    } catch (Throwable t) {
      return null;
    }
  }

  private void toggleControls() {}

  public void dismiss() {
    if (dialog != null && dialog.isShowing()) {
      try {
        dialog.dismiss();
      } catch (Throwable ignored) {}
    }
  }

  private void cleanup() {
    if (imageResolver != null) { imageResolver.close(); imageResolver = null; }
    if (snapAnimator != null) {
      snapAnimator.cancel();
      snapAnimator = null;
    }
    if (velocityTracker != null) {
      velocityTracker.recycle();
      velocityTracker = null;
    }
    if (mainHandler != null) {
      mainHandler.removeCallbacksAndMessages(null);
    }
    if (decodeExecutor != null) {
      decodeExecutor.shutdownNow();
    }
    if (memoryCache != null) {
      memoryCache.evictAll();
    }
    if (currView != null) currView.setImageBitmap(null);
    if (prevView != null) prevView.setImageBitmap(null);
    if (nextView != null) nextView.setImageBitmap(null);
  }

  public File getCurrentFile() {
    if (currentIndex >= 0 && currentIndex < imageFiles.size()) {
      return imageFiles.get(currentIndex);
    }
    return null;
  }

  private int dp(float val) {
    Activity host = activityRef.get();
    if (host == null) return (int) (val * 2.0f);
    return (int) (val * host.getResources().getDisplayMetrics().density + 0.5f);
  }

  private static GradientDrawable createTopGradient() {
    return new GradientDrawable(
        GradientDrawable.Orientation.TOP_BOTTOM,
        new int[]{Color.argb(200, 0, 0, 0), Color.argb(120, 0, 0, 0), Color.TRANSPARENT});
  }

  private static GradientDrawable createBottomGradient() {
    return new GradientDrawable(
        GradientDrawable.Orientation.BOTTOM_TOP,
        new int[]{Color.argb(230, 0, 0, 0), Color.argb(150, 0, 0, 0), Color.TRANSPARENT});
  }

  private static RippleDrawable createCircularRipple(Context context, int highlightColor) {
    GradientDrawable shape = new GradientDrawable();
    shape.setShape(GradientDrawable.OVAL);
    shape.setColor(Color.argb(60, 0, 0, 0));
    return new RippleDrawable(ColorStateList.valueOf(highlightColor), shape, null);
  }

  private static String formatFileSize(long bytes) {
    if (bytes <= 0) return "0 B";
    if (bytes < 1024) return bytes + " B";
    if (bytes < 1024 * 1024) return String.format(Locale.getDefault(), "%.1f KB", bytes / 1024.0);
    return String.format(Locale.getDefault(), "%.2f MB", bytes / (1024.0 * 1024.0));
  }

  /**
   * 支持双指缩放、双击放大/重置、单指缩放状态平移的原生 ImageView。
   */
  private static final class PinchZoomImageView extends ImageView {

    private final Matrix matrix = new Matrix();
    private final Matrix initialMatrix = new Matrix();
    private final float[] matrixValues = new float[9];

    private ScaleGestureDetector scaleDetector;
    private GestureDetector gestureDetector;
    private Runnable onSingleTapListener;
    private Runnable onImageLongPressListener;

    private float currentScale = 1.0f; // 相对 baseScale 的缩放倍数
    private float lastTouchX;
    private float lastTouchY;
    private boolean isDragging;
    private boolean isScaling;

    public PinchZoomImageView(Context context) {
      super(context);
      setScaleType(ScaleType.MATRIX);
      initGestures(context);
    }

    public void setOnSingleTapListener(Runnable listener) {
      this.onSingleTapListener = listener;
    }

    public void setOnImageLongPressListener(Runnable listener) { this.onImageLongPressListener=listener; }

    private void initGestures(Context context) {
      scaleDetector = new ScaleGestureDetector(context, new ScaleGestureDetector.SimpleOnScaleGestureListener() {
        @Override
        public boolean onScaleBegin(ScaleGestureDetector detector) {
          isScaling = true;
          return true;
        }

        @Override
        public boolean onScale(ScaleGestureDetector detector) {
          float factor = detector.getScaleFactor();
          float nextScale = currentScale * factor;
          if (nextScale < 1.0f) {
            factor = 1.0f / currentScale;
            currentScale = 1.0f;
          } else if (nextScale > 5.0f) {
            factor = 5.0f / currentScale;
            currentScale = 5.0f;
          } else {
            currentScale = nextScale;
          }
          matrix.postScale(factor, factor, detector.getFocusX(), detector.getFocusY());
          checkMatrixBounds();
          setImageMatrix(matrix);
          return true;
        }

        @Override
        public void onScaleEnd(ScaleGestureDetector detector) {
          isScaling = false;
        }
      });

      gestureDetector = new GestureDetector(context, new GestureDetector.SimpleOnGestureListener() {
        @Override
        public boolean onDoubleTap(MotionEvent e) {
          if (currentScale > 1.05f) {
            resetZoom(true);
          } else {
            zoomTo(2.5f, e.getX(), e.getY(), true);
          }
          return true;
        }

        @Override
        public void onLongPress(MotionEvent e) {
          if (onImageLongPressListener != null) onImageLongPressListener.run();
        }

        @Override
        public boolean onSingleTapConfirmed(MotionEvent e) {
          if (onSingleTapListener != null) {
            onSingleTapListener.run();
          }
          return true;
        }
      });
    }

    @Override
    protected void onLayout(boolean changed, int left, int top, int right, int bottom) {
      super.onLayout(changed, left, top, right, bottom);
      if (changed) {
        fitCenterImage();
      }
    }

    @Override
    public void setImageBitmap(Bitmap bm) {
      super.setImageBitmap(bm);
      fitCenterImage();
    }

    private void fitCenterImage() {
      if (getDrawable() == null || getWidth() <= 0 || getHeight() <= 0) return;
      int dw = getDrawable().getIntrinsicWidth();
      int dh = getDrawable().getIntrinsicHeight();
      if (dw <= 0 || dh <= 0) return;

      int vw = getWidth();
      int vh = getHeight();
      float scale = Math.min((float) vw / dw, (float) vh / dh);
      float dx = (vw - dw * scale) * 0.5f;
      float dy = (vh - dh * scale) * 0.5f;

      matrix.reset();
      matrix.postScale(scale, scale);
      matrix.postTranslate(dx, dy);
      initialMatrix.set(matrix);
      currentScale = 1.0f;
      setImageMatrix(matrix);
    }

    public void resetZoom(boolean animate) {
      if (!animate) {
        matrix.set(initialMatrix);
        currentScale = 1.0f;
        setImageMatrix(matrix);
        return;
      }
      ValueAnimator anim = ValueAnimator.ofFloat(currentScale, 1.0f);
      anim.setDuration(180);
      anim.setInterpolator(new DecelerateInterpolator());
      anim.addUpdateListener(animation -> {
        float val = (Float) animation.getAnimatedValue();
        float factor = val / currentScale;
        currentScale = val;
        matrix.postScale(factor, factor, getWidth() / 2f, getHeight() / 2f);
        checkMatrixBounds();
        setImageMatrix(matrix);
      });
      anim.start();
    }

    private void zoomTo(float targetScale, float focusX, float focusY, boolean animate) {
      if (!animate) {
        float factor = targetScale / currentScale;
        currentScale = targetScale;
        matrix.postScale(factor, factor, focusX, focusY);
        checkMatrixBounds();
        setImageMatrix(matrix);
        return;
      }
      ValueAnimator anim = ValueAnimator.ofFloat(currentScale, targetScale);
      anim.setDuration(200);
      anim.setInterpolator(new DecelerateInterpolator());
      anim.addUpdateListener(animation -> {
        float val = (Float) animation.getAnimatedValue();
        float factor = val / currentScale;
        currentScale = val;
        matrix.postScale(factor, factor, focusX, focusY);
        checkMatrixBounds();
        setImageMatrix(matrix);
      });
      anim.start();
    }

    public float getScale() {
      return currentScale;
    }

    @SuppressLint("ClickableViewAccessibility")
    @Override
    public boolean onTouchEvent(MotionEvent event) {
      scaleDetector.onTouchEvent(event);
      gestureDetector.onTouchEvent(event);

      if (event.getPointerCount() > 1) {
        isDragging = false;
        return true;
      }

      int action = event.getActionMasked();
      switch (action) {
        case MotionEvent.ACTION_DOWN:
          lastTouchX = event.getX();
          lastTouchY = event.getY();
          isDragging = currentScale > 1.05f;
          break;

        case MotionEvent.ACTION_MOVE:
          if (isDragging && !isScaling && currentScale > 1.05f) {
            float dx = event.getX() - lastTouchX;
            float dy = event.getY() - lastTouchY;
            matrix.postTranslate(dx, dy);
            checkMatrixBounds();
            setImageMatrix(matrix);
            lastTouchX = event.getX();
            lastTouchY = event.getY();
          }
          break;

        case MotionEvent.ACTION_UP:
        case MotionEvent.ACTION_CANCEL:
          isDragging = false;
          break;
      }
      return true;
    }

    private void checkMatrixBounds() {
      if (getDrawable() == null) return;
      RectF rect = new RectF(0, 0, getDrawable().getIntrinsicWidth(), getDrawable().getIntrinsicHeight());
      matrix.mapRect(rect);
      float width = rect.width();
      float height = rect.height();
      float deltaX = 0;
      float deltaY = 0;
      int viewWidth = getWidth();
      int viewHeight = getHeight();

      if (width >= viewWidth) {
        if (rect.left > 0) deltaX = -rect.left;
        if (rect.right < viewWidth) deltaX = viewWidth - rect.right;
      } else {
        deltaX = (viewWidth - width) / 2f - rect.left;
      }

      if (height >= viewHeight) {
        if (rect.top > 0) deltaY = -rect.top;
        if (rect.bottom < viewHeight) deltaY = viewHeight - rect.bottom;
      } else {
        deltaY = (viewHeight - height) / 2f - rect.top;
      }

      matrix.postTranslate(deltaX, deltaY);
    }
  }
}