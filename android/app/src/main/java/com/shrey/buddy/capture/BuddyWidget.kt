package com.shrey.buddy.capture

import android.content.Context
import android.content.Intent
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.glance.GlanceId
import androidx.glance.GlanceModifier
import androidx.glance.Image
import androidx.glance.ImageProvider
import androidx.glance.action.clickable
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.GlanceAppWidgetReceiver
import androidx.glance.appwidget.action.actionStartActivity
import androidx.glance.appwidget.cornerRadius
import androidx.glance.appwidget.provideContent
import androidx.glance.appwidget.updateAll
import androidx.glance.background
import androidx.glance.layout.Alignment
import androidx.glance.layout.Box
import androidx.glance.layout.Column
import androidx.glance.layout.Row
import androidx.glance.layout.Spacer
import androidx.glance.layout.fillMaxSize
import androidx.glance.layout.fillMaxWidth
import androidx.glance.layout.height
import androidx.glance.layout.padding
import androidx.glance.layout.size
import androidx.glance.layout.width
import androidx.glance.text.FontWeight
import androidx.glance.text.Text
import androidx.glance.text.TextStyle
import androidx.glance.unit.ColorProvider
import com.shrey.buddy.BuddyApp
import com.shrey.buddy.MainActivity
import com.shrey.buddy.R
import java.text.NumberFormat
import java.time.Instant
import java.time.YearMonth
import java.time.ZoneId
import java.util.Locale

/** Home-screen widget: month spend + open tasks, with one-tap "Tell Buddy" and "Ask". */
class BuddyWidget : GlanceAppWidget() {
    override val sizeMode = androidx.glance.appwidget.SizeMode.Exact
    override suspend fun providePreview(context: Context, widgetCategory: Int) = provideGlance(context, PreviewId)
    override suspend fun provideGlance(context: Context, id: GlanceId) {
        val c = (context.applicationContext as BuddyApp).container
        val zone = ZoneId.systemDefault()
        val month = YearMonth.now()
        val excluded = c.db.accountDao().all().filter { !it.includeInTotals }.map { it.id }.toSet()
        val spent = c.db.transactionDao().all()
            .filter { it.direction == "debit" && !it.isTransfer && it.accountId !in excluded && YearMonth.from(Instant.ofEpochMilli(it.occurredAt).atZone(zone)) == month }
            .sumOf { it.amountMinor }
        val openTasks = c.db.taskDao().all().count { it.status != "done" }
        val inr = NumberFormat.getCurrencyInstance(Locale.Builder().setLanguage("en").setRegion("IN").build()).apply { maximumFractionDigits = 0 }

        provideContent {
            val lime = ColorProvider(Color(0xFFC6F432))
            val white = ColorProvider(Color(0xFFF2F3F5))
            val grey = ColorProvider(Color(0xFF9AA0AA))
            Column(
                GlanceModifier.fillMaxSize().background(Color(0xFF17191E)).cornerRadius(24.dp)
                    .clickable(actionStartActivity(Intent(context, MainActivity::class.java))).padding(16.dp),
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Image(ImageProvider(R.drawable.ic_stat_buddy), contentDescription = null, modifier = GlanceModifier.size(18.dp))
                    Spacer(GlanceModifier.width(8.dp))
                    Text("Buddy", style = TextStyle(color = lime, fontWeight = FontWeight.Bold, fontSize = 14.sp))
                }
                Spacer(GlanceModifier.height(8.dp))
                Text(inr.format(spent / 100.0), style = TextStyle(color = white, fontWeight = FontWeight.Bold, fontSize = 24.sp))
                Text("spent this month · $openTasks open tasks", style = TextStyle(color = grey, fontSize = 12.sp))
                Spacer(GlanceModifier.defaultWeight())
                Row(GlanceModifier.fillMaxWidth()) {
                    Box(
                        GlanceModifier.defaultWeight().height(46.dp).background(Color(0xFFC6F432)).cornerRadius(16.dp)
                            .clickable(actionStartActivity(Intent(context, QuickCaptureActivity::class.java))),
                        contentAlignment = Alignment.Center,
                    ) { Text("＋ Tell Buddy", style = TextStyle(color = ColorProvider(Color(0xFF0E0F12)), fontWeight = FontWeight.Bold)) }
                    Spacer(GlanceModifier.width(8.dp))
                    Box(
                        GlanceModifier.defaultWeight().height(46.dp).background(Color(0xFF272B32)).cornerRadius(16.dp)
                            .clickable(actionStartActivity(Intent(context, MainActivity::class.java).putExtra(MainActivity.EXTRA_OPEN, "chat"))),
                        contentAlignment = Alignment.Center,
                    ) { Text("✦ Ask", style = TextStyle(color = white, fontWeight = FontWeight.Bold)) }
                }
            }
        }
    }

    companion object {
        suspend fun refresh(context: Context) = runCatching { BuddyWidget().updateAll(context) }
    }
}

class BuddyWidgetReceiver : GlanceAppWidgetReceiver() {
    override val glanceAppWidget = BuddyWidget()
}
