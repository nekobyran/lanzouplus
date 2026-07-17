# LanzouPlus

LanzouPlus 是一个轻量原生 Android 蓝奏目录浏览、全源搜索与下载客户端。

本仓库只发布 **空源、无主页推荐** 的 LanzouPlus 公开版源码：安装后由用户自行导入其有权访问且信任的规则。内置目录、推荐、私有更新配置与私有构建产物，以及签名材料和账号信息都不在本仓库中。

> 产品边界：本仓库、GitHub Release 与公开官网只包含 LanzouPlus。

## 正式版本

- 当前版本：`1.0.0`
- 包名：`cc.nkbr.lanzouplus`
- 最低系统：Android 7.0（API 24）
- 正式下载：[lanzouplus.nkbr.cc](https://lanzouplus.nkbr.cc/)
- 官网：[lanzouplus.nkbr.cc](https://lanzouplus.nkbr.cc/)

正式 APK 会校验发布元数据、SHA-256 与已安装应用的签名证书后再交给系统安装器。

## 功能

- 导入、导出并合并本地或 HTTPS 源规则
- 并发搜索、分页进度、暂停与继续
- 蓝奏目录和文件夹浏览、缓存与路径导航
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

作者：nekobyran · Bilibili UID：607234739

© 2026 nekobyran。除非另有书面授权，保留全部权利。

---

## English

LanzouPlus is a lightweight native Android client for browsing Lanzou directories, searching imported sources, and downloading files.

This public repository contains only the **empty-source edition with no home recommendations**. Users import only rules and directories they are authorized to access and trust. Built-in catalogs, recommendations, private update configuration and private artifacts, signing material, and account data are not published here.

> Product boundary: this repository, its GitHub releases, and the public website contain LanzouPlus only.

### Release

- Version: `1.0.0`
- Package: `cc.nkbr.lanzouplus`
- Minimum Android: 7.0 (API 24)
- Official page and download: [lanzouplus.nkbr.cc](https://lanzouplus.nkbr.cc/)

The release APK validates release metadata, SHA-256, and the installed application's signing certificate before handing an update to Android's package installer.

### Build

JDK 17 and the Android SDK are required:

```powershell
.\gradlew.bat --no-daemon clean assembleRelease
```

The empty flavor is the only public build. `app/src/empty/assets/s`, `r`, `u`, and `c` must remain zero-byte files.

Only import directories or rules you trust and are authorized to use. Do not post passwords, authenticated sessions, private directories, signing files, or exploitable security details in public issues or logs.

##赞助(subscription)
[图片](https://github.com/nekobyran/lanzouplus/blob/e61e81b8bea3030a4c7aa9c544e802a9d907c2ba/Screenshot_2026-07-17-22-38-28-48_3915bacb930634b7e206116f9dc9486f.jpg)
