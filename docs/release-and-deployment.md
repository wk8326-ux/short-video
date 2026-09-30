# 发布与更新流程

本文是本项目以后发布 APK 和服务端的固定流程。发布链路固定为：

```text
本地构建与校验 -> GitHub 分支 -> GitHub Release APK -> oracle2 从 GitHub 拉取 -> 线上验收
```

GitHub 是唯一发布源。oracle2 不再通过本机 SFTP 接收源码或 APK，也不应直接从本机工作目录复制发布文件。

## 版本信息

每次发布必须同步修改：

- `android/app/build.gradle.kts`
  - `versionCode`：只能递增
  - `versionName`：例如 `1.6.0-beta.40`
- `app/main.py`
  - `APP_VERSION`：必须与 APK 的 `versionName` 相同

APK 必须使用 `android/signing.properties` 指向的稳定签名，否则 Android 无法覆盖安装旧版本。

版本号只允许递增。`versionCode` 是 Android 判断新旧版本的唯一数字依据；`versionName` 只用于展示。一次发布只使用一个 GitHub Release 和一个 APK 资产，资产名统一为：

```text
deepfuck-android-v<versionName>.apk
```

## 本地构建与校验

在项目根目录执行：

```powershell
cd D:\WK_workfiles\bot_workspace\projects\short-video
.\android\gradlew.bat -p android lintDebug testDebugUnitTest assembleRelease --no-daemon
```

构建产物：

```text
android/app/build/outputs/apk/release/app-release.apk
```

记录 APK 的大小和 SHA-256：

```powershell
$apk = "android/app/build/outputs/apk/release/app-release.apk"
(Get-Item $apk).Length
(Get-FileHash $apk -Algorithm SHA256).Hash
```

同时验证 PWA：

```powershell
cd frontend
npm run build
```

## 推送 GitHub

先确认工作树、远程和版本：

```powershell
git status
git remote -v
git log -1 --oneline
```

提交并推送当前发布分支：

```powershell
git add app android frontend docs
git commit -m "release: v<versionName>"
git push origin feature/movie-library
```

如果 GitHub 连接超时，先确认 v2rayN 的本地 HTTP 代理端口。当前机器常用端口是 `127.0.0.1:10808`，不要把端口写死到项目配置中：

```powershell
$env:HTTPS_PROXY = "http://127.0.0.1:10808"
$env:HTTP_PROXY = "http://127.0.0.1:10808"
git push origin feature/movie-library
```

推送成功后再创建 GitHub Release。Release 的 tag 必须与版本名对应，例如：

```powershell
$versionName = "1.6.0-beta.X"
$releaseApk = "deepfuck-android-v$versionName.apk"
Copy-Item `
  android/app/build/outputs/apk/release/app-release.apk $releaseApk -Force
gh release create "v$versionName" `
  $releaseApk `
  --repo wk8326-ux/short-video `
  --prerelease `
  --title "v$versionName" `
  --notes "填写本次变更。"
```

PowerShell 中不能把构建产物直接作为 Release 资产，否则 GitHub 通常会把它命名为 `app-release.apk`。必须先复制成规范名称，再上传。已有 Release 修订 APK 时使用：

```text
gh release upload v1.6.0-beta.X deepfuck-android-v1.6.0-beta.X.apk --repo wk8326-ux/short-video --clobber
```

上传后检查 Release 只保留一个 APK 资产：

```powershell
gh release view v1.6.0-beta.X `
  --repo wk8326-ux/short-video `
  --json tagName,isPrerelease,assets,url
```

如果同一个 Release 中出现 `app-release.apk` 等旧资产，应立即删除，避免人工下载或服务器同步时选错：

```powershell
gh release delete-asset v1.6.0-beta.X app-release.apk `
  --repo wk8326-ux/short-video --yes
```

## oracle2 部署

服务器目录：

```text
/home/ubuntu/short-video
/home/ubuntu/short-video/data
/home/ubuntu/short-video/data/app-update
```

`data` 是运行数据，部署时必须保留，不能删除或覆盖。

### 1. 从 GitHub 同步源码

服务器不是 Git 工作树时，从 GitHub 下载对应分支归档到临时目录，校验后只替换源码目录。不要删除或覆盖 `data` 和 `.env`：

```bash
set -eu
release_dir=/tmp/short-video-release
rm -rf "$release_dir"
mkdir -p "$release_dir"
curl -fL --retry 3 \
  https://github.com/wk8326-ux/short-video/archive/refs/heads/feature/movie-library.tar.gz \
  -o "$release_dir/source.tar.gz"
tar -xzf "$release_dir/source.tar.gz" -C "$release_dir"
new_root="$(find "$release_dir" -mindepth 1 -maxdepth 1 -type d | head -n 1)"
rsync -a --delete \
  --exclude data \
  --exclude .env \
  "$new_root/" /home/ubuntu/short-video/
