package cc.nkbr.lanzouplus;

import android.app.AlertDialog;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.SharedPreferences;
import android.net.Uri;
import android.util.Base64;
import android.view.View;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import org.json.JSONArray;
import org.json.JSONObject;

/**
 * 每个 GitHub 登录账号的每份本地工程独立关联一个已创建的公开仓库。
 * Git Database API 先制作完整树和单一提交，最后非强制推进默认分支。
 * 未由该工程管理的文件由 base_tree 保留，不会发布到市场。
 */
final class GithubProjectPublisher {
    private static final String ASSOCIATIONS = "github_project_associations_v1";
    private static final Object ASSOCIATION_LOCK = new Object();
    private static final Object PUBLISH_LOCK = new Object();

    private GithubProjectPublisher() {}

    interface Progress {
        void status(String message);
    }

    static final class Association {
        final String repositoryUrl;
        final String ownerLogin;
        final String repositoryName;
        final String defaultBranch;
        final long repositoryId;
        final LinkedHashSet<String> managedFiles;
        final boolean published;

        Association(String repositoryUrl, String ownerLogin, String repositoryName,
                String defaultBranch, long repositoryId, Set<String> managedFiles, boolean published) {
            this.repositoryUrl = repositoryUrl;
            this.ownerLogin = ownerLogin;
            this.repositoryName = repositoryName;
            this.defaultBranch = defaultBranch;
            this.repositoryId = repositoryId;
            this.managedFiles = new LinkedHashSet<>(managedFiles);
            this.published = published;
        }

        Association withBranch(String branch) {
            return new Association(repositoryUrl, ownerLogin, repositoryName, branch,
                    repositoryId, managedFiles, published);
        }

        Association committed(String branch, Set<String> files) {
            return new Association(repositoryUrl, ownerLogin, repositoryName, branch,
                    repositoryId, files, true);
        }
    }

    static final class Outcome {
        final String repositoryUrl;
        final int preparedFiles;
        final int expectedFiles;
        final String error;
        final boolean newRepository;
        final boolean refUpdated;
        final boolean alreadyLatest;

        Outcome(String repositoryUrl, int preparedFiles, int expectedFiles,
                String error, boolean newRepository, boolean refUpdated) {
            this(repositoryUrl, preparedFiles, expectedFiles, error, newRepository, refUpdated,
                    false);
        }

        Outcome(String repositoryUrl, int preparedFiles, int expectedFiles,
                String error, boolean newRepository, boolean refUpdated, boolean alreadyLatest) {
            this.repositoryUrl = repositoryUrl;
            this.preparedFiles = preparedFiles;
            this.expectedFiles = expectedFiles;
            this.error = error;
            this.newRepository = newRepository;
            this.refUpdated = refUpdated;
            this.alreadyLatest = alreadyLatest;
        }

        boolean hasRepository() { return !repositoryUrl.isEmpty(); }
        boolean complete() { return (refUpdated || alreadyLatest) && error.isEmpty(); }
    }

    /**
     * 关联键只包含公开用户名和本机工程 ID，不包含认证数据。
     * URL、分支、GitHub 稳定仓库 ID 及已完成提交的文件清单跨进程保留。
     */
    static Association loadAssociation(Context context, String login, String projectId)
            throws IOException {
        synchronized (ASSOCIATION_LOCK) {
            String value = context.getApplicationContext()
                    .getSharedPreferences(ASSOCIATIONS, Context.MODE_PRIVATE)
                    .getString(associationKey(login, projectId), "");
            if (value.isEmpty()) return null;
            try {
                JSONObject stored = new JSONObject(value);
                String owner = stored.getString("owner");
                String name = stored.getString("repository");
                String url = stored.getString("url");
                String branch = stored.optString("branch");
                long id = stored.getLong("id");
                if (!owner.equalsIgnoreCase(login) || id <= 0
                        || !url.equals(MarketRepository.canonicalUrl(url))
                        || !url.equals("https://github.com/" + owner + "/" + name)) {
                    throw new IOException("关联信息与登录账号不一致");
                }
                JSONArray paths = stored.getJSONArray("files");
                LinkedHashSet<String> managed = new LinkedHashSet<>();
                for (int i = 0; i < paths.length(); i++) {
                    String path = paths.getString(i);
                    validFilePath(path);
                    managed.add(path);
                }
                return new Association(url, owner, name, branch, id, managed,
                        stored.optBoolean("published"));
            } catch (Exception corrupted) {
                throw new IOException("本机 GitHub 工程关联记录无法读取，为防止覆盖其他仓库已停止发布");
            }
        }
    }

