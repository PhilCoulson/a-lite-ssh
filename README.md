# A-Lite SSH

Android 上的 **SSH 本地端口转发** 客户端。用 NDK 编译 [libssh2](https://libssh2.org/)，连上云端 OpenSSH 后，把云主机上的 Web 服务转到手机本机 `127.0.0.1`，用系统浏览器访问。

等价于桌面命令：

```bash
ssh -N -L 8080:127.0.0.1:80 user@your-server
```

本项目不做 SSH 终端、SFTP、VPN 或动态 SOCKS，只做这件事。

## 适用场景

适合「服务已经在云主机或内网跑着，但不想把端口暴露到公网」的情况，例如：

- 用手机打开云主机本机的管理后台、面板、文档站（Nginx / Caddy 监听 `127.0.0.1:80` 等）
- 访问实验室、NAS、家庭服务器上未对公网开放的 Web 界面
- 临时把远端某个端口转到手机，用浏览器调试

典型路径：

```
手机系统浏览器
  → 127.0.0.1:<本地端口>     （只绑在手机本机，局域网其他设备进不来）
  → libssh2 隧道
  → 云端 sshd
  → <远端地址>:<远端端口>    （默认是服务器自己的 127.0.0.1）
```

界面上显示为：`127.0.0.1:本地端口  →  远端地址:远端端口`。

不适合：需要整机上网、需要命令行、页面里写死了公网 API 域名（请求会绕过隧道）。这类页面要用相对路径，或再加一组映射。

## 功能

- 一组 SSH 连接信息：主机、端口、用户名；密码或 PEM 私钥
- 多组端口映射：可命名、启用/关闭、编辑、删除；长按可置顶或调整顺序
- 远端地址默认为服务器本机 `127.0.0.1`，需要转到内网其他机器时可改（不要填云主机公网 IP）
- 连接后点「打开页面」走系统浏览器；点按映射地址可复制 `http://127.0.0.1:端口/`
- 前台服务保持隧道；运行中改映射会立刻应用到转发
- 主机密钥首次信任（TOFU）；可选忽略密钥变更（有中间人风险，仅在确认换过密钥时用）
- 密码默认不记住；可勾选记住，加密存在本机，最长 7 天
- 标题栏显示版本；可从 GitHub Releases 检查并安装更新（仓库需为 Public，且与发布签名一致）

## 使用

1. 云端 `sshd` 打开 `AllowTcpForwarding yes`。Web 服务监听服务器本机即可，不必对公网开放。
2. 在应用里填写 SSH 主机、端口、用户和认证方式。
3. 添加端口映射，例如本地 `8080` → 远端 `127.0.0.1:80`。
4. 连接成功后，用浏览器打开 `http://127.0.0.1:8080`，对应云主机上的 80 端口。

## 构建

需要 JDK 17+、Android SDK Platform 35、NDK 27.2、CMake 3.22.1。首次编译会下载 libssh2 1.11.1 和 mbedTLS 2.28.9。

```bash
export ANDROID_HOME=/path/to/android-sdk   # 本机 SDK 路径，Android Studio 常见为 $HOME/Android/Sdk
./gradlew :app:assembleDebug
```

调试包输出：`app/build/outputs/apk/debug/app-debug.apk`。

正式包（需本地签名配置，见下文发版）：

```bash
./gradlew :app:assembleRelease
```

输出：`app/build/outputs/apk/release/app-release.apk`。

当前版本号在 `app/build.gradle.kts` 开头的 `appVersionCode`、`appVersionName`。发新版时两个都要变大。

## 发版与应用内更新

手机通过 GitHub Releases 检查新安装包。仓库必须是 **Public**，否则应用匿名访问接口会 404，看起来像「没有发布包」。

签名钥匙相当于印章：手机上现有应用和 Release 里的 APK 必须同一把钥匙，才能覆盖安装。不要用日常 debug 包和 Release 包混着升级。

脚本在仓库根目录 `scripts/`：

| 文件 | 作用 | 何时用 |
| --- | --- | --- |
| `scripts/make-release-keystore.sh` | 生成发布签名钥匙 | 一辈子一次 |
| `scripts/publish-github-release.sh` | 打包并上传到 GitHub Release | 每次发新版本 |

第一次：

```bash
chmod +x scripts/*.sh
./scripts/make-release-keystore.sh
```

会生成不要提交到 Git 的 `release.jks` 和 `keystore.properties`。把其中的 `storePassword`、`keyPassword` 填成你给 keytool 设的密码。然后：

```bash
gh auth login
./scripts/publish-github-release.sh
```

脚本会探测 Android SDK 并写入（已 gitignore 的）`local.properties`。完成后到  
https://github.com/PhilCoulson/a-lite-ssh/releases  
下载 APK，先卸载旧的 debug 包再安装。之后手机菜单「检查更新」即可。

以后发版：改 `appVersionCode` / `appVersionName`，再跑 `./scripts/publish-github-release.sh`。不要把 `release.jks`、`keystore.properties` 提交或丢失。
