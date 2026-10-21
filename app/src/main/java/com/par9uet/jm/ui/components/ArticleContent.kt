package com.par9uet.jm.ui.components

import com.par9uet.jm.ui.components.MotionTextButton as TextButton

import androidx.compose.foundation.background
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import com.par9uet.jm.ui.interaction.ImageViewportTransform
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import coil.compose.AsyncImage
import coil.request.ImageRequest
import com.par9uet.jm.coil.currentCoverImageLoader
import com.par9uet.jm.coil.CoverImageHostResolver
import com.par9uet.jm.coil.nextCoverCandidateUrl
import com.par9uet.jm.image.classifyImageHostFailure
import com.par9uet.jm.image.ImageHostFailureKind
import com.par9uet.jm.ui.models.LocalRemoteImageHost
import com.par9uet.jm.dinner.model.ArticleBlock
import org.koin.compose.getKoin

/** 文章的 HTML 块使用原生文本/图片，阅读区域沿用应用配色并保留长文留白。 */
@Composable
internal fun ArticleBlockContent(block: ArticleBlock, onLink: ((String) -> Unit)? = null) {
    val colors = MaterialTheme.colorScheme
    val uri = LocalUriHandler.current
    when (block) {
        is ArticleBlock.Text -> {
            val text = buildAnnotatedString {
                block.runs.forEach { run ->
                    val start = length
                    append(run.text)
                    addStyle(SpanStyle(
                        fontWeight = if (run.bold) FontWeight.Bold else null,
                        fontStyle = if (run.italic) FontStyle.Italic else null,
                        fontFamily = if (run.code) FontFamily.Monospace else null,
                        background = if (run.code) colors.surfaceContainerHighest else Color.Unspecified,
                        textDecoration = if (run.strike) TextDecoration.LineThrough else null,
                    ), start, length)
                    run.link?.let { link -> addLink(LinkAnnotation.Clickable(link, TextLinkStyles(SpanStyle(
                        color = colors.primary, textDecoration = TextDecoration.Underline,
                    )), linkInteractionListener = {
                        if (onLink != null) onLink(link) else runCatching { uri.openUri(link) }
                    }), start, length) }
                }
            }
            val size = when (block.heading) { 1 -> 28; 2 -> 24; 3 -> 21; in 4..6 -> 19; else -> 17 }
            SelectionContainer {
                Text(text, modifier = Modifier.fillMaxWidth().then(if (block.quote) Modifier
                    .background(colors.surfaceContainer).padding(16.dp) else Modifier),
                    fontFamily = FontFamily.Serif, fontSize = size.sp, lineHeight = (size * 1.65f).sp,
                    fontWeight = if (block.heading > 0) FontWeight.SemiBold else FontWeight.Normal,
                    color = if (block.quote) colors.onSurfaceVariant else colors.onSurface)
            }
        }
        is ArticleBlock.Image -> ArticleImage(block.url, block.alt,
            onClick = block.link?.let { link -> { if (onLink != null) onLink(link) }; })
        is ArticleBlock.Code -> SelectionContainer {
            Text(block.text, modifier = Modifier.fillMaxWidth().background(colors.surfaceContainer)
                .horizontalScroll(rememberScrollState()).padding(16.dp), fontFamily = FontFamily.Monospace,
                fontSize = 14.sp, lineHeight = 23.sp)
        }
        is ArticleBlock.Table -> Column(Modifier.horizontalScroll(rememberScrollState())) {
            block.rows.forEachIndexed { rowIndex, row ->
                Row(Modifier.background(if (rowIndex == 0) colors.surfaceContainerHigh else colors.surface)) {
                    row.forEach { cell -> Text(cell, Modifier.width(180.dp).padding(12.dp),
                        fontSize = 16.sp, lineHeight = 25.sp,
                        fontWeight = if (rowIndex == 0) FontWeight.SemiBold else FontWeight.Normal) }
                }
                HorizontalDivider()
            }
        }
        ArticleBlock.Rule -> HorizontalDivider(Modifier.padding(vertical = 12.dp))
    }
}

