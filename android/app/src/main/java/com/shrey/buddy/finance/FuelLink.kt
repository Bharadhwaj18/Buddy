package com.shrey.buddy.finance

import android.content.Context
import com.shrey.buddy.data.items.ItemStore
import com.shrey.buddy.data.items.Kinds
import com.shrey.buddy.data.items.Vehicle
import com.shrey.buddy.data.items.VehicleLog
import java.time.LocalDate

/** What you filled in under a Fuel transaction. */
data class FuelEntry(val vehicleId: String, val litres: Double = 0.0, val odometerKm: Int = 0)

/**
 * Keeps a Fuel transaction and its vehicle's fuel log in step: one log per transaction (a stable id),
 * so editing the transaction updates the same fill-up and deleting it removes the fill-up.
 */
class FuelLink(private val items: ItemStore, context: Context) {
    private val prefs = context.getSharedPreferences("fuel", Context.MODE_PRIVATE)

    private fun logId(txnId: String) = ItemStore.stableId("fuel:$txnId")

    /** The vehicle you last filled — preselected next time when you have more than one. */
    fun lastVehicleId(): String? = prefs.getString("lastVehicle", null)

    suspend fun save(txnId: String, amountMinor: Long, day: LocalDate, entry: FuelEntry, note: String = "") {
        items.put(
            Kinds.VEHICLE_LOG,
            VehicleLog("fuel", amountMinor, entry.odometerKm, entry.litres, note, txnId = txnId),
            VehicleLog.serializer(), id = logId(txnId), occurredOn = day, refId = entry.vehicleId,
        )
        prefs.edit().putString("lastVehicle", entry.vehicleId).apply()
        val v = items.get(entry.vehicleId, Vehicle.serializer()) ?: return
        if (entry.odometerKm > v.data.odometerKm) {
            items.put(Kinds.VEHICLE, v.data.copy(odometerKm = entry.odometerKm), Vehicle.serializer(), id = v.id)
        }
    }

    /** The transaction was deleted or is no longer Fuel. */
    suspend fun clear(txnId: String) {
        if (items.get(logId(txnId), VehicleLog.serializer()) != null) items.delete(logId(txnId))
    }

    /** A voice/manual entry was merged into the bank's SMS transaction: move its fill-up across. */
    suspend fun relink(oldTxnId: String, newTxnId: String) {
        val old = items.get(logId(oldTxnId), VehicleLog.serializer()) ?: return
        val vehicle = old.refId ?: return
        items.put(
            Kinds.VEHICLE_LOG, old.data.copy(txnId = newTxnId), VehicleLog.serializer(),
            id = logId(newTxnId), occurredOn = old.date, refId = vehicle,
        )
        items.delete(old.id)
    }
}
