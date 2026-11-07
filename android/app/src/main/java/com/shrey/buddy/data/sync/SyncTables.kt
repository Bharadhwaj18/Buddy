package com.shrey.buddy.data.sync

import com.shrey.buddy.data.db.AccountEntity
import com.shrey.buddy.data.db.BucketEntity
import com.shrey.buddy.data.db.BuddyDatabase
import com.shrey.buddy.data.db.PlanEntity
import com.shrey.buddy.data.db.TaskEntity
import com.shrey.buddy.data.db.TransactionEntity
import com.shrey.buddy.data.db.ItemEntity
import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject

/**
 * One synced table: how to load local rows for push, encode them, and apply pulled rows.
 * Every table follows the same contract (DESIGN.md §6); only the column mapping differs.
 */
abstract class SyncTable<E, P, R>(
    val name: String,
    val pushSerializer: KSerializer<P>,
    val rowSerializer: KSerializer<R>,
) {
    abstract suspend fun loadLocal(ids: List<String>): List<E>
    abstract fun toPush(entity: E): P
    abstract fun rowId(row: R): String
    abstract fun rowUpdatedAt(row: R): Long
    abstract fun rowServerUpdatedAt(row: R): String
    abstract suspend fun localUpdatedAt(id: String): Long?
    abstract suspend fun applyRow(row: R)
}

// ---------------- tasks ----------------

class TasksTable(private val db: BuddyDatabase) :
    SyncTable<TaskEntity, TaskPushDto, TaskRowDto>("tasks", TaskPushDto.serializer(), TaskRowDto.serializer()) {
    override suspend fun loadLocal(ids: List<String>) = db.taskDao().getMany(ids)
    override fun toPush(entity: TaskEntity) = entity.toPushDto()
    override fun rowId(row: TaskRowDto) = row.id
    override fun rowUpdatedAt(row: TaskRowDto) = row.updatedAt.toEpochMillis()
    override fun rowServerUpdatedAt(row: TaskRowDto) = row.serverUpdatedAt
    override suspend fun localUpdatedAt(id: String) = db.taskDao().get(id)?.updatedAt
    override suspend fun applyRow(row: TaskRowDto) = db.taskDao().upsert(row.toEntity())
}

// ---------------- plans & buckets ----------------

@Serializable
data class PlanDto(
    val id: String,
    val name: String,
    val color: String?,
    val position: Double,
    val archived: Boolean,
    @SerialName("created_at") val createdAt: String,
    @SerialName("updated_at") val updatedAt: String,
    @SerialName("deleted_at") val deletedAt: String?,
    @SerialName("server_updated_at") val serverUpdatedAt: String? = null,
)

class PlansTable(private val db: BuddyDatabase) :
    SyncTable<PlanEntity, PlanDto, PlanDto>("plans", PlanDto.serializer(), PlanDto.serializer()) {
    override suspend fun loadLocal(ids: List<String>) = db.planDao().getMany(ids)
    override fun toPush(entity: PlanEntity) = PlanDto(
        id = entity.id, name = entity.name, color = entity.color, position = entity.position, archived = entity.archived,
        createdAt = entity.createdAt.toIso(), updatedAt = entity.updatedAt.toIso(), deletedAt = entity.deletedAt?.toIso(),
    )
    override fun rowId(row: PlanDto) = row.id
    override fun rowUpdatedAt(row: PlanDto) = row.updatedAt.toEpochMillis()
    override fun rowServerUpdatedAt(row: PlanDto) = row.serverUpdatedAt.orEmpty()
    override suspend fun localUpdatedAt(id: String) = db.planDao().get(id)?.updatedAt
    override suspend fun applyRow(row: PlanDto) = db.planDao().upsert(
        PlanEntity(
            id = row.id, name = row.name, color = row.color, position = row.position, archived = row.archived,
            createdAt = row.createdAt.toEpochMillis(), updatedAt = row.updatedAt.toEpochMillis(),
            deletedAt = row.deletedAt?.toEpochMillis(),
        ),
    )
}

@Serializable
data class BucketDto(
    val id: String,
    @SerialName("plan_id") val planId: String,
    val name: String,
    val position: Double,
    @SerialName("created_at") val createdAt: String,
    @SerialName("updated_at") val updatedAt: String,
    @SerialName("deleted_at") val deletedAt: String?,
    @SerialName("server_updated_at") val serverUpdatedAt: String? = null,
)