    static void saveAssociation(Context context, String login, String projectId,
            Association binding) throws IOException {
        synchronized (ASSOCIATION_LOCK) {
            try {
                JSONObject value = new JSONObject()
                        .put("url", binding.repositoryUrl)
                        .put("owner", binding.ownerLogin)
                        .put("repository", binding.repositoryName)
                        .put("branch", binding.defaultBranch)
                        .put("id", binding.repositoryId)
                        .put("published", binding.published)
                        .put("files", new JSONArray(binding.managedFiles));
                SharedPreferences prefs = context.getApplicationContext()
                        .getSharedPreferences(ASSOCIATIONS, Context.MODE_PRIVATE);
                if (!prefs.edit().putString(associationKey(login, projectId),
                        value.toString()).commit()) {
                    throw new IOException("无法保存本机工程与 GitHub 仓库的关联");
                }
            } catch (org.json.JSONException invalid) {
                throw new IOException("无法保存 GitHub 工程关联信息");
            }
        }
    }

    private static String associationKey(String login, String projectId) {
        byte[] plain = (login.toLowerCase(Locale.ROOT) + "\n" + projectId)
                .getBytes(StandardCharsets.UTF_8);
        return Base64.encodeToString(plain, Base64.NO_WRAP | Base64.URL_SAFE);
    }

    /** 工程操作菜单的固定入口：先登录当前用户，再决定首次创建还是更新。 */
    static void publish(MainActivity owner, MarketProject project) {
        int navigation = owner.navigationSession;
        if (!GithubAccountUi.isOwnerCurrent(owner, navigation)) return;
        GithubAccountClient client = GithubAccountClient.production(owner);
        owner.io.execute(() -> {
            try {
                GithubAccountClient.Profile profile = client.cachedProfile();
                boolean signedIn = profile != null && client.hasUsableSession();
                if (!signedIn) {
                    owner.runOnUiThread(() -> {
                        if (GithubAccountUi.isOwnerCurrent(owner, navigation)) {
                            GithubAccountUi.signIn(owner, () -> publish(owner, project));
                        }
                    });
                    return;
                }
                Association linked = loadAssociation(owner, profile.login, project.id);
                owner.runOnUiThread(() -> {
                    if (GithubAccountUi.isOwnerCurrent(owner, navigation)) {
                        confirmPublish(owner, client, project, navigation, profile.login, linked);
                    }
                });
            } catch (IOException failure) {
                owner.runOnUiThread(() -> {
                    if (GithubAccountUi.isOwnerCurrent(owner, navigation)) {
                        owner.showNotice(failure.getMessage(), true);
                    }
                });
            }
        });
    }

