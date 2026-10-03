package android.os
object SystemClock {
    var step = 0L
    private var time = 0L
    fun elapsedRealtime(): Long = if (step == 0L) System.nanoTime() / 1_000_000 else { time += step; time }
}
