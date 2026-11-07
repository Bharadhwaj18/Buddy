package com.shrey.buddy.finance

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.core.content.edit
import com.shrey.buddy.AppContainer
import com.shrey.buddy.MainActivity
import com.shrey.buddy.R
import com.shrey.buddy.data.items.Budget
import com.shrey.buddy.data.items.Holding
import com.shrey.buddy.data.items.Kinds
import com.shrey.buddy.data.items.Recurring
import java.time.LocalDate
import java.time.YearMonth
import java.time.ZoneId
import kotlin.math.roundToLong

/** Live investment prices, recurring auto-logging and budget alerts. */
class PlanningService(private val context: Context, private val c: AppContainer) {
    private val prefs = context.getSharedPreferences("planning", Context.MODE_PRIVATE)

    /** Re-prices every live holding. Skipped if done in the last 4 hours unless [force]. Returns an error, if any. */
    suspend fun refreshHoldings(force: Boolean = false): String? {
        val last = prefs.getLong("prices_at", 0)
        if (!force && System.currentTimeMillis() - last < 4 * 60 * 60 * 1000L) return null
        var error: String? = null
        for (h in c.items.all(Kinds.HOLDING, Holding.serializer())) {
            if (h.data.source == "manual" || h.data.symbol.isBlank()) continue
            try {
                val q = MarketData.quote(h.data.source, h.data.symbol)
                c.items.put(Kinds.HOLDING, priced(h.data, q.price, q.previous, q.date), Holding.serializer(), id = h.id, notify = false)
            } catch (e: Exception) {
                error = "Couldn't update ${h.data.name}: ${e.message}"
            }
        }
        prefs.edit { putLong("prices_at", System.currentTimeMillis()) }
        return error
    }

    /** Applies a fresh price to a live holding (units × price). */
    fun priced(h: Holding, price: Double, previous: Double?, date: LocalDate) = h.copy(
        price = price,
        previousPrice = previous,
        priceDate = date.toString(),
        currentMinor = (h.units * price * 100).roundToLong(),
        updatedOn = LocalDate.now().toString(),
    )

    /** For recurring items set to auto-log: create the transaction on its due date if no bank SMS matched. */
    suspend fun runAutoLog() {
        val today = LocalDate.now()
        val txns = c.db.transactionDao().all()
        for (item in c.items.all(Kinds.RECURRING, Recurring.serializer())) {
            val r = item.data
            if (!r.active || !r.autoLog) continue
            val due = RecurringLogic.occurrences(r, txns, today, back = 10, ahead = 0)
                .filter { it.status == OccurrenceStatus.OVERDUE || it.status == OccurrenceStatus.DUE }
            if (due.isEmpty()) continue
            for (o in due) {
                c.finance.addManualTransaction(
                    direction = r.direction, amountMinor = r.amountMinor, payee = r.name, category = Category.of(r.category),
                    accountHint = null, note = "Recurring", source = "manual", accountId = r.accountId,
                    occurredAt = o.date.atTime(9, 0).atZone(ZoneId.systemDefault()).toInstant().toEpochMilli(),
                )
            }
            c.items.put(Kinds.RECURRING, r.copy(settled = (r.settled + due.map { it.date.toString() }).takeLast(60)), Recurring.serializer(), id = item.id)
        }
    }

    /** Notifies once per month per budget when spending crosses 80% and 100%. */
    suspend fun checkBudgets() {
        val budgets = c.items.all(Kinds.BUDGET, Budget.serializer()).map { it.data }
        if (budgets.isEmpty()) return
        val excluded = c.db.accountDao().all().filter { !it.includeInTotals }.map { it.id }.toSet()
        val month = YearMonth.now()
        for (s in BudgetLogic.status(budgets, c.db.transactionDao().all(), excluded, month)) {
            val threshold = when {
                s.fraction >= 1f -> 100
                s.fraction >= 0.8f -> 80
                else -> continue
            }
            val key = "budget:$month:${s.budget.category}:${s.budget.name}:$threshold"
            if (prefs.getBoolean(key, false)) continue
            prefs.edit { putBoolean(key, true) }
            val label = s.budget.name.ifBlank { if (s.budget.category == "total") "Monthly budget" else "${Category.subOf(s.budget.category) ?: Category.of(s.budget.category).label} budget" }
            notify(
                if (threshold == 100) "$label exceeded" else "$label at ${(s.fraction * 100).toInt()}%",
                "Spent ${com.shrey.buddy.ui.inr(s.spentMinor)} of ${com.shrey.buddy.ui.inr(s.budget.limitMinor)} this month.",
            )
        }
    }

    private fun notify(title: String, text: String) {
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) return
        context.getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel("budgets", "Budget alerts", NotificationManager.IMPORTANCE_DEFAULT),
        )
        val open = PendingIntent.getActivity(context, 6, Intent(context, MainActivity::class.java).putExtra(MainActivity.EXTRA_OPEN, "money"), PendingIntent.FLAG_IMMUTABLE)
        NotificationManagerCompat.from(context).notify(
            title.hashCode(),
            NotificationCompat.Builder(context, "budgets").setSmallIcon(R.drawable.ic_stat_buddy)
                .setContentTitle(title).setContentText(text).setContentIntent(open).setAutoCancel(true).build(),
        )
    }
}