    private static void confirmPublish(MainActivity owner, GithubAccountClient client,
            MarketProject project, int navigation, String confirmedLogin, Association linked) {
        String requestedName = project.metadata.optString("name").trim();
        String description = project.metadata.optString("description").trim();
        boolean updating = linked != null;
        boolean renamed = updating && !requestedName.equalsIgnoreCase(linked.repositoryName);
        StringBuilder detail = new StringBuilder();
        if (updating) {
            detail.append("已关联仓库：").append(linked.repositoryUrl)
                    .append("\n上次默认分支：")
                    .append(linked.defaultBranch.isEmpty() ? "待检测" : linked.defaultBranch)
                    .append("\n上次由此工程管理的文件：").append(linked.managedFiles.size())
                    .append(" 个\n\n");
            if (renamed) {
                detail.append("注意：工程名称已改为「").append(requestedName)
                        .append("」，与已关联仓库「").append(linked.repositoryName)
                        .append("」不同。此次仅更新上述已关联仓库，不会自动重命名，")
                        .append("也不会覆盖其它同名仓库。\n\n");
            }
            detail.append("将一次提交所有工程文件，只删除此前由本工程管理且本次已移除的文件。")
                    .append("GitHub 中其它文件将保留。");
        } else {
            detail.append("GitHub 仓库：").append(requestedName)
                    .append("\n简介：").append(description.isEmpty() ? "无" : description)
                    .append("\n\n将创建全新公开仓库并一次提交工程 ZIP 根目录内容。")
                    .append("已存在但未关联的同名仓库不会被接管或覆盖。");
        }
        detail.append("\n\n创建或更新后不会自动提交市场。");
        AlertDialog confirm = new AlertDialog.Builder(owner)
                .setTitle(updating ? (renamed ? "名称不一致 · 确认更新原仓库？"
                        : "更新已关联的 GitHub 仓库？") : "创建公开 GitHub 仓库？")
                .setMessage(detail.toString())
                .setNegativeButton("取消", null)
                .setPositiveButton(updating ? "确认更新关联仓库" : "确认创建并上传",
                        (dialog, which) -> startPublish(
                                owner, project, client, navigation, confirmedLogin))
                .create();
        owner.showRounded(confirm);
    }

    private static void startPublish(MainActivity owner, MarketProject project,
            GithubAccountClient client, int navigation, String confirmedLogin) {
        if (!GithubAccountUi.isOwnerCurrent(owner, navigation)) return;
        AtomicBoolean cancelled = new AtomicBoolean();
        TextView status = owner.text("正在准备 GitHub 工程文件…", 14, owner.TEXT);
        status.setAccessibilityLiveRegion(View.ACCESSIBILITY_LIVE_REGION_POLITE);
        status.setPadding(owner.dp(18), owner.dp(10), owner.dp(18), owner.dp(10));
        AlertDialog dialog = new AlertDialog.Builder(owner)
                .setTitle("GitHub 仓库发布")
                .setView(status)
                .setNegativeButton("取消后续操作", null)
                .create();
        dialog.setCancelable(false);
        dialog.setOnShowListener(ignored -> dialog.getButton(AlertDialog.BUTTON_NEGATIVE)
                .setOnClickListener(v -> {
                    cancelled.set(true);
                    status.setText("正在停止：当前请求结束后不会继续推进分支…");
                    dialog.getButton(AlertDialog.BUTTON_NEGATIVE).setEnabled(false);
                }));
        owner.showRounded(dialog);
        owner.io.execute(() -> {
            Outcome result;
            try {
                result = uploadForAccount(owner, client, confirmedLogin, project,
                        GithubProjectPublisher::assetBytes, cancelled,
                        message -> owner.runOnUiThread(() -> {
                            if (GithubAccountUi.isOwnerCurrent(owner, navigation)
                                    && dialog.isShowing() && !cancelled.get()) {
                                status.setText(message);
                            } else if (!GithubAccountUi.isOwnerCurrent(owner, navigation)) {
                                cancelled.set(true);
                            }
                        }));
            } catch (Exception failure) {
                result = new Outcome("", 0, 0, safeMessage(failure), false, false);
            }
            if(result.refUpdated || result.alreadyLatest){
                try{
                    project.metadata.put("repositoryUrl",result.repositoryUrl);
                    project.save(new File(new File(owner.getFilesDir(),"market-projects"),project.id+".json"));
                }catch(Exception bindingFailure){
                    result=new Outcome(result.repositoryUrl,result.preparedFiles,result.expectedFiles,
                            result.alreadyLatest
                                    ? "GitHub 仓库已是最新，但本机工程绑定保存失败，请重新打开工程确认"
                                    : "GitHub 已发布，但本机工程绑定保存失败，请重新打开工程确认",
                            result.newRepository, result.refUpdated, result.alreadyLatest);
                }
            }
            Outcome resultOnUi = result;
            owner.runOnUiThread(() -> {
                if (!GithubAccountUi.isOwnerCurrent(owner, navigation)) return;
                dialog.dismiss();
                showOutcome(owner, navigation, resultOnUi);
            });
        });
    }

