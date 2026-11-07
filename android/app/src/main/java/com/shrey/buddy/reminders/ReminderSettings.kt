package com.shrey.buddy.reminders

import android.content.Context
import android.media.RingtoneManager
import android.net.Uri
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** How reminder calls ring. Device-local (it's about this phone's sounds), not synced. */
data class RingSettings(
    /** null = the phone's default ringtone. */
    val ringtone: Uri? = null,
    /** Start quiet and get louder over ~30 s. */
    val gradual: Boolean = true,
    val vibrate: Boolean = true,
    /** How long it rings before it counts as missed. */
    val ringSeconds: Int = 60,
    /** Buddy reads the reminder out loud over the ringtone. */
    val speak: Boolean = true,
    /** 8:00 am "here's your day" notification. */
    val morningPlan: Boolean = true,
    /** 9:00 pm "what's left" notification with "Move all to tomorrow". */
    val eveningWrap: Boolean = true,
)

object ReminderSettings {
    private const val PREFS = "reminder_settings"
    private val _state = MutableStateFlow<RingSettings?>(null)

    /** Bumped whenever a snooze changes, so lists re-read "rings at" times. */
    private val _snoozeVersion = MutableStateFlow(0)
    val snoozeVersion: StateFlow<Int> = _snoozeVersion.asStateFlow()
    internal fun snoozesChanged() { _snoozeVersion.value++ }

    fun observe(context: Context): StateFlow<RingSettings?> {
        if (_state.value == null) _state.value = load(context)
        return _state.asStateFlow()
    }

    fun get(context: Context): RingSettings = _state.value ?: load(context).also { _state.value = it }

    fun update(context: Context, change: (RingSettings) -> RingSettings) {
        val next = change(get(context))
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putString("ringtone", next.ringtone?.toString())
            .putBoolean("gradual", next.gradual)
            .putBoolean("vibrate", next.vibrate)
            .putInt("ringSeconds", next.ringSeconds)
            .putBoolean("speak", next.speak)
            .putBoolean("morningPlan", next.morningPlan)
            .putBoolean("eveningWrap", next.eveningWrap)
            .apply()
        _state.value = next
    }

    // ---- "Keep calling until I answer", per task (device-local, like snoozes) ----

    private fun nagPrefs(context: Context) = context.getSharedPreferences("reminder_nag", Context.MODE_PRIVATE)
    fun isNag(context: Context, taskId: String) = nagPrefs(context).getBoolean(taskId, false)
    fun setNag(context: Context, taskId: String, on: Boolean) {
        nagPrefs(context).edit().apply { if (on) putBoolean(taskId, true) else remove(taskId) }.apply()
    }

    /** Counts unanswered re-calls for one reminder time, so a forgotten phone isn't called forever. */
    fun nextNagCount(context: Context, taskId: String, remindAt: Long): Int {
        val key = "count:$taskId:$remindAt"
        val n = nagPrefs(context).getInt(key, 0) + 1
        nagPrefs(context).edit().putInt(key, n).apply()
        return n
    }

    private fun load(context: Context): RingSettings {
        val p = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        return RingSettings(
            ringtone = p.getString("ringtone", null)?.let(Uri::parse),
            gradual = p.getBoolean("gradual", true),
            vibrate = p.getBoolean("vibrate", true),
            ringSeconds = p.getInt("ringSeconds", 60),
            speak = p.getBoolean("speak", true),
            morningPlan = p.getBoolean("morningPlan", true),
            eveningWrap = p.getBoolean("eveningWrap", true),
        )
    }

    /** The sound that will actually play. */
    fun soundUri(context: Context): Uri =
        get(context).ringtone
            ?: RingtoneManager.getActualDefaultRingtoneUri(context, RingtoneManager.TYPE_RINGTONE)
            ?: RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM)

    fun ringtoneName(context: Context): String {
        val custom = get(context).ringtone ?: return "Phone ringtone"
        return runCatching { RingtoneManager.getRingtone(context, custom)?.getTitle(context) }.getOrNull() ?: "Custom"
    }
}
