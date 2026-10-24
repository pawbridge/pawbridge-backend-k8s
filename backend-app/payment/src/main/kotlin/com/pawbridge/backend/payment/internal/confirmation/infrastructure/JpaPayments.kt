package com.pawbridge.backend.payment.internal.confirmation.infrastructure

import com.pawbridge.backend.payment.internal.confirmation.application.PaymentOrderAccessDeniedException
import com.pawbridge.backend.payment.internal.confirmation.application.PaymentOrderNotPayableException
import com.pawbridge.backend.payment.internal.confirmation.application.Payments
import com.pawbridge.backend.payment.internal.confirmation.application.dto.TossPaymentCancelRequest
import com.pawbridge.backend.payment.internal.confirmation.application.dto.TossPaymentConfirmRequest
import com.pawbridge.backend.payment.internal.confirmation.application.dto.TossPaymentResponse
import com.pawbridge.backend.payment.internal.confirmation.infrastructure.jpa.model.Payment
import com.pawbridge.backend.payment.internal.confirmation.infrastructure.jpa.model.PaymentCompensationRecord
import com.pawbridge.backend.payment.internal.confirmation.infrastructure.jpa.model.PaymentStatus
import com.pawbridge.backend.payment.internal.confirmation.infrastructure.jpa.repository.PaymentCompensationRecordRepository
import com.pawbridge.backend.payment.internal.confirmation.infrastructure.jpa.repository.PaymentRepository
import com.pawbridge.backend.payment.internal.confirmation.infrastructure.toss.TossPaymentsClient
import com.pawbridge.backend.store.api.StorePaymentEvents
import com.pawbridge.backend.store.api.StorePaymentOrders
import org.apache.commons.logging.LogFactory
import org.springframework.transaction.CannotCreateTransactionException
import org.springframework.transaction.support.TransactionOperations
import org.springframework.web.client.RestClientResponseException
import java.time.Clock
import java.time.LocalDateTime
import java.time.ZoneOffset
import java.util.Base64
import java.util.Optional

/**
 * Confirmation that applies the provider result to the Store order directly.
 * The provider is called without a transaction or writer connection; the payment row and the order change
 * share one Store writer transaction. Provider payments that may need manual action are recorded and logged.
 */
