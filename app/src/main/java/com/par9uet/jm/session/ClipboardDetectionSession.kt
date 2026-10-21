package com.par9uet.jm.session

/** Process-only dismissals: recreated on cold start, never persisted with user settings. */
class ClipboardDetectionSession {
    private val dismissedIds = mutableSetOf<Int>()

    @Synchronized
    fun shouldPrompt(comicId: Int): Boolean = comicId > 0 && comicId !in dismissedIds

    @Synchronized
    fun dismiss(comicId: Int) {
        if (comicId > 0) dismissedIds.add(comicId)
    }
}
