package com.par9uet.jm.ui.glass

import android.content.Context
import android.graphics.Canvas
import android.os.Build
import android.view.View
import android.widget.FrameLayout
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.LocalMinimumInteractiveComponentSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.compose.ui.viewinterop.AndroidView
import androidx.navigation.NavHostController
import com.par9uet.jm.ui.models.LocalTabletLayoutEnabled
import com.par9uet.jm.ui.navigation.LocalMainNavController
import com.par9uet.jm.ui.theme.ExtendedColorScheme
import com.par9uet.jm.ui.theme.ExtendedTheme
import com.par9uet.jm.ui.theme.LocalExtendedColors
import com.par9uet.jm.utils.log
import kotlin.math.roundToInt

/**
 * Hosts one source composition and one transparent foreground composition. Any number of
 * [GlassSurface] instances in the foreground share the source display list.
 */
@Composable
fun GlassCaptureHost(
    modifier: Modifier = Modifier,
    backdropMode: GlassBackdropMode = GlassBackdropMode.Blur,
    sourceContent: @Composable () -> Unit,
    overlayContent: @Composable () -> Unit,
) {
    val sourceContentState = rememberUpdatedState(sourceContent)
    val overlayContentState = rememberUpdatedState(overlayContent)
    val colorScheme = MaterialTheme.colorScheme
    val typography = MaterialTheme.typography
    val shapes = MaterialTheme.shapes
    val extendedColors = ExtendedTheme.colors
    val mainNavController = LocalMainNavController.current
    val minimumInteractiveComponentSize = LocalMinimumInteractiveComponentSize.current
    val tabletLayoutEnabled = LocalTabletLayoutEnabled.current
    val context = LocalContext.current

    AndroidView(
        modifier = modifier,
        factory = {
            GlassCaptureHostView(context).apply {
                setBackdropMode(backdropMode)
                setContents(
                    sourceContent = { sourceContentState.value() },
                    overlayContent = { overlayContentState.value() },
                )
                updateTheme(
                    colorScheme = colorScheme,
                    typography = typography,
                    shapes = shapes,
                    extendedColors = extendedColors,
                    mainNavController = mainNavController,
                    minimumInteractiveComponentSize = minimumInteractiveComponentSize,
                    tabletLayoutEnabled = tabletLayoutEnabled,
                )
            }
        },
        update = { host ->
            host.setBackdropMode(backdropMode)
            host.updateTheme(
                colorScheme = colorScheme,
                typography = typography,
                shapes = shapes,
                extendedColors = extendedColors,
                mainNavController = mainNavController,
                minimumInteractiveComponentSize = minimumInteractiveComponentSize,
                tabletLayoutEnabled = tabletLayoutEnabled,
            )
        },
        onRelease = GlassCaptureHostView::dispose,
    )
}

