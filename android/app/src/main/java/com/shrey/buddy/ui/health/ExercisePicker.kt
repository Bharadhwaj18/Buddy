package com.shrey.buddy.ui.health

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material.icons.rounded.RadioButtonUnchecked
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import com.shrey.buddy.health.Exercise
import com.shrey.buddy.health.ExerciseLibrary
import com.shrey.buddy.ui.theme.BuddyColors

/** Pick one or more exercises from the library (or type your own). */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ExercisePickerSheet(
    onDismiss: () -> Unit,
    onPick: (List<Exercise>) -> Unit,
    onCustom: (String) -> Unit,
) {
    val context = LocalContext.current
    // Parsing ~1 MB of JSON and searching 870 entries must stay off the main thread.
    val all by androidx.compose.runtime.produceState(emptyList<Exercise>()) {
        value = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Default) { ExerciseLibrary.all(context) }
    }
    var query by remember { mutableStateOf("") }
    var muscle by remember { mutableStateOf<String?>(null) }
    var equipment by remember { mutableStateOf<String?>(null) }
    var picked by remember { mutableStateOf(listOf<Exercise>()) }
    var info by remember { mutableStateOf<Exercise?>(null) }
    val results by androidx.compose.runtime.produceState(emptyList<Exercise>(), all, query, muscle, equipment) {
        value = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Default) { ExerciseLibrary.search(all, query, muscle, equipment).take(150) }
    }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        sheetGesturesEnabled = false,
        containerColor = BuddyColors.Surface,
    ) {
        Column(Modifier.fillMaxHeight(0.92f).navigationBarsPadding()) {
            Column(Modifier.padding(horizontal = 20.dp)) {
                Text("Add exercises", style = MaterialTheme.typography.titleLarge)
                OutlinedTextField(
                    value = query,
                    onValueChange = { query = it },
                    placeholder = { Text(if (all.isEmpty()) "Loading exercises…" else "Search ${all.size} exercises") },
                    leadingIcon = { Icon(Icons.Rounded.Search, null) },
                    singleLine = true,
                    shape = RoundedCornerShape(16.dp),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedContainerColor = BuddyColors.SurfaceHigh, unfocusedContainerColor = BuddyColors.SurfaceHigh,
                        focusedBorderColor = BuddyColors.Fitness, unfocusedBorderColor = BuddyColors.SurfaceHigh,
                    ),
                    modifier = Modifier.fillMaxWidth().padding(top = 10.dp),
                )
            }
            ChipRow(ExerciseLibrary.muscleGroups, muscle) { muscle = it }
            ChipRow(ExerciseLibrary.equipment, equipment) { equipment = it }

            LazyColumn(Modifier.weight(1f), contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 12.dp, vertical = 4.dp)) {
                if (query.isNotBlank() && results.none { it.name.equals(query.trim(), true) }) {
                    item {
                        Row(
                            Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).clickable { onCustom(query.trim()) }.padding(10.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Icon(Icons.Rounded.Add, null, tint = BuddyColors.Fitness)
                            Text("Add “${query.trim()}” as my own exercise", modifier = Modifier.padding(start = 12.dp), color = BuddyColors.Fitness)
                        }
                    }
                }
                items(results, key = { it.id }) { e ->
                    val selected = picked.any { it.id == e.id }
                    Row(
                        Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp))
                            .background(if (selected) BuddyColors.Fitness.copy(alpha = 0.12f) else BuddyColors.Surface)
                            .clickable { picked = if (selected) picked.filterNot { it.id == e.id } else picked + e }
                            .padding(8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        AsyncImage(
                            // Decode a small thumbnail, not the full photo — keeps scrolling smooth.
                            model = coil3.request.ImageRequest.Builder(context)
                                .data(e.imageUrls.firstOrNull())
                                .size(160)
                                .build(),
                            contentDescription = null,
                            contentScale = ContentScale.Crop,
                            modifier = Modifier.size(52.dp).clip(RoundedCornerShape(10.dp)).background(BuddyColors.SurfaceHigh),
                        )
                        Column(Modifier.weight(1f).padding(start = 12.dp)) {
                            Text(e.name, style = MaterialTheme.typography.titleSmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
                            Text(
                                listOfNotNull(e.primaryMuscles.firstOrNull(), e.equipment).joinToString(" · ").replaceFirstChar { it.uppercase() },
                                style = MaterialTheme.typography.labelSmall, color = BuddyColors.TextTertiary,
                            )
                        }
                        IconButton(onClick = { info = e }) { Icon(Icons.Rounded.Info, contentDescription = "How to", tint = BuddyColors.TextTertiary) }
                        Icon(
                            if (selected) Icons.Rounded.CheckCircle else Icons.Rounded.RadioButtonUnchecked, null,
                            tint = if (selected) BuddyColors.Fitness else BuddyColors.TextTertiary,
                        )
                    }
                }
            }
            Button(
                onClick = { onPick(picked) },
                enabled = picked.isNotEmpty(),
                modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 12.dp).height(52.dp),
                shape = RoundedCornerShape(16.dp),
                colors = ButtonDefaults.buttonColors(containerColor = BuddyColors.Fitness, contentColor = BuddyColors.Background),
            ) { Text(if (picked.isEmpty()) "Pick exercises" else "Add ${picked.size}") }
        }
    }
    info?.let { ExerciseInfoSheet(it, onDismiss = { info = null }) }
}

