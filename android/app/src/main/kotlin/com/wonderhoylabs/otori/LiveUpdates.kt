package com.wonderhoylabs.otori

import android.app.Notification
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.drawable.Icon
import android.os.Build
import android.os.SystemClock
import android.service.notification.StatusBarNotification

/**
 * Android 16's Live Updates, from any app: ongoing notifications that asked to be promoted and that Android promoted.
 * This is Otori's contract with other apps. An app that posts one, with a title, and ideally ProgressStyle and short
 * critical text, shows in the island like music does, and tapping it opens the notification's content intent.
 */
object LiveUpdates {
	/** The island's view of a notification, or null if it isn't a Live Update. */
	fun from(context: Context, sbn: StatusBarNotification, scale: Float): NowPlaying? {
		if (Build.VERSION.SDK_INT < 36) return null
		val n = sbn.notification
		if (n.flags and Notification.FLAG_PROMOTED_ONGOING == 0) return null
		val extras = n.extras
		val title = extras.getCharSequence(Notification.EXTRA_TITLE)?.toString()?.takeIf { it.isNotBlank() } ?: return null
		val text = extras.getCharSequence(Notification.EXTRA_TEXT)?.toString().orEmpty()

		var progress = extras.getInt(Notification.EXTRA_PROGRESS)
		var max = extras.getInt(Notification.EXTRA_PROGRESS_MAX)
		var indeterminate = extras.getBoolean(Notification.EXTRA_PROGRESS_INDETERMINATE)
		val style = runCatching { Notification.Builder.recoverBuilder(context, n).style }.getOrNull()
		if (style is Notification.ProgressStyle) {
			progress = style.progress
			max = style.progressMax
			indeterminate = style.isProgressIndeterminate
		}
		if (indeterminate) max = 0

		val accent = if (n.color != Notification.COLOR_DEFAULT && n.color != 0) n.color else Color.WHITE
		return NowPlaying(
			packageName = sbn.packageName,
			title = title,
			artist = text,
			artwork = artworkOf(context, n.smallIcon, accent, scale),
			durationMs = max.toLong().coerceAtLeast(0),
			positionMs = progress.toLong().coerceIn(0, max.toLong().coerceAtLeast(0)),
			positionTakenAt = SystemClock.elapsedRealtime(),
			speed = 0f,
			isPlaying = false,
			canSeek = false,
			kind = NowPlaying.Kind.LIVE_UPDATE,
			chip = n.shortCriticalText?.takeIf { it.isNotBlank() },
			contentIntent = n.contentIntent,
		)
	}

	/** The app's small icon, drawn in its accent colour on clear, at the two sizes the island uses. */
	private fun artworkOf(context: Context, icon: Icon?, accent: Int, scale: Float): Artwork {
		val drawable = icon?.loadDrawable(context) ?: return Artwork(null, null, accent)
		drawable.setTint(accent)
		val size = (Spec.Media.ART_SIZE * scale).toInt().coerceAtLeast(1)
		val bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
		val inset = size / 6
		drawable.setBounds(inset, inset, size - inset, size - inset)
		drawable.draw(Canvas(bitmap))
		val small = ArtTools.scaled(bitmap, (Spec.Compact.ART_SIZE * scale).toInt().coerceAtLeast(1))
		return Artwork(bitmap, small, accent)
	}
}
