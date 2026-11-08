package com.wonderhoylabs.otori

import android.graphics.Path
import android.graphics.RectF
import kotlin.math.hypot
import kotlin.math.min

/** Transport icons, drawn from measurements of the real island. All sizes are in points. */
object Glyphs {
	const val TRIANGLE_W = 17.6f
	const val TRIANGLE_H = 20f
	private const val TRIANGLE_BASE_R = 2.9f
	private const val TRIANGLE_TIP_R = 2.6f

	const val PAUSE_BAR_W = 9.9f
	const val PAUSE_GAP = 4.8f
	const val PAUSE_H = 33f
	const val PAUSE_R = 2.7f

	private const val PLAY_W = 28f
	private const val PLAY_H = 33f

	/** A polygon with each corner replaced by a quadratic through the vertex. */
	private fun roundedPolygon(out: Path, xs: FloatArray, ys: FloatArray, radii: FloatArray) {
		val n = xs.size
		for (i in 0 until n) {
			val px = xs[(i + n - 1) % n]
			val py = ys[(i + n - 1) % n]
			val nx = xs[(i + 1) % n]
			val ny = ys[(i + 1) % n]
			val x = xs[i]
			val y = ys[i]
			val toPrev = hypot(px - x, py - y)
			val toNext = hypot(nx - x, ny - y)
			val r = min(radii[i], min(toPrev, toNext) / 2f)
			val ax = x + (px - x) / toPrev * r
			val ay = y + (py - y) / toPrev * r
			val bx = x + (nx - x) / toNext * r
			val by = y + (ny - y) / toNext * r
			if (i == 0) out.moveTo(ax, ay) else out.lineTo(ax, ay)
			out.quadTo(x, y, bx, by)
		}
		out.close()
	}

	/** One right-pointing transport triangle. Its base is at [baseX], centred on (baseX, cy). */
	fun triangle(out: Path, baseX: Float, cy: Float, scale: Float = 1f) {
		val w = TRIANGLE_W * scale
		val h = TRIANGLE_H * scale
		roundedPolygon(
			out,
			floatArrayOf(baseX, baseX + w, baseX),
			floatArrayOf(cy - h / 2f, cy, cy + h / 2f),
			floatArrayOf(TRIANGLE_BASE_R * scale, TRIANGLE_TIP_R * scale, TRIANGLE_BASE_R * scale),
		)
	}

	/** The play triangle, optically centred on (cx, cy). */
	fun play(out: Path, cx: Float, cy: Float) {
		val left = cx - PLAY_W * 0.42f
		roundedPolygon(
			out,
			floatArrayOf(left, left + PLAY_W, left),
			floatArrayOf(cy - PLAY_H / 2f, cy, cy + PLAY_H / 2f),
			floatArrayOf(4.2f, 3.6f, 4.2f),
		)
	}

	fun pause(out: Path, cx: Float, cy: Float) {
		val half = PAUSE_GAP / 2f
		val rect = RectF(cx - half - PAUSE_BAR_W, cy - PAUSE_H / 2f, cx - half, cy + PAUSE_H / 2f)
		out.addRoundRect(rect, PAUSE_R, PAUSE_R, Path.Direction.CW)
		rect.set(cx + half, cy - PAUSE_H / 2f, cx + half + PAUSE_BAR_W, cy + PAUSE_H / 2f)
		out.addRoundRect(rect, PAUSE_R, PAUSE_R, Path.Direction.CW)
	}
}