class BucketsTable(private val db: BuddyDatabase) :
    SyncTable<BucketEntity, BucketDto, BucketDto>("buckets", BucketDto.serializer(), BucketDto.serializer()) {
    override suspend fun loadLocal(ids: List<String>) = db.bucketDao().getMany(ids)
    override fun toPush(entity: BucketEntity) = BucketDto(
        id = entity.id, planId = entity.planId, name = entity.name, position = entity.position,
        createdAt = entity.createdAt.toIso(), updatedAt = entity.updatedAt.toIso(), deletedAt = entity.deletedAt?.toIso(),
    )
    override fun rowId(row: BucketDto) = row.id
    override fun rowUpdatedAt(row: BucketDto) = row.updatedAt.toEpochMillis()
    override fun rowServerUpdatedAt(row: BucketDto) = row.serverUpdatedAt.orEmpty()
    override suspend fun localUpdatedAt(id: String) = db.bucketDao().get(id)?.updatedAt
    override suspend fun applyRow(row: BucketDto) = db.bucketDao().upsert(
        BucketEntity(
            id = row.id, planId = row.planId, name = row.name, position = row.position,
            createdAt = row.createdAt.toEpochMillis(), updatedAt = row.updatedAt.toEpochMillis(),
            deletedAt = row.deletedAt?.toEpochMillis(),
        ),
    )
}

// ---------------- items (all "life" modules) ----------------

@Serializable
data class ItemDto(
    val id: String,
    val kind: String,
    val data: JsonObject,
    @SerialName("occurred_on") val occurredOn: String?,
    @SerialName("ref_id") val refId: String?,
    @SerialName("created_at") val createdAt: String,
    @SerialName("updated_at") val updatedAt: String,
    @SerialName("deleted_at") val deletedAt: String?,
    @SerialName("server_updated_at") val serverUpdatedAt: String? = null,
)

class ItemsTable(private val db: BuddyDatabase) :
    SyncTable<ItemEntity, ItemDto, ItemDto>("items", ItemDto.serializer(), ItemDto.serializer()) {
    private val json = Json { ignoreUnknownKeys = true }
    override suspend fun loadLocal(ids: List<String>) = db.itemDao().getMany(ids)
    override fun toPush(entity: ItemEntity) = ItemDto(
        id = entity.id, kind = entity.kind,
        data = runCatching { json.parseToJsonElement(entity.dataJson).jsonObject }.getOrDefault(JsonObject(emptyMap())),
        occurredOn = entity.occurredOn, refId = entity.refId,
        createdAt = entity.createdAt.toIso(), updatedAt = entity.updatedAt.toIso(), deletedAt = entity.deletedAt?.toIso(),
    )
    override fun rowId(row: ItemDto) = row.id
    override fun rowUpdatedAt(row: ItemDto) = row.updatedAt.toEpochMillis()
    override fun rowServerUpdatedAt(row: ItemDto) = row.serverUpdatedAt.orEmpty()
    override suspend fun localUpdatedAt(id: String) = db.itemDao().get(id)?.updatedAt
    override suspend fun applyRow(row: ItemDto) = db.itemDao().upsert(
        ItemEntity(
            id = row.id, kind = row.kind, dataJson = row.data.toString(), occurredOn = row.occurredOn, refId = row.refId,
            createdAt = row.createdAt.toEpochMillis(), updatedAt = row.updatedAt.toEpochMillis(),
            deletedAt = row.deletedAt?.toEpochMillis(),
        ),
    )
}

// ---------------- accounts ----------------

@Serializable
data class AccountDto(
    val id: String,
    val name: String,
    val bank: String?,
    val kind: String,
    val last4: String?,
    @SerialName("is_primary") val isPrimary: Boolean,
    @SerialName("include_in_totals") val includeInTotals: Boolean,
    val archived: Boolean,
    @SerialName("created_at") val createdAt: String,
    @SerialName("updated_at") val updatedAt: String,
    @SerialName("deleted_at") val deletedAt: String?,
    @SerialName("server_updated_at") val serverUpdatedAt: String? = null,
    @SerialName("manual_balance_minor") val manualBalanceMinor: Long? = null,
    @SerialName("manual_balance_at") val manualBalanceAt: String? = null,
)

