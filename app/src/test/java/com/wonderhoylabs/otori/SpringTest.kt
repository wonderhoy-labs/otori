package com.wonderhoylabs.otori

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SpringTest {
    private fun run(spring: Spring, seconds: Float, dt: Float = 1f / 120f) {
        var t = 0f
        while (t < seconds) {
            spring.step(dt)
            t += dt
        }
    }

    @Test
    fun widthFollowsTheMeasuredExpandCurve() {
        // Samples tracked from the real island's width during expand.
        val measured = listOf(0.400f to 370.6f, 0.508f to 372.8f, 0.600f to 371.8f, 0.708f to 370.7f)
        for ((time, expected) in measured) {
            val s = Spring(Spec.COMPACT_W)
            s.animateTo(Spec.EXPANDED_W, Spec.Motion.EXPAND_W_RESPONSE, Spec.Motion.EXPAND_W_DAMPING, Spec.Motion.EXPAND_W_DELAY)
            run(s, time)
            assertEquals("at $time s", expected, s.value, 1.6f)
        }
    }

    @Test
    fun resultDoesNotDependOnFrameRate() {
        val a = Spring(0f).apply { animateTo(100f, 0.5f, 0.8f, 0.05f) }
        val b = Spring(0f).apply { animateTo(100f, 0.5f, 0.8f, 0.05f) }
        run(a, 0.3f, 1f / 120f)
        run(b, 0.3f, 1f / 30f)
        assertEquals(a.value, b.value, 0.5f)
    }

    @Test
    fun retargetKeepsVelocity() {
        val s = Spring(370f).apply { animateTo(133f, 0.5f, 0.8f) }
        run(s, 0.2f)
        val before = s.velocity
        s.animateTo(187f, 0.43f, 0.67f)
        assertEquals(before, s.velocity, 0f)
        assertTrue(before < 0f)
    }

    @Test
    fun settlesExactlyOnTarget() {
        val s = Spring(0f).apply { animateTo(1f, 0.3f, 1f) }
        run(s, 4f)
        assertTrue(s.settled)
        assertEquals(1f, s.value, 0f)
    }

    @Test
    fun delayHoldsTheValue() {
        val s = Spring(5f).apply { animateTo(10f, 0.5f, 0.8f, 0.1f) }
        s.step(0.05f)
        assertEquals(5f, s.value, 0f)
    }
}
