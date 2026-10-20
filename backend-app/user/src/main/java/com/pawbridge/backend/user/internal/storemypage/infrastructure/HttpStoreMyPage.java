package com.pawbridge.backend.user.internal.storemypage.infrastructure;

import com.fasterxml.jackson.databind.JsonNode;
import com.pawbridge.backend.user.internal.profile.application.UserProfileQuery;
import com.pawbridge.backend.user.internal.storemypage.application.StoreMyPage;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.transaction.support.TransactionOperations;
import org.springframework.web.client.RestClient;

public final class HttpStoreMyPage implements StoreMyPage {
    private final UserProfileQuery profiles;
    private final TransactionOperations read;
    private final RestClient client;

    public HttpStoreMyPage(UserProfileQuery profiles, TransactionOperations read, RestClient client) {
        this.profiles = profiles;
        this.read = read;
        this.client = client;
    }

    @Override
    public Page<Wishlist> wishlists(long current, Request request) {
        return read.execute(status -> {
            profiles.require(current);
            try {
                return page(Objects.requireNonNull(client.get().uri(uri -> uri.path("/api/v1/mypage/wishlists")
                        .queryParam("userId", current).queryParam("page", request.page())
                        .queryParam("size", request.size()).queryParam("sort", request.sort()).build())
                        .retrieve().body(new ParameterizedTypeReference<RemotePage<Wishlist>>() { })));
            } catch (Exception unavailable) {
                throw new IllegalStateException("Store wishlist lookup failed", unavailable);
            }
        });
    }

    @Override
    public Page<Purchase> orders(long current, Request request) {
        return read.execute(status -> {
            profiles.require(current);
            try {
                return page(Objects.requireNonNull(client.get().uri(uri -> uri.path("/api/v1/mypage/orders")
                        .queryParam("userId", current).queryParam("page", request.page())
                        .queryParam("size", request.size()).queryParam("sort", request.sort()).build())
                        .retrieve().body(new ParameterizedTypeReference<RemotePage<Purchase>>() { })));
            } catch (Exception unavailable) {
                throw new IllegalStateException("Store order lookup failed", unavailable);
            }
        });
    }

    @Override
    public Cart cart(long current) {
        return read.execute(status -> {
            profiles.require(current);
            try {
                Cart cart = client.get().uri(uri -> uri.path("/api/v1/mypage/cart")
                        .queryParam("userId", current).build()).retrieve().body(Cart.class);
                // The original service dereferences items for a non-null cart, so malformed items fail.
                if (cart != null) cart.items().size();
                return cart;
            } catch (Exception unavailable) {
                throw new IllegalStateException("Store cart lookup failed", unavailable);
            }
        });
    }

    /** Match the existing Feign PageJacksonModule constructor, including its unsorted fallback. */
    private static <T> Page<T> page(RemotePage<T> source) {
        // Feign deserializes the nested Pageable even when the outer size is positive.
        // An invalid nested page size therefore fails rather than silently being ignored.
        PageRequest nested = source.pageable() == null || source.pageable().isNull() ? null
                : PageRequest.of(source.pageable().path("pageNumber").asInt(),
                        source.pageable().path("pageSize").asInt(), sort(source.pageable().get("sort")));
        org.springframework.data.domain.Page<T> normalized;
        if (source.size() > 0) {
            normalized = new PageImpl<>(source.content(), PageRequest.of(source.number(), source.size(), sort(source.sort())), source.totalElements());
        } else if (nested != null) {
            normalized = new PageImpl<>(source.content(), nested, source.totalElements());
        } else {
            normalized = new PageImpl<>(source.content());
        }
        List<Order> orders = normalized.getSort().stream().map(order -> new Order(order.getProperty(),
                order.isAscending(), order.isIgnoreCase(), order.getNullHandling().name())).toList();
        return new Page<>(normalized.getContent(), normalized.getNumber(), normalized.getSize(), normalized.getTotalElements(), orders);
    }

    private static Sort sort(JsonNode source) {
        JsonNode orders = source == null ? null : source.isArray() ? source : source.get("orders");
        if (orders == null || !orders.isArray()) return Sort.unsorted();
        var result = new ArrayList<Sort.Order>();
        for (JsonNode order : orders) {
            Sort.Direction direction = Sort.Direction.valueOf(order.get("direction").textValue());
            String property = order.get("property").textValue();
            if (order.has("ignoreCase") && order.get("ignoreCase").isBoolean()
                    && order.has("nullHandling") && order.get("nullHandling").isTextual()) {
                result.add(new Sort.Order(direction, property, order.get("ignoreCase").asBoolean(),
                        Sort.NullHandling.valueOf(order.get("nullHandling").textValue())));
            } else {
                result.add(new Sort.Order(direction, property));
            }
        }
        return Sort.by(result);
    }

    private record RemotePage<T>(List<T> content, JsonNode pageable, int number, int size,
                                 long totalElements, JsonNode sort) { }
}
