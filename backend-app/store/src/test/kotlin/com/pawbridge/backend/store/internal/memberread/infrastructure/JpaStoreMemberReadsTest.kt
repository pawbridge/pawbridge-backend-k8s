package com.pawbridge.backend.store.internal.memberread.infrastructure

import com.pawbridge.backend.store.api.StoreMemberReadUnavailableException
import com.pawbridge.backend.store.api.StoreMemberReads
import com.pawbridge.backend.store.internal.memberread.infrastructure.jpa.model.OptionGroup
import com.pawbridge.backend.store.internal.memberread.infrastructure.jpa.model.OptionValue
import com.pawbridge.backend.store.internal.memberread.infrastructure.jpa.model.Order
import com.pawbridge.backend.store.internal.memberread.infrastructure.jpa.model.OrderItem
import com.pawbridge.backend.store.internal.memberread.infrastructure.jpa.model.Product
import com.pawbridge.backend.store.internal.memberread.infrastructure.jpa.model.ProductSKU
import com.pawbridge.backend.store.internal.memberread.infrastructure.jpa.model.ProductStatus
import com.pawbridge.backend.store.internal.memberread.infrastructure.jpa.model.SKUValue
import com.pawbridge.backend.store.internal.memberread.infrastructure.jpa.model.Wishlist
import com.pawbridge.backend.store.internal.memberread.infrastructure.jpa.repository.MyOrderRepository
import com.pawbridge.backend.store.internal.memberread.infrastructure.jpa.repository.MyWishlistRepository
import jakarta.persistence.EntityNotFoundException
import jakarta.persistence.PersistenceException
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.Arguments
import org.junit.jupiter.params.provider.MethodSource
import org.junit.jupiter.params.provider.ValueSource
import org.mockito.ArgumentMatchers.any
import org.mockito.ArgumentMatchers.eq
import org.mockito.Mockito.doAnswer
import org.mockito.Mockito.doThrow
import org.mockito.Mockito.inOrder
import org.mockito.Mockito.mock
import org.mockito.Mockito.spy
import org.mockito.Mockito.`when`
import org.mockito.Mockito.verify
import org.mockito.Mockito.verifyNoInteractions
import org.mockito.Mockito.verifyNoMoreInteractions
import org.springframework.dao.DataAccessResourceFailureException
import org.springframework.data.domain.Page
import org.springframework.data.domain.PageImpl
import org.springframework.data.domain.PageRequest
import org.springframework.data.domain.Pageable
import org.springframework.data.domain.Sort
import org.springframework.test.util.ReflectionTestUtils
import org.springframework.transaction.CannotCreateTransactionException
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.TransactionSystemException
import org.springframework.transaction.support.SimpleTransactionStatus
import org.springframework.transaction.support.TransactionCallback
import org.springframework.transaction.support.TransactionOperations
import org.springframework.transaction.support.TransactionTemplate
import java.time.LocalDateTime
import java.util.Collections
import java.util.Optional
import java.util.stream.Stream

class JpaStoreMemberReadsTest {
    private val wishlists: MyWishlistRepository = mock(MyWishlistRepository::class.java)
    private val orders: MyOrderRepository = mock(MyOrderRepository::class.java)
    private val carts: StoreMemberReads = mock(StoreMemberReads::class.java)
    private val read = Read()
    private val store = JpaStoreMemberReads(wishlists, orders, read, carts)
    private val request = StoreMemberReads.Request(0, 1, listOf(StoreMemberReads.Order("createdAt", false, false)))
    private val created: LocalDateTime = LocalDateTime.of(2026, 10, 20, 3, 4, 5, 123456789)

