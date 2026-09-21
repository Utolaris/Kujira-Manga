---
feature: cache-control-ui
status: delivered
updated: 2026-09-19
branch: canary
commits: 552c94c..552c94c # 交付落在 canary 工作区未提交改动（含既有缓存 WIP）
---

# 缓存控制 UI 改造

## Report

**What was built** — `CacheCleanupScreen` 按 Telegram 存储页信息层级重排：顶部「总缓存额度」headline 标题 + 离散档（512 MB–8 GB / 无限制）；已用行与档位标签为 `bodySmall`；滑杆经过/抵达档位触发 `AppHaptics.tick()`。其下是小字下载豁免开关，再是**构成占比**环形图（每类缓存一色 + 百分比，中心仅「合计」，不用额度进度语义）与色点明细。颜色只读 `MaterialTheme.colorScheme`。

额度语义：`UNLIMITED_MB=-1`；有限值 snap 离散档；无限时 `overBudget` 恒 false，组件磁盘仍按 MAX 份额做技术上限。封面 Coil：`coverDiskCacheMb(total)` → `componentMbToBytes`，避免二次分享额。

**Verification** — `JAVA_HOME=temurin-21 ./gradlew :app:testDebugUnitTest`（全量）PASS；定向 `CacheBudgetTest` / `CacheCleanupViewModelTest` / `ArchitectureBoundaryTest` / `LocalSettingStorageCompatibilityTest` PASS。复审确认两条 critical（二次份额、T2 `-1` 持久化测试）已关闭。

**Journey log**
- 用户在 Grill 阶段选定「离散档 + 无限制」与主题色扇形图；无限制必须扩 `CacheBudget`/`LocalSetting` 语义，不能只做 UI 文案。
- `coverDiskCacheMb`/`coverDiskCacheBytes` 入参是**总额度**，`componentMbToBytes` 入参是**已分享额的组件 MB**——混用会二次套 COVER_SHARE。
- 历史字段 `LocalSetting.coverDiskCacheMb` 不驱动 Coil；活路径是 `cacheBudgetMb`。ARCHITECTURE.md 已同步。
- T2 验收要落在 Storage/Manager 测试，ViewModel 写入断言不能代替 JSON 加载路径。
- 阅读器磁盘缓存构造期仍用 `DEFAULT_TOTAL_MB`（既有限制），无限额度下不随 budget 即时变；未在本轮展开。

## [S1] Problem

`CacheCleanupScreen` 当前把「总缓存额度」做成一张大卡里的中等字号标题，并把下载豁免开关、连续 Slider、小字说明和分项明细挤在同一信息层级。用户希望：

1. **总缓存额度控件置顶，且不要用小字**（主视觉）；
2. **「下载漫画不受缓存控制」开关降为小字**（次要说明）；
3. 其后是 **Telegram 式扇形（环形）图 + 分项明细**；
4. 扇形图颜色 **必须调用 app 当前主题色**（`MaterialTheme.colorScheme`，随调色板预设/自定义色/明暗主题变化），不得写死 Telegram 蓝紫等固定色。

参考：Telegram 存储页（离散档位大字 + 环形图中心总量 + 色点明细）。

## [S2] Design

### 页面结构（自上而下）

```text
CommonScaffold「缓存控制」
├── [可选] 操作结果条（保留现有 result 文案）
├── ① 总缓存额度（主视觉，大字）
│   ├── 标题 titleLarge / headlineSmall 级
│   ├── 已用 / 额度 bodySmall
│   ├── 离散档位：当前档 primary+Bold，字号 bodySmall
│   └── Slider 档位切换 AppHaptics.tick()
├── ② 下载漫画不受缓存控制（小字 Switch 行）
│   ├── 主文案 bodyMedium
│   ├── 说明 bodySmall / onSurfaceVariant
│   └── 「开启后，已下载漫画不占用配额，也不会被自动清理」
├── ③ 环形图（主题色）
│   ├── 中心：已用总量 formatBytes(controlledUsedBytes 或 usedBytes 按豁免语义)
│   ├── 中心副文案：额度占用（有限）或「无限制」
│   └── 扇区：各 CacheArea 用量构成（不含 ALL）
└── ④ 明细列表 + 超额清理
    ├── 每行：主题色圆点 + 名称 + 占比 + 体积
    ├── DOWNLOAD 且豁免时标注「已豁免」
    └── overBudget 时显示「清理超出部分」按钮
```

### 额度档位（离散 + 无限制）

| 档位 | 存储值 `cacheBudgetMb` |
|---|---|
| 512 MB | `512` |
| 1 GB | `1024` |
| 2 GB | `2048` |
| 4 GB | `4096` |
| 8 GB | `8192` |
| 无限制 | `CacheBudget.UNLIMITED_MB`（`-1`） |

