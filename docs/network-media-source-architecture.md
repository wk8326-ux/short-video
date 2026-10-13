# 网络媒体源接入架构与网站适配手册

本文是本项目接入新网站、TVBox 源、CMS/API 源和其他远程媒体目录时的统一设计文档。

目标是把外部来源统一成播放器能理解的媒体目录，而不是让 Android 客户端分别理解 TVBox、网页、CMS、AList 等协议。后续接入一个新网站时，原则上只增加或调整服务端适配器，扫描、数据库、收藏、搜索、详情、播放和客户端展示沿用同一套契约。

> 适用版本：当前 `feature/movie-library` 代码线。文档中的接口以 `app/main.py`、`app/database.py`、`app/media_sources.py` 的现有实现为准；其中 91crdj 适配器需要随服务端版本一起部署，不能只在管理页面填写地址就假定功能已经上线。

## 1. 产品概念

### 1.1 对用户只有一种东西：网络媒体源

用户在管理页面看到的配置应该是：

- 名称：例如「91短剧」「某影视站」「某 TVBox 源」
- 地址：网站首页、JSON 配置、Base64/HEX 配置或 CMS API 地址
- 板块：短视频、长视频、ASMR、电影、短剧
- 分类策略：自动发现、指定分类、合并分类或忽略分类
- 鉴权：匿名、令牌、用户名密码或自定义请求头
- 同步：手动扫描、增量同步、定时刷新

用户不需要知道来源是 TVBox 还是 HTML。`TVBox`、`HTML`、`AList/OpenList` 是服务端实现类型，不是产品概念。

当前代码中的 `provider` 字段仍然保存内部实现值：

| provider | 含义 | 输入 | 主要场景 |
|---|---|---|---|
| `alist` / `openlist` | 文件目录适配器 | 目录地址、根路径、鉴权 | 网盘重定向、静态媒体 |
| `tvbox` | TVBox/CMS 适配器 | JSON、Base64、HEX 或 CMS API | 分类、影视详情、剧集播放 |
| `html` | 网站适配器 | 网站地址 | 列表页、详情页、播放页、站点 API |

后续可以新增一个对外的 `sourceKind = "network"` 或直接在客户端统一显示「网络源」，但不应把内部 `provider` 删除。内部类型决定如何发请求和解析，外部类型决定用户如何配置和理解。

### 1.2 TVBox 与网站源为什么是一类

它们在产品层确实是一类：都是把远程内容转换成媒体库和可播放媒体。

差别只在输入协议：

```text
TVBox JSON / CMS API
    -> 分类接口、列表接口、详情接口、播放地址

普通网站
    -> HTML 列表、HTML 详情、播放页、内嵌 JSON、站点 API、sitemap

AList / OpenList
    -> 目录列表、文件详情、直链或重定向地址

三者最终都必须转换成：
    Category + MediaItem + Episode + Metadata + PlaybackTarget
```

很多 TVBox 源本身就是对若干网站、CMS 站点或聚合站点的封装。TVBox 的 JSON 只是约定了如何找到这些站点和调用方式，并不保证资源稳定、长期维护或播放地址永久有效。

因此，统一的不是“所有来源使用同一套抓取代码”，而是“所有适配器输出同一套内部媒体模型”。

## 2. 总体层级

```text
┌────────────────────────────────────────────────────────────┐
│ Android / PWA 客户端                                        │
│ 首页、短视频、长视频、ASMR、电影墙、短剧墙、详情、收藏、搜索 │
└───────────────────────────┬────────────────────────────────┘
                            │ 只使用统一应用接口
┌───────────────────────────▼────────────────────────────────┐
│ FastAPI 应用层                                               │
│ 鉴权、分页、搜索、收藏、进度、详情、动态播放地址、封面代理   │
└───────────────────────────┬────────────────────────────────┘
                            │ 读取统一数据库模型
┌───────────────────────────▼────────────────────────────────┐
│ 同步编排层                                                   │
│ 分类发现、分页扫描、断点恢复、幂等、虚拟媒体库、失败重试     │
└───────────────────────────┬────────────────────────────────┘
                            │ 调用统一 SourceAdapter
┌───────────────────────────▼────────────────────────────────┐
│ 适配器层                                                     │
│ AList/OpenList | TVBox/CMS | HTML/站点 API | 未来 Telegram…  │
└───────────────────────────┬────────────────────────────────┘
                            │ 站点特定请求
┌───────────────────────────▼────────────────────────────────┐
│ 外部来源                                                     │
│ 目录 API、JSON、HTML、sitemap、详情 API、播放 API、CDN/HLS   │
└────────────────────────────────────────────────────────────┘
```

