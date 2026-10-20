package com.pawbridge.backend.user.internal.storemypage.infrastructure;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import com.pawbridge.backend.user.internal.profile.application.UserNotFoundException;
import com.pawbridge.backend.user.internal.profile.application.UserProfileQuery;
import com.pawbridge.backend.user.internal.storemypage.application.StoreMyPage;
import java.util.ArrayList;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.transaction.TransactionException;
import org.springframework.transaction.support.SimpleTransactionStatus;
import org.springframework.transaction.support.TransactionCallback;
import org.springframework.transaction.support.TransactionOperations;
import org.springframework.web.client.RestClient;

class HttpStoreMyPageTest {
    @ParameterizedTest
    @ValueSource(strings = {"wishlists", "orders", "cart"})
    void given__a_missing_current_member__when__reading_store_mypage__then__fail_inside_read_transaction_before_http(String operation) {
        var events = new ArrayList<String>();
        var profiles = mock(UserProfileQuery.class);
        when(profiles.require(7)).thenAnswer(call -> { events.add("member"); throw new UserNotFoundException(); });
        var read = new TransactionOperations() {
            public <T> T execute(TransactionCallback<T> callback) throws TransactionException {
                events.add("begin");
                try { return callback.doInTransaction(new SimpleTransactionStatus()); }
                finally { events.add("end"); }
            }
        };
        var client = RestClient.builder().requestFactory((uri, method) -> {
            fail("A missing User must not reach Store"); return null;
        }).build();
        var pages = new HttpStoreMyPage(profiles, read, client);
        assertThrows(UserNotFoundException.class, () -> call(pages, operation));
        assertEquals(java.util.List.of("begin", "member", "end"), events);
        verify(profiles).require(7);
    }

    @Test
    void given__read_transaction_begin_failure__when__reading__then__query_neither_member_nor_store() {
        var profiles = mock(UserProfileQuery.class);
        var failure = new IllegalStateException("synthetic begin failure");
        var read = new TransactionOperations() {
            public <T> T execute(TransactionCallback<T> callback) { throw failure; }
        };
        var client = RestClient.builder().requestFactory((uri, method) -> {
            fail("A begin failure must not reach Store"); return null;
        }).build();
        assertSame(failure, assertThrows(IllegalStateException.class,
                () -> new HttpStoreMyPage(profiles, read, client).cart(7)));
        verifyNoInteractions(profiles);
    }

    private static Object call(HttpStoreMyPage pages, String operation) {
        var request = new StoreMyPage.Request(0, 20, "createdAt,desc");
        return switch (operation) {
            case "wishlists" -> pages.wishlists(7, request);
            case "orders" -> pages.orders(7, request);
            default -> pages.cart(7);
        };
    }
}
