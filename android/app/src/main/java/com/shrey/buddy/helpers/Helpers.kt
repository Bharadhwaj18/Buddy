package com.shrey.buddy.helpers

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.provider.Telephony
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.shrey.buddy.AppContainer
import com.shrey.buddy.MainActivity
import com.shrey.buddy.R
import com.shrey.buddy.data.items.Iou
import com.shrey.buddy.data.items.Item
import com.shrey.buddy.data.items.Kinds
import com.shrey.buddy.data.items.MoneyBackLink
import com.shrey.buddy.data.items.Parcel
import com.shrey.buddy.data.items.ItemStore
import com.shrey.buddy.data.items.Recurring
import com.shrey.buddy.finance.BillRadar
import com.shrey.buddy.finance.Splits
import com.shrey.buddy.ingest.sms.OtpSms
import com.shrey.buddy.ingest.sms.ParcelSms
import com.shrey.buddy.ingest.sms.ParcelStatus
import com.shrey.buddy.ingest.sms.ParcelUpdate
import com.shrey.buddy.reminders.LaptopFirst
import com.shrey.buddy.ui.inr
import io.github.jan.supabase.postgrest.from
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import java.util.concurrent.TimeUnit

private fun prefs(context: Context) = context.getSharedPreferences("helpers", Context.MODE_PRIVATE)

/** Small shared notifier: one channel for these helpers. */
internal object HelperNotes {
    const val CHANNEL = "helpers"

    fun notify(context: Context, id: Int, title: String, text: String, open: String, big: Boolean = false) {
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) return
        context.getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel(CHANNEL, "Bills, splits & parcels", NotificationManager.IMPORTANCE_DEFAULT),
        )
        val intent = PendingIntent.getActivity(
            context, id, Intent(context, MainActivity::class.java).putExtra(MainActivity.EXTRA_OPEN, open).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val b = NotificationCompat.Builder(context, CHANNEL).setSmallIcon(R.drawable.ic_stat_buddy)
            .setContentTitle(title).setContentText(text).setContentIntent(intent).setAutoCancel(true)
        if (big) b.setStyle(NotificationCompat.BigTextStyle().bigText(text))
        NotificationManagerCompat.from(context).notify(id, b.build())
    }
}

// ---------------------------------------------------------------- OTP → laptop

/**
 * Sends one-time codes to the laptop while it's on (table `otp_relay`, kept for minutes only: the server deletes
 * old rows on every insert, and the laptop deletes each one after showing it). Off → nothing leaves the phone.
 */
object OtpRelay {
    private const val TAG = "BuddyOtp"
    fun enabled(context: Context) = prefs(context).getBoolean("otp_to_laptop", true)
    fun setEnabled(context: Context, on: Boolean) = prefs(context).edit().putBoolean("otp_to_laptop", on).apply()

    suspend fun onSms(c: AppContainer, sender: String, body: String): Boolean {
        if (!enabled(c.context)) return false
        val otp = OtpSms.find(sender, body) ?: return false
        return withTimeoutOrNull(8_000) {
            runCatching {
                if (!LaptopFirst.laptopSeenRecently(c.context, System.currentTimeMillis())) return@runCatching false
                c.supabase.from("otp_relay").insert(buildJsonObject { put("code", otp.code); put("source", otp.source) })
                true
            }.onFailure { Log.w(TAG, "relay failed: ${it.javaClass.simpleName}") }.getOrDefault(false)
        } ?: false
    }
}

// ---------------------------------------------------------------- Parcels

object ParcelTracker {
    private val ser = Parcel.serializer()

    suspend fun onSms(c: AppContainer, sender: String, body: String, at: Long, notify: Boolean = true): Boolean {
        val day = Instant.ofEpochMilli(at).atZone(ZoneId.systemDefault()).toLocalDate()
        val u = ParcelSms.parse(sender, body, day) ?: return false
        val all = c.items.all(Kinds.PARCEL, ser)
        val existing = match(all, u, at)
        val prev = existing?.data
        val status = when {
            prev == null -> u.status
            u.status == ParcelStatus.CANCELLED -> u.status
            // Never go backwards (an "order confirmed" SMS arriving after "shipped").
            ParcelStatus.valueOf(prev.status).ordinal > u.status.ordinal -> ParcelStatus.valueOf(prev.status)
            else -> u.status
        }
        val p = Parcel(
            merchant = prev?.merchant?.takeIf { isShop(it) } ?: u.merchant,
            title = u.title ?: prev?.title,
            orderId = prev?.orderId ?: u.orderId,
            status = status.name,
            eta = (u.eta ?: prev?.eta?.let(LocalDate::parse))?.toString(),
            deliveryCode = u.deliveryCode ?: prev?.deliveryCode?.takeIf { status != ParcelStatus.DELIVERED },
            firstSeen = prev?.firstSeen ?: at,
            updatedAt = at,
            hidden = prev?.hidden == true && status == ParcelStatus.DELIVERED,
        )
        if (p == prev) return true
        c.items.put(Kinds.PARCEL, p, ser, id = existing?.id, occurredOn = day)
        if (notify && status == ParcelStatus.OUT_FOR_DELIVERY && prev?.status != ParcelStatus.OUT_FOR_DELIVERY.name) {
            HelperNotes.notify(
                c.context, 0x5A00 + (p.orderId ?: p.merchant).hashCode() % 200, "📦 ${p.merchant} arriving today",
                listOfNotNull(p.title, p.deliveryCode?.let { "Delivery code $it" }).joinToString(" · ").ifBlank { "Out for delivery" },
                open = "parcels",
            )
        }
        return true
    }