    @ParameterizedTest
    @MethodSource("sortRequests")
    fun given__nested_or_alias_sort__when__reading_pages__then__delegate_unchanged_to_the_original_repository_inside_read_transaction(
        wishlist: Boolean,
        property: String,
    ) {
        val input = StoreMemberReads.Request(3, 7, listOf(StoreMemberReads.Order(property, true, true)))
        val expected = PageRequest.of(3, 7, Sort.by(Sort.Order(Sort.Direction.ASC, property, true, Sort.NullHandling.NATIVE)))
        if (wishlist) {
            `when`(wishlists.findIdsByUserId(7L, expected)).thenAnswer {
                assertTrue(read.active)
                Page.empty<MyWishlistRepository.IdOnly>(expected)
            }
        } else {
            `when`(orders.findIdsByUserId(7L, expected)).thenAnswer {
                assertTrue(read.active)
                Page.empty<MyOrderRepository.IdOnly>(expected)
            }
        }

        val result = if (wishlist) store.wishlists(7, input) else store.orders(7, input)

        assertEquals(StoreMemberReads.Page<Any>(listOf(), 3, 7, 0L, input.sort()), result)
        if (wishlist) {
            verify(wishlists).findIdsByUserId(7L, expected)
            verifyNoMoreInteractions(wishlists)
            verifyNoInteractions(orders)
        } else {
            verify(orders).findIdsByUserId(7L, expected)
            verifyNoMoreInteractions(orders)
            verifyNoInteractions(wishlists)
        }
        verifyNoInteractions(carts)
        assertEquals(1, read.calls)
        assertFalse(read.active)
    }

    @ParameterizedTest
    @ValueSource(
        strings = [
            "sku.createdAt", "sku.updatedAt", "sku.product.category.description",
            "sku.product.category.createdAt", "sku.product.category.updatedAt",
        ],
    )
    fun given__legacy_jdbc_wishlist_scalar_sort__when__reading_wishlist_page__then__preserve_property_and_delegate_inside_one_read_transaction(
        property: String,
    ) {
        val input = StoreMemberReads.Request(3, 7, listOf(StoreMemberReads.Order(property, true, false)))
        val expected = PageRequest.of(3, 7, Sort.by(Sort.Order.asc(property)))
        `when`(wishlists.findIdsByUserId(7L, expected)).thenAnswer {
            assertTrue(read.active)
            Page.empty<MyWishlistRepository.IdOnly>(expected)
        }

        val result = store.wishlists(7, input)

        assertEquals(StoreMemberReads.Page<Any>(listOf(), 3, 7, 0L, input.sort()), result)
        verify(wishlists).findIdsByUserId(7L, expected)
        verifyNoMoreInteractions(wishlists)
        verifyNoInteractions(orders, carts)
        assertEquals(1, read.calls)
        assertFalse(read.active)
    }

    @ParameterizedTest(name = "blockedSort[{index}]")
    @MethodSource("blockedSortRequests")
    fun given__unsupported_or_excessive_sort__when__reading_pages__then__reject_with_fixed_message_before_transaction_or_repository(
        wishlist: Boolean,
        sort: List<StoreMemberReads.Order>,
    ) {
        val input = StoreMemberReads.Request(0, 2, sort)

        val failure = assertThrows(IllegalArgumentException::class.java) {
            if (wishlist) {
                store.wishlists(7, input)
            } else {
                store.orders(7, input)
            }
        }

        assertEquals("Store member sort is not supported", failure.message)
        assertEquals(0, read.calls)
        assertFalse(read.active)
        verifyNoInteractions(wishlists, orders, carts)
    }

    @ParameterizedTest
    @MethodSource("collectionSortRequests")
    fun given__new_collection_sort_path__when__reading_parent_pages__then__reject_with_fixed_message_before_transaction_or_repository(
        wishlist: Boolean,
        property: String,
    ) {
        val input = StoreMemberReads.Request(0, 2, listOf(StoreMemberReads.Order(property, true, false)))

        val failure = assertThrows(IllegalArgumentException::class.java) {
            if (wishlist) {
                store.wishlists(7, input)
            } else {
                store.orders(7, input)
            }
        }

        assertEquals("Store member sort is not supported", failure.message)
        assertEquals(0, read.calls)
        assertFalse(read.active)
        verifyNoInteractions(wishlists, orders, carts)
    }

