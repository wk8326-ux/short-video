const variants = {
  A: {
    code: "A / RESTRAINED",
    name: "克制液态玻璃",
    tagline: "把玻璃退到画面边缘，让观看本身成为界面。",
    summary: "低模糊、细描边、弱高光。适合长时间观看，也最接近当前 Android 版本的克制方向。",
    blur: "16 px",
    surface: "弱反射 / 低遮挡",
    motion: "120 / 220 / 320 ms",
    tone: "安静、直接、耐看",
    radius: "8px",
    opacity: "56%",
    voice: ["继续观看", "正在准备播放", "后台播放已开启"],
    palette: ["#090A0B", "#15171A", "#F4F1ED", "#BBB7B1", "#E7464F"]
  },
  B: {
    code: "B / IMMERSIVE",
    name: "沉浸式媒体玻璃",
    tagline: "让控制层像环境光一样浮在媒体之上。",
    summary: "更强的模糊、反射和组合式控制簇。层次最丰富，适合强调产品辨识度和沉浸感。",
    blur: "28 px",
    surface: "环境反射 / 组合控件",
    motion: "140 / 260 / 360 ms",
    tone: "沉浸、精致、鲜明",
    radius: "16px",
    opacity: "58%",
    voice: ["回到你的声场", "媒体正在就绪", "声音将在后台继续"],
    palette: ["#07090B", "#12161A", "#E8F2F4", "#9CB0B6", "#E7464F"]
  },
  C: {
    code: "C / MATERIAL",
    name: "Material 3 + 局部玻璃",
    tagline: "保留 Android 的熟悉感，只在关键层使用玻璃。",
    summary: "Material 3 Expressive 的触控与层级为骨架，玻璃只进入导航、控制簇和迷你播放器。",
    blur: "10 px",
    surface: "色调表面 / 局部玻璃",
    motion: "100 / 200 / 300 ms",
    tone: "原生、清晰、稳健",
    radius: "14px",
    opacity: "74%",
    voice: ["继续播放", "正在加载媒体", "允许后台播放"],
    palette: ["#0C0D0F", "#191A1E", "#F4F1ED", "#C9C5C0", "#E7464F"]
  }
};

const icon = (name, className = "") =>
  `<svg class="icon ${className}" aria-hidden="true"><use href="#i-${name}"></use></svg>`;

const iconButton = (name, label, className = "edge-control", action = "") =>
  `<button class="${className}" type="button" aria-label="${label}" title="${label}" ${action ? `data-action="${action}"` : ""}>${icon(name)}</button>`;

const statusRow = () => `
  <div class="status-row" aria-hidden="true"><span>10:24</span><span>5G&nbsp;&nbsp;92%</span></div>`;

const topNav = (active = "短视频") => `
  <nav class="surface-nav" aria-label="内容分栏">
    ${["短视频", "长视频", "ASMR"].map(item => `<span class="${item === active ? "active" : ""}" data-nav-tab>${item}</span>`).join("")}
  </nav>
  ${iconButton("more", "打开管理菜单", "more-button")}`;

const progress = () => `<div class="progress-line" aria-label="播放进度 42%"><i></i></div>`;

const device = (content, className = "") => `<div class="device ${className}">${content}</div>`;

const screenBlock = (label, note, content, wide = false) => `
  <article class="screen-block ${wide ? "screen-wide" : ""}">
    <div class="screen-label"><span>${label}</span><em>${note}</em></div>
    ${content}
  </article>`;

