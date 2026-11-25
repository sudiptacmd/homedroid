package dev.homedroid

/**
 * Burn-in protection for the Overview, kept free of Android so it can be tested: when to switch
 * to the dim standby view, and where its text sits each minute.
 *
 * OLED pixels age with use, so a server phone that shows the same bright layout for weeks gets
 * a ghost image. The standby view is mostly black, its text dim and small, and it moves to a
 * new spot every minute so no pixel stays lit for long.
 */
object BurnIn {
    const val DEFAULT_MINUTES = 2
    const val MOVE_EVERY_MS = 60_000L

    /** Choices offered in Settings, in minutes. */
    val MINUTES = listOf(1, 2, 5, 10)

    /** Whether the untouched screen should be in standby now. */
    fun shouldStandby(enabled: Boolean, lastTouchMs: Long, nowMs: Long, minutes: Int): Boolean =
        enabled && nowMs - lastTouchMs >= minutes.coerceAtLeast(1) * 60_000L

    /** Milliseconds until standby starts (0 if it's due); for scheduling the check. */
    fun msUntilStandby(lastTouchMs: Long, nowMs: Long, minutes: Int): Long =
        (lastTouchMs + minutes.coerceAtLeast(1) * 60_000L - nowMs).coerceAtLeast(0)

    /** Which drift step [nowMs] falls in; the position changes when this does. */
    fun step(sinceMs: Long, nowMs: Long): Long = ((nowMs - sinceMs).coerceAtLeast(0)) / MOVE_EVERY_MS

    /**
     * Top-left corner for content of [contentW]×[contentH] inside [areaW]×[areaH] at drift
     * [step]: a pseudo-random spot that always fits (0 when it can't), never the same as the
     * step before, and well spread so the same pixels aren't reused minute after minute.
     */
    fun position(step: Long, areaW: Int, areaH: Int, contentW: Int, contentH: Int): Pair<Int, Int> {
        val freeX = (areaW - contentW).coerceAtLeast(0)
        val freeY = (areaH - contentH).coerceAtLeast(0)
        // A 4×5 grid of cells, visited in a scrambled order: consecutive steps land in different cells.
        val cells = 20
        val cell = ((step * 7 + 3) % cells).toInt() // 7 is coprime with 20: every cell, once per cycle
        val col = cell % 4
        val row = cell / 4
        // A small jitter inside the cell, so each cycle differs from the last.
        val cycle = step / cells
        val jx = hash(cycle * 31 + cell) % 1000 / 1000f
        val jy = hash(cycle * 17 + cell + 7) % 1000 / 1000f
        val x = ((col + jx) / 4f * freeX).toInt().coerceIn(0, freeX)
        val y = ((row + jy) / 5f * freeY).toInt().coerceIn(0, freeY)
        return x to y
    }

    private fun hash(v: Long): Int {
        var h = v * -0x61c8864680b583ebL
        h = h xor (h ushr 29)
        return (h and 0x7FFFFFFF).toInt()
    }
}
