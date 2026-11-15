package org.schabi.newpipe.player.gesture

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class InternalVolumeGestureTest {
    private fun apply(vararg distances: Float): Int {
        val gesture = InternalVolumeGesture().apply { start(50, 100) }
        return distances.fold(50) { _, delta -> gesture.scroll(delta) }
    }

    @Test
    fun `small split deltas agree with one coalesced movement in both directions`() {
        for (sign in listOf(-1f, 1f)) {
            assertEquals(apply(20f * sign), apply(*FloatArray(40) { 0.5f * sign }))
            assertEquals(apply(7f * sign), apply(*FloatArray(7) { sign }))
        }
    }

    @Test
    fun `reversals cross anchor without accumulating quantization errors`() {
        assertEquals(apply(1f), apply(6f, -5f))
        assertEquals(apply(-1f), apply(-6f, 5f))
        assertEquals(50, apply(7f, -7f))
        assertEquals(apply(-9f), apply(7f, -8f, -8f))
        assertEquals(apply(9f), apply(-7f, 8f, 8f))
    }

    @Test
    fun `existing four pixel step is retained for small positive and negative movement`() {
        assertEquals(50, apply(3.5f))
        assertEquals(50, apply(-3.5f))
        assertEquals(51, apply(4f))
        assertEquals(49, apply(-4f))
    }

    @Test
    fun `saturation discards overshoot and fractional outward movement at both bounds`() {
        val gesture = InternalVolumeGesture().apply { start(50, 100) }
        assertEquals(100, gesture.scroll(1000f))
        repeat(8) { assertEquals(100, gesture.scroll(0.5f)) }
        assertEquals(100, gesture.scroll(-3.5f))
        assertEquals(99, gesture.scroll(-0.5f))
        assertEquals(0, gesture.scroll(-1000f))
        repeat(8) { assertEquals(0, gesture.scroll(-0.5f)) }
        assertEquals(0, gesture.scroll(3.5f))
        assertEquals(1, gesture.scroll(0.5f))
    }

    @Test
    fun `new gesture uses its new volume and drops all fractional movement`() {
        val gesture = InternalVolumeGesture().apply { start(50, 100) }
        assertEquals(50, gesture.scroll(3f))
        gesture.reset()
        assertFalse(gesture.isActive)
        gesture.start(20, 100)
        assertEquals(20, gesture.scroll(1f))
        assertEquals(21, gesture.scroll(3f))
    }
}
