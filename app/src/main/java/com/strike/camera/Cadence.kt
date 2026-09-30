package com.strike.camera

import android.media.MediaCodec
import android.os.SystemClock
import com.strike.core.Diagnostics
import com.strike.daemon.DaemonLog

private const val TAG = "Cadence"
private const val REPORT_EVERY_MS = 10_000L

/** Logs an encoder's output rate and presentation-time gaps, the jitter a viewer sees. */
class Cadence(private val name: String, private val targetFps: Int) {

    private var reportAtMs = 0L
    private var lastUs = -1L
    private var count = 0
    private var gapSumUs = 0L
    private var gapMaxUs = 0L
    private var late = 0

    fun onSample(timeUs: Long, flags: Int) {
        if (flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0) return
        val now = SystemClock.elapsedRealtime()
        if (reportAtMs == 0L) reportAtMs = now
        if (lastUs >= 0) {
            val gap = timeUs - lastUs
            gapSumUs += gap
            if (gap > gapMaxUs) gapMaxUs = gap
            if (gap * targetFps > 1_500_000L) late++
        }
        lastUs = timeUs
        count++
        val elapsed = now - reportAtMs
        if (elapsed < REPORT_EVERY_MS) return
        if (Diagnostics.perfLogs()) {
            val gaps = (count - 1).coerceAtLeast(1)
            DaemonLog.d(
                TAG,
                "[perf] $name out=%.1ffps target=$targetFps gap avg=%.1fms max=%.1fms late=$late".format(
                    count * 1000.0 / elapsed, gapSumUs / 1000.0 / gaps, gapMaxUs / 1000.0
                )
            )
        }
        reportAtMs = now
        count = 0
        gapSumUs = 0L
        gapMaxUs = 0L
        late = 0
    }
}
