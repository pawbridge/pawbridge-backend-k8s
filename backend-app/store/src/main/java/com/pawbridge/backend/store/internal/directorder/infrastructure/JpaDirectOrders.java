package com.pawbridge.backend.store.internal.directorder.infrastructure;

import com.pawbridge.backend.store.api.StoreOrderActors;
import com.pawbridge.backend.store.api.StoreMemberReads;
import com.pawbridge.backend.store.internal.catalog.infrastructure.KoreanSearchTerms;
import com.pawbridge.backend.store.internal.directorder.application.DirectOrders;
import com.pawbridge.backend.store.internal.directorder.application.OrderActorRejectedException;
import com.pawbridge.backend.store.internal.directorder.infrastructure.jpa.projection.OrderResponse;
import com.pawbridge.backend.store.internal.directorder.infrastructure.jpa.repository.OrderRepository;
import com.pawbridge.backend.store.internal.directorder.infrastructure.jpa.repository.ProductRepository;
import com.pawbridge.backend.store.internal.directorder.infrastructure.jpa.repository.ProductSKURepository;
import com.pawbridge.backend.store.internal.memberread.infrastructure.jpa.model.Order;
import com.pawbridge.backend.store.internal.memberread.infrastructure.jpa.model.OrderItem;
import com.pawbridge.backend.store.internal.memberread.infrastructure.jpa.model.OrderStatus;
import jakarta.persistence.EntityManager;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.data.jpa.repository.support.JpaRepositoryFactory;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcOperations;
import org.springframework.transaction.support.TransactionOperations;
import org.springframework.transaction.support.TransactionSynchronizationManager;

public final class JpaDirectOrders implements DirectOrders {
    // Same scalar Order fields already accepted by Store member reads; no relationship paths.
    private static final Set<String> HISTORY_SORTS=Set.of("id","userId","orderUuid","totalAmount","status",
            "deliveryAddress","deliveryStatus","receiverName","receiverPhone","deliveryMessage","createdAt","updatedAt");
    private final OrderRepository writerOrders;
    private final OrderRepository readerOrders;
    private final ProductSKURepository skus;
    private final JpaStockAdjustments stocks;
    private final StoreOrderActors actors;
    private final TransactionOperations writes;
    private final TransactionOperations reads;

