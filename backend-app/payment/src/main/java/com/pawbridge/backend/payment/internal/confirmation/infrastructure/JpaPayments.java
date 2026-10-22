package com.pawbridge.backend.payment.internal.confirmation.infrastructure;

import com.pawbridge.backend.payment.internal.confirmation.application.PaymentOrderAccessDeniedException;
import com.pawbridge.backend.payment.internal.confirmation.application.PaymentOrderNotPayableException;
import com.pawbridge.backend.payment.internal.confirmation.application.Payments;
import com.pawbridge.backend.payment.internal.confirmation.application.dto.TossPaymentCancelRequest;
import com.pawbridge.backend.payment.internal.confirmation.application.dto.TossPaymentConfirmRequest;
import com.pawbridge.backend.payment.internal.confirmation.application.dto.TossPaymentResponse;
import com.pawbridge.backend.payment.internal.confirmation.infrastructure.jpa.model.Payment;
import com.pawbridge.backend.payment.internal.confirmation.infrastructure.jpa.model.PaymentCompensationRecord;
import com.pawbridge.backend.payment.internal.confirmation.infrastructure.jpa.model.PaymentStatus;
import com.pawbridge.backend.payment.internal.confirmation.infrastructure.jpa.repository.PaymentCompensationRecordRepository;
import com.pawbridge.backend.payment.internal.confirmation.infrastructure.jpa.repository.PaymentRepository;
import com.pawbridge.backend.payment.internal.confirmation.infrastructure.toss.TossPaymentsClient;
import com.pawbridge.backend.store.api.StorePaymentEvents;
import com.pawbridge.backend.store.api.StorePaymentOrders;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.Base64;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.function.Supplier;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.springframework.transaction.CannotCreateTransactionException;
import org.springframework.transaction.support.TransactionOperations;
import org.springframework.web.client.RestClientResponseException;

/**
 * Confirmation that applies the provider result to the Store order directly.
 * The provider is called without a transaction or writer connection; the payment row and the order change
 * share one Store writer transaction. Provider payments that may need manual action are recorded and logged.
 */
public final class JpaPayments implements Payments {
    static final String COMPENSATION_EVENT = "PAYMENT_COMPENSATION_REQUIRED";
    static final int CONNECTION_ATTEMPTS = 3;
    private static final Log LOG = LogFactory.getLog(JpaPayments.class);
    private static final ZoneOffset RESPONSE_OFFSET = ZoneOffset.of("+09:00");
    private static final Set<String> LOOKUP_FAILURES = Set.of("ABORTED", "EXPIRED", "CANCELED");
    private static final Pattern ERROR_CODE = Pattern.compile("\"code\"\\s*:\\s*\"([A-Za-z0-9_]+)\"");

    /** Back-off between writer connection attempts; replaceable in tests. */
    @FunctionalInterface interface Pause { void pause(long millis) throws InterruptedException; }

    private final TossPaymentsClient tossPaymentsClient;
    private final PaymentRepository paymentRepository;
    private final PaymentCompensationRecordRepository compensations;
    private final StorePaymentOrders storeServiceClient;
    private final StorePaymentEvents storePayments;
    private final String tossSecretKey;
    private final TransactionOperations writes;
    private final TransactionOperations reads;
    private final Clock clock;
    private final Pause pause;

    public JpaPayments(TossPaymentsClient tossPaymentsClient, PaymentRepository paymentRepository,
            PaymentCompensationRecordRepository compensations, StorePaymentOrders storeServiceClient,
            StorePaymentEvents storePayments, String tossSecretKey, TransactionOperations writes,
            TransactionOperations reads, Clock clock) {
        this(tossPaymentsClient, paymentRepository, compensations, storeServiceClient, storePayments, tossSecretKey,
                writes, reads, clock, Thread::sleep);
    }

