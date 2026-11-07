package com.shrey.buddy.ui.components

import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.SystemUpdate
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.shrey.buddy.BuildConfig
import com.shrey.buddy.ui.AppViewModel
import com.shrey.buddy.ui.theme.BuddyColors
import com.shrey.buddy.update.UpdateState

/**
 * Update status + actions. [compact] = banner for Today (only shown when there's something to do);
 * otherwise a full card for settings with a manual "Check" button.
 */
@Composable
fun UpdateCard(vm: AppViewModel, compact: Boolean, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val state by vm.updateState.collectAsStateWithLifecycle()
    val actionable = state is UpdateState.Available || state is UpdateState.Downloading || state is UpdateState.ReadyToInstall
    if (compact && !actionable) return

    BuddyCard(modifier.fillMaxWidth(), color = if (actionable) BuddyColors.LimeContainer else BuddyColors.Surface) {
        Row {
            IconBadge(Icons.Rounded.SystemUpdate, BuddyColors.Lime, 36.dp)
        }
        when (val s = state) {
            is UpdateState.Available -> {
                Text("Buddy ${s.release.versionName} is available", style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(top = 10.dp))
                if (s.release.notes.isNotBlank()) Text(s.release.notes, style = MaterialTheme.typography.bodySmall, color = BuddyColors.TextSecondary)
                Button(
                    onClick = { vm.downloadUpdate(s.release) },
                    modifier = Modifier.padding(top = 10.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = BuddyColors.Lime, contentColor = BuddyColors.OnLime),
                ) { Text("Download") }
            }
            is UpdateState.Downloading -> {
                Text("Downloading ${s.release.versionName}…", style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(top = 10.dp))
                LinearProgressIndicator(
                    progress = { s.progress },
                    color = BuddyColors.Lime,
                    trackColor = BuddyColors.SurfaceHigh,
                    modifier = Modifier.fillMaxWidth().padding(top = 10.dp),
                )
            }
            is UpdateState.ReadyToInstall -> {
                Text("Ready to install ${s.release.versionName}", style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(top = 10.dp))
                if (!vm.updater.canInstall()) {
                    Text(
                        "First, allow Buddy to install updates (one-time).",
                        style = MaterialTheme.typography.bodySmall,
                        color = BuddyColors.TextSecondary,
                    )
                }
                Button(
                    onClick = {
                        if (vm.updater.canInstall()) context.startActivity(vm.updater.installIntent(s.file))
                        else context.startActivity(vm.updater.installPermissionIntent())
                    },
                    modifier = Modifier.padding(top = 10.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = BuddyColors.Lime, contentColor = BuddyColors.OnLime),
                ) { Text(if (vm.updater.canInstall()) "Install" else "Allow installs") }
            }
            else -> {
                Text("App updates", style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(top = 10.dp))
                Text(
                    when (s) {
                        UpdateState.Checking -> "Checking…"
                        UpdateState.UpToDate -> "You're on the latest version (${BuildConfig.VERSION_NAME})."
                        is UpdateState.Failed -> s.message
                        else -> "Version ${BuildConfig.VERSION_NAME}"
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = if (s is UpdateState.Failed) BuddyColors.Red else BuddyColors.TextSecondary,
                )
                TextButton(onClick = { vm.checkForUpdate() }) { Text("Check for updates", color = BuddyColors.Lime) }
            }
        }
    }
}
