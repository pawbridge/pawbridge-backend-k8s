package com.pawbridge.backend.user.internal.storemypage.infrastructure

import com.pawbridge.backend.store.api.StoreMemberReadUnavailableException
import com.pawbridge.backend.store.api.StoreMemberReads
import com.pawbridge.backend.user.anyNonNull
import com.pawbridge.backend.user.internal.profile.application.UserNotFoundException
import com.pawbridge.backend.user.internal.profile.application.UserProfileQuery
import com.pawbridge.backend.user.internal.storemypage.application.StoreMyPage
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.Arguments
import org.junit.jupiter.params.provider.CsvSource
import org.junit.jupiter.params.provider.MethodSource
import org.junit.jupiter.params.provider.ValueSource
import org.mockito.ArgumentMatchers.anyLong
import org.mockito.Mockito.mock
import org.mockito.Mockito.`when`
import org.mockito.Mockito.verify
import org.mockito.Mockito.verifyNoInteractions
import org.mockito.Mockito.verifyNoMoreInteractions
import org.springframework.core.MethodParameter
import org.springframework.data.domain.Pageable
import org.springframework.data.domain.Sort
import org.springframework.data.web.PageableDefault
import org.springframework.data.web.PageableHandlerMethodArgumentResolver
import org.springframework.transaction.support.SimpleTransactionStatus
import org.springframework.transaction.support.TransactionCallback
import org.springframework.transaction.support.TransactionOperations
import org.springframework.web.context.request.NativeWebRequest
import java.time.LocalDateTime
import java.util.Optional
import java.util.function.Supplier
import java.util.stream.Stream

class ModuleStoreMyPageTest {
    @ParameterizedTest
    @ValueSource(strings = ["wishlists", "orders", "cart"])
    fun given__the_current_member__when__reading_each_store_result__then__finish_user_transaction_before_calling_store_with_that_id(
        operation: String,
    ) {
        val events = ArrayList<String>()
        val read = Read(events)
        val profiles = mock(UserProfileQuery::class.java)
        val store = mock(StoreMemberReads::class.java)
        `when`(profiles.require(7L)).thenAnswer {
            assertTrue(read.active)
            events.add("member")
            null
        }
        storeAnswer(store, operation, Supplier {
            assertFalse(read.active)
            events.add("store")
            empty(operation)
        })

        call(ModuleStoreMyPage(profiles, read, store), operation)

        assertEquals(listOf("begin", "member", "commit", "end", "store"), events)
        verify(profiles).require(7L)
        when (operation) {
            "wishlists" -> verify(store).wishlists(7L, StoreMemberReads.Request(0, 20, SORT))
            "orders" -> verify(store).orders(7L, StoreMemberReads.Request(0, 20, SORT))
            else -> verify(store).cart(7L)
        }
        verifyNoMoreInteractions(store)
    }

    @ParameterizedTest
    @ValueSource(strings = ["wishlists", "orders", "cart"])
    fun given__a_missing_current_member__when__reading__then__preserve_user_failure_without_store_call(operation: String) {
        val profiles = mock(UserProfileQuery::class.java)
        val store = mock(StoreMemberReads::class.java)
        val failure = UserNotFoundException()
        `when`(profiles.require(7L)).thenThrow(failure)

        assertSame(
            failure,
            assertThrows(UserNotFoundException::class.java) {
                call(ModuleStoreMyPage(profiles, Read(ArrayList()), store), operation)
            },
        )

        verifyNoInteractions(store)
    }

    @ParameterizedTest
    @CsvSource("begin,cart", "commit,orders")
    fun given__a_user_transaction_failure__when__reading__then__propagate_it_before_any_store_work(phase: String, operation: String) {
        val profiles = mock(UserProfileQuery::class.java)
        val store = mock(StoreMemberReads::class.java)
        val read = Read(ArrayList())
        val failure = IllegalStateException("synthetic user transaction failure")
        read.failure = failure
        read.phase = phase

        assertSame(
            failure,
            assertThrows(IllegalStateException::class.java) { call(ModuleStoreMyPage(profiles, read, store), operation) },
        )

        if (phase == "begin") {
            verifyNoInteractions(profiles)
        } else {
            verify(profiles).require(7L)
        }
        verifyNoInteractions(store)
        assertFalse(read.active)
    }

    @ParameterizedTest
    @ValueSource(strings = ["wishlists", "orders", "cart"])
    fun given__store_is_unavailable__when__reading__then__keep_fixed_user_failure_without_treating_it_as_missing_user(
        operation: String,
    ) {
        val failure = StoreMemberReadUnavailableException(IllegalStateException("synthetic-private-store-detail"))
        val store = mock(StoreMemberReads::class.java)
        storeAnswer(store, operation, Supplier { throw failure })

        val caught = assertThrows(IllegalStateException::class.java) {
            call(ModuleStoreMyPage(unusedProfiles(), Read(ArrayList()), store), operation)
        }

        assertEquals(
            if (operation == "wishlists") {
                "Store wishlist lookup failed"
            } else if (operation == "orders") {
                "Store order lookup failed"
            } else {
                "Store cart lookup failed"
            },
            caught.message,
        )
        assertSame(failure, caught.cause)
        assertFalse(caught.message!!.contains("synthetic-private"))
    }