    /**
     * 可用注入 Transport/SessionStore/AssetReader 的无界面发布核心。
     * 创建后马上关联仓库，只有成功 PATCH ref 才记录本轮管理的文件清单。
     */
    static Outcome upload(Context context, GithubAccountClient client, MarketProject project,
            MarketProject.AssetReader reader, AtomicBoolean cancelled, Progress progress)
            throws Exception {
        return uploadForAccount(context, client, null, project, reader, cancelled, progress);
    }

    private static Outcome uploadForAccount(Context context, GithubAccountClient client,
            String confirmedLogin, MarketProject project, MarketProject.AssetReader reader,
            AtomicBoolean cancelled, Progress progress) throws Exception {
        synchronized (PUBLISH_LOCK) {
            GithubAccountClient.Profile profile = client.cachedProfile();
            if (profile == null || !client.hasUsableSession()) {
                throw new IOException("请先登录自己的 GitHub 账户");
            }
            String login = profile.login;
            if (confirmedLogin != null && !confirmedLogin.equalsIgnoreCase(login)) {
                throw new IOException("GitHub 登录账号已改变，请重新确认要发布的仓库");
            }
            String requestedName = project.metadata.optString("name").trim();
            if (!requestedName.matches("[A-Za-z0-9_.-]+")) {
                throw new IOException("GitHub 仓库名称不符合规范");
            }
            project.metadata.put("publisher", profile.login);
            project.metadata.put("publisherAvatar", profile.avatarUrl);
            Association associated = loadAssociation(context, login, project.id);
            ensureNotCancelled(cancelled);
            progress.status("正在构建标准工程 ZIP，并准备一次提交全部内容…");
            Map<String, byte[]> files = new LinkedHashMap<>(
                    MarketProject.readZip(project.buildRepositoryZip(reader)));
            for (String path : files.keySet()) validFilePath(path);
            if (files.isEmpty()) throw new IOException("工程 ZIP 中没有可上传的文件");
            int total = files.size();
            boolean createdNow = false;

            if (associated == null) {
                ensureNotCancelled(cancelled);
                String repositoryPath = repositoryPath(login, requestedName);
                // 接管权限只来自本机明确的关联；已有仓库不会被误认为新工程。
                try {
                    client.apiForAccount(login, "GET", repositoryPath, null);
                    return new Outcome("", 0, total,
                            "同名 GitHub 仓库已存在，但未与此工程关联："
                                    + "https://github.com/" + login + "/" + requestedName
                                    + "；不会覆盖原仓库",
                            false, false);
                } catch (GithubAccountClient.ApiFailure absence) {
                    if (absence.status != 404) throw absence;
                }
                ensureNotCancelled(cancelled);
                progress.status("正在创建新的公开 GitHub 仓库：" + requestedName);
                JSONObject createRequest = new JSONObject()
                        .put("name", requestedName)
                        .put("description", project.metadata.optString("description"))
                        .put("private", false)
                        .put("auto_init", true);
                JSONObject created;
                try {
                    created = client.apiForAccount(login, "POST", "/user/repos", createRequest);
                } catch (GithubAccountClient.ApiFailure rejected) {
                    if (rejected.status == 422) {
                        throw new IOException("同名 GitHub 仓库可能已存在或名称不可用；"
                                + "未关联仓库不会被覆盖，请在 GitHub 检查后重试");
                    }
                    throw rejected;
                } catch (IOException uncertain) {
                    throw new IOException("GitHub 创建请求的结果未能确认，请先在官网检查仓库，"
                            + "不要直接重复创建");
                }
                createdNow = true;
                JSONObject owner = created.optJSONObject("owner");
                String returnedOwner = owner == null ? "" : owner.optString("login");
                String returnedName = created.optString("name");
                String expectedUrl = "https://github.com/" + login + "/" + requestedName;
                if (!returnedOwner.equalsIgnoreCase(login)
                        || !returnedName.equalsIgnoreCase(requestedName)
                        || created.optBoolean("private")) {
                    return new Outcome(expectedUrl, 0, total,
                            "仓库已创建，但 GitHub 返回的仓库身份或可见性不符合要求，已停止",
                            true, false);
                }
                long id = created.optLong("id");
                if (id <= 0) {
                    return new Outcome(expectedUrl, 0, total,
                            "仓库已创建，但 GitHub 未返回稳定仓库 ID，请核对后再操作",
                            true, false);
                }
                associated = new Association("https://github.com/" + returnedOwner + "/"
                        + returnedName, returnedOwner, returnedName,
                        created.optString("default_branch"), id, new LinkedHashSet<>(), false);
                try {
                    saveAssociation(context, login, project.id, associated);
                } catch (IOException storage) {
                    return new Outcome(associated.repositoryUrl, 0, total,
                            "仓库已创建，但本机关联记录未保存成功，请勿继续创建同名仓库",
                            true, false);
                }
            }

            int[] prepared = {0};
            try {
                return commitTree(context, client, login, project.id, associated, files,
                        cancelled, progress, createdNow, prepared);
            } catch (Exception failure) {
                return new Outcome(associated.repositoryUrl, prepared[0], total,
                        safeMessage(failure), createdNow, false);
            }
        }
    }