@Composable
private fun ChipRow(options: List<String>, selected: String?, onSelect: (String?) -> Unit) {
    LazyRow(
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 20.dp),
        modifier = Modifier.padding(top = 8.dp),
    ) {
        items(options) { o ->
            FilterChip(
                selected = selected == o,
                onClick = { onSelect(if (selected == o) null else o) },
                label = { Text(o.replaceFirstChar { it.uppercase() }) },
                colors = FilterChipDefaults.filterChipColors(selectedContainerColor = BuddyColors.Fitness.copy(alpha = 0.25f), selectedLabelColor = BuddyColors.TextPrimary),
            )
        }
    }
}

/** Photos, muscles and step-by-step instructions for one exercise. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ExerciseInfoSheet(e: Exercise, onDismiss: () -> Unit) {
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        sheetGesturesEnabled = false,
        containerColor = BuddyColors.Surface,
    ) {
        Column(Modifier.padding(horizontal = 20.dp).navigationBarsPadding().verticalScroll(rememberScrollState()).padding(bottom = 20.dp)) {
            Text(e.name, style = MaterialTheme.typography.titleLarge)
            Text(
                listOfNotNull(e.level, e.equipment, e.mechanic).joinToString(" · ").replaceFirstChar { it.uppercase() },
                style = MaterialTheme.typography.labelMedium, color = BuddyColors.TextTertiary,
            )
            Row(Modifier.fillMaxWidth().padding(top = 12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                e.imageUrls.take(2).forEach { url ->
                    AsyncImage(
                        model = url, contentDescription = null, contentScale = ContentScale.Crop,
                        modifier = Modifier.weight(1f).height(150.dp).clip(RoundedCornerShape(14.dp)).background(BuddyColors.SurfaceHigh),
                    )
                }
            }
            Text("Muscles", style = MaterialTheme.typography.titleSmall, color = BuddyColors.TextSecondary, modifier = Modifier.padding(top = 14.dp))
            Text(
                (e.primaryMuscles.map { it.replaceFirstChar(Char::uppercase) } + e.secondaryMuscles.map { "$it (secondary)" }).joinToString(", "),
                style = MaterialTheme.typography.bodyMedium,
            )
            Text("How to", style = MaterialTheme.typography.titleSmall, color = BuddyColors.TextSecondary, modifier = Modifier.padding(top = 14.dp))
            e.instructions.forEachIndexed { i, step ->
                Row(Modifier.padding(top = 6.dp)) {
                    Box(Modifier.size(22.dp).clip(RoundedCornerShape(11.dp)).background(BuddyColors.Fitness.copy(alpha = 0.2f)), contentAlignment = Alignment.Center) {
                        Text("${i + 1}", style = MaterialTheme.typography.labelSmall, color = BuddyColors.Fitness)
                    }
                    Text(step, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(start = 10.dp))
                }
            }
        }
    }
}