### 2.1 各层职责

#### 客户端

客户端只处理展示和交互：

- 展示媒体卡片、封面、标题、标签、年份、简介、剧集列表。
- 调用统一搜索、收藏、详情和播放接口。
- 播放时请求应用层的 `playUrl`，不直接拼接站点内部接口。
- 不保存需要长期有效的第三方签名播放地址。
- 对来源类型不做分支；短视频、电影、短剧的差别来自媒体模型和展示模式。

#### FastAPI 应用层

应用层是客户端稳定契约：

- 校验登录和会话。
- 将数据库记录转换为公共 JSON。
- 控制分页上限、来源范围和排序。
- 将播放请求转发给适配器，并返回 302 或受控代理地址。
- 不把第三方鉴权 token、密码、内部路径泄露给客户端。

#### 同步编排层

同步编排层不解析站点 HTML。它只关心统一接口：

- 发现分类。
- 遍历分类和分页。
- 将媒体和剧集写入数据库。
- 支持重复执行不产生重复数据。
- 支持一个虚拟分类单独扫描。
- 某个分类失败时继续其他分类，并记录错误。
- 对大源做限速、重试和断点恢复。

#### 适配器层

适配器只负责外部协议：

- 请求外部地址。
- 处理编码、分页、签名、Referer、Cookie、站点特殊字段。
- 输出统一的分类、媒体、详情、剧集和播放结果。
- 不直接修改 Android 模型，不直接实现收藏和观看进度。

#### 数据库层

数据库保存可复用的目录和元数据，不保存不稳定的临时播放地址：

- `media_sources`：源配置和虚拟媒体库。
- `videos`：可播放媒体和文件/远程媒体索引。
- `movies`：电影展示记录。
- `dramas`：系列/短剧展示记录。
- `movie_metadata_entities`、`movie_metadata_links`：电影元数据关系。
- `media_favorites`、`movie_favorites`：收藏。
- `watch_progress`：观看进度。
- `media_scan_jobs`、`media_scan_directories`：扫描任务和断点状态。

## 3. 统一适配器契约

下面是接入新来源时必须具备的逻辑接口。实际 Python 类可以有不同名字，但行为要满足这些约定。

```python
class SourceAdapter(Protocol):
    async def categories(self) -> list[SourceCategory]: ...

    async def list_items(
        self,
        category_id: str,
        *,
        page: int = 1,
    ) -> SourcePage: ...

    async def detail(self, remote_id: str) -> SourceItem: ...

    async def resolve(
        self,
        remote_id: str,
        *,
        episode: int = 0,
    ) -> PlaybackTarget: ...

    async def search(self, query: str, *, category_id: str | None = None) -> list[SourceItem]: ...

    async def close(self) -> None: ...
```

### 3.1 统一数据对象

#### Category

```json
{
  "remoteId": "duanju",
  "name": "成人短剧",
  "order": 1,
  "mediaMode": "series",
  "enabled": true
}
```

`remoteId` 是外部分类 ID，必须稳定。分类名称可以变，但 ID 不要用显示名称直接作为唯一键。

`mediaMode` 建议值：

- `single`：一条媒体一个可播放地址，例如短视频、电影。
- `series`：一部作品包含多集，例如短剧、电视剧、动画。
- `mixed`：同一分类同时存在单集和多集媒体。

#### MediaItem

```json
{
  "remoteId": "3010",
  "categoryId": "duanju",
  "title": "示例短剧",
  "originalTitle": null,
  "posterUrl": "https://source.example/poster.jpg",
  "backdropUrl": null,
  "overview": "简介",
  "year": 2026,
  "tags": ["都市", "复仇"],
  "episodeCount": 12,
  "updatedAt": "2026-10-13T00:00:00Z",
  "stableKey": "source-id:duanju:3010"
}
```

