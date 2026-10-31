package cc.nkbr.lanzouplus;

import android.app.AlertDialog;
import android.content.res.ColorStateList;
import android.graphics.Color;
import android.graphics.drawable.ColorDrawable;
import android.text.Editable;
import android.text.InputType;
import android.text.TextWatcher;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.BaseAdapter;
import android.widget.Button;
import android.widget.EditText;
import android.widget.ImageButton;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.RadioButton;
import android.widget.RadioGroup;
import android.widget.TextView;
import java.util.ArrayList;
import java.util.List;

/** Composer and public history popups for the static GitHub feedback repository. */
final class FeedbackUi {
  private final MainActivity owner;
  private final FeedbackClient client;
  private FeedbackContract.Identity identity;
  private AlertDialog composer;
  private AlertDialog identityChoice;
  private EditText input;
  private TextView counter;
  private TextView identityView;
  private TextView status;
  private Button send;
  private boolean identityReady;
  private boolean submitting;
  private boolean decidingIdentity;

  private FeedbackUi(MainActivity owner) {
    this.owner = owner;
    this.client = FeedbackClient.production(owner);
  }

  static View settingsRow(MainActivity owner) {
    return owner.settingsAction(R.drawable.ic_edit, owner.getString(R.string.feedback_entry),
        view -> show(owner));
  }

  static void show(MainActivity owner) {
    new FeedbackUi(owner).openComposer();
  }

  private void openComposer() {
    LinearLayout panel = new LinearLayout(owner);
    panel.setOrientation(LinearLayout.VERTICAL);
    panel.setPadding(owner.dp(20), owner.dp(6), owner.dp(20), 0);

    identityView = owner.text(owner.getString(R.string.feedback_identity_loading), 13, owner.PRIMARY);
    identityView.setTypeface(android.graphics.Typeface.create("sans-serif-medium",
        android.graphics.Typeface.NORMAL));
    identityView.setTextIsSelectable(true);
    panel.addView(identityView, new LinearLayout.LayoutParams(-1, owner.dp(38)));

    TextView privacy = owner.text(owner.getString(R.string.feedback_privacy), 12, owner.MUTED);
    privacy.setLineSpacing(owner.dp(2), 1f);
    privacy.setPadding(owner.dp(12), owner.dp(10), owner.dp(12), owner.dp(10));
    privacy.setBackground(SharedPopupSurface.solid(alpha(owner.PRIMARY, owner.darkMode ? .18f : .09f),
        owner.dp(14)));
    panel.addView(privacy, new LinearLayout.LayoutParams(-1, -2));

    input = owner.sourceInput(owner.getString(R.string.feedback_hint),
        InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_MULTI_LINE
            | InputType.TYPE_TEXT_FLAG_CAP_SENTENCES);
    input.setSingleLine(false);
    input.setGravity(Gravity.TOP | Gravity.START);
    input.setMinLines(5);
    input.setMaxLines(8);
    input.setPadding(owner.dp(12), owner.dp(10), owner.dp(12), owner.dp(10));
    input.setBackground(SharedPopupSurface.solid(owner.SURFACE, owner.dp(16)));
    LinearLayout.LayoutParams inputParams = new LinearLayout.LayoutParams(-1, owner.dp(154));
    inputParams.setMargins(0, owner.dp(12), 0, 0);
    panel.addView(input, inputParams);

    counter = owner.text(counterText(""), 12, owner.MUTED);
    counter.setGravity(Gravity.END | Gravity.CENTER_VERTICAL);
    panel.addView(counter, new LinearLayout.LayoutParams(-1, owner.dp(30)));

    status = owner.text("", 12, owner.MUTED);
    status.setLineSpacing(owner.dp(2), 1f);
    status.setMinHeight(owner.dp(38));
    panel.addView(status, new LinearLayout.LayoutParams(-1, -2));

    LinearLayout actions = new LinearLayout(owner);
    actions.setGravity(Gravity.CENTER_VERTICAL);
    Button history = AppButtons.text(owner, owner.getString(R.string.feedback_open_history));
    history.setOnClickListener(view -> openHistory());
    actions.addView(history, new LinearLayout.LayoutParams(0, owner.dp(48), 1));
    send = AppButtons.text(owner, owner.getString(R.string.feedback_send), 14);
    send.setOnClickListener(view -> submit());
    LinearLayout.LayoutParams sendParams = new LinearLayout.LayoutParams(0, owner.dp(48), 1);
    sendParams.setMarginStart(owner.dp(8));
    actions.addView(send, sendParams);
    panel.addView(actions, new LinearLayout.LayoutParams(-1, owner.dp(48)));

    input.addTextChangedListener(new TextWatcher() {
      @Override public void beforeTextChanged(CharSequence value, int start, int count, int after) {}
      @Override public void onTextChanged(CharSequence value, int start, int before, int count) {}
      @Override public void afterTextChanged(Editable value) {
        counter.setText(counterText(value == null ? "" : value.toString()));
      }
    });

    composer = new AlertDialog.Builder(owner)
        .setTitle(R.string.feedback_title)
        .setView(SharedPopupSurface.dialogScroll(owner, panel, 580))
        .setNegativeButton(R.string.feedback_close, null)
        .create();
    composer.setOnDismissListener(ignored -> {
      if (identityChoice != null && identityChoice.isShowing()) identityChoice.dismiss();
    });
    owner.showRounded(composer);
    updateSendState();
    loadIdentity();
  }

