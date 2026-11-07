package com.shrey.buddy.capture

import android.content.Intent
import android.os.Bundle
import android.speech.RecognizerIntent
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.Send
import androidx.compose.material.icons.rounded.Mic
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.key
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.shrey.buddy.BuddyApp
import com.shrey.buddy.data.items.Kinds
import com.shrey.buddy.data.items.Note
import com.shrey.buddy.ui.components.fieldColors
import com.shrey.buddy.ui.theme.BuddyColors
import com.shrey.buddy.ui.theme.BuddyTheme
import com.shrey.buddy.voice.VoiceCommands
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Quick capture (DESIGN.md §4.5): a floating sheet opened from the widget, Quick Settings tile or the
 * share sheet. Whatever you type or say goes through the same Gemini command pipeline as "Hey Buddy".
 */
class QuickCaptureActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val container = (application as BuddyApp).container
        current.value = intent

        setContent {
            // Re-keyed per intent, so a new share while the sheet is open starts fresh with the new text.
            val currentIntent = current.value
            key(currentIntent) {
            val shared = currentIntent?.takeIf { it.action == Intent.ACTION_SEND }?.getStringExtra(Intent.EXTRA_TEXT)?.trim()
            val startWithVoice = currentIntent?.getBooleanExtra(EXTRA_VOICE, false) == true
            BuddyTheme {
                val scope = rememberCoroutineScope()
                var text by remember { mutableStateOf(shared.orEmpty()) }
                var busy by remember { mutableStateOf(false) }
                var result by remember { mutableStateOf<String?>(null) }
                val focus = remember { FocusRequester() }

                fun run(input: String) {
                    if (input.isBlank() || busy) return
                    busy = true
                    scope.launch {
                        result = try {
                            VoiceCommands.handle(container, input.trim())
                        } catch (e: Exception) {
                            e.message ?: "Couldn't reach Buddy AI"
                        }
                        busy = false
                        delay(2_500)
                        finish()
                    }
                }

                val speech = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { r ->
                    val heard = r.data?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)?.firstOrNull()
                    if (!heard.isNullOrBlank()) {
                        text = heard
                        run(heard)
                    }
                }
                fun listen() = runCatching {
                    speech.launch(
                        Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
                            .putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                            .putExtra(RecognizerIntent.EXTRA_LANGUAGE, "en-IN")
                            .putExtra(RecognizerIntent.EXTRA_PROMPT, "Tell Buddy…"),
                    )
                }
                LaunchedEffect(Unit) { if (startWithVoice) listen() else runCatching { focus.requestFocus() } }

                // Tap outside to dismiss.
                Box(
                    Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.55f))
                        .clickable(remember { MutableInteractionSource() }, indication = null) { finish() },
                    contentAlignment = Alignment.BottomCenter,
                ) {
                    Surface(
                        shape = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp),
                        color = BuddyColors.Surface,
                        modifier = Modifier.fillMaxWidth().imePadding()
                            .clickable(remember { MutableInteractionSource() }, indication = null) {},
                    ) {
                        Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                            Text("Tell Buddy", style = MaterialTheme.typography.titleLarge)
                            when {
                                result != null -> Text(result!!, style = MaterialTheme.typography.bodyLarge, color = BuddyColors.Lime)
                                busy -> Row(verticalAlignment = Alignment.CenterVertically) {
                                    CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                                    Text("Working on it…", modifier = Modifier.padding(start = 10.dp))
                                }
                                else -> {
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        OutlinedTextField(
                                            value = text,
                                            onValueChange = { text = it },
                                            placeholder = { Text("“Spent 450 on lunch”, “Remind me to call mom tomorrow”") },
                                            shape = RoundedCornerShape(18.dp),
                                            colors = fieldColors(BuddyColors.Voice),
                                            maxLines = 4,
                                            modifier = Modifier.weight(1f).focusRequester(focus),
                                        )
                                        IconButton(onClick = { listen() }) { Icon(Icons.Rounded.Mic, contentDescription = "Speak", tint = BuddyColors.Voice) }
                                        FilledIconButton(
                                            onClick = { run(text) },
                                            enabled = text.isNotBlank(),
                                            colors = IconButtonDefaults.filledIconButtonColors(containerColor = BuddyColors.Voice, contentColor = BuddyColors.Background),
                                        ) { Icon(Icons.AutoMirrored.Rounded.Send, contentDescription = "Send") }
                                    }
                                    if (shared != null) {
                                        // Shared links/text: offer direct saves without AI.
                                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                            OutlinedButton(onClick = {
                                                scope.launch { container.tasks.add(shared.take(200)); result = "Saved as a task"; delay(1_200); finish() }
                                            }) { Text("Save as task") }
                                            OutlinedButton(onClick = {
                                                scope.launch {
                                                    container.items.put(Kinds.NOTE, Note(title = shared.lineSequence().first().take(60), body = shared), Note.serializer())
                                                    result = "Saved to notes"; delay(1_200); finish()
                                                }
                                            }) { Text("Save as note") }
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
            }
        }
    }

    private val current = mutableStateOf<Intent?>(null)

    /** A new share/tile tap while the sheet is already open: show the new content. */
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        current.value = intent
    }

    companion object {
        const val EXTRA_VOICE = "voice"
    }
}
