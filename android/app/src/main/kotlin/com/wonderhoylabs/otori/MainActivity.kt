package com.wonderhoylabs.otori

import android.content.ActivityNotFoundException
import android.content.ComponentName
import android.content.Intent
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.wonderhoylabs.otori.ui.BrandHeader
import com.wonderhoylabs.otori.ui.OtoriTheme

/** Setup: a card for each of the two system switches the island needs, each with a button that opens its page. */
class MainActivity : ComponentActivity() {
	private var overlayOn by mutableStateOf(false)
	private var mediaOn by mutableStateOf(false)

	override fun onCreate(savedInstanceState: Bundle?) {
		enableEdgeToEdge()
		super.onCreate(savedInstanceState)
		setContent {
			OtoriTheme {
				Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
					SetupScreen(
						overlayOn = overlayOn,
						mediaOn = mediaOn,
						onOverlay = ::openAccessibility,
						onMedia = ::openNotificationAccess,
						onPreview = { startActivity(Intent(this, PreviewActivity::class.java)) },
					)
				}
			}
		}
	}

	override fun onResume() {
		super.onResume()
		overlayOn = isAccessibilityOn()
		mediaOn = isNotificationAccessOn()
	}

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

	private fun isAccessibilityOn(): Boolean {
		val enabled =
			Settings.Secure.getString(contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES)
				?: return false
		val me = ComponentName(this, IslandService::class.java)
		return enabled.split(':').any { ComponentName.unflattenFromString(it) == me }
	}

	private fun isNotificationAccessOn(): Boolean {
		val enabled = Settings.Secure.getString(contentResolver, "enabled_notification_listeners") ?: return false
		return enabled.contains(packageName)
	}
}

@Composable
private fun SetupScreen(
	overlayOn: Boolean,
	mediaOn: Boolean,
	onOverlay: () -> Unit,
	onMedia: () -> Unit,
	onPreview: () -> Unit,
) {
	Box(
		modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).safeDrawingPadding(),
		contentAlignment = Alignment.TopCenter,
	) {
		Column(
			modifier = Modifier.widthIn(max = 560.dp).fillMaxWidth().padding(horizontal = 20.dp, vertical = 24.dp),
			verticalArrangement = Arrangement.spacedBy(16.dp),
		) {
			BrandHeader(subtitle = stringResource(R.string.tagline), logo = painterResource(R.drawable.otori_logo))
			Text(
				if (overlayOn && mediaOn) {
					"You're all set. Play some music, then long press the island."
				} else {
					"Otori needs two permissions. Tap Turn on for each one."
				},
				style = MaterialTheme.typography.bodyLarge,
				color = MaterialTheme.colorScheme.onSurfaceVariant,
			)
			Step(
				title = "1. Show the island",
				body = "Lets Otori draw at the top of the screen, around the camera.",
				howTo = "Opens Settings. Tap Otori, turn on Use Otori, then tap Allow.",
				on = overlayOn,
				onClick = onOverlay,
			)
			Step(
				title = "2. See what's playing",
				body = "Lets Otori read the song, artwork and controls from your music app.",
				howTo = "Opens Settings. Turn on the switch, then tap Allow.",
				on = mediaOn,
				onClick = onMedia,
			)
			OutlinedButton(onClick = onPreview, modifier = Modifier.fillMaxWidth()) { Text("Try the preview") }
		}
	}
}

@Composable
private fun Step(title: String, body: String, howTo: String, on: Boolean, onClick: () -> Unit) {
	val colors =
		if (on) {
			CardDefaults.cardColors(
				containerColor = MaterialTheme.colorScheme.primaryContainer,
				contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
			)
		} else {
			CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer)
		}
	Card(modifier = Modifier.fillMaxWidth(), colors = colors) {
		Column(modifier = Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
			Text(title, style = MaterialTheme.typography.titleMedium)
			Text(body, style = MaterialTheme.typography.bodyMedium)
			if (on) {
				FilledTonalButton(onClick = onClick, modifier = Modifier.fillMaxWidth().padding(top = 4.dp)) {
					Text("✓  On")
				}
			} else {
				Button(onClick = onClick, modifier = Modifier.fillMaxWidth().padding(top = 4.dp)) { Text("Turn on") }
				Text(
					howTo,
					style = MaterialTheme.typography.bodySmall,
					color = MaterialTheme.colorScheme.onSurfaceVariant,
				)
			}
		}
	}
}