    @ParameterizedTest
    @ValueSource(booleans = [true, false])
    fun given__page_size_above_bound__when__reading_pages__then__reject_with_fixed_message_before_transaction_or_repository(
        wishlist: Boolean,
    ) {
        val input = StoreMemberReads.Request(0, 2001, request.sort())

        val failure = assertThrows(IllegalArgumentException::class.java) {
            if (wishlist) {
                store.wishlists(7, input)
            } else {
                store.orders(7, input)
            }
        }

        assertEquals("Store member sort is not supported", failure.message)
        assertEquals(0, read.calls)
        assertFalse(read.active)
        verifyNoInteractions(wishlists, orders, carts)
    }

    @ParameterizedTest
    @ValueSource(booleans = [true, false])
    fun given__page_size_at_bound__when__reading_pages__then__preserve_page_size_and_skip_empty_graph(wishlist: Boolean) {
        val input = StoreMemberReads.Request(0, 2000, request.sort())
        val expected = PageRequest.of(0, 2000, Sort.by(Sort.Order.desc("createdAt")))
        if (wishlist) {
            `when`(wishlists.findIdsByUserId(7L, expected)).thenAnswer {
                assertTrue(read.active)
                Page.empty<MyWishlistRepository.IdOnly>(expected)
            }
        } else {
            `when`(orders.findIdsByUserId(7L, expected)).thenAnswer {
                assertTrue(read.active)
                Page.empty<MyOrderRepository.IdOnly>(expected)
            }
        }

        val result = if (wishlist) store.wishlists(7, input) else store.orders(7, input)

        assertEquals(StoreMemberReads.Page<Any>(listOf(), 0, 2000, 0L, input.sort()), result)
        if (wishlist) {
            verify(wishlists).findIdsByUserId(7L, expected)
            verifyNoMoreInteractions(wishlists)
            verifyNoInteractions(orders)
        } else {
            verify(orders).findIdsByUserId(7L, expected)
            verifyNoMoreInteractions(orders)
            verifyNoInteractions(wishlists)
        }
        assertEquals(1, read.calls)
        assertFalse(read.active)
        verifyNoInteractions(carts)
    }

    @ParameterizedTest
    @ValueSource(booleans = [true, false])
    fun given__four_allowed_sort_terms__when__reading_pages__then__preserve_every_term_inside_one_read_transaction(
        wishlist: Boolean,
    ) {
        val sort = listOf(
            StoreMemberReads.Order("id", true, false),
            StoreMemberReads.Order("userId", false, false),
            StoreMemberReads.Order("createdAt", true, false),
            StoreMemberReads.Order("updatedAt", false, false),
        )
        val input = StoreMemberReads.Request(0, 2, sort)
        val expected = PageRequest.of(
            0,
            2,
            Sort.by(Sort.Order.asc("id"), Sort.Order.desc("userId"), Sort.Order.asc("createdAt"), Sort.Order.desc("updatedAt")),
        )
        if (wishlist) {
            `when`(wishlists.findIdsByUserId(7L, expected)).thenAnswer {
                assertTrue(read.active)
                Page.empty<MyWishlistRepository.IdOnly>(expected)
            }
        } else {
            `when`(orders.findIdsByUserId(7L, expected)).thenAnswer {
                assertTrue(read.active)
                Page.empty<MyOrderRepository.IdOnly>(expected)
            }
        }

        val result = if (wishlist) store.wishlists(7, input) else store.orders(7, input)

        assertEquals(sort, result.sort)
        assertEquals(1, read.calls)
        assertFalse(read.active)
        if (wishlist) {
            verify(wishlists).findIdsByUserId(7L, expected)
            verifyNoInteractions(orders)
        } else {
            verify(orders).findIdsByUserId(7L, expected)
            verifyNoInteractions(wishlists)
        }
        verifyNoInteractions(carts)
    }

