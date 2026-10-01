package dev.homedroid

import kotlin.math.abs

/** Small luma grid, with global brightness changes removed and two-frame confirmation. */
class MotionDetector {
    private var previous: IntArray? = null
    private var consecutive = 0
    fun changed(samples: IntArray, thresholdPercent: Int): Boolean {
        val old = previous
        previous = samples
        if (old == null || old.size != samples.size) return false
        val brightness = (samples.sum() - old.sum()).toDouble() / samples.size
        val changed = samples.indices.count { abs(samples[it] - old[it] - brightness) > 20 }
        consecutive = if (changed * 100 >= samples.size * thresholdPercent) consecutive + 1 else 0
        return consecutive >= 2
    }
}
