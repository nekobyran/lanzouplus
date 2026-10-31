package cc.nkbr.lanzouplus;

import android.app.AlertDialog;
import android.view.View;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;
import java.io.IOException;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import org.json.JSONObject;

/** One project action: refresh its bound GitHub repository, then submit it to the market. */
final class MarketPublishFlow {
    private static final Set<String> RUNNING = new HashSet<>();

    private final MainActivity owner;
    private final MarketProject project;
    private final Runnable onChanged;

    private MarketPublishFlow(MainActivity owner, MarketProject project, Runnable onChanged) {
        this.owner = owner;
        this.project = project;
        this.onChanged = onChanged;
    }

    static void start(MainActivity owner, MarketProject project, Runnable onChanged) {
        new MarketPublishFlow(owner, project, onChanged).begin();
    }

    private void begin() {
        int navigation = owner.navigationSession;
        if (!GithubAccountUi.isOwnerCurrent(owner, navigation)) return;
        String problem = localProblem();
        if (problem != null) {
            showBlocked("未写入 GitHub", problem, null);
            return;
        }
        if (!enter()) {
            owner.showNotice("该工程正在更新，请等待当前操作完成", false);
            return;
        }
        GithubAccountClient client = GithubAccountClient.production(owner);
        owner.io.execute(() -> {
            try {
                GithubAccountClient.Profile profile = client.cachedProfile();
                if (profile == null || !client.hasUsableSession()) {
                    leave();
                    owner.runOnUiThread(() -> {
                        if (GithubAccountUi.isOwnerCurrent(owner, navigation)) {
                            GithubAccountUi.signIn(owner, this::begin);
                        }
                    });
                    return;
                }
                GithubProjectPublisher.Association linked =
                        GithubProjectPublisher.loadAssociation(owner, profile.login, project.id);
                if (linked == null) {
                    leave();
                    owner.runOnUiThread(() -> {
                        if (GithubAccountUi.isOwnerCurrent(owner, navigation)) showUnbound();
                    });
                    return;
                }
                MarketRepositoryClient.Submission current = owner.core.marketRepositories()
                        .precheck(linked.repositoryUrl, project.metadata);
                if (current != null) {
                    leave();
                    owner.runOnUiThread(() -> {
                        if (GithubAccountUi.isOwnerCurrent(owner, navigation)) {
                            showVersionNotHigher(current, linked);
                        }
                    });
                    return;
                }
                owner.runOnUiThread(() -> {
                    if (!GithubAccountUi.isOwnerCurrent(owner, navigation)) {
                        leave();
                        return;
                    }
                    run(linked, client, profile.login, navigation);
                });
            } catch (Throwable failure) {
                leave();
                owner.runOnUiThread(() -> {
                    if (GithubAccountUi.isOwnerCurrent(owner, navigation)) {
                        showBlocked("未写入 GitHub", actionableDetail(failure), null);
                    }
                });
            }
        });
    }

    private String localProblem() {
        String name = project.metadata.optString("name").trim();
        if (!name.matches("lzpmarket-[A-Za-z0-9_.-]+")) {
            return "仓库名必须为 lzpmarket-名称，当前为「" + name
                    + "」。请先编辑工程名称后再执行；本次不会写入 GitHub。";
        }
        try {
            new MarketRepository(new JSONObject(project.metadata.toString()));
            MarketRepository.resolveVersionMode(project.metadata.optString("versionMode"),
                    project.metadata.optString("version"));
        } catch (Exception invalid) {
            return actionableDetail(invalid) + "\n请先修正工程元数据后再执行。";
        }
        if (project.sourceJson.trim().isEmpty()) {
            return "工程还没有选择要发布的源，请先编辑工程并选择源；本次不会写入 GitHub。";
        }
        try {
            LanzouCore.parseSourceDocument(project.sourceJson);
        } catch (Exception invalid) {
            return "工程源文件无效：" + actionableDetail(invalid)
                    + "\n请先修正工程源后再执行；本次不会写入 GitHub。";
        }
        return null;
    }

