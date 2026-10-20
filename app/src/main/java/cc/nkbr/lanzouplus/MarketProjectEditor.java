package cc.nkbr.lanzouplus;

import android.text.Editable;
import android.text.InputType;
import android.text.TextWatcher;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.ImageButton;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import java.util.ArrayList;
import java.util.List;
import org.json.JSONObject;

/** Full-page create/edit surface for one locally saved market project. */
final class MarketProjectEditor {
  interface Host {
    void saveProject(Draft draft, SaveListener listener);

    void closeProjectEditor();
  }

  interface SaveListener {
    void failed(String message);
  }

  private static final String VERSION_HELP =
      "自动识别 1.2.3、2026.10.18 或递增整数；预发布后缀仍沿用元数据合同。";

  private static Draft retainedDraft;
  private static java.lang.ref.WeakReference<MarketProjectEditor> activeEditor;

  static final class Draft {
    final MarketProject original;
    String name;
    String title;
    String version;
    String versionMode;
    String publisher;
    String description;
    String icon;
    String publisherAvatar;
    String readme;
    int scrollY;
    boolean saving;
    final List<SourceDestinationPopup.Destination> paths = new ArrayList<>();

    Draft(MarketProject original) {
      this.original = original;
      JSONObject meta = original == null ? new JSONObject() : original.metadata;
      name = meta.optString("name", "lzpmarket-");
      title = meta.optString("title", "");
      version = meta.optString("version", "1.0.0");
      versionMode = versionMode(meta.optString("versionMode", ""), version);
      publisher = meta.optString("publisher", "");
      description = meta.optString("description", "");
      icon = meta.optString("icon", "");
      publisherAvatar = meta.optString("publisherAvatar", "");
      readme = original == null ? "" : original.readme;
    }
  }

  private final MainActivity owner;
  private final Host host;
  private final Draft draft;
  private EditText nameInput;
  private EditText versionInput;
  private EditText iconInput;
  private TextView pathSummary;
  private Button choosePathsButton;
  private Button saveButton;
  private Button cancelButton;
  private boolean saving;

  MarketProjectEditor(MainActivity owner, Host host, MarketProject existing) {
    this(owner, host, new Draft(existing));
  }

  MarketProjectEditor(MainActivity owner, Host host, Draft draft) {
    this.owner = owner;
    this.host = host;
    this.draft = draft;
    retainedDraft = draft;
    activeEditor = new java.lang.ref.WeakReference<>(this);
  }

  static boolean hasRetainedDraft() {
    return retainedDraft != null;
  }

  static Draft retainedDraft() {
    return retainedDraft;
  }

  static boolean isRetainedDraft(Draft draft) {
    return draft != null && retainedDraft == draft;
  }

  static void clearRetainedDraft() {
    retainedDraft = null;
    activeEditor = null;
  }

