# 独立 TVBox 电影客户端

从 1.10.0 开始，APK 默认打开电影页，直接请求所导入的 CMS 和视频 CDN。浏览、搜索、详情、播放、收藏和播放进度均不需要本项目服务器登录或目录同步。

`TvBoxActivity → TvBoxMovieViewModel → TvBoxRepository → CMS / 视频 CDN`。

## 使用

APK 内置 `frontend/public/tvbox.json` 的五个站点配置：155api、森林、番号资源、辣椒、奶香。安装后直接请求对应站点的分类与影片。内置的是站点配置，影片目录按分类和分页获取，不把庞大的全站影片数据库塞进安装包。

在“片源”页输入配置 URL 或 JSON，也可从本地文件导入。支持普通 JSON、Base64、十六进制配置。可调整顺序、启停、移除站点，或恢复内置配置。配置验证失败时保留原配置。

当前支持标准 TVBox `sites[].type = 1` 的 JSON CMS 和 HTTP(S) 直连视频，包括 HLS、多播放线路和多剧集。暂不支持爬虫 JAR、JavaScript、网页嗅探、外部解析器和 AList。混合配置会导入兼容站点并显示被忽略的站点；没有兼容站点时给出错误并保留原配置。

电影页显示当前片源的分类加载进度，已完成的分类可立即浏览。全站影片总数与分类数量分别保存；上游没有返回数量时显示未知。切换片源会取消旧请求，并阻止旧响应更新当前界面。

“清除缓存”后重新请求片源，保留配置、收藏和播放进度。TVBox 数据保存在 `filesDir/tvbox-v1`，独立于旧服务器目录。影片和剧集使用含片源身份的稳定负数 ID，避免与后端 ID 冲突。

旧短视频、长视频、ASMR 等入口在“片源”页中保留，仍沿用旧服务。独立 TVBox 流程不创建旧 `MainViewModel`。

## 验证

本地检查：

```powershell
cd android
.\gradlew.bat :app:testDebugUnitTest :app:lintDebug :app:assembleDebug :app:assembleDebugAndroidTest :app:assembleRelease --no-daemon --max-workers=2 '-Pkotlin.compiler.execution.strategy=in-process'
```

设备播放测试要求 Python、FFmpeg 和专用模拟器；测试会修改测试安装的片源和收藏。先启动独立 CMS/HLS fixture：

```powershell
python android/device-tests/tvbox_cms.py
```

另一个终端执行（有多台设备时给 adb 添加 `-s <serial>`）：

```powershell
adb install -r android/app/build/outputs/apk/debug/app-debug.apk
adb install -r android/app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk
adb reverse tcp:18765 tcp:18765
adb shell svc wifi disable
adb shell svc data disable
adb shell am instrument -w -r -e class 'you.deepfuck.shortvideo.TvBoxDeviceTest#importBrowseDecodeSeekFavoriteAndClearCacheWithoutBackend' you.deepfuck.shortvideo.test/androidx.test.runner.AndroidJUnitRunner
adb shell svc wifi enable
adb shell svc data enable
adb reverse --remove tcp:18765
```

该用例检查实际渲染帧数和播放位置前进，并验证暂停、拖动、收藏、断点、清缓存后重新加载、无效配置保留原数据。不会使用本项目后端。低速模拟器若在测试启动前解压 DEX 超时，可先执行 `adb shell cmd package compile -m speed -f <package>`，对应用和测试包各执行一次。

公网源另作冒烟测试，检查每个内置站点的分类、详情和实际视频渲染。上游访问受当前设备网络影响：

```powershell
adb shell am instrument -w -r -e publicSites true -e class 'you.deepfuck.shortvideo.TvBoxDeviceTest#bundledSourcesBrowseAndRenderVideoDirectly' you.deepfuck.shortvideo.test/androidx.test.runner.AndroidJUnitRunner
```

2026-10-26 的模拟器公网冒烟测试中，五个站点均完成分类、详情与实际视频渲染，整轮用时约 128 秒。测试网络使用主机可用的 HTTP 代理；模拟器直连网络曾超时，代理配置仅属于测试环境，没有写入 APK。此结果验证每站的抽样影片，不表示上游所有影片或所有网络环境均可用。

| 片源 | 返回分类数 | 详情和视频画面 |
| --- | ---: | --- |
| 155api | 40 | 通过 |
| 森林 | 56 | 通过 |
| 番号资源 | 32 | 通过 |
| 辣椒 | 32 | 通过 |
| 奶香 | 33 | 通过 |
