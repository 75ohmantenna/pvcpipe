package org.schabi.newpipe.player.gesture

/** Quantize total gesture displacement, not each detector callback's fractional delta. */
internal class InternalVolumeGesture {
    var isActive = false
        private set
    private var anchor = 0
    private var maximum = 0
    private var distance = 0.0

    fun start(progress: Int, max: Int) {
        anchor = progress.coerceIn(0, max)
        maximum = max
        distance = 0.0
        isActive = true
    }

    fun reset() {
        isActive = false
        distance = 0.0
    }

    fun scroll(delta: Float): Int {
        check(isActive)
        distance += delta.toDouble()
        val displacement = distance / REDUCE_DISTANCE_BY_FACTOR
        val position = anchor + displacement
        if (position <= 0 || position >= maximum) {
            // Reversing at a bound must not first repay invisible overscroll.
            anchor = if (position <= 0) 0 else maximum
            distance = 0.0
            return anchor
        }
        return anchor + displacement.toInt()
    }

    companion object {
        // Preserve the existing sensitivity. Device-specific tuning is a separate policy.
        private const val REDUCE_DISTANCE_BY_FACTOR = 4
    }
}