const feedPortrait = (variant, type) => {
  const isLong = type === "long";
  const title = isLong ? "海岸线缓慢升起的雾 · 4K" : "凌晨两点，城市还没有完全睡去";
  const meta = isLong ? "42:18 · 上次看到 18:06" : "00:38 · 随机播放 127 / 762";
  const mode = isLong ? "长视频" : "短视频";
  const variantClass = variant.toLowerCase();
  const sideClass = variant === "C" ? "material-cluster" : "side-rail console-actions";
  const controls = `
    <div class="${sideClass}">
      ${iconButton("volume", "声音")}
      ${iconButton("full", "横屏")}
    </div>`;
  const metaBlock = `<div class="feed-meta"><strong>${title}</strong><span>${meta}</span></div>`;
  const consoleBlock = variant === "B"
    ? `<div class="feed-console">${metaBlock}<div class="console-actions">${iconButton("volume", "声音")}${iconButton("full", "横屏")}</div></div>`
    : `${metaBlock}${controls}`;

  return device(`
    <div class="phone-screen feed-${variantClass}">
      <div class="media-photo ${isLong ? "long" : ""}"></div>
      ${statusRow()}${topNav(mode)}
      <button class="center-play" type="button" data-action="play" aria-label="暂停" title="播放或暂停">${icon("pause", "fill")}</button>
      ${consoleBlock}${progress()}
    </div>`);
};

const feedLandscape = (variant, type) => {
  const isLong = type === "long";
  const title = isLong ? "海岸线缓慢升起的雾 · 4K" : "凌晨两点，城市还没有完全睡去";
  const className = `land-${variant.toLowerCase()}`;
  return device(`
    <div class="phone-screen ${className}">
      <div class="media-photo ${isLong ? "long" : ""}"></div>
      <div class="landscape-controls">
        ${iconButton("prev", "上一条")}
        ${iconButton("pause", "暂停", "edge-control main", "play")}
        ${iconButton("next", "下一条")}
      </div>
      <div class="landscape-title"><span>${title}</span><span>${isLong ? "18:06 / 42:18" : "00:16 / 00:38"}</span></div>
      ${progress()}
    </div>`, "landscape");
};

const authorsScreen = variant => {
  const klass = `authors-${variant.toLowerCase()}`;
  const authors = [
    ["Aki 秋水", "48 个作品 · 12 视频 / 36 音频"],
    ["Eunzel ASMR", "31 个作品 · 8 视频 / 23 音频"],
    ["Hatomugi ASMR", "26 个作品 · 6 视频 / 20 音频"],
    ["PPOMO", "19 个作品 · 11 视频 / 8 音频"],
    ["RaffyTaphy", "17 个作品 · 仅音频"]
  ];
  return device(`
    <div class="phone-screen library-screen ${klass}">
      ${statusRow()}${topNav("ASMR")}
      <div class="library-hero">
        <div class="library-title"><div><h3>ASMR 媒体库</h3><p>5 位作者 · 141 个作品</p></div>${icon("library")}</div>
        <div class="search-field" role="search">${icon("search")}<span>搜索全部作者</span></div>
        <div class="filter-tabs" aria-label="内容筛选"><span class="active" data-filter>全部</span><span data-filter>只看视频</span><span data-filter>只听音频</span></div>
      </div>
      <div class="author-list">
        ${authors.map(([name, count]) => `<div class="author-row"><div class="row-copy"><strong>${name}</strong><span>${count}</span></div><span class="author-total">已建立索引</span>${icon("chevron")}</div>`).join("")}
      </div>
    </div>`);
};