    JpaPayments(TossPaymentsClient tossPaymentsClient, PaymentRepository paymentRepository,
            PaymentCompensationRecordRepository compensations, StorePaymentOrders storeServiceClient,
            StorePaymentEvents storePayments, String tossSecretKey, TransactionOperations writes,
            TransactionOperations reads, Clock clock, Pause pause) {
        this.tossPaymentsClient = tossPaymentsClient;
        this.paymentRepository = paymentRepository;
        this.compensations = compensations;
        this.storeServiceClient = storeServiceClient;
        this.storePayments = storePayments;
        this.tossSecretKey = tossSecretKey;
        this.writes = writes;
        this.reads = reads;
        this.clock = clock;
        this.pause = pause;
    }

    @Override public TossPaymentResponse confirmPayment(Long userId, TossPaymentConfirmRequest request) {
        String orderId = request.getOrderId();
        Long amount = request.getAmount();
        // A. Outside any transaction: owner first (no stored payment is revealed to another member),
        // then existing DONE re-response, then amount/state checks before the provider.
        StorePaymentOrders.Order order = storeServiceClient.getOrder(orderId);
        if (order.userId() == null || !order.userId().equals(userId)) throw new PaymentOrderAccessDeniedException();
        Optional<Payment> existing = reads.execute(status -> doneFor(orderId));
        if (existing != null && existing.isPresent()) {
            Payment payment = existing.get();
            if (!payment.getAmount().equals(amount)) {
                throw new IllegalStateException("Payment exists but amount mismatch");
            }
            return stored(payment);
        }
        if (order.totalAmount() == null || !order.totalAmount().equals(amount)) {
            throw new IllegalStateException("Payment Amount Mismatch");
        }
        if (!"PENDING".equals(order.status())) throw new PaymentOrderNotPayableException();

        TossPaymentResponse response;
        try {
            response = tossPaymentsClient.confirmPayment(authorization(), request);
        } catch (RuntimeException failure) {
            // B. Classify the provider failure (see ProviderFailure for the allow-list).
            switch (classify(failure)) {
                case ALREADY_PROCESSED -> {
                    Optional<Payment> found = reads.execute(status -> doneFor(orderId));
                    if (found != null && found.isPresent()) return stored(found.get());
                    // Toss says processed but nothing is recorded here: resolve it with one lookup.
                    return resolveUnknown(userId, request, true);
                }
                case NOT_PROCESSED ->
                    // Our request was refused (credentials, merchant configuration, rate limit); the customer's
                    // payment was not attempted, so the order stays PENDING and no money needs compensation.
                    throw new RuntimeException("Payment provider did not process the request. The order remains pending.", failure);
                case REJECTED -> {
                    return reject(request);
                }
                default -> {
                    return resolveUnknown(userId, request, false);
                }
            }
        }
        if (response == null) return resolveUnknown(userId, request, false);
        if (!"DONE".equals(response.getStatus())) {
            return response;
        }
        // Never apply a provider approval to an order other than the one checked above.
        if (!orderId.equals(response.getOrderId())) return compensate(userId, response, "ORDER_MISMATCH", null);
        return applySuccess(userId, response);
    }

    /**
     * Unknown provider result: one GET lookup. DONE for this order is applied; a clear failure for this order follows
     * the rejection path (never after ALREADY_PROCESSED); anything else keeps the order PENDING and is recorded.
     */
    private TossPaymentResponse resolveUnknown(Long userId, TossPaymentConfirmRequest request, boolean alreadyProcessed) {
        String orderId = request.getOrderId();
        TossPaymentResponse looked = lookup(request.getPaymentKey());
        boolean sameOrder = looked != null && orderId != null && orderId.equals(looked.getOrderId());
        if (sameOrder && "DONE".equals(looked.getStatus())) return applySuccess(userId, looked);
        if (sameOrder && !alreadyProcessed && LOOKUP_FAILURES.contains(looked.getStatus())) return reject(request);
        record(request.getPaymentKey(), orderId, request.getAmount(), userId, PaymentCompensationRecord.Stage.UNKNOWN_RESULT,
                alreadyProcessed ? "ALREADY_PROCESSED_UNRECORDED" : "PROVIDER_RESULT_UNKNOWN");
        throw new RuntimeException("Payment result is unknown. The order remains pending.");
    }

