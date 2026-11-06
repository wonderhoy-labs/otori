package com.wonderhoylabs.otori.testplayer

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/** Crowds the status bar for tests: posts the given number of notifications, each with its own icon. */
class NotifyReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val manager = context.getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(NotificationChannel("crowd", "Crowd", NotificationManager.IMPORTANCE_DEFAULT))
        val count = intent.getIntExtra("count", 0)
        for (i in ICONS.indices) {
            val id = 100 + i
            if (i >= count) {
                manager.cancel(id)
                continue
            }
            manager.notify(
                id,
                Notification.Builder(context, "crowd")
                    .setSmallIcon(ICONS[i])
                    .setContentTitle("Crowd $i")
                    .setGroup("crowd$i")
                    .build(),
            )
        }
    }

    private companion object {
        val ICONS = intArrayOf(
            android.R.drawable.ic_dialog_email, android.R.drawable.ic_menu_camera, android.R.drawable.stat_sys_download,
            android.R.drawable.ic_menu_call, android.R.drawable.ic_menu_compass, android.R.drawable.ic_menu_gallery,
            android.R.drawable.ic_menu_mapmode, android.R.drawable.ic_menu_mylocation, android.R.drawable.ic_menu_today,
            android.R.drawable.ic_menu_send, android.R.drawable.ic_menu_search, android.R.drawable.ic_menu_share,
            android.R.drawable.ic_menu_upload, android.R.drawable.ic_menu_view,
        )
    }
}
