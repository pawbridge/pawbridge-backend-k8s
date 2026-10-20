package com.pawbridge.backend.user.internal.storemypage.infrastructure;

import com.pawbridge.backend.store.api.StoreMemberReads;
import com.pawbridge.backend.user.internal.profile.application.UserProfileQuery;
import com.pawbridge.backend.user.internal.storemypage.application.StoreMyPage;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;
import java.util.function.Function;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.transaction.support.TransactionOperations;
import org.springframework.util.StringUtils;

/** User-owned subset mapping; Store owns its own read transaction after the User check has ended. */
public final class ModuleStoreMyPage implements StoreMyPage {
    private final UserProfileQuery profiles;
    private final TransactionOperations read;
    private final StoreMemberReads store;

    public ModuleStoreMyPage(UserProfileQuery profiles, TransactionOperations read, StoreMemberReads store) {
        this.profiles = profiles;
        this.read = read;
        this.store = store;
    }

    @Override
    public Page<Wishlist> wishlists(long current, Request request) {
        requireMember(current);
        try {
            return page(Objects.requireNonNull(store.wishlists(current, request(request))), item -> new Wishlist(
                    item.wishlistId(), item.productId(), item.productName(), item.productDescription(),
                    item.productImageUrl(), item.productStatus(), item.categoryId(), item.categoryName(), item.createdAt()));
        } catch (Exception unavailable) {
            throw new IllegalStateException("Store wishlist lookup failed", unavailable);
        }
    }

    @Override
    public Page<Purchase> orders(long current, Request request) {
        requireMember(current);
        try {
            return page(Objects.requireNonNull(store.orders(current, request(request))), item -> new Purchase(
                    item.orderId(), item.orderUuid(), item.totalAmount(), item.status(), item.deliveryAddress(),
                    item.deliveryStatus(), map(item.items(), value -> new OrderItem(value.orderItemId(),
                            value.productSkuId(), value.productName(), value.skuCode(), value.price(), value.quantity())), item.createdAt()));
        } catch (Exception unavailable) {
            throw new IllegalStateException("Store order lookup failed", unavailable);
        }
    }

    @Override
    public Cart cart(long current) {
        requireMember(current);
        try {
            StoreMemberReads.Cart source = Objects.requireNonNull(store.cart(current)).orElse(null);
            if (source == null) return null;
            Objects.requireNonNull(source.items());
            return new Cart(source.cartId(), source.userId(), map(source.items(), item -> new CartItem(
                    item.cartItemId(), item.productSkuId(), item.productName(), item.skuCode(),
                    item.price(), item.quantity(), item.totalPrice())), source.createdAt());
        } catch (Exception unavailable) {
            throw new IllegalStateException("Store cart lookup failed", unavailable);
        }
    }

    private void requireMember(long current) {
        // Begin/lookup/commit failures precede Store and keep their original User failure category.
        read.execute(status -> { profiles.require(current); return null; });
    }

    /** Match one HTTP sort parameter after User's existing comma flattening, including lost direction boundaries. */
    private static StoreMemberReads.Request request(Request source) {
        String raw = source.sort();
        if (raw == null || raw.isEmpty()) raw = "createdAt,desc";
        List<String> elements = Arrays.stream(raw.split(","))
                .filter(value -> StringUtils.hasText(value.replace(".", ""))).toList();
        int end = elements.size();
        boolean ignoreCase = end > 0 && elements.get(end - 1).equalsIgnoreCase("ignorecase");
        if (ignoreCase) end--;
        var direction = end == 0 ? java.util.Optional.<Sort.Direction>empty()
                : Sort.Direction.fromOptionalString(elements.get(end - 1));
        if (direction.isPresent()) end--;
        var orders = new ArrayList<StoreMemberReads.Order>();
        for (int i = 0; i < end; i++) {
            String property = elements.get(i);
            if (StringUtils.hasText(property)) orders.add(new StoreMemberReads.Order(property,
                    direction.orElse(Sort.Direction.ASC).isAscending(), ignoreCase));
        }
        // An empty parsed Sort lets Store's @PageableDefault supply createdAt DESC.
        if (orders.isEmpty()) orders.add(new StoreMemberReads.Order("createdAt", false, false));
        // These are the existing Store Pageable resolver defaults, not a new API page cap.
        return new StoreMemberReads.Request(Math.max(0, source.page()),
                source.size() < 1 ? 20 : Math.min(2000, source.size()), orders);
    }

    private static <T, R> Page<R> page(StoreMemberReads.Page<T> source, Function<T, R> mapper) {
        var content = map(source.content(), mapper);
        // Fixed Store Feign SortJacksonModule serializes an array, so the existing User array branch retains each order.
        Sort normalizedSort = Sort.by(source.sort().stream().map(order -> new Sort.Order(
                order.ascending() ? Sort.Direction.ASC : Sort.Direction.DESC, order.property(),
                order.ignoreCase(), Sort.NullHandling.NATIVE)).toList());
        org.springframework.data.domain.Page<R> normalized = source.size() > 0
                ? new PageImpl<>(content, PageRequest.of(source.number(), source.size(), normalizedSort), source.totalElements())
                : new PageImpl<>(content);
        return new Page<>(normalized.getContent(), normalized.getNumber(), normalized.getSize(),
                normalized.getTotalElements(), normalized.getSort().stream().map(order -> new Order(order.getProperty(),
                        order.isAscending(), order.isIgnoreCase(), order.getNullHandling().name())).toList());
    }

    private static <T, R> List<R> map(List<T> values, Function<T, R> mapper) {
        return values == null ? null : values.stream().map(value -> value == null ? null : mapper.apply(value)).toList();
    }
}
