package com.wonderhoylabs.otori

import kotlin.random.Random

/**
 * The six animated bars. Each bar chases a new random level several times a second through its own spring,
 * which gives the same restless, music-like movement. When paused they settle into dots.
 */
class Waveform(private val random: Random = Random.Default) {
	val count = 6
	private val springs = Array(count) { Spring(REST) }
	private val nextChange = FloatArray(count)
	private var clock = 0f
	private var playing = false

	/** Level of a bar from 0 to 1. */
	fun level(i: Int): Float = springs[i].value.coerceIn(0f, 1.15f)

	fun setPlaying(on: Boolean) {
		if (on == playing) return
		playing = on
		if (!on) {
			for (s in springs) s.animateTo(REST, 0.35f, 0.9f)
		} else {
			nextChange.fill(0f)
		}
	}

	val settled: Boolean
		get() = !playing && springs.all { it.settled }

	fun step(dt: Float) {
		clock += dt
		if (playing) {
			for (i in 0 until count) {
				if (clock >= nextChange[i]) {
					nextChange[i] = clock + 0.09f + random.nextFloat() * 0.13f
					val level = LOW + random.nextFloat() * (1f - LOW)
					springs[i].animateTo(level * ENVELOPE[i], 0.2f, 0.62f)
				}
			}
		}
		for (s in springs) s.step(dt)
	}

	private companion object {
		const val REST = 0.0f
		const val LOW = 0.42f

		// The outer bars stay a little lower, as in the real island.
		val ENVELOPE = floatArrayOf(0.66f, 0.84f, 1f, 1f, 1f, 0.92f)
	}
}
