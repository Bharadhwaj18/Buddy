package com.shrey.buddy.capture

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.provider.Telephony
import android.util.Log
import com.shrey.buddy.BuddyApp
import com.shrey.buddy.data.capture.IngestResult
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/** Live bank-SMS capture. Runs even when Buddy isn't open (but not after a force-stop). */
class SmsReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Telephony.Sms.Intents.SMS_RECEIVED_ACTION) return
        val parts = Telephony.Sms.Intents.getMessagesFromIntent(intent) ?: return
        // Long SMS arrive as several parts from the same sender.
        val bySender = parts.groupBy { it.originatingAddress.orEmpty() }
            .mapValues { (_, msgs) -> msgs.joinToString("") { it.messageBody.orEmpty() } }

        val app = context.applicationContext as BuddyApp
        val pending = goAsync()
        scope.launch {
            try {
                bySender.forEach { (sender, body) ->
                    val result = app.container.captures.ingestSms(sender, body, System.currentTimeMillis(), via = "live")
                    if (result is IngestResult.Stored) {
                        CaptureNotifier.notify(context, result.parse, result.transactionId)
                        runCatching { app.container.planning.checkBudgets() }
                    }
                }
            } catch (e: Exception) {
                Log.e("BuddySms", "capture failed", e)
            } finally {
                pending.finish()
            }
        }
    }

    private companion object {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    }
}