    private static Outcome commitTree(Context context, GithubAccountClient client, String login,
            String projectId, Association bound, Map<String, byte[]> files,
            AtomicBoolean cancelled, Progress progress, boolean createdNow, int[] prepared)
            throws Exception {
        String repository = repositoryPath(bound.ownerLogin, bound.repositoryName);
        ensureNotCancelled(cancelled);
        JSONObject remote;
        try {
            remote = client.apiForAccount(login, "GET", repository, null);
        } catch (GithubAccountClient.ApiFailure missing) {
            if (missing.status == 404) {
                throw new IOException("已关联的 GitHub 仓库无法访问，不会新建替代仓库");
            }
            throw missing;
        }
        JSONObject owner = remote.optJSONObject("owner");
        if (owner == null || !owner.optString("login").equalsIgnoreCase(login)
                || remote.optLong("id") != bound.repositoryId
                || !remote.optString("name").equalsIgnoreCase(bound.repositoryName)
                || remote.optBoolean("private")) {
            throw new IOException("关联仓库的所有者、身份或公开状态已变化，已阻止覆盖");
        }
        String branch = remote.optString("default_branch").trim();
        if (branch.isEmpty()) throw new IOException("GitHub 尚未建立默认分支，请稍后重新发布");
        String branchRef = repository + "/git/ref/heads/" + Uri.encode(branch, "/");
        String originalHead = requiredId(client.apiForAccount(login, "GET", branchRef, null)
                .optJSONObject("object"), "GitHub 默认分支引用缺失");
        JSONObject headCommit = client.apiForAccount(login, "GET",
                repository + "/git/commits/" + Uri.encode(originalHead), null);
        String baseTree = requiredId(headCommit.optJSONObject("tree"),
                "GitHub 默认分支树不存在");

        ensureNotCancelled(cancelled);
        JSONObject treeSnapshot = client.apiForAccount(login, "GET",
                repository + "/git/trees/" + Uri.encode(baseTree) + "?recursive=1", null);
        if (treeSnapshot.optBoolean("truncated")) {
            throw new IOException("GitHub 仓库文件清单被截断，已停止以保护原有文件");
        }
        LinkedHashSet<String> remoteFiles = new LinkedHashSet<>();
        Map<String, String> remoteBlobs = new LinkedHashMap<>();
        JSONArray existingTree = treeSnapshot.optJSONArray("tree");
        if (existingTree == null) throw new IOException("GitHub 未返回仓库文件清单");
        for (int i = 0; i < existingTree.length(); i++) {
            JSONObject member = existingTree.optJSONObject(i);
            if (member != null && "blob".equals(member.optString("type"))) {
                String path = member.optString("path");
                remoteFiles.add(path);
                remoteBlobs.put(path,
                        requiredId(member, "GitHub 仓库文件对象 ID 缺失：" + path));
            }
        }
        // 新增路径若已属于其它作者且不在上次管理清单中，拒绝覆盖。
        // GitHub auto_init 生成的 README.md 是首次发布唯一允许替换的文件。
        for (String path : files.keySet()) {
            if (remoteFiles.contains(path) && !bound.managedFiles.contains(path)
                    && !((createdNow || !bound.published) && isInitializedReadme(path))) {
                throw new IOException("远端已有非本工程管理的同名文件：" + path + "；已阻止覆盖");
            }
        }

        JSONArray treeChanges = new JSONArray();
        for (Map.Entry<String, byte[]> file : files.entrySet()) {
            ensureNotCancelled(cancelled);
            String filename = file.getKey();
            progress.status("准备 Git 对象 " + (prepared[0] + 1) + "/" + files.size()
                    + "：" + filename);
            String remoteBlob = remoteBlobs.get(filename);
            if (remoteBlob != null) {
                byte[] remoteBytes = readBlob(client, login, repository, remoteBlob, filename);
                if (sameFileContent(filename, file.getValue(), remoteBytes)) continue;
            }
            JSONObject blobRequest = new JSONObject()
                    .put("content", Base64.encodeToString(file.getValue(), Base64.NO_WRAP))
                    .put("encoding", "base64");
            JSONObject blob = client.apiForAccount(login, "POST",
                    repository + "/git/blobs", blobRequest);
            treeChanges.put(new JSONObject()
                    .put("path", filename)
                    .put("mode", "100644")
                    .put("type", "blob")
                    .put("sha", requiredId(blob, "GitHub 未返回新文件对象 ID")));
            prepared[0]++;
        }
        for (String managed : bound.managedFiles) {
            if (!files.containsKey(managed) && remoteFiles.contains(managed)) {
                treeChanges.put(new JSONObject()
                        .put("path", managed)
                        .put("mode", "100644")
                        .put("type", "blob")
                        .put("sha", JSONObject.NULL));
            }
        }

        if (treeChanges.length() == 0) {
            progress.status("GitHub 仓库内容已是最新，未创建新提交");
            return new Outcome(bound.repositoryUrl, 0, files.size(), "",
                    createdNow, false, true);
        }

        ensureNotCancelled(cancelled);
        progress.status("全部文件已准备，正在基于现有仓库树创建完整快照…");
        JSONObject tree = client.apiForAccount(login, "POST", repository + "/git/trees",
                new JSONObject().put("base_tree", baseTree).put("tree", treeChanges));
        String newTree = requiredId(tree, "GitHub 未返回新树对象 ID");
        ensureNotCancelled(cancelled);
        JSONObject commit = client.apiForAccount(login, "POST", repository + "/git/commits",
                new JSONObject()
                        .put("message", "发布 LZPMarket 工程文件")
                        .put("tree", newTree)
                        .put("parents", new JSONArray().put(originalHead)));
        String newCommit = requiredId(commit, "GitHub 未返回新提交对象 ID");

        ensureNotCancelled(cancelled);
        String latestHead = requiredId(client.apiForAccount(login, "GET", branchRef, null)
                .optJSONObject("object"), "GitHub 默认分支引用缺失");
        if (!originalHead.equals(latestHead)) {
            throw new IOException("GitHub 默认分支在准备期间被其他操作更新；未覆盖，请重新发布");
        }
        ensureNotCancelled(cancelled);
        progress.status("正在一次更新默认分支，所有文件将同时生效…");
        String updateBranchRef = repository + "/git/refs/heads/" + Uri.encode(branch, "/");
        try {
            client.apiForAccount(login, "PATCH", updateBranchRef,
                    new JSONObject().put("sha", newCommit).put("force", false));
        } catch (GithubAccountClient.ApiFailure failure) {
            if (failure.status == 409 || failure.status == 422) {
                throw new IOException("GitHub 分支发生冲突，未强制覆盖；请重新发布");
            }
            throw failure;
        } catch (IOException uncertain) {
            throw new IOException("GitHub 分支更新结果未能确认，请先在仓库核实本轮提交，"
                    + "不要直接视为成功");
        }

        // PATCH ref 已成功；此后才可记录本轮清单为“上次由工程管理的文件”。
        try {
            saveAssociation(context, login, projectId,
                    bound.committed(branch, files.keySet()));
            return new Outcome(bound.repositoryUrl, prepared[0], files.size(),
                    "", createdNow, true);
        } catch (IOException storage) {
            return new Outcome(bound.repositoryUrl, prepared[0], files.size(),
                    "GitHub 远端已完成一次提交，但本机关联文件清单保存失败，后续更新前需先修复本机存储",
                    createdNow, true);
        }
    }