    @Test
    fun given__full_store_projections__when__mapping__then__keep_only_existing_user_fields_nulls_precision_and_long_values() {
        val store = mock(StoreMemberReads::class.java)
        val time = LocalDateTime.of(2026, 10, 20, 1, 2, 3, 123456000)
        val wish = StoreMemberReads.Wishlist(
            1L, 99L, "not-in-user-subset", 2L, "상품", null, null, "DELETED", Long.MAX_VALUE, mutableMapOf<String?, String?>("색" to "보라"), null, null, time,
        )
        `when`(store.wishlists(7L, StoreMemberReads.Request(0, 20, SORT)))
            .thenReturn(StoreMemberReads.Page(listOf(wish), 0, 20, 1, SORT))
        val item = StoreMemberReads.OrderItem(3L, 4L, null, "snapshot", Long.MAX_VALUE, null)
        `when`(store.orders(7L, StoreMemberReads.Request(0, 20, SORT))).thenReturn(
            StoreMemberReads.Page(
                listOf(StoreMemberReads.Purchase(5L, "order", Long.MAX_VALUE, "CANCELLED", null, null, mutableListOf(item), null)),
                0,
                20,
                1,
                SORT,
            ),
        )
        val cartItem = StoreMemberReads.CartItem(6L, 4L, null, null, 0L, 2, 0L)
        `when`(store.cart(7L)).thenReturn(Optional.of(StoreMemberReads.Cart(8L, 7L, mutableListOf(cartItem), time)))
        val pages = ModuleStoreMyPage(unusedProfiles(), Read(ArrayList()), store)

        assertEquals(
            StoreMyPage.Wishlist(1L, 2L, "상품", null, null, "DELETED", null, null, time),
            pages.wishlists(7L, DEFAULT).content!!.first(),
        )
        assertEquals(
            StoreMyPage.Purchase(
                5L, "order", Long.MAX_VALUE, "CANCELLED", null, null,
                listOf(StoreMyPage.OrderItem(3L, 4L, null, "snapshot", Long.MAX_VALUE, null)), null,
            ),
            pages.orders(7L, DEFAULT).content!!.first(),
        )
        assertEquals(
            StoreMyPage.Cart(8L, 7L, listOf(StoreMyPage.CartItem(6L, 4L, null, null, 0L, 2, 0L)), time),
            pages.cart(7L),
        )
    }

    @ParameterizedTest
    @ValueSource(booleans = [false, true])
    fun given__an_absent_or_present_empty_cart__when__mapping__then__preserve_null_204_versus_a_nonnull_empty_cart(present: Boolean) {
        val store = mock(StoreMemberReads::class.java)
        `when`(store.cart(7L)).thenReturn(
            if (present) Optional.of(StoreMemberReads.Cart(8L, 7L, mutableListOf(), null)) else Optional.empty(),
        )

        val result = ModuleStoreMyPage(unusedProfiles(), Read(ArrayList()), store).cart(7L)

        if (present) {
            assertEquals(StoreMyPage.Cart(8L, 7L, listOf(), null), result)
        } else {
            assertNull(result)
        }
    }

    @Test
    fun given__configured_store_feign_array_sort_and_page_metadata__when__mapping__then__keep_array_branch_orders_and_pageimpl_total_adjustment() {
        val rich = listOf(StoreMemberReads.Order("sku.product.name", true, true))
        val store = mock(StoreMemberReads::class.java)
        val item = StoreMemberReads.Wishlist(1L, 2L, "sku", 3L, null, null, null, null, 4L, mutableMapOf(), null, null, null)
        `when`(store.wishlists(7L, StoreMemberReads.Request(0, 20, SORT)))
            .thenReturn(StoreMemberReads.Page(listOf(item), 2, 7, 14, rich))
        val pages = ModuleStoreMyPage(unusedProfiles(), Read(ArrayList()), store)

        val page = pages.wishlists(7L, DEFAULT)

        assertEquals(2, page.number)
        assertEquals(7, page.size)
        assertEquals(15L, page.totalElements)
        assertEquals(listOf(StoreMyPage.Order("sku.product.name", true, true, "NATIVE")), page.sort)
        `when`(store.wishlists(7L, StoreMemberReads.Request(0, 20, SORT)))
            .thenReturn(StoreMemberReads.Page(listOf(item), 2, 7, 2147483650L, rich))
        assertEquals(2147483650L, pages.wishlists(7L, DEFAULT).totalElements)
    }