`stableKey` 是跨扫描保持相同记录的关键。优先级：站点稳定 ID > 站点规范 URL > 规范化路径 > 受控哈希。不能使用临时播放 URL 作为 ID。

#### Episode

```json
{
  "remoteId": "3010",
  "episode": 1,
  "title": "第1集",
  "detailUrl": "https://source.example/duanju/3010/1",
  "duration": null,
  "updatedAt": "2026-10-13T00:00:00Z"
}
```

剧集索引可以持久化，但 `playUrl` 不应在扫描时永久写入。剧集点击播放时再解析最新地址。

#### PlaybackTarget

```json
{
  "url": "https://cdn.example/video.m3u8?auth_key=temporary",
  "format": "hls",
  "headers": {
    "Referer": "https://source.example/"
  },
  "expiresAt": "2026-10-13T00:10:00Z",
  "cacheable": false
}
```

如果播放地址带 `auth_key`、时间戳、一次性 token 或短 TTL：

- 每次播放或过期后重新调用 `resolve`。
- 应用播放接口使用 `Cache-Control: no-store`。
- 不把带签名的 URL 写入长期数据库。
- 不把完整签名 URL 放进搜索索引、日志或客户端持久化缓存。

## 4. 源配置与虚拟媒体库

### 4.1 顶层源和虚拟源

一个 TVBox 或网站地址可能拥有多个分类。推荐使用两层记录：

```text
顶层源：91短剧
  ├── 成人短剧（虚拟媒体库）
  ├── 成人漫剧（虚拟媒体库）
  ├── 真人剧（虚拟媒体库）
  └── 成人视频（虚拟媒体库）
```

`media_sources` 中的关系字段：

- 顶层源：`parent_source_id = null`。
- 虚拟源：`parent_source_id = 顶层源 id`。
- `virtual_category_id`：外部分类 ID。
- `virtual_category_name`：展示名称。
- `virtual_category_type`：`tvbox`、`html` 等内部类型。

删除顶层源时必须级联删除对应虚拟源、媒体记录、剧集记录和扫描状态。删除某个虚拟源时只能删除该分类的数据，不能删除其他分类。

### 4.2 配置输入的统一形态

当前管理接口仍兼容旧字段，推荐新客户端逐步使用下列语义：

```json
{
  "name": "91短剧",
  "sourceKind": "network",
  "adapterHint": "auto",
  "baseUrl": "https://91crdjai.com",
  "section": "drama",
  "rootPaths": [],
  "categoryPolicy": "auto",
  "anonymous": true,
  "token": "",
  "username": "",
  "password": "",
  "headers": {},
  "enabled": true
}
```

服务端内部落库时可以转换为：

```json
{
  "provider": "html",
  "scanMode": "remote_pages",
  "section": "drama"
}
```

兼容原则：客户端提交的是统一网络源语义，服务端根据 `adapterHint=auto`、响应头、JSON 结构、HTML 结构或用户明确选择，决定使用 `tvbox` 或 `html` 适配器。

## 5. 同步、扫描和幂等

### 5.1 标准同步流程

```text
1. 读取顶层源配置
2. 创建/取得适配器
3. discover categories
4. 对每个分类建立或取得虚拟媒体库
5. 从第 1 页开始读取列表
6. 按稳定 ID upsert 媒体基本信息
7. 需要时读取详情和剧集目录
8. 写入 videos / movies / dramas
9. 标记本次同步未出现的旧记录为 inactive 或删除虚拟索引
10. 保存分类级成功/失败状态
11. 释放适配器
```

### 5.2 幂等键

重复扫描不能依赖标题去重。推荐键：

```text
媒体：source_id + category_id + remote_id
剧集：source_id + category_id + series_remote_id + episode_number
电影：source_id + remote_id（或稳定文件路径）
```

如果站点没有 ID：

1. 使用规范 URL 去掉追踪参数后的哈希。
2. 再退回规范化标题 + 分类 + 发布日期。
3. 明确记录 `identityQuality = weak`，方便后续排查重复。

### 5.3 断点和分类级扫描

大源必须支持：

- 顶层源扫描可以中断后继续。
- 每个虚拟分类可单独扫描。
- 某一分类出现 404、520、超时或解析失败，不阻塞其他分类。
- 已经成功入库的页不因后续页面失败而整体清空。
- 新一轮扫描不能从头重复抓已确认不变的详情，除非强制刷新。

