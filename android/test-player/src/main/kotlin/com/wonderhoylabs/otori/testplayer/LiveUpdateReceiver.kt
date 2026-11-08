package com.wonderhoylabs.otori.testplayer

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.os.Build

/**
 * Posts a Live Update the way any app would, for tests of the island: an ongoing ProgressStyle notification that asks
 * to be promoted, with short critical text. "progress" out of 100 sets how far along it is, and a negative one clears it.
 */
class LiveUpdateReceiver : BroadcastReceiver() {
	override fun onReceive(context: Context, intent: Intent) {
		val manager = context.getSystemService(NotificationManager::class.java)
		val progress = intent.getIntExtra("progress", 40)
		if (progress < 0 || Build.VERSION.SDK_INT < 36) {
			manager.cancel(ID)
			return
		}
		manager.createNotificationChannel(NotificationChannel("live", "Live updates", NotificationManager.IMPORTANCE_DEFAULT))
		val open = PendingIntent.getActivity(
			context,
			0,
			Intent(context, PlayerActivity::class.java),
			PendingIntent.FLAG_IMMUTABLE,
		)
		val minutes = ((100 - progress) / 10).coerceAtLeast(1)
		manager.notify(
			ID,
			Notification.Builder(context, "live")
				.setSmallIcon(android.R.drawable.ic_menu_mylocation)
				.setContentTitle(intent.getStringExtra("title") ?: "Your ride is on its way")
				.setContentText(intent.getStringExtra("text") ?: "Arriving at the main entrance")
				.setColor(Color.rgb(0x34, 0xC7, 0x59))
				.setOngoing(true)
				.setContentIntent(open)
				.setRequestPromotedOngoing(true)
				.setShortCriticalText(intent.getStringExtra("chip") ?: "$minutes min")
				.setStyle(Notification.ProgressStyle().setProgress(progress))
				.build(),
		)
	}

	private companion object {
		const val ID = 300
	}
}