    @ParameterizedTest
    @ValueSource(booleans = [true, false])
    fun given__reversed_and_repeated_graph_roots__when__reading_selected_parent_page__then__restore_id_order_once_and_keep_page_metadata_inside_transaction(
        wishlist: Boolean,
    ) {
        val input = StoreMemberReads.Request(2, 2, request.sort())
        val expected = PageRequest.of(2, 2, Sort.by(Sort.Order.desc("createdAt")))
        val selected = listOf(33L, 11L)
        if (wishlist) {
            val product = Product.builder().name("stored name").status(ProductStatus.ACTIVE).build()
            id(product, 90L)
            val sku = ProductSKU.builder().product(product).skuCode("stored sku").price(1L).stockQuantity(1).build()
            id(sku, 80L)
            val first = Wishlist.builder().userId(7L).sku(sku).build()
            id(first, 33L)
            val second = Wishlist.builder().userId(7L).sku(sku).build()
            id(second, 11L)
            `when`(wishlists.findIdsByUserId(7L, expected)).thenAnswer {
                assertTrue(read.active)
                PageImpl(listOf(wishlistId { 33L }, wishlistId { 11L }), expected, 9)
            }
            `when`(wishlists.findByUserIdAndIdIn(7L, selected)).thenAnswer {
                assertTrue(read.active)
                listOf(second, first, first)
            }

            val result = store.wishlists(7, input)

            assertEquals(selected, result.content.map { it.wishlistId })
            assertEquals(9L, result.totalElements)
            assertEquals(2, result.number)
            assertEquals(2, result.size)
            assertEquals(input.sort(), result.sort)
            val sequence = inOrder(wishlists)
            sequence.verify(wishlists).findIdsByUserId(7L, expected)
            sequence.verify(wishlists).findByUserIdAndIdIn(7L, selected)
            verifyNoMoreInteractions(wishlists)
            verifyNoInteractions(orders)
        } else {
            val first = Order.builder().userId(7L).orderUuid("first").totalAmount(1L).build()
            id(first, 33L)
            val second = Order.builder().userId(7L).orderUuid("second").totalAmount(2L).build()
            id(second, 11L)
            `when`(orders.findIdsByUserId(7L, expected)).thenAnswer {
                assertTrue(read.active)
                PageImpl(listOf(orderId { 33L }, orderId { 11L }), expected, 9)
            }
            `when`(orders.findByUserIdAndIdIn(7L, selected)).thenAnswer {
                assertTrue(read.active)
                listOf(second, first, first)
            }

            val result = store.orders(7, input)

            assertEquals(selected, result.content.map { it.orderId })
            assertEquals(9L, result.totalElements)
            assertEquals(2, result.number)
            assertEquals(2, result.size)
            assertEquals(input.sort(), result.sort)
            val sequence = inOrder(orders)
            sequence.verify(orders).findIdsByUserId(7L, expected)
            sequence.verify(orders).findByUserIdAndIdIn(7L, selected)
            verifyNoMoreInteractions(orders)
            verifyNoInteractions(wishlists)
        }
        assertEquals(1, read.calls)
        assertFalse(read.active)
        verifyNoInteractions(carts)
    }

    @ParameterizedTest
    @ValueSource(booleans = [true, false])
    fun given__empty_parent_id_page__when__reading_pages__then__skip_graph_and_preserve_total_and_requested_sort(
        wishlist: Boolean,
    ) {
        val input = StoreMemberReads.Request(3, 2, request.sort())
        val expected = PageRequest.of(3, 2, Sort.by(Sort.Order.desc("createdAt")))
        if (wishlist) {
            `when`(wishlists.findIdsByUserId(7L, expected)).thenAnswer {
                assertTrue(read.active)
                PageImpl<MyWishlistRepository.IdOnly>(listOf(), expected, 5)
            }
        } else {
            `when`(orders.findIdsByUserId(7L, expected)).thenAnswer {
                assertTrue(read.active)
                PageImpl<MyOrderRepository.IdOnly>(listOf(), expected, 5)
            }
        }

        val result = if (wishlist) store.wishlists(7, input) else store.orders(7, input)

        assertEquals(StoreMemberReads.Page<Any>(listOf(), 3, 2, 5L, input.sort()), result)
        if (wishlist) {
            verify(wishlists).findIdsByUserId(7L, expected)
            verifyNoMoreInteractions(wishlists)
            verifyNoInteractions(orders)
        } else {
            verify(orders).findIdsByUserId(7L, expected)
            verifyNoMoreInteractions(orders)
            verifyNoInteractions(wishlists)
        }
        assertEquals(1, read.calls)
        assertFalse(read.active)
        verifyNoInteractions(carts)
    }