每个分类至少记录：

```json
{
  "status": "running|completed|partial|failed",
  "page": 12,
  "pageCount": 40,
  "items": 288,
  "lastSuccess": 1791840000,
  "lastError": null,
  "retryCount": 1
}
```

### 5.4 分页不能只看前 20 条

常见错误是把首页返回的 20 条当成整个分类。适配器必须识别至少一种分页信号：

- `page` / `pagecount`。
- `total` + `limit` 推导页数。
- HTML 中的 `data-pages`、下一页链接或分页器。
- sitemap 全量 URL。

扫描完成条件是“已处理到总页数”或“下一页为空且已达到合理终止条件”，不是“第一页返回成功”。必须在接口中把 `total` 和 `nextOffset` 返回给客户端，避免电影墙或短剧墙只显示 20 条。

## 6. 播放链路

### 6.1 播放的统一链路

```text
客户端点击媒体/剧集
  -> GET /api/videos/{video_id}/play?episode=N
  -> 服务端查 videos 和 source
  -> SourceAdapter.resolve(...)
  -> 得到当前有效的外部 URL
  -> FastAPI 返回 302 或受控代理响应
  -> Android Media3 / 浏览器播放器直接请求 CDN/HLS
```

播放器不应该知道：

- TVBox 的 `vod_play_url` 如何分割。
- 网站的 `/videos/{id}/episodes/{n}/playback` 如何调用。
- AList 的 `fs/get` 参数如何组织。
- 哪些请求需要 Referer。

这些逻辑都属于适配器的 `resolve`。

### 6.2 静态地址和动态地址

| 地址类型 | 处理方式 |
|---|---|
| AList/OpenList 直链，可在短时间内复用 | 可使用短时 direct URL cache |
| TVBox 返回的普通 MP4/M3U8 | 可以短时缓存，但失败时重新解析 |
| 带 `auth_key`、时间戳、一次性签名 | 不缓存或只缓存极短时间；播放时实时解析 |
| 需要 Referer/Cookie 的地址 | 适配器返回 `requiresHeaders`；若播放器不能附带，则服务端代理或拒绝 |

当前项目的播放器链路尽量不改：继续使用 `/api/videos/{id}/play`，由服务端返回 302。新源只需要加入 `resolve` 分支，Android 播放器、进度、收藏和缓存 key 可以复用。

### 6.3 HLS 注意事项

- `m3u8`、分片、密钥文件可能使用不同域名。
- CDN 可能要求 Referer、Origin 或 User-Agent。
- 浏览器需要 CORS；Android 原生播放器不依赖浏览器 CORS。
- 临时签名可能绑定 IP、Cookie、User-Agent 或过期时间。
- 播放地址失败时要重新解析，而不是重复使用旧 URL。
- 不要把 HLS 分片下载到应用服务器作为默认方案，否则会增加带宽和磁盘压力。

## 7. FastAPI 对外接口

以下接口是客户端依赖的稳定面。新适配器不应增加客户端专用接口，除非现有媒体模型无法表达。

### 7.1 管理和同步

| 接口 | 作用 |
|---|---|
| `GET /api/admin/sources` | 获取媒体源和虚拟媒体库，密码等敏感字段不返回 |
| `POST /api/admin/sources` | 添加源；TVBox/HTML 源保存后可启动后台同步 |
| `PUT /api/admin/sources/{id}` | 修改源配置 |
| `DELETE /api/admin/sources/{id}` | 删除源及其子库和索引 |
| `POST /api/admin/sources/{id}/scan` | 单个源或虚拟分类扫描 |
| `POST /api/admin/scan?source={id}` | 兼容的单源扫描入口 |
| `GET /api/admin/status` | 查看扫描、媒体库、元数据和错误状态 |

### 7.2 统一媒体接口