    @ParameterizedTest
    @MethodSource("requests")
    fun given__the_existing_single_flattened_sort_parameter__when__converting__then__match_actual_store_spring_pageable_including_lost_direction_boundaries(
        source: StoreMyPage.Request,
    ) {
        val web = mock(NativeWebRequest::class.java)
        `when`(web.getParameter("page")).thenReturn(Integer.toString(source.page))
        `when`(web.getParameter("size")).thenReturn(Integer.toString(source.size))
        `when`(web.getParameterValues("sort")).thenReturn(if (source.sort == null) null else arrayOf(source.sort!!))
        val parameter = MethodParameter(
            ModuleStoreMyPageTest::class.java.getDeclaredMethod("storeEndpoint", Pageable::class.java),
            0,
        )
        val expected: Pageable = PageableHandlerMethodArgumentResolver().resolveArgument(parameter, null, web, null)
        val store = mock(StoreMemberReads::class.java)
        `when`(store.orders(anyLong(), anyNonNull<StoreMemberReads.Request>()))
            .thenReturn(StoreMemberReads.Page(listOf(), 0, 20, 0, listOf()))

        ModuleStoreMyPage(unusedProfiles(), Read(ArrayList()), store).orders(7L, source)

        val orders = expected.sort.stream()
            .map { order -> StoreMemberReads.Order(order.property, order.isAscending, order.isIgnoreCase) }
            .toList()
        verify(store).orders(7L, StoreMemberReads.Request(expected.pageNumber, expected.pageSize, orders))
        if ("id,desc,createdAt,asc" == source.sort) {
            assertEquals(
                listOf(
                    StoreMemberReads.Order("id", true, false),
                    StoreMemberReads.Order("desc", true, false),
                    StoreMemberReads.Order("createdAt", true, false),
                ),
                orders,
            )
        }
    }

    @ParameterizedTest
    @ValueSource(strings = ["null-page", "null-cart-items"])
    fun given__malformed_module_data__when__mapping__then__fail_instead_of_fabricating_empty_state(defect: String) {
        val store = mock(StoreMemberReads::class.java)
        `when`(store.cart(7L)).thenReturn(Optional.of(StoreMemberReads.Cart(8L, 7L, null, null)))
        val pages = ModuleStoreMyPage(unusedProfiles(), Read(ArrayList()), store)

        assertThrows(IllegalStateException::class.java) {
            if (defect == "null-page") {
                pages.orders(7L, DEFAULT)
            } else {
                pages.cart(7L)
            }
        }
    }

    @Suppress("unused")
    private fun storeEndpoint(
        @PageableDefault(size = 20, sort = ["createdAt"], direction = Sort.Direction.DESC) pageable: Pageable,
    ) {
    }

    /** The Java test passed a lambda returning null; the member lookup result is never used by these tests. */
    private fun unusedProfiles(): UserProfileQuery {
        return mock(UserProfileQuery::class.java)
    }

    private fun call(pages: ModuleStoreMyPage, operation: String): Any? {
        return when (operation) {
            "wishlists" -> pages.wishlists(7L, DEFAULT)
            "orders" -> pages.orders(7L, DEFAULT)
            else -> pages.cart(7L)
        }
    }

    private fun empty(operation: String): Any {
        return if (operation == "cart") Optional.empty<Any>() else StoreMemberReads.Page(listOf<Any>(), 0, 20, 0, listOf())
    }

    private fun storeAnswer(store: StoreMemberReads, operation: String, answer: Supplier<Any?>) {
        when (operation) {
            "wishlists" -> `when`(store.wishlists(anyLong(), anyNonNull<StoreMemberReads.Request>()))
                .thenAnswer { answer.get() }
            "orders" -> `when`(store.orders(anyLong(), anyNonNull<StoreMemberReads.Request>()))
                .thenAnswer { answer.get() }
            else -> `when`(store.cart(anyLong())).thenAnswer { answer.get() }
        }
    }

    private class Read(private val events: MutableList<String>) : TransactionOperations {
        var active = false
        var phase: String? = null
        var failure: RuntimeException? = null

        override fun <T> execute(action: TransactionCallback<T>): T? {
            events.add("begin")
            if ("begin" == phase) {
                throw failure!!
            }
            active = true
            try {
                val value = action.doInTransaction(SimpleTransactionStatus())
                if ("commit" == phase) {
                    throw failure!!
                }
                events.add("commit")
                return value
            } finally {
                active = false
                events.add("end")
            }
        }
    }

    companion object {
        private val DEFAULT = StoreMyPage.Request(0, 20, "createdAt,desc")
        private val SORT = listOf(StoreMemberReads.Order("createdAt", false, false))

        @JvmStatic
        fun requests(): Stream<Arguments> = Stream.of(
            StoreMyPage.Request(0, 20, "createdAt,desc"),
            StoreMyPage.Request(3, 7, "id,desc,createdAt,asc"),
            StoreMyPage.Request(0, 20, "sku.product.name,DESC,ignorecase"),
            StoreMyPage.Request(0, 20, "...,,id,aSc"),
            StoreMyPage.Request(0, 20, "."),
            StoreMyPage.Request(0, 20, ""),
            StoreMyPage.Request(-3, -2, null),
            StoreMyPage.Request(7, 9000, "id,asc"),
        ).map { request -> Arguments.of(request) }
    }
}