    /** C. One Store writer transaction: lock and re-check the order, then insert the payment. D on any failure. */
    private TossPaymentResponse applySuccess(Long userId, TossPaymentResponse response) {
        Applied applied;
        Runnable[] ranking = {null};
        try {
            applied = withConnectionRetry(() -> writes.execute(status -> {
                ranking[0] = null;
                long total = response.getTotalAmount() == null ? -1L : response.getTotalAmount();
                StorePaymentEvents.Completed completed = storePayments.completePayment(response.getOrderId(), total);
                StorePaymentEvents.Completion completion = completed.completion();
                Optional<Payment> done = doneFor(response.getOrderId());
                if (done.isPresent()) {
                    if (done.get().getPaymentKey().equals(response.getPaymentKey())) return Applied.ACCEPTED;
                    status.setRollbackOnly();
                    return Applied.ORDER_ALREADY_PAID;
                }
                if (completion != StorePaymentEvents.Completion.APPLIED) {
                    status.setRollbackOnly();
                    return completion == StorePaymentEvents.Completion.ALREADY_PAID ? Applied.ORDER_ALREADY_PAID : Applied.ORDER_NOT_PAYABLE;
                }
                Payment payment = Payment.builder()
                        .paymentKey(response.getPaymentKey())
                        .orderId(response.getOrderId())
                        .userId(userId)
                        .amount(response.getTotalAmount())
                        .method(response.getMethod())
                        .requestedAt(response.getRequestedAt() == null ? null : response.getRequestedAt().toLocalDateTime())
                        .build();
                payment.approve(response.getApprovedAt() == null ? null : response.getApprovedAt().toLocalDateTime());
                paymentRepository.saveAndFlush(payment);
                ranking[0] = completed.afterCommit();
                return Applied.ACCEPTED;
            }));
        } catch (RuntimeException failure) {
            return compensate(userId, response, failure instanceof CannotCreateTransactionException
                    ? "WRITER_UNAVAILABLE" : "PERSIST_FAILED", failure);
        }
        if (applied == Applied.ACCEPTED) {
            // Committed and the writer connection is released: Redis latency cannot hold it any more.
            runAfterCommit(ranking[0]);
            return response;
        }
        return compensate(userId, response, applied.name(), null);
    }

    /** D. Cancel at the provider unless that payment key is already recorded DONE. */
    private TossPaymentResponse compensate(Long userId, TossPaymentResponse response, String reason, RuntimeException cause) {
        String paymentKey = response.getPaymentKey();
        Optional<Payment> recorded;
        try {
            recorded = withConnectionRetry(() -> reads.execute(status -> paymentKey == null ? Optional.<Payment>empty()
                    : paymentRepository.findByPaymentKey(paymentKey).filter(payment -> payment.getStatus() == PaymentStatus.DONE)));
        } catch (RuntimeException unavailable) {
            // Without a verified absence the payment may already be committed; never cancel blindly.
            record(paymentKey, response.getOrderId(), response.getTotalAmount(), userId,
                    PaymentCompensationRecord.Stage.UNKNOWN_RESULT, reason + "_UNVERIFIED");
            throw new RuntimeException("Payment processed but its record could not be verified.", cause);
        }
        if (recorded != null && recorded.isPresent()) return stored(recorded.get());
        if (!cancelPayment(paymentKey, "System Error during saving payment record")) {
            record(paymentKey, response.getOrderId(), response.getTotalAmount(), userId,
                    PaymentCompensationRecord.Stage.CANCEL_FAILED, reason);
            throw new RuntimeException("Payment processed but failed to save record. Payment cancellation failed.", cause);
        }
        throw new RuntimeException("Payment processed but failed to save record. Payment Cancelled.", cause);
    }