| 接口 | 作用 |
|---|---|
| `GET /api/feed?category=short\|long` | 短视频/长视频分页和播放入口 |
| `GET /api/asmr/authors` | ASMR 作者目录搜索 |
| `GET /api/asmr/authors/{author}/items` | 作者媒体列表 |
| `GET /api/movies/groups` | 电影墙媒体库/虚拟分类导航 |
| `GET /api/movies` | 电影列表、分页、排序、搜索 |
| `GET /api/movies/{id}` | 电影详情和元数据实体 |
| `GET /api/movies/{id}/recommendations` | 相似推荐 |
| `GET /api/dramas/groups` | 短剧分类/虚拟媒体库导航 |
| `GET /api/dramas` | 短剧系列墙、分类、分页、搜索 |
| `GET /api/dramas/{id}` | 短剧详情和有序选集 |
| `GET /api/search?q=...` | 跨板块统一搜索 |
| `GET /api/favorites` | 收藏列表 |
| `GET /api/videos/{id}/play` | 所有来源共用的播放解析入口 |
| `POST /api/progress` | 所有媒体共用的观看进度 |

### 7.3 统一列表响应

```json
{
  "items": [
    {
      "id": "html:91crdjai:duanju:3010",
      "title": "示例短剧",
      "posterUrl": "/api/dramas/html%3A91crdjai%3Aduanju%3A3010/poster",
      "category": "成人短剧",
      "episodeCount": 12,
      "tags": ["都市", "复仇"],
      "source": "html-91crdjai-duanju"
    }
  ],
  "total": 288,
  "nextOffset": 24,
  "scan": {
    "running": false
  }
}
```

字段命名要保持客户端一致：

- `posterUrl`：应用服务端可访问的封面地址，不直接暴露不稳定 CDN 时优先走代理。
- `playUrl`：应用服务端播放入口，不是永久第三方 URL。
- `source`：用于展示来源或服务端定位，不等于用户要理解的协议。
- `episodeCount`：系列数量；单媒体可以省略或使用 `1`。
- `total`、`nextOffset`：客户端必须依据它们加载更多，不能写死 20。

## 8. 客户端展示规则

### 8.1 媒体模式

适配器输出的 `mediaMode` 决定展示方式：

| mediaMode | 展示 | 点击行为 |
|---|---|---|
| `single` | 媒体卡/电影卡 | 详情后直接播放 |
| `series` | 系列卡，显示集数 | 进入详情页选择集数 |
| `mixed` | 统一卡片，显示单集或集数 | 根据详情是否有 episodes 决定 |

短剧不能把每一集直接当成电影墙卡片，否则会丢失系列分组、封面和进度语义。剧集行仍然可以复用 `videos`，系列信息存 `dramas`，通过 `series_id` 关联。

### 8.2 封面

封面优先级：

1. 外部列表/详情提供的封面。
2. 本地目录中的 `poster.jpg`、`cover.jpg`、`folder.jpg`。
3. 站点详情页 `og:image` 或 JSON API 的 `cover`。
4. 无封面时显示稳定的文字占位卡。

封面策略：

- 封面 URL 可以缓存，临时播放 URL 不可以照搬这个策略。
- 统一通过应用服务端代理或重定向，避免 Android 直接访问被防盗链的图片域名。
- 缓存 key 使用媒体稳定 ID + 封面 URL 摘要；封面变化时摘要变化。
- 扫描时不要每次重复下载已有封面。
- 对大量源设置并发上限和失败退避。

### 8.3 搜索

搜索至少分两层：

- 结构搜索：根据源的分类、目录、作者、系列和媒体标题查询本地索引。
- 远程搜索：只有本地没有结果或源声明支持远程搜索时，才请求适配器的 `search`。

网站源的搜索规则由适配器实现，但返回必须转换成统一 `MediaItem`。搜索结果点击后必须带稳定媒体 ID 或系列 ID，不能只带标题；否则短视频/长视频会出现“搜到了但点不开”的问题。

## 9. 网站接入标准流程

拿到一个新网址后，按下面顺序执行。不要先写解析器。

### 阶段 A：站点勘探

1. 确认域名是否可访问，记录 HTTP、重定向和规范域名。
2. 检查 `robots.txt`、sitemap、站点条款和访问限制。
3. 访问首页、分类页、分页、搜索页、详情页、播放页。
4. 保存一组最小样本：1 个单媒体、1 个多集系列、1 个无封面或异常项。
5. 使用浏览器开发者工具或 REA 记录真实请求，不猜接口。
6. 区分 HTML 只是展示页面，还是实际 JSON/API 的外壳。

### 阶段 B：确定来源协议

按优先级选择：