  private void loadIdentity() {
    if (composer != null && !composer.isShowing()) return;
    identityReady = false;
    updateSendState();
    owner.io.execute(() -> {
      try {
        FeedbackContract.Identity loaded = client.identity();
        owner.ui.post(() -> {
          if (composer == null || !composer.isShowing()) return;
          applyIdentity(loaded);
          identityReady = true;
          updateSendState();
        });
      } catch (FeedbackContract.Failure failure) {
        owner.ui.post(() -> {
          if (composer == null || !composer.isShowing()) return;
          identityReady = false;
          identityView.setText(R.string.feedback_identity_loading);
          showStatus(failure.getMessage(), true);
          updateSendState();
        });
      }
    });
  }

  private void applyIdentity(FeedbackContract.Identity value) {
    identity = value;
    identityView.setText(value.github()
        ? owner.getString(R.string.feedback_identity_github, value.githubLogin)
        : owner.getString(R.string.feedback_identity_device, value.deviceCode));
  }

  private void submit() {
    if (submitting || decidingIdentity || !identityReady || identity == null) return;
    String raw = input.getText().toString();
    try {
      FeedbackContract.validateContent(raw);
    } catch (FeedbackContract.Failure failure) {
      input.setError(failure.getMessage());
      showStatus(failure.getMessage(), true);
      return;
    }
    sendWith(identity, raw);
  }

  private void sendWith(FeedbackContract.Identity source, String raw) {
    input.setError(null);
    submitting = true;
    decidingIdentity = false;
    showStatus("", false);
    updateSendState();
    long now = System.currentTimeMillis();
    owner.io.execute(() -> {
      try {
        FeedbackClient.SubmissionResult result = client.send(raw, source, now);
        owner.ui.post(() -> finishSubmission(result));
      } catch (FeedbackContract.Failure failure) {
        owner.ui.post(() -> failSubmission(failure, raw));
      }
    });
  }

  private void finishSubmission(FeedbackClient.SubmissionResult result) {
    submitting = false;
    if (composer == null || !composer.isShowing()) {
      owner.showNotice(owner.getString(R.string.feedback_submitted), false);
      return;
    }
    input.setText("");
    applyIdentity(result.identity);
    updateSendState();
    showStatus(owner.getString(R.string.feedback_submitted), false);
    owner.showNotice(owner.getString(R.string.feedback_submitted), false);
  }

  private void failSubmission(FeedbackContract.Failure failure, String raw) {
    submitting = false;
    if (composer == null || !composer.isShowing()) return;
    updateSendState();
    showStatus(failure.getMessage(), true);
    if ("feedback_daily_limit".equals(failure.code)) client.markSubmitted(System.currentTimeMillis());
    if ("github_login_required".equals(failure.code)
        || "github_identity_invalid".equals(failure.code)
        || "feedback_identity_unavailable".equals(failure.code)) {
      offerDeviceFallback(raw, failure);
    }
  }