    /** Clear provider rejection: cancel a still PENDING order and restore stock, then keep the original ABORTED body. */
    private TossPaymentResponse reject(TossPaymentConfirmRequest request) {
        Optional<Payment> paid = withConnectionRetry(() -> writes.execute(status ->
                storePayments.failPayment(request.getOrderId()) ? Optional.<Payment>empty() : doneFor(request.getOrderId())));
        // A concurrent request may already have paid this order; report that payment instead of ABORTED.
        if (paid != null && paid.isPresent()) return stored(paid.get());
        return TossPaymentResponse.builder()
                .status("ABORTED")
                .orderId(request.getOrderId())
                .paymentKey(request.getPaymentKey())
                .build();
    }

    private static void runAfterCommit(Runnable work) {
        if (work == null) return;
        try {
            work.run();
        } catch (RuntimeException ignored) {
            // Ranking is best effort and must never fail or refund a committed payment.
            LOG.warn("Payment ranking update failed after commit: " + ignored.getClass().getSimpleName());
        }
    }

    private TossPaymentResponse lookup(String paymentKey) {
        if (paymentKey == null || paymentKey.isBlank()) return null;
        try {
            return tossPaymentsClient.getPayment(authorization(), paymentKey);
        } catch (RuntimeException unavailable) {
            return null;
        }
    }

    private Optional<Payment> doneFor(String orderId) {
        if (orderId == null) return Optional.empty();
        return paymentRepository.findFirstByOrderIdAndStatusOrderByIdAsc(orderId, PaymentStatus.DONE);
    }

    private <T> T withConnectionRetry(Supplier<T> work) {
        for (int attempt = 1; ; attempt++) {
            try {
                return work.get();
            } catch (CannotCreateTransactionException unavailable) {
                if (attempt >= CONNECTION_ATTEMPTS) throw unavailable;
                try {
                    pause.pause(100L * attempt);
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    throw unavailable;
                }
            }
        }
    }

    private void record(String paymentKey, String orderId, Long amount, Long userId,
            PaymentCompensationRecord.Stage stage, String reason) {
        String key = safe(paymentKey), order = safe(orderId);
        String recordId = "none";
        try {
            Long id = withConnectionRetry(() -> writes.execute(status -> compensations.saveAndFlush(new PaymentCompensationRecord(
                    key, order, userId, amount, stage, reason, LocalDateTime.now(clock))).getId()));
            if (id != null) recordId = id.toString();
        } catch (RuntimeException ignored) {
            // The structured log line below remains the durable signal when the record cannot be stored.
        }
        LOG.error(COMPENSATION_EVENT + " paymentKey=" + key + " orderId=" + order + " amount=" + amount
                + " userId=" + userId + " stage=" + stage + " reason=" + reason + " recordId=" + recordId);
    }

    /** Client-supplied identifiers are reduced to a bounded single token before storage and logging. */
    static String safe(String value) {
        if (value == null) return null;
        String cleaned = value.replaceAll("[^A-Za-z0-9_\\-]", "_");
        return cleaned.length() > 200 ? cleaned.substring(0, 200) : cleaned;
    }

    /** How a failed confirm call is handled; only REJECTED may cancel the order. */
    enum ProviderFailure { ALREADY_PROCESSED, NOT_PROCESSED, REJECTED, UNKNOWN }

    /**
     * Customer/payment rejections from the Toss confirm API (docs.tosspayments.com/reference/error-codes, checked
     * 2026-10-22): the customer's card, account or limit declined the payment, so no money moved. Prefixes
     * REJECT_, INVALID_CARD_ and EXCEED_ plus these exact codes.
     */
    static final Set<String> REJECTION_CODES = Set.of("INVALID_REJECT_CARD", "INVALID_STOPPED_CARD", "BELOW_MINIMUM_AMOUNT",
            "NOT_ALLOWED_POINT_USE", "NOT_SUPPORTED_INSTALLMENT_PLAN_CARD_OR_MERCHANT", "NOT_SUPPORTED_MONTHLY_INSTALLMENT_PLAN",
            "RESTRICTED_TRANSFER_ACCOUNT", "INVALID_ACCOUNT_INFO_RE_REGISTER", "INVALID_PASSWORD", "FDS_ERROR",
            "NOT_AVAILABLE_BANK", "NOT_AVAILABLE_PAYMENT", "NOT_FOUND_PAYMENT_SESSION");
    private static final List<String> REJECTION_PREFIXES = List.of("REJECT_", "INVALID_CARD_", "EXCEED_");
    /** Our request was refused before any payment attempt: credentials, merchant configuration or malformed request. */
    static final Set<String> NOT_PROCESSED_CODES = Set.of("INVALID_API_KEY", "UNAUTHORIZED_KEY", "FORBIDDEN_REQUEST",
            "INCORRECT_BASIC_AUTH_FORMAT", "INVALID_AUTHORIZE_AUTH", "NOT_FOUND_TERMINAL_ID", "INVALID_UNREGISTERED_SUBMALL",
            "NOT_REGISTERED_BUSINESS", "INVALID_REQUEST");

