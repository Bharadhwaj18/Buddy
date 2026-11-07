package com.shrey.buddy.ui.life

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Snackbar
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.shrey.buddy.ui.AppViewModel
import com.shrey.buddy.ui.components.IconBadge
import com.shrey.buddy.ui.theme.BuddyColors

@Composable
fun LifeModuleScreen(module: LifeModule, lvm: LifeViewModel, vm: AppViewModel, onBack: () -> Unit) {
    when (module) {
        LifeModule.JOURNAL -> JournalScreen(lvm, onBack)
        LifeModule.NOTES -> NotesScreen(lvm, onBack)
        LifeModule.GOALS -> GoalsScreen(lvm, onBack)
        LifeModule.PEOPLE -> PeopleScreen(lvm, onBack)
        LifeModule.READING -> ReadingScreen(lvm, onBack)
        LifeModule.VEHICLES -> VehiclesScreen(lvm, onBack)
        LifeModule.WISHLIST -> WishlistScreen(lvm, onBack)
        LifeModule.VAULT -> VaultScreen(lvm, onBack)
        LifeModule.NET_WORTH -> NetWorthScreen(lvm, vm, onBack)
        LifeModule.INSIGHTS -> InsightsScreen(lvm, onBack)
        LifeModule.RECURRING -> RecurringScreen(lvm, onBack)
        LifeModule.BUDGETS -> BudgetsScreen(lvm, onBack)
    }
}

/** Common frame: back + title header, scrolling content, optional ＋ button, busy/error strip. */
@Composable
fun ModuleScaffold(
    module: LifeModule,
    lvm: LifeViewModel,
    onBack: () -> Unit,
    onAdd: (() -> Unit)? = null,
    content: LazyListScope.() -> Unit,
) {
    val busy by lvm.busy.collectAsStateWithLifecycle()
    val message by lvm.message.collectAsStateWithLifecycle()
    Box(Modifier.fillMaxSize()) {
        LazyColumn(
            contentPadding = PaddingValues(start = 20.dp, end = 20.dp, top = 8.dp, bottom = 96.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            item {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = "Back") }
                    IconBadge(module.icon, module.color, 36.dp)
                    Column(Modifier.padding(start = 12.dp)) {
                        Text(module.label, style = MaterialTheme.typography.headlineSmall)
                        Text(module.blurb, style = MaterialTheme.typography.bodySmall, color = BuddyColors.TextTertiary)
                    }
                }
            }
            content()
        }
        if (onAdd != null) {
            FloatingActionButton(
                onClick = onAdd,
                containerColor = module.color,
                contentColor = BuddyColors.Background,
                modifier = Modifier.align(Alignment.BottomEnd).padding(20.dp),
            ) { Icon(Icons.Rounded.Add, contentDescription = "Add") }
        }
        busy?.let {
            Row(
                Modifier.align(Alignment.BottomStart).padding(20.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp, color = module.color)
                Text(it, style = MaterialTheme.typography.labelLarge, modifier = Modifier.padding(start = 10.dp))
            }
        }
        message?.let {
            Snackbar(
                modifier = Modifier.align(Alignment.BottomCenter).padding(16.dp),
                action = { TextButton(onClick = { lvm.clearMessage() }) { Text("OK") } },
                containerColor = BuddyColors.SurfaceHighest,
                contentColor = BuddyColors.TextPrimary,
            ) { Text(it) }
        }
    }
}
