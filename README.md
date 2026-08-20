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

## 应用内更新（自己发版）

手机上的应用会去 GitHub Releases 找有没有新安装包。  
你要做的不是再手动拷 APK，而是：**在电脑上用脚本打一次包、传到 GitHub，手机点「检查更新」就能装。**

脚本在仓库根目录的 `scripts/` 里（先 `git pull` 到含这些文件的分支）：

| 文件 | 干什么 | 什么时候用 |
| --- | --- | --- |
| `scripts/make-release-keystore.sh` | 生成一把「发布签名钥匙」 | **一辈子只用一次** |
| `scripts/publish-github-release.sh` | 打包 APK 并上传到 GitHub Release | **每次发新版本用一次** |

签名钥匙相当于印章：手机上现在装的包，和以后下载的更新，必须是同一把钥匙盖的章，系统才允许覆盖安装。所以第一次请用脚本打出来的包安装，不要继续用平时的 debug 包。

### 第一次（只需做一遍）

在仓库根目录打开终端：

```bash
chmod +x scripts/*.sh
./scripts/make-release-keystore.sh
```

会生成两个**不要上传到 Git** 的本地文件：

- `release.jks`：钥匙本身
- `keystore.properties`：钥匙密码配置（从模板拷出来的）

用编辑器打开 `keystore.properties`，把 `storePassword` 和 `keyPassword` 填成你刚才给 `keytool` 设的密码（可以两个相同）：

```
storeFile=release.jks
storePassword=你的密码
keyAlias=alite
keyPassword=你的密码
```

电脑需已登录 GitHub CLI（`gh auth login`），然后发第一版：

```bash
./scripts/publish-github-release.sh
```

脚本会编译、把 APK 传到  
https://github.com/PhilCoulson/a-lite-ssh/releases  
到该页面把 `a-lite-ssh-1.2.0.apk` 下到手机，**先卸载旧的 debug 版**，再安装这个包。

### 以后每发一个新版本

1. 改 `app/build.gradle.kts` 开头两行，两个数字都要变大，例如 `4` / `1.2.0` → `5` / `1.2.1`。
2. 提交代码后，再运行：

```bash
./scripts/publish-github-release.sh
```

3. 手机打开应用 → 右上角菜单 → **检查更新** → 下载安装。  
   应用大约每天也会自动查一次。

不要把 `release.jks`、`keystore.properties` 提交到 GitHub，也不要弄丢；弄丢就无法给旧用户推送覆盖更新，只能让他们重新安装。


## 使用

1. 云端 `sshd` 保持 `AllowTcpForwarding yes`，Web 服务监听云主机本机即可。
2. 填写 SSH 连接信息。
3. 添加端口映射，例如本地 `8080` → 远端 `80`。
4. 连接成功后，在手机打开 `http://127.0.0.1:8080`，对应云主机上的 80 端口。

页面里如果写死了公网域名的 API 地址，请求会绕过隧道。需要相对路径，或再加一组映射。