    private static byte[] readBlob(GithubAccountClient client, String login, String repository,
            String blobId, String path) throws IOException {
        JSONObject blob = client.apiForAccount(login, "GET",
                repository + "/git/blobs/" + Uri.encode(blobId), null);
        if (!"base64".equals(blob.optString("encoding"))) {
            throw new IOException("GitHub 返回的文件内容编码无法读取：" + path);
        }
        try {
            return Base64.decode(blob.optString("content"), Base64.DEFAULT);
        } catch (IllegalArgumentException malformed) {
            throw new IOException("GitHub 返回的文件内容无法读取：" + path);
        }
    }

    private static boolean sameFileContent(String path, byte[] local, byte[] remote)
            throws IOException {
        if (!MarketProject.SOURCES_ENTRY.equals(path)) return Arrays.equals(local, remote);
        // sources.lzp 每次含随机 IV；只比较源 JSON 明文，上传内容仍是原密文。
        try {
            return Arrays.equals(MarketSourceCodec.decode(local),
                    MarketSourceCodec.decode(remote));
        } catch (IllegalArgumentException malformed) {
            return false;
        }
    }

    private static boolean isInitializedReadme(String path) {
        return "README.md".equalsIgnoreCase(path);
    }

    private static String repositoryPath(String owner, String name) {
        return "/repos/" + Uri.encode(owner) + "/" + Uri.encode(name);
    }

