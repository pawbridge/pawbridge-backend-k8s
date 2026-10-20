package com.pawbridge.backend.user.internal.storemypage.infrastructure;

import static org.junit.jupiter.api.Assertions.*;
import com.pawbridge.backend.user.internal.profile.application.UserProfileQuery;
import com.pawbridge.backend.user.internal.storemypage.application.StoreMyPage;
import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.transaction.support.SimpleTransactionStatus;
import org.springframework.transaction.support.TransactionCallback;
import org.springframework.transaction.support.TransactionOperations;
import org.springframework.web.client.RestClient;

class HttpStoreMyPageTransportTest {
    @Test
    void given__the_three_store_json_responses__when__reading_inside_the_original_ro_boundary__then__keep_paths_dto_dates_remote_page_metadata_and_sort_without_forwarded_identity() throws Exception {
        var paths = new ArrayList<String>();
        var auth = new ArrayList<String>();
        var forwarded = new ArrayList<String>();
        var active = new AtomicBoolean();
        var memberSeen = new AtomicBoolean();
        var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            paths.add(exchange.getRequestURI().toASCIIString());
            auth.add(exchange.getRequestHeaders().getFirst("Authorization"));
            forwarded.add(exchange.getRequestHeaders().getFirst("X-User-Id"));
            String json = paths.getLast().contains("wishlists")
                    ? """
                    {"content":[{"wishlistId":1,"productId":2,"productName":"상품","productDescription":null,"productImageUrl":"synthetic","productStatus":"ACTIVE","categoryId":3,"categoryName":"분류","createdAt":"2026-10-20T01:02:03.123456"}],"number":2,"size":7,"totalElements":2147483650,"sort":[{"property":"productName","direction":"ASC","ignoreCase":true,"nullHandling":"NULLS_LAST"}],"first":false,"last":false}
                    """
                    : paths.getLast().contains("orders")
                    ? """
                    {"content":[{"orderId":4,"orderUuid":"synthetic-order","totalAmount":12,"status":"PAID","deliveryAddress":null,"deliveryStatus":"READY","items":[{"orderItemId":5,"productSkuId":6,"productName":"상품","skuCode":"SKU","price":12,"quantity":1}],"createdAt":null}],"number":0,"size":20,"totalElements":1,"sort":{"empty":false,"sorted":true,"unsorted":false},"pageable":{"pageNumber":0,"pageSize":20}}
                    """
                    : """
                    {"cartId":8,"userId":7,"items":[{"cartItemId":9,"productSkuId":6,"productName":"상품","skuCode":"SKU","price":12,"quantity":2,"totalPrice":24}],"createdAt":"2026-10-20T01:02:03.123456"}
                    """;
            byte[] body = json.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, body.length); exchange.getResponseBody().write(body); exchange.close();
        });
        server.start();
        try {
            UserProfileQuery profiles = id -> { assertTrue(active.get()); assertEquals(7, id); memberSeen.set(true); return null; };
            var read = new TransactionOperations() {
                public <T> T execute(TransactionCallback<T> callback) {
                    active.set(true);
                    try { return callback.doInTransaction(new SimpleTransactionStatus()); }
                    finally { active.set(false); }
                }
            };
            var client = RestClient.builder().baseUrl("http://127.0.0.1:" + server.getAddress().getPort())
                    .requestInterceptor((request, body, execution) -> {
                        assertTrue(active.get()); assertTrue(memberSeen.getAndSet(false));
                        assertEquals("GET", request.getMethod().name()); assertEquals(0, body.length);
                        return execution.execute(request, body);
                    }).build();
            var pages = new HttpStoreMyPage(profiles, read, client);
            var request = new StoreMyPage.Request(3, 20, "name,asc,createdAt,desc");
            var wishlist = pages.wishlists(7, request);
            assertEquals(2, wishlist.number()); assertEquals(7, wishlist.size());
            assertEquals(2147483650L, wishlist.totalElements());
            assertEquals(new StoreMyPage.Order("productName", true, true, "NULLS_LAST"), wishlist.sort().getFirst());
            assertNull(wishlist.content().getFirst().productDescription());
            assertEquals(123456000, wishlist.content().getFirst().createdAt().getNano());
            var orders = pages.orders(7, request);
            assertEquals(5L, orders.content().getFirst().items().getFirst().orderItemId());
            assertNull(orders.content().getFirst().createdAt()); assertEquals(List.of(), orders.sort());
            assertEquals(24L, pages.cart(7).items().getFirst().totalPrice());
            assertEquals(List.of("/api/v1/mypage/wishlists?userId=7&page=3&size=20&sort=name,asc,createdAt,desc",
                    "/api/v1/mypage/orders?userId=7&page=3&size=20&sort=name,asc,createdAt,desc",
                    "/api/v1/mypage/cart?userId=7"), paths);
            assertTrue(auth.stream().allMatch(java.util.Objects::isNull));
            assertTrue(forwarded.stream().allMatch(java.util.Objects::isNull)); assertFalse(active.get());
        } finally { server.stop(0); }
    }

    @Test
    void given__store_has_no_cart__when__it_returns_204__then__return_null_without_creating_a_cart() throws Exception {
        var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> { exchange.sendResponseHeaders(204, -1); exchange.close(); }); server.start();
        try { assertNull(facade(server).cart(7)); } finally { server.stop(0); }
    }

    @ParameterizedTest
    @ValueSource(strings = {"server-error", "empty-page", "null-cart-items", "invalid-pageable"})
    void given__an_unavailable_or_invalid_store_response__when__reading__then__fail_instead_of_fabricating_empty_data(String defect) throws Exception {
        var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            if (defect.equals("server-error")) exchange.sendResponseHeaders(503, -1);
            else if (defect.equals("empty-page")) exchange.sendResponseHeaders(200, -1);
            else {
                String json = defect.equals("invalid-pageable")
                        ? "{\"content\":[],\"number\":0,\"size\":20,\"totalElements\":0,\"pageable\":{}}"
                        : "{\"cartId\":8,\"userId\":7,\"items\":null}";
                byte[] body = json.getBytes(StandardCharsets.UTF_8);
                exchange.getResponseHeaders().set("Content-Type", "application/json");
                exchange.sendResponseHeaders(200, body.length); exchange.getResponseBody().write(body);
            }
            exchange.close();
        }); server.start();
        try {
            var pages = facade(server);
            assertThrows(IllegalStateException.class, () -> {
                if (defect.equals("null-cart-items")) pages.cart(7);
                else pages.orders(7, new StoreMyPage.Request(0, 20, "createdAt,desc"));
            });
        } finally { server.stop(0); }
    }

    @ParameterizedTest
    @ValueSource(strings = {"wishlists", "orders", "cart"})
    void given__a_store_redirect__when__reading_each_route__then__fail_before_mapping_page_data_or_treating_it_as_an_absent_cart(String operation) throws Exception {
        var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            exchange.getResponseHeaders().set("Location", "/another-location");
            if (operation.equals("cart")) exchange.sendResponseHeaders(302, -1);
            else {
                byte[] body = "{\"content\":[],\"number\":0,\"size\":20,\"totalElements\":0}".getBytes(StandardCharsets.UTF_8);
                exchange.getResponseHeaders().set("Content-Type", "application/json");
                exchange.sendResponseHeaders(302, body.length); exchange.getResponseBody().write(body);
            }
            exchange.close();
        });
        server.start();
        try (var http = java.net.http.HttpClient.newBuilder().followRedirects(java.net.http.HttpClient.Redirect.NEVER).build()) {
            var client = RestClient.builder().baseUrl("http://127.0.0.1:" + server.getAddress().getPort())
                    .requestFactory(new org.springframework.http.client.JdkClientHttpRequestFactory(http)).build();
            var pages = new HttpStoreMyPage(id -> null, new TransactionOperations() {
                public <T> T execute(TransactionCallback<T> callback) { return callback.doInTransaction(new SimpleTransactionStatus()); }
            }, client);
            assertThrows(IllegalStateException.class, () -> {
                if (operation.equals("cart")) pages.cart(7);
                else if (operation.equals("wishlists")) pages.wishlists(7, new StoreMyPage.Request(0, 20, "createdAt,desc"));
                else pages.orders(7, new StoreMyPage.Request(0, 20, "createdAt,desc"));
            });
        } finally { server.stop(0); }
    }

    private static HttpStoreMyPage facade(HttpServer server) {
        return new HttpStoreMyPage(id -> null, new TransactionOperations() {
            public <T> T execute(TransactionCallback<T> callback) { return callback.doInTransaction(new SimpleTransactionStatus()); }
        }, RestClient.builder().baseUrl("http://127.0.0.1:" + server.getAddress().getPort()).build());
    }
}