    private val COURIERS = setOf("Delhivery", "Blue Dart", "Ecom Express", "Xpressbees", "Shadowfax", "DTDC", "India Post", "Ekart", "Smartr")
    private fun isShop(m: String) = m !in COURIERS

    /** Same order id; else the latest open parcel from the same shop (or any open one, for a courier) seen in the last 2 weeks. */
    private fun match(all: List<Item<Parcel>>, u: ParcelUpdate, at: Long): Item<Parcel>? {
        u.orderId?.let { id -> all.firstOrNull { it.data.orderId == id }?.let { return it } }
        val open = all.filter {
            it.data.status !in setOf(ParcelStatus.DELIVERED.name, ParcelStatus.CANCELLED.name) &&
                at - it.data.updatedAt < TimeUnit.DAYS.toMillis(14) && (u.orderId == null || it.data.orderId == null)
        }
        val sameShop = open.filter { it.data.merchant == u.merchant }
        val pool = sameShop.ifEmpty { if (u.merchant in COURIERS && open.size == 1) open else emptyList() }
        val titled = u.title?.let { t -> pool.filter { it.data.title == null || it.data.title == t } } ?: pool
        return titled.maxByOrNull { it.data.updatedAt }
    }

    /** Active = not hidden, and not delivered / cancelled more than a day ago. */
    fun active(list: List<Item<Parcel>>, now: Long = System.currentTimeMillis()) = list.filter {
        !it.data.hidden && (it.data.status !in setOf(ParcelStatus.DELIVERED.name, ParcelStatus.CANCELLED.name) ||
            now - it.data.updatedAt < TimeUnit.HOURS.toMillis(20))
    }.sortedWith(compareBy({ it.data.status == ParcelStatus.DELIVERED.name }, { it.data.eta ?: "9999" }))

    /** Hides parcels that went quiet for 3 weeks (an SMS for "delivered" never came). */
    suspend fun tidy(c: AppContainer) {
        val now = System.currentTimeMillis()
        for (p in c.items.all(Kinds.PARCEL, ser)) {
            if (!p.data.hidden && now - p.data.updatedAt > TimeUnit.DAYS.toMillis(21)) c.items.put(Kinds.PARCEL, p.data.copy(hidden = true), ser, id = p.id)
        }
    }

    /** First run: reads the last 10 days of SMS so orders already on the way show up. */
    suspend fun backfillOnce(c: AppContainer) = withContext(Dispatchers.IO) {
        val p = prefs(c.context)
        if (p.getBoolean("parcels_backfilled", false)) return@withContext
        if (ContextCompat.checkSelfPermission(c.context, Manifest.permission.READ_SMS) != PackageManager.PERMISSION_GRANTED) return@withContext
        val since = System.currentTimeMillis() - TimeUnit.DAYS.toMillis(10)
        runCatching {
            c.context.contentResolver.query(
                Telephony.Sms.Inbox.CONTENT_URI, arrayOf(Telephony.Sms.ADDRESS, Telephony.Sms.BODY, Telephony.Sms.DATE),
                "${Telephony.Sms.DATE} >= ?", arrayOf(since.toString()), "${Telephony.Sms.DATE} ASC",
            )?.use { cur ->
                while (cur.moveToNext()) {
                    onSms(c, cur.getString(0) ?: continue, cur.getString(1).orEmpty(), cur.getLong(2), notify = false)
                }
            }
            p.edit().putBoolean("parcels_backfilled", true).apply()
        }.onFailure { Log.w("BuddyParcels", "backfill failed", it) }
    }
}

// ---------------------------------------------------------------- Splits

object SplitKeeper {
    private val ser = Iou.serializer()

    suspend fun add(c: AppContainer, d: Splits.Draft, txnId: String? = null): Int {
        val list = Splits.ious(d, System.currentTimeMillis(), txnId)
        list.forEach { c.items.put(Kinds.IOU, it, ser, occurredOn = LocalDate.now()) }
        return list.size
    }

