package cc.nkbr.lanzouplus;

import android.app.AlertDialog;
import android.content.Intent;
import android.net.Uri;
import android.view.Gravity;
import android.widget.ImageButton;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.List;
import org.json.JSONArray;
import org.json.JSONObject;

/** Independent, locally saved repository workspace; one row represents one project. */
final class MarketProjects implements MarketProjectEditor.Host {
  private final MainActivity owner;
  private final File directory;

  MarketProjects(MainActivity owner) {
    this.owner = owner;
    directory = new File(owner.getFilesDir(), "market-projects");
  }

  void show() {
    if (owner.suppressUiMotion && MarketProjectEditor.hasRetainedDraft()) {
      new MarketProjectEditor(owner, this, MarketProjectEditor.retainedDraft()).show();
      return;
    }
    if (!owner.suppressUiMotion) MarketProjectEditor.clearRetainedDraft();
    showList();
  }

  private void showList() {
    owner.base();
    owner.pageKind = 6;
    owner.activeSource = null;
    owner.clearFolderTrail();
    owner.systemBackAction = owner::showSources;
    owner.primaryHeader("lzpmarket 仓库工程");

    ImageButton back = owner.iconButton(R.drawable.ic_back, "返回列表");
    back.setOnClickListener(v -> owner.showSources());
    owner.pageHeaderRow.addView(
        back, 0, new LinearLayout.LayoutParams(owner.dp(44), owner.dp(48)));

    ImageButton add = owner.iconButton(R.drawable.ic_add, "创建仓库工程");
    ImageButton importProject = owner.iconButton(R.drawable.ic_folder, "导入明文工程");
    owner.pageHeaderRow.addView(importProject);
    owner.pageHeaderRow.addView(add);
    add.setOnClickListener(v -> edit(null));
    importProject.setOnClickListener(v -> MarketFilePicker.choose(
        owner, ".json,.lzp-project", this::importProject));

    LinearLayout list = new LinearLayout(owner);
    list.setOrientation(LinearLayout.VERTICAL);
    ScrollView scroll = new ScrollView(owner);
    scroll.addView(list);
    owner.root.addView(scroll, new LinearLayout.LayoutParams(-1, 0, 1));
    addGithubAccountEntry(list);

    int session = owner.navigationSession;
    owner.io.execute(() -> {
      List<MarketProject> projects = new ArrayList<>();
      try {
        File[] files = directory.listFiles((dir, name) -> name.endsWith(".json"));
        if (files != null) {
          for (File file : files) projects.add(MarketProject.load(file));
        }
        owner.runOnUiThread(() -> {
          if (session != owner.navigationSession) return;
          if (projects.isEmpty()) {
            list.addView(owner.text("暂无工程，点击加号选择路径并创建", 14, owner.MUTED));
          }
          for (MarketProject project : projects) {
            LinearLayout row = new LinearLayout(owner);
            row.setGravity(Gravity.CENTER_VERTICAL);
            row.setPadding(owner.dp(10), owner.dp(8), owner.dp(8), owner.dp(8));
            String titleText = project.metadata.optString(
                "title", project.metadata.optString("name"));
            TextView title = owner.text(
                titleText + "\n" + project.metadata.optString("version") + " · "
                    + project.metadata.optString("publisher"),
                14,
                owner.TEXT);
            title.setMaxLines(2);
            title.setEllipsize(android.text.TextUtils.TruncateAt.END);
            row.addView(title, new LinearLayout.LayoutParams(0, -2, 1));
            ImageButton more = owner.iconButton(R.drawable.ic_more, "工程操作");
            row.addView(more, new LinearLayout.LayoutParams(owner.dp(48), owner.dp(48)));
            row.setClickable(true);
            row.setFocusable(true);
            row.setContentDescription("打开仓库工程 " + titleText);
            row.setOnClickListener(v -> edit(project));
            more.setOnClickListener(v -> actions(project));
            list.addView(row, new LinearLayout.LayoutParams(-1, owner.dp(72)));
          }
        });
      } catch (Exception error) {
        owner.runOnUiThread(
            () -> owner.showNotice("工程读取失败：" + owner.friendlyError(error), true));
      }
    });
  }