    @ParameterizedTest
    @ValueSource(booleans = [true, false])
    fun given__selected_parent_missing_from_graph__when__reading_pages__then__fail_with_fixed_unavailable_boundary_before_mapping(
        wishlist: Boolean,
    ) {
        if (wishlist) {
            `when`(wishlists.findIdsByUserId(eq(7L), any<Pageable>())).thenAnswer { call ->
                assertTrue(read.active)
                PageImpl(listOf(wishlistId { 9L }), call.getArgument<Pageable>(1), 1)
            }
            `when`(wishlists.findByUserIdAndIdIn(7L, listOf(9L))).thenAnswer {
                assertTrue(read.active)
                listOf<Wishlist>()
            }
        } else {
            `when`(orders.findIdsByUserId(eq(7L), any<Pageable>())).thenAnswer { call ->
                assertTrue(read.active)
                PageImpl(listOf(orderId { 9L }), call.getArgument<Pageable>(1), 1)
            }
            `when`(orders.findByUserIdAndIdIn(7L, listOf(9L))).thenAnswer {
                assertTrue(read.active)
                listOf<Order>()
            }
        }

        val failure = assertThrows(StoreMemberReadUnavailableException::class.java) {
            if (wishlist) {
                store.wishlists(7, request)
            } else {
                store.orders(7, request)
            }
        }

        assertEquals("Store member information could not be read", failure.message)
        assertInstanceOf(PersistenceException::class.java, failure.cause)
        assertEquals("Store member page could not be read", failure.cause!!.message)
        assertEquals(1, read.calls)
        assertFalse(read.active)
        verifyNoInteractions(carts)
        if (wishlist) {
            verifyNoInteractions(orders)
        } else {
            verifyNoInteractions(wishlists)
        }
    }

    @Test
    fun given__wishlist_with_long_price_and_repeated_option_group__when__mapping__then__run_original_mapper_inside_transaction_and_preserve_all_fields() {
        val product = Product.builder().name("stored name").status(ProductStatus.DELETED).build()
        id(product, 90L)
        val sku = ProductSKU.builder().product(product).skuCode("stored sku").price(Long.MAX_VALUE).stockQuantity(0).build()
        id(sku, 80L)
        val group = OptionGroup.builder().name("color").build()
        sku.skuValues.add(
            SKUValue.builder().productSKU(sku)
                .optionValue(OptionValue.builder().optionGroup(group).name("red").build()).build(),
        )
        sku.skuValues.add(
            SKUValue.builder().productSKU(sku)
                .optionValue(OptionValue.builder().optionGroup(group).name("blue").build()).build(),
        )
        val wishlist = spy(Wishlist.builder().userId(7L).sku(sku).build())
        id(wishlist, 9007199254740993L)
        ReflectionTestUtils.setField(wishlist, "createdAt", created)
        doAnswer {
            assertTrue(read.active)
            sku
        }.`when`(wishlist).sku
        `when`(wishlists.findIdsByUserId(eq(7L), any<Pageable>())).thenAnswer { call ->
            assertTrue(read.active)
            PageImpl(listOf(wishlistId { wishlist.id }), call.getArgument<Pageable>(1), 4)
        }
        `when`(wishlists.findByUserIdAndIdIn(7L, listOf(9007199254740993L))).thenAnswer {
            assertTrue(read.active)
            listOf(wishlist)
        }

        val result = store.wishlists(7, request)

        assertEquals(
            listOf(
                StoreMemberReads.Wishlist(
                    9007199254740993L, 80L, "stored sku", 90L, "stored name", null, null,
                    "DELETED", Long.MAX_VALUE, mutableMapOf<String?, String?>("color" to "blue"), null, null, created,
                ),
            ),
            result.content,
        )
        assertEquals(4L, result.totalElements)
        assertEquals(request.sort(), result.sort)
        assertEquals(1, read.calls)
        assertFalse(read.active)
        verifyNoInteractions(orders, carts)
    }

