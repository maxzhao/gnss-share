---
title: GitHub APK 发布作业手册
created: 2026-09-20
updated: 2026-09-20
type: pattern
tags: [android, release, github-actions, apk, signing]
sources:
  - ".github/workflows/release.yml"
  - "build.gradle"
  - "server-app/build.gradle"
  - "client-app/build.gradle"
  - ".github/release-notes/v2.10.1-maxzhao.1.md"
  - "https://github.com/maxzhao/gnss-share/releases/tag/v2.10.1-maxzhao.1"
confidence: high
aliases: [Android 发布流程, APK Release Playbook]
---

> **TLDR**: 发布时以新 `v*` tag 驱动测试签名 Release 构建，产出服务端 4 个 ABI 包加 universal 包、客户端 1 个 universal 包；必须先本地验证 6 个 APK，再推送 tag，并在 GitHub Actions 不可用时用同一批已验证产物通过 GitHub API 发布。

## 适用范围与所有权

- 适用于 `maxzhao/gnss-share` 的 GitHub APK 发布。
- `.github/workflows/release.yml`：发布触发、测试签名恢复、构建、发布日志和附件上传。
- `server-app/build.gradle`：服务端 ABI 拆分、APK 命名和签名选择。
- `client-app/build.gradle`：客户端 universal APK 命名和签名选择。
- `build.gradle`：`VERSION_NAME`、版本名后缀解析和 `versionCode`。
- `.github/release-notes/<tag>.md`：对应版本的人工发布日志。
- 不提交 APK、`local.properties`、keystore 或密码；构建目录均为生成物。

## 当前发布契约

### 版本与基线

- 上游基线 tag `v2.10.1` 必须保留，不得移动或复用。
- Fork 发布使用独立 tag，例如 `v2.10.1-maxzhao.1`；tag 必须指向包含发布配置和发布日志的提交。
- `build.gradle` 通过去除 `-...` 后缀计算基础 `versionCode`。新增其他版本格式前，先验证解析结果。

### 签名

- 当前发布明确使用 **Android 测试签名**：`./gradlew assembleRelease -PtestSigning=true`。
- GitHub 仓库 secret `TEST_KEYSTORE_BASE64` 保存稳定测试 keystore 的 Base64；workflow 将其恢复为 `$HOME/.android/debug.keystore`。不得读取、打印或写入仓库。
- 必须复用同一测试 keystore；每次临时生成新 key 会导致测试版本之间无法覆盖升级。
- 测试签名不适合正式生产。与已有 APK 签名不同时必须先卸载，且以后不能直接升级为正式签名版本。

### 预期产物

一次发布必须恰好生成 6 个 APK：

| 应用 | 产物 |
| --- | --- |
| 客户端 | `gnss-client-<tag>-universal.apk` |
| 服务端 | `gnss-server-<tag>-arm64-v8a.apk` |
| 服务端 | `gnss-server-<tag>-armeabi-v7a.apk` |
| 服务端 | `gnss-server-<tag>-x86.apk` |
| 服务端 | `gnss-server-<tag>-x86_64.apk` |
| 服务端 | `gnss-server-<tag>-universal.apk` |

客户端没有原生 ABI 拆分需求；服务端包含原生惯性滤波器，因此提供 4 个单 ABI 包和 1 个 universal 包。

## 发布步骤

1. **预检**
   - 确认工作区干净、递归子模块完整、目标 tag 本地和远程均不存在。
   - 确认目标提交从上游基线 tag 演进，且不会移动上游基线 tag。
   - 确认 GitHub secret `TEST_KEYSTORE_BASE64` 已配置；只检查存在性，不读取内容。
2. **准备发布日志**
   - 创建 `.github/release-notes/<tag>.md`。
   - 记录用户可见增强、协议兼容性、6 个下载项及测试签名限制。
   - 从实际提交、规格和代码提炼，不把提交标题原样堆叠为日志。
3. **本地构建**

   ```bash
   ANDROID_HOME=<android-sdk> \
   ANDROID_SDK_ROOT=<android-sdk> \
   VERSION_NAME=<tag> \
   ./gradlew assembleRelease -PtestSigning=true
   ```

   不提交 `local.properties`；SDK 缺失时通过环境变量指向已安装 SDK。发布构建需要 SDK 36、NDK `28.2.13676358` 和 CMake `3.22.1`。
