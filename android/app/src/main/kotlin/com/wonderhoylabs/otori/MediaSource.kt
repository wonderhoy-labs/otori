package com.wonderhoylabs.otori

import android.content.ComponentName
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.MediaMetadata
import android.media.session.MediaController
import android.media.session.MediaSessionManager
import android.media.session.PlaybackState
import android.net.Uri
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.os.SystemClock

/**
 * Follows the device's media sessions and reports the one to show. Works with any app that publishes a
 * MediaSession, including Apple Music. Reading sessions requires notification access.
 */
class MediaSource(
	private val context: Context,
	private val scale: () -> Float,
	private val onChange: (NowPlaying?) -> Unit,
) {
	private val manager = context.getSystemService(MediaSessionManager::class.java)
	private val component = ComponentName(context, IslandNotificationListener::class.java)
	private val main = Handler(Looper.getMainLooper())
	private val artThread = HandlerThread("island-art").apply { start() }
	private val artHandler = Handler(artThread.looper)

	private val controllers = LinkedHashMap<SessionToken, Tracked>()
	private var current: MediaController? = null
	private var started = false
	private var artKey: Any? = null
	private var artwork = Artwork.NONE

	private inner class Tracked(val controller: MediaController) : MediaController.Callback() {
		override fun onPlaybackStateChanged(state: PlaybackState?) = choose()
		override fun onMetadataChanged(metadata: MediaMetadata?) = choose()
		override fun onSessionDestroyed() = refresh()
	}

	private val sessionsListener = MediaSessionManager.OnActiveSessionsChangedListener { refresh() }

	private val retry = object : Runnable {
		override fun run() {
			if (!tryStart()) main.postDelayed(this, RETRY_MS)
		}
	}

	/** Starts now if notification access is granted, otherwise keeps trying until it is. */
	fun start() {
		if (!tryStart()) main.postDelayed(retry, RETRY_MS)
	}

	fun stop() {
		main.removeCallbacks(retry)
		if (started) manager.removeOnActiveSessionsChangedListener(sessionsListener)
		started = false
		controllers.values.forEach { it.controller.unregisterCallback(it) }
		controllers.clear()
		artThread.quitSafely()
	}

	private fun tryStart(): Boolean {
		if (started) return true
		return try {
			manager.addOnActiveSessionsChangedListener(sessionsListener, component, main)
			started = true
			refresh()
			true
		} catch (_: SecurityException) {
			false
		}
	}

	private fun refresh() {
		val sessions = try {
			manager.getActiveSessions(component)
		} catch (_: SecurityException) {
			emptyList()
		}
		val live = sessions.map { it.sessionToken }.toSet()
		val it = controllers.entries.iterator()
		while (it.hasNext()) {
			val e = it.next()
			if (e.key !in live) {
				e.value.controller.unregisterCallback(e.value)
				it.remove()
			}
		}
		for (c in sessions) {
			if (c.sessionToken !in controllers) {
				val tracked = Tracked(c)
				c.registerCallback(tracked, main)
				controllers[c.sessionToken] = tracked
			}
		}
		choose()
	}

	/** A playing session wins. Otherwise the highest priority session that has something to show. */
	private fun choose() {
		val all = controllers.values.map { it.controller }
		val pick = all.firstOrNull { isPlaying(it.playbackState) }
			?: current?.takeIf { c -> all.any { it.sessionToken == c.sessionToken } && hasContent(c) }
			?: all.firstOrNull { hasContent(it) }
		current = pick
		publish(pick)
	}

	private fun hasContent(c: MediaController): Boolean {
		val state = c.playbackState?.state ?: return false
		if (state == PlaybackState.STATE_NONE || state == PlaybackState.STATE_STOPPED ||
			state == PlaybackState.STATE_ERROR
		) {
			return false
		}
		return c.metadata != null
	}

	private fun isPlaying(state: PlaybackState?): Boolean = when (state?.state) {
		PlaybackState.STATE_PLAYING, PlaybackState.STATE_BUFFERING,
		PlaybackState.STATE_FAST_FORWARDING, PlaybackState.STATE_REWINDING,
		PlaybackState.STATE_SKIPPING_TO_NEXT, PlaybackState.STATE_SKIPPING_TO_PREVIOUS,
		PlaybackState.STATE_SKIPPING_TO_QUEUE_ITEM,
		-> true

		else -> false
	}

	private fun publish(c: MediaController?) {
		if (c == null || !hasContent(c)) {
			onChange(null)
			return
		}
		val meta = c.metadata!!
		val state = c.playbackState!!
		loadArtworkIfChanged(c, meta)
		onChange(
			NowPlaying(
				packageName = c.packageName,
				title = meta.getString(MediaMetadata.METADATA_KEY_TITLE)
					?: meta.getString(MediaMetadata.METADATA_KEY_DISPLAY_TITLE).orEmpty(),
				artist = meta.getString(MediaMetadata.METADATA_KEY_ARTIST)
					?: meta.getString(MediaMetadata.METADATA_KEY_ALBUM_ARTIST)
					?: meta.getString(MediaMetadata.METADATA_KEY_DISPLAY_SUBTITLE).orEmpty(),
				artwork = artwork,
				durationMs = meta.getLong(MediaMetadata.METADATA_KEY_DURATION),
				positionMs = state.position,
				positionTakenAt = state.lastPositionUpdateTime.takeIf { it > 0 } ?: SystemClock.elapsedRealtime(),
				speed = if (state.playbackSpeed > 0f) state.playbackSpeed else 1f,
				isPlaying = isPlaying(state),
				canSeek = state.actions and PlaybackState.ACTION_SEEK_TO != 0L,
			),
		)
	}

	private fun loadArtworkIfChanged(c: MediaController, meta: MediaMetadata) {
		val bitmap = meta.getBitmap(MediaMetadata.METADATA_KEY_ART)
			?: meta.getBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART)
			?: meta.getBitmap(MediaMetadata.METADATA_KEY_DISPLAY_ICON)
		val uri = meta.getString(MediaMetadata.METADATA_KEY_ART_URI)
			?: meta.getString(MediaMetadata.METADATA_KEY_ALBUM_ART_URI)
			?: meta.getString(MediaMetadata.METADATA_KEY_DISPLAY_ICON_URI)
		// Bitmaps cross the binder as new objects, so identity comes from the track instead.
		val key = listOf(
			c.sessionToken,
			meta.getString(MediaMetadata.METADATA_KEY_TITLE),
			meta.getString(MediaMetadata.METADATA_KEY_ALBUM),
			bitmap?.width,
			bitmap?.height,
			uri,
		)
		if (key == artKey) return
		artKey = key
		val token = c.sessionToken
		artHandler.post {
			val src = bitmap ?: uri?.let { decodeUri(it) }
			val prepared = ArtTools.prepare(src, scale())
			main.post {
				if (artKey == key) {
					artwork = prepared
					if (current?.sessionToken == token) publish(current)
				}
			}
		}
	}

	private fun decodeUri(uri: String): Bitmap? = try {
		context.contentResolver.openInputStream(Uri.parse(uri))?.use { BitmapFactory.decodeStream(it) }
	} catch (_: Exception) {
		null
	}

	val controller: MediaController? get() = current

	private companion object {
		const val RETRY_MS = 2000L
	}
}

private typealias SessionToken = android.media.session.MediaSession.Token
