# 玻璃材质

调色板的「玻璃效果」提供高斯模糊与柔光玻璃单选。下方调节器随模式显示
「高斯模糊强度」或「柔光玻璃强度」。默认维持高斯模糊；切换模式分别保留强度。

- 高斯模糊：原有 0–100 范围，默认 50，显示透明 / 平衡 / 实心。
- 柔光玻璃：16–168，默认 84；左侧「通透」，右侧「柔和」，不显示数字，轨道下方标出默认位置。
- 拖动先更新滑块本地状态，松手写入设置并应用材质，避免每个触摸事件都写加密配置。
- 主题选择仍在页面最底部，配色与玻璃材质互不重置。

## 渲染与参考

视觉与参数设计参考 Hyper-PiliPlus（GPL-3.0）：

- [GlassMaterialModifier.kt](https://github.com/AritxOnly/Hyper-PiliPlus/blob/main/android/app/src/main/kotlin/com/aritxonly/deadliner/ui/material/glass/GlassMaterialModifier.kt)：底图颜色处理、模糊、光学细节和锐利前景分离。
- [AdvancedMaterialTuning.kt](https://github.com/AritxOnly/Hyper-PiliPlus/blob/main/android/app/src/main/kotlin/com/aritxonly/deadliner/model/AdvancedMaterialTuning.kt)：16–168、默认 84。
- [AdvancedMaterialFineTuning.kt](https://github.com/AritxOnly/Hyper-PiliPlus/blob/main/android/app/src/main/kotlin/com/aritxonly/deadliner/model/AdvancedMaterialFineTuning.kt)：材质半径倍率 0.4 与饱和度补偿。
- [调节器](https://github.com/AritxOnly/Hyper-PiliPlus/blob/main/lib/pages/setting/widgets/glass_material_slider_dialog.dart)：通透到柔和的方向、默认位置标记；本项目按需求省去数字。

本项目在既有原生 RenderNode 路径中适配这些材质处理，不引入第二套底图捕获库。
`GlassMaterialStyle.withAppearance` 负责统一解析；柔光半径为设置值 × 0.4 物理像素（与参考的原生 BlurEffect 单位一致），
并结合饱和度、轻微明暗调整、固定半透明底色和上缘高光。强度表示模糊尺度，不通过额外堆积不透明遮罩模拟。

API 33+ 在该效果链上加入稳定的细微颗粒和圆角边缘折射；API 31–32 保留颜色处理、真实模糊、底色与高光。
API 30 沿用半透明底色降级。前景文字和按钮不参与模糊。

所有原有原生玻璃区域（顶栏、底栏、菜单、弹窗、阅读器控件）统一从
`LocalGlassAppearance` 取得设置；详情封面模糊背景也在柔光模式使用相同效果配方。手机端的背景包住封面、元数据及完整标签区，
渐变罩层独立于被模糊的图片，在接近标签末尾时平滑过渡、末尾 5% 完全匹配页面底色，到评论组件前结束；加载骨架与正式内容使用同一覆盖范围。手机背景图片始终按 3:4、顶部对齐绘制，
标签展开只改变外层裁剪与渐变范围，不重新居中裁切图片；超出图片高度的区域已经平滑收束为页面底色。
无底图宿主的引导弹窗仍使用纯色降级，它原先也没有真实高斯模糊。

共享 source 显示列表仍只录制一次；切换材质或几何变化才重建效果，滚动继续复用底图和效果。
图像背景缓存效果，避免随滚动重复编译 shader；柔光半径不再乘屏幕密度，避免高密度设备无意放大 GPU 模糊开销。`GlassCaptureHost` 将设置显式传给 source、overlay
和嵌套宿主，避免独立 ComposeView 丢失全局选择。

## 持久化与验证

`LocalSetting` 保存 `glassEffect`、原有 `glassBlurLevel` 与新增 `softGlassLevel`；
通过 AppearancePreferences 的聚合状态发布，模式和两种强度不会在组合时串档。
缺失或未知模式回退高斯，缺失/null 柔光强度回退 84，越界值收敛到 16–168。
写入失败不发布新状态。设置 JSON 与备份保留这几个字段。

单元测试覆盖迁移、范围、模式独立、持久化失败和材质参数；Android 测试覆盖选择与调节器语义、
嵌套宿主参数传递、原生效果构造及缓存复用。Android 测试需按项目约定经用户要求后运行；
仅编译通过不能证明 GPU 视觉结果或真机性能。