- `CacheBudget.UNLIMITED_MB = -1`。
- `coerceTotalMb`：`-1` 原样保留；其余仍 `coerceIn(MIN_TOTAL_MB, MAX_TOTAL_MB)`，再 **snap 到最近离散档**（距离相等取较小档）。
- `isUnlimited(mb)`：`mb == UNLIMITED_MB`。
- `LocalSetting.cacheBudgetMb` 字段类型不变（`Int`）；`LocalSettingStorage` / `LocalSettingManager` 的 coerce 必须让 `-1` 穿透，不得被夹成 256。
- **无限制时的组件技术上限**：Coil / Reader 磁盘缓存仍需要有限 `maxSizeBytes`。`coverDiskCacheBytes` / `readerDiskCacheBytes` / `downloadCacheBytes` / `otherCacheBytes` / `totalBytes` 在 `UNLIMITED_MB` 入参时，一律按 `MAX_TOTAL_MB`（8192）份额计算技术上限。UI 与 `overBudget` **不**把该技术上限当作用户额度。
- **API 契约**：`*DiskCacheMb/Bytes(totalMb)` 入参是总额度；`componentMbToBytes(componentMb)` 入参是已分享额的组件 MB，禁止二次套份额。封面 Coil：`CoverImageLoaderHolder.coverDiskCacheMb(cacheBudgetMb)` → `Config.componentMbToBytes`。
- `overBudget`：`!unlimited && controlledUsedBytes > budgetBytes`。无限制时恒为 false，不显示「清理超出」。
- UI 档位标签：`512 MB` / `1 GB` / `2 GB` / `4 GB` / `8 GB` / `无限制`。已用行与档位标签均为 `bodySmall`（与页面说明小字对齐）；当前档用 `primary` + Bold 区分。

### 下载豁免开关（小字）

- 默认 `downloadExemptFromCacheLimit = true`（与现状一致）。
- 开启：环形图**仍展示** DOWNLOAD 的构成占比；额度相关「已用」与 `overBudget` 不含 DOWNLOAD；明细标注「已豁免 · 不计入额度」。
- 关闭：已用 = 含 DOWNLOAD 的总量；DOWNLOAD 参与超额计算。
- 说明文案保持小字，不升为主标题。

### 环形图（主题色，构成占比）

- Compose `Canvas` 自绘，不引入图表库。
- **语义**：磁盘上各类缓存的**构成占比**（每类一色 + 百分比），**不是**「额度 / 已用」进度环。中心仅显示构成合计 +「合计」，不显示「占额度 xx%」。
- 构成分母：所有非 `ALL` 分区 `sizeBytes` 之和（**含 DOWNLOAD**，与豁免无关）。
- **颜色来源**：与设置页调色板一致的**实色**（`applyPaletteOverride` 写入 `MaterialTheme.colorScheme` 的 primary/secondary/tertiary/error），**按占比名次**取色，不按缓存类型写死、不用 container 淡色：

| 名次（占用降序） | ColorScheme 槽位 |
|---|---|
| 第 1 | `primary`（主色） |
| 第 2 | `secondary`（辅助色） |
| 第 3 | `tertiary`（第三色） |
| 第 4 | `error`（调色板第 4 色） |
| 第 5+ | `outline` |

- `pieSlices` 按 `sizeBytes` 降序构建；图例、环形扇区、明细圆点共用同一 `cacheSlicePaletteColors(colorScheme, n)` 映射。
- 构成分母：所有非 `ALL` 分区 `sizeBytes` 之和（**含 DOWNLOAD**，与豁免无关）。
- 环轨道：`surfaceVariant`。中心仅「合计」+ 构成总量。
- 不在图内烘焙固定 hex。

### 统计口径（统一）

- 命名分区：COMMON / READER / DECODE / PDF / DOWNLOAD；`ALL` 扫描整个 `cacheDir`。
- **残差** = `ALL - 命名分区合计`（http_cache、updates 等）→ 构成图「其他」切片，并计入额度侧已用。
- **构成图分母** = `compositionTotalBytes` = `ALL`（无 ALL 时用命名合计）。
- **额度「已用」** = `controlledUsedBytes`：豁免时 `ALL - DOWNLOAD`，否则 `ALL`。与 `overBudget` 同一口径。
- 配额份额不对用户展示；仅作组件磁盘技术上限。

### 内部分项与「剩余份额」

下载**受控**时总预算拆分：COMMON 20% + READER 40% + OTHER 10%（DECODE/PDF 各半）+ DOWNLOAD 30%。

下载**豁免**（默认）时：DOWNLOAD 不占用户额度；原 30% **按比例并入**受控组件技术上限  
（cover 20/70、reader 40/70、other 10/70 × 总预算），避免份额悬空。用户仍只看到总额度。

### 页面结构（自上而下）

