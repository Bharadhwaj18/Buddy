package com.shrey.buddy.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.shrey.buddy.data.db.BucketEntity
import com.shrey.buddy.data.db.TaskEntity
import com.shrey.buddy.data.tasks.isDone
import com.shrey.buddy.ui.AppViewModel
import com.shrey.buddy.ui.tasks.TaskCard
import com.shrey.buddy.ui.tasks.TaskDetailSheet
import com.shrey.buddy.ui.tasks.planColor
import com.shrey.buddy.ui.theme.BuddyColors

/** Kanban board for one plan: a horizontally scrolling row of bucket columns. */
@Composable
fun PlanBoardScreen(vm: AppViewModel, planId: String, onBack: () -> Unit) {
    val plans by vm.plans.collectAsStateWithLifecycle()
    val allBuckets by vm.buckets.collectAsStateWithLifecycle()
    val tasks by vm.tasks.collectAsStateWithLifecycle()
    val plan = plans.firstOrNull { it.id == planId }
    val buckets = allBuckets.filter { it.planId == planId }
    var editing by remember { mutableStateOf<TaskEntity?>(null) }
    var menu by remember { mutableStateOf(false) }
    var editPlan by remember { mutableStateOf(false) }
    var confirmDelete by remember { mutableStateOf(false) }
    var renaming by remember { mutableStateOf<BucketEntity?>(null) }
    var addingBucket by remember { mutableStateOf(false) }

    if (plan == null) {
        // Deleted (or not synced yet).
        Column(Modifier.fillMaxSize().padding(20.dp)) {
            IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = "Back") }
            Text("This plan no longer exists.", color = BuddyColors.TextSecondary)
        }
        return
    }

    Column(Modifier.fillMaxSize()) {
        Row(Modifier.padding(horizontal = 8.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = "Back") }
            Box(Modifier.size(12.dp).clip(CircleShape).background(planColor(plan.color)))
            Text(plan.name, style = MaterialTheme.typography.headlineSmall, modifier = Modifier.weight(1f).padding(start = 10.dp))
            Box {
                IconButton(onClick = { menu = true }) { Icon(Icons.Rounded.MoreVert, contentDescription = "Plan options") }
                DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                    DropdownMenuItem(text = { Text("Rename / colour") }, onClick = { menu = false; editPlan = true })
                    DropdownMenuItem(text = { Text("Add bucket") }, onClick = { menu = false; addingBucket = true })
                    DropdownMenuItem(text = { Text("Delete plan", color = BuddyColors.Red) }, onClick = { menu = false; confirmDelete = true })
                }
            }
        }

        LazyRow(
            modifier = Modifier.weight(1f),
            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            items(buckets, key = { it.id }) { bucket ->
                BucketColumn(
                    bucket = bucket,
                    tasks = tasks.filter { it.bucketId == bucket.id }
                        .sortedWith(compareBy<TaskEntity> { it.isDone }.thenBy { it.position }),
                    onAdd = { title -> vm.addTask(title, planId, bucket.id) },
                    onOpen = { editing = it },
                    onToggle = { vm.toggle(it) },
                    onRename = { renaming = bucket },
                    onDelete = { vm.deleteBucket(bucket) },
                    canDelete = buckets.size > 1,
                )
            }
            item {
                Surface(
                    onClick = { addingBucket = true },
                    shape = RoundedCornerShape(20.dp),
                    color = BuddyColors.Surface.copy(alpha = 0.5f),
                    modifier = Modifier.width(200.dp),
                ) {
                    Row(Modifier.padding(18.dp), verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Rounded.Add, null, tint = BuddyColors.Tasks)
                        Text("Add bucket", style = MaterialTheme.typography.titleSmall, color = BuddyColors.Tasks, modifier = Modifier.padding(start = 8.dp))
                    }
                }
            }
        }
    }

    editing?.let { t ->
        TaskDetailSheet(
            task = t,
            plans = plans,
            buckets = allBuckets,
            onDismiss = { editing = null },
            onSave = { vm.saveTask(it); editing = null },
            onDelete = { vm.delete(it); editing = null },
        )
    }
    if (editPlan) {
        PlanSheet("Edit plan", plan.name, plan.color ?: "sky", onDismiss = { editPlan = false }) { name, color ->
            vm.savePlan(plan.copy(name = name.trim(), color = color))
            editPlan = false
        }
    }
    if (confirmDelete) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text("Delete “${plan.name}”?") },
            text = { Text("Its buckets and ${tasks.count { it.planId == planId }} tasks will be deleted.") },
            confirmButton = {
                TextButton(onClick = { confirmDelete = false; vm.deletePlan(planId); onBack() }) { Text("Delete", color = BuddyColors.Red) }
            },
            dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text("Cancel") } },
        )
    }
    renaming?.let { b ->
        NameDialog("Rename bucket", b.name, onDismiss = { renaming = null }) { vm.renameBucket(b, it); renaming = null }
    }
    if (addingBucket) {
        NameDialog("New bucket", "", onDismiss = { addingBucket = false }) { vm.addBucket(planId, it); addingBucket = false }
    }
}