    /**
     * Allow-list classification. S008/ALREADY_PROCESSED_PAYMENT: already processed. 401, 429 and the codes above: not
     * processed. Listed customer rejections: rejected. Everything else (ALREADY_PROCESSING_REQUEST, PROVIDER_ERROR,
     * CARD_PROCESSING_ERROR, unknown or missing codes, 5xx, timeouts, connection errors): unknown, resolved by lookup.
     */
    static ProviderFailure classify(RuntimeException failure) {
        String code = failure instanceof RestClientResponseException provider ? errorCode(provider.getResponseBodyAsString()) : null;
        if ("ALREADY_PROCESSED_PAYMENT".equals(code) || "S008".equals(code) || mentionsProcessed(failure)) {
            return ProviderFailure.ALREADY_PROCESSED;
        }
        if (!(failure instanceof RestClientResponseException provider) || !provider.getStatusCode().is4xxClientError()) {
            return ProviderFailure.UNKNOWN;
        }
        int status = provider.getStatusCode().value();
        if (status == 401 || status == 429 || (code != null && NOT_PROCESSED_CODES.contains(code))) return ProviderFailure.NOT_PROCESSED;
        if (code != null && (REJECTION_CODES.contains(code) || REJECTION_PREFIXES.stream().anyMatch(code::startsWith))) {
            return ProviderFailure.REJECTED;
        }
        return ProviderFailure.UNKNOWN;
    }

    /** The original Feign-era check on the exception text, kept for non-JSON error bodies. */
    private static boolean mentionsProcessed(RuntimeException failure) {
        String text = failure.getMessage();
        if (failure instanceof RestClientResponseException provider) text = text + " " + provider.getResponseBodyAsString();
        return text != null && (text.contains("S008") || text.contains("ALREADY_PROCESSED_PAYMENT"));
    }

    private static String errorCode(String body) {
        if (body == null) return null;
        Matcher matcher = ERROR_CODE.matcher(body);
        return matcher.find() ? matcher.group(1) : null;
    }

    private String authorization() {
        String encodedKey = Base64.getEncoder().encodeToString((tossSecretKey + ":").getBytes(StandardCharsets.UTF_8));
        return "Basic " + encodedKey;
    }

    private static TossPaymentResponse stored(Payment payment) {
        return TossPaymentResponse.builder()
                .paymentKey(payment.getPaymentKey())
                .orderId(payment.getOrderId())
                .totalAmount(payment.getAmount())
                .status("DONE")
                .requestedAt(payment.getRequestedAt() == null ? null : payment.getRequestedAt().atOffset(RESPONSE_OFFSET))
                .approvedAt(payment.getApprovedAt() == null ? null : payment.getApprovedAt().atOffset(RESPONSE_OFFSET))
                .build();
    }

    private boolean cancelPayment(String paymentKey, String reason) {
        if (paymentKey == null) return false;
        try {
            TossPaymentCancelRequest request = TossPaymentCancelRequest.builder()
                    .cancelReason(reason)
                    .build();
            tossPaymentsClient.cancelPayment(authorization(), paymentKey, request);
            return true;
        } catch (RuntimeException e) {
            return false;
        }
    }

    private enum Applied { ACCEPTED, ORDER_ALREADY_PAID, ORDER_NOT_PAYABLE }
}
