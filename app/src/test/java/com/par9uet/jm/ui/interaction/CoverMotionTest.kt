package com.par9uet.jm.ui.interaction

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import org.junit.Assert.*
import org.junit.Test

class CoverMotionTest {
    @Test fun `tablet whitespace cannot start a direct drag but the cover and existing phone gestures can`() {
        val cover = Rect(100f, 140f, 420f, 566f)
        assertFalse(canStartDirectDetailDrag(true, Offset(180f, 900f), cover))
        assertFalse(canStartDirectDetailDrag(true, Offset(450f, 200f), cover))
        assertFalse(canStartDirectDetailDrag(true, Offset(180f, 200f), null))
        assertTrue(canStartDirectDetailDrag(true, Offset(180f, 200f), cover))
        assertTrue(canStartDirectDetailDrag(false, Offset(180f, 900f), cover))
    }

    @Test fun `offscreen detail covers stay absent while partially visible covers can fly`() {
        val viewport = Rect(0f, 0f, 400f, 800f)
        assertFalse(coverHasVisiblePixels(Rect(16f, -300f, 196f, -60f), viewport))
        assertFalse(coverHasVisiblePixels(Rect(16f, -240f, 196f, 0f), viewport))
        assertFalse(coverHasVisiblePixels(Rect.Zero, viewport))
        assertFalse(coverHasVisiblePixels(null, viewport))
        assertTrue(coverHasVisiblePixels(Rect(16f, -100f, 196f, 140f), viewport))
    }
    @Test fun `a nearly completed side gesture still has time to reach a moved source cover`() {
        val start = Rect(16f, 100f, 196f, 340f)
        val moved = Rect(220f, 600f, 340f, 760f)
        assertEquals(128, coverExitDuration(.01f, start, moved, fading = false))
        assertEquals(256, coverExitDuration(1f, start, moved, fading = false))
        assertEquals(0, coverExitDuration(0f, start, start, fading = false))
        assertEquals(256, coverExitDuration(0f, start, moved, fading = true))
        assertEquals(128, coverExitDuration(.5f, null, null, fading = false))
    }
    @Test fun `return follows the geometric path in reverse with equal travel per time step`() {
        val source = Rect(220f, 600f, 340f, 760f)
        val detail = Rect(16f, 100f, 196f, 340f)
        repeat(21) { step ->
            val time = step / 20f
            val forward = coverEntryBounds(source, detail, 1f - time)
            val backward = coverEntryBounds(detail, source, time)
            assertEquals(forward.left, backward.left, .001f)
            assertEquals(forward.top, backward.top, .001f)
            assertEquals(forward.width, backward.width, .001f)
        }
    }
    @Test fun `missing source cover shrinks toward the viewport center and keeps its aspect ratio`() {
        val target = coverCenterExitBounds(Rect(16f, 100f, 196f, 340f), Size(400f, 800f))
        assertEquals(Offset(200f, 400f), target.center)
        assertEquals(99f, target.width, .001f)
        assertEquals(132f, target.height, .001f)
    }
    @Test fun `return starts at the cover location under the current drag transform`() {
        val original = Rect(16f, 100f, 196f, 340f)
        assertEquals(original, coverPoseBounds(original, CoverPose(), Size(400f, 800f)))
        val rect = coverPoseBounds(original, coverDragPose(Offset(20f, 160f), 800f), Size(400f, 800f))
        assertEquals(63.6f, rect.left, .001f)
        assertEquals(305f, rect.top, .001f)
        assertEquals(153f, rect.width, .001f)
        assertEquals(204f, rect.height, .001f)
    }
    @Test fun `entry lands on the actual detail cover rather than expanding to the viewport`() {
        val source = Rect(220f, 600f, 340f, 760f)
        val destination = Rect(16f, 100f, 196f, 340f)
        assertEquals(source, coverEntryBounds(source, destination, 0f))
        assertEquals(destination, coverEntryBounds(source, destination, 1f))
        assertEquals(Rect(118f, 350f, 268f, 550f), coverEntryBounds(source, destination, .5f))
        assertEquals(source, coverEntryBounds(source, destination, -1f))
        assertEquals(destination, coverEntryBounds(source, destination, 2f))
    }
    @Test fun `phone and tablet cover flights are monotonic and keep the cover aspect ratio`() {
        val source = Rect(20f, 500f, 140f, 660f)
        listOf(Rect(16f, 100f, 196f, 340f), Rect(16f, 100f, 336f, 100f + 320f / .75f)).forEach { destination ->
            var lastWidth = source.width
            repeat(21) { frame ->
                val fraction = CoverEasing.transform(frame / 20f)
                val rect = coverEntryBounds(source, destination, fraction)
                assertTrue(rect.width >= lastWidth)
                assertTrue(rect.width <= destination.width)
                assertEquals(.75f, rect.width / rect.height, .0001f)
                lastWidth = rect.width
            }
        }
    }
    @Test fun `background fills from the top using the exact same fraction as the cover`() {
        listOf(-1f, 0f, .2f, .5f, 1f, 2f).forEach { progress ->
            val outline = DetailRevealShape(progress).createOutline(Size(400f, 800f), LayoutDirection.Ltr, Density(1f))
            assertEquals(Rect(0f, 0f, 400f, 800f * progress.coerceIn(0f, 1f)), outline.rect)
        }
    }
    @Test fun `drag directly follows finger while shrinking and fading the background`() {
        val pose = coverDragPose(Offset(20f, 160f), 800f)
        assertEquals(20f, pose.x)
        assertEquals(160f, pose.y)
        assertEquals(.85f, pose.scale, .0001f)
        assertEquals(.575f, pose.dim, .0001f)
        assertEquals(.7f, coverDragPose(Offset(0f, -900f), 800f).scale, .0001f)
    }
    @Test fun `reversing past the original drag point only adds light resistance`() {
        val reversed = coverDragPose(Offset(15f, -100f), 800f, direction = 1f)
        assertEquals(1f, reversed.scale)
        assertEquals(-20f, reversed.y)
        assertEquals(1f, reversed.dim)
    }
    @Test fun `release distance is relative to viewport and fling must follow the same direction`() {
        assertFalse(shouldDismissCover(80f, 0f, 800f, 2f))
        assertTrue(shouldDismissCover(150f, 0f, 800f, 2f))
        assertTrue(shouldDismissCover(-150f, 0f, 800f, 2f))
        assertTrue(shouldDismissCover(20f, 2500f, 800f, 2f))
        assertFalse(shouldDismissCover(20f, -2500f, 800f, 2f))
        assertFalse(shouldDismissCover(0f, 4000f, 800f, 2f))
        assertFalse(shouldDismissCover(20f, 1800f, 800f, 2f))
    }
    @Test fun `cancel interpolates from the current pose rather than snapping to a start state`() {
        val start = coverDragPose(Offset(25f, 180f), 800f)
        assertEquals(start, start.interpolate(CoverPose(), 0f))
        assertEquals(CoverPose(), start.interpolate(CoverPose(), 1f))
        assertEquals(90f, start.interpolate(CoverPose(), .5f).y, .001f)
    }
}
