package com.shrey.buddy.ingest.sms

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

enum class Bank { INDUSIND, SBI }

enum class Direction { DEBIT, CREDIT }

/** Result of parsing one SMS from an allowlisted bank sender. Amounts are in paise. */
@Serializable
sealed interface SmsParse {
    val bank: Bank

    @Serializable
    @SerialName("txn")
    data class Transaction(
        override val bank: Bank,
        val direction: Direction,
        val amountMinor: Long,
        val accountLast4: String?,
        /** Payee for debits, payer for credits, when the bank includes it. */
        val counterparty: String? = null,
        /** e.g. "Debit Card Purchase", "UPI". */
        val channel: String? = null,
        val reference: String? = null,
        /** Available balance after the transaction, when the bank includes it (IndusInd does, SBI UPI doesn't). */
        val balanceMinor: Long? = null,
        /** ISO date from the message body, when present (SBI). */
        val txnDate: String? = null,
    ) : SmsParse

    /** A recurring-payment mandate (e.g. a subscription) was set up. Not money moving yet. */
    @Serializable
    @SerialName("mandate")
    data class Mandate(
        override val bank: Bank,
        val amountMinor: Long,
        val payee: String,
        val accountLast4: String?,
        val umn: String? = null,
    ) : SmsParse

    /** A debit announced in advance (UPI AutoPay pre-debit notice, scheduled payment). Feeds bill reminders. */
    @Serializable
    @SerialName("upcoming")
    data class UpcomingDebit(
        override val bank: Bank,
        val amountMinor: Long,
        val payee: String?,
        /** ISO date. */
        val dueDate: String?,
        val umn: String? = null,
    ) : SmsParse

    /** Informational message from the bank (KYC, card blocked, OTP, marketing...). Never stored. */
    @Serializable
    @SerialName("info")
    data class NotTransaction(override val bank: Bank) : SmsParse

    /** Mentions money but matched no template. Stored locally so the parser can be improved. */
    @Serializable
    @SerialName("unrecognised")
    data class Unrecognised(override val bank: Bank) : SmsParse
}
