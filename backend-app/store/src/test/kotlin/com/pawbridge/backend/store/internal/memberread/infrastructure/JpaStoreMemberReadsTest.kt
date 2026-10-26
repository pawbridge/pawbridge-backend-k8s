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
import io.mockk.Called
import io.mockk.confirmVerified
import io.mockk.every
import io.mockk.mockk
import io.mockk.spyk
import io.mockk.verify
import io.mockk.verifySequence
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
    private val wishlists = mockk<MyWishlistRepository>()
    private val orders = mockk<MyOrderRepository>()
    private val carts = mockk<StoreMemberReads>()
    private val read = Read()
    private val store = JpaStoreMemberReads(wishlists, orders, read, carts)
    private val request = StoreMemberReads.Request(0, 1, listOf(StoreMemberReads.Order("createdAt", false, false)))
    private val created: LocalDateTime = LocalDateTime.of(2026, 10, 20, 3, 4, 5, 123456789)

    @ParameterizedTest
    @MethodSource("sortRequests")
    fun `중첩 또는 별칭 정렬일 때__페이지를 읽으면__원래 저장소에 그대로 위임하고 읽기 트랜잭션 안에서 실행한다`(
        wishlist: Boolean,
        property: String,
    ) {
        // given
        val input = StoreMemberReads.Request(3, 7, listOf(StoreMemberReads.Order(property, true, true)))
        val expected = PageRequest.of(3, 7, Sort.by(Sort.Order(Sort.Direction.ASC, property, true, Sort.NullHandling.NATIVE)))

        if (wishlist) {
            every { wishlists.findIdsByUserId(any(), any()) } answers {
                assertTrue(read.active)
                Page.empty<MyWishlistRepository.IdOnly>(expected)
            }
        } else {
            every { orders.findIdsByUserId(any(), any()) } answers {
                assertTrue(read.active)
                Page.empty<MyOrderRepository.IdOnly>(expected)
            }
        }

        // when
        val result = if (wishlist) store.wishlists(7, input) else store.orders(7, input)

        // then
        assertEquals(StoreMemberReads.Page<Any>(listOf(), 3, 7, 0L, input.sort()), result)
        if (wishlist) {
            verify(exactly = 1) { wishlists.findIdsByUserId(7L, expected) }
            confirmVerified(wishlists)
            verify { orders wasNot Called }
        } else {
            verify(exactly = 1) { orders.findIdsByUserId(7L, expected) }
            confirmVerified(orders)
            verify { wishlists wasNot Called }
        }
        verify { carts wasNot Called }
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
    fun `기존 JDBC 위시리스트 스칼라 정렬일 때__위시리스트 페이지를 읽으면__속성을 보존하고 하나의 읽기 트랜잭션 안에서 위임한다`(
        property: String,
    ) {
        // given
        val input = StoreMemberReads.Request(3, 7, listOf(StoreMemberReads.Order(property, true, false)))
        val expected = PageRequest.of(3, 7, Sort.by(Sort.Order.asc(property)))

        every { wishlists.findIdsByUserId(any(), any()) } answers {
            assertTrue(read.active)
            Page.empty<MyWishlistRepository.IdOnly>(expected)
        }

        // when
        val result = store.wishlists(7, input)

        // then
        assertEquals(StoreMemberReads.Page<Any>(listOf(), 3, 7, 0L, input.sort()), result)
        verify(exactly = 1) { wishlists.findIdsByUserId(7L, expected) }
        confirmVerified(wishlists)
        verify {
            orders wasNot Called
            carts wasNot Called
        }
        assertEquals(1, read.calls)
        assertFalse(read.active)
    }

    @ParameterizedTest(name = "rejected[{index}]")
    @MethodSource("rejectedRequests")
    fun `지원하지 않는 정렬이나 한도를 넘는 페이지 크기일 때__페이지를 읽으면__트랜잭션과 저장소 전에 고정 메시지로 거부한다`(
        wishlist: Boolean,
        input: StoreMemberReads.Request,
    ) {
        // when
        val failure = assertThrows(IllegalArgumentException::class.java) {
            if (wishlist) {
                store.wishlists(7, input)
            } else {
                store.orders(7, input)
            }
        }

        // then
        assertEquals("Store member sort is not supported", failure.message)
        assertEquals(0, read.calls)
        assertFalse(read.active)
        verify {
            wishlists wasNot Called
            orders wasNot Called
            carts wasNot Called
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = [true, false])
    fun `페이지 크기가 한도와 같을 때__페이지를 읽으면__페이지 크기를 보존하고 빈 그래프 조회를 건너뛴다`(wishlist: Boolean) {
        // given
        val input = StoreMemberReads.Request(0, 2000, request.sort())
        val expected = PageRequest.of(0, 2000, Sort.by(Sort.Order.desc("createdAt")))

        if (wishlist) {
            every { wishlists.findIdsByUserId(any(), any()) } answers {
                assertTrue(read.active)
                Page.empty<MyWishlistRepository.IdOnly>(expected)
            }
        } else {
            every { orders.findIdsByUserId(any(), any()) } answers {
                assertTrue(read.active)
                Page.empty<MyOrderRepository.IdOnly>(expected)
            }
        }

        // when
        val result = if (wishlist) store.wishlists(7, input) else store.orders(7, input)

        // then
        assertEquals(StoreMemberReads.Page<Any>(listOf(), 0, 2000, 0L, input.sort()), result)
        if (wishlist) {
            verify(exactly = 1) { wishlists.findIdsByUserId(7L, expected) }
            confirmVerified(wishlists)
            verify { orders wasNot Called }
        } else {
            verify(exactly = 1) { orders.findIdsByUserId(7L, expected) }
            confirmVerified(orders)
            verify { wishlists wasNot Called }
        }
        assertEquals(1, read.calls)
        assertFalse(read.active)
        verify { carts wasNot Called }
    }

    @ParameterizedTest
    @ValueSource(booleans = [true, false])
    fun `허용된 정렬 항목 네 개가 있을 때__페이지를 읽으면__모든 항목을 하나의 읽기 트랜잭션 안에서 보존한다`(
        wishlist: Boolean,
    ) {
        // given
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
            every { wishlists.findIdsByUserId(any(), any()) } answers {
                assertTrue(read.active)
                Page.empty<MyWishlistRepository.IdOnly>(expected)
            }
        } else {
            every { orders.findIdsByUserId(any(), any()) } answers {
                assertTrue(read.active)
                Page.empty<MyOrderRepository.IdOnly>(expected)
            }
        }

        // when
        val result = if (wishlist) store.wishlists(7, input) else store.orders(7, input)

        // then
        assertEquals(sort, result.sort)
        assertEquals(1, read.calls)
        assertFalse(read.active)
        if (wishlist) {
            verify(exactly = 1) { wishlists.findIdsByUserId(7L, expected) }
            verify { orders wasNot Called }
        } else {
            verify(exactly = 1) { orders.findIdsByUserId(7L, expected) }
            verify { wishlists wasNot Called }
        }
        verify { carts wasNot Called }
    }

    @ParameterizedTest
    @ValueSource(booleans = [true, false])
    fun `그래프 루트가 뒤집히고 반복될 때__선택된 부모 페이지를 읽으면__ID 순서를 한 번만 복원하고 트랜잭션 안에서 페이지 정보를 유지한다`(
        wishlist: Boolean,
    ) {
        // given
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

            every { wishlists.findIdsByUserId(any(), any()) } answers {
                assertTrue(read.active)
                PageImpl(listOf(wishlistId { 33L }, wishlistId { 11L }), expected, 9)
            }
            every { wishlists.findByUserIdAndIdIn(any(), any()) } answers {
                assertTrue(read.active)
                listOf(second, first, first)
            }

            // when
            val result = store.wishlists(7, input)

            // then
            assertEquals(selected, result.content.map { it.wishlistId })
            assertEquals(9L, result.totalElements)
            assertEquals(2, result.number)
            assertEquals(2, result.size)
            assertEquals(input.sort(), result.sort)
            verifySequence {
                wishlists.findIdsByUserId(7L, expected)
                wishlists.findByUserIdAndIdIn(7L, selected)
            }
            verify { orders wasNot Called }
        } else {
            val first = Order.builder().userId(7L).orderUuid("first").totalAmount(1L).build()
            id(first, 33L)
            val second = Order.builder().userId(7L).orderUuid("second").totalAmount(2L).build()
            id(second, 11L)

            every { orders.findIdsByUserId(any(), any()) } answers {
                assertTrue(read.active)
                PageImpl(listOf(orderId { 33L }, orderId { 11L }), expected, 9)
            }
            every { orders.findByUserIdAndIdIn(any(), any()) } answers {
                assertTrue(read.active)
                listOf(second, first, first)
            }

            // when
            val result = store.orders(7, input)

            // then
            assertEquals(selected, result.content.map { it.orderId })
            assertEquals(9L, result.totalElements)
            assertEquals(2, result.number)
            assertEquals(2, result.size)
            assertEquals(input.sort(), result.sort)
            verifySequence {
                orders.findIdsByUserId(7L, expected)
                orders.findByUserIdAndIdIn(7L, selected)
            }
            verify { wishlists wasNot Called }
        }
        assertEquals(1, read.calls)
        assertFalse(read.active)
        verify { carts wasNot Called }
    }

    @ParameterizedTest
    @ValueSource(booleans = [true, false])
    fun `부모 ID 페이지가 비어 있을 때__페이지를 읽으면__그래프 조회를 건너뛰고 총계와 요청한 정렬을 보존한다`(
        wishlist: Boolean,
    ) {
        // given
        val input = StoreMemberReads.Request(3, 2, request.sort())
        val expected = PageRequest.of(3, 2, Sort.by(Sort.Order.desc("createdAt")))

        if (wishlist) {
            every { wishlists.findIdsByUserId(any(), any()) } answers {
                assertTrue(read.active)
                PageImpl<MyWishlistRepository.IdOnly>(listOf(), expected, 5)
            }
        } else {
            every { orders.findIdsByUserId(any(), any()) } answers {
                assertTrue(read.active)
                PageImpl<MyOrderRepository.IdOnly>(listOf(), expected, 5)
            }
        }

        // when
        val result = if (wishlist) store.wishlists(7, input) else store.orders(7, input)

        // then
        assertEquals(StoreMemberReads.Page<Any>(listOf(), 3, 2, 5L, input.sort()), result)
        if (wishlist) {
            verify(exactly = 1) { wishlists.findIdsByUserId(7L, expected) }
            confirmVerified(wishlists)
            verify { orders wasNot Called }
        } else {
            verify(exactly = 1) { orders.findIdsByUserId(7L, expected) }
            confirmVerified(orders)
            verify { wishlists wasNot Called }
        }
        assertEquals(1, read.calls)
        assertFalse(read.active)
        verify { carts wasNot Called }
    }

    @ParameterizedTest
    @ValueSource(booleans = [true, false])
    fun `선택한 부모가 그래프에 없을 때__페이지를 읽으면__매핑 전에 고정된 조회 불가 경계로 실패한다`(
        wishlist: Boolean,
    ) {
        // given
        if (wishlist) {
            every { wishlists.findIdsByUserId(any(), any()) } answers {
                assertTrue(read.active)
                PageImpl(listOf(wishlistId { 9L }), secondArg<Pageable>(), 1)
            }
            every { wishlists.findByUserIdAndIdIn(any(), any()) } answers {
                assertTrue(read.active)
                listOf<Wishlist>()
            }
        } else {
            every { orders.findIdsByUserId(any(), any()) } answers {
                assertTrue(read.active)
                PageImpl(listOf(orderId { 9L }), secondArg<Pageable>(), 1)
            }
            every { orders.findByUserIdAndIdIn(any(), any()) } answers {
                assertTrue(read.active)
                listOf<Order>()
            }
        }

        // when
        val failure = assertThrows(StoreMemberReadUnavailableException::class.java) {
            if (wishlist) {
                store.wishlists(7, request)
            } else {
                store.orders(7, request)
            }
        }

        // then
        assertEquals("Store member information could not be read", failure.message)
        assertInstanceOf(PersistenceException::class.java, failure.cause)
        assertEquals("Store member page could not be read", failure.cause!!.message)
        assertEquals(1, read.calls)
        assertFalse(read.active)
        verify { carts wasNot Called }
        if (wishlist) {
            verify(exactly = 1) { wishlists.findIdsByUserId(7L, any()) }
            verify(exactly = 1) { wishlists.findByUserIdAndIdIn(7L, listOf(9L)) }
            verify { orders wasNot Called }
        } else {
            verify(exactly = 1) { orders.findIdsByUserId(7L, any()) }
            verify(exactly = 1) { orders.findByUserIdAndIdIn(7L, listOf(9L)) }
            verify { wishlists wasNot Called }
        }
    }

    @Test
    fun `긴 가격과 반복된 옵션 그룹이 있는 위시리스트일 때__매핑하면__원래 매퍼를 트랜잭션 안에서 돌려 모든 필드를 보존한다`() {
        // given
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
        val wishlist = spyk(Wishlist.builder().userId(7L).sku(sku).build())
        id(wishlist, 9007199254740993L)
        ReflectionTestUtils.setField(wishlist, "createdAt", created)

        every { wishlist.sku } answers {
            assertTrue(read.active)
            sku
        }
        every { wishlists.findIdsByUserId(any(), any()) } answers {
            assertTrue(read.active)
            PageImpl(listOf(wishlistId { wishlist.id }), secondArg<Pageable>(), 4)
        }
        every { wishlists.findByUserIdAndIdIn(any(), any()) } answers {
            assertTrue(read.active)
            listOf(wishlist)
        }

        // when
        val result = store.wishlists(7, request)

        // then
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
        verify(exactly = 1) { wishlists.findIdsByUserId(7L, any()) }
        verify(exactly = 1) { wishlists.findByUserIdAndIdIn(7L, listOf(9007199254740993L)) }
        verify {
            orders wasNot Called
            carts wasNot Called
        }
    }

    @Test
    fun `주문 스냅샷이 현재 상품과 다를 때__매핑하면__스냅샷 긴 값을 유지하고 트랜잭션 안에서 매핑을 끝낸다`() {
        // given
        val product = Product.builder().name("live name").status(ProductStatus.ACTIVE).build()
        val sku = ProductSKU.builder().product(product).skuCode("live sku").price(1L).stockQuantity(0).build()
        id(sku, 80L)
        val order = spyk(
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

        every { order.orderItems } answers {
            assertTrue(read.active)
            items
        }
        every { orders.findIdsByUserId(any(), any()) } answers {
            assertTrue(read.active)
            PageImpl(listOf(orderId { order.id }), secondArg<Pageable>(), 1)
        }
        every { orders.findByUserIdAndIdIn(any(), any()) } answers {
            assertTrue(read.active)
            listOf(order)
        }

        // when
        val result = store.orders(7, request)

        // then
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
        verify(exactly = 1) { orders.findIdsByUserId(7L, any()) }
        verify(exactly = 1) { orders.findByUserIdAndIdIn(7L, listOf(33L)) }
        verify {
            wishlists wasNot Called
            carts wasNot Called
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = [false, true])
    fun `기존 장바구니 결과가 있을 때__장바구니를 읽으면__JPA 트랜잭션 없이 위임한다`(present: Boolean) {
        // given
        val expected: Optional<StoreMemberReads.Cart> = if (present) {
            Optional.of(StoreMemberReads.Cart(9L, 7L, mutableListOf(), null))
        } else {
            Optional.empty()
        }

        every { carts.cart(any()) } returns expected

        // when
        val result = store.cart(7)

        // then
        assertSame(expected, result)
        verify(exactly = 1) { carts.cart(7) }
        confirmVerified(carts)
        verify {
            wishlists wasNot Called
            orders wasNot Called
        }
        assertEquals(0, read.calls)
    }

    @ParameterizedTest
    @MethodSource("failures")
    fun `저장소가 실패했을 때__페이지를 읽으면__JDK 실패 경계를 지키고 원래 원인을 보존한다`(
        wishlist: Boolean,
        failure: RuntimeException,
    ) {
        // given
        if (wishlist) {
            every { wishlists.findIdsByUserId(any(), any()) } throws failure
        } else {
            every { orders.findIdsByUserId(any(), any()) } throws failure
        }

        // when
        val result = assertThrows(StoreMemberReadUnavailableException::class.java) {
            if (wishlist) {
                store.wishlists(7, request)
            } else {
                store.orders(7, request)
            }
        }

        // then
        assertSame(failure, result.cause)
        assertEquals("Store member information could not be read", result.message)
        assertFalse(read.active)
        assertEquals(1, read.calls)
        verify { carts wasNot Called }
    }

    @ParameterizedTest
    @MethodSource("failures")
    fun `선택 그래프 조회가 실패했을 때__페이지를 읽으면__JDK 실패 경계를 지키고 원래 원인을 보존한다`(
        wishlist: Boolean,
        failure: RuntimeException,
    ) {
        // given
        if (wishlist) {
            every { wishlists.findIdsByUserId(any(), any()) } answers {
                assertTrue(read.active)
                PageImpl(listOf(wishlistId { 9L }), secondArg<Pageable>(), 1)
            }
            every { wishlists.findByUserIdAndIdIn(any(), any()) } answers {
                assertTrue(read.active)
                throw failure
            }
        } else {
            every { orders.findIdsByUserId(any(), any()) } answers {
                assertTrue(read.active)
                PageImpl(listOf(orderId { 9L }), secondArg<Pageable>(), 1)
            }
            every { orders.findByUserIdAndIdIn(any(), any()) } answers {
                assertTrue(read.active)
                throw failure
            }
        }

        // when
        val result = assertThrows(StoreMemberReadUnavailableException::class.java) {
            if (wishlist) {
                store.wishlists(7, request)
            } else {
                store.orders(7, request)
            }
        }

        // then
        assertSame(failure, result.cause)
        assertEquals("Store member information could not be read", result.message)
        assertEquals(1, read.calls)
        assertFalse(read.active)
        verify { carts wasNot Called }
        if (wishlist) {
            verify(exactly = 1) { wishlists.findByUserIdAndIdIn(7L, listOf(9L)) }
            verify { orders wasNot Called }
        } else {
            verify(exactly = 1) { orders.findByUserIdAndIdIn(7L, listOf(9L)) }
            verify { wishlists wasNot Called }
        }
    }

    @ParameterizedTest
    @MethodSource("transactionFailures")
    fun `트랜잭션 경계가 실패했을 때__페이지를 읽으면__성공 없이 원래 원인과 함께 전파한다`(
        wishlist: Boolean,
        phase: String,
    ) {
        // given
        val manager = mockk<PlatformTransactionManager>()
        val status = SimpleTransactionStatus()
        val failure: RuntimeException = if (phase == "begin") {
            CannotCreateTransactionException("private begin")
        } else {
            TransactionSystemException("private $phase")
        }
        val candidate = JpaStoreMemberReads(wishlists, orders, TransactionTemplate(manager), carts)

        if (phase == "begin") {
            every { manager.getTransaction(any()) } throws failure
        } else {
            every { manager.getTransaction(any()) } returns status
        }
        if (phase == "commit") {
            every { manager.commit(status) } throws failure
            every { wishlists.findIdsByUserId(any(), any()) } returns
                Page.empty<MyWishlistRepository.IdOnly>(PageRequest.of(0, 1))
            every { orders.findIdsByUserId(any(), any()) } returns
                Page.empty<MyOrderRepository.IdOnly>(PageRequest.of(0, 1))
        }
        if (phase == "rollback") {
            every { wishlists.findIdsByUserId(any(), any()) } throws PersistenceException("private query")
            every { orders.findIdsByUserId(any(), any()) } throws PersistenceException("private query")
            every { manager.rollback(status) } throws failure
        }

        // when
        val result = assertThrows(StoreMemberReadUnavailableException::class.java) {
            if (wishlist) {
                candidate.wishlists(7, request)
            } else {
                candidate.orders(7, request)
            }
        }

        // then
        assertSame(failure, result.cause)
        if (phase == "begin") {
            verify {
                wishlists wasNot Called
                orders wasNot Called
            }
        }
        if (phase == "rollback") {
            verify(exactly = 1) { manager.rollback(status) }
        }
        verify { carts wasNot Called }
    }

    @Test
    fun `지연 연관이 실패했을 때__원래 위시리스트 매퍼가 돌면__트랜잭션 안에서 실패하고 원인을 보존한다`() {
        // given
        val wishlist = mockk<Wishlist>()
        val failure = EntityNotFoundException("private lazy association")

        every { wishlist.id } returns 9L
        every { wishlist.sku } answers {
            assertTrue(read.active)
            throw failure
        }
        every { wishlists.findIdsByUserId(any(), any()) } answers {
            assertTrue(read.active)
            PageImpl(listOf(wishlistId { 9L }), secondArg<Pageable>(), 1)
        }
        every { wishlists.findByUserIdAndIdIn(any(), any()) } answers {
            assertTrue(read.active)
            listOf(wishlist)
        }

        // when
        val result = assertThrows(StoreMemberReadUnavailableException::class.java) { store.wishlists(7, request) }

        // then
        assertSame(failure, result.cause)
        assertFalse(read.active)
        verify(exactly = 1) { wishlists.findIdsByUserId(7L, any()) }
        verify(exactly = 1) { wishlists.findByUserIdAndIdIn(7L, listOf(9L)) }
        verify {
            orders wasNot Called
            carts wasNot Called
        }
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
        fun rejectedRequests(): Stream<Arguments> {
            val defaultSort = listOf(StoreMemberReads.Order("createdAt", false, false))
            val blocked = Stream.of(true, false).flatMap { wishlist ->
                val deep = if (wishlist) "sku.product.category.parent.parent.name" else "orderItems.order.orderItems.price"
                Stream.concat(
                    Stream.of(
                        "unknown", " ", "sum(price)", null, deep, deep.replace('.', '_'),
                        if (wishlist) "skuProductCategoryParentParentName" else "orderItemsOrderOrderItemsPrice",
                        if (wishlist) "orderItems.price" else "sku.price",
                    ).map { property ->
                        Arguments.of(wishlist, StoreMemberReads.Request(0, 2, listOf(StoreMemberReads.Order(property, true, false))))
                    },
                    Stream.of(
                        Arguments.of(
                            wishlist,
                            StoreMemberReads.Request(0, 2, Collections.nCopies(5, StoreMemberReads.Order("createdAt", true, false))),
                        ),
                    ),
                )
            }
            val collection = Stream.concat(
                Stream.of("sku.skuValues.optionValue.name", "sku_skuValues_optionValue_name")
                    .map { property -> Arguments.of(true, property) },
                Stream.of(
                    "orderItems.id", "orderItems.productName", "orderItems.skuCode", "orderItems.price",
                    "orderItems.quantity", "orderItems.productSKU.id", "orderItems.productSKU.skuCode",
                    "orderItems.productSKU.price", "orderItems_price", "orderItems_productSKU_price",
                    "orderItemsProductSKUPrice",
                ).map { property -> Arguments.of(false, property) },
            ).map { args ->
                val property = args.get()[1] as String
                Arguments.of(args.get()[0], StoreMemberReads.Request(0, 2, listOf(StoreMemberReads.Order(property, true, false))))
            }
            val oversized = Stream.of(true, false).map { wishlist ->
                Arguments.of(wishlist, StoreMemberReads.Request(0, 2001, defaultSort))
            }
            return Stream.concat(Stream.concat(blocked, collection), oversized)
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