  private void addGithubAccountEntry(LinearLayout list) {
    LinearLayout account = new LinearLayout(owner);
    account.setOrientation(LinearLayout.VERTICAL);
    account.setGravity(Gravity.CENTER_VERTICAL);
    account.setPadding(owner.dp(14), owner.dp(10), owner.dp(14), owner.dp(10));
    account.setBackground(owner.filterRipple(owner.solidShape(owner.SURFACE, 14)));
    account.setClickable(true);
    account.setFocusable(true);
    account.setMinimumHeight(owner.dp(64));
    account.setContentDescription(
        "GitHub 账号：每位用户登录自己的账户，由本人授权创建、上传或更新仓库");

    TextView title = owner.text("GitHub 账号", 15, owner.TEXT);
    title.setTypeface(android.graphics.Typeface.DEFAULT, android.graphics.Typeface.BOLD);
    TextView hint = owner.text(
        "每位用户登录自己的账户；发布时由本人授权创建、上传或更新仓库。",
        12,
        owner.MUTED);
    hint.setPadding(0, owner.dp(3), 0, 0);
    account.addView(title, new LinearLayout.LayoutParams(-1, -2));
    account.addView(hint, new LinearLayout.LayoutParams(-1, -2));
    account.setOnClickListener(v -> GithubAccountUi.accountAction(owner, () -> {
      if (owner.pageKind == 6 && !owner.isFinishing() && !owner.isDestroyed()) showList();
    }));

    LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(-1, -2);
    params.setMargins(owner.dp(2), 0, owner.dp(2), owner.dp(10));
    list.addView(account, params);
  }

  private void actions(MarketProject project) {
    AlertDialog prompt = new AlertDialog.Builder(owner)
        .setTitle(project.metadata.optString("title"))
        .setItems(
            new String[] {
              "编辑工程",
              "发布或更新GitHub仓库",
              "导出明文工程",
              "创建标准仓库压缩包",
              "删除工程"
            },
            (dialog, index) -> {
              if (index == 0) {
                edit(project);
              } else if (index == 1) {
                GithubProjectPublisher.publish(owner, project);
              } else if (index == 2) {
                export(project, false);
              } else if (index == 3) {
                export(project, true);
              } else {
                AlertDialog confirm = new AlertDialog.Builder(owner)
                    .setTitle("删除此工程？")
                    .setNegativeButton("取消", null)
                    .setPositiveButton("删除", (d, w) -> {
                      File file = new File(directory, project.id + ".json");
                      if (file.delete()) {
                        show();
                      } else {
                        owner.showNotice("工程删除失败", true);
                      }
                    })
                    .create();
                owner.showRounded(confirm);
              }
            })
        .create();
    owner.showRounded(prompt);
  }

  private void edit(MarketProject existing) {
    // The project editor uses the same independent page transition as the market.
    owner.pageDirection = 1;
    new MarketProjectEditor(owner, this, existing).show();
  }

  @Override
  public void saveProject(
      MarketProjectEditor.Draft draft, MarketProjectEditor.SaveListener listener) {
    final String draftName = draft.name.trim();
    final String draftTitle = draft.title.trim();
    final String draftVersion = draft.version.trim();
    final String draftPublisher = draft.publisher.trim();
    final String draftDescription = draft.description;
    final String draftIcon = draft.icon.trim();
    final String draftAvatar = draft.publisherAvatar.trim();
    final String draftReadme = draft.readme;
    final String draftMode = draft.versionMode;
    final MarketProject existing = draft.original;
    final List<SourceDestinationPopup.Destination> paths = new ArrayList<>(draft.paths);
    final String existingCategoriesJson =
        existing == null || existing.metadata.optJSONArray("categories") == null
            ? ""
            : existing.metadata.optJSONArray("categories").toString();

    owner.io.execute(() -> {
      try {
        JSONArray existingCategories = existingCategoriesJson.isEmpty()
            ? new JSONArray()
            : new JSONArray(existingCategoriesJson);
        if (existing == null && paths.isEmpty()) {
          throw new IOException("请先选择打包路径");
        }
        JSONObject metadata = new JSONObject()
            .put("schema", 1)
            .put("name", draftName)
            .put("title", draftTitle.isEmpty() ? draftName : draftTitle)
            .put("version", draftVersion)
            .put("versionMode", draftMode)
            .put("publisher", draftPublisher)
            .put("publisherAvatar", draftAvatar)
            .put("description", draftDescription)
            .put("icon", draftIcon)
            .put("readme", "README.md")
            .put("sources", "sources.lzp")
            .put("categories", existingCategories);

        String sources = paths.isEmpty()
            ? existing.sourceJson
            : LanzouCore.sourceDocument(
                SourcePathCatalog.selectedSources(owner.core, paths)).toString();
        MarketProject project;
        if (existing == null) {
          project = MarketProject.create(metadata, sources, draftReadme);
        } else {
          project = MarketProject.parse(existing.exportPlain());
          for (java.util.Iterator<String> keys = metadata.keys(); keys.hasNext(); ) {
            String key = keys.next();
            project.metadata.put(key, metadata.get(key));
          }
          project.sourceJson = sources;
          project.readme = draftReadme;
        }
        if (!paths.isEmpty()) {
          while (project.sourcePaths.length() > 0) project.sourcePaths.remove(0);
          for (SourceDestinationPopup.Destination path : paths) {
            project.addSourcePath(
                path.categoryOnly()
                    ? new JSONObject().put("category", path.category).toString()
                    : new JSONObject()
                        .put("sourceId", LanzouCore.sourceId(path.composite))
                        .put("nodeId", path.nodeId)
                        .toString());
          }
        }
        project.resolveAssets(this::assetBytes);
        if (!directory.isDirectory() && !directory.mkdirs()) {
          throw new IOException("无法创建工程目录");
        }
        project.save(new File(directory, project.id + ".json"));
        owner.runOnUiThread(() -> {
          if (owner.pageKind == 6 && MarketProjectEditor.isRetainedDraft(draft)) {
            MarketProjectEditor.clearRetainedDraft();
            owner.pageDirection = -1;
            owner.showNotice("工程已保存", false);
            show();
          } else {
            MarketProjectEditor.clearRetainedDraft();
            owner.showNotice("工程已保存", false);
          }
        });
      } catch (Exception error) {
        owner.runOnUiThread(() -> listener.failed(owner.friendlyError(error)));
      }
    });
  }

