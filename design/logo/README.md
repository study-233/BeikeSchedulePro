# 贝壳课表图标：小牛抱鼎

保留原版暖棕色小牛和略带疲惫的微笑，将课表牌改为浅色鼎。双耳、鼎身与双足共同强化北科元素，鼎身六个色块保留课表含义。造型参考[北京科技大学学校标识说明](https://www.ustb.edu.cn/xxgk/xxbs/5488.htm)，不使用校名文字或完整校徽。

## 素材

- beike-app-icon-foreground.png：内置 image_gen 编辑生成的 1254 × 1254 透明原始素材，供后续继续编辑。
- beike-app-icon-source.png：1254 × 1254 蓝底完整图标，与桌面可见区域构图一致。
- Android 单色版沿用 app/src/main/res/drawable/ic_launcher_monochrome.xml，使用可编辑矢量简化小牛和鼎，格子、双耳内部及面部通过镂空表现。

## 导出约定

背景色为 #075F96，与 ic_launcher_background.xml 保持一致。彩图主体由 image_gen 完成，Sharp 仅用于裁边、缩放、合成蓝底、遮罩预览和资源格式导出；未使用 CLI 图像生成。

透明素材以 alpha > 8 的像素求主体包围盒（本版 x=202…1051、y=84…1164），以包围盒中心计算最远像素半径，缩放至 108 dp 前景中央半径 32.5 dp 的范围内，保持宽高比。不要把蓝底合并进自适应前景，也不要将整幅原图直接放大铺满前景。

先以 1728 × 1728（108 dp × 16）制作透明前景母版，再取中央 1152 × 1152（72 dp × 16）区域合成蓝底，作为普通图标与商店图标的导出母版。圆形版本仅裁切蓝底图标外缘；自适应前景不预置圆角或圆形遮罩。

| 密度 | 普通 / 圆形图标 | 自适应前景 |
| --- | --- | --- |
| mdpi | 48 × 48 | 108 × 108 |
| hdpi | 72 × 72 | 162 × 162 |
| xhdpi | 96 × 96 | 216 × 216 |
| xxhdpi | 144 × 144 | 324 × 324 |
| xxxhdpi | 192 × 192 | 432 × 432 |

mipmap 资源采用无损 WebP；商店图标为 app/src/main/ic_launcher-playstore.png（512 × 512，不透明 PNG）。已有资源名称和 Manifest 引用保持一致。[Android 自适应图标规范](https://developer.android.com/develop/ui/compose/system/icon_design_adaptive)说明了前景、背景、单色层和安全区域。

## 生成提示词

以下为内置 image_gen 编辑原版图标时使用的完整提示词：

Edit the supplied existing blue BeikeSchedule cow icon into the approved new identity: 小牛抱鼎, a small warm brown cartoon cow embracing a Chinese DING ritual vessel. Use the original icon visible in conversation as the edit target (direct local-file loading is unavailable). Preserve recognizable original cow identity: ochre brown face, dark brown swept tuft, ivory horns, relaxed slightly weary half-lidded eyes and subtle smile, rounded bold dark-brown outlines. Reduce head dominance. Replace rectangular timetable completely with a large ivory-white DING inspired by the stylized rectangular ding in University of Science and Technology Beijing emblem: two distinct raised squared loop handles atop left and right rim, broad horizontal lip, wide slightly tapering rectangular belly, two clearly separated substantial splayed legs in front view. Must instantly read as ancient Chinese ritual vessel, NOT cup, cooking pot, trophy or timetable board. Cow behind vessel, face above and between the raised handles, hands holding outer sides of belly below handles, never occluding loops or feet. Ding takes lower 45 percent of entire mascot. Integrate only four to six simple blue timetable cells directly into vessel belly, sparse 2-by-3 pattern, no separate board or tiny lattice. Remove old gate-like white background lines completely. Output isolated complete mascot on genuinely TRANSPARENT background with alpha, no blue backdrop, no external shadow or floor. Blue #075F96 background will be applied later for Android icons. Polished flat vector-like raster illustration, smooth color fills, restrained shading, excellent small-size readability, not photorealistic or 3D. Entire horns ears hands ding handles and feet within frame, centered balanced silhouette, generous transparent margin about 12 percent all sides. Square high-resolution 1024x1024 or larger. No text, letters, watermark, circular badge or rounded icon tile. One final production icon asset, not options sheet.

## 复核范围

已进行图片尺寸、透明通道、资源引用和圆形 / 圆角方形 / 单色 / 48 与 64 像素预览的静态复核。按仓库源码编辑约定，不运行构建、测试或设备操作；实际桌面效果需由开发者安装新构建后检查，包括主题图标开关和不同启动器裁切。
