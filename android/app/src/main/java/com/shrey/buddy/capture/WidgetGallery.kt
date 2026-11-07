package com.shrey.buddy.capture

import android.appwidget.AppWidgetManager
import android.content.ComponentName
import android.content.Context
import android.os.Build
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.glance.appwidget.GlanceAppWidgetManager
import androidx.glance.appwidget.GlanceAppWidgetReceiver
import com.shrey.buddy.BuildConfig
import com.shrey.buddy.ui.components.BuddyCard
import com.shrey.buddy.ui.theme.BuddyColors
import kotlin.reflect.KClass

/** Every home-screen widget Buddy has, for the in-app gallery and the phone's widget picker previews. */
object WidgetGallery {
    data class Entry(val emoji: String, val name: String, val what: String, val receiver: KClass<out GlanceAppWidgetReceiver>)

    val all = listOf(
        Entry("✅", "Today", "Today's tasks and reminders — tick them off right there", TodayWidgetReceiver::class),
        Entry("💸", "Money", "Spent this month and today, last 3 transactions, add an expense", MoneyWidgetReceiver::class),
        Entry("❤️", "Health", "Steps, sleep, water — with +250 / +500 ml buttons", HealthWidgetReceiver::class),
        Entry("🏃", "Fitness", "This week's km and sessions, today's plan, start a run or workout", FitnessWidgetReceiver::class),
        Entry("🔁", "Habits", "Today's habits — tap to tick", HabitsWidgetReceiver::class),
        Entry("✦", "Buddy", "Month's spend, open tasks, Tell Buddy and Ask buttons", BuddyWidgetReceiver::class),
    )

    /** Asks the launcher to place the widget (it shows its own "Add to home screen" confirmation). */
    fun pin(context: Context, entry: Entry): Boolean {
        val manager = AppWidgetManager.getInstance(context)
        if (!manager.isRequestPinAppWidgetSupported) return false
        return runCatching { manager.requestPinAppWidget(ComponentName(context, entry.receiver.java), null, null) }.getOrDefault(false)
    }

    /** Android 15+: show the real widget (with your data) in the launcher's widget picker. Once per app version. */
    suspend fun publishPreviews(context: Context) {
        if (Build.VERSION.SDK_INT < 35) return
        val prefs = context.getSharedPreferences("widgets", Context.MODE_PRIVATE)
        if (prefs.getInt("previewsFor", 0) == BuildConfig.VERSION_CODE) return
        val glance = GlanceAppWidgetManager(context)
        all.forEach { runCatching { glance.setWidgetPreviews(it.receiver) } }
        prefs.edit().putInt("previewsFor", BuildConfig.VERSION_CODE).apply()
    }
}

/** "Home screen widgets" card: one tap adds a widget, no digging through the launcher's picker. */
@Composable
fun WidgetGalleryCard(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    BuddyCard(modifier.fillMaxWidth()) {
        Text("Home screen widgets", style = MaterialTheme.typography.titleMedium)
        Text(
            "Tap Add, then confirm. Long-press a widget on your home screen to resize it — they all shrink down to 2×2.",
            style = MaterialTheme.typography.bodySmall, color = BuddyColors.TextTertiary,
        )
        WidgetGallery.all.forEach { w ->
            Row(Modifier.fillMaxWidth().padding(top = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(w.emoji, style = MaterialTheme.typography.titleLarge)
                Column(Modifier.weight(1f).padding(horizontal = 12.dp)) {
                    Text(w.name, style = MaterialTheme.typography.titleSmall)
                    Text(w.what, style = MaterialTheme.typography.labelSmall, color = BuddyColors.TextSecondary)
                }
                FilledTonalButton(onClick = {
                    if (!WidgetGallery.pin(context, w)) {
                        android.widget.Toast.makeText(context, "Your launcher can't add widgets this way — long-press the home screen → Widgets → Buddy", android.widget.Toast.LENGTH_LONG).show()
                    }
                }) { Text("Add") }
            }
        }
    }
}
