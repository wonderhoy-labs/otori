package com.wonderhoylabs.otori

import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification

/**
 * Exists so that Android lets the island read media sessions, which is tied to notification access. It ignores what
 * notifications say, and only uses their arrival and removal as a cue that the status bar's icons have moved.
 */
class IslandNotificationListener : NotificationListenerService() {
    override fun onNotificationPosted(sbn: StatusBarNotification?) = IslandService.onStatusBarMayHaveChanged()

    override fun onNotificationRemoved(sbn: StatusBarNotification?) = IslandService.onStatusBarMayHaveChanged()
}
