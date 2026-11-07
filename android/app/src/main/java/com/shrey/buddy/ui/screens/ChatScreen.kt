package com.shrey.buddy.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.automirrored.rounded.Send
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.material.icons.rounded.DeleteSweep
import androidx.compose.material3.AssistChip
import androidx.compose.material3.AssistChipDefaults
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.shrey.buddy.ui.AppViewModel
import com.shrey.buddy.ui.components.IconBadge
import com.shrey.buddy.ui.theme.BuddyColors

private val suggestions = listOf(
    "How much did I spend this month?",
    "Where did most of my money go last month?",
    "How much have I spent on food this week?",
    "What are my account balances?",
    "What tasks are still open?",
)

private val toolLabels = mapOf(
    "query_transactions" to "transactions",
    "spending_summary" to "spending totals",
    "list_accounts" to "accounts",
    "list_tasks" to "tasks",
    "query_items" to "your logs",
)

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun ChatScreen(vm: AppViewModel, onBack: () -> Unit) {
    val messages by vm.chat.collectAsStateWithLifecycle()
    val busy by vm.chatBusy.collectAsStateWithLifecycle()
    var input by rememberSaveable { mutableStateOf("") }
    val listState = rememberLazyListState()
    val send = {
        vm.ask(input)
        input = ""
    }

    // Follow the answer as it streams in.
    LaunchedEffect(messages.size, messages.lastOrNull()?.text?.length) {
        if (messages.isNotEmpty()) listState.scrollToItem(messages.size - 1)
    }

    Column(Modifier.fillMaxSize().imePadding()) {
        Row(Modifier.padding(horizontal = 8.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = "Back") }
            Text("Ask Buddy", style = MaterialTheme.typography.headlineSmall, modifier = Modifier.weight(1f))
            if (messages.isNotEmpty()) {
                IconButton(onClick = { vm.clearChat() }) { Icon(Icons.Rounded.DeleteSweep, contentDescription = "Clear chat") }
            }
        }

        LazyColumn(
            state = listState,
            modifier = Modifier.weight(1f).fillMaxWidth(),
            contentPadding = PaddingValues(horizontal = 20.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            if (messages.isEmpty()) {
                item {
                    Column(Modifier.padding(top = 24.dp)) {
                        IconBadge(Icons.Rounded.AutoAwesome, BuddyColors.Voice, 56.dp)
                        Text("Ask about your money and tasks", style = MaterialTheme.typography.titleLarge, modifier = Modifier.padding(top = 16.dp))
                        Text(
                            "Buddy looks things up in your own data. Gemini only sees amounts, dates, categories and merchant names — " +
                                "never account numbers or the names of people you paid.",
                            style = MaterialTheme.typography.bodySmall,
                            color = BuddyColors.TextTertiary,
                            modifier = Modifier.padding(top = 4.dp, bottom = 16.dp),
                        )
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            suggestions.forEach { s ->
                                AssistChip(
                                    onClick = { vm.ask(s) },
                                    label = { Text(s) },
                                    colors = AssistChipDefaults.assistChipColors(containerColor = BuddyColors.Surface),
                                )
                            }
                        }
                    }
                }
            }
            items(messages) { m -> Bubble(m) }
        }

        Row(
            Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            OutlinedTextField(
                value = input,
                onValueChange = { input = it },
                placeholder = { Text("Ask anything…") },
                shape = RoundedCornerShape(22.dp),
                maxLines = 4,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
                keyboardActions = KeyboardActions(onSend = { if (input.isNotBlank()) send() }),
                colors = OutlinedTextFieldDefaults.colors(
                    focusedContainerColor = BuddyColors.Surface,
                    unfocusedContainerColor = BuddyColors.Surface,
                    focusedBorderColor = BuddyColors.Voice,
                    unfocusedBorderColor = BuddyColors.Surface,
                ),
                modifier = Modifier.weight(1f),
            )
            FilledIconButton(
                onClick = send,
                enabled = input.isNotBlank() && !busy,
                modifier = Modifier.padding(start = 8.dp).size(52.dp),
                colors = IconButtonDefaults.filledIconButtonColors(containerColor = BuddyColors.Voice, contentColor = BuddyColors.Background),
            ) { Icon(Icons.AutoMirrored.Rounded.Send, contentDescription = "Send") }
        }
    }
}

@Composable
private fun Bubble(m: AppViewModel.ChatUiMessage) {
    val mine = m.role == "user"
    Box(Modifier.fillMaxWidth(), contentAlignment = if (mine) Alignment.CenterEnd else Alignment.CenterStart) {
        Column(
            Modifier
                .widthIn(max = 320.dp)
                .clip(RoundedCornerShape(20.dp, 20.dp, if (mine) 6.dp else 20.dp, if (mine) 20.dp else 6.dp))
                .background(
                    when {
                        mine -> BuddyColors.Voice
                        m.failed -> BuddyColors.Red.copy(alpha = 0.14f)
                        else -> BuddyColors.Surface
                    },
                )
                .padding(horizontal = 16.dp, vertical = 12.dp),
        ) {
            if (m.text.isNotEmpty() || m.status == null) {
                Text(
                    m.text + if (m.streaming && m.text.isNotEmpty()) " ▍" else "",
                    style = MaterialTheme.typography.bodyMedium,
                    color = if (mine) BuddyColors.Background else BuddyColors.TextPrimary,
                )
            }
            m.status?.let {
                Text(it, style = MaterialTheme.typography.bodyMedium, color = BuddyColors.TextTertiary, modifier = Modifier.padding(top = if (m.text.isNotEmpty()) 6.dp else 0.dp))
            }
            val footer = listOfNotNull(
                m.tools.takeIf { it.isNotEmpty() }?.let { "Checked ${it.distinct().joinToString { t -> toolLabels[t] ?: t }}" },
                m.meta,
            )
            if (footer.isNotEmpty()) {
                Text(
                    footer.joinToString(" · "),
                    style = MaterialTheme.typography.labelSmall,
                    color = BuddyColors.TextTertiary,
                    modifier = Modifier.padding(top = 6.dp),
                )
            }
        }
    }
}
