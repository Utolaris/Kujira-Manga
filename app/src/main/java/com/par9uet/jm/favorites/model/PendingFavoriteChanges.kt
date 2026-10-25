package com.par9uet.jm.favorites.model

import kotlinx.coroutines.flow.Flow

/** Account-wide pending comic count; null means the persisted queue cannot be read. */
fun interface PendingFavoriteChanges {
    fun observeCount(accountId: Int): Flow<Int?>
}
