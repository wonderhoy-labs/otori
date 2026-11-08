package com.wonderhoylabs.otori

import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification

/**
 * Notification access lets the island read media sessions, and shows it other apps' Live Updates. Every arrival and
 * removal is also a cue that the status bar's icons have moved.
 */
class IslandNotificationListener : NotificationListenerService() {
	override fun onListenerConnected() {
		instance = this
		IslandService.onNotificationsReset(activeNotifications.orEmpty().toList())
	}

	override fun onListenerDisconnected() {
		if (instance === this) instance = null
	}

	override fun onNotificationPosted(sbn: StatusBarNotification?) {
		if (sbn != null) IslandService.onNotification(sbn, removed = false)
		IslandService.onStatusBarMayHaveChanged()
	}

	override fun onNotificationRemoved(sbn: StatusBarNotification?) {
		if (sbn != null) IslandService.onNotification(sbn, removed = true)
		IslandService.onStatusBarMayHaveChanged()
	}

	companion object {
		// Cleared when disconnected, so this can't outlive the listener.
		@android.annotation.SuppressLint("StaticFieldLeak")
		private var instance: IslandNotificationListener? = null

		/** What's showing now, for an island that starts after the listener. */
		fun active(): List<StatusBarNotification> = runCatching {
			instance?.activeNotifications?.toList()
		}.getOrNull().orEmpty()
	}
}