    public JpaDirectOrders(EntityManager sharedWriter,EntityManager sharedReader,TransactionOperations writes,
            TransactionOperations reads,StoreOrderActors actors,NamedParameterJdbcOperations writerSearchJdbc,
            KoreanSearchTerms terms) {
        var writer=new JpaRepositoryFactory(sharedWriter);
        this.writerOrders=writer.getRepository(OrderRepository.class);
        this.readerOrders=new JpaRepositoryFactory(sharedReader).getRepository(OrderRepository.class);
        this.skus=writer.getRepository(ProductSKURepository.class);
        var snapshots=new ProductSnapshots(writerSearchJdbc,terms);
        this.stocks=new JpaStockAdjustments(writer.getRepository(ProductRepository.class),skus,snapshots);
        this.actors=actors;this.writes=writes;this.reads=reads;
    }
    JpaDirectOrders(OrderRepository writerOrders,OrderRepository readerOrders,ProductSKURepository skus,JpaStockAdjustments stocks,
            StoreOrderActors actors,TransactionOperations writes,TransactionOperations reads) {
        this.writerOrders=writerOrders;this.readerOrders=readerOrders;this.skus=skus;this.stocks=stocks;
        this.actors=actors;this.writes=writes;this.reads=reads;
    }
    @Override public OrderDetails create(long verifiedUserId,Command command) {
        return writes.execute(status->{
            requireTransaction(false);requireActor(verifiedUserId,actors.currentForWrite(verifiedUserId));
            if(command.skuId()==null || command.quantity()==null || command.skuId()<=0 || command.quantity()<=0)
                throw new IllegalArgumentException("SKU ID and quantity must be positive");
            stocks.decreaseStocks(Map.of(command.skuId(),command.quantity()));
            var sku=skus.findById(command.skuId()).orElseThrow(()->new IllegalArgumentException("SKU not found"));
            long amount;
            try { amount=Math.multiplyExact(sku.getPrice(),command.quantity().longValue()); }
            catch(ArithmeticException overflow) { throw new IllegalArgumentException("Order amount is out of range",overflow); }
            Order order=Order.builder().userId(verifiedUserId).orderUuid(UUID.randomUUID().toString()).totalAmount(amount)
                    .receiverName(command.receiverName()).receiverPhone(command.receiverPhone()).deliveryAddress(command.deliveryAddress())
                    .deliveryMessage(command.deliveryMessage()).build();
            order.getOrderItems().add(OrderItem.builder().order(order).productSKU(sku).productName(sku.getProduct().getName())
                    .skuCode(sku.getSkuCode()).price(sku.getPrice()).quantity(command.quantity()).build());
            writerOrders.save(order);
            return details(OrderResponse.from(order));
        });
    }
    @Override public OrderDetails get(long verifiedUserId,long orderId) {
        return reads.execute(status->{
            requireTransaction(true);requireActor(verifiedUserId,actors.currentForRead(verifiedUserId));
            Order order=readerOrders.findById(orderId).orElseThrow(()->new IllegalArgumentException("Order not found"));
            if(!order.getUserId().equals(verifiedUserId)) throw new IllegalArgumentException("Order cannot be accessed by this member");
            return details(OrderResponse.from(order));
        });
    }
    @Override public StoreMemberReads.Page<OrderDetails> list(long verifiedUserId,String status,StoreMemberReads.Request request) {
        if(request.size()>100 || request.offset()>Integer.MAX_VALUE || request.sort().size()>4
                || request.sort().stream().anyMatch(order->order.property()==null || !HISTORY_SORTS.contains(order.property())))
            throw new IllegalArgumentException("Order history page or sort is not supported");
        OrderStatus selected=status==null?null:OrderStatus.valueOf(status);
        Sort sort=Sort.by(request.sort().stream().map(order->new Sort.Order(
                order.ascending()?Sort.Direction.ASC:Sort.Direction.DESC,order.property(),order.ignoreCase(),Sort.NullHandling.NATIVE)).toList());
        var pageable=PageRequest.of(request.page(),request.size(),sort);
        return reads.execute(tx->{
            requireTransaction(true);requireActor(verifiedUserId,actors.currentForRead(verifiedUserId));
            var page=selected==null?readerOrders.findByUserIdOrderByCreatedAtDesc(verifiedUserId,pageable)
                    :readerOrders.findByUserIdAndStatusOrderByCreatedAtDesc(verifiedUserId,selected,pageable);
            if(page.hasContent()) {
                // Initialize only this owner's page collections in the same persistence context.
                readerOrders.findWithItemsByUserIdAndIdIn(verifiedUserId,page.getContent().stream().map(Order::getId).toList());
            }
            var content=page.getContent().stream().map(OrderResponse::from).map(JpaDirectOrders::details).toList();
            return new StoreMemberReads.Page<>(content,page.getNumber(),page.getSize(),page.getTotalElements(),
                    page.getSort().stream().map(order->new StoreMemberReads.Order(order.getProperty(),order.isAscending(),order.isIgnoreCase())).toList());
        });
    }
    private static void requireActor(long verified,StoreOrderActors.Actor actor) {
        if(actor==null || actor.userId()!=verified || !actor.active() || actor.deleted()) throw new OrderActorRejectedException();
    }
    private static void requireTransaction(boolean readOnly) {
        if(!TransactionSynchronizationManager.isActualTransactionActive() || TransactionSynchronizationManager.isCurrentTransactionReadOnly()!=readOnly)
            throw new IllegalStateException("An active order transaction with the expected read mode is required");
    }
    static OrderDetails details(OrderResponse source) {
        return new OrderDetails(source.getOrderId(),source.getOrderUuid(),source.getUserId(),source.getTotalAmount(),source.getStatus(),
                source.getDeliveryStatus(),source.getReceiverName(),source.getReceiverPhone(),source.getDeliveryAddress(),source.getDeliveryMessage(),
                source.getCreatedAt(),source.getItems().stream().map(item->new Item(item.getProductName(),item.getSkuCode(),item.getPrice(),item.getQuantity())).toList());
    }
}
