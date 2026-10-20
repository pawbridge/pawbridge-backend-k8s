package com.pawbridge.backend.store.internal.orderadministration.infrastructure;

import com.pawbridge.backend.store.api.StoreOrderAdministrators;
import com.pawbridge.backend.store.internal.directorder.application.DirectOrders;
import com.pawbridge.backend.store.internal.directorder.infrastructure.jpa.projection.OrderResponse;
import com.pawbridge.backend.store.internal.directorder.infrastructure.jpa.repository.OrderRepository;
import com.pawbridge.backend.store.internal.memberread.infrastructure.jpa.model.Order;
import com.pawbridge.backend.store.internal.memberread.infrastructure.jpa.model.OrderStatus;
import com.pawbridge.backend.store.internal.memberread.infrastructure.jpa.model.DeliveryStatus;
import com.pawbridge.backend.store.internal.orderadministration.application.AdminOrders;
import com.pawbridge.backend.store.internal.orderadministration.application.AdminOrderNotFoundException;
import com.pawbridge.backend.store.internal.orderadministration.application.AdministratorRejectedException;
import jakarta.persistence.EntityManager;
import jakarta.persistence.LockModeType;
import java.util.Set;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.data.jpa.repository.support.JpaRepositoryFactory;
import org.springframework.transaction.support.TransactionOperations;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/** Root supplies one writer EM/JpaTM; only deliveryStatus is changed, never Order payment state. */
public final class JpaAdminOrders implements AdminOrders {
    private static final Set<String> SORTS=Set.of("id","userId","orderUuid","totalAmount","status","deliveryAddress","deliveryStatus",
            "receiverName","receiverPhone","deliveryMessage","createdAt","updatedAt");
    private final EntityManager em;
    private final OrderRepository orders;
    private final TransactionOperations writes,reads;
    private final StoreOrderAdministrators administrators;
    public JpaAdminOrders(EntityManager sharedWriter,TransactionOperations writes,TransactionOperations reads,StoreOrderAdministrators administrators) {
        this(sharedWriter,new JpaRepositoryFactory(sharedWriter).getRepository(OrderRepository.class),writes,reads,administrators);
    }
    JpaAdminOrders(EntityManager em,OrderRepository orders,TransactionOperations writes,TransactionOperations reads,StoreOrderAdministrators administrators) {
        this.em=em;this.orders=orders;this.writes=writes;this.reads=reads;this.administrators=administrators;
    }
    @Override public Page list(long user,Query query) {
        if(query.page()<0 || query.size()<1 || query.size()>100 || query.sortBy()==null || !SORTS.contains(query.sortBy()))
            throw new IllegalArgumentException("Admin order query is not supported");
        OrderStatus status=query.status()==null?null:OrderStatus.valueOf(query.status());
        DeliveryStatus delivery=query.deliveryStatus()==null?null:DeliveryStatus.valueOf(query.deliveryStatus());
        boolean ascending="asc".equalsIgnoreCase(query.sortOrder());
        var pageable=PageRequest.of(query.page(),query.size(),Sort.by(ascending?Sort.Direction.ASC:Sort.Direction.DESC,query.sortBy()));
        return reads.execute(tx->{
            transaction(true);actor(user,administrators.currentForRead(user));
            Specification<Order> spec=Specification.where(null);
            if(status!=null)spec=spec.and((root,q,cb)->cb.equal(root.get("status"),status));
            if(delivery!=null)spec=spec.and((root,q,cb)->cb.equal(root.get("deliveryStatus"),delivery));
            if(query.userId()!=null)spec=spec.and((root,q,cb)->cb.equal(root.get("userId"),query.userId()));
            if(query.keyword()!=null && !query.keyword().isBlank()) {
                String pattern="%"+query.keyword().toLowerCase()+"%";
                spec=spec.and((root,q,cb)->cb.or(cb.like(cb.lower(root.get("orderUuid")),pattern),cb.like(cb.lower(root.get("receiverName")),pattern)));
            }
            var page=orders.findAll(spec,pageable).map(JpaAdminOrders::details);
            return new Page(page.getContent(),page.getNumber(),page.getSize(),page.getTotalElements(),query.sortBy(),ascending);
        });
    }
    @Override public DirectOrders.OrderDetails get(long user,long orderId) {
        return reads.execute(tx->{transaction(true);actor(user,administrators.currentForRead(user));
            return details(orders.findById(orderId).orElseThrow(AdminOrderNotFoundException::new));});
    }
    @Override public DirectOrders.OrderDetails updateDelivery(long user,long orderId,String deliveryStatus) {
        DeliveryStatus delivery=deliveryStatus==null?null:DeliveryStatus.valueOf(deliveryStatus);
        return writes.execute(tx->{
            transaction(false);actor(user,administrators.currentForWrite(user));
            Order order=em.find(Order.class,orderId,LockModeType.PESSIMISTIC_WRITE);
            if(order==null)throw new IllegalArgumentException("Order not found");
            if(delivery==null)throw new IllegalStateException("Delivery status cannot be stored");
            order.updateDeliveryStatus(delivery);
            em.flush();
            return details(order);
        });
    }
    private static void actor(long user,StoreOrderAdministrators.Actor actor) {
        if(actor==null || actor.userId()!=user || !actor.active() || actor.deleted() || !"ROLE_ADMIN".equals(actor.role()))throw new AdministratorRejectedException();
    }
    private static void transaction(boolean readOnly) {
        if(!TransactionSynchronizationManager.isActualTransactionActive() || TransactionSynchronizationManager.isCurrentTransactionReadOnly()!=readOnly)
            throw new IllegalStateException("An active admin order transaction with the expected read mode is required");
    }
    private static DirectOrders.OrderDetails details(Order entity) {
        var source=OrderResponse.from(entity);
        return new DirectOrders.OrderDetails(source.getOrderId(),source.getOrderUuid(),source.getUserId(),source.getTotalAmount(),source.getStatus(),source.getDeliveryStatus(),
                source.getReceiverName(),source.getReceiverPhone(),source.getDeliveryAddress(),source.getDeliveryMessage(),source.getCreatedAt(),source.getItems().stream()
                .map(item->new DirectOrders.Item(item.getProductName(),item.getSkuCode(),item.getPrice(),item.getQuantity())).toList());
    }
}
