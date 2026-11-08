package com.wonderhoylabs.otori

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.BitmapShader
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.RenderEffect
import android.graphics.RenderNode
import android.graphics.Shader
import android.graphics.Typeface
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.Choreographer
import android.view.MotionEvent
import android.view.View
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.sin

enum class Presentation { IDLE, COMPACT, EXPANDED }

/** What the island needs from whatever hosts it: the system overlay, or the preview screen. */
interface IslandHost {
	fun openNowPlayingApp()
	fun togglePlayPause()
	fun skipNext()
	fun skipPrevious()
	fun seekTo(positionMs: Long)
	fun openNotificationShade()
	fun haptic(strong: Boolean)
	fun onPresentationChanged(presentation: Presentation, touchBounds: Rect, needsTallWindow: Boolean)
	fun onMotionSettled(presentation: Presentation)

	/** A sideways swipe: the user tucks the activity away, or brings it back from the idle island. */
	fun onSwipeAside(hide: Boolean)
}

/** Where the island sits on this display. */
data class Placement(
	val scale: Float,
	val centerX: Float,
	val pillTop: Float,
	val idleDiameter: Float,
	val holeRadius: Float,
)

/**
 * Draws and animates the island. All layout is in points, multiplied by [Placement.scale]. Motion comes from the
 * springs and timelines measured from the real island; see Under the hood in the README.
 */
@SuppressLint("ViewConstructor")
class IslandView(context: Context, private val host: IslandHost) : View(context) {

	private var place = Placement(
		context.resources.displayMetrics.widthPixels / Spec.SCREEN_PT,
		context.resources.displayMetrics.widthPixels / 2f,
		Spec.IOS_TOP,
		Spec.IDLE_D_NO_HOLE,
		0f,
	)

	var presentation = Presentation.IDLE
		private set

	/** Slows the animation clock, for recording and inspecting motion. Motion is unchanged apart from its speed. */
	var timeScale = 1f

	private enum class Motion { NONE, EXPAND, COLLAPSE, APPEAR, DISAPPEAR }

	private var motion = Motion.NONE
	private var motionTime = 0f
	private var collapseTarget = Spec.COMPACT_W
	private var collapsePhaseTwo = false

	private val widthPt = Spring(Spec.IDLE_D_NO_HOLE)
	private val heightPt = Spring(Spec.IDLE_D_NO_HOLE)

	// How far the compact island reaches to each side of the camera.
	private var compactHalf = Spec.COMPACT_W / 2f
	private val press = Spring(1f)
	private val playMorph = Spring(0f)
	private val skipBack = Spring(0f)
	private val skipForward = Spring(0f)
	private val barGrow = Spring(0f)
	private val trackFade = Spring(1f)
	private val waveform = Waveform()

	// Content state, low-passed so that interrupted transitions never jump.
	private var compactAlpha = 0f
	private var compactBlur = 0f
	private var expandedAlpha = 0f
	private var expandedBlur = 0f
	private var textAlpha = 0f
	private var topOffset = 0f
	private var heightBump = 0f
	private var shadowProgress = 0f

	private var nowPlaying: NowPlaying? = null
	private var shownArtwork: Artwork = Artwork.NONE
	private var expandedSince = 0f
	private var clock = 0f
	private var lastFrameNanos = 0L
	private var ticking = false
	private var scrubFraction: Float? = null

