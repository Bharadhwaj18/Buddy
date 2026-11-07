package com.shrey.buddy.ui.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.shrey.buddy.ui.AppViewModel
import com.shrey.buddy.ui.theme.BuddyColors
import kotlinx.coroutines.delay

/** "Deleted … · Undo" bar for the last undoable change; goes away by itself after 5 s. */
@Composable
fun UndoBar(vm: AppViewModel, modifier: Modifier = Modifier) {
    val undoable by vm.undoable.collectAsStateWithLifecycle()
    LaunchedEffect(undoable) {
        if (undoable != null) {
            delay(5_000)
            vm.clearUndo()
        }
    }
    AnimatedVisibility(
        visible = undoable != null,
        enter = slideInVertically { it } + fadeIn(),
        exit = slideOutVertically { it } + fadeOut(),
        modifier = modifier.padding(16.dp),
    ) {
        // Keep the last message while the bar slides away.
        var message by remember { mutableStateOf("") }
        undoable?.let { message = it.message }
        Surface(shape = RoundedCornerShape(16.dp), color = BuddyColors.SurfaceHighest, shadowElevation = 6.dp) {
            Row(Modifier.fillMaxWidth().padding(start = 16.dp, end = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Rounded.CheckCircle, null, tint = BuddyColors.Lime, modifier = Modifier.size(20.dp))
                Text(
                    message,
                    style = MaterialTheme.typography.bodyMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f).padding(horizontal = 10.dp),
                )
                TextButton(onClick = { vm.undo() }) { Text("Undo", color = BuddyColors.Lime) }
            }
        }
    }
}