  void show() {
    owner.base();
    owner.pageKind = 6;
    owner.activeSource = null;
    owner.clearFolderTrail();
    owner.systemBackAction = this::cancel;

    TextView heading =
        owner.primaryHeader(draft.original == null ? "创建仓库工程" : "编辑仓库工程");
    heading.setContentDescription(
        draft.original == null ? "创建仓库工程，独立编辑页面" : "编辑仓库工程，独立编辑页面");
    ImageButton back = owner.iconButton(R.drawable.ic_back, "取消并返回工程列表");
    back.setOnClickListener(v -> cancel());
    owner.pageHeaderRow.addView(
        back, 0, new LinearLayout.LayoutParams(owner.dp(44), owner.dp(48)));

    android.content.res.Configuration configuration = owner.getResources().getConfiguration();
    int screenWidthDp = Math.max(1, configuration.screenWidthDp);
    int screenHeightDp = Math.max(1, configuration.screenHeightDp);
    boolean wideNavigation = screenWidthDp >= 600 && screenWidthDp > screenHeightDp;
    int availableWidth = owner.dp(screenWidthDp)
        - (wideNavigation ? owner.dp(132) : 0)
        - owner.dp(32);
    int contentWidth = Math.max(1, Math.min(availableWidth, owner.dp(980)));
    boolean wide = contentWidth >= owner.dp(680);
    ScrollView scroll = new ScrollView(owner);
    scroll.setFillViewport(true);
    scroll.setOnScrollChangeListener(
        (view, scrollX, scrollY, oldScrollX, oldScrollY) -> {
          if (view.isAttachedToWindow() && view.getWindowToken() != null) {
            draft.scrollY = scrollY;
          }
        });
    LinearLayout content = new LinearLayout(owner);
    content.setOrientation(LinearLayout.VERTICAL);
    content.setPadding(owner.dp(4), owner.dp(8), owner.dp(4), owner.dp(18));
    android.widget.FrameLayout shell = new android.widget.FrameLayout(owner);
    shell.addView(
        content,
        new android.widget.FrameLayout.LayoutParams(
            contentWidth, -2, Gravity.TOP | Gravity.CENTER_HORIZONTAL));
    scroll.addView(shell, new ScrollView.LayoutParams(-1, -2));
    owner.root.addView(scroll, new LinearLayout.LayoutParams(-1, 0, 1));
    scroll.post(() -> scroll.scrollTo(0, draft.scrollY));

    TextView intro = helperText(
        "工程保存在本机。图片支持 HTTPS 地址或本机图片文件，保存后会封装到工程中。");
    intro.setPadding(owner.dp(2), 0, owner.dp(2), owner.dp(12));
    content.addView(intro, new LinearLayout.LayoutParams(-1, -2));

    nameInput = inputField(
        "例如 lzpmarket-tools",
        "仓库名称",
        InputType.TYPE_CLASS_TEXT,
        draft.name);
    EditText titleInput = inputField(
        "列表和仓库页显示的标题",
        "展示名称",
        InputType.TYPE_CLASS_TEXT,
        draft.title);
    versionInput = inputField(
        "例如 1.0.0",
        "版本号",
        InputType.TYPE_CLASS_TEXT,
        draft.version);
    TextView publisherSummary = helperText("登录 GitHub 后自动读取名称和头像，发布时使用当前账号。");
    try {
      GithubAccountClient.Profile profile = GithubAccountClient.production(owner).cachedProfile();
      if (profile != null) {
        draft.publisher = profile.login;
        draft.publisherAvatar = profile.avatarUrl;
        publisherSummary.setText("@" + profile.login + " · 名称与头像来自当前 GitHub 账号");
      }
    } catch (java.io.IOException ignored) {}
    EditText descriptionInput = multilineField(
        "简要介绍仓库内容",
        "简介",
        3,
        6,
        draft.description);
    iconInput = inputField(
        "https://… 或 /storage/…/icon.png",
        "仓库图标",
        InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_URI,
        draft.icon);
    EditText readmeInput = multilineField(
        "支持 Markdown、图片与常用排版",
        "README 文档",
        8,
        14,
        draft.readme);

    addPair(
        content,
        field("仓库名称", "市场上传时需使用 lzpmarket-名称。", nameInput, 54),
        field("展示名称", "留空时使用仓库名称。", titleInput, 54),
        wide);
    content.addView(
        field("版本号", VERSION_HELP, versionInput, 54),
        new LinearLayout.LayoutParams(-1, -2));
    addPair(
        content,
        field("发布者（GitHub 账号）", "无需手动填写名称和头像。", publisherSummary, -2),
        field("简介", "支持多行文本。", descriptionInput, -2),
        wide);
    content.addView(imageField("仓库图标", "支持图片 URL 或通过系统选择图片。", iconInput),
        new LinearLayout.LayoutParams(-1,-2));

    LinearLayout readmeField =
        field("README 文档", "README 中的图片会随工程一起保存。", readmeInput, -2);
    Button addImage = secondaryButton("在 README 中添加图片");
    addImage.setOnClickListener(v -> MarketFilePicker.choose(
        owner,
        ".png,.jpg,.jpeg,.webp,.gif",
        file -> readmeInput.append("\n![图片](" + file.getAbsolutePath() + ")\n")));
    readmeField.addView(addImage, new LinearLayout.LayoutParams(-2, owner.dp(48)));
    content.addView(readmeField, new LinearLayout.LayoutParams(-1, -2));

    pathSummary = helperText("");
    pathSummary.setTextIsSelectable(true);
    choosePathsButton = secondaryButton(
        draft.original == null ? "选择打包源路径（可多选）" : "重新选择打包源路径");
    choosePathsButton.setOnClickListener(v -> choosePaths());
    LinearLayout pathsField = field("打包源路径", "", pathSummary, -2);
    pathsField.addView(choosePathsButton, new LinearLayout.LayoutParams(-2, owner.dp(48)));
    content.addView(pathsField, new LinearLayout.LayoutParams(-1, -2));
    refreshPathSummary();

    LinearLayout actions = new LinearLayout(owner);
    actions.setGravity(Gravity.CENTER_VERTICAL);
    actions.setPadding(owner.dp(2), owner.dp(6), owner.dp(2), owner.dp(4));
    cancelButton = secondaryButton("取消并返回");
    cancelButton.setOnClickListener(v -> cancel());
    saveButton = primaryButton("保存工程");
    saveButton.setOnClickListener(v -> save());
    LinearLayout.LayoutParams cancelParams =
        new LinearLayout.LayoutParams(0, owner.dp(50), 1);
    cancelParams.setMargins(0, 0, owner.dp(8), 0);
    actions.addView(cancelButton, cancelParams);
    actions.addView(saveButton, new LinearLayout.LayoutParams(0, owner.dp(50), 1));
    android.widget.FrameLayout actionShell = new android.widget.FrameLayout(owner);
    actionShell.addView(
        actions,
        new android.widget.FrameLayout.LayoutParams(
            contentWidth, -2, Gravity.CENTER_HORIZONTAL));
    owner.root.addView(actionShell, new LinearLayout.LayoutParams(-1, owner.dp(60)));
    saving = draft.saving;
    if (saving) {
      saveButton.setEnabled(false);
      saveButton.setText("正在保存图片与工程…");
      cancelButton.setEnabled(false);
    }

    watch(nameInput, value -> draft.name = value);
    watch(titleInput, value -> draft.title = value);
    watch(versionInput, value -> {
      draft.version = value;
      String mode = versionMode(draft.versionMode, value);
      if (mode != null) draft.versionMode = mode;
    });
    watch(descriptionInput, value -> draft.description = value);
    watch(iconInput, value -> draft.icon = value);
    watch(readmeInput, value -> draft.readme = value);
  }

