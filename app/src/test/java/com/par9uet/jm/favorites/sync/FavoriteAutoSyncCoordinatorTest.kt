package com.par9uet.jm.favorites.sync

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class FavoriteAutoSyncCoordinatorTest {
    private class Clock(var elapsed: Long = 0L, var wall: Long = 1_000_000L)
    private fun coordinator(clock: Clock) = FavoriteAutoSyncCoordinator(
        timeSource = { clock.elapsed }, wallTimeSource = { clock.wall },
    )

    @Test
    fun `first automatic request starts immediately`() {
        assertEquals(FavoriteAutoRequestResult.StartNow(0), coordinator(Clock()).request(0, false))
    }

    @Test
    fun `every folder shares sixty seconds after successful completion`() {
        val clock = Clock()
        val policy = coordinator(clock)
        assertEquals(60_000L, FAVORITE_AUTO_SYNC_INTERVAL_MILLIS)
        policy.onSyncSucceeded()
        clock.elapsed = 59_999L
        for (folder in listOf(0, 1, 2, 99)) {
            assertEquals(FavoriteAutoRequestResult.Skipped, policy.request(folder, false))
        }
        clock.elapsed = 60_000L
        assertEquals(FavoriteAutoRequestResult.StartNow(99), policy.request(99, false))
    }

    @Test
    fun `long running synchronization starts the cooldown when it completes`() {
        val clock = Clock()
        val policy = coordinator(clock)
        policy.request(0, false)
        clock.elapsed = 120_000L
        assertEquals(FavoriteAutoRequestResult.Skipped, policy.request(2, true))
        policy.onSyncSucceeded()
        clock.elapsed = 179_999L
        assertEquals(FavoriteAutoRequestResult.Skipped, policy.request(2, false))
        clock.elapsed = 180_000L
        assertEquals(FavoriteAutoRequestResult.StartNow(2), policy.request(2, false))
    }

    @Test
    fun `a request without a successful completion does not open a cooldown`() {
        val policy = coordinator(Clock())
        policy.request(0, false)
        assertEquals(FavoriteAutoRequestResult.StartNow(3), policy.request(3, false))
    }

    @Test
    fun `persisted completion suppresses a new process until the minute boundary`() {
        val clock = Clock(wall = 1_059_999L)
        val restarted = coordinator(clock)
        assertEquals(FavoriteAutoRequestResult.Skipped, restarted.request(9, false, 1_000_000L))
        clock.wall++
        assertEquals(FavoriteAutoRequestResult.StartNow(9), restarted.request(9, false, 1_000_000L))
    }

    @Test
    fun `wall clock changes do not shorten the in process cooldown or freeze a new process`() {
        val clock = Clock()
        val policy = coordinator(clock)
        policy.onSyncSucceeded()
        clock.elapsed = 1L
        clock.wall += 3_600_000L
        assertEquals(FavoriteAutoRequestResult.Skipped, policy.request(0, false))
        assertTrue(coordinator(clock).request(0, false, clock.wall + 3_600_000L) is FavoriteAutoRequestResult.StartNow)
    }

    @Test
    fun `account reset clears only the previous accounts in process cooldown`() {
        val policy = coordinator(Clock())
        policy.onSyncSucceeded()
        policy.reset()
        assertEquals(FavoriteAutoRequestResult.StartNow(9), policy.request(9, false))
    }
}
