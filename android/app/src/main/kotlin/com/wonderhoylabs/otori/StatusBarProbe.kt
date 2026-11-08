package com.wonderhoylabs.otori

import android.accessibilityservice.AccessibilityService
import android.graphics.Rect
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityWindowInfo

/** Where the status bar's items end on either side of the camera, in pixels. Null means that side is empty. */
data class StatusBarClearance(val leftEnd: Int?, val rightStart: Int?)

/**
 * Measures the status bar, so that the compact island can fit between its icons. It looks only at the system's
 * status bar window, and reads only where its items are, never any text.
 */
class StatusBarProbe(private val service: AccessibilityService) {
	private val bounds = Rect()

	/** Null when no status bar is showing, as in a fullscreen app. */
	fun measure(centerX: Float, displayWidth: Int): StatusBarClearance? {
		val root = service.windows.firstOrNull { isStatusBarShaped(it, displayWidth) }?.root ?: return null
		if (root.packageName != SYSTEM_UI) return null
		var leftEnd: Int? = null
		var rightStart: Int? = null
		fun visit(node: AccessibilityNodeInfo) {
			// Android caches these nodes, and only clears them on events this service doesn't subscribe to, so
			// without this the island could keep measuring an old copy of the status bar.
			if (!node.refresh()) return
			if (!node.isVisibleToUser && node.childCount == 0) return
			if (node.childCount > 0) {
				for (i in 0 until node.childCount) node.getChild(i)?.let { visit(it) }
				return
			}
			node.getBoundsInScreen(bounds)
			// Only leaves count, since containers include empty space. Anything across the camera isn't an icon.
			if (bounds.isEmpty || (bounds.left < centerX && bounds.right > centerX)) return
			if (bounds.right <= centerX) {
				leftEnd = maxOf(leftEnd ?: bounds.right, bounds.right)
			} else {
				rightStart = minOf(rightStart ?: bounds.left, bounds.left)
			}
		}
		visit(root)
		if (leftEnd == null && rightStart == null) return null
		return StatusBarClearance(leftEnd, rightStart)
	}

	private fun isStatusBarShaped(window: AccessibilityWindowInfo, displayWidth: Int): Boolean {
		if (window.type != AccessibilityWindowInfo.TYPE_SYSTEM) return false
		window.getBoundsInScreen(bounds)
		return bounds.top == 0 && bounds.width() >= displayWidth * 9 / 10 && bounds.height() < displayWidth / 3
	}

	private companion object {
		const val SYSTEM_UI = "com.android.systemui"
	}
}
