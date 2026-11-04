package io.github.realkhfan.foco.camera.lens

import android.os.SystemClock
import android.util.Log

/**
 * In-memory ring buffer (last ~150 lines) recording the precise timeline
 * of 0.5x Ultra-Wide camera requests, CameraX/Camera2 lifecycle states,
 * and session configuration attempts.
 *
 * Each line is formatted as: "+<ms since the 0.5x request> <event>"
 * and is also mirrored to Logcat under tag "LensDiag".
 */
object LensDebugLog {
    private const val TAG = "LensDiag"
    private const val MAX_LINES = 150
    private val buffer = ArrayDeque<String>(MAX_LINES)
    private var requestOriginUptimeMs: Long = 0L

    @Synchronized
    fun markRequestStart(event: String) {
        requestOriginUptimeMs = SystemClock.uptimeMillis()
        logInternal("+0ms $event")
    }

    @Synchronized
    fun log(event: String) {
        val delta = if (requestOriginUptimeMs > 0L) {
            SystemClock.uptimeMillis() - requestOriginUptimeMs
        } else {
            0L
        }
        val line = "+${delta}ms $event"
        logInternal(line)
    }

    private fun logInternal(line: String) {
        if (buffer.size >= MAX_LINES) {
            buffer.removeFirst()
        }
        buffer.addLast(line)
        Log.i(TAG, line)
    }

    @Synchronized
    fun dump(): String {
        return if (buffer.isEmpty()) "No lens logs recorded yet." else buffer.joinToString("\n")
    }

    @Synchronized
    fun clear() {
        buffer.clear()
        requestOriginUptimeMs = 0L
    }
}