```text
CommonScaffold「缓存控制」
├── [可选] 操作结果条
├── ① 总缓存额度（主视觉，大字标题 + bodySmall 已用/档位）
├── ② 下载漫画不受缓存控制（小字 Switch）
├── ③ 缓存明细列表（主题色圆点 + 名称 + 占比 + 体积）
└── ④ 底部「清理缓存」按钮 → GlassConfirmDialog 高斯模糊二次确认
```

- **无扇形图组件**（已删除）；构成数据仍在 ViewModel `pieSlices`，供明细着色与占比。
- 清理确认复用 `ui/glass/GlassConfirmDialog`（与下载删除/历史删除同一套），`destructive=true`。
- 文案：豁免时说明「已下载漫画不会被删除」；未豁免说明会一并清理下载。
- `cleanCache()` 清理 COMMON + READER（租约协议）+ DECODE + PDF；未豁免时含 DOWNLOAD。

### 额度区字号与触觉

- 标题「总缓存额度」保持 headline 级主视觉。
- 「已用 …」与档位标签统一 `bodySmall`。
- 滑杆拖动**经过或抵达**某一档位（index 变化）时 `AppHaptics.tick()`；点选档位文案同样触发。系统关震动时 no-op。

### 明细列表

- 数据来自 `CacheControlItem` 与 `pieSlices`。
- 行布局：色点 + 名称 +（右侧）百分比 / 体积；次要行可保留推荐份额或「已豁免」。
- `CacheArea.ALL` 不出现在明细与扇区。

### ViewModel 契约

`CacheControlState`：

- `budgetUnlimited: Boolean`
- `compositionTotalBytes: Long`（扇区分母）
- `pieSlices: List<CachePieSlice>`
- `usageRatio`：仅有限额度且 `budgetBytes > 0` 时有意义
- `overBudget`：见上
- `displayUsedBytes`：豁免时 `controlledUsedBytes`，否则 `usedBytes`

### 分层

- UI 只在 `ui/screens/CacheCleanupScreen.kt`。
- 额度语义在 `cache/CacheBudget.kt`；状态推导在 `ui/viewModel/CacheCleanupViewModel.kt`。
- Screens 不得直接依赖 `cache.atom`（架构边界测试约束保持不变）。

### 测试边界

- `CacheBudget`：哨兵、snap、无限组件 bytes、`componentMbToBytes` 不二次分享额。
- Storage/Manager：JSON `-1` 与 coerce 往返；legacy 256 snap 到 512。
- ViewModel：无限/豁免组合下的 `overBudget`、`pieSlices`、`compositionTotalBytes`。

## [S3] Out of Scope

- 自动后台强制裁剪（当前仅有手动「清理超出」）。
- 分项配额单独调节 UI（仍按 CacheBudget 固定份额自动分配）。
- 真正无上限的 Coil/Reader 磁盘缓存（无限额度仅关闭「超额」语义，组件仍有技术上限）。
- `ReaderImagePipeline` 构造期跟随 live budget（仍用 `DEFAULT_TOTAL_MB`，既有限制）。
- 清理历史、按漫画清理、存储迁移到 SD 卡。
- Telegram 固定色板或第三方图表库。

## Tasks

- [x] T1: CacheBudget 离散档位 + UNLIMITED_MB 语义 — acceptance: coerce 保留 -1、snap 离散档、无限时组件 bytes 按 MAX 份额；单测覆盖 (covers: S2)
- [x] T2: LocalSettingManager/Storage 预算读写兼容无限 — acceptance: 持久化与投影 cacheBudgetMb=-1 往返不丢、不被夹成 256 (covers: S2; depends: T1)
- [x] T3: CacheCleanupViewModel 状态扩展 — acceptance: budgetUnlimited / pieSlices / overBudget 在无限与豁免组合下正确；更新 CacheCleanupViewModelTest (covers: S2; depends: T1)
- [x] T4: CacheCleanupScreen 布局与环形图 — acceptance: 额度大字离散档置顶、豁免开关小字、主题色环形图 + 明细、无限时不显示清理超出 (covers: S2; depends: T2, T3)
- [x] T5: 单测与架构边界 — acceptance: `:app:testDebugUnitTest` 相关用例通过，ArchitectureBoundaryTest 不因本改动失败 (covers: S2; depends: T4)
- [x] T6: 构成占比扇形图 + 额度区小字 + 档位震动 — acceptance: 图例/明细按类型百分比展示且中心不展示额度进度；已用与档位标签为 bodySmall；滑杆经过档位 AppHaptics.tick() (covers: S2; depends: T4)
- [x] T7: 扇区颜色按占用名次取调色板实色 — acceptance: 第1–4名依次 primary/secondary/tertiary/error，pieSlices 降序，图例与环形同色 (covers: S2; depends: T6)