1. 有稳定 JSON/API：优先 API 适配器。
2. 是标准 TVBox/MacCMS：优先 TVBox 适配器。
3. 只有 HTML 列表和详情：使用 HTML 适配器。
4. 只有播放页嵌入数据：使用 HTML + 播放页解析，并在播放时实时获取地址。
5. 只有需要登录的浏览器会话：先判断是否能安全配置 Cookie/Token；不要把个人会话硬编码。

### 阶段 C：建立字段映射

填写这张表再开始编码：

| 统一字段 | 外部位置 | 是否稳定 | 缺失时策略 |
|---|---|---|---|
| category ID | 分类 JSON/URL/导航 | 是/否 | 生成受控 ID |
| media ID | JSON 字段/卡片属性/详情 URL | 是/否 | 规范 URL 哈希 |
| title | title/name/卡片文本 | 通常是 | 清洗占位文案 |
| poster | cover/image/og:image | 可能带签名 | 代理并缓存 |
| overview | description/简介 | 否 | 空字符串 |
| year | date/year | 否 | null |
| tags | tags/genre/关键词 | 否 | 空列表 |
| episodes | play list/episode anchors | 可能变化 | 详情时补齐 |
| playback | play API/m3u8/MP4 | 通常不稳定 | 播放时实时解析 |
| request headers | Referer/Cookie/UA | 可能必须 | 记录能力要求 |

### 阶段 D：实现适配器

实现顺序：

1. `parse_*` 纯函数：HTML/JSON 到统一对象。
2. `_request_*`：超时、重试、状态码、编码和请求头。
3. `categories`：确认分类不会被错误过滤。
4. `list_items`：先验证第一页，再验证全分页。
5. `detail`：补全简介、封面、标签和剧集。
6. `resolve`：每次获取真实可播地址，验证 URL 和格式。
7. `search`：如果站点有搜索，映射到统一对象。
8. 加入 `MediaSourceRegistry`，但不要把站点逻辑写入 `main.py` 的公共接口。
9. 加入管理源保存、扫描、删除和虚拟分类级扫描。

## 10. 91crdj 接入记录

### 10.1 站点结构

当前勘探到的规范域名是 `https://91crdjai.com`；`https://91crdj.com` 会重定向到它。

主要地址：

```text
/duanju/                  成人短剧
/manju/                   成人漫剧
/zhenrenju/               真人剧
/shipin/                  成人视频
/duanju/page/{page}/      分类分页
/search/?q={keyword}      搜索
/duanju/{slug}/           详情
/duanju/{slug}/{episode}/ 播放页
/api/videos/{id}          元数据接口
/videos/{id}/episodes/{n}/playback  动态播放接口
```

播放接口返回类似：

```json
{
  "status": 1,
  "data": {
    "n": 1,
    "title": "第1集",
    "src": "https://cdn.example/video.m3u8?auth_key=temporary"
  }
}
```

`src` 是临时签名地址，只能在播放时生成。

### 10.2 这次踩到的 sitemap 坑

`/sitemap.xml` 返回的是 sitemap index：

```xml
<sitemapindex>
  <sitemap><loc>.../sitemap/sitemap-home.xml</loc></sitemap>
  <sitemap><loc>.../sitemap/videos-details-1.xml</loc></sitemap>
  <sitemap><loc>.../sitemap/pagination-1.xml</loc></sitemap>
</sitemapindex>
```

它不是详情 URL 列表。只解析第一层会得到 `/sitemap/...`，再根据路径筛选 `duanju`、`shipin` 时就会得到“没有视频分类”。

通用规则：

```text
sitemap.xml
  -> 如果是 sitemapindex，递归请求每一个子 sitemap
  -> 如果是 urlset，才把 loc 当作页面 URL
  -> 对所有 URL 做去重、规范化和分类筛选
```

不能通过“域名是 91crdjai 就硬编码四个分类”来代替通用修复。站点专用路径可以作为兜底，但通用 sitemap 解析必须正确，否则下一个网站还会重复出错。

### 10.3 91crdj 的四个分类

目标短剧模块当前应 materialize 四个虚拟媒体库：

```text
成人短剧  -> duanju
成人漫剧  -> manju
真人剧    -> zhenrenju
成人视频  -> shipin
```

