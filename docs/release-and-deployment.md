# 发布与更新流程

本文是本项目以后发布 APK 和服务端的固定流程。GitHub 是唯一发布源，oracle2 不再通过本机 SFTP 接收源码或 APK。

## 版本信息

每次发布必须同步修改：

- `android/app/build.gradle.kts`
  - `versionCode`：只能递增
  - `versionName`：例如 `1.6.0-beta.40`
- `app/main.py`
  - `APP_VERSION`：必须与 APK 的 `versionName` 相同

APK 必须使用 `android/signing.properties` 指向的稳定签名，否则 Android 无法覆盖安装旧版本。

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
git commit -m "release: v1.6.0-beta.X"
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
gh release create v1.6.0-beta.X `
  android/app/build/outputs/apk/release/app-release.apk `
  --repo wk8326-ux/short-video `
  --prerelease `
  --title "v1.6.0-beta.X" `
  --notes "填写本次变更。"
```

资产文件建议改名为：

```text
deepfuck-android-v1.6.0-beta.X.apk
```

## oracle2 部署

服务器目录：

```text
/home/ubuntu/short-video
/home/ubuntu/short-video/data
/home/ubuntu/short-video/data/app-update
```

`data` 是运行数据，部署时必须保留，不能删除或覆盖。

服务器不是 Git 工作树时，从 GitHub 下载对应分支归档到临时目录，校验后只替换源码目录：

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
export HTTPS_PROXY=http://127.0.0.1:10808
export HTTP_PROXY=http://127.0.0.1:10808
```

实际端口以 oracle2 上的代理配置为准，先用下面命令验证，不要盲目替换端口：

```bash
curl -I --max-time 15 https://github.com/
```

同步 APK 到运行数据目录。推荐从 GitHub Release 下载，而不是从本机上传：

```bash
mkdir -p /home/ubuntu/short-video/data/app-update
curl -fL --retry 3 \
  https://github.com/wk8326-ux/short-video/releases/download/v1.6.0-beta.X/deepfuck-android-v1.6.0-beta.X.apk \
  -o /home/ubuntu/short-video/data/app-update/deepfuck-android-v1.6.0-beta.X.apk
```

写入同目录的 `manifest.json`，其中 `size` 和 `sha256` 必须与 APK 完全一致：

```json
{
  "versionCode": 196,
  "versionName": "1.6.0-beta.39",
  "apkFile": "deepfuck-android-v1.6.0-beta.39.apk",
  "sha256": "填写 Get-FileHash 的 64 位结果",
  "size": 4325720,
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

在服务器上检查：

```bash
curl -fsS https://short.deepfuck.you/api/health
curl -fsS https://short.deepfuck.you/api/app/update
curl -fsSI "https://short.deepfuck.you/api/app/update/apk?versionCode=196"
```

必须确认：

- `/api/health` 的 `version` 是本次 `APP_VERSION`
- `/api/app/update` 返回正确的 `versionCode`、`versionName`、大小和 SHA-256
- APK HEAD 返回正确的 `Content-Length`
- 使用旧版 `versionCode` 检查更新时不会因为版本号相同或 manifest 半写入而失败
- 用旧 APK 打开“检查更新”，能够发现并下载新版本
- 管理页面可以填写排除目录，例如根目录 `/asmr/中文音声`，排除目录 `/asmr/中文音声/小元`，保存后重新扫描
- ASMR 作者搜索会请求 `/api/asmr/authors?q=小苮儿`，并且不会遍历全部媒体文件

## beta.39 记录

当前已构建 APK：

```text
versionName: 1.6.0-beta.39
versionCode: 196
size: 4325720
sha256: AAC8E1C898046BC4D93EF430E7F3867881727063AE157B8750E5BB0EEC5664F7
```