  private void offerDeviceFallback(String raw, FeedbackContract.Failure failure) {
    decidingIdentity = true;
    updateSendState();
    AlertDialog choice = new AlertDialog.Builder(owner)
        .setTitle(R.string.feedback_identity_choice_title)
        .setMessage(owner.getString(R.string.feedback_identity_choice_message,
            failure.getMessage()))
        .setNegativeButton(R.string.feedback_identity_choice_cancel, null)
        .setNeutralButton(R.string.feedback_identity_choice_login, null)
        .setPositiveButton(R.string.feedback_identity_choice_device, null)
        .create();
    identityChoice = choice;
    choice.setOnShowListener(ignored -> {
      choice.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(view -> {
        choice.dismiss();
        submitAsDevice(raw);
      });
      choice.getButton(AlertDialog.BUTTON_NEUTRAL).setOnClickListener(view -> {
        choice.dismiss();
        decidingIdentity = false;
        updateSendState();
        GithubAccountUi.signIn(owner, this::loadIdentity);
      });
    });
    choice.setOnDismissListener(ignored -> {
      if (identityChoice == choice) identityChoice = null;
      if (decidingIdentity) {
        decidingIdentity = false;
        updateSendState();
      }
    });
    owner.showRounded(choice);
  }

  private void submitAsDevice(String raw) {
    decidingIdentity = true;
    updateSendState();
    owner.io.execute(() -> {
      try {
        FeedbackContract.Identity device = FeedbackContract.Identity.deviceOnly(client.deviceCode());
        owner.ui.post(() -> {
          if (composer == null || !composer.isShowing()) return;
          applyIdentity(device);
          identityReady = true;
          sendWith(device, raw);
        });
      } catch (FeedbackContract.Failure failure) {
        owner.ui.post(() -> {
          decidingIdentity = false;
          updateSendState();
          showStatus(failure.getMessage(), true);
        });
      }
    });
  }

  private void updateSendState() {
    if (send == null) return;
    boolean doneToday = client.submittedToday(System.currentTimeMillis());
    boolean enabled = identityReady && !submitting && !decidingIdentity && !doneToday;
    send.setEnabled(enabled);
    send.setText(submitting ? R.string.feedback_sending : R.string.feedback_send);
    if (doneToday && !submitting) status.setText(R.string.feedback_today_done);
  }

  private void showStatus(String message, boolean error) {
    if (status == null) return;
    status.setText(message == null ? "" : message);
    status.setTextColor(error ? errorColor() : owner.MUTED);
  }

  private int errorColor() {
    return owner.darkMode ? Color.rgb(242, 184, 181) : Color.rgb(179, 38, 30);
  }

  private String counterText(String value) {
    return owner.getString(R.string.feedback_counter, value.codePointCount(0, value.length()));
  }

  private static int alpha(int color, float amount) {
    return Color.argb(Math.round(255 * amount), Color.red(color), Color.green(color), Color.blue(color));
  }

  private void openHistory() {
    new HistoryPopup(identity).show();
  }

  private final class HistoryPopup {
    private final AlertDialog dialog;
    private final ListView list;
    private final TextView summary;
    private final RadioGroup modes;
    private final RadioButton mine;
    private final ImageButton refresh;
    private final EntryAdapter adapter = new EntryAdapter();
    private FeedbackClient.HistoryResult result;
    private boolean loading;
    private String refreshProblem = "";

