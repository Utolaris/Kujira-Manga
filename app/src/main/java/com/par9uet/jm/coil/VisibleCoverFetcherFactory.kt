package com.par9uet.jm.coil

import android.net.Uri
import coil.ImageLoader
import coil.decode.DataSource
import coil.decode.ImageSource
import coil.fetch.Fetcher
import coil.fetch.SourceResult
import coil.request.Options
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.withContext
import okio.FileSystem
import okio.Path.Companion.toOkioPath

/**
 * Memory hits bypass fetchers. Local thumbnail hits and existing disk entries use a dedicated
 * disk permit pool so slow network downloads never stall cached covers.
 */
@OptIn(ExperimentalCoroutinesApi::class)
internal class VisibleCoverFetcherFactory(
    private val thumbnailCache: CoverThumbnailCache? = null,
) : Fetcher.Factory<Uri> {
    private val scheduler = CoverFetchScheduler(maxParallelism = 2, diskMaxParallelism = 4)
    private val fetchDispatcher = Dispatchers.IO.limitedParallelism(6)

    @OptIn(coil.annotation.ExperimentalCoilApi::class)
    override fun create(data: Uri, options: Options, imageLoader: ImageLoader): Fetcher? {
        if (data.scheme != "https" && data.scheme != "http") return null
        val gate = options.parameters.value<CoverWorkGate>(CoverWorkGateParameter) ?: return null
        val ownIndex = imageLoader.components.fetcherFactories.indexOfFirst { it.first === this }
        if (ownIndex < 0) return null
        val delegate = imageLoader.components.newFetcher(data, options, imageLoader, ownIndex + 1)?.first
            ?: return null
        return Fetcher {
            withContext(fetchDispatcher) {
                gate.awaitReady()
                val cacheKey = options.diskCacheKey ?: data.toString()
                // Fast path: serve compact local WebP thumbnail directly for grid-sized requests.
                if (options.diskCachePolicy.readEnabled &&
                    thumbnailCache != null &&
                    thumbnailCache.isEligibleSize(options)
                ) {
                    val thumb = thumbnailCache.getIfPresent(cacheKey)
                    if (thumb != null) {
                        return@withContext scheduler.fetch(gate, cached = true) {
                            SourceResult(
                                source = ImageSource(
                                    file = thumb.toOkioPath(),
                                    fileSystem = FileSystem.SYSTEM,
                                    diskCacheKey = null,
                                ),
                                mimeType = "image/webp",
                                dataSource = DataSource.DISK,
                            )
                        }
                    }
                }
                val cached = options.diskCachePolicy.readEnabled &&
                    imageLoader.diskCache?.openSnapshot(cacheKey)
                        ?.use { true } == true
                scheduler.fetch(gate, cached) { delegate.fetch() }
            }
        }
    }
}