分类页面通过卡片属性提供 ID、标题、详情路径、封面和分页信息。不要只抓首屏 20 条；分类页的 `data-pages` 和下一页链接决定全量扫描。

详情 API 可补充：

- 标题
- 简介
- 封面
- 创建时间/年份
- 标签
- 集数信息

播放 API 必须在点击剧集时调用。站点的 HTML 播放页和 API 都可能改变，适配器应该优先使用已确认的动态播放接口，并保留 HTML 播放页作为诊断和回退证据。

## 11. 已踩坑清单

### 11.1 版本和部署

- 本地写完适配器不等于 APK 已更新。
- 管理页面能选择一个 provider，不等于服务器容器已加载该代码。
- 必须同时检查 Git 提交、Docker 镜像、运行容器和 APK `manifest.json`。
- 服务器更新清单必须只有一个当前 `manifest.json`；旧的 `.update-manifest.json` 如果仍被外部脚本读取，会造成版本指针混乱。
- 服务端新接口和客户端新展示必须分别发布、分别验收。

### 11.2 解析

- sitemap index 和 urlset 不是同一种 XML。
- HTML 的属性可能在 `src`、`data-src`、`data-original` 三种位置。
- 卡片标题可能是「立即观看」等按钮占位文字，不能当真实剧名。
- JSON 字段可能是字符串、对象或列表，解析时要做类型归一化。
- 外部分类名称会变化，内部 ID 必须稳定。
- URL 中的中文、斜杠和 query 参数要使用 URL 编码，不能手工拼接未编码字符串。

### 11.3 扫描

- 首页返回 20 条不代表总数只有 20 条。
- 单个分类失败不应清空整个顶层源。
- 扫描重跑必须幂等，否则每次同步都会重复建剧集或视频。
- 虚拟媒体库需要独立扫描入口，否则用户无法重试一个失败分类。
- 不要在扫描阶段把临时播放 URL 写进数据库。
- 大量详情请求必须有并发上限、分页间隔和退避；遇到 429、5xx、520 要重试，4xx 结构错误通常不要无限重试。

### 11.4 播放

- CDN 地址可能过期，播放失败时重新解析。
- Referer、Cookie、User-Agent 可能是播放链路的一部分。
- HLS 的 m3u8、ts、key 可能有不同的域名和鉴权要求。
- 播放接口不要把第三方临时地址做长时间 HTTP 缓存。
- 客户端不要自己调用第三方播放 API，否则不同来源会污染播放器逻辑。

### 11.5 封面和缓存

- 封面可以缓存；临时播放地址不能照搬封面缓存策略。
- 加密图片需要服务端解密/代理，客户端不应该知道站点密钥。
- 封面失败应记录失败状态并退避，不能每次列表刷新都重复打爆源站。
- 本地封面缓存是可回收缓存，但 `library.db`、媒体文件和扫描索引不是普通缓存。

### 11.6 安全

- 不把密码、Token、Cookie 写入日志、客户端响应或 Git。
- 站点地址来自管理用户时，必须限制协议为 `http/https`，拒绝 `file://`、内网敏感端口和带用户密码的 URL。
- 远程抓取要防 SSRF：限制重定向目标、解析 IP、端口和请求头注入。
- 外部标题、简介和标签在 Web 前端渲染时必须按文本处理，不能直接插入 HTML。
- 第三方站点的内容和播放地址变化属于外部依赖，管理页应展示最后成功时间和最后错误。

## 12. 测试要求

每个新适配器至少包含以下测试：

### 12.1 解析单测

- 正常分类列表。
- 多页列表和分页结束。
- sitemap index 递归和重复 URL 去重。
- 缺封面、懒加载封面、相对 URL。
- 标题、标签、年份的多种字段类型。
- 无效 JSON、空列表、异常状态码。
- 中文 URL、特殊字符和 query 参数。

### 12.2 适配器 Mock 测试

- `categories()` 返回预期分类。
- `list_items()` 不只返回第一页。
- `detail()` 能建立系列和剧集。
- `resolve()` 每次调用都会重新取得动态播放地址。
- 失败请求按预期重试并在最终失败时给出可读错误。
- Referer/Accept/User-Agent 等必要请求头存在。

### 12.3 数据库/扫描测试

