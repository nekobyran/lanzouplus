package cc.nkbr.lanzouplus;

import android.app.AlertDialog;
import android.text.Editable;
import android.text.InputType;
import android.text.TextWatcher;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;
import org.json.JSONArray;
import org.json.JSONObject;

/** Comment section embedded in a repository detail page. */
final class RepositoryCommentsView {
  private final MainActivity owner;
  private final String githubUrl;
  private final RepositoryCommentsClient client;
  private final LinearLayout root;
  private final LinearLayout list;
  private final TextView status;
  private final Button previous;
  private final Button next;
  private int offset;
  private int total;
  private boolean loading;
  private boolean submitting;
  private String draft = "";

  static View create(MainActivity owner, String githubUrl) {
    return new RepositoryCommentsView(owner, githubUrl).root;
  }

  private RepositoryCommentsView(MainActivity owner, String githubUrl) {
    this.owner = owner;
    this.githubUrl = githubUrl;
    client = new RepositoryCommentsClient(owner);
    root = new LinearLayout(owner);
    root.setOrientation(LinearLayout.VERTICAL);
    TextView title = owner.text("评论", 18, owner.PRIMARY);
    root.addView(title);
    Button compose = AppButtons.text(owner, "发表评论");
    compose.setOnClickListener(view -> compose());
    root.addView(compose);
    status = owner.text("正在加载评论…", 13, owner.MUTED);
    root.addView(status);
    list = new LinearLayout(owner);
    list.setOrientation(LinearLayout.VERTICAL);
    root.addView(list);
    LinearLayout controls = new LinearLayout(owner);
    previous = AppButtons.text(owner, "上一页");
    next = AppButtons.text(owner, "下一页");
    previous.setOnClickListener(view -> load(Math.max(0, offset - 20)));
    next.setOnClickListener(view -> load(offset + 20));
    controls.addView(previous, new LinearLayout.LayoutParams(0, owner.dp(48), 1));
    controls.addView(next, new LinearLayout.LayoutParams(0, owner.dp(48), 1));
    root.addView(controls);
    load(0);
  }

  private void load(int target) {
    if (loading) return;
    loading = true;
    previous.setEnabled(false);
    next.setEnabled(false);
    status.setText("正在加载评论…");
    owner.io.execute(() -> {
      try {
        JSONObject page = client.page(githubUrl, target);
        JSONArray entries = page.getJSONArray("entries");
        int count = page.getInt("total");
        owner.ui.post(() -> {
          loading = false;
          offset = target;
          total = count;
          list.removeAllViews();
          for (int i = 0; i < entries.length(); i++) {
            JSONObject entry = entries.optJSONObject(i);
            if (entry == null) continue;
            String author = entry.optString("author", "");
            TextView meta = owner.text((author.isEmpty() ? "匿名" : author) + " · "
                + entry.optString("createdAt", ""), 12, owner.MUTED);
            meta.setPadding(0, owner.dp(12), 0, owner.dp(4));
            list.addView(meta);
            TextView content = owner.text(entry.optString("content", ""), 14, owner.PRIMARY);
            content.setTextIsSelectable(true);
            list.addView(content);
          }
          status.setText(count == 0 ? "暂无评论" : "共 " + count + " 条 · 第 " + (offset / 20 + 1) + " 页");
          previous.setEnabled(offset > 0);
          next.setEnabled(offset + 20 < total);
        });
      } catch (Exception error) {
        owner.ui.post(() -> {
          loading = false;
          status.setText("评论加载失败，请重试");
          previous.setEnabled(offset > 0);
          next.setEnabled(offset + 20 < total);
          list.removeAllViews();
          Button retry = AppButtons.text(owner, "重试");
          retry.setOnClickListener(view -> load(target));
          list.addView(retry);
        });
      }
    });
  }

  private void compose() {
    EditText input = owner.sourceInput("写下你的评论（最多 2000 字）",
        InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_MULTI_LINE | InputType.TYPE_TEXT_FLAG_CAP_SENTENCES);
    input.setSingleLine(false);
    input.setGravity(Gravity.TOP | Gravity.START);
    input.setMinLines(4);
    input.setText(draft);
    input.setSelection(input.length());
    input.addTextChangedListener(new TextWatcher() {
      @Override public void beforeTextChanged(CharSequence text, int start, int count, int after) {}
      @Override public void onTextChanged(CharSequence text, int start, int before, int count) { draft = text.toString(); }
      @Override public void afterTextChanged(Editable text) {}
    });
    LinearLayout panel = new LinearLayout(owner);
    panel.setPadding(owner.dp(20), owner.dp(8), owner.dp(20), 0);
    panel.addView(input, new LinearLayout.LayoutParams(-1, -2));
    AlertDialog dialog = new AlertDialog.Builder(owner).setTitle("发表评论")
        .setView(panel).setNegativeButton("取消", null).setPositiveButton("提交", null).create();
    owner.showRounded(dialog);
    dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(view -> {
      if (submitting) return;
      String content = input.getText().toString().trim();
      int length = content.codePointCount(0, content.length());
      if (length < 1 || length > 2000) {
        input.setError("请输入 1 至 2000 字评论");
        return;
      }
      submitting = true;
      dialog.setCancelable(false);
      dialog.getButton(AlertDialog.BUTTON_POSITIVE).setEnabled(false);
      dialog.getButton(AlertDialog.BUTTON_NEGATIVE).setEnabled(false);
      owner.io.execute(() -> {
        try {
          client.submit(githubUrl, content);
          owner.ui.post(() -> {
            submitting = false;
            draft = "";
            dialog.dismiss();
            Toast.makeText(owner, "评论已提交，等待合并", Toast.LENGTH_SHORT).show();
          });
        } catch (Exception error) {
          owner.ui.post(() -> {
            submitting = false;
            dialog.setCancelable(true);
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setEnabled(true);
            dialog.getButton(AlertDialog.BUTTON_NEGATIVE).setEnabled(true);
            Toast.makeText(owner, error.getMessage() == null ? "提交失败，请重试" : error.getMessage(), Toast.LENGTH_SHORT).show();
          });
        }
      });
    });
  }
}