    HistoryPopup(FeedbackContract.Identity current) {
      if (current != null) identity = current;
      LinearLayout panel = new LinearLayout(owner);
      panel.setOrientation(LinearLayout.VERTICAL);
      panel.setPadding(owner.dp(16), owner.dp(8), owner.dp(16), 0);

      LinearLayout controls = new LinearLayout(owner);
      controls.setGravity(Gravity.CENTER_VERTICAL);
      modes = new RadioGroup(owner);
      modes.setOrientation(LinearLayout.HORIZONTAL);
      mine = new RadioButton(owner);
      mine.setId(View.generateViewId());
      mine.setText(R.string.feedback_mine);
      mine.setTextColor(owner.TEXT);
      mine.setButtonTintList(ColorStateList.valueOf(owner.PRIMARY));
      RadioButton all = new RadioButton(owner);
      all.setId(View.generateViewId());
      all.setText(R.string.feedback_all);
      all.setTextColor(owner.TEXT);
      all.setButtonTintList(ColorStateList.valueOf(owner.PRIMARY));
      modes.addView(mine, new LinearLayout.LayoutParams(0, owner.dp(48), 1));
      modes.addView(all, new LinearLayout.LayoutParams(0, owner.dp(48), 1));
      modes.check(mine.getId());
      modes.setOnCheckedChangeListener((group, checked) -> render());
      controls.addView(modes, new LinearLayout.LayoutParams(0, owner.dp(48), 1));
      refresh = AppButtons.icon(owner, R.drawable.ic_refresh,
          owner.getString(R.string.feedback_refresh));
      refresh.setOnClickListener(view -> load());
      controls.addView(refresh, new LinearLayout.LayoutParams(owner.dp(48), owner.dp(48)));
      panel.addView(controls, new LinearLayout.LayoutParams(-1, owner.dp(48)));

      summary = owner.text(owner.getString(R.string.feedback_loading), 12, owner.MUTED);
      summary.setLineSpacing(owner.dp(2), 1f);
      summary.setPadding(owner.dp(4), owner.dp(4), owner.dp(4), owner.dp(8));
      panel.addView(summary, new LinearLayout.LayoutParams(-1, -2));

      list = new ListView(owner);
      list.setAdapter(adapter);
      list.setDivider(new ColorDrawable(owner.DIV));
      list.setDividerHeight(owner.dp(1));
      list.setVerticalScrollBarEnabled(true);
      list.setContentDescription(owner.getString(R.string.feedback_history_title));
      int available = owner.getResources().getDisplayMetrics().heightPixels - owner.dp(270);
      panel.addView(list, new LinearLayout.LayoutParams(-1,
          Math.max(1, Math.min(owner.dp(380), available))));

      dialog = new AlertDialog.Builder(owner)
          .setTitle(R.string.feedback_history_title)
          .setView(SharedPopupSurface.dialogScroll(owner, panel, 580))
          .setNegativeButton(R.string.feedback_close, null)
          .create();
    }

    void show() {
      owner.showRounded(dialog);
      if (identity == null) {
        owner.io.execute(() -> {
          try {
            FeedbackContract.Identity loaded = client.identity();
            owner.ui.post(() -> {
              if (!dialog.isShowing()) return;
              identity = loaded;
              render();
            });
          } catch (FeedbackContract.Failure failure) {
            owner.ui.post(() -> {
              if (dialog.isShowing()) summary.setText(
                  owner.getString(R.string.feedback_load_failed, failure.getMessage()));
            });
          }
        });
      }
      load();
    }

    private void load() {
      if (loading) return;
      loading = true;
      refresh.setEnabled(false);
      if (result == null) summary.setText(R.string.feedback_loading);
      owner.io.execute(() -> {
        FeedbackClient.HistoryResult loaded = null;
        FeedbackContract.Failure failure = null;
        try {
          loaded = client.history();
        } catch (FeedbackContract.Failure error) {
          failure = error;
        }
        FeedbackClient.HistoryResult value = loaded;
        FeedbackContract.Failure problem = failure;
        owner.ui.post(() -> {
          loading = false;
          refresh.setEnabled(true);
          if (!dialog.isShowing()) return;
          if (value != null) {
            result = value;
            refreshProblem = value.warning;
          }
          if (problem != null) {
            refreshProblem = owner.getString(R.string.feedback_load_failed, problem.getMessage());
          }
          render();
        });
      });
    }

    private void render() {
      if (result == null) {
        adapter.entries = new ArrayList<>();
        adapter.notifyDataSetChanged();
        list.setVisibility(View.GONE);
        summary.setText(emptyHistoryStatus(refreshProblem,
            owner.getString(R.string.feedback_loading)));
        return;
      }
      String head = result.warning.isEmpty()
          ? owner.getString(R.string.feedback_updated, result.history.version,
              FeedbackContract.formatTime(result.history.updatedAt))
          : result.warning;
      if (!refreshProblem.isEmpty()) head = refreshProblem + "\n" + head;
      boolean mineOnly = modes.getCheckedRadioButtonId() == mine.getId();
      List<FeedbackContract.Entry> shown = new ArrayList<>();
      for (FeedbackContract.Entry entry : result.history.entries) {
        if (!mineOnly || (identity != null && identity.owns(entry))) shown.add(entry);
      }
      adapter.entries = shown;
      adapter.notifyDataSetChanged();
      list.setVisibility(shown.isEmpty() ? View.GONE : View.VISIBLE);
      if (shown.isEmpty()) {
        head += "\n" + owner.getString(mineOnly ? R.string.feedback_empty_mine
            : R.string.feedback_empty_all);
      }
      summary.setText(head);
    }