    private void run(GithubProjectPublisher.Association linked, GithubAccountClient client,
            String login, int navigation) {
        AtomicBoolean cancelled = new AtomicBoolean();
        TextView status = owner.text("正在校验工程并准备 GitHub 提交…", 14, owner.TEXT);
        status.setAccessibilityLiveRegion(View.ACCESSIBILITY_LIVE_REGION_POLITE);
        status.setPadding(owner.dp(20), owner.dp(12), owner.dp(20), owner.dp(12));
        AlertDialog dialog = new AlertDialog.Builder(owner)
                .setTitle("更新并提交市场")
                .setView(status)
                .setNegativeButton("取消后续操作", null)
                .create();
        dialog.setCancelable(false);
        dialog.setOnShowListener(ignored -> dialog.getButton(AlertDialog.BUTTON_NEGATIVE)
                .setOnClickListener(v -> {
                    cancelled.set(true);
                    status.setText("正在停止：当前请求结束后不会继续推进 GitHub 分支或提交市场…");
                    dialog.getButton(AlertDialog.BUTTON_NEGATIVE).setEnabled(false);
                }));
        owner.showRounded(dialog);
        owner.io.execute(() -> {
            GithubProjectPublisher.Outcome published;
            try {
                published = GithubProjectPublisher.uploadForAccount(
                        owner, client, login, project, GithubProjectPublisher::assetBytes,
                        cancelled, message -> runOnUiThread(status, message));
            } catch (Throwable failure) {
                owner.runOnUiThread(() -> {
                    dialog.dismiss();
                    leave();
                    if (!GithubAccountUi.isOwnerCurrent(owner, navigation)) return;
                    changed();
                    showBlocked("GitHub 未更新", actionableDetail(failure) + "\n\n市场未提交。", linked);
                });
                return;
            }
            if (!published.complete()) {
                owner.runOnUiThread(() -> {
                    dialog.dismiss();
                    leave();
                    if (!GithubAccountUi.isOwnerCurrent(owner, navigation)) return;
                    changed();
                    showPublishFailure(published, linked);
                });
                return;
            }
            String bindingWarning = "";
            try {
                GithubProjectPublisher.persistProjectRepository(owner, project, published);
            } catch (Exception storage) {
                bindingWarning = "本机工程文件未记录仓库地址：" + actionableDetail(storage)
                        + "；GitHub 关联仍保留。";
            }
            String warning = bindingWarning;
            if (cancelled.get()) {
                owner.runOnUiThread(() -> {
                    dialog.dismiss();
                    leave();
                    if (!GithubAccountUi.isOwnerCurrent(owner, navigation)) return;
                    changed();
                    showCanceledAfterPublish(published, linked, warning);
                });
                return;
            }
            if (!GithubAccountUi.isOwnerCurrent(owner, navigation)) {
                leave();
                return;
            }
            owner.runOnUiThread(() -> {
                if (!GithubAccountUi.isOwnerCurrent(owner, navigation)) {
                    dialog.dismiss();
                    leave();
                    return;
                }
                status.setText("GitHub 已更新，正在检查市场版本并提交…");
                dialog.getButton(AlertDialog.BUTTON_NEGATIVE).setEnabled(false);
            });
            try {
                MarketRepositoryClient.Submission submitted = owner.core.marketRepositories()
                        .upload(linked.repositoryUrl, message -> runOnUiThread(status, message));
                owner.runOnUiThread(() -> {
                    dialog.dismiss();
                    leave();
                    if (!GithubAccountUi.isOwnerCurrent(owner, navigation)) return;
                    changed();
                    showComplete(published, submitted, linked, warning);
                });
            } catch (Throwable failure) {
                owner.runOnUiThread(() -> {
                    dialog.dismiss();
                    leave();
                    if (!GithubAccountUi.isOwnerCurrent(owner, navigation)) return;
                    changed();
                    showMarketFailure(linked, failure, warning);
                });
            }
        });
    }

    private void submitMarketOnly(GithubProjectPublisher.Association linked, int navigation) {
        if (!enter()) {
            owner.showNotice("该工程正在更新，请等待当前操作完成", false);
            return;
        }
        TextView status = owner.text("正在重新检查市场版本并提交…", 14, owner.TEXT);
        status.setAccessibilityLiveRegion(View.ACCESSIBILITY_LIVE_REGION_POLITE);
        status.setPadding(owner.dp(20), owner.dp(12), owner.dp(20), owner.dp(12));
        AlertDialog dialog = new AlertDialog.Builder(owner)
                .setTitle("仅重试市场提交")
                .setView(status)
                .create();
        dialog.setCancelable(false);
        owner.showRounded(dialog);
        owner.io.execute(() -> {
            try {
                MarketRepositoryClient.Submission submitted = owner.core.marketRepositories()
                        .upload(linked.repositoryUrl, message -> runOnUiThread(status, message));
                owner.runOnUiThread(() -> {
                    dialog.dismiss();
                    leave();
                    if (!GithubAccountUi.isOwnerCurrent(owner, navigation)) return;
                    changed();
                    showComplete(null, submitted, linked, "");
                });
            } catch (Throwable failure) {
                owner.runOnUiThread(() -> {
                    dialog.dismiss();
                    leave();
                    if (!GithubAccountUi.isOwnerCurrent(owner, navigation)) return;
                    changed();
                    showMarketFailure(linked, failure, "");
                });
            }
        });
    }