    @Test
    fun given__order_snapshot_different_from_live_product__when__mapping__then__keep_snapshot_long_values_and_complete_mapping_inside_transaction() {
        val product = Product.builder().name("live name").status(ProductStatus.ACTIVE).build()
        val sku = ProductSKU.builder().product(product).skuCode("live sku").price(1L).stockQuantity(0).build()
        id(sku, 80L)
        val order = spy(
            Order.builder()
                .orderUuid("uuid").userId(7L).totalAmount(9007199254740993L).deliveryAddress("address")
                .receiverName("receiver").receiverPhone("fixture").build(),
        )
        id(order, 33L)
        order.cancelOrder()
        val item = OrderItem.builder()
            .order(order).productSKU(sku).productName("purchase name").skuCode("purchase sku")
            .price(Long.MAX_VALUE).quantity(2).build()
        id(item, 44L)
        val items = mutableListOf(item)
        doAnswer {
            assertTrue(read.active)
            items
        }.`when`(order).orderItems
        `when`(orders.findIdsByUserId(eq(7L), any<Pageable>())).thenAnswer { call ->
            assertTrue(read.active)
            PageImpl(listOf(orderId { order.id }), call.getArgument<Pageable>(1), 1)
        }
        `when`(orders.findByUserIdAndIdIn(7L, listOf(33L))).thenAnswer {
            assertTrue(read.active)
            listOf(order)
        }

        val result = store.orders(7, request)

        assertEquals(
            listOf(
                StoreMemberReads.Purchase(
                    33L, "uuid", 9007199254740993L, "CANCELLED", "address", "READY",
                    mutableListOf(StoreMemberReads.OrderItem(44L, 80L, "purchase name", "purchase sku", Long.MAX_VALUE, 2)),
                    null,
                ),
            ),
            result.content,
        )
        assertEquals(1, read.calls)
        assertFalse(read.active)
        verifyNoInteractions(wishlists, carts)
    }

    @ParameterizedTest
    @ValueSource(booleans = [false, true])
    fun given__existing_cart_result__when__reading_cart__then__delegate_without_a_jpa_transaction(present: Boolean) {
        val expected: Optional<StoreMemberReads.Cart> = if (present) {
            Optional.of(StoreMemberReads.Cart(9L, 7L, mutableListOf(), null))
        } else {
            Optional.empty()
        }
        `when`(carts.cart(7)).thenReturn(expected)

        assertSame(expected, store.cart(7))

        verify(carts).cart(7)
        verifyNoMoreInteractions(carts)
        verifyNoInteractions(wishlists, orders)
        assertEquals(0, read.calls)
    }

    @ParameterizedTest
    @MethodSource("failures")
    fun given__repository_failure__when__reading_pages__then__keep_jdk_failure_boundary_and_original_cause(
        wishlist: Boolean,
        failure: RuntimeException,
    ) {
        if (wishlist) {
            doThrow(failure).`when`(wishlists).findIdsByUserId(eq(7L), any<Pageable>())
        } else {
            doThrow(failure).`when`(orders).findIdsByUserId(eq(7L), any<Pageable>())
        }

        val result = assertThrows(StoreMemberReadUnavailableException::class.java) {
            if (wishlist) {
                store.wishlists(7, request)
            } else {
                store.orders(7, request)
            }
        }

        assertSame(failure, result.cause)
        assertEquals("Store member information could not be read", result.message)
        assertFalse(read.active)
        assertEquals(1, read.calls)
        verifyNoInteractions(carts)
    }

