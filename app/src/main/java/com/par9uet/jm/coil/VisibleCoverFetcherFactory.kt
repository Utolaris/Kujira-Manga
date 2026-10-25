package com.par9uet.jm.coil

import android.net.Uri
import coil.ImageLoader
import coil.fetch.Fetcher
import coil.request.Options
import com.par9uet.jm.core.concurrent.BackgroundWork
import kotlinx.coroutines.withContext

/** Memory hits bypass fetchers. Existing disk entries remain available while the grid moves. */
internal class VisibleCoverFetcherFactory : Fetcher.Factory<Uri> {
    private val scheduler = CoverFetchScheduler()
    private val work = BackgroundWork()

    @OptIn(coil.annotation.ExperimentalCoilApi::class)
    override fun create(data: Uri, options: Options, imageLoader: ImageLoader): Fetcher? {
        if (data.scheme != "https" && data.scheme != "http") return null
        val gate = options.parameters.value<CoverWorkGate>(CoverWorkGateParameter) ?: return null
        val ownIndex = imageLoader.components.fetcherFactories.indexOfFirst { it.first === this }
        if (ownIndex < 0) return null
        val delegate = imageLoader.components.newFetcher(data, options, imageLoader, ownIndex + 1)?.first
            ?: return null
        return Fetcher {
            withContext(work.context) {
                gate.awaitReady()
                val cached = options.diskCachePolicy.readEnabled &&
                    imageLoader.diskCache?.openSnapshot(options.diskCacheKey ?: data.toString())
                        ?.use { true } == true
                scheduler.fetch(gate, cached) { delegate.fetch() }
            }
        }
    }
}
