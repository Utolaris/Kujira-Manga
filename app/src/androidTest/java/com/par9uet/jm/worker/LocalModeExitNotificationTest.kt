package com.par9uet.jm.worker

import android.app.Notification
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.par9uet.jm.utils.LOCAL_MODE_SYNC_CHANNEL_ID
import com.par9uet.jm.utils.localModeSyncNotification
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class LocalModeExitNotificationTest {
    @Test fun foregroundNoticeStaysOngoingAndShowsCurrentStage() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val notification = localModeSyncNotification(context, "正在同步收藏")

        assertEquals(LOCAL_MODE_SYNC_CHANNEL_ID, notification.channelId)
        assertEquals("正在同步收藏", notification.extras.getCharSequence(Notification.EXTRA_TEXT))
        assertTrue(notification.flags and Notification.FLAG_ONGOING_EVENT != 0)
    }
}