class AccountsTable(private val db: BuddyDatabase) :
    SyncTable<AccountEntity, AccountDto, AccountDto>("accounts", AccountDto.serializer(), AccountDto.serializer()) {
    override suspend fun loadLocal(ids: List<String>) = db.accountDao().getMany(ids)
    override fun toPush(entity: AccountEntity) = AccountDto(
        id = entity.id, name = entity.name, bank = entity.bank, kind = entity.kind, last4 = entity.last4,
        isPrimary = entity.isPrimary, includeInTotals = entity.includeInTotals, archived = entity.archived,
        createdAt = entity.createdAt.toIso(), updatedAt = entity.updatedAt.toIso(), deletedAt = entity.deletedAt?.toIso(),
        manualBalanceMinor = entity.manualBalanceMinor, manualBalanceAt = entity.manualBalanceAt?.toIso(),
    )
    override fun rowId(row: AccountDto) = row.id
    override fun rowUpdatedAt(row: AccountDto) = row.updatedAt.toEpochMillis()
    override fun rowServerUpdatedAt(row: AccountDto) = row.serverUpdatedAt.orEmpty()
    override suspend fun localUpdatedAt(id: String) = db.accountDao().get(id)?.updatedAt
    override suspend fun applyRow(row: AccountDto) = db.accountDao().upsert(
        AccountEntity(
            id = row.id, name = row.name, bank = row.bank, kind = row.kind, last4 = row.last4,
            isPrimary = row.isPrimary, includeInTotals = row.includeInTotals, archived = row.archived,
            createdAt = row.createdAt.toEpochMillis(), updatedAt = row.updatedAt.toEpochMillis(),
            deletedAt = row.deletedAt?.toEpochMillis(),
            manualBalanceMinor = row.manualBalanceMinor, manualBalanceAt = row.manualBalanceAt?.toEpochMillis(),
        ),
    )
}

// ---------------- transactions ----------------

@Serializable
data class TransactionDto(
    val id: String,
    @SerialName("account_id") val accountId: String?,
    val direction: String,
    @SerialName("amount_minor") val amountMinor: Long,
    @SerialName("occurred_at") val occurredAt: String,
    val payee: String?,
    @SerialName("counterparty_raw") val counterpartyRaw: String?,
    val channel: String?,
    val reference: String?,
    val category: String,
    @SerialName("category_source") val categorySource: String,
    val note: String?,
    @SerialName("is_transfer") val isTransfer: Boolean,
    @SerialName("transfer_group") val transferGroup: String?,
    val source: String,
    @SerialName("dedupe_key") val dedupeKey: String,
    @SerialName("balance_after_minor") val balanceAfterMinor: Long?,
    @SerialName("created_at") val createdAt: String,
    @SerialName("updated_at") val updatedAt: String,
    @SerialName("deleted_at") val deletedAt: String?,
    @SerialName("server_updated_at") val serverUpdatedAt: String? = null,
)

class TransactionsTable(private val db: BuddyDatabase) :
    SyncTable<TransactionEntity, TransactionDto, TransactionDto>(
        "transactions", TransactionDto.serializer(), TransactionDto.serializer(),
    ) {
    override suspend fun loadLocal(ids: List<String>) = db.transactionDao().getMany(ids)
    override fun toPush(entity: TransactionEntity) = TransactionDto(
        id = entity.id, accountId = entity.accountId, direction = entity.direction, amountMinor = entity.amountMinor,
        occurredAt = entity.occurredAt.toIso(), payee = entity.payee, counterpartyRaw = entity.counterpartyRaw,
        channel = entity.channel, reference = entity.reference, category = entity.category,
        categorySource = entity.categorySource, note = entity.note, isTransfer = entity.isTransfer,
        transferGroup = entity.transferGroup, source = entity.source, dedupeKey = entity.dedupeKey,
        balanceAfterMinor = entity.balanceAfterMinor, createdAt = entity.createdAt.toIso(),
        updatedAt = entity.updatedAt.toIso(), deletedAt = entity.deletedAt?.toIso(),
    )
    override fun rowId(row: TransactionDto) = row.id
    override fun rowUpdatedAt(row: TransactionDto) = row.updatedAt.toEpochMillis()
    override fun rowServerUpdatedAt(row: TransactionDto) = row.serverUpdatedAt.orEmpty()
    override suspend fun localUpdatedAt(id: String) = db.transactionDao().get(id)?.updatedAt
    override suspend fun applyRow(row: TransactionDto) {
        // Keep the local-only link to the raw SMS if we already have this row.
        val captureId = db.transactionDao().get(row.id)?.captureId
        db.transactionDao().upsert(
            TransactionEntity(
                id = row.id, accountId = row.accountId, direction = row.direction, amountMinor = row.amountMinor,
                occurredAt = row.occurredAt.toEpochMillis(), payee = row.payee, counterpartyRaw = row.counterpartyRaw,
                channel = row.channel, reference = row.reference, category = row.category,
                categorySource = row.categorySource, note = row.note, isTransfer = row.isTransfer,
                transferGroup = row.transferGroup, source = row.source, dedupeKey = row.dedupeKey,
                balanceAfterMinor = row.balanceAfterMinor, captureId = captureId,
                createdAt = row.createdAt.toEpochMillis(), updatedAt = row.updatedAt.toEpochMillis(),
                deletedAt = row.deletedAt?.toEpochMillis(),
            ),
        )
    }
}
