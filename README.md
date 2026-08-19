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

## 使用

1. 云端 `sshd` 保持 `AllowTcpForwarding yes`，Web 服务监听云主机本机即可。
2. 填写 SSH 连接信息。
3. 添加端口映射，例如本地 `8080` → 远端 `80`。
4. 连接成功后，在手机打开 `http://127.0.0.1:8080`，对应云主机上的 80 端口。

页面里如果写死了公网域名的 API 地址，请求会绕过隧道。需要相对路径，或再加一组映射。
