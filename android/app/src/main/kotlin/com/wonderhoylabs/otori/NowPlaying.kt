package com.wonderhoylabs.otori

import android.app.PendingIntent
import android.graphics.Bitmap
import android.graphics.Color
import android.os.SystemClock

/** Artwork prepared for the two sizes the island draws, plus its tint. */
class Artwork(val large: Bitmap?, val small: Bitmap?, val tint: Int) {
	companion object {
		val NONE = Artwork(null, null, Color.WHITE)
	}
}

/**
 * What the island shows: the current media session, or a Live Update from any app. A Live Update has no controls; its
 * progress is positionMs out of durationMs, and it never plays.
 */
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
	val kind: Kind = Kind.MEDIA,
	/** A Live Update's short text, such as "12 min", shown where music shows its waveform. */
	val chip: String? = null,
	/** What tapping a Live Update opens. */
	val contentIntent: PendingIntent? = null,
) {
	enum class Kind { MEDIA, LIVE_UPDATE }

	val isLiveUpdate get() = kind == Kind.LIVE_UPDATE

	fun positionAt(now: Long = SystemClock.elapsedRealtime()): Long {
		val p = if (isPlaying) positionMs + ((now - positionTakenAt) * speed).toLong() else positionMs
		return if (durationMs > 0) p.coerceIn(0, durationMs) else p.coerceAtLeast(0)
	}
}