- 同一输入扫描两次，行数和稳定 ID 不变。
- 删除外部媒体后，旧记录按约定变成 inactive 或被清理。
- 一个分类失败，其他分类仍能入库。
- 单个虚拟媒体库能独立扫描。
- 多集系列的 episode 顺序按数字排序，避免 `第10集` 排在 `第2集` 前。
- 删除顶层源会删除虚拟源，但不会误删其他源。

### 12.4 接口测试

- `total` 和 `nextOffset` 正确。
- 搜索结果点击后能够定位到真实媒体/系列 ID。
- 详情接口返回封面、元数据和剧集。
- 播放接口返回 302 或明确的 4xx/5xx，不返回半截 URL。
- 收藏、进度和推荐不依赖具体 provider。

## 13. 新网站接入检查表

复制下面清单作为每次新接入的任务模板：

```text
[ ] 确认规范域名、重定向、robots 和使用权限
[ ] 记录首页、分类、分页、搜索、详情、播放页
[ ] 捕获真实 JSON/API 请求和必要请求头
[ ] 判断 TVBox/CMS、HTML、sitemap 或混合协议
[ ] 找到稳定分类 ID 和媒体 ID
[ ] 找到单媒体与多集系列的差异
[ ] 找到封面、简介、年份、标签、演员等字段
[ ] 确认播放地址是否临时签名、是否需要 Referer/Cookie
[ ] 确认 HLS/MP4/音频格式和 CDN 域名
[ ] 编写纯解析函数和 fixture
[ ] 实现 categories/list/detail/resolve/search
[ ] 实现分页、限速、重试、超时和错误分类
[ ] 加入 SourceRegistry 和统一媒体模型
[ ] 加入虚拟媒体库和分类级扫描
[ ] 验证扫描幂等、断点恢复和删除清理
[ ] 验证 total/nextOffset 不被限制为首屏 20 条
[ ] 验证详情、收藏、进度、搜索和播放
[ ] 验证动态播放地址没有被长缓存
[ ] 更新管理页面为统一“网络源”配置
[ ] 构建服务端镜像并部署
[ ] 如客户端需要新展示，再递增 APK versionCode
[ ] 线上检查健康、扫描状态、接口、播放和回滚
```

## 14. 推荐的后续重构

当前代码已经有真实的 `tvbox` 和 `html` 适配器，但 `main.py` 仍然需要根据 provider 分支。后续可以分三步收敛：

### 第一步：统一内部接口

为 TVBox、HTML 和 AList 定义共同的 `SourceAdapter` 类型，统一 `Category`、`Page`、`Item`、`PlaybackTarget`。`main.py` 只调用接口，不直接判断站点字段。

### 第二步：统一配置和能力声明

为每个适配器增加能力：

```json
{
  "supportsSearch": true,
  "supportsSeries": true,
  "supportsDynamicPlayback": true,
  "requiresHeaders": false,
  "supportsRemoteCategories": true
}
```

管理页面显示统一的网络源配置，根据能力动态显示分类过滤、鉴权和扫描选项。

### 第三步：统一同步编排器

把现在的 `scan_tvbox_source`、`scan_html_source` 和目录扫描中的共同部分抽出：

```text
SourceAdapter -> SyncCoordinator -> Database
```

适配器只负责远端，编排器只负责分页、幂等、断点、错误和虚拟库。这样以后接 Telegram、RSS、GraphQL 或新的 CMS 时，不需要再修改收藏、搜索、电影墙和播放器。

## 15. 验收结论标准

一个新源只有同时满足以下条件，才算接入完成：

1. 管理页面可以用统一方式保存，不要求用户理解实现协议。
2. 分类和媒体数量与外部源的分页/总数一致，不能只显示首屏 20 条。
3. 重复扫描不增加重复媒体和剧集。
4. 单个分类可以独立扫描和重试。
5. 详情页可展示封面、标题、简介、标签、年份和集数。
6. 播放时能获取当前有效地址，过期后能重新解析。
7. 搜索、收藏、进度和推荐使用统一媒体 ID，不依赖 provider 分支。
8. 源站暂时不可用时，已有索引不会被误清空，并且管理页能看到错误。
9. Android 和 PWA 不需要为这个新网站增加一套播放器。
10. 服务端和 APK 的版本、部署、manifest、回滚路径都已记录。