const worksScreen = variant => {
  const klass = `works-${variant.toLowerCase()}`;
  const works = [
    ["audio", "雨夜耳语与木质敲击", "48:22 · 音频", true],
    ["video", "双耳麦克风近距离触发", "22:08 · 视频", false],
    ["audio", "缓慢翻书与呼吸声", "36:51 · 音频", false],
    ["video", "睡前护理角色扮演", "31:05 · 视频", false],
    ["audio", "无说话 · 玻璃与水声", "54:14 · 音频", false]
  ];
  return device(`
    <div class="phone-screen library-screen ${klass}">
      ${statusRow()}${topNav("ASMR")}
      <div class="library-title"><div><h3>Aki 秋水</h3><p>48 个作品 · 返回时保留列表位置</p></div>${icon("more")}</div>
      <div class="filter-tabs"><span class="active" data-filter>全部</span><span data-filter>视频</span><span data-filter>音频</span></div>
      <div class="media-list">
        ${works.map(([type, title, meta, active]) => `<div class="media-row ${active ? "active-row" : ""}">${active ? `<div class="equalizer" aria-label="正在播放"><i></i><i></i><i></i></div>` : icon(type === "video" ? "video" : "headphones")}<div class="row-copy"><strong>${title}</strong><span>${meta}</span></div>${icon("more")}</div>`).join("")}
      </div>
      <div class="mini-player" aria-label="正在播放 雨夜耳语与木质敲击">
        <div class="equalizer"><i></i><i></i><i></i></div>
        <div class="mini-copy"><strong>雨夜耳语与木质敲击</strong><span>18:21 / 48:22 · 后台播放已开启</span></div>
        ${iconButton("pause", "暂停", "edge-control", "play")}
      </div>
    </div>`);
};

const asmrVideoPortrait = variant => {
  const klass = `asmr-player-screen player-${variant.toLowerCase()}`;
  return device(`
    <div class="phone-screen ${klass}">
      <div class="media-photo asmr"></div>
      ${statusRow()}${topNav("ASMR")}
      <div class="player-title"><strong>双耳麦克风近距离触发</strong><span>Aki 秋水 · 08:42 / 22:08</span></div>
      <button class="center-play" type="button" data-action="play" aria-label="暂停">${icon("pause", "fill")}</button>
      <div class="thumb-controls">
        ${iconButton("background", "后台播放")}
        ${iconButton("full", "横屏")}
      </div>
      ${progress()}
    </div>`);
};

const asmrVideoLandscape = variant => device(`
  <div class="phone-screen land-${variant.toLowerCase()}">
    <div class="media-photo asmr"></div>
    <div class="landscape-controls">
      ${iconButton("prev", "上一条")}
      ${iconButton("pause", "暂停", "edge-control main", "play")}
      ${iconButton("next", "下一条")}
    </div>
    <div class="landscape-title"><span>双耳麦克风近距离触发 · Aki 秋水</span><span>08:42 / 22:08</span></div>
    ${progress()}
  </div>`, "landscape");

const managementScreen = variant => {
  const klass = `manage-${variant.toLowerCase()}`;
  const sources = [
    ["光鸭", "https://guangya.deepfuck.you/光鸭/下载器", "短视频 / 长视频", "1,428", "18 分钟前"],
    ["ASMR 主库", "https://www.asmrgay.com/asmr", "ASMR", "836", "2 天前"],
    ["ASMR 6", "https://www.asmrgay.com/asmr6", "ASMR", "412", "2 天前"]
  ];
  return device(`
    <div class="phone-screen management-screen ${klass}">
      ${statusRow()}
      <div class="manage-header">${icon("back")}<div><h3>媒体库管理</h3><p>各媒体源独立维护，只在手动操作时扫描</p></div></div>
      <div class="source-list">
        ${sources.map(([name, url, area, count, time], index) => `<section class="source-card"><header><div><h4>${name}</h4><p>${url}</p></div><span class="badge">${area}</span></header><div class="source-stats"><div><strong>${count}</strong><span>媒体数量</span></div><div><strong>${time}</strong><span>上次扫描</span></div><div><strong>${index === 0 ? "正常" : "已索引"}</strong><span>当前状态</span></div></div><div class="source-actions"><span>编辑</span><span>校验</span><span class="primary">扫描此库</span></div></section>`).join("")}
      </div>
      <div class="fab-add">＋ 添加媒体源</div>
    </div>`);
};