    private static String requiredId(JSONObject record, String message) throws IOException {
        if (record == null) throw new IOException(message);
        String id = record.optString("sha");
        if (id.isEmpty()) throw new IOException(message);
        // GitHub 给出的 Git 对象版本标识只原样传给后续 API，不自行计算。
        return id;
    }

    private static void validFilePath(String path) throws IOException {
        MarketRepository.path(path);
        for (String segment : path.split("/", -1)) {
            if (segment.isEmpty() || ".".equals(segment) || "..".equals(segment)) {
                throw new IOException("ZIP 文件路径无效");
            }
        }
    }

    private static void ensureNotCancelled(AtomicBoolean cancelled) throws IOException {
        if (cancelled.get() || Thread.currentThread().isInterrupted()) {
            throw new IOException("已取消后续上传；GitHub 默认分支未被本次操作更新");
        }
    }

    private static byte[] assetBytes(String reference) throws Exception {
        try {
            if (reference.startsWith("https://")) {
                // 图片为匿名素材请求，不携带 GitHub Token。
                return MarketRepositoryClient.httpGet(reference, 8 * 1024 * 1024);
            }
            try (InputStream input = new FileInputStream(reference)) {
                return MarketRepositoryClient.bytes(input, 8 * 1024 * 1024);
            }
        } catch (Exception failure) {
            throw new IOException("工程图片读取失败，请检查本地图片或 HTTPS 地址");
        }
    }

    private static String safeMessage(Exception error) {
        if (error instanceof IOException && error.getMessage() != null) {
            return error.getMessage();
        }
        return "工程打包或 GitHub 发布失败，请检查工程信息";
    }

