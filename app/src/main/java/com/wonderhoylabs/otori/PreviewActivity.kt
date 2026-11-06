package com.wonderhoylabs.otori

import android.annotation.SuppressLint
import android.app.Activity
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.Rect
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.Gravity
import android.view.MotionEvent
import android.view.WindowInsets
import android.widget.FrameLayout
import android.widget.TextView
import android.view.ViewGroup.LayoutParams.MATCH_PARENT

/**
 * The island inside an ordinary window, driven by a pretend player. It needs no special access, so the look and
 * motion can be tried on any device. It uses the same drawing and gesture code as the real overlay.
 *
 * Extras for automated comparison: state = idle | compact | expanded, bg = a #RRGGBB colour, paused = true,
 * title, artist, and timeScale (a float, such as 0.25 for quarter speed).
 */
class PreviewActivity : Activity(), IslandHost {
    private lateinit var island: IslandView
    private lateinit var status: TextView
    private val main = Handler(Looper.getMainLooper())
    private var durationMs = 180_000L
    private var positionMs = 42_000L
    private var positionTakenAt = 0L
    private var playing = true
    private var track = 0
    private lateinit var artwork: Artwork
    private var touchBounds = Rect()
    private var tracks = listOf(
        "Otori Preview" to "Wonderhoy Labs",
        "A Much Longer Track Title That Has To Scroll" to "Wonderhoy Labs",
    )

    @SuppressLint("ClickableViewAccessibility")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Lets the look be checked on a locked phone. It shows only this page, and Back returns to the lock screen.
        setShowWhenLocked(true)
        setTurnScreenOn(true)
        val bg = intent.getStringExtra("bg")?.let { runCatching { Color.parseColor(it) }.getOrNull() }
            ?: Color.rgb(0xF2, 0xF2, 0xF7)
        val root = object : FrameLayout(this) {
            override fun onInterceptTouchEvent(ev: MotionEvent) = true
            override fun onTouchEvent(ev: MotionEvent) = routeTouch(ev)
        }
        root.setBackgroundColor(bg)
        status = TextView(this).apply {
            textSize = 15f
            setTextColor(Color.DKGRAY)
            gravity = Gravity.CENTER
            text = getString(R.string.app_name) + " preview\nlong press the island"
        }
        root.addView(status, FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT))
        island = IslandView(this, this)
        island.timeScale = intent.getFloatExtra("timeScale", 1f)
        root.addView(island, FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT))
        setContentView(root)
        window.insetsController?.hide(WindowInsets.Type.statusBars())

        intent.getStringExtra("title")?.let { t ->
            tracks = listOf((t to intent.getStringExtra("artist").orEmpty())) + tracks
        }
        val width = windowManager.maximumWindowMetrics.bounds.width()
        island.setPlacement(IslandService.placementFor(display, width))
        val placeScale = width / Spec.SCREEN_PT
        val art = BitmapFactory.decodeResource(resources, R.drawable.otori_logo)
        artwork = ArtTools.prepare(art, placeScale)
        positionTakenAt = SystemClock.elapsedRealtime()
        playing = intent.getStringExtra("paused") != "true"
        publish()
        when (intent.getStringExtra("state")) {
            "idle" -> island.setResting(Presentation.IDLE)
            "expanded" -> {
                island.setResting(Presentation.COMPACT)
                island.expand()
            }
            else -> island.setResting(Presentation.COMPACT)
        }
    }

    override fun onResume() {
        super.onResume()
        IslandService.setPreviewOpen(true)
    }

    override fun onPause() {
        IslandService.setPreviewOpen(false)
        super.onPause()
    }

    private fun publish() {
        val (title, artist) = tracks[track]
        island.setNowPlaying(
            NowPlaying(
                packageName = packageName,
                title = title,
                artist = artist,
                artwork = artwork,
                durationMs = durationMs,
                positionMs = positionMs,
                positionTakenAt = positionTakenAt,
                speed = 1f,
                isPlaying = playing,
                canSeek = true,
            ),
        )
    }

    private fun routeTouch(ev: MotionEvent): Boolean {
        val inside = touchBounds.contains(ev.rawX.toInt(), ev.rawY.toInt())
        if (ev.actionMasked == MotionEvent.ACTION_DOWN && !inside) {
            island.onOutsideTouch()
            tracking = false
            return true
        }
        if (ev.actionMasked == MotionEvent.ACTION_DOWN) tracking = true
        if (tracking) island.handleTouch(ev)
        return true
    }

    private var tracking = false

    private fun syncPosition() {
        val now = SystemClock.elapsedRealtime()
        if (playing) positionMs = (positionMs + (now - positionTakenAt)).coerceAtMost(durationMs)
        positionTakenAt = now
    }

    override fun openNowPlayingApp() {
        status.text = "tap: would open the music app"
    }

    override fun togglePlayPause() {
        syncPosition()
        playing = !playing
        publish()
    }

    override fun skipNext() {
        track = (track + 1) % tracks.size
        positionMs = 0
        positionTakenAt = SystemClock.elapsedRealtime()
        publish()
    }

    override fun skipPrevious() {
        positionMs = 0
        positionTakenAt = SystemClock.elapsedRealtime()
        publish()
    }

    override fun seekTo(positionMs: Long) {
        this.positionMs = positionMs
        positionTakenAt = SystemClock.elapsedRealtime()
        publish()
    }

    override fun openNotificationShade() {
        status.text = "swipe down: would open the notification shade"
    }

    override fun haptic(strong: Boolean) {
        island.performHapticFeedback(
            if (strong) android.view.HapticFeedbackConstants.LONG_PRESS else android.view.HapticFeedbackConstants.CLOCK_TICK,
        )
    }

    override fun onPresentationChanged(presentation: Presentation, touchBounds: Rect, needsTallWindow: Boolean) {
        this.touchBounds = Rect(touchBounds)
    }

    override fun onMotionSettled(presentation: Presentation) {}

    override fun onSwipeAside(hide: Boolean) {
        island.setResting(if (hide) Presentation.IDLE else Presentation.COMPACT)
    }
}
