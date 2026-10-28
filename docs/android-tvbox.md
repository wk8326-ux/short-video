# 独立 TVBox 电影客户端

从 1.10.0 开始，APK 默认打开电影页，直接请求所导入的 CMS 和视频 CDN。浏览、搜索、详情、播放、收藏和播放进度均不需要本项目服务器登录或目录同步。

`TvBoxActivity → TvBoxMovieViewModel → TvBoxRepository → CMS / 视频 CDN`。

## 使用

APK 内置 `frontend/public/tvbox.json` 的五个站点配置：155api、森林、番号资源、辣椒、奶香。安装后直接请求对应站点的分类与影片。内置的是站点配置，影片目录按分类和分页获取，不把庞大的全站影片数据库塞进安装包。

在“片源”页输入配置 URL 或 JSON，也可从本地文件导入。支持普通 JSON、Base64、十六进制配置。导入会增量添加片源；重复导入不会重复添加，也不会覆盖已有片源、排序、启停状态或当前选择。不同订阅可以复用同一个 key；相同接口的别名会合并。可单独删除、排序和启停片源。刷新全部订阅时保留手动添加的片源和删除记录；主动重新导入可以恢复该订阅中删除的片源。“恢复内置配置”是单独的替换操作。配置验证失败时保留原配置。

支持标准 TVBox `sites[].type = 1` JSON CMS，以及 `type = 3` 的 CatVod dex JAR 和 JavaScript 爬虫。支持多线路、多剧集和带请求头的 HTTP(S) 视频，包括 HLS。type 3 的目录、搜索、详情和播放解析均在 APK 中执行，不转发到本项目后端。

JAR 站点使用 `csp_*` API 和根配置 `spider`，也可用站点级 `jar` / `spider` 覆盖；支持 `;md5;` 校验、Android 14 的只读动态代码加载和常规 `lib/<abi>/*.so`。插件必须匹配设备 ABI 和公开 CatVod Spider 接口；依赖其他壳私有接口、授权或专有原生库的插件不能保证兼容。

JS 使用 Android WebView 的 JavaScript 引擎，支持 ES module、Promise、`__jsEvalReturn` 和全局 `__JS_SPIDER__`，提供 HTTP 请求、本地存储、Cheerio、CryptoJS 和 JSONPath 公共模块。支持相对模块和相对规则地址。脚本自身的其他依赖仍从配置指定的地址加载，不把外部脚本打包进应用。Python、QuickJS 字节码和其他专有脚本运行时不在支持范围。

支持 JS `proxy` 和 JAR `proxyLocal` 的本机 HTTP 代理，以及 `parse:1` 页面的本机网页嗅探。代理绑定 `127.0.0.1`，各源使用独立地址。嗅探有 25 秒超时；需要交互登录、验证码、DRM 或专用解析服务的页面仍可能无法播放。AList 和 type 0/2/4 暂未实现。混合配置会导入支持的站点并显示被忽略的站点；没有支持的站点时保留原配置。

HTTPS 证书校验保持开启。用户提供的 `jieluntan-fty.json` 在 2026-10-27 抓取时有 50 条 type 3 记录，其中一条完全重复；其配置域名证书已过期，URL 导入需要上游修复证书，也可导入已经下载的 JSON 文件。其加密 JAR 只附带 ARM 原生库，不能用 x86 模拟器的结果代替 ARM 真机兼容性验证。

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
python android/device-tests/build_type3_fixture.py --output .deploy/staging/type3/fixture.jar
python android/device-tests/tvbox_cms.py --jar .deploy/staging/type3/fixture.jar
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

把 instrumentation 的 `class` 参数改为 `you.deepfuck.shortvideo.TvBoxDeviceTest`，可运行 CMS、真实 dex JAR、异步 JS 模块、JS 本地代理和网页嗅探的全部本地测试。JAR fixture 由公开 Spider ABI 编译生成，未打包进正式 APK；测试 HLS 的清单和每个分片都会检查爬虫返回的请求头。

2026-10-28 的 1.11.0 验证中，167 项 JVM 测试全部通过，lint 为 0 错误、9 项警告。API 35 x86_64 专用模拟器关闭外网后，通过本机 fixture 验证了 CMS、JAR、异步 JS 模块、JAR 代理、JS 代理、网页嗅探、全局脚本以及真实 drpy2 运行时的八条路径，检查浏览、搜索、详情、实际解码画面、暂停、拖动、收藏、进度与清缓存。整轮首次运行中，JS 嗅探用例在宿主机内存紧张时遇到 JS 引擎冷启动超时，单独重试后通过；不能将此记录理解为整轮无需重试全部成功。遇到初始化超时可重试或切换片源。

混淆后的正式 APK 可另外通过实际界面验证，避免 debug 构建掩盖反射或 JS bridge 被裁剪的问题：

```powershell
python android/device-tests/tvbox_cms.py --port 28765 --jar .deploy/staging/type3/fixture.jar --drpy .deploy/staging/type3/drpy
adb install -r android/app/build/outputs/apk/release/app-release.apk
adb reverse tcp:28765 tcp:28765
python android/device-tests/release_ui.py --kind jar --output .deploy/staging/type3/release-jar
python android/device-tests/release_ui.py --kind drpy --output .deploy/staging/type3/release-drpy
python android/device-tests/release_ui.py --kind js-proxy --output .deploy/staging/type3/release-js-proxy
```

`--drpy` 指向测试者另行提供的 drpy2 运行时和相对依赖目录；省略该参数时仍可运行其他 fixture。正式 APK 的界面验证会逐次增量添加测试片源，检查彩色视频帧随时间变化，截图和测量结果写入指定目录。

1.11.0 的正式 APK 已通过上述 JAR、真实 drpy2 和 JS 本地代理三项界面播放验证，三项均检测到彩色画面持续变化；签名沿用上一版证书。模拟器关闭外网，仅通过 adb reverse 请求独立测试服务。

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
