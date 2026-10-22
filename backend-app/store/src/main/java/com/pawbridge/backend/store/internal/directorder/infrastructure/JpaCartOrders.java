package com.pawbridge.backend.store.internal.directorder.infrastructure;

import com.pawbridge.backend.store.api.StoreOrderActors;
import com.pawbridge.backend.store.internal.cart.infrastructure.jpa.repository.CartRepository;
import com.pawbridge.backend.store.internal.directorder.application.CartOrders;
import com.pawbridge.backend.store.internal.directorder.application.DirectOrders;
import com.pawbridge.backend.store.internal.directorder.application.OrderActorRejectedException;
import com.pawbridge.backend.store.internal.directorder.infrastructure.jpa.projection.OrderResponse;
import com.pawbridge.backend.store.internal.directorder.infrastructure.jpa.repository.*;
import com.pawbridge.backend.store.internal.memberread.infrastructure.jpa.model.Order;
import com.pawbridge.backend.store.internal.memberread.infrastructure.jpa.model.OrderItem;
import com.pawbridge.backend.store.internal.catalog.infrastructure.KoreanSearchTerms;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceException;
import java.util.LinkedHashMap;
import java.util.UUID;
import java.util.stream.Collectors;
import org.springframework.data.jpa.repository.support.JpaRepositoryFactory;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcOperations;
import org.springframework.transaction.support.TransactionOperations;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/** Existing stock/search business; Cart consumption and Order creation share the same writer TX. */
public final class JpaCartOrders implements CartOrders {
    private final CartRepository carts;
    private final OrderRepository orders;
    private final ProductSKURepository skus;
    private final JpaStockAdjustments stocks;
    private final StoreOrderActors actors;
    private final TransactionOperations writes;

    public JpaCartOrders(EntityManager sharedEntityManager,TransactionOperations writes,StoreOrderActors actors,
            NamedParameterJdbcOperations writerSearchJdbc,KoreanSearchTerms terms) {
        var factory=new JpaRepositoryFactory(sharedEntityManager);
        this.carts=factory.getRepository(CartRepository.class);
        this.orders=factory.getRepository(OrderRepository.class);
        this.skus=factory.getRepository(ProductSKURepository.class);
        var snapshots=new ProductSnapshots(writerSearchJdbc,terms);
        this.stocks=new JpaStockAdjustments(factory.getRepository(ProductRepository.class),skus,snapshots);
        this.actors=actors;this.writes=writes;
    }
    JpaCartOrders(CartRepository carts,OrderRepository orders,ProductSKURepository skus,JpaStockAdjustments stocks,
            StoreOrderActors actors,TransactionOperations writes) {
        this.carts=carts;this.orders=orders;this.skus=skus;this.stocks=stocks;this.actors=actors;this.writes=writes;
    }
    @Override public DirectOrders.OrderDetails create(long user,Command command) {
        return writes.execute(status->{
            if(!TransactionSynchronizationManager.isActualTransactionActive() || TransactionSynchronizationManager.isCurrentTransactionReadOnly())
                throw new IllegalStateException("An active checkout writer transaction is required");
            var actor=actors.currentForWrite(user);
            if(actor==null || actor.userId()!=user || !actor.active() || actor.deleted()) throw new OrderActorRejectedException();
            var cart=carts.findByUserId(user).orElseThrow(()->new IllegalArgumentException("Cart is empty"));
            if(cart.getItems().isEmpty()) throw new IllegalArgumentException("Cart is empty");
            var quantities=new LinkedHashMap<Long,Integer>();
            for(var item:cart.getItems()) {
                if(quantities.containsKey(item.getSkuId())) throw new PersistenceException("Stored cart cannot be read");
                quantities.put(item.getSkuId(),item.getQuantity());
            }
            // Preserve Cart GET's missing-SKU filtering without loading prices/entities before the stock locks.
            var existing=skus.findProductIdsBySkuIdIn(quantities.keySet()).stream()
                    .map(ProductSKURepository.SkuProductId::getSkuId).collect(Collectors.toSet());
            quantities.keySet().retainAll(existing);
            if(quantities.isEmpty()) throw new IllegalArgumentException("Cart is empty");
            stocks.decreaseStocks(quantities);
            var purchased=skus.findAllById(quantities.keySet());
            if(purchased.size()!=quantities.size()) throw new PersistenceException("Checkout items cannot be read");
            long total=0;
            try {
                for(var sku:purchased) total=Math.addExact(total,Math.multiplyExact(sku.getPrice(),quantities.get(sku.getId()).longValue()));
            } catch(ArithmeticException overflow) { throw new IllegalArgumentException("Order amount is out of range",overflow); }
            var order=Order.builder().userId(user).orderUuid(UUID.randomUUID().toString()).totalAmount(total)
                    .receiverName(command.receiverName()).receiverPhone(command.receiverPhone())
                    .deliveryAddress(command.deliveryAddress()).deliveryMessage(command.deliveryMessage()).build();
            for(var sku:purchased) order.getOrderItems().add(OrderItem.builder().order(order).productSKU(sku)
                    .productName(sku.getProduct().getName()).skuCode(sku.getSkuCode()).price(sku.getPrice()).quantity(quantities.get(sku.getId())).build());
            orders.save(order);
            cart.clearItems();
            return JpaDirectOrders.details(OrderResponse.from(order));
        });
    }
}
