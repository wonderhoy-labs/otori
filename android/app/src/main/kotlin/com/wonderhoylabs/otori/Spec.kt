package com.wonderhoylabs.otori

import android.graphics.Color

/**
 * Every number the island draws, in iOS points on a 393 pt wide screen.
 * Where they came from, and how closely they fit, is in the README's Under the hood section.
 */
object Spec {
	const val SCREEN_PT = 393f

	const val PILL_H = 37.16f
	const val IOS_TOP = 11.11f
	const val COMPACT_W = 187.12f
	const val EXPANDED_W = 371f
	const val EXPANDED_H = 200.6f
	const val EXPANDED_R = 44f

	/**
	 * The idle island is a circle inside the camera hole, as a share of the hole's diameter. Unlike the iPhone, a
	 * Pixel has no sensor bar to hide, so at rest only the camera shows. It stays smaller than the hole so that no
	 * black ring can show around the lens.
	 */
	const val IDLE_HOLE_FILL = 0.86f

	/** The idle circle's diameter on a display with no central camera hole to hide in. */
	const val IDLE_D_NO_HOLE = 14f

	/** Room below the island for its drop shadow. */
	const val SHADOW_ROOM = 64f

	object Motion {
		const val EXPAND_W_RESPONSE = 0.530f
		const val EXPAND_W_DAMPING = 0.805f
		const val EXPAND_W_DELAY = 0.046f
		const val EXPAND_H_RESPONSE = 0.537f
		const val EXPAND_H_DAMPING = 0.808f
		const val EXPAND_H_DELAY = 0.045f

		const val COLLAPSE_W1_TARGET = 133.6f
		const val COLLAPSE_W1_RESPONSE = 0.504f
		const val COLLAPSE_W1_DAMPING = 0.817f
		const val COLLAPSE_W1_DELAY = 0.019f
		const val COLLAPSE_SWITCH = 0.345f
		const val COLLAPSE_W2_RESPONSE = 0.429f
		const val COLLAPSE_W2_DAMPING = 0.669f
		const val COLLAPSE_H_RESPONSE = 0.459f
		const val COLLAPSE_H_DAMPING = 0.937f
		const val COLLAPSE_H_DELAY = 0.021f
		const val COLLAPSE_TOP_RISE = 2.6f
		const val COLLAPSE_H_BUMP = 2.2f

		const val APPEAR_RESPONSE = 0.556f
		const val APPEAR_DAMPING = 0.793f
		const val APPEAR_DELAY = 0.021f
		const val DISAPPEAR_RESPONSE = 0.505f
		const val DISAPPEAR_DAMPING = 0.800f
		const val DISAPPEAR_DELAY = 0.026f
	}

	object Shadow {
		const val ALPHA = 0.36f
		const val SIGMA = 13f
		const val OFFSET_Y = 6f
	}

	/** Media island content, relative to the expanded island's top left. */
	object Media {
		const val ART_X = 21.6f
		const val ART_Y = 24.2f
		const val ART_SIZE = 65.2f
		const val ART_R = 11f

		const val TEXT_X = 106.6f
		const val TITLE_BASELINE = 51.4f
		const val ARTIST_BASELINE = 71.9f
		const val TEXT_RIGHT = 300.3f

		const val WAVE_LEFT = 316.3f
		const val WAVE_RIGHT = 342f
		const val WAVE_CY = 48.8f
		const val WAVE_MAX_H = 14f

		const val TIME_LEFT = 25f
		const val TIME_RIGHT = 345.6f
		const val TIME_CY = 113.6f

		const val BAR_LEFT = 56.8f
		const val BAR_RIGHT = 306.7f
		const val BAR_CY = 113.9f
		const val BAR_H = 7.2f
		const val BAR_H_SCRUB = 10f

		const val CONTROL_CY = 157.2f
		const val BACK_CX = 114.6f
		const val PLAY_CX = 185.3f
		const val FORWARD_CX = 255.5f
		const val CONTROL_HIT_R = 32f
	}

	object Compact {
		const val ART_INSET = 8.8f
		const val ART_SIZE = 22.6f
		const val ART_R = 5f
		const val WAVE_INSET = 9.4f
		const val BAR_W = 2.4f
		const val BAR_PITCH = 3.6f
		const val WAVE_MAX_H = 15f

		/** Space kept between the island and the nearest status bar item, as measured on the iPhone. */
		const val ICON_GAP = 21.7f

		/** Space kept between the camera hole and the artwork or waveform, which sets the island's minimum width. */
		const val CAMERA_GAP = 3f
	}

	/**
	 * Inter settings that match SF Pro's widths and x-height, calibrated against the real SF fonts:
	 * within 0.3% (semibold) and 0.7% (regular) on sample titles. SF Pro is licensed for Apple platforms only.
	 */
	object Type {
		const val TITLE_SIZE = 16.8f
		const val TITLE_WEIGHT = 600
		const val TITLE_OPSZ = 20
		const val TITLE_TRACKING = -0.0017f
		const val ARTIST_SIZE = 16.6f
		const val ARTIST_WEIGHT = 400
		const val ARTIST_OPSZ = 20
		const val ARTIST_TRACKING = -0.0036f
		const val TIME_SIZE = 12f
		const val TIME_WEIGHT = 500
		const val TIME_OPSZ = 14
	}

	object Colors {
		val BLACK = Color.BLACK
		val TITLE = Color.WHITE
		val ARTIST = Color.rgb(0x9E, 0x9D, 0xA3)
		val TIME = Color.rgb(0x8C, 0x8B, 0x91)
		val BAR_FILL = Color.rgb(0x9B, 0x9B, 0xA3)
		val BAR_TRACK = Color.rgb(0x24, 0x24, 0x26)
		val PLACEHOLDER_ART = Color.rgb(0x3A, 0x3A, 0x3C)
	}

	object Timing {
		const val LONG_PRESS_MS = 400L
		const val PAUSE_LINGER_MS = 20_000L
		const val EXPANDED_IDLE_MS = 12_000L
	}
}