    @ParameterizedTest
    @MethodSource("failures")
    fun given__selected_graph_failure__when__reading_pages__then__keep_jdk_failure_boundary_and_original_cause(
        wishlist: Boolean,
        failure: RuntimeException,
    ) {
        if (wishlist) {
            `when`(wishlists.findIdsByUserId(eq(7L), any<Pageable>())).thenAnswer { call ->
                assertTrue(read.active)
                PageImpl(listOf(wishlistId { 9L }), call.getArgument<Pageable>(1), 1)
            }
            doAnswer {
                assertTrue(read.active)
                throw failure
            }.`when`(wishlists).findByUserIdAndIdIn(7L, listOf(9L))
        } else {
            `when`(orders.findIdsByUserId(eq(7L), any<Pageable>())).thenAnswer { call ->
                assertTrue(read.active)
                PageImpl(listOf(orderId { 9L }), call.getArgument<Pageable>(1), 1)
            }
            doAnswer {
                assertTrue(read.active)
                throw failure
            }.`when`(orders).findByUserIdAndIdIn(7L, listOf(9L))
        }

        val result = assertThrows(StoreMemberReadUnavailableException::class.java) {
            if (wishlist) {
                store.wishlists(7, request)
            } else {
                store.orders(7, request)
            }
        }

        assertSame(failure, result.cause)
        assertEquals("Store member information could not be read", result.message)
        assertEquals(1, read.calls)
        assertFalse(read.active)
        verifyNoInteractions(carts)
        if (wishlist) {
            verifyNoInteractions(orders)
        } else {
            verifyNoInteractions(wishlists)
        }
    }

    @ParameterizedTest
    @MethodSource("transactionFailures")
    fun given__transaction_boundary_failure__when__reading_pages__then__propagate_with_original_cause_without_success(
        wishlist: Boolean,
        phase: String,
    ) {
        val manager = mock(PlatformTransactionManager::class.java)
        val status = SimpleTransactionStatus()
        `when`(manager.getTransaction(any())).thenReturn(status)
        val failure: RuntimeException = if (phase == "begin") {
            CannotCreateTransactionException("private begin")
        } else {
            TransactionSystemException("private $phase")
        }
        if (phase == "begin") {
            doThrow(failure).`when`(manager).getTransaction(any())
        } else if (phase == "commit") {
            doThrow(failure).`when`(manager).commit(status)
        } else {
            doThrow(PersistenceException("private query")).`when`(wishlists).findIdsByUserId(eq(7L), any<Pageable>())
            doThrow(PersistenceException("private query")).`when`(orders).findIdsByUserId(eq(7L), any<Pageable>())
            doThrow(failure).`when`(manager).rollback(status)
        }
        if (phase == "commit") {
            `when`(wishlists.findIdsByUserId(eq(7L), any<Pageable>()))
                .thenReturn(Page.empty<MyWishlistRepository.IdOnly>(PageRequest.of(0, 1)))
            `when`(orders.findIdsByUserId(eq(7L), any<Pageable>()))
                .thenReturn(Page.empty<MyOrderRepository.IdOnly>(PageRequest.of(0, 1)))
        }
        val candidate = JpaStoreMemberReads(wishlists, orders, TransactionTemplate(manager), carts)

        val result = assertThrows(StoreMemberReadUnavailableException::class.java) {
            if (wishlist) {
                candidate.wishlists(7, request)
            } else {
                candidate.orders(7, request)
            }
        }

        assertSame(failure, result.cause)
        if (phase == "begin") {
            verifyNoInteractions(wishlists, orders)
        }
        if (phase == "rollback") {
            verify(manager).rollback(status)
        }
        verifyNoInteractions(carts)
    }