    private void showVersionNotHigher(MarketRepositoryClient.Submission current,
            GithubProjectPublisher.Association linked) {
        String published = current.publishedVersion.isEmpty()
                ? "市场已发布版本" : "市场已发布版本 " + current.publishedVersion;
        String body = "工程版本 " + current.version + " 未高于" + published
                + "。\n\n为避免无意义写入，本次没有更新 GitHub，也没有重复提交市场。"
                + "\n\n请先编辑工程并递增版本，然后再执行一键更新。";
        showBlocked("版本未递增", body, linked);
    }

    private void showUnbound() {
        LinearLayout panel = panel();
        TextView message = owner.text(
                "此工程在本机没有 GitHub 仓库绑定。一键更新不会猜测同名仓库，也不会覆盖它。"
                        + "\n\n请先使用「发布或更新GitHub仓库」创建并绑定仓库，完成后再次点击一键更新。",
                13, owner.TEXT);
        message.setTextIsSelectable(true);
        panel.addView(message, new LinearLayout.LayoutParams(-1, -2));
        AlertDialog dialog = new AlertDialog.Builder(owner)
                .setTitle("尚未绑定 GitHub 仓库")
                .setView(panel)
                .setNegativeButton("关闭", null)
                .setPositiveButton("去创建/绑定", (ignored, which) -> {
                    leave();
                    GithubProjectPublisher.publish(owner, project);
                })
                .create();
        owner.showRounded(dialog);
    }

    private void showPublishFailure(GithubProjectPublisher.Outcome result,
            GithubProjectPublisher.Association linked) {
        StringBuilder body = new StringBuilder();
        if (result.hasRepository()) {
            body.append("仓库：").append(result.repositoryUrl).append("\n\n");
        }
        body.append(result.error.isEmpty() ? "GitHub 未确认提交结果。" : result.error);
        if (result.hasRepository() && !result.refUpdated) {
            body.append("\n\n已准备 Git 对象：").append(result.preparedFiles).append("/")
                    .append(result.expectedFiles).append("；这些对象不代表默认分支已发布。");
        }
        body.append("\n\n市场未提交。");
        showBlocked("GitHub 未更新", body.toString(), linked);
    }

    private void showMarketFailure(GithubProjectPublisher.Association linked, Throwable failure,
            String warning) {
        StringBuilder body = new StringBuilder("GitHub 仓库已写入，本机绑定已保留。\n\n市场提交未完成：\n")
                .append(actionableDetail(failure))
                .append("\n\n点击「仅重试市场提交」只会重新执行市场预检与提交，不会再次更新 GitHub，也不会新建仓库。");
        if (!warning.isEmpty()) body.append("\n\n").append(warning);
        LinearLayout panel = panel();
        TextView message = owner.text(body.toString(), 13, owner.MUTED);
        message.setTextIsSelectable(true);
        panel.addView(message, new LinearLayout.LayoutParams(-1, -2));
        AlertDialog dialog = new AlertDialog.Builder(owner)
                .setTitle("GitHub 已更新 · 市场提交未完成")
                .setView(panel)
                .setNegativeButton("关闭", null)
                .setNeutralButton("打开仓库", (ignored, which) ->
                        GithubAccountUi.openBrowser(owner, linked.repositoryUrl))
                .setPositiveButton("仅重试市场提交", (ignored, which) ->
                        submitMarketOnly(linked, owner.navigationSession))
                .create();
        owner.showRounded(dialog);
    }

    private void showCanceledAfterPublish(GithubProjectPublisher.Outcome published,
            GithubProjectPublisher.Association linked, String warning) {
        StringBuilder body = new StringBuilder("GitHub 仓库已更新，本机绑定已保留。\n\n后续市场投稿已取消。")
                .append("\n\n仓库：").append(linked.repositoryUrl)
                .append("\n\n点击「仅提交市场」会从市场预检开始重新提交，不会再次更新 GitHub。");
        if (published.alreadyLatest) body.append("\nGitHub 内容原本已是最新，未创建新提交。");
        if (!warning.isEmpty()) body.append("\n\n").append(warning);
        LinearLayout panel = panel();
        TextView message = owner.text(body.toString(), 13, owner.MUTED);
        message.setTextIsSelectable(true);
        panel.addView(message, new LinearLayout.LayoutParams(-1, -2));
        AlertDialog dialog = new AlertDialog.Builder(owner)
                .setTitle("GitHub 已更新 · 投稿已取消")
                .setView(panel)
                .setNegativeButton("关闭", null)
                .setNeutralButton("打开仓库", (ignored, which) ->
                        GithubAccountUi.openBrowser(owner, linked.repositoryUrl))
                .setPositiveButton("仅提交市场", (ignored, which) ->
                        submitMarketOnly(linked, owner.navigationSession))
                .create();
        owner.showRounded(dialog);
    }

