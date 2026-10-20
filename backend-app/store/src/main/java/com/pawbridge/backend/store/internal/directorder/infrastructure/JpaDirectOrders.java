package com.pawbridge.backend.store.internal.directorder.infrastructure;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.pawbridge.backend.store.api.StoreOrderActors;
import com.pawbridge.backend.store.internal.catalog.infrastructure.KoreanSearchTerms;
import com.pawbridge.backend.store.internal.directorder.application.DirectOrders;
import com.pawbridge.backend.store.internal.directorder.application.OrderActorRejectedException;
import com.pawbridge.backend.store.internal.directorder.infrastructure.jpa.projection.OrderResponse;
import com.pawbridge.backend.store.internal.directorder.infrastructure.jpa.repository.OrderRepository;
import com.pawbridge.backend.store.internal.directorder.infrastructure.jpa.repository.OutboxRepository;
import com.pawbridge.backend.store.internal.directorder.infrastructure.jpa.repository.ProductRepository;
import com.pawbridge.backend.store.internal.directorder.infrastructure.jpa.repository.ProductSKURepository;
import com.pawbridge.backend.store.internal.memberread.infrastructure.jpa.model.Order;
import com.pawbridge.backend.store.internal.memberread.infrastructure.jpa.model.OrderItem;
import jakarta.persistence.EntityManager;
import java.time.Clock;
import java.util.Map;
import java.util.UUID;
import org.springframework.data.jpa.repository.support.JpaRepositoryFactory;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcOperations;
import org.springframework.transaction.support.TransactionOperations;
import org.springframework.transaction.support.TransactionSynchronizationManager;

public final class JpaDirectOrders implements DirectOrders {
    private final OrderRepository writerOrders;
    private final OrderRepository readerOrders;
    private final ProductSKURepository skus;
    private final JpaStockAdjustments stocks;
    private final StoreOrderActors actors;
    private final TransactionOperations writes;
    private final TransactionOperations reads;

    public JpaDirectOrders(EntityManager sharedWriter,EntityManager sharedReader,TransactionOperations writes,
            TransactionOperations reads,StoreOrderActors actors,NamedParameterJdbcOperations writerSearchJdbc,
            KoreanSearchTerms terms,ObjectMapper mapper) {
        var writer=new JpaRepositoryFactory(sharedWriter);
        this.writerOrders=writer.getRepository(OrderRepository.class);
        this.readerOrders=new JpaRepositoryFactory(sharedReader).getRepository(OrderRepository.class);
        this.skus=writer.getRepository(ProductSKURepository.class);
        var snapshots=new ProductSnapshots(writer.getRepository(OutboxRepository.class),mapper,
                new PostgresqlSearchDocuments(writerSearchJdbc,terms),Clock.systemDefaultZone());
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
    private static void requireActor(long verified,StoreOrderActors.Actor actor) {
        if(actor==null || actor.userId()!=verified || !actor.active() || actor.deleted()) throw new OrderActorRejectedException();
    }
    private static void requireTransaction(boolean readOnly) {
        if(!TransactionSynchronizationManager.isActualTransactionActive() || TransactionSynchronizationManager.isCurrentTransactionReadOnly()!=readOnly)
            throw new IllegalStateException("An active order transaction with the expected read mode is required");
    }
    private static OrderDetails details(OrderResponse source) {
        return new OrderDetails(source.getOrderId(),source.getOrderUuid(),source.getUserId(),source.getTotalAmount(),source.getStatus(),
                source.getDeliveryStatus(),source.getReceiverName(),source.getReceiverPhone(),source.getDeliveryAddress(),source.getDeliveryMessage(),
                source.getCreatedAt(),source.getItems().stream().map(item->new Item(item.getProductName(),item.getSkuCode(),item.getPrice(),item.getQuantity())).toList());
    }
}