const tokenBoard = data => `
  <section class="board-section" aria-labelledby="tokens-title">
    <div class="section-heading"><div><span class="section-kicker">Foundations</span><h2 id="tokens-title">视觉令牌</h2></div><p>令牌只表达视觉和交互节奏，不映射新的业务状态。Compose 与 PWA 后续从同一语义层生成平台值。</p></div>
    <div class="token-grid">
      <div class="token-group"><span class="token-label">Color / OLED dark</span><div class="palette">${data.palette.map(color => `<i class="swatch" style="background:${color}" title="${color}"></i>`).join("")}</div><div class="token-copy"><strong>暗场中的媒体色</strong><span>红色仅用于选中、进度与关键状态，不制造单一黑灰界面。</span></div></div>
      <div class="token-group"><span class="token-label">Glass / ${data.blur}</span><div class="glass-sample"></div><div class="token-copy"><strong>${data.surface}</strong><span>玻璃只进入导航、控制簇和浮动播放层，不包裹全部列表。</span></div></div>
      <div class="token-group"><span class="token-label">Type / Noto Sans SC + Roboto</span><div class="type-sample"><strong>画面优先</strong><span>作者名称完整显示</span><span>18:21 / 48:22</span></div><div class="token-copy"><span>标题 21 / 650，正文 14 / 400，标签 12 / 550，时间采用等宽数字。</span></div></div>
      <div class="token-group"><span class="token-label">Motion / spatial</span><div class="motion-bars"><div><span>反馈</span><i style="--bar-width:32%"></i><b>120</b></div><div><span>切换</span><i style="--bar-width:58%"></i><b>220</b></div><div><span>进入</span><i style="--bar-width:84%"></i><b>320</b></div></div><div class="token-copy"><strong>${data.motion}</strong><span>退出快于进入；动效可打断；减弱动态时退化为淡入淡出。</span></div></div>
      <div class="token-group"><span class="token-label">Geometry / surface</span><div class="geometry-spec"><div class="radius-samples"><i></i><i></i><i></i></div><dl><div><dt>间距</dt><dd>4 · 8 · 12 · 16 · 24 · 32</dd></div><div><dt>圆角</dt><dd>${data.radius}</dd></div><div><dt>描边</dt><dd>1px / 18%</dd></div><div><dt>玻璃</dt><dd>${data.opacity}</dd></div></dl></div><div class="token-copy"><span>布局使用 4/8dp 节奏，表面参数按方向统一，不在单个页面随意变化。</span></div></div>
      <div class="token-group"><span class="token-label">Product voice / ${data.tone}</span><div class="voice-sample">${data.voice.map((line, index) => `<div><span>0${index + 1}</span><strong>${line}</strong></div>`).join("")}</div><div class="token-copy"><span>短句、当前状态、直接结果。不使用社交平台式劝导，不解释界面本身。</span></div></div>
    </div>
  </section>`;

const symbolBoard = () => {
  const symbols = [
    ["play", "播放"], ["pause", "暂停"], ["prev", "上一条"], ["next", "下一条"],
    ["volume", "声音"], ["full", "横屏"], ["background", "后台播放"], ["filter", "筛选"],
    ["settings", "管理"], ["refresh", "更新"], ["download", "下载"]
  ];
  return `
    <section class="board-section" aria-labelledby="symbols-title">
      <div class="section-heading"><div><span class="section-kicker">Symbols</span><h2 id="symbols-title">统一符号板</h2></div><p>统一 24px 线性图标和约 1.8px 笔画。视觉框体透明，交互命中区保持 48dp，避免每个按钮都套圆。</p></div>
      <div class="symbol-board">${symbols.map(([name, label]) => `<div class="symbol-item">${icon(name, name === "play" || name === "pause" ? "fill" : "")}<span>${label}</span></div>`).join("")}</div>
    </section>`;
};

