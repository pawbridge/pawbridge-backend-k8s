package com.pawbridge.backend.store.internal.memberread.infrastructure;

import com.pawbridge.backend.store.api.StoreMemberReadUnavailableException;
import com.pawbridge.backend.store.api.StoreMemberReads;
import com.pawbridge.backend.store.internal.memberread.infrastructure.jpa.projection.OrderResponse;
import com.pawbridge.backend.store.internal.memberread.infrastructure.jpa.projection.WishlistResponse;
import com.pawbridge.backend.store.internal.memberread.infrastructure.jpa.repository.MyOrderRepository;
import com.pawbridge.backend.store.internal.memberread.infrastructure.jpa.repository.MyWishlistRepository;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceException;
import java.util.HashMap;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;
import java.util.function.Supplier;
import org.springframework.dao.DataAccessException;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.repository.support.JpaRepositoryFactory;
import org.springframework.transaction.TransactionException;
import org.springframework.transaction.support.TransactionOperations;

/** Parent ID paging precedes the selected parents' original graphs in Root's read-only, repeatable-read JpaTM transaction. */
public final class JpaStoreMemberReads implements StoreMemberReads {
    private static final Set<String> WISHLIST_SORTS = Set.of(
            "id", "userId", "createdAt", "updatedAt",
            "sku.id", "sku.skuCode", "sku.price", "sku.stockQuantity",
            "sku.createdAt", "sku.updatedAt",
            "sku.product.id", "sku.product.name", "sku.product.description", "sku.product.imageUrl",
            "sku.product.status", "sku.product.viewCount", "sku.product.createdAt", "sku.product.updatedAt",
            "sku.product.category.id", "sku.product.category.name",
            "sku.product.category.description", "sku.product.category.createdAt", "sku.product.category.updatedAt",
            "sku.product.category.parent.id", "sku.product.category.parent.name",
            "skuPrice", "sku_price");
    private static final Set<String> ORDER_SORTS = Set.of(
            "id", "userId", "orderUuid", "totalAmount", "status", "deliveryAddress", "deliveryStatus",
            "receiverName", "receiverPhone", "deliveryMessage", "createdAt", "updatedAt");

    private final MyWishlistRepository wishlists;
    private final MyOrderRepository orders;
    private final TransactionOperations reads;
    private final StoreMemberReads carts;

    public JpaStoreMemberReads(EntityManager sharedEntityManager, TransactionOperations reads, StoreMemberReads carts) {
        this(new JpaRepositoryFactory(sharedEntityManager), reads, carts);
    }

    private JpaStoreMemberReads(JpaRepositoryFactory factory, TransactionOperations reads, StoreMemberReads carts) {
        this(factory.getRepository(MyWishlistRepository.class), factory.getRepository(MyOrderRepository.class), reads, carts);
    }

    JpaStoreMemberReads(MyWishlistRepository wishlists, MyOrderRepository orders, TransactionOperations reads, StoreMemberReads carts) {
        this.wishlists = wishlists;
        this.orders = orders;
        this.reads = reads;
        this.carts = carts;
    }

    @Override
    public Page<Wishlist> wishlists(long memberId, Request request) {
        var pageable = pageable(request, WISHLIST_SORTS);
        return execute(() -> {
            var ids = wishlists.findIdsByUserId(memberId, pageable);
            var selected = ids.getContent().stream().map(MyWishlistRepository.IdOnly::getId).toList();
            if (selected.isEmpty()) return page(ids, List.<Wishlist>of());
            var content = ordered(selected, wishlists.findByUserIdAndIdIn(memberId, selected), entity -> entity.getId()).stream()
                    .map(WishlistResponse::from).map(JpaStoreMemberReads::wishlist).toList();
            return page(ids, content);
        });
    }

    @Override
    public Page<Purchase> orders(long memberId, Request request) {
        var pageable = pageable(request, ORDER_SORTS);
        return execute(() -> {
            var ids = orders.findIdsByUserId(memberId, pageable);
            var selected = ids.getContent().stream().map(MyOrderRepository.IdOnly::getId).toList();
            if (selected.isEmpty()) return page(ids, List.<Purchase>of());
            var content = ordered(selected, orders.findByUserIdAndIdIn(memberId, selected), entity -> entity.getId()).stream()
                    .map(OrderResponse::from).map(JpaStoreMemberReads::purchase).toList();
            return page(ids, content);
        });
    }

    @Override
    public Optional<Cart> cart(long memberId) {
        // Keep the existing persisted-cart JDBC read and its own transaction boundary.
        return carts.cart(memberId);
    }

    private <T> T execute(Supplier<T> action) {
        try { return reads.execute(status -> action.get()); }
        catch (DataAccessException | TransactionException | PersistenceException failure) {
            throw new StoreMemberReadUnavailableException(failure);
        }
    }

    private static PageRequest pageable(Request request, Set<String> allowed) {
        // Bound query joins before opening the reader transaction; aliases are exact literals only.
        if (request.size() > 2000 || request.sort().size() > 4 || request.sort().stream()
                .anyMatch(order -> order.property() == null || !allowed.contains(order.property()))) {
            throw new IllegalArgumentException("Store member sort is not supported");
        }
        Sort sort = Sort.by(request.sort().stream().map(order -> new Sort.Order(
                order.ascending() ? Sort.Direction.ASC : Sort.Direction.DESC, order.property(),
                order.ignoreCase(), Sort.NullHandling.NATIVE)).toList());
        return PageRequest.of(request.page(), request.size(), sort);
    }

    private static Wishlist wishlist(WishlistResponse source) {
        return new Wishlist(source.getWishlistId(), source.getSkuId(), source.getSkuCode(), source.getProductId(),
                source.getProductName(), source.getProductDescription(), source.getProductImageUrl(), source.getProductStatus(),
                source.getPrice(), source.getOptions(), source.getCategoryId(), source.getCategoryName(), source.getCreatedAt());
    }

    private static Purchase purchase(OrderResponse source) {
        return new Purchase(source.getOrderId(), source.getOrderUuid(), source.getTotalAmount(), source.getStatus(),
                source.getDeliveryAddress(), source.getDeliveryStatus(), source.getItems().stream().map(item -> new OrderItem(
                        item.getOrderItemId(), item.getProductSkuId(), item.getProductName(), item.getSkuCode(), item.getPrice(),
                        item.getQuantity())).toList(), source.getCreatedAt());
    }

    private static <T> List<T> ordered(List<Long> selected, List<T> fetched, Function<T, Long> identity) {
        var byId = new HashMap<Long, T>();
        fetched.forEach(entity -> byId.putIfAbsent(identity.apply(entity), entity));
        return selected.stream().map(id -> {
            T entity = byId.get(id);
            if (entity == null) throw new PersistenceException("Store member page could not be read");
            return entity;
        }).toList();
    }

    private static <T> Page<T> page(org.springframework.data.domain.Page<?> source, List<T> content) {
        return new Page<>(content, source.getNumber(), source.getSize(), source.getTotalElements(),
                source.getSort().stream().map(order -> new Order(order.getProperty(), order.isAscending(), order.isIgnoreCase())).toList());
    }
}
