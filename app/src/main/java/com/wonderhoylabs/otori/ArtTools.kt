package com.wonderhoylabs.otori

import android.graphics.Bitmap
import android.graphics.Color
import kotlin.math.max
import kotlin.math.min

/** Prepares artwork bitmaps and finds the tint for the waveform, like the island's artwork-coloured bars. */
object ArtTools {
    /** Scales in halving steps, because one big bilinear step aliases badly at small sizes. */
    fun scaled(src: Bitmap, target: Int): Bitmap {
        var cur = src
        var w = cur.width
        var h = cur.height
        val side = min(w, h)
        if (w != h) {
            cur = Bitmap.createBitmap(cur, (w - side) / 2, (h - side) / 2, side, side)
            w = side
            h = side
        }
        while (w / 2 >= target) {
            w /= 2
            h /= 2
            cur = Bitmap.createScaledBitmap(cur, w, h, true)
        }
        return if (w == target) cur else Bitmap.createScaledBitmap(cur, target, target, true)
    }

    /** The most prominent vivid colour, or a light grey for monochrome artwork. */
    fun tint(src: Bitmap): Int {
        val small = Bitmap.createScaledBitmap(
            if (src.config == Bitmap.Config.HARDWARE) src.copy(Bitmap.Config.ARGB_8888, false) else src,
            24, 24, true,
        )
        val buckets = 24
        val weight = FloatArray(buckets)
        val sumR = FloatArray(buckets)
        val sumG = FloatArray(buckets)
        val sumB = FloatArray(buckets)
        val hsv = FloatArray(3)
        var total = 0f
        for (y in 0 until small.height) for (x in 0 until small.width) {
            val c = small.getPixel(x, y)
            Color.colorToHSV(c, hsv)
            val w = hsv[1] * hsv[1] * hsv[2]
            val b = (hsv[0] / 360f * buckets).toInt().coerceIn(0, buckets - 1)
            weight[b] += w
            sumR[b] += Color.red(c) * w
            sumG[b] += Color.green(c) * w
            sumB[b] += Color.blue(c) * w
            total += 1f
        }
        var best = 0
        for (i in 0 until buckets) if (weight[i] > weight[best]) best = i
        // Near monochrome artwork gets a neutral waveform.
        if (weight[best] / total < 0.015f) return Color.rgb(0xC8, 0xC8, 0xCC)
        val w = weight[best]
        val color = Color.rgb((sumR[best] / w).toInt(), (sumG[best] / w).toInt(), (sumB[best] / w).toInt())
        return legible(color)
    }

    /** Keeps the hue but lifts dark colours, so the bars stay visible on black. */
    private fun legible(color: Int): Int {
        val hsv = FloatArray(3)
        Color.colorToHSV(color, hsv)
        hsv[1] = min(hsv[1], 0.75f)
        hsv[2] = max(hsv[2], 0.78f)
        return Color.HSVToColor(hsv)
    }

    fun prepare(src: Bitmap?, scale: Float): Artwork {
        if (src == null) return Artwork.NONE
        val soft = if (src.config == Bitmap.Config.HARDWARE) src.copy(Bitmap.Config.ARGB_8888, false) else src
        val large = scaled(soft, (Spec.Media.ART_SIZE * scale).toInt().coerceAtLeast(1))
        val small = scaled(soft, (Spec.Compact.ART_SIZE * scale).toInt().coerceAtLeast(1))
        return Artwork(large, small, tint(soft))
    }
}
