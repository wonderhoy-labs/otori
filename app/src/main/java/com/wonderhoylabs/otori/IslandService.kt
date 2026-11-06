package com.wonderhoylabs.otori

import android.accessibilityservice.AccessibilityService
import android.annotation.SuppressLint
import android.app.ActivityOptions
import android.app.KeyguardManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.res.Configuration
import android.graphics.PixelFormat
import android.graphics.Rect
import android.graphics.RectF
import android.hardware.display.DisplayManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.VibrationEffect
import android.os.VibratorManager
import android.view.Display
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent

/**
 * Hosts the island above everything else. An accessibility overlay is the only window type an ordinary app can
 * draw above the status bar, and it is exempt from Android's touch-passthrough blocking.
 *
 * There are two windows. A large, untouchable one renders the island and its shadow. A small transparent one
 * matches the island's bounds and takes touches, so that touches anywhere else reach the apps below.
 */
class IslandService : AccessibilityService(), IslandHost {

    private lateinit var windows: WindowManager
    private lateinit var island: IslandView
    private lateinit var touch: View
    private lateinit var renderParams: WindowManager.LayoutParams
    private lateinit var touchParams: WindowManager.LayoutParams
    private var media: MediaSource? = null
    private var attached = false
    private var nowPlaying: NowPlaying? = null
    private var foregroundPackage: String? = null
    private var hiddenByRotation = false
    private val main = Handler(Looper.getMainLooper())
    private val pauseLinger = Runnable { updateResting() }
    private var pausedAt = 0L
    private var userHidden = false
    private var previewOpen = false
    private var presentation = Presentation.IDLE
    private val touchBounds = Rect()
    private val statusBar = StatusBarProbe(this)
    private val remeasure = object : Runnable {
        override fun run() {
            measureStatusBar()
            if (presentation == Presentation.COMPACT) main.postDelayed(this, REMEASURE_MS)
        }
    }

