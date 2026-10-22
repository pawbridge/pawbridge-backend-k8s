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
    private static final Set<String> NON_BUSINESS_403_CODES = Set.of("FORBIDDEN_REQUEST", "UNAUTHORIZED_KEY");
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
            // B. Classify the provider failure.
            if (alreadyProcessed(failure)) return previouslyRecorded(orderId, failure);
            if (notProcessed(failure)) {
                // Our request was refused (credentials, permission, rate limit); the customer's payment was not
                // attempted, so the order stays PENDING, stock stays reserved and no money needs compensation.
                throw new RuntimeException("Payment provider did not process the request. The order remains pending.", failure);
            }
            if (clearRejection(failure)) return reject(request);
            response = null;
        }
        if (response == null) {
            TossPaymentResponse looked = lookup(request.getPaymentKey());
            if (looked != null && "DONE".equals(looked.getStatus()) && orderId != null && orderId.equals(looked.getOrderId())) {
                response = looked;
            } else if (looked != null && LOOKUP_FAILURES.contains(looked.getStatus())) {
                return reject(request);
            } else {
                record(request.getPaymentKey(), orderId, amount, userId,
                        PaymentCompensationRecord.Stage.UNKNOWN_RESULT, "PROVIDER_RESULT_UNKNOWN");
                throw new RuntimeException("Payment result is unknown. The order remains pending.");
            }
        }
        if (!"DONE".equals(response.getStatus())) {
            return response;
        }
        // Never apply a provider approval to an order other than the one checked above.
        if (!orderId.equals(response.getOrderId())) return compensate(userId, response, "ORDER_MISMATCH", null);
        return applySuccess(userId, response);
    }

    /** C. One Store writer transaction: lock and re-check the order, then insert the payment. D on any failure. */
    private TossPaymentResponse applySuccess(Long userId, TossPaymentResponse response) {
        Applied applied;
        try {
            applied = withConnectionRetry(() -> writes.execute(status -> {
                long total = response.getTotalAmount() == null ? -1L : response.getTotalAmount();
                StorePaymentEvents.Completion completion = storePayments.completePayment(response.getOrderId(), total);
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
                return Applied.ACCEPTED;
            }));
        } catch (RuntimeException failure) {
            return compensate(userId, response, failure instanceof CannotCreateTransactionException
                    ? "WRITER_UNAVAILABLE" : "PERSIST_FAILED", failure);
        }
        if (applied == Applied.ACCEPTED) return response;
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
        withConnectionRetry(() -> writes.execute(status -> storePayments.failPayment(request.getOrderId())));
        return TossPaymentResponse.builder()
                .status("ABORTED")
                .orderId(request.getOrderId())
                .paymentKey(request.getPaymentKey())
                .build();
    }

    private TossPaymentResponse previouslyRecorded(String orderId, RuntimeException failure) {
        Optional<Payment> found = reads.execute(status -> doneFor(orderId));
        if (found == null || found.isEmpty()) throw failure;
        return stored(found.get());
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
        return cleaned.length() > 100 ? cleaned.substring(0, 100) : cleaned;
    }

    private static boolean alreadyProcessed(RuntimeException failure) {
        return mentions(failure.getMessage())
                || (failure instanceof RestClientResponseException provider && mentions(provider.getResponseBodyAsString()));
    }

    private static boolean mentions(String text) {
        return text != null && (text.contains("S008") || text.contains("ALREADY_PROCESSED_PAYMENT"));
    }

    /** Toss business rejection: 4xx other than 401/429 and other than a non-business 403 (see {@link #notProcessed}). */
    private static boolean clearRejection(RuntimeException failure) {
        return failure instanceof RestClientResponseException provider && provider.getStatusCode().is4xxClientError();
    }

    /**
     * 401 (e.g. UNAUTHORIZED_KEY) and 429 (rate limit) mean Toss did not process our request. Toss also returns 403 for
     * card/account declines (REJECT_*, INVALID_PASSWORD ...), so a 403 is a business rejection only when its error code
     * is present and is not an authorization code (FORBIDDEN_REQUEST, UNAUTHORIZED_KEY).
     */
    static boolean notProcessed(RuntimeException failure) {
        if (!(failure instanceof RestClientResponseException provider)) return false;
        int status = provider.getStatusCode().value();
        if (status == 401 || status == 429) return true;
        if (status != 403) return false;
        String code = errorCode(provider.getResponseBodyAsString());
        return code == null || NON_BUSINESS_403_CODES.contains(code);
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