  private void choosePaths() {
    if (saving || draft.saving) return;
    int session = owner.navigationSession;
    choosePathsButton.setEnabled(false);
    choosePathsButton.setText("正在读取路径…");
    owner.io.execute(() -> {
      try {
        List<Models.Source> values = owner.core.knownCategorySources();
        owner.runOnUiThread(() -> {
          if (session != owner.navigationSession) return;
          choosePathsButton.setEnabled(true);
          choosePathsButton.setText(
              draft.original == null ? "选择打包源路径（可多选）" : "重新选择打包源路径");
          SourceDestinationPopup.select(
              owner,
              new SourcePathCatalog(owner, values),
              selected -> {
                if (session != owner.navigationSession) return;
                draft.paths.clear();
                if (selected != null) draft.paths.addAll(selected);
                refreshPathSummary();
              });
        });
      } catch (Exception error) {
        owner.runOnUiThread(() -> {
          if (session != owner.navigationSession) return;
          choosePathsButton.setEnabled(true);
          choosePathsButton.setText(
              draft.original == null ? "选择打包源路径（可多选）" : "重新选择打包源路径");
          owner.showNotice(owner.friendlyError(error), true);
        });
      }
    });
  }

  private void refreshPathSummary() {
    if (pathSummary == null) return;
    StringBuilder text = new StringBuilder();
    if (!draft.paths.isEmpty()) {
      text.append("已选择 ").append(draft.paths.size()).append(" 个路径");
      int shown = Math.min(6, draft.paths.size());
      for (int i = 0; i < shown; i++) {
        SourceDestinationPopup.Destination path = draft.paths.get(i);
        String label = path.path == null || path.path.trim().isEmpty()
            ? path.category
            : path.path;
        text.append('\n').append("• ").append(label);
      }
      if (draft.paths.size() > shown) {
        text.append('\n').append("• 另有 ").append(draft.paths.size() - shown).append(" 个路径");
      }
      choosePathsButton.setText("重新选择打包源路径");
    } else if (draft.original != null && draft.original.sourcePaths.length() > 0) {
      text.append("保留现有 ")
          .append(draft.original.sourcePaths.length())
          .append(" 个打包路径；重新选择会整体替换。");
      choosePathsButton.setText("重新选择打包源路径");
    } else {
      text.append(draft.original == null
          ? "尚未选择。创建工程前至少选择一个分类或目录。"
          : "当前工程没有保存路径，可重新选择。");
    }
    pathSummary.setText(text.toString());
    pathSummary.setContentDescription(text.toString());
  }