    // On the lock screen iOS shows its own player and leaves the island idle.
    private val screenReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.action == Intent.ACTION_SCREEN_OFF) island.collapse()
            updateResting()
        }
    }

    override fun onServiceConnected() {
        windows = getSystemService(WindowManager::class.java)
        island = IslandView(this, this)
        touch = object : View(this) {
            @SuppressLint("ClickableViewAccessibility")
            override fun onTouchEvent(event: MotionEvent): Boolean {
                if (event.actionMasked == MotionEvent.ACTION_OUTSIDE) {
                    island.onOutsideTouch()
                    return true
                }
                return island.handleTouch(event)
            }
        }
        touch.importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        renderParams = overlayParams(
            WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
        ).apply {
            width = WindowManager.LayoutParams.MATCH_PARENT
            title = "Otori"
        }
        touchParams = overlayParams(
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH,
        ).apply { title = "OtoriTouch" }

        val place = computePlacement()
        island.setPlacement(place)
        renderParams.height = island.shortWindowHeight()
        try {
            windows.addView(island, renderParams)
            windows.addView(touch, touchParams)
        } catch (_: WindowManager.BadTokenException) {
            // The service was switched off again while starting. Android reconnects it if it's switched back on.
            if (island.isAttachedToWindow) windows.removeViewImmediate(island)
            return
        }
        attached = true
        instance = this
        applyPlacement()

        registerReceiver(
            screenReceiver,
            IntentFilter().apply {
                addAction(Intent.ACTION_SCREEN_OFF)
                addAction(Intent.ACTION_SCREEN_ON)
                addAction(Intent.ACTION_USER_PRESENT)
            },
        )
        media = MediaSource(this, { computePlacement().scale }) { np -> onNowPlaying(np) }.also { it.start() }
        measureStatusBar()
    }

    private fun overlayParams(flags: Int) = WindowManager.LayoutParams(
        WindowManager.LayoutParams.WRAP_CONTENT,
        WindowManager.LayoutParams.WRAP_CONTENT,
        WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
        flags or
            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
            WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS or
            WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED,
        PixelFormat.TRANSLUCENT,
    ).apply {
        gravity = Gravity.TOP or Gravity.START
        layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
        fitInsetsTypes = 0
        fitInsetsSides = 0
    }

    override fun onDestroy() {
        media?.stop()
        media = null
        main.removeCallbacksAndMessages(null)
        if (instance === this) instance = null
        if (attached) {
            attached = false
            unregisterReceiver(screenReceiver)
            windows.removeViewImmediate(touch)
            windows.removeViewImmediate(island)
        }
        super.onDestroy()
    }

    // ---- Placement ----

    /** Centres the pill on the camera hole, at 1 pt = displayWidth / 393. */
    private fun computePlacement(): Placement = placementFor(
        getSystemService(DisplayManager::class.java).getDisplay(Display.DEFAULT_DISPLAY),
        windows.maximumWindowMetrics.bounds.width(),
    )

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        if (attached) applyPlacement()
    }

    private fun applyPlacement() {
        island.setPlacement(computePlacement())
        // In landscape the camera is at the side, and there is no status bar for the island to live in.
        val bounds = windows.maximumWindowMetrics.bounds
        hiddenByRotation = bounds.width() > bounds.height()
        applyVisibility()
    }

    private fun applyVisibility() {
        val visibility = if (hiddenByRotation || previewOpen) View.GONE else View.VISIBLE
        island.visibility = visibility
        touch.visibility = visibility
    }

    // ---- Media ----

    private fun onNowPlaying(np: NowPlaying?) {
        val wasPlaying = nowPlaying?.isPlaying == true
        if (np?.title != nowPlaying?.title || np?.packageName != nowPlaying?.packageName) userHidden = false
        nowPlaying = np
        island.setNowPlaying(np)
        main.removeCallbacks(pauseLinger)
        if (attached) applyTouchWindow()
        if (np != null && !np.isPlaying) {
            if (wasPlaying || pausedAt == 0L) pausedAt = System.currentTimeMillis()
            val left = pausedAt + Spec.Timing.PAUSE_LINGER_MS - System.currentTimeMillis()
            if (left > 0) main.postDelayed(pauseLinger, left)
        } else {
            pausedAt = 0L
        }
        updateResting()
    }

    /** Fits the compact island between the status bar's icons. */
    private fun measureStatusBar() {
        if (!attached) return
        val width = windows.maximumWindowMetrics.bounds.width()
        val clearance = statusBar.measure(computePlacement().centerX, width)
        island.setStatusBarClearance(clearance?.leftEnd, clearance?.rightStart)
    }

    private fun updateResting() {
        val np = nowPlaying
        val locked = getSystemService(KeyguardManager::class.java).isKeyguardLocked
        val show = np != null && !locked && !userHidden && np.packageName != foregroundPackage &&
            (np.isPlaying || System.currentTimeMillis() - pausedAt < Spec.Timing.PAUSE_LINGER_MS)
        // Measure first, so the island opens at the right size.
        if (show) measureStatusBar()
        island.setResting(if (show) Presentation.COMPACT else Presentation.IDLE)
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event?.eventType != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) return
        val pkg = event.packageName?.toString() ?: return
        // The shade and keyboard do not count as an app coming to the front.
        if (pkg == "com.android.systemui" || pkg == packageName || pkg.contains("inputmethod")) return
        if (pkg != foregroundPackage) {
            foregroundPackage = pkg
            updateResting()
            statusBarMayHaveChanged()
        }
    }

    override fun onInterrupt() {}

    /** The status bar animates its icons into place, so this looks again once they should have settled. */
    private fun statusBarMayHaveChanged() {
        if (presentation != Presentation.COMPACT) return
        main.removeCallbacks(remeasure)
        main.postDelayed(remeasure, SETTLE_MS)
    }

    // ---- IslandHost ----

    private val controller get() = media?.controller

    override fun openNowPlayingApp() {
        val c = controller ?: return
        val options = ActivityOptions.makeBasic().apply {
            if (Build.VERSION.SDK_INT >= 34) {
                setPendingIntentBackgroundActivityStartMode(ActivityOptions.MODE_BACKGROUND_ACTIVITY_START_ALLOWED)
            }
        }.toBundle()
        val session = c.sessionActivity
        try {
            if (session != null) {
                session.send(this, 0, null, null, null, null, options)
                return
            }
        } catch (_: PendingIntent.CanceledException) {
            // Fall through to launching the app.
        }
        packageManager.getLaunchIntentForPackage(c.packageName)?.let {
            it.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            startActivity(it)
        }
    }

    override fun togglePlayPause() {
        val c = controller ?: return
        if (nowPlaying?.isPlaying == true) c.transportControls.pause() else c.transportControls.play()
    }

    override fun skipNext() {
        controller?.transportControls?.skipToNext()
    }

    override fun skipPrevious() {
        controller?.transportControls?.skipToPrevious()
    }

    override fun seekTo(positionMs: Long) {
        controller?.transportControls?.seekTo(positionMs)
    }

    override fun openNotificationShade() {
        performGlobalAction(GLOBAL_ACTION_NOTIFICATIONS)
    }

    override fun haptic(strong: Boolean) {
        val vibrator = getSystemService(VibratorManager::class.java).defaultVibrator
        val effect = if (strong) VibrationEffect.EFFECT_HEAVY_CLICK else VibrationEffect.EFFECT_TICK
        vibrator.vibrate(VibrationEffect.createPredefined(effect))
    }

    override fun onPresentationChanged(presentation: Presentation, touchBounds: Rect, needsTallWindow: Boolean) {
        this.presentation = presentation
        this.touchBounds.set(touchBounds)
        if (!attached) return
        applyTouchWindow()
        main.removeCallbacks(remeasure)
        if (presentation == Presentation.COMPACT) main.postDelayed(remeasure, REMEASURE_MS)
        // Grow at once for a transition; shrink only once the island has settled.
        if (needsTallWindow || presentation != Presentation.IDLE) setRenderHeight(island.tallWindowHeight())
    }

    override fun onMotionSettled(presentation: Presentation) {
        if (attached && presentation != Presentation.EXPANDED) setRenderHeight(island.shortWindowHeight())
    }

    override fun onSwipeAside(hide: Boolean) {
        if (!hide && nowPlaying == null) return
        userHidden = hide
        haptic(false)
        updateResting()
        applyTouchWindow()
    }

    /**
     * At rest the island is out of sight inside the camera hole, so it takes no touches and the status bar works as
     * usual. The exception is music the user swiped away, which a sideways swipe there brings back.
     */
    private fun applyTouchWindow() {
        touchParams.x = touchBounds.left
        touchParams.y = touchBounds.top
        touchParams.width = touchBounds.width()
        touchParams.height = touchBounds.height()
        val takesTouches = presentation != Presentation.IDLE || (userHidden && nowPlaying != null)
        val flag = WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
        touchParams.flags = if (takesTouches) touchParams.flags and flag.inv() else touchParams.flags or flag
        windows.updateViewLayout(touch, touchParams)
    }

    private fun setRenderHeight(height: Int) {
        if (renderParams.height == height) return
        renderParams.height = height
        windows.updateViewLayout(island, renderParams)
    }

    companion object {
        private const val REMEASURE_MS = 4000L
        private const val SETTLE_MS = 600L
        // Cleared in onDestroy, so this can't outlive the service.
        @SuppressLint("StaticFieldLeak")
        private var instance: IslandService? = null

        fun onStatusBarMayHaveChanged() {
            instance?.let { service -> service.main.post { service.statusBarMayHaveChanged() } }
        }

        /** The preview draws its own island, so the overlay steps aside while it is open. */
        fun setPreviewOpen(open: Boolean) {
            instance?.let {
                it.previewOpen = open
                if (it.attached) it.applyVisibility()
            }
        }

        fun placementFor(display: Display?, displayWidth: Int): Placement {
            val scale = displayWidth / Spec.SCREEN_PT
            val cutout = display?.cutout
            val bounds = RectF()
            val path = cutout?.cutoutPath
            if (path != null) {
                path.computeBounds(bounds, true)
                // Only a central hole at the top counts. Its centre sets the pill's centre line.
                val central = kotlin.math.abs(bounds.centerX() - displayWidth / 2f) < displayWidth * 0.03f
                if (!bounds.isEmpty && central && bounds.top < displayWidth / 4f) {
                    val top = bounds.centerY() - Spec.PILL_H * scale / 2f
                    val hole = kotlin.math.min(bounds.width(), bounds.height()) / scale
                    if (top >= 0f) return Placement(scale, bounds.centerX(), top, hole * Spec.IDLE_HOLE_FILL, hole / 2f)
                }
            }
            return Placement(scale, displayWidth / 2f, Spec.IOS_TOP * scale, Spec.IDLE_D_NO_HOLE, 0f)
        }
    }
}