class JpaPayments internal constructor(
    private val tossPaymentsClient: TossPaymentsClient,
    private val paymentRepository: PaymentRepository,
    private val compensations: PaymentCompensationRecordRepository,
    private val storeServiceClient: StorePaymentOrders,
    private val storePayments: StorePaymentEvents,
    private val tossSecretKey: String,
    private val writes: TransactionOperations,
    private val reads: TransactionOperations,
    private val clock: Clock,
    private val pause: Pause,
) : Payments {

    constructor(
        tossPaymentsClient: TossPaymentsClient,
        paymentRepository: PaymentRepository,
        compensations: PaymentCompensationRecordRepository,
        storeServiceClient: StorePaymentOrders,
        storePayments: StorePaymentEvents,
        tossSecretKey: String,
        writes: TransactionOperations,
        reads: TransactionOperations,
        clock: Clock,
    ) : this(
        tossPaymentsClient,
        paymentRepository,
        compensations,
        storeServiceClient,
        storePayments,
        tossSecretKey,
        writes,
        reads,
        clock,
        Pause { millis -> Thread.sleep(millis) },
    )

    /** Back-off between writer connection attempts; replaceable in tests. */
    internal fun interface Pause {
        @Throws(InterruptedException::class)
        fun pause(millis: Long)
    }

    override fun confirmPayment(verifiedUserId: Long?, request: TossPaymentConfirmRequest): TossPaymentResponse {
        val orderId = request.orderId
        val amount = request.amount
        // A. Outside any transaction: owner first (no stored payment is revealed to another member),
        // then existing DONE re-response, then amount/state checks before the provider.
        val order = storeServiceClient.getOrder(orderId)
        if (order.userId() == null || order.userId() != verifiedUserId) {
            throw PaymentOrderAccessDeniedException()
        }
        val existing = reads.execute { doneFor(orderId) }
        if (existing != null && existing.isPresent) {
            val payment = existing.get()
            if (payment.amount != amount) {
                throw IllegalStateException("Payment exists but amount mismatch")
            }
            return stored(payment)
        }
        if (order.totalAmount() == null || order.totalAmount() != amount) {
            throw IllegalStateException("Payment Amount Mismatch")
        }
        if (order.status() != "PENDING") {
            throw PaymentOrderNotPayableException()
        }

        val response = try {
            tossPaymentsClient.confirmPayment(authorization(), request)
        } catch (failure: RuntimeException) {
            // B. Classify the provider failure (see ProviderFailure for the allow-list).
            return when (classify(failure)) {
                ProviderFailure.ALREADY_PROCESSED -> {
                    val found = reads.execute { doneFor(orderId) }
                    if (found != null && found.isPresent) {
                        stored(found.get())
                    } else {
                        // Toss says processed but nothing is recorded here: resolve it with one lookup.
                        resolveUnknown(verifiedUserId, request, true)
                    }
                }
                // Our request was refused (credentials, merchant configuration, rate limit); the customer's
                // payment was not attempted, so the order stays PENDING and no money needs compensation.
                ProviderFailure.NOT_PROCESSED -> throw RuntimeException(
                    "Payment provider did not process the request. The order remains pending.",
                    failure,
                )
                ProviderFailure.REJECTED -> reject(request)
                ProviderFailure.UNKNOWN -> resolveUnknown(verifiedUserId, request, false)
            }
        }
        if (response == null) {
            return resolveUnknown(verifiedUserId, request, false)
        }
        if (response.status != "DONE") {
            return response
        }
        // Never apply a provider approval to an order other than the one checked above.
        if (orderId != response.orderId) {
            return compensate(verifiedUserId, response, "ORDER_MISMATCH", null)
        }
        return applySuccess(verifiedUserId, response)
    }

    /**
     * Unknown provider result: one GET lookup. DONE for this order is applied; a clear failure for this order follows
     * the rejection path (never after ALREADY_PROCESSED); anything else keeps the order PENDING and is recorded.
     */
    private fun resolveUnknown(
        userId: Long?,
        request: TossPaymentConfirmRequest,
        alreadyProcessed: Boolean,
    ): TossPaymentResponse {
        val orderId = request.orderId
        val looked = lookup(request.paymentKey)
        if (looked != null && orderId != null && orderId == looked.orderId) {
            if (looked.status == "DONE") {
                return applySuccess(userId, looked)
            }
            if (!alreadyProcessed && looked.status in LOOKUP_FAILURES) {
                return reject(request)
            }
        }
        record(
            request.paymentKey,
            orderId,
            request.amount,
            userId,
            PaymentCompensationRecord.Stage.UNKNOWN_RESULT,
            if (alreadyProcessed) "ALREADY_PROCESSED_UNRECORDED" else "PROVIDER_RESULT_UNKNOWN",
        )
        throw RuntimeException("Payment result is unknown. The order remains pending.")
    }

    /** C. One Store writer transaction: lock and re-check the order, then insert the payment. D on any failure. */
    private fun applySuccess(userId: Long?, response: TossPaymentResponse): TossPaymentResponse {
        var ranking: Runnable? = null
        val applied = try {
            withConnectionRetry {
                writes.execute { status ->
                    ranking = null
                    val total = response.totalAmount ?: -1L
                    val completed = storePayments.completePayment(response.orderId, total)
                    val completion = completed.completion()
                    val done = doneFor(response.orderId)
                    if (done.isPresent) {
                        if (done.get().paymentKey == response.paymentKey) {
                            return@execute Applied.ACCEPTED
                        }
                        status.setRollbackOnly()
                        return@execute Applied.ORDER_ALREADY_PAID
                    }
                    if (completion != StorePaymentEvents.Completion.APPLIED) {
                        status.setRollbackOnly()
                        return@execute if (completion == StorePaymentEvents.Completion.ALREADY_PAID) {
                            Applied.ORDER_ALREADY_PAID
                        } else {
                            Applied.ORDER_NOT_PAYABLE
                        }
                    }
                    val payment = Payment.builder()
                        .paymentKey(response.paymentKey)
                        .orderId(response.orderId)
                        .userId(userId)
                        .amount(response.totalAmount)
                        .method(response.method)
                        .requestedAt(response.requestedAt?.toLocalDateTime())
                        .build()
                    payment.approve(response.approvedAt?.toLocalDateTime())
                    paymentRepository.saveAndFlush(payment)
                    ranking = completed.afterCommit()
                    Applied.ACCEPTED
                }
            }
        } catch (failure: RuntimeException) {
            val reason = if (failure is CannotCreateTransactionException) "WRITER_UNAVAILABLE" else "PERSIST_FAILED"
            return compensate(userId, response, reason, failure)
        }
        if (applied == Applied.ACCEPTED) {
            // Committed and the writer connection is released: Redis latency cannot hold it any more.
            runAfterCommit(ranking)
            return response
        }
        // execute() is declared nullable; the callback never returns null, so this matches the Java dereference.
        return compensate(userId, response, applied!!.name, null)
    }

    /** D. Cancel at the provider unless that payment key is already recorded DONE. */
    private fun compensate(
        userId: Long?,
        response: TossPaymentResponse,
        reason: String,
        cause: RuntimeException?,
    ): TossPaymentResponse {
        val paymentKey = response.paymentKey
        val recorded = try {
            withConnectionRetry {
                reads.execute {
                    if (paymentKey == null) {
                        Optional.empty()
                    } else {
                        paymentRepository.findByPaymentKey(paymentKey).filter { it.status == PaymentStatus.DONE }
                    }
                }
            }
        } catch (unavailable: RuntimeException) {
            // Without a verified absence the payment may already be committed; never cancel blindly.
            record(
                paymentKey,
                response.orderId,
                response.totalAmount,
                userId,
                PaymentCompensationRecord.Stage.UNKNOWN_RESULT,
                reason + "_UNVERIFIED",
            )
            throw RuntimeException("Payment processed but its record could not be verified.", cause)
        }
        if (recorded != null && recorded.isPresent) {
            return stored(recorded.get())
        }
        if (!cancelPayment(paymentKey, "System Error during saving payment record")) {
            record(
                paymentKey,
                response.orderId,
                response.totalAmount,
                userId,
                PaymentCompensationRecord.Stage.CANCEL_FAILED,
                reason,
            )
            throw RuntimeException("Payment processed but failed to save record. Payment cancellation failed.", cause)
        }
        throw RuntimeException("Payment processed but failed to save record. Payment Cancelled.", cause)
    }

    /** Clear provider rejection: cancel a still PENDING order and restore stock, then keep the original ABORTED body. */
    private fun reject(request: TossPaymentConfirmRequest): TossPaymentResponse {
        val paid = withConnectionRetry {
            writes.execute {
                if (storePayments.failPayment(request.orderId)) Optional.empty() else doneFor(request.orderId)
            }
        }
        // A concurrent request may already have paid this order; report that payment instead of ABORTED.
        if (paid != null && paid.isPresent) {
            return stored(paid.get())
        }
        return TossPaymentResponse.builder()
            .status("ABORTED")
            .orderId(request.orderId)
            .paymentKey(request.paymentKey)
            .build()
    }

    private fun lookup(paymentKey: String?): TossPaymentResponse? {
        if (paymentKey == null || paymentKey.isBlank()) {
            return null
        }
        return try {
            tossPaymentsClient.getPayment(authorization(), paymentKey)
        } catch (unavailable: RuntimeException) {
            null
        }
    }

    private fun doneFor(orderId: String?): Optional<Payment> {
        if (orderId == null) {
            return Optional.empty()
        }
        return paymentRepository.findFirstByOrderIdAndStatusOrderByIdAsc(orderId, PaymentStatus.DONE)
    }

    private fun <T> withConnectionRetry(work: () -> T): T {
        var attempt = 1
        while (true) {
            try {
                return work()
            } catch (unavailable: CannotCreateTransactionException) {
                if (attempt >= CONNECTION_ATTEMPTS) {
                    throw unavailable
                }
                try {
                    pause.pause(100L * attempt)
                } catch (interrupted: InterruptedException) {
                    Thread.currentThread().interrupt()
                    throw unavailable
                }
            }
            attempt++
        }
    }

    private fun record(
        paymentKey: String?,
        orderId: String?,
        amount: Long?,
        userId: Long?,
        stage: PaymentCompensationRecord.Stage,
        reason: String,
    ) {
        val key = safe(paymentKey)
        val order = safe(orderId)
        var recordId = "none"
        try {
            val id = withConnectionRetry {
                writes.execute {
                    val record = PaymentCompensationRecord(key, order, userId, amount, stage, reason, LocalDateTime.now(clock))
                    compensations.saveAndFlush(record).id
                }
            }
            if (id != null) {
                recordId = id.toString()
            }
        } catch (ignored: RuntimeException) {
            // The structured log line below remains the durable signal when the record cannot be stored.
        }
        LOG.error(
            "$COMPENSATION_EVENT paymentKey=$key orderId=$order amount=$amount userId=$userId " +
                "stage=$stage reason=$reason recordId=$recordId",
        )
    }

    private fun authorization(): String {
        val encodedKey = Base64.getEncoder().encodeToString("$tossSecretKey:".toByteArray(Charsets.UTF_8))
        return "Basic $encodedKey"
    }

    private fun cancelPayment(paymentKey: String?, reason: String): Boolean {
        if (paymentKey == null) {
            return false
        }
        return try {
            val request = TossPaymentCancelRequest.builder()
                .cancelReason(reason)
                .build()
            tossPaymentsClient.cancelPayment(authorization(), paymentKey, request)
            true
        } catch (e: RuntimeException) {
            false
        }
    }

    /** How a failed confirm call is handled; only REJECTED may cancel the order. */
    internal enum class ProviderFailure { ALREADY_PROCESSED, NOT_PROCESSED, REJECTED, UNKNOWN }

    private enum class Applied { ACCEPTED, ORDER_ALREADY_PAID, ORDER_NOT_PAYABLE }

    companion object {
        internal const val COMPENSATION_EVENT = "PAYMENT_COMPENSATION_REQUIRED"
        internal const val CONNECTION_ATTEMPTS = 3
        private val LOG = LogFactory.getLog(JpaPayments::class.java)
        private val RESPONSE_OFFSET = ZoneOffset.of("+09:00")

        // java.util.Set.of keeps the original NullPointerException when a looked-up payment has no status.
        private val LOOKUP_FAILURES: Set<String?> = java.util.Set.of("ABORTED", "EXPIRED", "CANCELED")
        private val ERROR_CODE = Regex("\"code\"\\s*:\\s*\"([A-Za-z0-9_]+)\"")

        /**
         * Customer/payment rejections from the Toss confirm API (docs.tosspayments.com/reference/error-codes, checked
         * 2026-10-22): the customer's card, account or limit declined the payment, so no money moved. Prefixes
         * REJECT_, INVALID_CARD_ and EXCEED_ plus these exact codes.
         */
        internal val REJECTION_CODES = setOf(
            "INVALID_REJECT_CARD",
            "INVALID_STOPPED_CARD",
            "BELOW_MINIMUM_AMOUNT",
            "NOT_ALLOWED_POINT_USE",
            "NOT_SUPPORTED_INSTALLMENT_PLAN_CARD_OR_MERCHANT",
            "NOT_SUPPORTED_MONTHLY_INSTALLMENT_PLAN",
            "RESTRICTED_TRANSFER_ACCOUNT",
            "INVALID_ACCOUNT_INFO_RE_REGISTER",
            "INVALID_PASSWORD",
            "FDS_ERROR",
            "NOT_AVAILABLE_BANK",
            "NOT_AVAILABLE_PAYMENT",
            "NOT_FOUND_PAYMENT_SESSION",
        )
        private val REJECTION_PREFIXES = listOf("REJECT_", "INVALID_CARD_", "EXCEED_")

        /** Our request was refused before any payment attempt: credentials, merchant configuration or malformed request. */
        internal val NOT_PROCESSED_CODES = setOf(
            "INVALID_API_KEY",
            "UNAUTHORIZED_KEY",
            "FORBIDDEN_REQUEST",
            "INCORRECT_BASIC_AUTH_FORMAT",
            "INVALID_AUTHORIZE_AUTH",
            "NOT_FOUND_TERMINAL_ID",
            "INVALID_UNREGISTERED_SUBMALL",
            "NOT_REGISTERED_BUSINESS",
        )

        private fun runAfterCommit(work: Runnable?) {
            if (work == null) {
                return
            }
            try {
                work.run()
            } catch (ignored: RuntimeException) {
                // Ranking is best effort and must never fail or refund a committed payment.
                LOG.warn("Payment ranking update failed after commit: " + ignored.javaClass.simpleName)
            }
        }

        /**
         * Client-supplied identifiers are reduced to a bounded single token before storage and logging.
         * Public only because Kotlin has no package-private visibility for the same-package Java test.
         */
        @JvmStatic
        fun safe(value: String?): String? {
            if (value == null) {
                return null
            }
            val cleaned = value.replace(Regex("[^A-Za-z0-9_\\-]"), "_")
            return if (cleaned.length > 200) cleaned.substring(0, 200) else cleaned
        }

        /**
         * Allow-list classification. S008/ALREADY_PROCESSED_PAYMENT: already processed. 401, 429 and the codes above: not
         * processed. Listed customer rejections: rejected. Everything else (ALREADY_PROCESSING_REQUEST, PROVIDER_ERROR,
         * CARD_PROCESSING_ERROR, unknown or missing codes, 5xx, timeouts, connection errors): unknown, resolved by lookup.
         */
        internal fun classify(failure: RuntimeException): ProviderFailure {
            val code = if (failure is RestClientResponseException) errorCode(failure.responseBodyAsString) else null
            if (code == "ALREADY_PROCESSED_PAYMENT" || code == "S008" || mentionsProcessed(failure)) {
                return ProviderFailure.ALREADY_PROCESSED
            }
            if (failure !is RestClientResponseException || !failure.statusCode.is4xxClientError) {
                return ProviderFailure.UNKNOWN
            }
            val status = failure.statusCode.value()
            if (status == 401 || status == 429 || (code != null && code in NOT_PROCESSED_CODES)) {
                return ProviderFailure.NOT_PROCESSED
            }
            if (code != null && (code in REJECTION_CODES || REJECTION_PREFIXES.any { code.startsWith(it) })) {
                return ProviderFailure.REJECTED
            }
            return ProviderFailure.UNKNOWN
        }

        /** The original Feign-era check on the exception text, kept for non-JSON error bodies. */
        private fun mentionsProcessed(failure: RuntimeException): Boolean {
            var text: String? = failure.message
            if (failure is RestClientResponseException) {
                text = text + " " + failure.responseBodyAsString
            }
            return text != null && (text.contains("S008") || text.contains("ALREADY_PROCESSED_PAYMENT"))
        }

        private fun errorCode(body: String?): String? {
            if (body == null) {
                return null
            }
            return ERROR_CODE.find(body)?.groupValues?.get(1)
        }

        private fun stored(payment: Payment): TossPaymentResponse =
            TossPaymentResponse.builder()
                .paymentKey(payment.paymentKey)
                .orderId(payment.orderId)
                .totalAmount(payment.amount)
                .status("DONE")
                .requestedAt(payment.requestedAt?.atOffset(RESPONSE_OFFSET))
                .approvedAt(payment.approvedAt?.atOffset(RESPONSE_OFFSET))
                .build()
    }
}