internal class GlassCaptureHostView(context: Context) :
    FrameLayout(context),
    GlassSurfaceRegistry {

    private data class ThemeState(
        val colorScheme: ColorScheme,
        val typography: Typography,
        val shapes: Shapes,
        val extendedColors: ExtendedColorScheme,
        val mainNavController: NavHostController,
        val minimumInteractiveComponentSize: androidx.compose.ui.unit.Dp,
        val tabletLayoutEnabled: Boolean,
    )

    private data class RegisteredSurface(
        val style: GlassSurfaceStyle,
        val alpha: Float,
        val scale: Float = 1f,
        val bounds: GlassSurfaceBounds? = null,
    )

    private val sourceComposeView = GlassSourceComposeView(context)
    private val overlayComposeView = ComposeView(context)
    private val sourceContentState = mutableStateOf<(@Composable () -> Unit)?>(null)
    private val overlayContentState = mutableStateOf<(@Composable () -> Unit)?>(null)
    private val themeState = mutableStateOf<ThemeState?>(null)
    private val registeredSurfaces = linkedMapOf<String, RegisteredSurface>()
    private val backdropViews = linkedMapOf<String, GlassBackdropView>()
    private var currentColorScheme: ColorScheme? = null
    private var isDarkTheme = false
    private var surfaceSyncPosted = false
    private var isCapturingSource = false
    private var backdropMode = GlassBackdropMode.Blur
    private val sourceCapture = GlassCaptureSource(
        sourceView = sourceComposeView,
        onCaptureStart = { isCapturingSource = true },
        onCaptureEnd = { isCapturingSource = false },
    )

    @get:androidx.annotation.VisibleForTesting
    internal val sourceCaptureGeneration: Int get() = sourceCapture.generation

    init {
        setWillNotDraw(true)
        clipChildren = false

        sourceComposeView.layoutParams = LayoutParams(
            LayoutParams.MATCH_PARENT,
            LayoutParams.MATCH_PARENT,
        )
        overlayComposeView.layoutParams = LayoutParams(
            LayoutParams.MATCH_PARENT,
            LayoutParams.MATCH_PARENT,
        )
        sourceComposeView.setViewCompositionStrategy(
            ViewCompositionStrategy.DisposeOnDetachedFromWindow,
        )
        overlayComposeView.setViewCompositionStrategy(
            ViewCompositionStrategy.DisposeOnDetachedFromWindow,
        )
        overlayComposeView.isClickable = false
        overlayComposeView.isFocusable = false

        addView(sourceComposeView)
        addView(overlayComposeView)

        // The capture is refreshed only when the source composition reports a real change
        // (invalidation or layout request). Re-marking dirty from inside every draw would
        // make the host redraw forever and Compose instrumentation never reaches idle.
        sourceComposeView.onSourceInvalidated = {
            if (!isCapturingSource) {
                sourceCapture.markDirty("源内容 invalidate")
            }
        }

        sourceComposeView.contentCallback = {
            val theme = themeState.value
            val content = sourceContentState.value
            if (theme != null && content != null) {
                ThemedContent(theme) {
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .drawWithContent {
                                drawRect(theme.colorScheme.background)
                                drawContent()
                            },
                    ) {
                        content()
                    }
                }
            }
        }

        overlayComposeView.setContent {
            val theme = themeState.value
            val content = overlayContentState.value
            if (theme != null && content != null) {
                CompositionLocalProvider(LocalGlassSurfaceRegistry provides this@GlassCaptureHostView) {
                    ThemedContent(theme) {
                        Box(modifier = Modifier.fillMaxSize()) {
                            content()
                        }
                    }
                }
            }
        }
    }

    fun setContents(
        sourceContent: @Composable () -> Unit,
        overlayContent: @Composable () -> Unit,
    ) {
        sourceContentState.value = sourceContent
        overlayContentState.value = overlayContent
        sourceCapture.markDirty("setContents")
        invalidate()
    }

    fun setBackdropMode(mode: GlassBackdropMode) {
        if (backdropMode == mode) return
        backdropMode = mode
        backdropViews.values.forEach { it.setBackdropMode(mode) }
        sourceCapture.markDirty("backdropMode=$mode")
        invalidate()
    }

    fun updateTheme(
        colorScheme: ColorScheme,
        typography: Typography,
        shapes: Shapes,
        extendedColors: ExtendedColorScheme,
        mainNavController: NavHostController,
        minimumInteractiveComponentSize: androidx.compose.ui.unit.Dp,
        tabletLayoutEnabled: Boolean,
    ) {
        val newTheme = ThemeState(
            colorScheme = colorScheme,
            typography = typography,
            shapes = shapes,
            extendedColors = extendedColors,
            mainNavController = mainNavController,
            minimumInteractiveComponentSize = minimumInteractiveComponentSize,
            tabletLayoutEnabled = tabletLayoutEnabled,
        )
        val themeChanged = themeState.value != newTheme
        themeState.value = newTheme
        currentColorScheme = colorScheme
        isDarkTheme = colorScheme.background.luminance() < 0.5f
        setBackgroundColor(colorScheme.background.toArgb())
        if (themeChanged) {
            sourceCapture.markDirty("主题变化")
            backdropViews.forEach { (surfaceId, view) ->
                val style = registeredSurfaces[surfaceId]?.style ?: GlassSurfaceStyle.Default
                view.setColors(colorsFor(style.material))
            }
            invalidate()
        }
    }

    override fun updateSurface(
        surfaceId: String,
        style: GlassSurfaceStyle,
        alpha: Float,
        scale: Float,
        bounds: GlassSurfaceBounds,
    ) {
        val next = RegisteredSurface(
            style = style,
            alpha = alpha.coerceIn(0f, 1f),
            scale = scale.coerceAtLeast(0.1f),
            bounds = bounds,
        )
        if (registeredSurfaces[surfaceId] == next) return
        val previous = registeredSurfaces[surfaceId]
        val firstSurface = registeredSurfaces.isEmpty()
        registeredSurfaces[surfaceId] = next
        // [GlassDiag] 临时诊断：注册面数的真实跳变 + 是否走了"第一个玻璃面必须重录"的分支。
        if (previous == null || previous.bounds == null) {
            log(
                "GlassDiag",
                "[host] 注册面 id=$surfaceId firstSurface=$firstSurface " +
                    "之前有没有 bounds=${previous?.bounds != null} " +
                    "bounds=${bounds.left.roundToInt()},${bounds.top.roundToInt()} " +
                    "${bounds.width.roundToInt()}x${bounds.height.roundToInt()} " +
                    "alpha=${alpha.coerceIn(0f, 1f)} 现有面=${registeredSurfaces.keys}",
            )
        }
        if (firstSurface) {
            // Capture is skipped while no surface is registered; the first glass panel must
            // force a fresh source record or it would sample an empty/stale display list.
            sourceCapture.markDirty("第一个玻璃面注册")
        }
        backdropViews[surfaceId]?.let { applySurfaceVisuals(it, next) }
        scheduleSurfaceSync()
    }

    override fun updateSurfaceStyle(
        surfaceId: String,
        style: GlassSurfaceStyle,
        alpha: Float,
        scale: Float,
    ) {
        // 只更新已经注册过的面。**不要**在面还没注册时凭空插入一条注册：
        // GlassSurface 的 SideEffect（本方法）在 Compose 里先于 layout 的 onGloballyPositioned
        // （updateSurface）执行，插入的注册没有 bounds，会让紧随其后的 updateSurface 看到
        // registeredSurfaces 非空 ⇒ firstSurface=false ⇒ 跳过"第一个玻璃面必须强制重录"，
        // 于是面板重新出现时沿用一个已经失效/为空的共享底图（表现为只有底色、没有模糊）。
        // 未注册时直接忽略是安全的：onGloballyPositioned 带的 alpha/style/scale 都是当前值。
        val previous = registeredSurfaces[surfaceId] ?: return
        val next = RegisteredSurface(
            style = style,
            alpha = alpha.coerceIn(0f, 1f),
            scale = scale.coerceAtLeast(0.1f),
            bounds = previous.bounds,
        )
        if (previous == next) return
        registeredSurfaces[surfaceId] = next
        backdropViews[surfaceId]?.let { applySurfaceVisuals(it, next) }
    }

    private fun applySurfaceVisuals(view: GlassBackdropView, registration: RegisteredSurface) {
        view.alpha = registration.alpha
        view.scaleX = registration.scale
        view.scaleY = registration.scale
        view.pivotX = 0f
        view.pivotY = 0f
        view.setSurfaceStyle(registration.style)
        view.setColors(colorsFor(registration.style.material))
    }

    override fun removeSurface(surfaceId: String) {
        // [GlassDiag] 临时诊断：注销时机对不上会留下无 bounds 的幽灵注册。
        log(
            "GlassDiag",
            "[host] 注销面 id=$surfaceId 有注册=${registeredSurfaces.containsKey(surfaceId)} " +
                "有背板=${backdropViews.containsKey(surfaceId)} 其余面=${registeredSurfaces.keys - surfaceId}",
        )
        registeredSurfaces.remove(surfaceId)
        backdropViews.remove(surfaceId)?.let(::removeView)
        scheduleSurfaceSync()
        dumpGlassState("注销后")
    }

    /** [GlassDiag] 临时诊断：把宿主 + 各背板的当前状态打一行，便于逐次对比。 */
    internal fun dumpGlassState(label: String) {
        val surfaces = registeredSurfaces.entries.joinToString(" ") { (id, r) ->
            val b = r.bounds
            "$id(alpha=${"%.2f".format(r.alpha)},bounds=${
                if (b == null) "null" else "${b.width.roundToInt()}x${b.height.roundToInt()}"
            })"
        }
        val backdrops = backdropViews.entries.joinToString(" ") { (id, v) ->
            "$id(drawList=${v.regionHasDisplayList()})"
        }
        log(
            "GlassDiag",
            "[state] $label mode=$backdropMode gen=${sourceCapture.generation} " +
                "captureAvailable=${sourceCapture.nativeCaptureAvailable} " +
                "captureDirty=${sourceCapture.isDirtyForDiagnostics} " +
                "sharedDrawList=${sourceCapture.sharedHasDisplayList()} " +
                "面=[$surfaces] 背板=[$backdrops]",
        )
    }

    fun dispose() {
        sourceContentState.value = null
        overlayContentState.value = null
        registeredSurfaces.clear()
        backdropViews.values.forEach(::removeView)
        backdropViews.clear()
        sourceComposeView.disposeComposition()
        overlayComposeView.disposeComposition()
    }

    override fun onSizeChanged(width: Int, height: Int, oldWidth: Int, oldHeight: Int) {
        super.onSizeChanged(width, height, oldWidth, oldHeight)
        sourceCapture.markDirty("宿主尺寸变化 $oldWidth x $oldHeight → $width x $height")
        backdropViews.values.forEach { it.markSurfacePositionChanged() }
        scheduleSurfaceSync()
        invalidate()
    }

    /** Draws source first, records it once, then draws all native glass views before Compose UI. */
    override fun dispatchDraw(canvas: Canvas) {
        val time = drawingTime
        drawChild(canvas, sourceComposeView, time)
        if (
            backdropMode == GlassBackdropMode.Blur &&
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && registeredSurfaces.isNotEmpty()
        ) {
            // Idle hosts (no glass surfaces) must not re-record the full-screen source.
            val generationBefore = sourceCapture.generation
            sourceCapture.recordIfNeeded()
            if (sourceCapture.generation != generationBefore) {
                // [GlassDiag] 临时诊断：gen 真的前进了才记，说明这一帧重录成功。
                log("GlassDiag", "[host] 本帧重录成功 gen=$generationBefore → ${sourceCapture.generation}")
                // Backdrop views only notice a new source generation while drawing, so they
                // must be redrawn in this same frame to keep the glass in sync.
                backdropViews.values.forEach { view ->
                    if (view.visibility != View.GONE) {
                        view.invalidate()
                    }
                }
            }
        }
        backdropViews.values.forEach { view ->
            if (view.visibility != View.GONE) {
                drawChild(canvas, view, time)
            }
        }
        drawChild(canvas, overlayComposeView, time)
    }

    private fun scheduleSurfaceSync() {
        if (surfaceSyncPosted) return
        surfaceSyncPosted = true
        postOnAnimation {
            surfaceSyncPosted = false
            syncSurfaceViews()
        }
    }

    private fun syncSurfaceViews() {
        // A surface can report an invalid/zero-size bound briefly while its Compose parent is
        // being remeasured. Keep the last native backdrop until GlassSurface explicitly removes
        // the surface; otherwise a later layout pass may not re-register an identical bound.
        val activeIds = registeredSurfaces.keys

        backdropViews.keys
            .filter { it !in activeIds }
            .toList()
            .forEach { surfaceId ->
                // [GlassDiag] 临时诊断
                log("GlassDiag", "[host] 移除原生背板 id=$surfaceId 原因=已不在注册表")
                backdropViews.remove(surfaceId)?.let(::removeView)
            }

        val hostLocation = IntArray(2)
        getLocationInWindow(hostLocation)
        var layoutChanged = false
        registeredSurfaces.forEach { (surfaceId, registration) ->
            val bounds = registration.bounds ?: return@forEach
            if (bounds.width <= 0f || bounds.height <= 0f) return@forEach

            val view = backdropViews.getOrPut(surfaceId) {
                layoutChanged = true
                // [GlassDiag] 临时诊断：新建背板 = 全新实例，lastSourceGeneration 从 -1 起算。
                log("GlassDiag", "[host] 新建原生背板 id=$surfaceId mode=$backdropMode")
                GlassBackdropView(context, registration.style, surfaceId, backdropMode).also {
                    it.setSource(sourceCapture, sourceComposeView)
                    it.setColors(colorsFor(registration.style.material))
                    addView(it, childCount - 1)
                }
            }
            view.setSurfaceStyle(registration.style)
            view.setColors(colorsFor(registration.style.material))
            view.alpha = registration.alpha
            view.scaleX = registration.scale
            view.scaleY = registration.scale
            view.pivotX = 0f
            view.pivotY = 0f

            val left = (bounds.left - hostLocation[0]).roundToInt()
            val top = (bounds.top - hostLocation[1]).roundToInt()
            val width = maxOf(1, bounds.width.roundToInt())
            val height = maxOf(1, bounds.height.roundToInt())
            val currentParams = view.layoutParams as? LayoutParams
            if (
                currentParams == null ||
                currentParams.leftMargin != left ||
                currentParams.topMargin != top ||
                currentParams.width != width ||
                currentParams.height != height
            ) {
                layoutChanged = true
                view.markSurfacePositionChanged()
                view.layoutParams = LayoutParams(width, height).apply {
                    leftMargin = left
                    topMargin = top
                }
            }
        }
        if (layoutChanged) {
            requestLayout()
            // [GlassDiag] 临时诊断：背板发生过增删/挪位就打个状态快照。
            dumpGlassState("背板同步后")
        }
    }

    private fun colorsFor(material: GlassMaterialStyle): GlassSurfaceColors {
        val colorScheme = currentColorScheme ?: return GlassSurfaceColors(
            tint = 0,
            topStroke = 0,
            bottomStroke = 0,
            shadow = 0,
        )
        return GlassSurfaceColors(
            tint = colorScheme.surfaceContainer.copy(alpha = material.tintAlpha).toArgb(),
            topStroke = if (isDarkTheme) 0x06FFFFFF else 0x11000000,
            bottomStroke = if (isDarkTheme) 0x11FFFFFF else 0x20000000,
            shadow = if (isDarkTheme) 0x04FFFFFF else 0x20000000,
        )
    }

    @Composable
    private fun ThemedContent(theme: ThemeState, content: @Composable () -> Unit) {
        CompositionLocalProvider(
            LocalExtendedColors provides theme.extendedColors,
            LocalMainNavController provides theme.mainNavController,
            LocalMinimumInteractiveComponentSize provides theme.minimumInteractiveComponentSize,
            LocalTabletLayoutEnabled provides theme.tabletLayoutEnabled,
        ) {
            MaterialTheme(
                colorScheme = theme.colorScheme,
                typography = theme.typography,
                shapes = theme.shapes,
                content = content,
            )
        }
    }
}
