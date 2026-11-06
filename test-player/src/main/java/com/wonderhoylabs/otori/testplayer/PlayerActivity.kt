package com.wonderhoylabs.otori.testplayer

import android.app.Activity
import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import android.view.Gravity
import android.widget.TextView

/** Starts the player. Launching it brings the "music app" to the front, which the island should react to. */
class PlayerActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        startForegroundService(Intent(this, PlayerService::class.java))
        setContentView(TextView(this).apply {
            text = "Otori Test Player"
            textSize = 28f
            gravity = Gravity.CENTER
            setTextColor(Color.BLACK)
            setBackgroundColor(Color.WHITE)
        })
    }
}
