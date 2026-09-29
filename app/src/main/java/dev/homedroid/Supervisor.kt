package dev.homedroid

import android.os.SystemClock
import android.system.ErrnoException
import android.system.Os
import android.system.OsConstants.SIGKILL
import android.system.OsConstants.SIGTERM
import java.io.File
import java.io.FileWriter

/**
 * Runs each [Spec] on its own thread and restarts it with exponential backoff when it exits.
 *
 * Every daemon starts in its own session (setsid), and stops are sent to that whole process
 * group. Signalling only the direct child isn't enough: proot ignores SIGTERM, and killing it
 * orphans the program it was tracing.
 */
class Supervisor(private val paths: Paths, specs: List<Spec>) {
    enum class State { STARTING, RUNNING, BACKOFF, STOPPED }

    val daemons = specs.map { Daemon(it) }

    fun start() = daemons.forEach { it.start() }

    /** Sends SIGTERM to everything, then SIGKILLs whatever is still alive after a grace period. */
    fun stop() {
        daemons.forEach { it.signalStop() }
        daemons.forEach { it.awaitStop(GRACE_MS) }
    }

    inner class Daemon(val spec: Spec) {
        @Volatile var state = State.STARTING; private set
        @Volatile var restarts = 0; private set
        val log = LogRing(200)

        @Volatile private var process: Process? = null
        @Volatile private var pgid = 0
        @Volatile private var stopping = false
        @Volatile private var restartRequested = false
        private var thread: Thread? = null

        fun start() {
            thread = Thread(::loop, "sv-${spec.name}").apply { isDaemon = true; start() }
        }

        fun signalStop() {
            stopping = true
            if (!killGroup(SIGTERM)) process?.destroy()
            thread?.interrupt()
        }

        /** Stops the daemon's processes; the loop starts it again straight away. */
        fun restart() {
            restartRequested = true
            log.add("! restart requested")
            if (!killGroup(SIGTERM)) process?.destroy()
        }

        fun awaitStop(ms: Long) {
            thread?.join(ms)
            killGroup(SIGKILL)
            process?.takeIf { it.isAlive }?.destroyForcibly()
        }

        /** Signals the daemon's process group; false if there's no group to signal. */
        private fun killGroup(signal: Int): Boolean {
            val id = pgid.takeIf { it > 0 } ?: return false
            return try {
                Os.kill(-id, signal)
                true
            } catch (_: ErrnoException) {
                false
            }
        }

        private fun loop() {
            var backoff = MIN_BACKOFF_MS
            while (!stopping) {
                val t0 = SystemClock.elapsedRealtime()
                val rc = try {
                    runOnce()
                } catch (e: Exception) {
                    log.add("! ${e.message}")
                    -1
                }
                if (stopping) break
                if (restartRequested) {
                    restartRequested = false
                    backoff = MIN_BACKOFF_MS
                    continue
                }
                if (SystemClock.elapsedRealtime() - t0 > STABLE_MS) backoff = MIN_BACKOFF_MS
                restarts++
                state = State.BACKOFF
                log.add("! exited with $rc, restarting in ${backoff / 1000}s")
                try {
                    Thread.sleep(backoff)
                } catch (_: InterruptedException) {
                    break
                }
                backoff = (backoff * 2).coerceAtMost(MAX_BACKOFF_MS)
            }
            state = State.STOPPED
        }

        private fun runOnce(): Int {
            val pb = ProcessBuilder(listOf(SETSID) + spec.command).directory(paths.home).redirectErrorStream(true)
            pb.environment().putAll(paths.env())
            pb.environment().putAll(spec.env)
            val p = pb.start()
            process = p
            // setsid execs in place, so the child's pid is also its process group id.
            pgid = pidOf(p)
            if (stopping) signalStop()
            state = State.RUNNING
            p.outputStream.close()

            val file = File(paths.logs, "${spec.name}.log")
            var out = FileWriter(file, true)
            var written = file.length()
            try {
                p.inputStream.bufferedReader().forEachLine { line ->
                    log.add(line)
                    if (written > MAX_LOG_BYTES) {
                        out.close()
                        file.renameTo(File(file.path + ".1"))
                        out = FileWriter(file)
                        written = 0
                    }
                    out.write(line)
                    out.write("\n")
                    out.flush()
                    written += line.length + 1
                }
            } finally {
                out.close()
            }
            val rc = p.waitFor()
            // Don't leave anything the daemon spawned running into the next attempt.
            killGroup(SIGKILL)
            pgid = 0
            return rc
        }
    }

    companion object {
        private const val SETSID = "/system/bin/setsid"

        // Android has no Process.pid(); its Process.toString() is "Process[pid=123, ...]".
        private val PID = Regex("""pid=(\d+)""")

        private fun pidOf(p: Process) = PID.find(p.toString())?.groupValues?.get(1)?.toInt() ?: 0

        private const val MIN_BACKOFF_MS = 1_000L
        private const val MAX_BACKOFF_MS = 60_000L
        private const val STABLE_MS = 60_000L
        private const val GRACE_MS = 3_000L
        private const val MAX_LOG_BYTES = 512 * 1024L
    }
}

/** Fixed-size in-memory tail of a daemon's output, for the UI. */
class LogRing(private val capacity: Int) {
    private val lines = ArrayDeque<String>(capacity)

    @Synchronized
    fun add(line: String) {
        if (lines.size == capacity) lines.removeFirst()
        lines.addLast(line)
    }

    @Synchronized
    fun tail(n: Int): List<String> = lines.takeLast(n)
}