    /** Money came in: does it pay back a split? Marks those IOUs paid and files the money as money back. */
    suspend fun onCredit(c: AppContainer, txnId: String): String? {
        val t = c.db.transactionDao().get(txnId) ?: return null
        val open = c.items.all(Kinds.IOU, ser).filter { it.data.settledAt == null }.map { it.id to it.data }
        val ids = Splits.settles(t, open)
        if (ids.isEmpty()) return null
        val byId = open.toMap()
        for (id in ids) settle(c, id, byId.getValue(id), t.id)
        val first = byId.getValue(ids.first())
        // Money back: file it under what was paid for, so spending shows your share only.
        val paidFor = first.txnId?.let { c.db.transactionDao().get(it) }
        if (paidFor != null && t.categorySource != "user") {
            c.finance.updateTransaction(t.id, paidFor.category, false, t.note)
            c.items.put(Kinds.MONEY_BACK, MoneyBackLink(t.id, paidFor.id), MoneyBackLink.serializer(), id = ItemStore.stableId("moneyback:${t.id}"))
        }
        val what = ids.mapNotNull { byId[it]?.note?.ifBlank { null } }.distinct().joinToString(", ")
        val text = "${first.person} paid you back ${inr(t.amountMinor)}" + (if (what.isNotBlank()) " for $what" else "")
        HelperNotes.notify(c.context, 0x5B00 + txnId.hashCode() % 200, "✓ Split settled", text, open = "splits")
        return text
    }

    suspend fun settle(c: AppContainer, id: String, iou: Iou, byTxn: String? = null) {
        c.items.put(Kinds.IOU, iou.copy(settledAt = System.currentTimeMillis(), settledTxnId = byTxn), ser, id = id)
    }

    /** Morning nudge: people who've owed you for 3+ days, at most every 3 days each. */
    suspend fun nudge(c: AppContainer) {
        val now = System.currentTimeMillis()
        val due = c.items.all(Kinds.IOU, ser).filter {
            val i = it.data
            i.theyOwe && i.settledAt == null && now - i.createdAt > TimeUnit.DAYS.toMillis(3) &&
                (i.lastNudgeAt == null || now - i.lastNudgeAt > TimeUnit.DAYS.toMillis(3))
        }
        if (due.isEmpty()) return
        val lines = due.groupBy { it.data.person }.map { (who, list) ->
            val days = TimeUnit.MILLISECONDS.toDays(now - list.minOf { it.data.createdAt })
            "$who owes you ${inr(list.sumOf { it.data.amountMinor })} ($days days)"
        }
        HelperNotes.notify(c.context, 0x5B01, "Money owed to you", lines.joinToString("\n"), open = "splits", big = true)
        due.forEach { c.items.put(Kinds.IOU, it.data.copy(lastNudgeAt = now), ser, id = it.id) }
    }
}

// ---------------------------------------------------------------- Bill radar

object BillWatch {
    private val dayFmt = DateTimeFormatter.ofPattern("EEE d MMM", Locale.ENGLISH)

    fun dismissed(context: Context): Set<String> = prefs(context).getStringSet("radar_dismissed", emptySet()) ?: emptySet()
    fun dismiss(context: Context, key: String) {
        prefs(context).edit().putStringSet("radar_dismissed", dismissed(context) + key).apply()
    }

    private suspend fun tracked(c: AppContainer) = c.items.all(Kinds.RECURRING, Recurring.serializer()).map { it.id to it.data }

    suspend fun findings(c: AppContainer): List<BillRadar.Found> {
        val gone = dismissed(c.context)
        return BillRadar.detect(c.db.transactionDao().all(), tracked(c)).filter { it.key !in gone }
    }

    /** Morning: "Netflix ₹649 on Fri" two days ahead; new bills found or a price rise, once each. */
    suspend fun morning(c: AppContainer) {
        val txns = c.db.transactionDao().all()
        val tracked = tracked(c)
        val soon = BillRadar.dueIn(tracked, txns, 2)
        if (soon.isNotEmpty()) {
            val text = soon.joinToString("\n") { (_, r, d) -> "${r.name} ${inr(r.amountMinor)} on ${dayFmt.format(d)}" }
            HelperNotes.notify(c.context, 0x5C00, if (soon.size == 1) "Bill in 2 days" else "${soon.size} bills in 2 days", text, open = "recurring", big = true)
        }
        val p = prefs(c.context)
        val told = p.getStringSet("radar_told", emptySet()) ?: emptySet()
        val fresh = findings(c).filter { it.key !in told }
        if (fresh.isNotEmpty()) {
            val price = fresh.filter { it.kind == BillRadar.Kind.PRICE_CHANGE }
            val text = (price.map { "${it.name} went up: ${inr(it.oldAmountMinor ?: 0)} → ${inr(it.amountMinor)}" } +
                fresh.filter { it.kind == BillRadar.Kind.NEW }.map { "${it.name} ${inr(it.amountMinor)} ${it.frequency}" }).joinToString("\n")
            HelperNotes.notify(c.context, 0x5C01, "Bill radar found ${fresh.size}", text, open = "recurring", big = true)
            p.edit().putStringSet("radar_told", told + fresh.map { it.key }).apply()
        }
    }
}
