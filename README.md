# a-lite-ssh

Android 原生客户端：用 NDK 编译 [libssh2](https://libssh2.org/)，连上云端 OpenSSH 后做 **本地端口转发**，在手机上访问云上的 Web 服务。

```
手机 WebView
  → 127.0.0.1:<本地端口>
  → libssh2 direct-tcpip 隧道
  → 云端 sshd
  → 远端 127.0.0.1:<Web 端口>
```

等价于桌面：

```bash
ssh -N -L 8080:127.0.0.1:80 user@your-server
```

## 功能

- 密码或 PEM 私钥登录
- 只绑定 `127.0.0.1` 的本地转发
- 前台服务保活隧道
- 首次信任（TOFU）主机密钥
- 应用内 WebView 打开转发后的页面

## 编译

需要 JDK 17+、Android SDK Platform 35、NDK 27.2、CMake 3.22.1。首次 CMake 会下载 libssh2 1.11.1 和 mbedTLS 2.28.9。

```bash
export ANDROID_HOME=/path/to/android-sdk
./gradlew :app:assembleDebug
```

APK 输出：`app/build/outputs/apk/debug/app-debug.apk`。

## 使用

1. 云端 `sshd` 保持 `AllowTcpForwarding yes`（默认一般已开启），Web 服务监听本机即可，不必对公网开放 80。
2. 安装 APK，填写主机、用户和认证信息。
3. 本地端口默认 `8080`，远端默认 `127.0.0.1:80`。
4. 点「连接并转发」，成功后点「打开本地网页」。

页面里如果写死了公网域名的 API 地址，请求会绕过隧道。需要相对路径，或把 API 也转到本地端口。
