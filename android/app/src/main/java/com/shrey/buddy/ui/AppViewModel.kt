package com.shrey.buddy.ui

import androidx.health.connect.client.HealthConnectClient
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.shrey.buddy.AppContainer
import com.shrey.buddy.ai.ChatMessage
import com.shrey.buddy.update.ReleaseInfo
import com.shrey.buddy.update.UpdateState
import com.shrey.buddy.data.capture.CaptureRepository
import com.shrey.buddy.data.db.AccountEntity
import com.shrey.buddy.data.db.BucketEntity
import com.shrey.buddy.data.db.PlanEntity
import com.shrey.buddy.data.db.SyncState
import com.shrey.buddy.finance.Category
import com.shrey.buddy.data.db.TaskEntity
import com.shrey.buddy.data.db.VoiceEvent
import com.shrey.buddy.data.health.DaySteps
import com.shrey.buddy.data.tasks.TaskRepository
import com.shrey.buddy.data.tasks.isDone
import com.shrey.buddy.data.tasks.QuickParse
import java.time.LocalDate
import com.shrey.buddy.ui.money.MoneyData
import com.shrey.buddy.ui.money.buildMoneyData
import com.shrey.buddy.voice.BuddyVoiceService
import com.shrey.buddy.voice.VoiceStatus
import io.github.jan.supabase.auth.auth
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

sealed interface HealthUi {
    data object Loading : HealthUi
    data class Unavailable(val sdkStatus: Int) : HealthUi
    data object NeedsPermission : HealthUi
    data class Loaded(val days: List<DaySteps>) : HealthUi
    data class Error(val message: String) : HealthUi
}

