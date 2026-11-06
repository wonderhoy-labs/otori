package com.wonderhoylabs.otori

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.ComponentName
import android.content.Intent
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.os.Bundle
import android.provider.Settings
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.view.WindowInsets
import android.widget.Button
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView

/** Setup: a card for each of the two system switches the island needs, each with a button that opens its page. */
class MainActivity : Activity() {

    /** One step of the setup. */
    private inner class Step(val button: Button, val hint: TextView)

    private lateinit var summary: TextView
    private lateinit var overlayStep: Step
    private lateinit var mediaStep: Step

    private fun dp(v: Float) = (v * resources.displayMetrics.density).toInt()

    private fun inter(weight: Int): Typeface =
        Typeface.Builder(assets, "fonts/InterVariable.ttf").setFontVariationSettings("'wght' $weight, 'opsz' 20").build()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val column = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val header = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        header.addView(
            ImageView(this).apply {
                setImageResource(R.drawable.otori_logo)
                scaleType = ImageView.ScaleType.CENTER_CROP
                background = GradientDrawable().apply { cornerRadius = dp(14f).toFloat() }
                clipToOutline = true
                contentDescription = null
            },
            LinearLayout.LayoutParams(dp(60f), dp(60f)).apply { marginEnd = dp(14f) },
        )
        header.addView(TextView(this).apply {
            text = getString(R.string.app_name)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 34f)
            typeface = inter(700)
            setTextColor(Color.BLACK)
        })
        column.addView(header)
        summary = TextView(this).apply {
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
            typeface = inter(400)
            setTextColor(SECONDARY_TEXT)
            setPadding(0, dp(12f), 0, dp(22f))
        }
        column.addView(summary)

        overlayStep = addStep(
            column,
            "1. Show the island",
            "Lets Otori draw at the top of the screen, around the camera.",
        ) { openAccessibility() }
        mediaStep = addStep(
            column,
            "2. See what's playing",
            "Lets Otori read the song, artwork and controls from your music app.",
        ) { openNotificationAccess() }

        column.addView(
            pillButton("Try the Preview", FILL_NEUTRAL, BLUE).apply {
                setOnClickListener { startActivity(Intent(this@MainActivity, PreviewActivity::class.java)) }
            },
            LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { topMargin = dp(6f) },
        )

        val scroll = ScrollView(this).apply {
            clipToPadding = false
            addView(column, MATCH_PARENT, WRAP_CONTENT)
        }
        scroll.setOnApplyWindowInsetsListener { v, insets ->
            val bars = insets.getInsets(WindowInsets.Type.systemBars() or WindowInsets.Type.displayCutout())
            v.setPadding(dp(20f), bars.top + dp(14f), dp(20f), bars.bottom + dp(24f))
            insets
        }
        setContentView(scroll)
    }

    private fun addStep(parent: LinearLayout, title: String, body: String, onClick: () -> Unit): Step {
        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(18f), dp(18f), dp(18f), dp(18f))
            background = GradientDrawable().apply {
                setColor(Color.WHITE)
                cornerRadius = dp(20f).toFloat()
            }
        }
        card.addView(TextView(this).apply {
            text = title
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 18f)
            typeface = inter(600)
            setTextColor(Color.BLACK)
        })
        card.addView(TextView(this).apply {
            text = body
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
            typeface = inter(400)
            setTextColor(SECONDARY_TEXT)
            setPadding(0, dp(4f), 0, dp(14f))
        })
        val button = pillButton("", BLUE, Color.WHITE).apply { setOnClickListener { onClick() } }
        card.addView(button, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
        val hint = TextView(this).apply {
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
            typeface = inter(400)
            setTextColor(SECONDARY_TEXT)
            setPadding(0, dp(10f), 0, 0)
        }
        card.addView(hint)
        parent.addView(card, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { bottomMargin = dp(14f) })
        return Step(button, hint)
    }

    private fun pillButton(label: String, fill: Int, textColor: Int) = Button(this).apply {
        text = label
        isAllCaps = false
        stateListAnimator = null
        minHeight = dp(52f)
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 17f)
        typeface = inter(600)
        setTextColor(textColor)
        background = pillBackground(fill)
    }

    private fun pillBackground(fill: Int) = RippleDrawable(
        ColorStateList.valueOf(Color.argb(60, 128, 128, 128)),
        GradientDrawable().apply {
            setColor(fill)
            cornerRadius = dp(14f).toFloat()
        },
        null,
    )

    /** Android keeps a service's own page for system apps, so this opens the list with Otori's row highlighted. */
    private fun openAccessibility() {
        val me = ComponentName(this, IslandService::class.java).flattenToString()
        open(
            Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)
                .putExtra(":settings:fragment_args_key", me)
                .putExtra(":settings:show_fragment_args", Bundle().apply { putString(":settings:fragment_args_key", me) }),
            Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS),
        )
    }

    /** This one has a public link straight to Otori's switch. */
    private fun openNotificationAccess() {
        val me = ComponentName(this, IslandNotificationListener::class.java).flattenToString()
        open(
            Intent(Settings.ACTION_NOTIFICATION_LISTENER_DETAIL_SETTINGS)
                .putExtra(Settings.EXTRA_NOTIFICATION_LISTENER_COMPONENT_NAME, me),
            Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS),
        )
    }

    private fun open(preferred: Intent, fallback: Intent) {
        try {
            startActivity(preferred)
        } catch (_: ActivityNotFoundException) {
            startActivity(fallback)
        }
    }

    override fun onResume() {
        super.onResume()
        val overlay = isAccessibilityOn()
        val media = isNotificationAccessOn()
        show(overlayStep, overlay, "Opens Settings. Tap Otori, turn on Use Otori, then tap Allow.")
        show(mediaStep, media, "Opens Settings. Turn on the switch, then tap Allow.")
        summary.text = when {
            overlay && media -> "You're all set. Play some music, then long press the island."
            else -> "Otori needs two permissions. Tap Turn On for each one."
        }
    }

    private fun show(step: Step, on: Boolean, howTo: String) {
        step.button.text = if (on) "✓  On" else "Turn On"
        step.button.setTextColor(if (on) GREEN_TEXT else Color.WHITE)
        step.button.background = pillBackground(if (on) GREEN_FILL else BLUE)
        step.hint.text = howTo
        step.hint.visibility = if (on) View.GONE else View.VISIBLE
    }

    private fun isAccessibilityOn(): Boolean {
        val enabled = Settings.Secure.getString(contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES)
            ?: return false
        val me = ComponentName(this, IslandService::class.java)
        return enabled.split(':').any { ComponentName.unflattenFromString(it) == me }
    }

    private fun isNotificationAccessOn(): Boolean {
        val enabled = Settings.Secure.getString(contentResolver, "enabled_notification_listeners") ?: return false
        return enabled.contains(packageName)
    }

    private companion object {
        val BLUE = Color.rgb(0x00, 0x7A, 0xFF)
        val FILL_NEUTRAL = Color.rgb(0xE5, 0xE5, 0xEA)
        val GREEN_FILL = Color.rgb(0xE3, 0xF7, 0xE8)
        val GREEN_TEXT = Color.rgb(0x1B, 0x8A, 0x3B)
        val SECONDARY_TEXT = Color.rgb(0x6C, 0x6C, 0x70)
    }
}
