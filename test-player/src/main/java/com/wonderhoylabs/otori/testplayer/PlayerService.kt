package com.wonderhoylabs.otori.testplayer

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.BitmapFactory
import android.media.AudioAttributes
import android.media.MediaMetadata
import android.media.MediaPlayer
import android.media.session.MediaSession
import android.media.session.PlaybackState
import android.os.IBinder
import android.os.SystemClock

/**
 * A real MediaSession backed by real audio, using the same track and artwork as the iOS reference harness.
 * Skips change the title, so that tests can see the command reach the app and come back through the session.
 */
class PlayerService : Service() {
    private lateinit var session: MediaSession
    private lateinit var player: MediaPlayer
    private val titles = listOf("Island Reference Track", "Second Track On The Queue", "A Much Longer Track Title That Has To Scroll")
    private var track = 0

    override fun onCreate() {
        super.onCreate()
        val channel = NotificationChannel("player", "Player", NotificationManager.IMPORTANCE_LOW)
        getSystemService(NotificationManager::class.java).createNotificationChannel(channel)

        player = MediaPlayer.create(
            this, R.raw.track,
            AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA)
                .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC).build(),
            0,
        )
        player.isLooping = true

        session = MediaSession(this, "OtoriTestPlayer")
        session.setSessionActivity(
            PendingIntent.getActivity(
                this, 0, Intent(this, PlayerActivity::class.java),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            ),
        )
        session.setCallback(object : MediaSession.Callback() {
            override fun onPlay() {
                player.start()
                publishState()
            }

            override fun onPause() {
                player.pause()
                publishState()
            }

            override fun onSkipToNext() {
                track = (track + 1) % titles.size
                player.seekTo(0)
                publishMetadata()
                publishState()
            }

            override fun onSkipToPrevious() {
                track = (track + titles.size - 1) % titles.size
                player.seekTo(0)
                publishMetadata()
                publishState()
            }

            override fun onSeekTo(pos: Long) {
                player.seekTo(pos.toInt())
                publishState()
            }

            override fun onCustomAction(action: String, extras: android.os.Bundle?) {
                favorite = !favorite
                publishState()
            }
        })
        session.isActive = true
        publishMetadata()
        player.start()
        publishState()
        startForeground(1, notification(), ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK)
    }

    private var favorite = false

    private fun notification(): Notification =
        Notification.Builder(this, "player")
            .setSmallIcon(android.R.drawable.ic_media_play)
            .setContentTitle(titles[track])
            .setContentText("Test Artist")
            .setStyle(Notification.MediaStyle().setMediaSession(session.sessionToken))
            .build()

    private fun publishMetadata() {
        val art = BitmapFactory.decodeResource(resources, R.drawable.test_cover)
        session.setMetadata(
            MediaMetadata.Builder()
                .putString(MediaMetadata.METADATA_KEY_TITLE, titles[track])
                .putString(MediaMetadata.METADATA_KEY_ARTIST, "Test Artist")
                .putString(MediaMetadata.METADATA_KEY_ALBUM, "Measurement Sessions")
                .putLong(MediaMetadata.METADATA_KEY_DURATION, player.duration.toLong())
                .putBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART, art)
                .build(),
        )
    }

    private fun publishState() {
        val playing = player.isPlaying
        session.setPlaybackState(
            PlaybackState.Builder()
                .setActions(
                    PlaybackState.ACTION_PLAY or PlaybackState.ACTION_PAUSE or PlaybackState.ACTION_PLAY_PAUSE or
                        PlaybackState.ACTION_SKIP_TO_NEXT or PlaybackState.ACTION_SKIP_TO_PREVIOUS or
                        PlaybackState.ACTION_SEEK_TO,
                )
                .addCustomAction(
                    PlaybackState.CustomAction.Builder(
                        if (favorite) "favorite.remove" else "favorite.add",
                        if (favorite) "Unfavorite" else "Favorite",
                        android.R.drawable.btn_star,
                    ).build(),
                )
                .setState(
                    if (playing) PlaybackState.STATE_PLAYING else PlaybackState.STATE_PAUSED,
                    player.currentPosition.toLong(),
                    if (playing) 1f else 0f,
                    SystemClock.elapsedRealtime(),
                )
                .build(),
        )
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        session.release()
        player.release()
        super.onDestroy()
    }
}