    private static void showOutcome(MainActivity owner, int navigation, Outcome result) {
        if (!GithubAccountUi.isOwnerCurrent(owner, navigation)) return;
        String title;
        String message;
        if (result.complete()) {
            if (result.alreadyLatest) {
                title = "GitHub 仓库已是最新";
                message = "工程内容与仓库默认分支一致，未创建新提交。"
                        + "\n" + result.repositoryUrl + "\n\n尚未提交至市场，可另行操作。";
            } else {
                title = result.newRepository ? "已创建并发布 GitHub 仓库" : "GitHub 仓库更新成功";
                message = "已将 " + result.expectedFiles + " 个工程文件在默认分支一次提交生效。"
                        + "\n" + result.repositoryUrl + "\n\n尚未提交至市场，可另行操作。";
            }
        } else if (result.alreadyLatest) {
            title = "GitHub 仓库已是最新 · 本机记录异常";
            message = result.repositoryUrl + "\n\n" + result.error;
        } else if (result.refUpdated) {
            title = "GitHub 远端已更新 · 本机记录异常";
            message = result.repositoryUrl + "\n\n" + result.error;
        } else if (result.hasRepository()) {
            title = result.newRepository ? "新仓库已创建 · 文件尚未完整发布"
                    : "已关联仓库尚未更新";
            message = "仓库：" + result.repositoryUrl
                    + "\n已准备 Git 对象：" + result.preparedFiles + "/" + result.expectedFiles
                    + "（对象不代表分支已经发布）"
                    + "\n原因：" + result.error
                    + "\n\n不会删除仓库、回滚远端修改或覆盖其它文件。";
        } else {
            title = "GitHub 发布未完成";
            message = result.error;
        }
        LinearLayout panel = new LinearLayout(owner);
        panel.setOrientation(LinearLayout.VERTICAL);
        panel.setPadding(owner.dp(20), owner.dp(8), owner.dp(20), owner.dp(6));
        panel.addView(owner.text(message, 13, owner.TEXT),
                new LinearLayout.LayoutParams(-1, -2));
        if (result.hasRepository()) {
            Button copy = owner.button("复制仓库地址");
            copy.setOnClickListener(v -> {
                ClipboardManager clipboard =
                        (ClipboardManager) owner.getSystemService(Context.CLIPBOARD_SERVICE);
                clipboard.setPrimaryClip(ClipData.newPlainText("GitHub 仓库地址",
                        result.repositoryUrl));
                owner.showNotice("仓库地址已复制", false);
            });
            addOutcomeAction(owner, panel, copy);
            Button open = owner.button("在浏览器打开仓库");
            open.setOnClickListener(v -> GithubAccountUi.openBrowser(owner, result.repositoryUrl));
            addOutcomeAction(owner, panel, open);
            if (result.refUpdated) {
                Button add = owner.button("添加为列表源（不提交市场）");
                add.setOnClickListener(v -> addAsSource(owner, navigation, result.repositoryUrl));
                addOutcomeAction(owner, panel, add);
            }
        }
        owner.showRounded(new AlertDialog.Builder(owner)
                .setTitle(title)
                .setView(panel)
                .setNegativeButton("关闭", null)
                .create());
    }

    private static void addOutcomeAction(MainActivity owner, LinearLayout panel, Button button) {
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(-1, owner.dp(48));
        params.topMargin = owner.dp(8);
        panel.addView(button, params);
    }

    private static void addAsSource(MainActivity owner, int navigation, String repositoryUrl) {
        owner.io.execute(() -> {
            try {
                owner.core.addUserSource(repositoryUrl, "");
                owner.runOnUiThread(() -> {
                    if (GithubAccountUi.isOwnerCurrent(owner, navigation)) {
                        owner.showNotice("已添加 GitHub 仓库为列表源，尚未提交市场", false);
                    }
                });
            } catch (Exception failure) {
                owner.runOnUiThread(() -> {
                    if (GithubAccountUi.isOwnerCurrent(owner, navigation)) {
                        owner.showNotice("添加列表源失败：" + safeMessage(failure), true);
                    }
                });
            }
        });
    }
}