  private void save() {
    if (saving || draft.saving) return;
    String name = nameInput.getText().toString().trim();
    String version = versionInput.getText().toString().trim();
    if (name.isEmpty()) {
      nameInput.setError("请输入仓库名称");
      nameInput.requestFocus();
      return;
    }
    if (!name.matches("[A-Za-z0-9_.-]+")) {
      nameInput.setError("只能使用字母、数字、点、下划线和连字符");
      nameInput.requestFocus();
      return;
    }
    if (version.isEmpty()) {
      versionInput.setError("请输入版本号");
      versionInput.requestFocus();
      return;
    }
    String resolvedMode;
    try {
      resolvedMode = MarketRepository.resolveVersionMode(draft.versionMode, version);
    } catch (java.io.IOException invalid) {
      versionInput.setError(VERSION_HELP);
      versionInput.requestFocus();
      return;
    }
    draft.versionMode = resolvedMode;
    if (draft.original == null && draft.paths.isEmpty()) {
      owner.showNotice("请先选择打包源路径", true);
      choosePathsButton.requestFocus();
      return;
    }
    saving = true;
    draft.saving = true;
    saveButton.setEnabled(false);
    saveButton.setText("正在保存图片与工程…");
    cancelButton.setEnabled(false);
    host.saveProject(draft, this::onSaveFailed);
  }

  private void onSaveFailed(String message) {
    MarketProjectEditor current = activeEditor == null ? null : activeEditor.get();
    if (current == null) {
      draft.saving = false;
      saving = false;
      owner.showNotice("保存失败：" + message, true);
      return;
    }
    current.draft.saving = false;
    current.saving = false;
    current.saveButton.setEnabled(true);
    current.saveButton.setText("保存工程");
    current.cancelButton.setEnabled(true);
    current.owner.showNotice("保存失败：" + message, true);
  }

  private void cancel() {
    if (saving) {
      owner.systemBackAction = this::cancel;
      owner.showNotice("正在保存工程，请稍候", false);
      return;
    }
    host.closeProjectEditor();
  }

  private EditText inputField(String hint, String label, int type, String value) {
    EditText input = owner.sourceInput(hint, type);
    input.setText(value);
    input.setContentDescription(label + "，" + hint);
    input.setBackground(owner.shape(owner.SURFACE, 14));
    input.setPadding(owner.dp(14), 0, owner.dp(14), 0);
    return input;
  }

  private EditText multilineField(
      String hint, String label, int minLines, int maxLines, String value) {
    EditText input = inputField(
        hint, label, InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_MULTI_LINE, value);
    input.setSingleLine(false);
    input.setMinLines(minLines);
    input.setMaxLines(maxLines);
    input.setGravity(Gravity.TOP | Gravity.START);
    input.setPadding(owner.dp(14), owner.dp(10), owner.dp(14), owner.dp(10));
    return input;
  }