@Composable
private fun BucketColumn(
    bucket: BucketEntity,
    tasks: List<TaskEntity>,
    onAdd: (String) -> Unit,
    onOpen: (TaskEntity) -> Unit,
    onToggle: (TaskEntity) -> Unit,
    onRename: () -> Unit,
    onDelete: () -> Unit,
    canDelete: Boolean,
) {
    var text by remember { mutableStateOf("") }
    var menu by remember { mutableStateOf(false) }
    Surface(shape = RoundedCornerShape(20.dp), color = BuddyColors.Surface, modifier = Modifier.width(300.dp).fillMaxHeight()) {
        Column(Modifier.padding(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(bucket.name, style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f).padding(start = 4.dp))
                Text("${tasks.count { !it.isDone }}", style = MaterialTheme.typography.labelLarge, color = BuddyColors.TextTertiary)
                Box {
                    IconButton(onClick = { menu = true }) { Icon(Icons.Rounded.MoreVert, contentDescription = "Bucket options", tint = BuddyColors.TextSecondary) }
                    DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                        DropdownMenuItem(text = { Text("Rename") }, onClick = { menu = false; onRename() })
                        if (canDelete) DropdownMenuItem(text = { Text("Delete bucket", color = BuddyColors.Red) }, onClick = { menu = false; onDelete() })
                    }
                }
            }
            Column(
                Modifier.weight(1f, fill = false).verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                tasks.forEach { t -> TaskCard(t, onToggle = { onToggle(t) }, onClick = { onOpen(t) }) }
            }
            val submit = {
                if (text.isNotBlank()) {
                    onAdd(text)
                    text = ""
                }
            }
            OutlinedTextField(
                value = text,
                onValueChange = { text = it },
                placeholder = { Text("+ Add task") },
                singleLine = true,
                shape = RoundedCornerShape(14.dp),
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                keyboardActions = KeyboardActions(onDone = { submit() }),
                colors = OutlinedTextFieldDefaults.colors(
                    focusedContainerColor = BuddyColors.SurfaceHigh,
                    unfocusedContainerColor = BuddyColors.Surface,
                    focusedBorderColor = BuddyColors.Tasks,
                    unfocusedBorderColor = BuddyColors.Surface,
                ),
                modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
            )
        }
    }
}

@Composable
private fun NameDialog(title: String, initial: String, onDismiss: () -> Unit, onSave: (String) -> Unit) {
    var name by remember { mutableStateOf(initial) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            OutlinedTextField(value = name, onValueChange = { name = it }, singleLine = true, shape = RoundedCornerShape(14.dp))
        },
        confirmButton = { TextButton(enabled = name.isNotBlank(), onClick = { onSave(name) }) { Text("Save") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}
