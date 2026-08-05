# LanzouPlus

LanzouPlus 是一个轻量原生 Android 蓝奏目录浏览、全源搜索与下载客户端。

本仓库只发布 **空源、无主页推荐** 的 LanzouPlus 公开版源码：安装后由用户自行导入其有权访问且信任的规则。内置目录、推荐、私有更新配置与私有构建产物，以及签名材料和账号信息都不在本仓库中。

> 产品边界：本仓库、GitHub Release 与公开官网只包含 LanzouPlus。

## 正式版本

- 当前版本：`1.1.0`
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

## GPT-5.6 Sol 开发与发布声明

LanzouPlus 的程序代码、工程配置、测试、CI、项目文档、构建、版本整理及公开发布均由 **GPT-5.6 Sol** 完成，包括提交代码、生成发布产物、维护版本信息和执行发布流程。

`nekobyran` 负责提出需求、确定产品方向，并对各版本进行实际功能验证、UI 矫正、问题反馈与验收。验证结果和界面反馈会继续交由 GPT-5.6 Sol 修正并形成后续版本。

本声明用于透明披露项目采用的 AI 开发与发布方式。仓库所有权、许可合规、安全边界及项目责任仍由 `nekobyran` 承担；该声明不表示 OpenAI 对本项目的官方认可或背书。

## 发展方向与未来潜力

LanzouPlus 的长期方向是成为一个隐私优先、由用户掌控数据来源的 Android 目录浏览与多源检索基础项目。后续维护将重点推进：

- 建立更清晰、可扩展的来源适配层，降低新增合规来源的维护成本
- 提升大规模并发检索、失败恢复、缓存与弱网络环境下的稳定性
- 完善自动化测试、静态检查、可复现构建、发布校验与供应链安全
- 改进无障碍、平板适配、国际化与面向贡献者的开发文档
- 探索在本地优先和最小数据暴露原则下，为其他开源 Android 工具复用核心组件

这些内容是公开路线图与未来目标，不代表尚未完成的功能。项目将继续以可审计、安全、合规和长期可维护为优先原则。

## 开源许可证

本项目采用 [MIT License](LICENSE)。允许个人与商业使用、修改及再分发；复制或分发本软件及其重要部分时，必须保留原版权声明与许可声明。

作者：nekobyran · Bilibili UID：607234739

## 赞助(subscription)
![图片](https://github.com/nekobyran/lanzouplus/blob/e61e81b8bea3030a4c7aa9c544e802a9d907c2ba/Screenshot_2026-07-17-22-38-28-48_3915bacb930634b7e206116f9dc9486f.jpg)

---

## English

LanzouPlus is a lightweight native Android client for browsing Lanzou directories, searching imported sources, and downloading files.

This public repository contains only the **empty-source edition with no home recommendations**. Users import only rules and directories they are authorized to access and trust. Built-in catalogs, recommendations, private update configuration and private artifacts, signing material, and account data are not published here.

> Product boundary: this repository, its GitHub releases, and the public website contain LanzouPlus only.

### Release

- Version: `1.1.0`
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

### GPT-5.6 Sol development and release disclosure

LanzouPlus's application code, project configuration, tests, CI, documentation, builds, version preparation, and public releases are completed by **GPT-5.6 Sol**. This includes committing code, generating release artifacts, maintaining version metadata, and carrying out the release workflow.

`nekobyran` defines requirements and product direction, then performs real-world functional verification, UI correction, issue reporting, and acceptance for each version. Verification results and interface feedback are returned to GPT-5.6 Sol for correction and subsequent releases.

This disclosure documents the project's AI-based development and release workflow. Repository ownership, licensing compliance, safety boundaries, and project responsibility remain with `nekobyran`. It does not imply endorsement by OpenAI.

### Roadmap and future potential

LanzouPlus aims to become a privacy-first Android foundation for user-controlled directory browsing and multi-source search. Planned work focuses on:

- a clearer and extensible source-adapter architecture
- resilient concurrent search, recovery, caching, and weak-network behavior
- stronger automated testing, reproducible builds, release verification, and supply-chain security
- accessibility, tablet support, internationalization, and contributor documentation
- reusable local-first components for other open-source Android tools, with minimal data exposure

These are roadmap goals rather than claims about unfinished functionality. The project will continue to prioritize auditability, safety, compliance, and long-term maintainability.

### License

Licensed under the [MIT License](LICENSE). Commercial use, modification, and redistribution are permitted, provided that the original copyright notice and license notice are retained in all copies or substantial portions of the software.

Only import directories or rules you trust and are authorized to use. Do not post passwords, authenticated sessions, private directories, signing files, or exploitable security details in public issues or logs.