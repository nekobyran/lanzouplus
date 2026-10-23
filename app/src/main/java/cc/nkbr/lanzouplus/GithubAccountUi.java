package cc.nkbr.lanzouplus;

import android.app.AlertDialog;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;
import java.io.IOException;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicBoolean;

/** GitHub 原生账户弹窗：设备验证码、授权等待、缓存账户、头像与本机退出。 */
final class GithubAccountUi {
    private GithubAccountUi() {}

    static void signIn(MainActivity owner, Runnable onSignedIn) {
        final int navigation = owner.navigationSession;
        if (!isOwnerCurrent(owner, navigation)) return;
        GithubAccountClient client = GithubAccountClient.production(owner);
        if (!client.isConfigured()) {
            owner.showRounded(new AlertDialog.Builder(owner)
                    .setTitle("GitHub 登录尚未配置")
                    .setMessage("当前版本缺少 GitHub OAuth Client ID。"
                            + "需要应用维护者注册 OAuth 应用并开启 Device Flow 后才能使用；无需粘贴 Token。")
                    .setPositiveButton("知道了", null)
                    .create());
            return;
        }

        LinearLayout panel = new LinearLayout(owner);
        panel.setOrientation(LinearLayout.VERTICAL);
        panel.setPadding(owner.dp(22), owner.dp(8), owner.dp(22), owner.dp(8));
        TextView status = owner.text("正在向 GitHub 申请一次性设备验证码…", 14, owner.TEXT);
        status.setAccessibilityLiveRegion(View.ACCESSIBILITY_LIVE_REGION_POLITE);
        TextView code = owner.text("---- ----", 24, owner.PRIMARY);
        code.setGravity(Gravity.CENTER);
        code.setTextIsSelectable(true);
        code.setPadding(0, owner.dp(14), 0, owner.dp(14));
        TextView instructions = owner.text(
                "授权步骤：复制验证码 → 打开 github.com/login/device → 输入验证码并同意公开仓库权限。\n"
                        + "登录过程在 GitHub 官网完成，本应用不要求提供密码或个人 Token。",
                12, owner.MUTED);
        panel.addView(status, new LinearLayout.LayoutParams(-1, -2));
        panel.addView(code, new LinearLayout.LayoutParams(-1, -2));
        panel.addView(instructions, new LinearLayout.LayoutParams(-1, -2));

        AtomicBoolean cancelled = new AtomicBoolean(false);
        final GithubAccountClient.DeviceCode[] challenge = new GithubAccountClient.DeviceCode[1];
        final Future<?>[] task = new Future<?>[1];
        AlertDialog dialog = new AlertDialog.Builder(owner)
                .setTitle("登录 GitHub · 设备授权")
                .setView(panel)
                .setNegativeButton("取消等待", null)
                .setNeutralButton("复制验证码", null)
                .setPositiveButton("打开 GitHub", null)
                .create();
        dialog.setOnDismissListener(ignored -> {
            cancelled.set(true);
            if (task[0] != null) task[0].cancel(true);
        });
        dialog.setOnShowListener(ignored -> {
            Button copy = dialog.getButton(AlertDialog.BUTTON_NEUTRAL);
            Button browser = dialog.getButton(AlertDialog.BUTTON_POSITIVE);
            copy.setEnabled(false);
            browser.setEnabled(false);
            dialog.getButton(AlertDialog.BUTTON_NEGATIVE).setOnClickListener(v -> dialog.dismiss());
            copy.setOnClickListener(v -> {
                if (challenge[0] == null) return;
                ClipboardManager clipboard =
                        (ClipboardManager) owner.getSystemService(Context.CLIPBOARD_SERVICE);
                clipboard.setPrimaryClip(ClipData.newPlainText(
                        "GitHub 设备验证码", challenge[0].userCode));
                owner.showNotice("GitHub 验证码已复制", false);
            });
            browser.setOnClickListener(v -> openBrowser(owner, GithubAccountClient.DEVICE_URL));
        });
        owner.showRounded(dialog);
        task[0] = owner.io.submit(() -> {
            try {
                GithubAccountClient.DeviceCode codeValue = client.requestDeviceCode();
                if (cancelled.get()) return;
                owner.runOnUiThread(() -> {
                    if (!canUpdate(owner, navigation, dialog, cancelled)) return;
                    challenge[0] = codeValue;
                    code.setText(codeValue.userCode);
                    status.setText("等待 GitHub 浏览器授权；验证码过期后需要重新申请。");
                    dialog.getButton(AlertDialog.BUTTON_NEUTRAL).setEnabled(true);
                    dialog.getButton(AlertDialog.BUTTON_POSITIVE).setEnabled(true);
                });
                GithubAccountClient.Session session = client.awaitAuthorization(
                        codeValue, cancelled, state -> owner.runOnUiThread(() -> {
                            if (canUpdate(owner, navigation, dialog, cancelled)) {
                                status.setText(state);
                            }
                        }));
                owner.runOnUiThread(() -> {
                    if (!canUpdate(owner, navigation, dialog, cancelled)) return;
                    try {
                        client.saveAuthorizedSession(session);
                        dialog.dismiss();
                        owner.showNotice("GitHub 登录成功：@" + session.profile.login, false);
                        GithubMarketProjectDiscovery.start(owner, true, null);
                        if (onSignedIn != null && isOwnerCurrent(owner, navigation)) {
                            onSignedIn.run();
                        }
                    } catch (IOException storage) {
                        status.setText(storage.getMessage());
                    }
                });
            } catch (InterruptedException ignored) {
                Thread.currentThread().interrupt();
            } catch (IOException failure) {
                if (cancelled.get()) return;
                owner.runOnUiThread(() -> {
                    if (!canUpdate(owner, navigation, dialog, cancelled)) return;
                    status.setText(failure.getMessage() + "\n关闭后可以重新登录。");
                    dialog.getButton(AlertDialog.BUTTON_NEUTRAL).setEnabled(false);
                    dialog.getButton(AlertDialog.BUTTON_POSITIVE).setEnabled(false);
                    dialog.getButton(AlertDialog.BUTTON_NEGATIVE).setText("关闭");
                });
            }
        });
    }