    private void showComplete(GithubProjectPublisher.Outcome published,
            MarketRepositoryClient.Submission submitted,
            GithubProjectPublisher.Association linked, String warning) {
        StringBuilder body = new StringBuilder();
        if (published == null) body.append("GitHub 仓库已更新，绑定已保留。\n");
        else if (published.alreadyLatest) body.append("GitHub 仓库内容已是最新，无需新提交。\n");
        else body.append("GitHub 已提交 ").append(published.expectedFiles).append(" 个文件。\n");
        body.append("仓库：").append(linked.repositoryUrl).append("\n\n");
        if (submitted.created()) {
            body.append("市场资料已创建，等待市场合并。\n目标版本：").append(submitted.version);
        } else if (submitted.updated()) {
            body.append("市场更新已提交，等待市场合并。\n目标版本：").append(submitted.version);
        } else {
            body.append("市场未重复提交。\n当前市场已发布版本：")
                    .append(submitted.publishedVersion.isEmpty()
                            ? submitted.version : submitted.publishedVersion);
            if (submitted.serverCurrent) {
                body.append("\n服务端已有同版或更高版本的待合并资料，本次未入队。请递增工程版本后再试。");
            } else {
                body.append("\n本次工程版本未高于该版本。请递增工程版本后再试。");
            }
        }
        if (!warning.isEmpty()) body.append("\n\n").append(warning);
        LinearLayout panel = panel();
        TextView message = owner.text(body.toString(), 13, owner.MUTED);
        message.setTextIsSelectable(true);
        panel.addView(message, new LinearLayout.LayoutParams(-1, -2));
        AlertDialog dialog = new AlertDialog.Builder(owner)
                .setTitle("更新并提交市场结果")
                .setView(panel)
                .setNegativeButton("关闭", null)
                .setNeutralButton("打开仓库", (ignored, which) ->
                        GithubAccountUi.openBrowser(owner, linked.repositoryUrl))
                .create();
        owner.showRounded(dialog);
    }

    private void showBlocked(String title, String body,
            GithubProjectPublisher.Association linked) {
        LinearLayout panel = panel();
        TextView message = owner.text(body, 13, owner.MUTED);
        message.setTextIsSelectable(true);
        panel.addView(message, new LinearLayout.LayoutParams(-1, -2));
        AlertDialog.Builder builder = new AlertDialog.Builder(owner)
                .setTitle(title)
                .setView(panel)
                .setNegativeButton("关闭", null);
        if (linked != null) {
            builder.setNeutralButton("打开仓库", (ignored, which) ->
                    GithubAccountUi.openBrowser(owner, linked.repositoryUrl));
        }
        owner.showRounded(builder.create());
    }

    private LinearLayout panel() {
        LinearLayout panel = new LinearLayout(owner);
        panel.setOrientation(LinearLayout.VERTICAL);
        panel.setPadding(owner.dp(20), owner.dp(8), owner.dp(20), owner.dp(8));
        return panel;
    }

    private void runOnUiThread(TextView view, String message) {
        owner.runOnUiThread(() -> view.setText(message));
    }

    private void changed() {
        if (onChanged != null) onChanged.run();
    }

    private boolean enter() {
        synchronized (RUNNING) {
            return RUNNING.add(project.id);
        }
    }

    private void leave() {
        synchronized (RUNNING) {
            RUNNING.remove(project.id);
        }
    }

    static String actionableDetail(Throwable failure) {
        if (failure == null) return "未收到具体错误信息。";
        if (failure instanceof MarketRepositoryClient.SubmissionRejected) {
            MarketRepositoryClient.SubmissionRejected rejected =
                    (MarketRepositoryClient.SubmissionRejected) failure;
            String message = failure.getMessage() == null ? "" : failure.getMessage().trim();
            if ("repository_duplicate".equals(rejected.code)) {
                return message + "\n市场已有该版本或更高版本，本次无需重复提交；请递增工程版本后再试。";
            }
            if ("repository_version_mode_changed".equals(rejected.code)) {
                return message + "\n请恢复市场已收录的 versionMode 后再提交。";
            }
            if ("repository_version_invalid".equals(rejected.code)) {
                return message + "\n请检查工程版本号与 versionMode 是否匹配。";
            }
            if ("source_bad_id".equals(rejected.code)) {
                return message + "\n请在工程中重新添加或修正该网页源成员，再重新打包。";
            }
            if (rejected.code.startsWith("verification_code_")) {
                return message + "\n检测码只使用一次；重新点击提交即可自动重新预检。";
            }
            return message.isEmpty() ? failure.getClass().getSimpleName() : message;
        }
        String message = failure.getMessage();
        if (message != null && !message.trim().isEmpty()) return message.trim();
        return failure.getClass().getSimpleName();
    }
}