    private final class EntryAdapter extends BaseAdapter {
      List<FeedbackContract.Entry> entries = new ArrayList<>();

      @Override public int getCount() { return entries.size(); }
      @Override public FeedbackContract.Entry getItem(int position) { return entries.get(position); }
      @Override public long getItemId(int position) { return position; }
      @Override public boolean hasStableIds() { return false; }

      @Override public View getView(int position, View recycled, ViewGroup parent) {
        Row row = recycled != null && recycled.getTag() instanceof Row
            ? (Row) recycled.getTag() : new Row();
        row.root.setTag(row);
        row.bind(entries.get(position));
        return row.root;
      }
    }

    private final class Row {
      final LinearLayout root;
      final TextView state;
      final TextView content;
      final TextView meta;
      final TextView progress;

      Row() {
        root = new LinearLayout(owner);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(owner.dp(4), owner.dp(12), owner.dp(4), owner.dp(12));
        LinearLayout heading = new LinearLayout(owner);
        heading.setGravity(Gravity.CENTER_VERTICAL);
        state = SharedPopupSurface.label(owner, "", 11, owner.PRIMARY);
        state.setPadding(owner.dp(8), owner.dp(3), owner.dp(8), owner.dp(3));
        heading.addView(state, new LinearLayout.LayoutParams(-2, owner.dp(28)));
        meta = SharedPopupSurface.label(owner, "", 11, owner.MUTED);
        meta.setGravity(Gravity.END | Gravity.CENTER_VERTICAL);
        heading.addView(meta, new LinearLayout.LayoutParams(0, owner.dp(28), 1));
        root.addView(heading, new LinearLayout.LayoutParams(-1, owner.dp(28)));
        content = SharedPopupSurface.label(owner, "", 14, owner.TEXT);
        content.setTextIsSelectable(true);
        content.setLineSpacing(owner.dp(2), 1f);
        content.setPadding(0, owner.dp(6), 0, owner.dp(2));
        root.addView(content, new LinearLayout.LayoutParams(-1, -2));
        progress = SharedPopupSurface.label(owner, "", 11, owner.MUTED);
        progress.setPadding(0, owner.dp(4), 0, 0);
        root.addView(progress, new LinearLayout.LayoutParams(-1, -2));
      }

      void bind(FeedbackContract.Entry entry) {
        state.setText(statusLabel(entry.status));
        state.setTextColor(entry.status == FeedbackContract.Status.READ ? owner.MUTED : owner.PRIMARY);
        state.setBackground(SharedPopupSurface.solid(alpha(entry.status == FeedbackContract.Status.READ
            ? owner.MUTED : owner.PRIMARY, owner.darkMode ? .18f : .10f), owner.dp(12)));
        meta.setText(entry.identity.label() + " · " + FeedbackContract.formatTime(entry.createdAt));
        content.setText(entry.content);
        StringBuilder timeline = new StringBuilder();
        if (entry.readAt > 0 && entry.status != FeedbackContract.Status.NEW) {
          timeline.append(owner.getString(R.string.feedback_read_at,
              FeedbackContract.formatTime(entry.readAt)));
        }
        if (entry.doneAt > 0 || entry.status == FeedbackContract.Status.DONE) {
          if (timeline.length() > 0) timeline.append(" · ");
          timeline.append(owner.getString(R.string.feedback_done_at,
              FeedbackContract.formatTime(entry.doneAt)));
        }
        progress.setText(timeline);
        progress.setVisibility(timeline.length() == 0 ? View.GONE : View.VISIBLE);
      }
    }
  }

  private String statusLabel(FeedbackContract.Status value) {
    if (value == FeedbackContract.Status.READ) return owner.getString(R.string.feedback_status_read);
    if (value == FeedbackContract.Status.DONE) return owner.getString(R.string.feedback_status_done);
    return owner.getString(R.string.feedback_status_new);
  }

  static String emptyHistoryStatus(String problem, String loading) {
    return problem == null || problem.trim().isEmpty() ? loading : problem;
  }
}
