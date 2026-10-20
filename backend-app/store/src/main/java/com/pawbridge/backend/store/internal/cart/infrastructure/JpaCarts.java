package com.pawbridge.backend.store.internal.cart.infrastructure;

import com.pawbridge.backend.store.api.StoreOrderActors;
import com.pawbridge.backend.store.internal.cart.application.Carts;
import com.pawbridge.backend.store.internal.cart.application.CartActorRejectedException;
import com.pawbridge.backend.store.internal.cart.infrastructure.jpa.model.Cart;
import com.pawbridge.backend.store.internal.cart.infrastructure.jpa.model.CartItem;
import com.pawbridge.backend.store.internal.cart.infrastructure.jpa.projection.CartItemResponse;
import com.pawbridge.backend.store.internal.cart.infrastructure.jpa.repository.CartRepository;
import com.pawbridge.backend.store.internal.memberread.infrastructure.jpa.model.ProductSKU;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.data.jpa.repository.support.JpaRepositoryFactory;
import org.springframework.transaction.support.TransactionOperations;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/** DB writes replace Redis hot data; Root fences the old scheduler and owns legacy data handoff. */
public final class JpaCarts implements Carts {
    private final EntityManager em;
    private final CartRepository carts;
    private final TransactionOperations writes;
    private final TransactionOperations reads;
    private final StoreOrderActors actors;

    public JpaCarts(EntityManager sharedEntityManager, TransactionOperations writes, TransactionOperations reads, StoreOrderActors actors) {
        this(sharedEntityManager,new JpaRepositoryFactory(sharedEntityManager).getRepository(CartRepository.class),writes,reads,actors);
    }
    JpaCarts(EntityManager em,CartRepository carts,TransactionOperations writes,TransactionOperations reads,StoreOrderActors actors) {
        this.em=em;this.carts=carts;this.writes=writes;this.reads=reads;this.actors=actors;
    }
    @Override public List<Item> get(long user) {
        return reads.execute(status->{
            transaction(true);actor(user,actors.currentForRead(user));
            var cart=carts.findByUserId(user);
            if(cart.isEmpty() || cart.get().getItems().isEmpty()) return List.of();
            var quantities=items(cart.get());
            return em.createQuery("select s from ProductSKU s where s.id in :ids",ProductSKU.class)
                    .setParameter("ids",quantities.keySet()).getResultList().stream().map(sku->{
                        int quantity=quantities.get(sku.getId()).getQuantity();
                        amount(sku,quantity);
                        var row=CartItemResponse.of(sku,quantity);
                        return new Item(row.getId(),row.getSkuId(),row.getSkuCode(),row.getProductName(),row.getPrice(),row.getQuantity(),row.getTotalPrice());
                    }).toList();
        });
    }
    @Override public void add(long user,Long skuId,Integer quantity) {
        write(user,()->{
            if(skuId==null || skuId<=0 || quantity==null || quantity<=0) throw new IllegalArgumentException("SKU ID and quantity must be positive");
            ProductSKU sku=em.find(ProductSKU.class,skuId);
            if(sku==null) throw new IllegalArgumentException("SKU not found");
            // Preserve the original per-add stock check; cart quantity is not a stock reservation.
            if(sku.getStockQuantity()<quantity) throw new IllegalArgumentException("Not enough stock");
            amount(sku,quantity);
            Cart cart=carts.findByUserId(user).orElseGet(()->carts.save(Cart.builder().userId(user).build()));
            CartItem existing=items(cart).get(skuId);
            if(existing==null) cart.addItem(CartItem.builder().skuId(skuId).quantity(quantity).build());
            else {
                int next;
                try { next=Math.addExact(existing.getQuantity(),quantity); }
                catch(ArithmeticException failure) { throw new IllegalArgumentException("Cart quantity is out of range",failure); }
                amount(sku,next);
                existing.updateQuantity(next);
            }
        });
    }
    @Override public void update(long user,long skuId,int quantity) {
        write(user,()->{
            var cart=carts.findByUserId(user);
            if(quantity<=0) { cart.ifPresent(value->value.getItems().removeIf(item->item.getSkuId().equals(skuId)));return; }
            CartItem existing=cart.map(JpaCarts::items).map(value->value.get(skuId)).orElse(null);
            if(existing==null) throw new IllegalArgumentException("Item not in cart");
            ProductSKU sku=em.find(ProductSKU.class,skuId);
            if(sku!=null) amount(sku,quantity);
            existing.updateQuantity(quantity);
        });
    }
    @Override public void remove(long user,long skuId) {
        write(user,()->carts.findByUserId(user).ifPresent(cart->cart.getItems().removeIf(item->item.getSkuId().equals(skuId))));
    }
    @Override public void clear(long user) { write(user,()->carts.findByUserId(user).ifPresent(Cart::clearItems)); }
    private void write(long user,Runnable change) {
        writes.execute(status->{transaction(false);actor(user,actors.currentForWrite(user));change.run();return null;});
    }
    private static void amount(ProductSKU sku,int quantity) {
        try { Math.multiplyExact(sku.getPrice(),(long)quantity); }
        catch(ArithmeticException failure) { throw new IllegalArgumentException("Cart amount is out of range",failure); }
    }
    private static Map<Long,CartItem> items(Cart cart) {
        var result=new LinkedHashMap<Long,CartItem>();
        for(CartItem item:cart.getItems()) if(result.putIfAbsent(item.getSkuId(),item)!=null)
            throw new PersistenceException("Stored cart cannot be read");
        return result;
    }
    private static void actor(long user,StoreOrderActors.Actor actor) {
        if(actor==null || actor.userId()!=user || !actor.active() || actor.deleted()) throw new CartActorRejectedException();
    }
    private static void transaction(boolean readOnly) {
        if(!TransactionSynchronizationManager.isActualTransactionActive() || TransactionSynchronizationManager.isCurrentTransactionReadOnly()!=readOnly)
            throw new IllegalStateException("An active cart transaction with the expected read mode is required");
    }
}