/** 普通文章 URL 不进入 JM 封面编号/CDN 回退管线；共用 DoH 图片加载器。 */
@Composable
internal fun ArticleImage(
    url: String, alt: String, modifier: Modifier = Modifier, zoomEnabled: Boolean = true,
    onClick: (() -> Unit)? = null, resolver: CoverImageHostResolver = getKoin().get(),
    initialRatio: Float = 4f / 3f, onAspectRatio: ((Float) -> Unit)? = null,
) {
    val loader = currentCoverImageLoader()
    val context = LocalContext.current
    val remoteHost = LocalRemoteImageHost.current
    val generation by resolver.networkGeneration.collectAsState()
    val candidates = remember(url, remoteHost, generation) { resolver.articleUrls(url, remoteHost) }
    val cacheKey = remember(url, remoteHost) { resolver.articleCacheKey(url, remoteHost) }
    var attempts by remember(url, remoteHost, generation) { mutableStateOf(emptySet<String>()) }
    val candidate = nextCoverCandidateUrl(candidates, attempts)
    // Lazy waterfall cells can be disposed offscreen; keep their measured ratio on return.
    var ratio by rememberSaveable(url) { mutableFloatStateOf(initialRatio) }
    var loaded by remember(url) { mutableStateOf(false) }
    var failed by remember(url) { mutableStateOf(false) }
    var attempt by remember(url) { mutableIntStateOf(0) }
    var displayed by remember(url) { mutableStateOf<Painter?>(null) }
    var successfulUrl by remember(url) { mutableStateOf<String?>(null) }
    var expanded by remember(url) { mutableStateOf(false) }
    val uri = LocalUriHandler.current
    LaunchedEffect(url, remoteHost, generation) { failed = false }
    val request = remember(context, cacheKey, candidate, attempt) {
        candidate?.let { ImageRequest.Builder(context).data(it)
            .memoryCacheKey(cacheKey).diskCacheKey(cacheKey).build() }
    }
    Column(modifier) {
        Box(Modifier.fillMaxWidth().aspectRatio(ratio).background(MaterialTheme.colorScheme.surfaceContainer)
            .clickable(enabled = onClick != null || (loaded && zoomEnabled)) {
                if (onClick != null) onClick() else expanded = true
            }, contentAlignment = Alignment.Center) {
            key(url, attempt, candidate) {
                AsyncImage(model = request, imageLoader = loader, contentDescription = alt,
                    contentScale = ContentScale.Fit, modifier = Modifier.fillMaxSize().graphicsLayer { alpha = if (displayed == null) 1f else 0f },
                    onSuccess = { state ->
                        val size = state.painter.intrinsicSize
                        if (size.width > 0 && size.height > 0) {
                            ratio = (size.width / size.height).coerceIn(0.03f, 20f)
                            onAspectRatio?.invoke(ratio)
                        }
                        loaded = true; failed = false
                        displayed = state.painter
                        successfulUrl = candidate
                        candidate?.let(resolver::recordHealthy)
                    }, onError = { state ->
                        val kind = classifyImageHostFailure(state.result.throwable)
                        if (kind != ImageHostFailureKind.CANCELLED && candidate != null) {
                            if (kind == ImageHostFailureKind.HOST_FAILURE) resolver.recordHostFailure(candidate)
                            val nextAttempts = attempts + candidate
                            attempts = nextAttempts
                            failed = nextCoverCandidateUrl(candidates, nextAttempts) == null
                        }
                    })
            }
            displayed?.let { Image(it, contentDescription = null, contentScale = ContentScale.Fit, modifier = Modifier.fillMaxSize()) }
            if (!loaded && !failed) ReimuLoadingIndicator()
            if (failed && !loaded) Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text("图片加载失败", color = MaterialTheme.colorScheme.onSurfaceVariant)
                if (zoomEnabled || onClick == null) {
                    TextButton(onClick = { failed = false; attempts = emptySet(); attempt++ }) { Text("重试图片") }
                    TextButton(onClick = { runCatching { uri.openUri(url) } }) { Text("打开原图") }
                }
            }
        }
        if (alt.isNotBlank() && alt != "文章插图") Text(alt, Modifier.padding(top = 8.dp),
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
    if (expanded) Dialog(onDismissRequest = { expanded = false }, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        val transform = remember(url) { ImageViewportTransform() }
        val zoomRequest = remember(context, url, successfulUrl, cacheKey) {
            ImageRequest.Builder(context).data(successfulUrl ?: url)
                .memoryCacheKey(cacheKey).diskCacheKey(cacheKey).build()
        }
        Box(Modifier.fillMaxSize().background(Color.Black)) {
            AsyncImage(model = zoomRequest, imageLoader = loader, contentDescription = alt, contentScale = ContentScale.Fit,
                modifier = Modifier.fillMaxSize()
                    .onSizeChanged { transform.resize(Size(it.width.toFloat(), it.height.toFloat()), ratio) }
                    .pointerInput(url, transform) {
                        detectTransformGestures { _, pan, scale, _ -> transform.transform(scale, pan) }
                    }.graphicsLayer {
                        scaleX = transform.scale; scaleY = transform.scale
                        translationX = transform.offset.x; translationY = transform.offset.y
                    })
            TextButton(onClick = { expanded = false }, modifier = Modifier.align(Alignment.TopEnd).statusBarsPadding()) {
                Text("关闭原图", color = Color.White)
            }
        }
    }
}
