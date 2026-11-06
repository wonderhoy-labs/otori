package com.wonderhoylabs.otori

import android.graphics.Bitmap
import android.graphics.Color
import android.os.SystemClock

/** Artwork prepared for the two sizes the island draws, plus its tint. */
class Artwork(val large: Bitmap?, val small: Bitmap?, val tint: Int) {
    companion object {
        val NONE = Artwork(null, null, Color.WHITE)
    }
}

/** What the island shows for the current media session. */
data class NowPlaying(
    val packageName: String,
    val title: String,
    val artist: String,
    val artwork: Artwork,
    val durationMs: Long,
    val positionMs: Long,
    val positionTakenAt: Long,
    val speed: Float,
    val isPlaying: Boolean,
    val canSeek: Boolean,
) {
    fun positionAt(now: Long = SystemClock.elapsedRealtime()): Long {
        val p = if (isPlaying) positionMs + ((now - positionTakenAt) * speed).toLong() else positionMs
        return if (durationMs > 0) p.coerceIn(0, durationMs) else p.coerceAtLeast(0)
    }
}