	private val islandPath = Path()
	private val shapePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Spec.Colors.BLACK }
	private val shadowPath = Path()
	private val shadowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.BLACK }
	private val shadowNode = RenderNode("islandShadow")
	private var shadowW = -1f
	private var shadowH = -1f
	private val compactNode = RenderNode("islandCompact")
	private val expandedNode = RenderNode("islandExpanded")

	private val inter = Fonts(context)
	private val titleRow =
		TextRow(textPaint(Spec.Type.TITLE_SIZE, inter.title, Spec.Colors.TITLE, Spec.Type.TITLE_TRACKING))
	private val artistRow =
		TextRow(textPaint(Spec.Type.ARTIST_SIZE, inter.artist, Spec.Colors.ARTIST, Spec.Type.ARTIST_TRACKING))
	private val timePaint = textPaint(Spec.Type.TIME_SIZE, inter.time, Spec.Colors.TIME, 0f)
	private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
	private val artPaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
	private val artMatrix = Matrix()
	private val glyph = Path()
	private val artPath = Path()
	private val rect = RectF()
	private val outRect = Rect()
	private var barShader: Shader? = null
	private var barShaderKey = 0L

	private enum class Control { NONE, BACK, PLAY, FORWARD, BAR, INFO }

	private var activeControl = Control.NONE
	private val controlHighlight = Spring(0f)
	private var downX = 0f
	private var downY = 0f
	private var gestureHandled = false
	private val handler = Handler(Looper.getMainLooper())

	private val longPress = Runnable {
		if (presentation == Presentation.COMPACT && !gestureHandled) {
			gestureHandled = true
			host.haptic(true)
			press.animateTo(1f, 0.35f, 0.8f)
			show(Presentation.EXPANDED)
		}
	}
	private val autoCollapse = Runnable {
		if (presentation == Presentation.EXPANDED && activeControl == Control.NONE) collapse()
	}

	/** The presentation the content alone asks for, ignoring a press or an expansion. */
	private var restingPresentation = Presentation.IDLE

	private val frameCallback = Choreographer.FrameCallback { nanos -> onFrame(nanos) }

	init {
		// The island is decoration over other windows, and must not capture focus or a11y traversal.
		importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS
		setLayerType(LAYER_TYPE_NONE, null)
	}

	fun setPlacement(p: Placement) {
		place = p
		shadowW = -1f
		if (presentation == Presentation.IDLE && motion == Motion.NONE) {
			widthPt.snapTo(p.idleDiameter)
			heightPt.snapTo(p.idleDiameter)
		}
		invalidate()
		publishBounds()
	}

	/**
	 * Fits the compact island between the status bar's items, down to what its own content needs. It always stays
	 * centred on the camera, so the side with less room sets the width of both.
	 */
	fun setStatusBarClearance(leftEnd: Int?, rightStart: Int?) {
		val c = Spec.Compact
		val s = place.scale
		val most = Spec.COMPACT_W / 2f
		val fewest = place.holeRadius + c.CAMERA_GAP + max(c.ART_INSET + c.ART_SIZE, c.WAVE_INSET + waveWidth())
		val left = leftEnd?.let { (place.centerX - it) / s - c.ICON_GAP } ?: most
		val right = rightStart?.let { (it - place.centerX) / s - c.ICON_GAP } ?: most
		val half = minOf(left, right).coerceIn(fewest, most)
		if (abs(half - compactHalf) < 0.75f) return
		compactHalf = half
		if (motion == Motion.COLLAPSE && !collapsePhaseTwo) {
			collapseTarget = compactWidth()
		} else if (presentation == Presentation.COMPACT) {
			widthPt.animateTo(compactWidth(), RESIZE_RESPONSE, RESIZE_DAMPING)
			publishBounds()
			ensureTicking()
		}
	}

	private fun compactWidth() = compactHalf * 2f

	private fun waveWidth() = (waveform.count - 1) * Spec.Compact.BAR_PITCH + Spec.Compact.BAR_W

	/** Bounds that fit any state of the island and its shadow, in pixels. */
	fun tallWindowHeight(): Int =
		((place.pillTop / place.scale + Spec.EXPANDED_H + Spec.SHADOW_ROOM) * place.scale).toInt()

	fun shortWindowHeight(): Int = ((place.pillTop / place.scale + Spec.PILL_H + 8f) * place.scale).toInt()

	fun setNowPlaying(np: NowPlaying?) {
		val previous = nowPlaying
		nowPlaying = np
		if (np != null) {
			shownArtwork = np.artwork
			titleRow.set(np.title)
			artistRow.set(np.artist)
			playMorph.animateTo(if (np.isPlaying) 0f else 1f, 0.32f, 0.8f)
			waveform.setPlaying(np.isPlaying)
			barShader = null
		}
		if (previous != null && np != null && previous.title != np.title) {
			// A new track fades in over the old one's place, instead of cutting.
			trackFade.snapTo(0f)
			trackFade.animateTo(1f, 0.42f, 1f)
		}
		if (previous?.title != np?.title) expandedSince = clock
		ensureTicking()
		invalidate()
	}

	/** The content's own wish, COMPACT or IDLE, applied unless the user has it expanded. */
	fun setResting(next: Presentation) {
		restingPresentation = next
		if (presentation != Presentation.EXPANDED) {
			show(next)
		} else if (next == Presentation.IDLE && nowPlaying == null) {
			show(Presentation.IDLE)
		}
	}

	fun collapse() {
		if (presentation == Presentation.EXPANDED) show(restingPresentation)
	}

	fun expand() {
		if (nowPlaying != null) show(Presentation.EXPANDED)
	}

	private fun show(next: Presentation) {
		if (next == presentation) return
		val from = presentation
		presentation = next
		motionTime = 0f
		val m = Spec.Motion
		when {
			next == Presentation.EXPANDED -> {
				motion = Motion.EXPAND
				expandedSince = clock
				widthPt.animateTo(Spec.EXPANDED_W, m.EXPAND_W_RESPONSE, m.EXPAND_W_DAMPING, m.EXPAND_W_DELAY)
				heightPt.animateTo(Spec.EXPANDED_H, m.EXPAND_H_RESPONSE, m.EXPAND_H_DAMPING, m.EXPAND_H_DELAY)
				scheduleAutoCollapse()
			}

			from == Presentation.EXPANDED -> {
				motion = Motion.COLLAPSE
				collapseTarget = if (next == Presentation.COMPACT) compactWidth() else place.idleDiameter
				collapsePhaseTwo = false
				widthPt.animateTo(
					m.COLLAPSE_W1_TARGET,
					m.COLLAPSE_W1_RESPONSE,
					m.COLLAPSE_W1_DAMPING,
					m.COLLAPSE_W1_DELAY,
				)
				heightPt.animateTo(Spec.PILL_H, m.COLLAPSE_H_RESPONSE, m.COLLAPSE_H_DAMPING, m.COLLAPSE_H_DELAY)
				handler.removeCallbacks(autoCollapse)
				scrubFraction = null
				activeControl = Control.NONE
			}

			next == Presentation.COMPACT -> {
				motion = Motion.APPEAR
				widthPt.animateTo(compactWidth(), m.APPEAR_RESPONSE, m.APPEAR_DAMPING, m.APPEAR_DELAY)
				heightPt.animateTo(Spec.PILL_H, m.APPEAR_RESPONSE, m.APPEAR_DAMPING, m.APPEAR_DELAY)
			}

			else -> {
				motion = Motion.DISAPPEAR
				widthPt.animateTo(place.idleDiameter, m.DISAPPEAR_RESPONSE, m.DISAPPEAR_DAMPING, m.DISAPPEAR_DELAY)
				heightPt.animateTo(place.idleDiameter, m.DISAPPEAR_RESPONSE, m.DISAPPEAR_DAMPING, m.DISAPPEAR_DELAY)
			}
		}
		publishBounds()
		ensureTicking()
	}

	private fun publishBounds() {
		val s = place.scale
		val w = when (presentation) {
			Presentation.IDLE -> place.idleDiameter
			Presentation.COMPACT -> compactWidth()
			Presentation.EXPANDED -> Spec.EXPANDED_W
		}
		val h = when (presentation) {
			Presentation.IDLE -> place.idleDiameter
			Presentation.COMPACT -> Spec.PILL_H
			Presentation.EXPANDED -> Spec.EXPANDED_H
		}
		// A little slop around the pill, like the real island's generous touch target.
		val slop = if (presentation == Presentation.EXPANDED) 0f else 8f * s
		val top = if (presentation == Presentation.EXPANDED) {
			place.pillTop
		} else {
			place.pillTop + (Spec.PILL_H - h) * s / 2f
		}
		outRect.set(
			(place.centerX - w * s / 2f - slop).toInt(),
			max(0f, top - slop).toInt(),
			(place.centerX + w * s / 2f + slop).toInt(),
			(top + h * s + slop).toInt(),
		)
		host.onPresentationChanged(presentation, outRect, presentation == Presentation.EXPANDED)
	}

	// ---- Animation ----

	private fun ensureTicking() {
		if (!ticking && isAttachedToWindow) {
			ticking = true
			lastFrameNanos = 0L
			Choreographer.getInstance().postFrameCallback(frameCallback)
		}
	}

	override fun onAttachedToWindow() {
		super.onAttachedToWindow()
		ensureTicking()
	}

	override fun onDetachedFromWindow() {
		super.onDetachedFromWindow()
		ticking = false
		Choreographer.getInstance().removeFrameCallback(frameCallback)
		handler.removeCallbacksAndMessages(null)
	}

	private fun onFrame(nanos: Long) {
		if (!ticking) return
		val dt = if (lastFrameNanos == 0L) 0.008f else ((nanos - lastFrameNanos) / 1e9f).coerceIn(0.001f, 0.05f)
		lastFrameNanos = nanos
		advance(dt * timeScale)
		invalidate()
		if (needsAnotherFrame()) {
			Choreographer.getInstance().postFrameCallback(frameCallback)
		} else {
			ticking = false
			if (motion == Motion.NONE) host.onMotionSettled(presentation)
		}
	}

	private fun needsAnotherFrame(): Boolean {
		if (motion != Motion.NONE) return true
		if (!widthPt.settled || !heightPt.settled || !press.settled) return true
		if (!playMorph.settled || !skipBack.settled || !skipForward.settled) return true
		if (!barGrow.settled || !controlHighlight.settled || !trackFade.settled) return true
		if (compactAlpha > 0f || expandedAlpha > 0f) {
			val np = nowPlaying
			if (np != null && np.isPlaying) return true
			if (!waveform.settled) return true
		}
		return false
	}

	private fun advance(dt: Float) {
		clock += dt
		motionTime += dt
		val t = motionTime

		if (motion == Motion.COLLAPSE && !collapsePhaseTwo && t >= Spec.Motion.COLLAPSE_SWITCH) {
			collapsePhaseTwo = true
			widthPt.animateTo(collapseTarget, Spec.Motion.COLLAPSE_W2_RESPONSE, Spec.Motion.COLLAPSE_W2_DAMPING)
			if (presentation == Presentation.IDLE) {
				heightPt.animateTo(
					place.idleDiameter,
					Spec.Motion.COLLAPSE_W2_RESPONSE,
					Spec.Motion.COLLAPSE_W2_DAMPING,
				)
			}
		}

		widthPt.step(dt)
		heightPt.step(dt)
		press.step(dt)
		playMorph.step(dt)
		skipBack.step(dt)
		skipForward.step(dt)
		if (skipBack.settled && skipBack.value >= 1f) skipBack.snapTo(0f)
		if (skipForward.settled && skipForward.value >= 1f) skipForward.snapTo(0f)
		barGrow.step(dt)
		trackFade.step(dt)
		controlHighlight.step(dt)
		waveform.step(dt)

		evaluateTimeline(t, dt)

		if (motion != Motion.NONE && t > 1.25f && widthPt.settled && heightPt.settled) {
			motion = Motion.NONE
			host.onMotionSettled(presentation)
		}
	}

	/** The content timeline read off the real island's frame sequences. */
	private fun evaluateTimeline(t: Float, dt: Float) {
		var cA: Float
		var cB: Float
		var eA: Float
		var eB: Float
		var tA: Float
		var top = 0f
		var bump = 0f
		when (motion) {
			Motion.EXPAND -> {
				cA = 1f - smooth(t, 0.060f, 0.130f)
				cB = BLUR_MAX * smooth(t, 0.030f, 0.130f)
				eA = smooth(t, 0.090f, 0.200f)
				eB = BLUR_MAX * (1f - smooth(t, 0.120f, 0.330f))
				tA = smooth(t, 0.190f, 0.430f)
			}

			Motion.COLLAPSE -> {
				val toCompact = presentation == Presentation.COMPACT
				cA = if (toCompact) smooth(t, 0.420f, 0.580f) else 0f
				cB = BLUR_COMPACT * (1f - smooth(t, 0.420f, 0.600f))
				eA = 1f - smooth(t, 0.050f, 0.170f)
				eB = BLUR_MAX * smooth(t, 0f, 0.120f)
				tA = 1f - smooth(t, 0f, 0.090f)
				val rise = ((t - 0.02f) / 0.64f).coerceIn(0f, 1f)
				top = -Spec.Motion.COLLAPSE_TOP_RISE * sinSquared(rise)
				val pop = ((t - 0.42f) / 0.50f).coerceIn(0f, 1f)
				if (toCompact) bump = Spec.Motion.COLLAPSE_H_BUMP * sinSquared(pop)
			}

			Motion.APPEAR -> {
				cA = smooth(t, 0.080f, 0.260f)
				cB = BLUR_COMPACT * (1f - smooth(t, 0.080f, 0.280f))
				eA = 0f
				eB = 0f
				tA = 0f
			}

			Motion.DISAPPEAR -> {
				cA = 1f - smooth(t, 0f, 0.120f)
				cB = BLUR_COMPACT * smooth(t, 0f, 0.120f)
				eA = 0f
				eB = 0f
				tA = 0f
			}

			Motion.NONE -> {
				cA = if (presentation == Presentation.COMPACT) 1f else 0f
				cB = 0f
				eA = if (presentation == Presentation.EXPANDED) 1f else 0f
				eB = 0f
				tA = eA
			}
		}
		val k = 1f - kotlin.math.exp(-dt / FILTER_TAU)
		compactAlpha += (cA - compactAlpha) * k
		compactBlur += (cB - compactBlur) * k
		expandedAlpha += (eA - expandedAlpha) * k
		expandedBlur += (eB - expandedBlur) * k
		textAlpha += (tA - textAlpha) * k
		topOffset += (top - topOffset) * k
		heightBump += (bump - heightBump) * k
		if (motion == Motion.NONE) {
			// Land exactly, so the settled island is crisp and costs nothing to composite.
			if (abs(compactAlpha - cA) < 0.004f) compactAlpha = cA
			if (abs(expandedAlpha - eA) < 0.004f) expandedAlpha = eA
			if (abs(textAlpha - tA) < 0.004f) textAlpha = tA
			if (compactBlur < 0.05f) compactBlur = 0f
			if (expandedBlur < 0.05f) expandedBlur = 0f
			if (abs(topOffset) < 0.01f) topOffset = 0f
			if (abs(heightBump) < 0.01f) heightBump = 0f
		}
		shadowProgress = ((heightPt.value - Spec.PILL_H) / (Spec.EXPANDED_H - Spec.PILL_H)).coerceIn(0f, 1.05f)
	}

	// ---- Drawing ----

	override fun onDraw(canvas: Canvas) {
		val s = place.scale
		val w = widthPt.value * press.value
		val h = (heightPt.value + heightBump) * (1f + (press.value - 1f) * 0.8f)
		val pillCenterY = place.pillTop + Spec.PILL_H * s / 2f
		val top = if (h <= Spec.PILL_H) {
			pillCenterY - h * s / 2f + topOffset * s
		} else {
			place.pillTop + topOffset * s
		}
		val left = place.centerX - w * s / 2f
		val right = left + w * s
		val bottom = top + h * s

		if (canvas.isHardwareAccelerated && shadowProgress > 0.01f) drawShadow(canvas, left, top, w, h)

		IslandShape.island(islandPath, left, top, right, bottom, h, s)
		canvas.drawPath(islandPath, shapePaint)

		if (compactAlpha <= 0.004f && expandedAlpha <= 0.004f) return
		canvas.save()
		canvas.clipPath(islandPath)
		if (compactAlpha > 0.004f) drawCompact(canvas, left, top, w, h)
		if (expandedAlpha > 0.004f) drawExpanded(canvas, left, top, w, h)
		canvas.restore()
	}

	private fun drawShadow(canvas: Canvas, left: Float, top: Float, w: Float, h: Float) {
		val s = place.scale
		val pad = PADDING * s
		if (abs(w - shadowW) > 0.05f || abs(h - shadowH) > 0.05f) {
			shadowW = w
			shadowH = h
			val nodeW = (w * s + pad * 2).toInt()
			val nodeH = (h * s + pad * 2).toInt()
			shadowNode.setPosition(0, 0, nodeW, nodeH)
			shadowNode.setRenderEffect(blurOf(Spec.Shadow.SIGMA * s))
			val c = shadowNode.beginRecording(nodeW, nodeH)
			IslandShape.island(shadowPath, pad, pad, pad + w * s, pad + h * s, h, s)
			c.drawPath(shadowPath, shadowPaint)
			shadowNode.endRecording()
		}
		shadowNode.setAlpha((Spec.Shadow.ALPHA * shadowProgress).coerceIn(0f, 1f))
		canvas.save()
		canvas.translate(left - pad, top - pad + Spec.Shadow.OFFSET_Y * s * shadowProgress)
		canvas.drawRenderNode(shadowNode)
		canvas.restore()
	}

	private fun drawCompact(canvas: Canvas, left: Float, top: Float, w: Float, h: Float) {
		val s = place.scale
		val pad = (PADDING_SMALL * s)
		val nodeW = (w * s + pad * 2).toInt()
		val nodeH = (h * s + pad * 2).toInt()
		val node = compactNode
		node.setPosition(0, 0, nodeW, nodeH)
		node.setHasOverlappingRendering(false)
		node.setAlpha(compactAlpha.coerceIn(0f, 1f))
		node.setRenderEffect(if (compactBlur > 0.1f) blurOf(compactBlur * s) else null)
		val c = node.beginRecording(nodeW, nodeH)
		c.translate(pad, pad)
		c.scale(s, s)
		val cy = h / 2f
		val artX = Spec.Compact.ART_INSET
		val artSize = Spec.Compact.ART_SIZE
		drawArt(c, shownArtwork.small, artX, cy - artSize / 2f, artSize, Spec.Compact.ART_R)
		val waveRight = w - Spec.Compact.WAVE_INSET
		val waveLeft = waveRight - waveWidth()
		drawWave(c, waveLeft, cy, Spec.Compact.BAR_W, Spec.Compact.BAR_PITCH, Spec.Compact.WAVE_MAX_H)
		node.endRecording()
		canvas.save()
		canvas.translate(left - pad, top - pad)
		canvas.drawRenderNode(node)
		canvas.restore()
	}

	private fun drawExpanded(canvas: Canvas, left: Float, top: Float, w: Float, h: Float) {
		val s = place.scale
		val pad = PADDING * s
		val fullW = Spec.EXPANDED_W * s
		val fullH = Spec.EXPANDED_H * s
		val nodeW = (fullW + pad * 2).toInt()
		val nodeH = (fullH + pad * 2).toInt()
		val node = expandedNode
		node.setPosition(0, 0, nodeW, nodeH)
		node.setHasOverlappingRendering(false)
		node.setAlpha(expandedAlpha.coerceIn(0f, 1f))
		node.setRenderEffect(if (expandedBlur > 0.1f) blurOf(expandedBlur * s) else null)
		val c = node.beginRecording(nodeW, nodeH)
		c.translate(pad, pad)
		c.scale(s, s)
		drawMediaContent(c)
		node.endRecording()
		canvas.save()
		// The content scales with the island through the morph, as in the real frame sequences.
		val sx = w / Spec.EXPANDED_W
		val sy = h / Spec.EXPANDED_H
		canvas.translate(left, top)
		canvas.scale(sx, sy)
		canvas.translate(-pad, -pad)
		canvas.drawRenderNode(node)
		canvas.restore()
	}

	/** Everything inside the expanded media island, in points relative to its top left. */
	private fun drawMediaContent(c: Canvas) {
		val np = nowPlaying ?: return
		val m = Spec.Media

		drawArt(c, shownArtwork.large, m.ART_X, m.ART_Y, m.ART_SIZE, m.ART_R)

		val textA = (textAlpha * trackFade.value * 255).toInt().coerceIn(0, 255)
		titleRow.paint.alpha = textA
		artistRow.paint.alpha = textA
		val marqueeT = clock - expandedSince
		titleRow.draw(c, m.TEXT_X, m.TITLE_BASELINE, m.TEXT_RIGHT - m.TEXT_X, marqueeT)
		artistRow.draw(c, m.TEXT_X, m.ARTIST_BASELINE, m.TEXT_RIGHT - m.TEXT_X, marqueeT)

		val barPitch = (m.WAVE_RIGHT - m.WAVE_LEFT - Spec.Compact.BAR_W * 1.05f) / (waveform.count - 1)
		drawWave(c, m.WAVE_LEFT, m.WAVE_CY, Spec.Compact.BAR_W * 1.05f, barPitch, m.WAVE_MAX_H)

		drawTimeline(c, np, textA)
		drawControls(c)
	}

	private fun drawTimeline(c: Canvas, np: NowPlaying, textA: Int) {
		val m = Spec.Media
		val position = scrubFraction?.let { (it * np.durationMs).toLong() } ?: np.positionAt()
		val fraction = if (np.durationMs > 0) (position.toFloat() / np.durationMs).coerceIn(0f, 1f) else 0f

		timePaint.alpha = textA
		val fm = timePaint.fontMetrics
		val digitCenter = (fm.ascent * 0.72f) / 2f
		val baseline = m.TIME_CY - digitCenter
		timePaint.textAlign = Paint.Align.LEFT
		c.drawText(formatTime(position), m.TIME_LEFT, baseline, timePaint)
		timePaint.textAlign = Paint.Align.RIGHT
		val remaining = if (np.durationMs > 0) np.durationMs - position else 0L
		c.drawText(MINUS + formatTime(remaining), m.TIME_RIGHT, baseline, timePaint)

		val h = m.BAR_H + (m.BAR_H_SCRUB - m.BAR_H) * barGrow.value
		val top = m.BAR_CY - h / 2f
		val bottom = m.BAR_CY + h / 2f
		val r = h / 2f
		fill.shader = null
		fill.color = Spec.Colors.BAR_TRACK
		rect.set(m.BAR_LEFT, top, m.BAR_RIGHT, bottom)
		c.drawRoundRect(rect, r, r, fill)
		val fillRight = m.BAR_LEFT + (m.BAR_RIGHT - m.BAR_LEFT) * fraction
		if (fillRight > m.BAR_LEFT + 0.5f) {
			// The fill is clipped by the track's capsule, with a square leading edge like the real one.
			c.save()
			c.clipRect(m.BAR_LEFT, top, fillRight, bottom)
			fill.color = lerpColor(Spec.Colors.BAR_FILL, Color.WHITE, barGrow.value)
			c.drawRoundRect(rect, r, r, fill)
			c.restore()
		}
	}

	private fun drawControls(c: Canvas) {
		val m = Spec.Media
		val cy = m.CONTROL_CY
		fill.shader = null

		highlight(c, Control.BACK, m.BACK_CX, cy)
		highlight(c, Control.PLAY, m.PLAY_CX, cy)
		highlight(c, Control.FORWARD, m.FORWARD_CX, cy)

		fill.color = Color.WHITE
		drawSkip(c, m.BACK_CX, cy, -1f, skipBack.value, controlScale(Control.BACK))
		drawSkip(c, m.FORWARD_CX, cy, 1f, skipForward.value, controlScale(Control.FORWARD))

		// Pause morphs to play through a scale and fade.
		val morph = playMorph.value.coerceIn(0f, 1f)
		val scale = controlScale(Control.PLAY)
		if (morph < 0.99f) {
			fill.alpha = ((1f - morph) * 255).toInt()
			c.save()
			val k = scale * (1f - morph * 0.35f)
			c.scale(k, k, m.PLAY_CX, cy)
			glyph.rewind()
			Glyphs.pause(glyph, m.PLAY_CX, cy)
			c.drawPath(glyph, fill)
			c.restore()
		}
		if (morph > 0.01f) {
			fill.alpha = (morph * 255).toInt()
			c.save()
			val k = scale * (0.65f + morph * 0.35f)
			c.scale(k, k, m.PLAY_CX, cy)
			glyph.rewind()
			Glyphs.play(glyph, m.PLAY_CX, cy)
			c.drawPath(glyph, fill)
			c.restore()
		}
		fill.alpha = 255
	}

	private fun controlScale(control: Control): Float =
		if (activeControl == control) 1f - 0.14f * controlHighlight.value else 1f

	private fun highlight(c: Canvas, control: Control, cx: Float, cy: Float) {
		if (activeControl != control || controlHighlight.value < 0.01f) return
		fill.color = Color.WHITE
		fill.alpha = (controlHighlight.value * 0.16f * 255).toInt()
		c.drawCircle(cx, cy, 27f, fill)
		fill.alpha = 255
	}

	/**
	 * The skip glyph. Pressing rolls it over: the leading triangle slides out and shrinks, the trailing one takes
	 * its place, and a new one grows in behind. [dir] is 1 for forward and -1 for back.
	 */
	private fun drawSkip(c: Canvas, cx: Float, cy: Float, dir: Float, p: Float, scale: Float) {
		c.save()
		c.scale(scale * dir, scale, cx, cy)
		val w = Glyphs.TRIANGLE_W
		glyph.rewind()
		val progress = p.coerceIn(0f, 1f)
		if (progress <= 0.001f) {
			Glyphs.triangle(glyph, cx - w, cy)
			Glyphs.triangle(glyph, cx, cy)
		} else {
			Glyphs.triangle(glyph, cx - w, cy, progress.coerceAtLeast(0.01f))
			Glyphs.triangle(glyph, cx - w + w * progress, cy)
			val outgoing = (1f - progress).coerceAtLeast(0.01f)
			Glyphs.triangle(glyph, cx + w * progress, cy, outgoing)
		}
		c.drawPath(glyph, fill)
		c.restore()
	}

	private fun drawArt(c: Canvas, bitmap: android.graphics.Bitmap?, x: Float, y: Float, size: Float, r: Float) {
		IslandShape.rounded(artPath, x, y, x + size, y + size, r, 1f)
		if (bitmap == null) {
			fill.shader = null
			fill.color = Spec.Colors.PLACEHOLDER_ART
			c.drawPath(artPath, fill)
			return
		}
		val shader = BitmapShader(bitmap, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP)
		artMatrix.setScale(size / bitmap.width, size / bitmap.height)
		artMatrix.postTranslate(x, y)
		shader.setLocalMatrix(artMatrix)
		artPaint.shader = shader
		artPaint.alpha = (trackFade.value.coerceIn(0f, 1f) * 255).toInt()
		c.drawPath(artPath, artPaint)
	}

	private fun drawWave(c: Canvas, left: Float, cy: Float, barW: Float, pitch: Float, maxH: Float) {
		val tint = shownArtwork.tint
		val key = tint.toLong() shl 8 or maxH.toLong()
		if (barShader == null || barShaderKey != key) {
			barShaderKey = key
			barShader = LinearGradient(
				0f,
				-maxH / 2f,
				0f,
				maxH / 2f,
				lerpColor(tint, Color.WHITE, 0.10f),
				lerpColor(tint, Color.BLACK, 0.12f),
				Shader.TileMode.CLAMP,
			)
		}
		c.save()
		c.translate(0f, cy)
		fill.shader = barShader
		fill.alpha = 255
		for (i in 0 until waveform.count) {
			val h = max(barW, waveform.level(i) * maxH)
			val x = left + i * pitch
			rect.set(x, -h / 2f, x + barW, h / 2f)
			c.drawRoundRect(rect, barW / 2f, barW / 2f, fill)
		}
		fill.shader = null
		c.restore()
	}

	private fun blurOf(sigmaPx: Float): RenderEffect {
		// RenderEffect's radius converts to sigma as 0.57735 * radius + 0.5.
		val radius = max(0.5f, (sigmaPx - 0.5f) / 0.57735f)
		return RenderEffect.createBlurEffect(radius, radius, Shader.TileMode.DECAL)
	}

	private fun textPaint(size: Float, face: Typeface, color: Int, tracking: Float) =
		Paint(Paint.ANTI_ALIAS_FLAG or Paint.SUBPIXEL_TEXT_FLAG or Paint.LINEAR_TEXT_FLAG).apply {
			textSize = size
			typeface = face
			this.color = color
			letterSpacing = tracking
			isDither = false
		}

	/** A single line of text that fades at its right edge and scrolls if it does not fit. */
	private class TextRow(val paint: Paint) {
		private var text = ""
		private var width = 0f
		private val fade = Paint().apply { xfermode = PorterDuffXfermode(PorterDuff.Mode.DST_OUT) }
		private var rightFade: Shader? = null
		private var leftFade: Shader? = null
		private var fadeKey = ""

		fun set(value: String) {
			if (value == text) return
			text = value
			width = paint.measureText(value)
		}

		fun draw(c: Canvas, x: Float, baseline: Float, maxW: Float, time: Float) {
			if (text.isEmpty()) return
			if (width <= maxW) {
				c.drawText(text, x, baseline, paint)
				return
			}
			val loop = width + MARQUEE_GAP
			val travel = loop / MARQUEE_SPEED
			val cycle = MARQUEE_HOLD + travel
			val phase = (time % cycle) - MARQUEE_HOLD
			val offset = if (phase <= 0f) 0f else phase * MARQUEE_SPEED
			val top = baseline - 17f
			val bottom = baseline + 6f
			val right = x + maxW
			c.saveLayer(x - 1f, top, right + 1f, bottom, null)
			c.drawText(text, x - offset, baseline, paint)
			if (offset > 0f) c.drawText(text, x - offset + loop, baseline, paint)
			val key = "$x/$right"
			if (key != fadeKey) {
				fadeKey = key
				rightFade =
					LinearGradient(
						right - MARQUEE_FADE,
						0f,
						right,
						0f,
						Color.TRANSPARENT,
						Color.BLACK,
						Shader.TileMode.CLAMP,
					)
				leftFade =
					LinearGradient(x, 0f, x + MARQUEE_FADE, 0f, Color.BLACK, Color.TRANSPARENT, Shader.TileMode.CLAMP)
			}
			fade.shader = rightFade
			c.drawRect(right - MARQUEE_FADE, top, right + 1f, bottom, fade)
			if (offset > 0.5f) {
				fade.shader = leftFade
				c.drawRect(x - 1f, top, x + MARQUEE_FADE, bottom, fade)
			}
			c.restore()
		}
	}

	// ---- Touch ----

	private fun scheduleAutoCollapse() {
		handler.removeCallbacks(autoCollapse)
		handler.postDelayed(autoCollapse, Spec.Timing.EXPANDED_IDLE_MS)
	}

	/** A touch outside the expanded island. */
	fun onOutsideTouch() {
		collapse()
	}

	/** Handles a touch in screen coordinates, from the host's touch window. */
	fun handleTouch(event: MotionEvent): Boolean {
		val x = event.rawX
		val y = event.rawY
		return when (presentation) {
			Presentation.EXPANDED -> touchExpanded(event.actionMasked, x, y)
			else -> touchPill(event.actionMasked, x, y)
		}
	}

	private fun touchPill(action: Int, x: Float, y: Float): Boolean {
		val s = place.scale
		when (action) {
			MotionEvent.ACTION_DOWN -> {
				downX = x
				downY = y
				gestureHandled = false
				handler.removeCallbacks(longPress)
				if (presentation == Presentation.COMPACT) {
					press.animateTo(PRESS_SCALE, 0.28f, 0.72f)
					handler.postDelayed(longPress, Spec.Timing.LONG_PRESS_MS)
				}
				ensureTicking()
			}

			MotionEvent.ACTION_MOVE -> {
				if (gestureHandled) return true
				val dx = (x - downX) / s
				val dy = (y - downY) / s
				pillSwipe(dx, dy)
				if (abs(dx) > SLOP_PT || abs(dy) > SLOP_PT) handler.removeCallbacks(longPress)
			}

			MotionEvent.ACTION_UP -> {
				handler.removeCallbacks(longPress)
				press.animateTo(1f, 0.38f, 0.58f)
				if (!gestureHandled) {
					val dx = (x - downX) / s
					val dy = (y - downY) / s
					pillSwipe(dx, dy)
					val moved = abs(dx) > SLOP_PT || abs(dy) > SLOP_PT
					if (!gestureHandled && !moved && presentation == Presentation.COMPACT) host.openNowPlayingApp()
				}
				ensureTicking()
			}

			MotionEvent.ACTION_CANCEL -> {
				handler.removeCallbacks(longPress)
				press.animateTo(1f, 0.38f, 0.8f)
				// The system takes over any downward swipe that starts in the status bar, cancelling this one
				// partway. It then does nothing with it, so the swipe still counts as ours.
				if (!gestureHandled) pillSwipe((x - downX) / s, (y - downY) / s)
				ensureTicking()
			}
		}
		return true
	}

	private fun pillSwipe(dx: Float, dy: Float) {
		val sideways = abs(dx) > SWIPE_ASIDE_PT && abs(dx) > abs(dy) * 1.5f
		if (dy <= SWIPE_DOWN_PT && !sideways) return
		gestureHandled = true
		handler.removeCallbacks(longPress)
		press.animateTo(1f, 0.35f, 0.8f)
		when {
			sideways -> host.onSwipeAside(hide = presentation == Presentation.COMPACT)

			presentation == Presentation.COMPACT -> {
				host.haptic(false)
				show(Presentation.EXPANDED)
			}

			else -> host.openNotificationShade()
		}
		ensureTicking()
	}

	private fun touchExpanded(action: Int, x: Float, y: Float): Boolean {
		val s = place.scale
		val left = place.centerX - Spec.EXPANDED_W * s / 2f
		val px = (x - left) / s
		val py = (y - place.pillTop) / s
		val np = nowPlaying
		when (action) {
			MotionEvent.ACTION_DOWN -> {
				downX = px
				downY = py
				gestureHandled = false
				activeControl = hitTest(px, py, np)
				handler.removeCallbacks(autoCollapse)
				if (activeControl == Control.BAR) {
					scrubFraction = barFraction(px)
					barGrow.animateTo(1f, 0.3f, 0.75f)
				} else if (activeControl != Control.NONE && activeControl != Control.INFO) {
					controlHighlight.animateTo(1f, 0.2f, 0.9f)
				}
				ensureTicking()
			}

			MotionEvent.ACTION_MOVE -> {
				if (activeControl == Control.BAR) {
					scrubFraction = barFraction(px)
					invalidate()
				} else if (!gestureHandled && downY - py > SWIPE_UP_PT) {
					gestureHandled = true
					activeControl = Control.NONE
					controlHighlight.snapTo(0f)
					collapse()
				} else if (activeControl != Control.NONE && (abs(px - downX) > 34f || abs(py - downY) > 34f)) {
					controlHighlight.animateTo(0f, 0.25f, 0.9f)
					activeControl = Control.NONE
					ensureTicking()
				}
			}

			MotionEvent.ACTION_UP -> {
				val control = activeControl
				if (control == Control.BAR) {
					scrubFraction?.let { f -> np?.let { host.seekTo((f * it.durationMs).toLong()) } }
					scrubFraction = null
					barGrow.animateTo(0f, 0.3f, 0.8f)
				} else if (!gestureHandled && downY - py > SWIPE_UP_PT) {
					gestureHandled = true
					collapse()
				} else if (!gestureHandled) {
					runControl(control)
				}
				controlHighlight.animateTo(0f, 0.28f, 0.75f)
				if (control != Control.INFO) activeControl = Control.NONE
				if (presentation == Presentation.EXPANDED) scheduleAutoCollapse()
				ensureTicking()
			}

			MotionEvent.ACTION_CANCEL -> {
				activeControl = Control.NONE
				scrubFraction = null
				barGrow.animateTo(0f, 0.3f, 0.8f)
				controlHighlight.animateTo(0f, 0.28f, 0.9f)
				if (presentation == Presentation.EXPANDED) scheduleAutoCollapse()
				ensureTicking()
			}
		}
		return true
	}

	private fun barFraction(px: Float): Float {
		val m = Spec.Media
		return ((px - m.BAR_LEFT) / (m.BAR_RIGHT - m.BAR_LEFT)).coerceIn(0f, 1f)
	}

	private fun hitTest(px: Float, py: Float, np: NowPlaying?): Control {
		val m = Spec.Media
		if (py in (m.BAR_CY - 14f)..(m.BAR_CY + 14f) && px in (m.BAR_LEFT - 8f)..(m.BAR_RIGHT + 8f) &&
			np != null && np.canSeek && np.durationMs > 0
		) {
			return Control.BAR
		}
		if (py > m.CONTROL_CY - m.CONTROL_HIT_R) {
			val candidates = arrayOf(
				Control.BACK to m.BACK_CX,
				Control.PLAY to m.PLAY_CX,
				Control.FORWARD to m.FORWARD_CX,
			)
			var best = Control.NONE
			var bestD = m.CONTROL_HIT_R
			for ((control, cx) in candidates) {
				val d = abs(px - cx)
				if (d < bestD) {
					bestD = d
					best = control
				}
			}
			return best
		}
		if (py < m.BAR_CY - 14f) return Control.INFO
		return Control.NONE
	}

	private fun runControl(control: Control) {
		when (control) {
			Control.PLAY -> {
				host.haptic(false)
				host.togglePlayPause()
			}

			Control.FORWARD -> {
				host.haptic(false)
				skipForward.snapTo(0f)
				skipForward.animateTo(1f, 0.42f, 0.86f)
				host.skipNext()
			}

			Control.BACK -> {
				host.haptic(false)
				skipBack.snapTo(0f)
				skipBack.animateTo(1f, 0.42f, 0.86f)
				host.skipPrevious()
			}

			Control.INFO -> {
				activeControl = Control.NONE
				host.openNowPlayingApp()
				collapse()
			}

			else -> {}
		}
	}

	/** A frame rate hint: full speed in motion, and a calmer rate for the waveform to save power. */
	override fun invalidate() {
		if (Build.VERSION.SDK_INT >= 35) {
			requestedFrameRate = if (motion != Motion.NONE || !press.settled) {
				REQUESTED_FRAME_RATE_CATEGORY_HIGH
			} else {
				60f
			}
		}
		super.invalidate()
	}

	override fun hasOverlappingRendering() = false

	companion object {
		private const val BLUR_MAX = 9f
		private const val BLUR_COMPACT = 6f
		private const val FILTER_TAU = 0.010f
		private const val PADDING = 60f
		private const val PADDING_SMALL = 30f
		private const val PRESS_SCALE = 1.035f
		private const val RESIZE_RESPONSE = 0.45f
		private const val RESIZE_DAMPING = 0.86f
		private const val SWIPE_DOWN_PT = 18f
		private const val SWIPE_UP_PT = 26f
		private const val SWIPE_ASIDE_PT = 30f
		private const val SLOP_PT = 8f
		private const val MINUS = "−"
		private const val MARQUEE_GAP = 40f
		private const val MARQUEE_SPEED = 28f
		private const val MARQUEE_HOLD = 2.2f
		private const val MARQUEE_FADE = 6f

		fun smooth(t: Float, a: Float, b: Float): Float {
			val x = ((t - a) / (b - a)).coerceIn(0f, 1f)
			return x * x * (3f - 2f * x)
		}

		fun sinSquared(x: Float): Float {
			val v = sin(Math.PI.toFloat() * x)
			return v * v
		}

		fun formatTime(ms: Long): String {
			val total = (ms / 1000).coerceAtLeast(0)
			return "${total / 60}:${(total % 60).toString().padStart(2, '0')}"
		}

		fun lerpColor(a: Int, b: Int, t: Float): Int {
			val f = t.coerceIn(0f, 1f)
			fun mix(x: Int, y: Int) = (x + (y - x) * f).toInt()
			return Color.argb(
				mix(Color.alpha(a), Color.alpha(b)),
				mix(Color.red(a), Color.red(b)),
				mix(Color.green(a), Color.green(b)),
				mix(Color.blue(a), Color.blue(b)),
			)
		}
	}
}

/** Inter at the weights and optical sizes calibrated against SF Pro. */
private class Fonts(context: Context) {
	private fun make(context: Context, wght: Int, opsz: Int): Typeface =
		Typeface.Builder(context.assets, "fonts/InterVariable.ttf")
			.setFontVariationSettings("'wght' $wght, 'opsz' $opsz")
			.build()

	val title = make(context, Spec.Type.TITLE_WEIGHT, Spec.Type.TITLE_OPSZ)
	val artist = make(context, Spec.Type.ARTIST_WEIGHT, Spec.Type.ARTIST_OPSZ)
	val time = make(context, Spec.Type.TIME_WEIGHT, Spec.Type.TIME_OPSZ)
}