/**
 * One shared ViewModel for the M0/M1 shell. Split per screen once screens grow real logic.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class AppViewModel(private val c: AppContainer) : ViewModel() {

    private fun <T> Flow<T>.state(initial: T): StateFlow<T> =
        stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), initial)

    // --- Money ---
    private val selectedAccount = MutableStateFlow<String?>(null)

    val money: StateFlow<MoneyData> = combine(
        c.finance.observeTransactions(),
        c.finance.observeAccounts(),
        c.captures.observeParsed(),
        selectedAccount,
    ) { txns, accounts, captures, selected ->
        buildMoneyData(txns, accounts, captures, c.captures::decode, selected)
    }
        .flowOn(Dispatchers.Default)
        .state(MoneyData())

    val ownIdentifiers: StateFlow<List<String>> = c.finance.ownIdentifiers

    fun selectAccount(id: String?) {
        selectedAccount.value = id
    }

    /** [fuel]: what was filled in under a Fuel transaction (null = no vehicle picked / not fuel). */
    fun updateTransaction(
        t: com.shrey.buddy.ui.money.TxnItem, category: Category, sub: String?, isTransfer: Boolean, note: String?,
        fuel: com.shrey.buddy.finance.FuelEntry? = null,
    ) = viewModelScope.launch {
        c.finance.updateTransaction(t.id, Category.key(category, sub), isTransfer, note)
        if (category == Category.FUEL && !isTransfer && fuel != null) c.fuel.save(t.id, t.amountMinor, t.date, fuel) else c.fuel.clear(t.id)
    }

    fun deleteTransaction(id: String, title: String) = viewModelScope.launch {
        val fuelId = com.shrey.buddy.data.items.ItemStore.stableId("fuel:$id")
        val fuelLog = c.items.get(fuelId, com.shrey.buddy.data.items.VehicleLog.serializer())
        c.finance.deleteTransaction(id)
        c.fuel.clear(id)
        offerUndo("Deleted “$title”") {
            c.finance.restoreTransaction(id)
            fuelLog?.let { c.items.put(com.shrey.buddy.data.items.Kinds.VEHICLE_LOG, it.data, com.shrey.buddy.data.items.VehicleLog.serializer(), id = fuelId, occurredOn = it.date, refId = it.refId) }
        }
    }

    // Sub-categories, vehicles and fuel fill-ups (for the transaction sheets).
    val subcategories = c.items.observe(com.shrey.buddy.data.items.Kinds.SUBCATEGORY, com.shrey.buddy.data.items.Subcategory.serializer()).state(emptyList())
    val vehicles = c.items.observe(com.shrey.buddy.data.items.Kinds.VEHICLE, com.shrey.buddy.data.items.Vehicle.serializer()).state(emptyList())
    val vehicleLogs = c.items.observe(com.shrey.buddy.data.items.Kinds.VEHICLE_LOG, com.shrey.buddy.data.items.VehicleLog.serializer()).state(emptyList())
    fun lastFuelVehicle(): String? = c.fuel.lastVehicleId()

    fun addSubcategory(parent: Category, name: String) = viewModelScope.launch {
        val clean = name.trim().replace("/", "-")
        if (clean.isEmpty()) return@launch
        c.items.put(
            com.shrey.buddy.data.items.Kinds.SUBCATEGORY, com.shrey.buddy.data.items.Subcategory(parent.slug, clean),
            com.shrey.buddy.data.items.Subcategory.serializer(), id = com.shrey.buddy.data.items.ItemStore.stableId("sub:${parent.slug}:${clean.lowercase()}"),
        )
    }

    fun deleteSubcategory(id: String) = viewModelScope.launch { c.items.delete(id) }

    /** A transaction to open on the Money tab (from tapping its notification). */
    private val _openTxn = MutableStateFlow<String?>(null)
    val openTxn = _openTxn.asStateFlow()
    fun openTransaction(id: String?) {
        if (id != null) selectedAccount.value = null // so the list surely contains it
        _openTxn.value = id
    }

    fun saveAccount(account: AccountEntity) = viewModelScope.launch { c.finance.saveAccount(account) }

    fun addTransaction(
        credit: Boolean,
        amountMinor: Long,
        payee: String,
        category: Category,
        accountId: String?,
        note: String?,
        date: java.time.LocalDate,
        sub: String? = null,
        fuel: com.shrey.buddy.finance.FuelEntry? = null,
    ) = viewModelScope.launch {
        val id = java.util.UUID.randomUUID().toString()
        val zone = java.time.ZoneId.systemDefault()
        val at = if (date == java.time.LocalDate.now()) System.currentTimeMillis() else date.atTime(12, 0).atZone(zone).toInstant().toEpochMilli()
        c.finance.addManualTransaction(
            direction = if (credit) "credit" else "debit", amountMinor = amountMinor, payee = payee, category = category,
            accountHint = null, note = note, source = "manual", accountId = accountId, occurredAt = at, sub = sub, id = id,
        )
        if (category == Category.FUEL && !credit && fuel != null) c.fuel.save(id, amountMinor, date, fuel)
        runCatching { c.planning.checkBudgets() }
    }

    private val _receipt = MutableStateFlow<String?>(null)
    /** null idle, "" scanning, otherwise an error. */
    val receiptState = _receipt.asStateFlow()

    fun scanReceipt(base64: String, onResult: (com.shrey.buddy.ai.ReceiptReply) -> Unit) = viewModelScope.launch {
        _receipt.value = ""
        _receipt.value = try {
            val r = c.ai.receipt(base64, "image/jpeg")
            if (r.error != null) r.error else { onResult(r); null }
        } catch (e: Exception) {
            e.message ?: "Couldn't read the receipt"
        }
    }

    fun clearReceipt() { _receipt.value = null }

    fun addAccount(name: String, kind: String) {
        if (name.isNotBlank()) viewModelScope.launch { c.finance.addAccount(name, kind) }
    }

    fun setOwnIdentifiers(raw: String) = viewModelScope.launch {
        c.finance.setOwnIdentifiers(raw.split(',', '\n'))
    }

    val unrecognisedCount = c.captures.observeCount(CaptureRepository.STATUS_UNRECOGNISED).state(0)

    private val _scan = MutableStateFlow<String?>(null)
    val scanStatus = _scan.asStateFlow()
    private val _scanning = MutableStateFlow(false)
    val scanning = _scanning.asStateFlow()

    fun scanSms() = viewModelScope.launch {
        _scanning.value = true
        _scan.value = try {
            val s = c.captures.scanInbox()
            "${s.stored} new · ${s.reparsed} re-parsed · ${s.scanned} SMS checked"
        } catch (e: Exception) {
            "Scan failed: ${e.message ?: e.javaClass.simpleName}"
        }
        _scanning.value = false
    }

    // --- Tasks (Planner) ---
    val tasks: StateFlow<List<TaskEntity>> = c.tasks.observeTasks().state(emptyList())
    val plans: StateFlow<List<PlanEntity>> = c.tasks.observePlans().state(emptyList())
    val buckets: StateFlow<List<BucketEntity>> = c.tasks.observeBuckets().state(emptyList())

    fun addTask(
        title: String,
        planId: String? = null,
        bucketId: String? = null,
        myDay: Boolean = false,
        remindAt: Long? = null,
        onCreated: (TaskEntity) -> Unit = {},
    ) {
        if (title.isNotBlank()) viewModelScope.launch { c.tasks.add(title, planId, bucketId, myDay, remindAt = remindAt)?.let(onCreated) }
    }
    fun saveTask(task: TaskEntity) = viewModelScope.launch { c.tasks.save(task) }
    fun toggle(task: TaskEntity) = viewModelScope.launch {
        val spawned = c.tasks.toggleDone(task)
        if (!task.isDone) {
            offerUndo(if (spawned != null) "Done · next one scheduled" else "Completed “${task.title}”") {
                c.tasks.undoComplete(task, spawned)
            }
        }
    }

    /** Swipe left / long-press: move to a day, keeping the time. */
    fun moveTask(task: TaskEntity, day: LocalDate) = viewModelScope.launch {
        c.tasks.moveTo(task, day)
        offerUndo(if (day == LocalDate.now()) "Moved to today" else if (day == LocalDate.now().plusDays(1)) "Moved to tomorrow" else "Moved") {
            c.tasks.save(task)
        }
    }
    fun setPriority(task: TaskEntity, priority: String) = viewModelScope.launch { c.tasks.save(task.copy(priority = priority)) }
    fun duplicate(task: TaskEntity) = viewModelScope.launch { c.tasks.duplicate(task) }

    /** Quick add from plain English ("Pay rent 5th every month 10am"). */
    fun addParsed(p: QuickParse.Result, myDay: Boolean, onCreated: (TaskEntity) -> Unit = {}) = viewModelScope.launch {
        val zone = java.time.ZoneId.systemDefault()
        val due = p.date?.let { d -> (p.time?.let { d.atTime(it) } ?: d.atStartOfDay()).atZone(zone).toInstant().toEpochMilli() }
        c.tasks.add(
            p.title,
            myDay = myDay || p.date == LocalDate.now(),
            dueAt = due,
            dueAllDay = p.time == null,
            remindAt = if (p.remind && p.time != null) due else null,
            repeat = p.repeat?.encode(),
        )?.let(onCreated)
    }

    /** The last undoable task change, for the Undo bar. */
    data class Undoable(val message: String, val at: Long = System.currentTimeMillis(), val undo: suspend () -> Unit)
    private val _undoable = MutableStateFlow<Undoable?>(null)
    val undoable = _undoable.asStateFlow()
    private fun offerUndo(message: String, undo: suspend () -> Unit) { _undoable.value = Undoable(message, undo = undo) }
    fun clearUndo() { _undoable.value = null }
    fun undo() {
        val last = _undoable.value ?: return
        _undoable.value = null
        viewModelScope.launch { last.undo() }
    }
    fun toggleMyDay(task: TaskEntity) = viewModelScope.launch { c.tasks.toggleMyDay(task) }
    fun delete(task: TaskEntity) = viewModelScope.launch {
        c.tasks.delete(task)
        offerUndo("Deleted “${task.title}”") { c.tasks.get(task.id)?.let { c.tasks.save(it.copy(deletedAt = null)) } }
    }

    fun addPlan(name: String, color: String) = viewModelScope.launch { c.tasks.addPlan(name, color) }
    fun savePlan(plan: PlanEntity) = viewModelScope.launch { c.tasks.savePlan(plan) }
    fun deletePlan(planId: String) = viewModelScope.launch { c.tasks.deletePlan(planId) }
    fun addBucket(planId: String, name: String) = viewModelScope.launch { c.tasks.addBucket(planId, name) }
    fun renameBucket(bucket: BucketEntity, name: String) = viewModelScope.launch { c.tasks.renameBucket(bucket, name) }
    fun deleteBucket(bucket: BucketEntity) = viewModelScope.launch { c.tasks.deleteBucket(bucket) }

    // --- Health ---
    private val _health = MutableStateFlow<HealthUi>(HealthUi.Loading)
    val health = _health.asStateFlow()
    val healthPermissions get() = c.health.permissions

    /** Buddy's own step history (phone sensor + Health Connect), every day it has, by date. */
    val stepDays: StateFlow<Map<LocalDate, com.shrey.buddy.data.items.StepsDay>> =
        c.items.observe(com.shrey.buddy.data.items.Kinds.STEPS_DAY, com.shrey.buddy.data.items.StepsDay.serializer())
            .map { list -> list.mapNotNull { i -> i.date?.let { it to i.data } }.toMap() }
            .state(emptyMap())

    private val stepPrefs = c.context.getSharedPreferences("steps", android.content.Context.MODE_PRIVATE)
    private val _stepGoal = MutableStateFlow(stepPrefs.getInt("goal", 10_000))
    val stepGoal = _stepGoal.asStateFlow()
    fun setStepGoal(goal: Int) {
        stepPrefs.edit().putInt("goal", goal).apply()
        _stepGoal.value = goal
    }
    fun stepSensorReady() = c.steps.hasSensorPermission()
    suspend fun healthMissingSome(): Boolean =
        c.health.sdkStatus() == HealthConnectClient.SDK_AVAILABLE && c.health.missingSome()
    fun hasStepSensor() = c.steps.hasSensor()

    fun refreshHealth() = viewModelScope.launch {
        runCatching { c.steps.refresh() }
        val status = c.health.sdkStatus()
        if (status != HealthConnectClient.SDK_AVAILABLE) {
            _health.value = HealthUi.Unavailable(status)
            return@launch
        }
        _health.value = try {
            if (!c.health.hasPermissions()) HealthUi.NeedsPermission else HealthUi.Loaded(c.health.stepsLast7Days())
        } catch (e: Exception) {
            HealthUi.Error(e.message ?: e.javaClass.simpleName)
        }
    }

    // --- Voice ---
    val voiceStatus: StateFlow<VoiceStatus> = BuddyVoiceService.status
    val voiceEvents: StateFlow<List<VoiceEvent>> = c.db.voiceEventDao().observeRecent(30).state(emptyList())
    val lastVoiceStart: StateFlow<VoiceEvent?> = c.db.voiceEventDao().observeLastStart().state(null)
    val wakesSinceStart: StateFlow<Int> = c.db.voiceEventDao().observeLastStart()
        .flatMapLatest { start -> if (start == null) flowOf(0) else c.db.voiceEventDao().observeWakeCount(start.at) }
        .state(0)

    // --- Ask Buddy (Gemini chat) ---
    data class ChatUiMessage(
        val role: String,
        val text: String,
        val tools: List<String> = emptyList(),
        val failed: Boolean = false,
        /** "On your phone · instant", "Flash-Lite · 2.1 s" — where the answer came from and how long it took. */
        val meta: String? = null,
        /** While streaming: "Checking transactions…" */
        val status: String? = null,
        val streaming: Boolean = false,
    )

    private val _chat = MutableStateFlow<List<ChatUiMessage>>(emptyList())
    val chat = _chat.asStateFlow()
    private val _chatBusy = MutableStateFlow(false)
    val chatBusy = _chatBusy.asStateFlow()

    private fun replaceLast(f: (ChatUiMessage) -> ChatUiMessage) {
        val list = _chat.value
        if (list.isNotEmpty()) _chat.value = list.dropLast(1) + f(list.last())
    }

    /**
     * 1. Simple questions are answered instantly on the phone (no AI).
     * 2. Otherwise the question goes to Buddy AI with a compact snapshot of your numbers, and the answer
     *    streams in word by word.
     */
    fun ask(text: String) {
        val question = text.trim()
        if (question.isEmpty() || _chatBusy.value) return
        val history = _chat.value.filter { !it.failed && !it.streaming }.map { ChatMessage(it.role, it.text) } + ChatMessage("user", question)
        _chat.value = _chat.value + ChatUiMessage("user", question)
        _chatBusy.value = true
        viewModelScope.launch {
            // "spent 450 on lunch", "remind me…", "add task…", "note…": do it right here, no AI round trip.
            val accounts = c.db.accountDao().all().filter { !it.archived }.map { it.name }
            val action = com.shrey.buddy.voice.VoiceIntent.parse(question, accounts)
                ?.takeIf { it.explicit && it.kind != com.shrey.buddy.voice.VoiceIntent.Kind.QUESTION }
            if (action != null) {
                val done = runCatching { com.shrey.buddy.voice.VoiceCommands.perform(c, action, question) }.getOrElse { it.message ?: "Couldn't do that" }
                _chat.value = _chat.value + ChatUiMessage("model", "✓ $done", meta = "Done on your phone · instant")
                _chatBusy.value = false
                return@launch
            }
            val facts = runCatching { com.shrey.buddy.ai.AiContext.facts(c) }.getOrNull()
            val local = facts?.let { com.shrey.buddy.ai.LocalAnswers.answer(question, it) }
            if (local != null) {
                _chat.value = _chat.value + ChatUiMessage("model", local, meta = "On your phone · instant")
                _chatBusy.value = false
                return@launch
            }
            val started = System.currentTimeMillis()
            _chat.value = _chat.value + ChatUiMessage("model", "", status = "Thinking…", streaming = true)
            var got = false
            c.ai.chatStream(history, facts?.let(com.shrey.buddy.ai.AiContext::snapshot)).collect { ev ->
                when (ev) {
                    is com.shrey.buddy.ai.BuddyAi.StreamEvent.Text -> { got = true; replaceLast { it.copy(text = it.text + ev.delta, status = null) } }
                    is com.shrey.buddy.ai.BuddyAi.StreamEvent.Status -> replaceLast { it.copy(status = ev.text) }
                    is com.shrey.buddy.ai.BuddyAi.StreamEvent.Done -> replaceLast {
                        it.copy(streaming = false, status = null, tools = ev.tools, meta = "${modelLabel(ev.model)} · %.1f s".format((System.currentTimeMillis() - started) / 1000.0))
                    }
                    is com.shrey.buddy.ai.BuddyAi.StreamEvent.Failed -> replaceLast {
                        if (got) it.copy(streaming = false, status = null, meta = "Cut short: ${ev.message}")
                        else it.copy(text = ev.message, failed = true, streaming = false, status = null)
                    }
                }
            }
            // Stream ended without a "done" (connection dropped): keep what arrived.
            replaceLast { if (it.streaming) it.copy(streaming = false, status = null, failed = it.text.isBlank(), text = it.text.ifBlank { "Buddy didn't answer. Try again." }) else it }
            _chatBusy.value = false
        }
    }

    private fun modelLabel(model: String?) = when {
        model == null -> "Buddy AI"
        "lite" in model -> "Flash-Lite"
        "flash" in model -> "Flash"
        else -> model
    }

    fun clearChat() {
        _chat.value = emptyList()
    }

    // --- Updates ---
    val updateState: StateFlow<UpdateState> = c.updater.state
    val updater get() = c.updater
    fun checkForUpdate(quiet: Boolean = false) = viewModelScope.launch { c.updater.check(quiet) }
    fun downloadUpdate(release: ReleaseInfo) = viewModelScope.launch { c.updater.download(release) }

    // --- Sync & account ---
    val pendingChanges = c.db.outboxDao().observeCount().state(0)

    /** Aggregate across synced tables: latest success, and any current error. */
    val syncState: StateFlow<SyncState?> = c.db.syncStateDao().observeAll().map { states ->
        if (states.isEmpty()) null
        else SyncState(
            tableName = "*",
            cursor = null,
            lastSyncedAt = states.mapNotNull { it.lastSyncedAt }.maxOrNull(),
            lastError = states.firstNotNullOfOrNull { it.lastError },
        )
    }.state(null)
    private val _syncing = MutableStateFlow(false)
    val syncing = _syncing.asStateFlow()

    fun syncNow() = viewModelScope.launch {
        _syncing.value = true
        c.syncEngine.sync()
        _syncing.value = false
    }

    val userEmail: String? get() = c.supabase.auth.currentUserOrNull()?.email

    fun signOut() = viewModelScope.launch { c.supabase.auth.signOut() }

    init {
        viewModelScope.launch {
            // Backfill: turns captures from before the finance core existed into transactions (idempotent).
            c.captures.materializeAll()
            c.tasks.ensureDefaults()
            c.updater.check(quiet = true)
            runCatching { c.planning.runAutoLog() }
            runCatching { c.planning.checkBudgets() }
            runCatching { c.planning.refreshHoldings() }
            c.syncEngine.sync()
        }
        refreshHealth()
    }
}