  @Override
  public void closeProjectEditor() {
    MarketProjectEditor.clearRetainedDraft();
    // Direction -1 keeps the editor exit on the same page-motion path as other independent pages.
    owner.pageDirection = -1;
    show();
  }

  private byte[] assetBytes(String reference) throws Exception {
    if (reference.startsWith("https://")) {
      return MarketRepositoryClient.httpGet(reference, 8 * 1024 * 1024);
    }
    try (InputStream input = reference.startsWith("content:")
        ? owner.getContentResolver().openInputStream(Uri.parse(reference))
        : new FileInputStream(reference)) {
      if (input == null) throw new IOException("无法读取所选图片");
      return MarketRepositoryClient.bytes(input, 8 * 1024 * 1024);
    }
  }

  private void importProject(File file) {
    owner.io.execute(() -> {
      try {
        MarketProject project = MarketProject.load(file);
        if (!directory.isDirectory() && !directory.mkdirs()) {
          throw new IOException("无法创建工程目录");
        }
        project.save(new File(directory, project.id + ".json"));
        owner.runOnUiThread(() -> {
          owner.showNotice("已导入明文工程", false);
          show();
        });
      } catch (Exception error) {
        owner.runOnUiThread(
            () -> owner.showNotice("导入失败：" + owner.friendlyError(error), true));
      }
    });
  }

  private void export(MarketProject project, boolean publish) {
    owner.showNotice(publish ? "正在创建标准仓库压缩包…" : "正在导出工程…", false);
    owner.io.execute(() -> {
      try {
        File folder = new File(owner.getFilesDir(), "project-exports");
        if (!folder.isDirectory() && !folder.mkdirs()) {
          throw new IOException("无法创建导出目录");
        }
        String name = project.metadata.getString("name")
            + (publish
                ? "-" + project.metadata.getString("version") + ".zip"
                : ".lzp-project.json");
        File file = new File(folder, name);
        byte[] bytes =
            publish ? project.buildRepositoryZip(this::assetBytes) : project.exportPlain();
        try (OutputStream output = new FileOutputStream(file)) {
          output.write(bytes);
        }
        owner.runOnUiThread(() -> {
          Intent intent = new Intent(Intent.ACTION_SEND);
          intent.setType(publish ? "application/zip" : "application/json");
          intent.putExtra(
              Intent.EXTRA_STREAM,
              new Uri.Builder()
                  .scheme("content")
                  .authority(owner.getPackageName() + ".downloads")
                  .appendPath("project")
                  .appendPath(file.getName())
                  .build());
          intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
          owner.startActivity(intent);
          owner.showNotice("导出完成 · " + owner.formatSize(bytes.length), false);
        });
      } catch (Exception error) {
        owner.runOnUiThread(
            () -> owner.showNotice("导出失败：" + owner.friendlyError(error), true));
      }
    });
  }
}
