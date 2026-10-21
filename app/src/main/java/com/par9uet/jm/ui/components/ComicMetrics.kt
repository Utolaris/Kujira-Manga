package com.par9uet.jm.ui.components

/** The upstream like count is unreliable until the view count has synchronized. */
internal fun comicMetricText(readCount: Int, value: Int): String = if (readCount == 0) "-" else value.toString()
