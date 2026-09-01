# 液态玻璃 UI 视觉原型

这是一套独立、可删除的高保真视觉原型，用于在修改 Android/PWA 生产代码前比较三种设计方向。

- `?variant=A`：克制液态玻璃。低遮挡、弱高光，适合长期观看。
- `?variant=B`：沉浸式媒体玻璃。更强层次、环境反射和组合式控制簇。
- `?variant=C`：Material 3 + 局部玻璃。Android 原生感更强。
- `&capture=1`：隐藏底部方案切换器并冻结循环动画，供 Figma 捕获。

每个方向均包含颜色、字体、间距、圆角、模糊、描边、透明度、动效和产品语言令牌，统一符号板，以及 9 个核心画面：短视频横竖屏、长视频横竖屏、ASMR 作者列表、作品与行内音频播放、ASMR 视频横竖屏、媒体库管理。

在当前目录运行：

```powershell
python -m http.server 4178
```

然后打开：

- `http://127.0.0.1:4178/?variant=A`
- `http://127.0.0.1:4178/?variant=B`
- `http://127.0.0.1:4178/?variant=C`

也可使用页面底部切换器或键盘左右方向键切换。

## 验证结果

- `390 x 844` 下无横向溢出，设备画面宽度为 `358px`。
- 原型按钮最小命中区为 `48px`。
- 已检查桌面设计板、手机短视频页、横竖屏核心矩阵、播放切换和筛选反馈。
- 控制台无错误，支持 `prefers-reduced-motion`。
- `screenshots/` 保存桌面顶部、手机短视频和三套核心矩阵截图。

## Figma 说明

目标文件：<https://www.figma.com/design/CTo9opm5GLUU2KTPRu2XG9>

当前 Figma 账号为 Starter / View 席位，无法通过 MCP 创建原生 Variables、Components 或 Styles。可使用 `generate_figma_design` 将上述运行页面捕获为可编辑图层；这类捕获不是可发布的 Figma Design System library。

本目录没有接入生产应用。选定方向并记录决策后，可单独删除，不影响现有播放器。