```

如果 oracle2 访问 GitHub 不通，使用服务器上已配置的 v2rayN/代理端口：

```bash
# 先查看实际监听端口，再替换下面的端口；不要默认假定端口一定是 10808。
ss -lntp | grep -E ':(1080|10808|7890|7897)\b' || true
export HTTPS_PROXY=http://127.0.0.1:<代理端口>
export HTTP_PROXY=http://127.0.0.1:<代理端口>
```

实际端口以 oracle2 上的代理配置为准，先用下面命令验证：

```bash
curl -I --max-time 15 https://github.com/
```

### 2. 从 GitHub Release 同步 APK

推荐从 GitHub Release 下载，而不是从本机上传。下载地址只使用规范资产名：

```bash
mkdir -p /home/ubuntu/short-video/data/app-update
curl -fL --retry 3 \
  https://github.com/wk8326-ux/short-video/releases/download/v<versionName>/deepfuck-android-v<versionName>.apk \
  -o /home/ubuntu/short-video/data/app-update/deepfuck-android-v<versionName>.apk
```

写入同目录的 `manifest.json`，其中 `size` 和 `sha256` 必须与 APK 完全一致。`versionCode` 必须使用本次 APK 的实际值：

```json
{
  "versionCode": 196,
  "versionName": "<versionName>",
  "apkFile": "deepfuck-android-v<versionName>.apk",
  "sha256": "<APK 的 64 位 SHA-256>",
  "size": <APK 字节数>,
  "notes": "填写本次更新内容。"
}
```

写入时应先写临时文件，再原子改名，避免服务读到半个 manifest：

```bash
mv /home/ubuntu/short-video/data/app-update/manifest.json.tmp \
   /home/ubuntu/short-video/data/app-update/manifest.json
```

重建服务：

```bash
cd /home/ubuntu/short-video
sudo docker compose up -d --build
```

## 上线验收

### 1. 使用认证会话检查接口

`/api/health`、`/api/app/update` 和 APK 下载接口都需要登录。不要把密码写进脚本或提交到 Git；验收时临时输入或从受保护的环境变量读取：

```bash
base=https://short.deepfuck.you
tmp="$(mktemp -d)"
trap 'rm -rf "$tmp"' EXIT
read -rsp 'App password: ' APP_PASSWORD
printf '\n'
curl -fsS -c "$tmp/cookies" \
  -H 'Content-Type: application/json' \
  --data "{\"password\":\"$APP_PASSWORD\"}" \
  "$base/api/auth/login"
unset APP_PASSWORD

curl -fsS -b "$tmp/cookies" "$base/api/health"
curl -fsS -b "$tmp/cookies" "$base/api/app/update"
```

### 2. 检查 APK 完整下载和断点下载

```bash
curl -fsSI -b "$tmp/cookies" \
  "$base/api/app/update/apk?versionCode=<versionCode>"

curl -fsS -D - -o /dev/null \
  -H 'Range: bytes=0-1023' \
  -b "$tmp/cookies" \
  "$base/api/app/update/apk?versionCode=<versionCode>"

curl -sS -o /tmp/stale-update-body -w 'HTTP %{http_code}\n' \
  -b "$tmp/cookies" \
  "$base/api/app/update/apk?versionCode=<旧版versionCode>"
cat /tmp/stale-update-body
rm -f /tmp/stale-update-body
```

必须确认：

- `/api/health` 的 `version` 是本次 `APP_VERSION`
- `/api/app/update` 返回正确的 `versionCode`、`versionName`、大小和 SHA-256
- APK `HEAD` 返回 `200`、正确的 `Content-Length`、`X-APK-SHA256` 和规范文件名
- APK `Range` 请求返回 `206`、`Content-Range` 和正确的分段长度
- 传入旧版 `versionCode` 返回 `409`，客户端随后重新获取 manifest
- 用旧 APK 打开“检查更新”，能够发现并下载新版本
- 管理页面可以填写排除目录，例如根目录 `/asmr/中文音声`，排除目录 `/asmr/中文音声/小元`，保存后重新扫描
- ASMR 作者搜索会请求 `/api/asmr/authors?q=小苮儿`，并且不会遍历全部媒体文件

## 当前发布记录

当前已构建 APK：

```text
versionName: 1.6.0-beta.39
versionCode: 196
size: 4325720
sha256: AAC8E1C898046BC4D93EF430E7F3867881727063AE157B8750E5BB0EEC5664F7
GitHub tag: v1.6.0-beta.39
GitHub asset: deepfuck-android-v1.6.0-beta.39.apk
GitHub asset URL: https://github.com/wk8326-ux/short-video/releases/download/v1.6.0-beta.39/deepfuck-android-v1.6.0-beta.39.apk
oracle2 APK path: /home/ubuntu/short-video/data/app-update/deepfuck-android-v1.6.0-beta.39.apk
```

beta.39 已完成线上验收：服务端版本为 `1.6.0-beta.39`，APK `HEAD` 返回 `200`，`Range: bytes=0-1023` 返回 `206`，旧版本号返回 `409`。GitHub Release 已清理旧的 `app-release.apk`，只保留规范资产。
