# LanzouPlus

LanzouPlus 是一个轻量原生 Android 蓝奏目录浏览、全源搜索与下载客户端。

本仓库只发布 **空源、无主页推荐** 的 LanzouPlus 公开版源码：安装后由用户自行导入其有权访问且信任的规则。内置目录、推荐、私有更新配置与私有构建产物，以及签名材料和账号信息都不在本仓库中。

> 产品边界：本仓库、GitHub Release 与公开官网只包含 LanzouPlus。

## 正式版本

- 当前版本：`1.8.8alpha`
- 包名：`cc.nkbr.lanzouplus`
- 最低系统：Android 7.0（API 24）
- 正式下载：[lanzouplus.nkbr.cc](https://lanzouplus.nkbr.cc/)
- 官网：[lanzouplus.nkbr.cc](https://lanzouplus.nkbr.cc/)

正式 APK 会校验发布元数据、安装包大小与已安装应用的签名身份后再交给系统安装器。

## 功能

- 导入、导出并合并本地或 HTTPS 源规则，支持明文源导出与订阅路径选择
- 分类标签与多路径搜索范围；并发搜索、分页进度、暂停与继续
- 蓝奏目录和文件夹浏览、缓存与路径导航；目录分页沿用公开的状态语义
- 资源市场与 GitHub 标准仓库源：仓库作为一个项目显示，进入后查看内部源并缓存到本地
- 本地仓库工程页：保存工程、明文导入导出并生成标准仓库压缩包
- 设置页按“列表与外观、搜索与索引、源与市场、下载、账号与权限、关于与更新”横向分类切换
- 引导流程覆盖权限、蓝奏基础链接验证、GitHub 源与更新通道，完成后播放一次主页过场
- 更新通道区分稳定与测试两条清单，并按 SemVer 规则比较版本
- 下载历史、失败重试、系统安装及文件管理器联动
- 跟随系统的明暗主题与横竖屏自适应布局

## 构建

需要 JDK 17 与 Android SDK：

```powershell
.\gradlew.bat --no-daemon clean assembleRelease
```

默认且唯一可用的公开构建为 empty flavor；`app/src/empty/assets/s`、`r`、`u`、`c` 必须保持 0 字节。

## 安全与合规

- 仅导入你信任且有权使用的目录或规则。
- 不要在 issue、日志或提交中公开密码、登录态、私有目录和签名文件。
- 安全问题请通过[正式发布页](https://lanzouplus.nkbr.cc/)所列作者渠道联系，不要公开披露利用细节。

## 开源许可证

本项目采用 [MIT License](LICENSE)。允许个人与商业使用、修改及再分发；复制或分发本软件及其重要部分时，必须保留原版权声明与许可声明。

## 开源借鉴

- [npm/node-semver](https://github.com/npm/node-semver)：更新检查沿用其 SemVer 分段比较与预发布排序规则；未引入其源码或运行依赖。
- [zaxtyson/LanZouCloud-API](https://github.com/zaxtyson/LanZouCloud-API)（MIT）：目录分页沿用其记录的公开状态含义，并按本项目实测结果实现；未引入其源文件。
- [AppIntro](https://github.com/AppIntro/AppIntro)（Apache-2.0）：引导入场借鉴按方向组合位移、透明度与恢复交互状态的思路，逐元素时序和主页形变使用 Android 原生 Animator 实现。
- [AstrBot](https://github.com/AstrBotDevs/AstrBot)：资源市场借鉴以 GitHub 仓库登记信息、读取上游描述与配图的组织方式。
- [GitHub CLI](https://github.com/cli/cli)（MIT）：正式发布复用 Release 创建、文件上传和回读命令，Alpha 使用 prerelease 且不更新稳定 latest。

上游内容与权利归原作者所有。

作者：nekobyran · Bilibili UID：607234739

## 赞助(subscription)
![图片](https://github.com/nekobyran/lanzouplus/blob/e61e81b8bea3030a4c7aa9c544e802a9d907c2ba/Screenshot_2026-07-17-22-38-28-48_3915bacb930634b7e206116f9dc9486f.jpg)

---

## English

LanzouPlus is a lightweight native Android client for browsing Lanzou directories, searching imported sources, and downloading files.

This public repository contains only the **empty-source edition with no home recommendations**. Users import only rules and directories they are authorized to access and trust. Built-in catalogs, recommendations, private update configuration and private artifacts, signing material, and account data are not published here.

> Product boundary: this repository, its GitHub releases, and the public website contain LanzouPlus only.

### Features

- Import, export, and merge local or HTTPS source rules, including plain-text source export and subscription path selection
- Category tags and multi-path search scope; concurrent search with paged progress, pause, and resume
- Lanzou directory and folder browsing with cache and path navigation; directory pagination keeps the public state semantics
- Resource market and GitHub standard repository sources: a repository appears as one project and its inner sources are cached locally
- Local repository project screen: save a project, import or export plain text, and build a standard repository archive
- Settings split into horizontal categories: list and appearance, search and index, sources and market, downloads, account and permissions, about and updates
- Onboarding covers permissions, base link verification, GitHub sources, and the update channel, then plays one home transition
- Update channels keep separate stable and test manifests and compare versions with SemVer rules
- Download history with retry, system installer hand-off, and file manager integration
- Follows the system light and dark theme with portrait and landscape layouts

### Release

- Version: `1.8.8alpha`
- Package: `cc.nkbr.lanzouplus`
- Minimum Android: 7.0 (API 24)
- Official page and download: [lanzouplus.nkbr.cc](https://lanzouplus.nkbr.cc/)

The release APK validates release metadata, package size, and the installed application's signing identity before handing an update to Android's package installer.

### Build

JDK 17 and the Android SDK are required:

```powershell
.\gradlew.bat --no-daemon clean assembleRelease
```

The empty flavor is the only public build. `app/src/empty/assets/s`, `r`, `u`, and `c` must remain zero-byte files.

### Open-source references

- [npm/node-semver](https://github.com/npm/node-semver): the update check follows its SemVer segment comparison and prerelease ordering rules; no upstream code or runtime dependency is bundled.
- [zaxtyson/LanZouCloud-API](https://github.com/zaxtyson/LanZouCloud-API) (MIT): directory pagination follows the public state meanings recorded there and is implemented from this project's own live results; no upstream source file is bundled.
- [AppIntro](https://github.com/AppIntro/AppIntro) (Apache-2.0): onboarding follows its directional translation, opacity and interaction-restoration approach; Android Animator implements the stagger and home morph.
- [AstrBot](https://github.com/AstrBotDevs/AstrBot): the market follows its organization of GitHub repository entries with upstream descriptions and images.
- [GitHub CLI](https://github.com/cli/cli) (MIT): releases use its create, asset upload and download commands; alpha releases are prereleases and do not replace stable latest.

Upstream content and rights stay with the original authors.

### License

Licensed under the [MIT License](LICENSE). Commercial use, modification, and redistribution are permitted, provided that the original copyright notice and license notice are retained in all copies or substantial portions of the software.

Only import directories or rules you trust and are authorized to use. Do not post passwords, authenticated sessions, private directories, signing files, or exploitable security details in public issues or logs.