  private LinearLayout imageField(String label, String helper, EditText input) {
    LinearLayout group = field(label, helper, input, 54);
    Button pick = secondaryButton("选择本机图片");
    pick.setOnClickListener(v -> {
      final Draft selectedDraft = draft;
      MarketFilePicker.chooseImage(owner, uri -> {
        if (!isRetainedDraft(selectedDraft)) return;
        selectedDraft.icon = uri;
        MarketProjectEditor current = activeEditor == null ? null : activeEditor.get();
        if (current != null && current.draft == selectedDraft) current.iconInput.setText(uri);
      });
    });
    group.addView(pick, new LinearLayout.LayoutParams(-2, owner.dp(48)));
    return group;
  }

  private LinearLayout field(String label, String helper, View control, int height) {
    LinearLayout group = new LinearLayout(owner);
    group.setOrientation(LinearLayout.VERTICAL);
    group.setPadding(0, 0, 0, owner.dp(14));
    TextView title = owner.text(label, 15, owner.TEXT);
    title.setTypeface(android.graphics.Typeface.DEFAULT, android.graphics.Typeface.BOLD);
    title.setPadding(owner.dp(2), 0, owner.dp(2), owner.dp(4));
    group.addView(title, new LinearLayout.LayoutParams(-1, -2));
    group.addView(
        control,
        new LinearLayout.LayoutParams(-1, height < 0 ? -2 : owner.dp(height)));
    if (helper != null && !helper.isEmpty()) {
      TextView hint = helperText(helper);
      hint.setPadding(owner.dp(2), owner.dp(4), owner.dp(2), 0);
      group.addView(hint, new LinearLayout.LayoutParams(-1, -2));
    }
    return group;
  }

  private void addPair(
      LinearLayout parent, LinearLayout first, LinearLayout second, boolean wide) {
    if (!wide) {
      parent.addView(first, new LinearLayout.LayoutParams(-1, -2));
      parent.addView(second, new LinearLayout.LayoutParams(-1, -2));
      return;
    }
    LinearLayout row = new LinearLayout(owner);
    row.setOrientation(LinearLayout.HORIZONTAL);
    row.setGravity(Gravity.TOP);
    row.addView(first, new LinearLayout.LayoutParams(0, -2, 1));
    View gap = new View(owner);
    row.addView(gap, new LinearLayout.LayoutParams(owner.dp(16), 1));
    row.addView(second, new LinearLayout.LayoutParams(0, -2, 1));
    parent.addView(row, new LinearLayout.LayoutParams(-1, -2));
  }

  private TextView helperText(String text) {
    TextView view = owner.text(text, 12, owner.MUTED);
    view.setGravity(Gravity.START | Gravity.TOP);
    view.setLineSpacing(0, 1.08f);
    return view;
  }

  private Button secondaryButton(String label) {
    Button button = owner.toolbarTextButton(label);
    button.setTextSize(13);
    button.setTextColor(owner.PRIMARY);
    button.setGravity(Gravity.CENTER);
    button.setPadding(owner.dp(14), 0, owner.dp(14), 0);
    button.setMinHeight(owner.dp(48));
    button.setBackground(owner.filterRipple(owner.solidShape(owner.SURFACE, 14)));
    button.setContentDescription(label);
    return button;
  }

  private Button primaryButton(String label) {
    Button button = owner.toolbarTextButton(label);
    button.setTextSize(14);
    button.setTextColor(owner.BG);
    button.setBackground(owner.filterRipple(owner.solidShape(owner.PRIMARY, 16)));
    button.setContentDescription(label);
    return button;
  }

  private void watch(EditText input, java.util.function.Consumer<String> setter) {
    input.addTextChangedListener(new TextWatcher() {
      @Override
      public void beforeTextChanged(CharSequence s, int start, int count, int after) {}

      @Override
      public void onTextChanged(CharSequence s, int start, int before, int count) {
        input.setError(null);
        setter.accept(s == null ? "" : s.toString());
      }

      @Override
      public void afterTextChanged(Editable e) {}
    });
  }

  private static String versionMode(String requested, String version) {
    try {
      return MarketRepository.resolveVersionMode(requested, version);
    } catch (java.io.IOException invalid) {
      return null;
    }
  }
}