    @Test
    fun given__lazy_association_failure__when__original_wishlist_mapper_runs__then__fail_inside_transaction_and_preserve_cause() {
        val wishlist = mock(Wishlist::class.java)
        val failure = EntityNotFoundException("private lazy association")
        `when`(wishlist.id).thenReturn(9L)
        `when`(wishlist.sku).thenAnswer {
            assertTrue(read.active)
            throw failure
        }
        `when`(wishlists.findIdsByUserId(eq(7L), any<Pageable>())).thenAnswer { call ->
            assertTrue(read.active)
            PageImpl(listOf(wishlistId { 9L }), call.getArgument<Pageable>(1), 1)
        }
        `when`(wishlists.findByUserIdAndIdIn(7L, listOf(9L))).thenAnswer {
            assertTrue(read.active)
            listOf(wishlist)
        }

        val result = assertThrows(StoreMemberReadUnavailableException::class.java) { store.wishlists(7, request) }

        assertSame(failure, result.cause)
        assertFalse(read.active)
        verifyNoInteractions(orders, carts)
    }

    private class Read : TransactionOperations {
        var active = false
        var calls = 0

        override fun <T> execute(action: TransactionCallback<T>): T? {
            calls++
            active = true
            try {
                return action.doInTransaction(SimpleTransactionStatus())
            } finally {
                active = false
            }
        }
    }

    companion object {
        @JvmStatic
        fun sortRequests(): Stream<Arguments> = Stream.of(
            Arguments.of(true, "skuPrice"),
            Arguments.of(true, "sku_price"),
            Arguments.of(true, "sku.product.category.parent.name"),
            Arguments.of(false, "orderUuid"),
            Arguments.of(false, "receiverName"),
        )

        @JvmStatic
        fun blockedSortRequests(): Stream<Arguments> {
            return Stream.of(true, false).flatMap { wishlist ->
                val deep = if (wishlist) "sku.product.category.parent.parent.name" else "orderItems.order.orderItems.price"
                Stream.concat(
                    Stream.of(
                        "unknown", " ", "sum(price)", null, deep, deep.replace('.', '_'),
                        if (wishlist) "skuProductCategoryParentParentName" else "orderItemsOrderOrderItemsPrice",
                        if (wishlist) "orderItems.price" else "sku.price",
                    ).map { property -> Arguments.of(wishlist, listOf(StoreMemberReads.Order(property, true, false))) },
                    Stream.of(
                        Arguments.of(wishlist, Collections.nCopies(5, StoreMemberReads.Order("createdAt", true, false))),
                    ),
                )
            }
        }

        @JvmStatic
        fun collectionSortRequests(): Stream<Arguments> {
            return Stream.concat(
                Stream.of("sku.skuValues.optionValue.name", "sku_skuValues_optionValue_name")
                    .map { property -> Arguments.of(true, property) },
                Stream.of(
                    "orderItems.id", "orderItems.productName", "orderItems.skuCode", "orderItems.price",
                    "orderItems.quantity", "orderItems.productSKU.id", "orderItems.productSKU.skuCode",
                    "orderItems.productSKU.price", "orderItems_price", "orderItems_productSKU_price",
                    "orderItemsProductSKUPrice",
                ).map { property -> Arguments.of(false, property) },
            )
        }

        @JvmStatic
        fun failures(): Stream<Arguments> {
            return Stream.of(true, false).flatMap { wishlist ->
                Stream.of(
                    Arguments.of(wishlist, DataAccessResourceFailureException("private SQL")),
                    Arguments.of(wishlist, TransactionSystemException("private transaction")),
                    Arguments.of(wishlist, PersistenceException("private persistence")),
                )
            }
        }

        @JvmStatic
        fun transactionFailures(): Stream<Arguments> {
            return Stream.of(true, false).flatMap { wishlist ->
                Stream.of("begin", "commit", "rollback").map { phase -> Arguments.of(wishlist, phase) }
            }
        }

        private fun id(entity: Any, id: Long) {
            ReflectionTestUtils.setField(entity, "id", id)
        }

        private fun wishlistId(value: () -> Long?): MyWishlistRepository.IdOnly {
            return object : MyWishlistRepository.IdOnly {
                override val id: Long?
                    get() = value()
            }
        }

        private fun orderId(value: () -> Long?): MyOrderRepository.IdOnly {
            return object : MyOrderRepository.IdOnly {
                override val id: Long?
                    get() = value()
            }
        }
    }
}
