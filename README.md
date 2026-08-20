# a-lite-ssh

Android 原生客户端：用 NDK 编译 [libssh2](https://libssh2.org/)，连上云端 OpenSSH 后做 **本地端口转发**，在手机上访问云上的 Web 服务。

```
手机浏览器 / WebView
  → 127.0.0.1:<本地端口>          （手机本机）
  → libssh2 隧道
  → 云端 sshd
  → 127.0.0.1:<远端端口>          （云主机本机）
```

界面上展示为：`127.0.0.1:本地端口 → SSH主机:远端端口`。

等价于桌面：

```bash
ssh -N -L 8080:127.0.0.1:80 user@your-server
```

## 功能

- SSH 主机、端口、用户和认证信息归为一组
- 多组本地端口 → 远端端口映射，可临时关闭或删除
- 密码或 PEM 私钥登录
- 只绑定手机 `127.0.0.1` 的本地转发
- 前台服务保活隧道
- 首次信任（TOFU）主机密钥

## 编译

需要 JDK 17+、Android SDK Platform 35、NDK 27.2、CMake 3.22.1。首次 CMake 会下载 libssh2 1.11.1 和 mbedTLS 2.28.9。

```bash
export ANDROID_HOME=/path/to/android-sdk
./gradlew :app:assembleDebug
```

APK 输出：`app/build/outputs/apk/debug/app-debug.apk`。

## 应用内更新

应用从 [GitHub Releases](https://github.com/PhilCoulson/a-lite-ssh/releases) 检查新版本，确认后下载安装包并调起系统安装。默认每天最多自动检查一次，也可在菜单里手动「检查更新」。

更新能装上的前提是：**手机上现有应用和 Release 里的 APK 用同一把签名密钥**。本地 debug 包和 Release 包密钥不同，会提示签名不一致。做法是：第一次先装 GitHub Release 的包，之后即可在应用里更新。

1. 生成本地发布密钥（只做一次，密钥不要提交到 Git）：

```bash
./scripts/make-release-keystore.sh
```

按提示填写 `keystore.properties`。若要用 GitHub Actions 发版，把脚本打印的值加到仓库 Secrets：`RELEASE_KEYSTORE_BASE64`、`RELEASE_STORE_PASSWORD`、`RELEASE_KEY_ALIAS`、`RELEASE_KEY_PASSWORD`。

2. 升 `app/build.gradle.kts` 里的 `appVersionCode` / `appVersionName` 后，任选一种发版方式：

```bash
# 本机打包并创建 GitHub Release
./scripts/publish-github-release.sh
```

或打 tag 交给 CI：`git tag v1.2.0 && git push origin v1.2.0`（tag 必须等于 `v` + versionName）。两种方式不要同时用，以免重复创建 Release。

## 使用

1. 云端 `sshd` 保持 `AllowTcpForwarding yes`，Web 服务监听云主机本机即可。
2. 填写 SSH 连接信息。
3. 添加端口映射，例如本地 `8080` → 远端 `80`。
4. 连接成功后，在手机打开 `http://127.0.0.1:8080`，对应云主机上的 80 端口。

页面里如果写死了公网域名的 API 地址，请求会绕过隧道。需要相对路径，或再加一组映射。
