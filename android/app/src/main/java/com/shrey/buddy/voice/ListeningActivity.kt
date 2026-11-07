package com.shrey.buddy.voice

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Mic
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.shrey.buddy.ui.theme.BuddyColors
import com.shrey.buddy.ui.theme.BuddyTheme
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** What "Hey Buddy" is doing right now — drives the on-screen listening overlay. */
sealed interface Live {
    data object Idle : Live
    data class Listening(val partial: String = "") : Live
    data class Thinking(val heard: String) : Live
    data class Done(val result: String) : Live
}

object VoiceLive {
    private val _state = MutableStateFlow<Live>(Live.Idle)
    val state: StateFlow<Live> = _state.asStateFlow()
    fun set(live: Live) { _state.value = live }
}

/**
 * Full-screen glow shown when "Hey Buddy" is heard — over other apps and the lock screen, turning the
 * screen on — so it's obvious Buddy is listening. Shows the live transcript, then the result.
 */
class ListeningActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setShowWhenLocked(true)
        setTurnScreenOn(true)
        setContent {
            BuddyTheme {
                val live by VoiceLive.state.collectAsStateWithLifecycle()
                LaunchedEffect(live) {
                    when (live) {
                        Live.Idle -> finish()
                        is Live.Done -> { delay(4_000); finish() }
                        else -> Unit
                    }
                }
                val pulse by rememberInfiniteTransition(label = "orb").animateFloat(
                    1f, 1.18f, infiniteRepeatable(tween(700), RepeatMode.Reverse), label = "pulse",
                )
                Box(
                    Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.72f))
                        .clickable(remember { MutableInteractionSource() }, indication = null) { finish() },
                    contentAlignment = Alignment.Center,
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(28.dp), modifier = Modifier.padding(32.dp)) {
                        Box(
                            Modifier.size(150.dp)
                                .scale(if (live is Live.Listening) pulse else 1f)
                                .clip(CircleShape)
                                .background(Brush.radialGradient(listOf(BuddyColors.Voice, BuddyColors.Voice.copy(alpha = 0.35f), Color.Transparent))),
                            contentAlignment = Alignment.Center,
                        ) {
                            if (live is Live.Thinking) CircularProgressIndicator(color = Color.White, strokeWidth = 3.dp)
                            else Icon(Icons.Rounded.Mic, contentDescription = null, tint = Color.White, modifier = Modifier.size(56.dp))
                        }
                        val (title, body, color) = when (val l = live) {
                            is Live.Listening -> Triple("Listening…", l.partial.ifBlank { "Say it — “spent 450 on lunch”" }, BuddyColors.TextPrimary)
                            is Live.Thinking -> Triple("Got it", "“${l.heard}”", BuddyColors.TextPrimary)
                            is Live.Done -> Triple("Done", l.result, BuddyColors.Lime)
                            Live.Idle -> Triple("", "", BuddyColors.TextPrimary)
                        }
                        Text(title, style = MaterialTheme.typography.headlineMedium, color = BuddyColors.TextPrimary)
                        Text(body, style = MaterialTheme.typography.titleMedium, color = color, textAlign = TextAlign.Center)
                    }
                }
            }
        }
    }
}