    /** 账户入口可直接在菜单或设置页调用；变更登录状态后回调刷新当前页面。 */
    static void accountAction(MainActivity owner, Runnable onChanged) {
        final int navigation = owner.navigationSession;
        if (!isOwnerCurrent(owner, navigation)) return;
        GithubAccountClient client = GithubAccountClient.production(owner);
        owner.io.execute(() -> {
            GithubAccountClient.Profile account = null;
            boolean authorized = false;
            String failure = "";
            try {
                account = client.cachedProfile();
                authorized = client.hasUsableSession();
            } catch (IOException error) {
                failure = error.getMessage();
            }
            GithubAccountClient.Profile finalAccount = account;
            boolean finalAuthorized = authorized;
            String finalFailure = failure;
            owner.runOnUiThread(() -> {
                if (!isOwnerCurrent(owner, navigation)) return;
                showAccount(owner, navigation, client, finalAccount, finalAuthorized,
                        finalFailure, onChanged);
            });
        });
    }

    private static void showAccount(MainActivity owner, int navigation,
            GithubAccountClient client, GithubAccountClient.Profile account,
            boolean authorized, String failure, Runnable onChanged) {
        LinearLayout panel = new LinearLayout(owner);
        panel.setOrientation(LinearLayout.VERTICAL);
        panel.setPadding(owner.dp(20), owner.dp(8), owner.dp(20), owner.dp(8));

        if (account != null) {
            LinearLayout row = new LinearLayout(owner);
            row.setGravity(Gravity.CENTER_VERTICAL);
            ImageView avatar = new ImageView(owner);
            avatar.setContentDescription("GitHub 用户头像");
            avatar.setImageResource(android.R.drawable.sym_def_app_icon);
            row.addView(avatar, new LinearLayout.LayoutParams(owner.dp(52), owner.dp(52)));
            TextView login = owner.text("@" + account.login
                    + (authorized ? "\n已登录 · 可创建公开 GitHub 仓库"
                    : "\n授权已过期 · 请重新登录"), 14, owner.TEXT);
            login.setPadding(owner.dp(12), 0, 0, 0);
            row.addView(login, new LinearLayout.LayoutParams(0, -2, 1));
            panel.addView(row, new LinearLayout.LayoutParams(-1, -2));
            if (account.avatarUrl.startsWith("https://")) {
                owner.requestImage(account.avatarUrl, avatar, true);
            }
            TextView hint = owner.text("退出会清除本机加密令牌；如需撤销 GitHub 网站上的授权，"
                    + "请在 GitHub 设置中撤销此 OAuth 应用。", 12, owner.MUTED);
            hint.setPadding(0, owner.dp(16), 0, 0);
            panel.addView(hint);
        } else {
            String content = failure.isEmpty()
                    ? "尚未登录 GitHub。登录将使用官方一次性设备验证码，只申请 public_repo 权限。"
                    : failure;
            panel.addView(owner.text(content, 14, owner.TEXT));
        }

        AlertDialog.Builder builder = new AlertDialog.Builder(owner)
                .setTitle("GitHub 账户")
                .setView(panel)
                .setNegativeButton("关闭", null)
                .setPositiveButton(account == null ? "登录 GitHub" : "重新登录",
                        (dialog, which) -> signIn(owner, onChanged));
        if (account != null || !failure.isEmpty()) {
            builder.setNeutralButton("退出本机账户", (dialog, which) ->
                    owner.io.execute(() -> {
                        try {
                            client.signOut();
                            owner.runOnUiThread(() -> {
                                if (!isOwnerCurrent(owner, navigation)) return;
                                owner.showNotice("已清除本机 GitHub 登录信息", false);
                                if (onChanged != null) onChanged.run();
                            });
                        } catch (IOException error) {
                            owner.runOnUiThread(() -> {
                                if (isOwnerCurrent(owner, navigation)) {
                                    owner.showNotice(error.getMessage(), true);
                                }
                            });
                        }
                    }));
        }
        owner.showRounded(builder.create());
    }

    static boolean isOwnerCurrent(MainActivity owner, int navigation) {
        return owner.navigationSession == navigation && !owner.isFinishing() && !owner.isDestroyed();
    }

    private static boolean canUpdate(MainActivity owner, int navigation,
            AlertDialog dialog, AtomicBoolean cancelled) {
        return !cancelled.get() && isOwnerCurrent(owner, navigation) && dialog.isShowing();
    }

    static void openBrowser(MainActivity owner, String url) {
        try {
            Intent intent = new Intent(Intent.ACTION_VIEW, Uri.parse(url));
            intent.addCategory(Intent.CATEGORY_BROWSABLE);
            owner.startActivity(intent);
        } catch (android.content.ActivityNotFoundException unavailable) {
            owner.showNotice("设备上没有可用浏览器", true);
        }
    }
}
