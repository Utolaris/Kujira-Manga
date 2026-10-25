# 调色板

五套预设是针对 Material 四种角色的适配方案，并非这些产品提供的官方 Material 主题。
品牌没有定义的辅助色、第三色和错误色由本项目补齐；错误色保留红色语义。
浅色主题加深部分原色，深色主题使用亮色，保证角色色作为文字绘制在页面底色上时至少 4.5:1。

| 显示名 | 保留的设置 ID | 主色方向与来源 |
| --- | --- | --- |
| Linear | `default` | 蓝紫色；[品牌规范](https://linear.app/brand)。浅色使用 `#5C68D0`，对蓝紫原色略作加深以适应页面底色 |
| Vercel | `ocean` | 蓝、灰、紫、红；[Geist 色彩系统](https://vercel.com/geist/colors)，使用其 sRGB 色阶并按主题选取 |
| Raycast | `sunset` | 珊瑚红及中性灰、紫；[媒体资料](https://www.raycast.com/press)提供品牌红 `#FF6363`，浅色主色加深，辅助和第三色为本项目适配 |
| Dracula | `forest` | 紫、青、粉、红；[官方规范](https://draculatheme.com/spec)，浅色用 Alucard，深色用 Dracula Classic |
| Catppuccin | `lavender` | Mauve、Blue、Teal、Red；[官方色板](https://catppuccin.com/palette/)，浅色用 Latte 并加深 Teal，深色用 Mocha |

具体四角色颜色以 `ui/theme/PalettePresets.kt` 为准。持久化 ID 不变，旧设置与备份无需迁移。
四色预览、自定义编辑器起始值与当前主题一致；自动取色仍由系统生成。
玻璃效果可在高斯模糊和柔光玻璃间选择，强度随模式切换；详见 [玻璃材质](./glass-material.md)。
主题入口位于调色板底部的「外观 → 主题」，点击展开二级选择菜单，支持跟随系统、日间模式和夜间模式。

`PaletteContrast` 使用 WCAG 相对亮度计算，而非对编码 RGB 做加权亮度估算。
每个角色和其容器各自选取对比度更高的黑色或白色文字。漫画的内容、角色、作品标签显式应用容器对应的前景色。
同一颜色不能普遍同时满足黑字和白字的可读性，因此不能固定前景颜色。

单元测试覆盖五套预设的浅/深主题：四种角色与对应文字、四种容器与对应文字、漫画标签文字的对比度均至少 4.5:1。
自定义颜色也自动选择可读前景；不改写用户选取的颜色，因此不保证自定义角色色直接作为页面文字时的对比度。
