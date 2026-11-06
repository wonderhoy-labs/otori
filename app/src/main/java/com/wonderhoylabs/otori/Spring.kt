package com.wonderhoylabs.otori

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Damped spring parameterised like SwiftUI's spring(response:dampingFraction:), solved in closed form so the
 * motion does not depend on frame rate. Retargeting keeps velocity, which keeps interrupted transitions smooth.
 */
class Spring(initial: Float) {
    var value = initial
        private set
    var velocity = 0f
    var target = initial
        private set
    private var omega = 2f * PI.toFloat() / 0.5f
    private var damping = 1f
    private var hold = 0f

    fun animateTo(target: Float, response: Float, damping: Float, delay: Float = 0f) {
        this.target = target
        this.omega = 2f * PI.toFloat() / response
        this.damping = damping
        this.hold = delay
    }

    fun snapTo(v: Float) {
        value = v
        target = v
        velocity = 0f
        hold = 0f
    }

    val settled: Boolean
        get() = hold <= 0f && abs(value - target) < 0.01f && abs(velocity) < 0.05f

    fun step(dtSeconds: Float) {
        var dt = dtSeconds
        if (hold > 0f) {
            if (dt <= hold) {
                hold -= dt
                return
            }
            dt -= hold
            hold = 0f
        }
        val x0 = value - target
        val v0 = velocity
        val w = omega
        if (damping < 1f) {
            val a = damping * w
            val wd = w * sqrt(1f - damping * damping)
            val e = exp(-a * dt)
            val c = cos(wd * dt)
            val s = sin(wd * dt)
            value = target + e * (x0 * c + ((v0 + a * x0) / wd) * s)
            velocity = e * (v0 * c - ((a * v0 + w * w * x0) / wd) * s)
        } else {
            val e = exp(-w * dt)
            val b = v0 + w * x0
            value = target + e * (x0 + b * dt)
            velocity = e * (v0 - b * w * dt)
        }
        if (settled) {
            value = target
            velocity = 0f
        }
    }
}