4. **验证产物**
   - APK 数量必须为 6，文件名必须符合上表。
   - 对每个 APK 执行 `apksigner verify` 和 `unzip -tqq`。
   - 用 `aapt dump badging` 确认包名、`versionName=<tag>` 和预期 `versionCode`。
   - 检查服务端单 ABI APK 只含目标 `lib/<abi>/`，universal APK 同时包含四个 ABI。
   - 计算 SHA-256；上传后核对 GitHub asset digest。
5. **提交与推送**
   - 提交发布配置和日志，先推送唯一工作分支。
   - 创建 annotated tag：`git tag -a <tag> -m "Release <tag>"`。
   - 推送明确 ref：`git push origin refs/tags/<tag>`。
6. **发布与远程复核**
   - 正常路径：tag push 触发 `.github/workflows/release.yml`，等待 `Build and Release` 成功。
   - 检查 Release 非 draft、非 prerelease，发布日志完整，附件恰好为 6 个且状态均为 `uploaded`。
   - 确认远程 tag、工作分支和发布提交一致。

## 已验证的失败处理

| 症状 | 原因 | 处理 |
| --- | --- | --- |
| `For input string: "1-maxzhao"` | 旧版本解析只支持简单 `-word` 后缀 | 保持 `build.gradle` 使用 `replaceFirst('-.*$', '')`；新格式先运行 Release 构建验证 |
| `SDK location not found` | 未设置 Android SDK 路径且无本地配置 | 设置 `ANDROID_HOME` 和 `ANDROID_SDK_ROOT`；不要提交 `local.properties` |
| Git push 报 `src refspec v2.10.1 matches more than one` | 同名 branch 和 tag 造成歧义 | 推送完整 ref，如 `refs/heads/v2.10.1:refs/heads/v2.10.1` |
| Workflow job 没有 steps/log | GitHub 在 runner 启动前拒绝任务 | 查询 check-run annotations，而不是重复下载空日志 |
| `The job was not started because your account is locked due to a billing issue.` | GitHub Actions 账户计费锁定 | 不重复触发；若本地 6 个 APK 已完成全部验证，可创建 GitHub Release 并通过 Releases API 上传原产物，随后核对数量、状态和 digest |
| Release 上传文件缺失或命名冲突 | ABI 输出名或 workflow 文件 glob 不一致 | 对照“预期产物”；保持客户端精确路径、服务端 `-*.apk` glob，并启用 `fail_on_unmatched_files` |

### GitHub API 降级路径

仅在 Actions 无法启动且本地验证已经全部通过时使用：

1. 使用现有 GitHub 凭据创建指向已推送 tag 的 Release；不得把 token 写进命令历史、日志或仓库。
2. 将本次本地验证过的 6 个 APK 原样上传到该 Release，不得重新构建后混用产物。
3. 调用 `GET /repos/maxzhao/gnss-share/releases/tags/<tag>` 复核：
   - `draft=false`、`prerelease=false`；
   - 发布日志包含“主要增强”和签名说明；
   - `assets | length == 6` 且全部 `state=uploaded`；
   - 每个远程 `digest` 与本地 SHA-256 一致。

## `v2.10.1-maxzhao.1` 发布证据

- 发布提交：`936188387b3155e7d35760a759c1b3071e5fc96b`。
- Release：<https://github.com/maxzhao/gnss-share/releases/tag/v2.10.1-maxzhao.1>。
- 本地 Release 构建成功；6 个 APK 均通过签名、ZIP 完整性、包版本和 ABI 检查。
- GitHub Actions run `35485332266` 因账户计费锁定未启动；随后按 API 降级路径发布。
- GitHub Release 返回 6 个 `uploaded` assets，且远程 SHA-256 digest 与本地产物一致。

## 待解问题

- [TODO: GitHub Actions 计费锁定解除后，用下一次测试 tag 验证完整自动发布路径。]
- [TODO: 若要转为正式签名，先确定密钥保管、secret 迁移和无法从测试签名原地升级的发布策略。]

## 反论与数据空白

- 本次证据验证了本地构建和 GitHub API 发布，但未验证 GitHub-hosted runner 上的完整构建，因为任务在 runner 启动前被计费锁定阻止。
- `versionCode` 当前忽略 `-maxzhao.N` 修订号；同一基础版本的后续修订是否需要单调递增策略尚未确定。发布前必须显式检查目标安装/升级方式。