const screensBoard = variant => `
  <section class="board-section" aria-labelledby="screens-title">
    <div class="section-heading"><div><span class="section-kicker">Core screens</span><h2 id="screens-title">横竖屏与核心流程</h2></div><p>统一覆盖短视频、长视频、ASMR 和媒体库管理。画面中的控制只用于视觉与微交互演示，不接入生产播放器。</p></div>
    <div class="screens-grid">
      ${screenBlock("01 / 短视频", "竖屏 · 常驻轻控件", feedPortrait(variant, "short"))}
      ${screenBlock("02 / 长视频", "竖屏 · 进度优先", feedPortrait(variant, "long"))}
      ${screenBlock("03 / ASMR", "作者库 · 完整名称", authorsScreen(variant))}
      ${screenBlock("04 / ASMR", "作品列表 · 行内音频", worksScreen(variant))}
      ${screenBlock("05 / ASMR VIDEO", "竖屏 · 单手可达", asmrVideoPortrait(variant))}
      ${screenBlock("06 / MEDIA SOURCES", "独立扫描与维护", managementScreen(variant))}
      ${screenBlock("07 / 短视频", "横屏 · 5 秒渐隐", feedLandscape(variant, "short"), true)}
      ${screenBlock("08 / 长视频", "横屏 · 三点对齐", feedLandscape(variant, "long"), true)}
      ${screenBlock("09 / ASMR VIDEO", "横屏 · 顺序续播", asmrVideoLandscape(variant), true)}
    </div>
  </section>`;

const render = variant => {
  const data = variants[variant];
  document.body.dataset.variant = variant;
  document.title = `短片 · ${data.name}`;
  document.getElementById("switcher-label").textContent = `${variant} · ${data.name}`;
  document.getElementById("prototype-root").innerHTML = `
    <div class="design-board">
      <header class="board-header">
        <div><span class="board-kicker">Short · Liquid Glass Exploration</span><h1>${data.name}</h1><p class="board-summary">${data.tagline}<br>${data.summary}</p></div>
        <div class="direction-facts"><div><span>方向</span><strong>${data.code}</strong></div><div><span>触控</span><strong>48dp 最小命中区</strong></div><div><span>模糊</span><strong>${data.blur}</strong></div><div><span>性格</span><strong>${data.tone}</strong></div></div>
      </header>
      ${tokenBoard(data)}${symbolBoard()}${screensBoard(variant)}
    </div>`;
};

const params = new URLSearchParams(location.search);
let currentVariant = (params.get("variant") || "A").toUpperCase();
if (!variants[currentVariant]) currentVariant = "A";
if (params.get("capture") === "1") document.body.classList.add("is-capture");

const setVariant = variant => {
  currentVariant = variant;
  params.set("variant", variant);
  history.replaceState({}, "", `${location.pathname}?${params.toString()}`);
  render(variant);
  window.scrollTo({ top: 0, behavior: "smooth" });
};

const cycleVariant = delta => {
  const keys = Object.keys(variants);
  const next = (keys.indexOf(currentVariant) + delta + keys.length) % keys.length;
  setVariant(keys[next]);
};

document.querySelector(".prototype-switcher").addEventListener("click", event => {
  const button = event.target.closest("button[data-cycle]");
  if (button) cycleVariant(Number(button.dataset.cycle));
});

document.addEventListener("click", event => {
  const tab = event.target.closest("[data-nav-tab], [data-filter]");
  if (tab) {
    [...tab.parentElement.children].forEach(item => item.classList.remove("active"));
    tab.classList.add("active");
  }

  const action = event.target.closest("[data-action='play']");
  if (action) {
    const use = action.querySelector("use");
    const paused = use.getAttribute("href") === "#i-play";
    use.setAttribute("href", paused ? "#i-pause" : "#i-play");
    action.setAttribute("aria-label", paused ? "暂停" : "播放");
  }
});

document.addEventListener("keydown", event => {
  if (event.target.matches("input, textarea, select, [contenteditable='true']")) return;
  if (event.key === "ArrowLeft") cycleVariant(-1);
  if (event.key === "ArrowRight") cycleVariant(1);
});

render(currentVariant);
