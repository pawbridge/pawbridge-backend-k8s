package com.pawbridge.backend.store.internal.payment.infrastructure;

import com.pawbridge.backend.store.api.StorePaymentEvents;
import com.pawbridge.backend.store.internal.directorder.infrastructure.jpa.repository.OrderRepository;
import com.pawbridge.backend.store.internal.memberread.infrastructure.jpa.model.Order;
import com.pawbridge.backend.store.internal.memberread.infrastructure.jpa.model.OrderItem;
import com.pawbridge.backend.store.internal.memberread.infrastructure.jpa.model.OrderStatus;
import jakarta.persistence.EntityManager;
import jakarta.persistence.LockModeType;
import java.time.Clock;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;
import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.springframework.data.jpa.repository.support.JpaRepositoryFactory;
import org.springframework.transaction.support.TransactionOperations;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/** Store side of the direct payment call. No pool, EMF, message transport or Redis client is owned here. */
public final class JpaStorePaymentEvents implements StorePaymentEvents {
    private static final Log LOG = LogFactory.getLog(JpaStorePaymentEvents.class);

    /** Loads one order by UUID with a PostgreSQL row lock (SELECT ... FOR UPDATE) in the caller's transaction. */
    @FunctionalInterface interface OrderLocks { Optional<Order> lockByUuid(String orderUuid); }

    private final OrderRepository orders;
    private final OrderLocks locks;
    private final TransactionOperations writes;
    private final TransactionOperations mandatoryWrites;
    private final StockRestorer stocks;
    private final Ranking ranking;
    private final Clock clock;

    public JpaStorePaymentEvents(EntityManager sharedWriter, TransactionOperations writes, TransactionOperations mandatoryWrites,
            StockRestorer stocks, Ranking ranking, Clock clock) {
        this(new JpaRepositoryFactory(sharedWriter).getRepository(OrderRepository.class), uuid -> {
            List<Order> found = sharedWriter.createQuery("select o from Order o where o.orderUuid = :uuid", Order.class)
                    .setParameter("uuid", uuid).setLockMode(LockModeType.PESSIMISTIC_WRITE).getResultList();
            if (found.size() > 1) throw new IllegalStateException("Duplicate order UUID");
            return found.stream().findFirst();
        }, writes, mandatoryWrites, stocks, ranking, clock);
    }

    JpaStorePaymentEvents(OrderRepository orders, OrderLocks locks, TransactionOperations writes,
            TransactionOperations mandatoryWrites, StockRestorer stocks, Ranking ranking, Clock clock) {
        this.orders=orders; this.locks=locks; this.writes=writes; this.mandatoryWrites=mandatoryWrites;
        this.stocks=stocks; this.ranking=ranking; this.clock=clock;
    }

    private static final Runnable NOTHING = () -> { };

    @Override public Completed completePayment(String orderUuid, long amount) {
        return mandatoryWrites.execute(transaction -> {
            requireWriter();
            Order order=locks.lockByUuid(orderUuid).orElse(null);
            if(order==null) return new Completed(Completion.REJECTED,NOTHING);
            if(order.getStatus()==OrderStatus.PAID) return new Completed(Completion.ALREADY_PAID,NOTHING);
            if(order.getStatus()!=OrderStatus.PENDING || order.getTotalAmount()==null || order.getTotalAmount()!=amount)
                return new Completed(Completion.REJECTED,NOTHING);
            order.paid();
            String key="store:ranking:daily:"+LocalDate.now(clock).format(DateTimeFormatter.BASIC_ISO_DATE);
            List<Rank> ranks=order.getOrderItems().stream().map(item -> new Rank(
                    String.valueOf(item.getProductSKU().getProduct().getId()),item.getQuantity())).toList();
            Long orderId=order.getId();
            // Redis is not touched here: the caller runs this after commit and connection release.
            return new Completed(Completion.APPLIED,() -> incrementAfterCommit(key,ranks,orderId));
        });
    }

    @Override public boolean failPayment(String orderUuid) {
        Boolean cancelled=mandatoryWrites.execute(transaction -> {
            requireWriter();
            Order order=locks.lockByUuid(orderUuid).orElse(null);
            if(order==null || order.getStatus()!=OrderStatus.PENDING) return false;
            order.cancelOrder();
            Map<Long,Integer> quantities=order.getOrderItems().stream().collect(Collectors.toMap(
                    item -> item.getProductSKU().getId(),OrderItem::getQuantity,Integer::sum,LinkedHashMap::new));
            stocks.increaseStocks(quantities);
            return true;
        });
        return Boolean.TRUE.equals(cancelled);
    }

    @Override public void processPayment(long orderId) {
        writes.executeWithoutResult(transaction -> {
            Order order=orders.findById(orderId).orElseThrow(()->new IllegalArgumentException("Order not found: "+orderId));
            if(order.getStatus()!=OrderStatus.PENDING)
                throw new IllegalStateException("Order is not in PENDING state. Current: "+order.getStatus());
            order.paid();
        });
    }

    private void incrementAfterCommit(String key, List<Rank> ranks, Long orderId) {
        for(Rank rank:ranks) {
            try {
                ranking.increment(key,rank.productId(),rank.quantity());
            } catch(RuntimeException failure) {
                // The order is already committed; a ranking miss must not turn a paid order into an error response.
                LOG.warn("Store payment ranking increment failed after commit: orderId="+orderId+" productId="+rank.productId()
                        +" error="+failure.getClass().getSimpleName());
            }
        }
    }

    private static void requireWriter() {
        if(!TransactionSynchronizationManager.isActualTransactionActive() || TransactionSynchronizationManager.isCurrentTransactionReadOnly())
            throw new IllegalStateException("An active Store writer transaction is required");
    }

    private record Rank(String productId, double quantity) { }
}
