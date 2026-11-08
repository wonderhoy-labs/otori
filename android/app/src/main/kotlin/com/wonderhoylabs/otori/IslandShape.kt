package com.wonderhoylabs.otori

import android.graphics.Path
import kotlin.math.min
import kotlin.math.tan

/**
 * The island's silhouette. Measured from the real island: the pill is a plain capsule and the settled expanded
 * island has Apple's continuous corners at r = 44. Between them, the corner curve blends from one to the other,
 * so the outline stays smooth through the morph.
 *
 * Each corner is three cubics. Points are in corner space: x along the edge from the vertex, y into the shape,
 * in units of the radius.
 */
object IslandShape {
	private const val EXTENT = 1.528665f

	// UIKit's continuous corner control points.
	private val CONTINUOUS = floatArrayOf(
		EXTENT, 0f,
		1.08849296f, 0f,
		0.86840694f, 0f,
		0.63149379f, 0.07491139f,
		0.37282383f, 0.16905956f,
		0.16905956f, 0.37282383f,
		0.07491139f, 0.63149379f,
		0f, 0.86840694f,
		0f, 1.08849296f,
		0f, EXTENT,
	)

	// A quarter circle with the same topology: three 30 degree arcs.
	private val CIRCLE = FloatArray(20).also { out ->
		val k = (4.0 / 3.0 * tan(Math.toRadians(7.5))).toFloat()
		fun px(deg: Double) = (1.0 - Math.sin(Math.toRadians(deg))).toFloat()
		fun py(deg: Double) = (1.0 - Math.cos(Math.toRadians(deg))).toFloat()
		fun tx(deg: Double) = (-Math.cos(Math.toRadians(deg))).toFloat()
		fun ty(deg: Double) = Math.sin(Math.toRadians(deg)).toFloat()
		out[0] = px(0.0)
		out[1] = py(0.0)
		var i = 2
		var a = 0.0
		repeat(3) {
			val b = a + 30.0
			out[i++] = px(a) + k * tx(a)
			out[i++] = py(a) + k * ty(a)
			out[i++] = px(b) - k * tx(b)
			out[i++] = py(b) - k * ty(b)
			out[i++] = px(b)
			out[i++] = py(b)
			a = b
		}
	}

	private val blended = FloatArray(20)

	/** The height at which a corner of radius [Spec.EXPANDED_R] is fully continuous and still fits. */
	private val FULL_H = 2f * EXTENT * Spec.EXPANDED_R

	/** How continuous the corners are at this island height, 0 for a capsule and 1 for fully continuous. */
	fun smoothness(heightPt: Float): Float = ((heightPt - Spec.PILL_H) / (FULL_H - Spec.PILL_H)).coerceIn(0f, 1f)

	/** The corner radius at this island height, in points. */
	fun radius(heightPt: Float): Float =
		min(Spec.EXPANDED_R, (heightPt / 2f) / (1f + (EXTENT - 1f) * smoothness(heightPt)))

	/**
	 * Builds the island outline. Sizes are in pixels; [heightPt] selects the corner shape.
	 * [scale] is pixels per point.
	 */
	fun island(out: Path, left: Float, top: Float, right: Float, bottom: Float, heightPt: Float, scale: Float) {
		val c = smoothness(heightPt)
		// A spring can briefly make the island narrower than it is tall, on its way into the camera.
		val widest = (right - left) / 2f / (1f + (EXTENT - 1f) * c)
		rounded(out, left, top, right, bottom, min(radius(heightPt) * scale, widest), c)
	}

	/** A rectangle with corners blended between circular (c = 0) and continuous (c = 1). */
	fun rounded(out: Path, left: Float, top: Float, right: Float, bottom: Float, r: Float, c: Float) {
		out.rewind()
		val p = blended
		for (i in p.indices) p[i] = CIRCLE[i] + (CONTINUOUS[i] - CIRCLE[i]) * c
		val e = p[0] * r

		out.moveTo(left + e, top)
		out.lineTo(right - e, top)
		// Top right: forwards through the corner table.
		var i = 2
		repeat(3) {
			out.cubicTo(
				right - p[i] * r,
				top + p[i + 1] * r,
				right - p[i + 2] * r,
				top + p[i + 3] * r,
				right - p[i + 4] * r,
				top + p[i + 5] * r,
			)
			i += 6
		}
		out.lineTo(right, bottom - e)
		// Bottom right: backwards, with x and y swapped so it starts on the right edge.
		i = 18
		repeat(3) {
			out.cubicTo(
				right - p[i - 2] * r,
				bottom - p[i - 1] * r,
				right - p[i - 4] * r,
				bottom - p[i - 3] * r,
				right - p[i - 6] * r,
				bottom - p[i - 5] * r,
			)
			i -= 6
		}
		out.lineTo(left + e, bottom)
		// Bottom left: forwards.
		i = 2
		repeat(3) {
			out.cubicTo(
				left + p[i] * r,
				bottom - p[i + 1] * r,
				left + p[i + 2] * r,
				bottom - p[i + 3] * r,
				left + p[i + 4] * r,
				bottom - p[i + 5] * r,
			)
			i += 6
		}
		out.lineTo(left, top + e)
		// Top left: backwards.
		i = 18
		repeat(3) {
			out.cubicTo(
				left + p[i - 2] * r,
				top + p[i - 1] * r,
				left + p[i - 4] * r,
				top + p[i - 3] * r,
				left + p[i - 6] * r,
				top + p[i - 5] * r,
			)
			i -= 6
		}
		out.close()
	}
}
