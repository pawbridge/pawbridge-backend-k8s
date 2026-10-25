package com.pawbridge.backend.user.internal.storemypage.infrastructure

import com.pawbridge.backend.store.api.StoreMemberReadUnavailableException
import com.pawbridge.backend.store.api.StoreMemberReads
import com.pawbridge.backend.user.internal.profile.application.UserNotFoundException
import com.pawbridge.backend.user.internal.profile.application.UserProfile
import com.pawbridge.backend.user.internal.profile.application.UserProfileQuery
import com.pawbridge.backend.user.internal.storemypage.application.StoreMyPage
import io.mockk.confirmVerified
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
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
import java.util.stream.Stream

class ModuleStoreMyPageTest {
    @ParameterizedTest
    @ValueSource(strings = ["wishlists", "orders", "cart"])
    fun `현재 회원이 있을 때__위시리스트와 주문과 장바구니를 읽으면__사용자 트랜잭션을 끝낸 뒤 그 회원 번호로 스토어를 호출한다`(
        operation: String,
    ) {
        // given
        val events = ArrayList<String>()
        val read = Read(events)
        val profiles = mockk<UserProfileQuery>()
        val store = mockk<StoreMemberReads>()

        every { profiles.require(any()) } answers {
            assertTrue(read.active)
            events.add("member")
            member()
        }
        when (operation) {
            "wishlists" -> every { store.wishlists(any(), any()) } answers {
                assertFalse(read.active)
                events.add("store")
                StoreMemberReads.Page(listOf(), 0, 20, 0, listOf())
            }
            "orders" -> every { store.orders(any(), any()) } answers {
                assertFalse(read.active)
                events.add("store")
                StoreMemberReads.Page(listOf(), 0, 20, 0, listOf())
            }
            else -> every { store.cart(any()) } answers {
                assertFalse(read.active)
                events.add("store")
                Optional.empty()
            }
        }

        // when
        call(ModuleStoreMyPage(profiles, read, store), operation)

        // then
        assertEquals(listOf("begin", "member", "commit", "end", "store"), events)
        verify(exactly = 1) { profiles.require(7L) }
        when (operation) {
            "wishlists" -> verify(exactly = 1) { store.wishlists(7L, StoreMemberReads.Request(0, 20, SORT)) }
            "orders" -> verify(exactly = 1) { store.orders(7L, StoreMemberReads.Request(0, 20, SORT)) }
            else -> verify(exactly = 1) { store.cart(7L) }
        }
        confirmVerified(store)
    }

    @ParameterizedTest
    @ValueSource(strings = ["wishlists", "orders", "cart"])
    fun `현재 회원이 없을 때__읽으면__스토어를 호출하지 않고 사용자 실패를 그대로 전달한다`(operation: String) {
        // given
        val profiles = mockk<UserProfileQuery>()
        val store = mockk<StoreMemberReads>()
        val failure = UserNotFoundException()

        every { profiles.require(any()) } throws failure

        // when
        val thrown = assertThrows(UserNotFoundException::class.java) {
            call(ModuleStoreMyPage(profiles, Read(ArrayList()), store), operation)
        }

        // then
        assertSame(failure, thrown)
        confirmVerified(store)
    }

    @ParameterizedTest
    @CsvSource("begin,cart", "commit,orders")
    fun `사용자 트랜잭션이 실패할 때__읽으면__스토어 작업 전에 그대로 전파한다`(phase: String, operation: String) {
        // given
        val profiles = mockk<UserProfileQuery>()
        val store = mockk<StoreMemberReads>()
        val read = Read(ArrayList())
        val failure = IllegalStateException("synthetic user transaction failure")
        read.failure = failure
        read.phase = phase

        if (phase == "commit") {
            every { profiles.require(any()) } returns member()
        }

        // when
        val thrown = assertThrows(IllegalStateException::class.java) {
            call(ModuleStoreMyPage(profiles, read, store), operation)
        }

        // then
        assertSame(failure, thrown)
        if (phase == "begin") {
            confirmVerified(profiles)
        } else {
            verify(exactly = 1) { profiles.require(7L) }
        }
        confirmVerified(store)
        assertFalse(read.active)
    }

    @ParameterizedTest
    @ValueSource(strings = ["wishlists", "orders", "cart"])
    fun `스토어를 쓸 수 없을 때__읽으면__없는 회원으로 취급하지 않고 고정된 사용자 실패를 유지한다`(
        operation: String,
    ) {
        // given
        val failure = StoreMemberReadUnavailableException(IllegalStateException("synthetic-private-store-detail"))
        val profiles = mockk<UserProfileQuery>()
        val store = mockk<StoreMemberReads>()

        every { profiles.require(any()) } returns member()
        when (operation) {
            "wishlists" -> every { store.wishlists(any(), any()) } throws failure
            "orders" -> every { store.orders(any(), any()) } throws failure
            else -> every { store.cart(any()) } throws failure
        }

        // when
        val caught = assertThrows(IllegalStateException::class.java) {
            call(ModuleStoreMyPage(profiles, Read(ArrayList()), store), operation)
        }

        // then
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
    fun `스토어 투영이 모든 필드를 가질 때__매핑하면__기존 사용자 필드와 null과 정밀도와 긴 값만 유지한다`() {
        // given
        val profiles = mockk<UserProfileQuery>()
        val store = mockk<StoreMemberReads>()
        val time = LocalDateTime.of(2026, 10, 20, 1, 2, 3, 123456000)
        val wish = StoreMemberReads.Wishlist(
            1L, 99L, "not-in-user-subset", 2L, "상품", null, null, "DELETED", Long.MAX_VALUE, mutableMapOf<String?, String?>("색" to "보라"), null, null, time,
        )
        val item = StoreMemberReads.OrderItem(3L, 4L, null, "snapshot", Long.MAX_VALUE, null)
        val cartItem = StoreMemberReads.CartItem(6L, 4L, null, null, 0L, 2, 0L)
        val pages = ModuleStoreMyPage(profiles, Read(ArrayList()), store)

        every { profiles.require(any()) } returns member()
        every { store.wishlists(any(), any()) } returns StoreMemberReads.Page(listOf(wish), 0, 20, 1, SORT)
        every { store.orders(any(), any()) } returns StoreMemberReads.Page(
            listOf(StoreMemberReads.Purchase(5L, "order", Long.MAX_VALUE, "CANCELLED", null, null, mutableListOf(item), null)),
            0,
            20,
            1,
            SORT,
        )
        every { store.cart(any()) } returns Optional.of(StoreMemberReads.Cart(8L, 7L, mutableListOf(cartItem), time))

        // when
        val wishlist = pages.wishlists(7L, DEFAULT).content!!.first()
        val purchase = pages.orders(7L, DEFAULT).content!!.first()
        val cart = pages.cart(7L)

        // then
        assertEquals(StoreMyPage.Wishlist(1L, 2L, "상품", null, null, "DELETED", null, null, time), wishlist)
        assertEquals(
            StoreMyPage.Purchase(
                5L, "order", Long.MAX_VALUE, "CANCELLED", null, null,
                listOf(StoreMyPage.OrderItem(3L, 4L, null, "snapshot", Long.MAX_VALUE, null)), null,
            ),
            purchase,
        )
        assertEquals(
            StoreMyPage.Cart(8L, 7L, listOf(StoreMyPage.CartItem(6L, 4L, null, null, 0L, 2, 0L)), time),
            cart,
        )
        verify(exactly = 1) { store.wishlists(7L, StoreMemberReads.Request(0, 20, SORT)) }
        verify(exactly = 1) { store.orders(7L, StoreMemberReads.Request(0, 20, SORT)) }
        verify(exactly = 1) { store.cart(7L) }
    }

    @ParameterizedTest
    @ValueSource(booleans = [false, true])
    fun `장바구니가 없거나 비어 있을 때__매핑하면__null 204와 null이 아닌 빈 장바구니를 구분해 보존한다`(present: Boolean) {
        // given
        val profiles = mockk<UserProfileQuery>()
        val store = mockk<StoreMemberReads>()

        every { profiles.require(any()) } returns member()
        every { store.cart(any()) } returns
            if (present) Optional.of(StoreMemberReads.Cart(8L, 7L, mutableListOf(), null)) else Optional.empty()

        // when
        val result = ModuleStoreMyPage(profiles, Read(ArrayList()), store).cart(7L)

        // then
        if (present) {
            assertEquals(StoreMyPage.Cart(8L, 7L, listOf(), null), result)
        } else {
            assertNull(result)
        }
        verify(exactly = 1) { store.cart(7L) }
    }

    @Test
    fun `스토어 feign 배열 정렬과 페이지 메타데이터가 설정돼 있을 때__매핑하면__배열 분기와 정렬 순서와 PageImpl 총계 보정을 유지한다`() {
        // given
        val profiles = mockk<UserProfileQuery>()
        val store = mockk<StoreMemberReads>()
        val rich = listOf(StoreMemberReads.Order("sku.product.name", true, true))
        val item = StoreMemberReads.Wishlist(1L, 2L, "sku", 3L, null, null, null, null, 4L, mutableMapOf(), null, null, null)
        val pages = ModuleStoreMyPage(profiles, Read(ArrayList()), store)

        every { profiles.require(any()) } returns member()
        every { store.wishlists(any(), any()) } returnsMany listOf(
            StoreMemberReads.Page(listOf(item), 2, 7, 14, rich),
            StoreMemberReads.Page(listOf(item), 2, 7, 2147483650L, rich),
        )

        // when
        val page = pages.wishlists(7L, DEFAULT)
        val largeTotal = pages.wishlists(7L, DEFAULT)

        // then
        assertEquals(2, page.number)
        assertEquals(7, page.size)
        assertEquals(15L, page.totalElements)
        assertEquals(listOf(StoreMyPage.Order("sku.product.name", true, true, "NATIVE")), page.sort)
        assertEquals(2147483650L, largeTotal.totalElements)
        verify(exactly = 2) { store.wishlists(7L, StoreMemberReads.Request(0, 20, SORT)) }
    }

    @ParameterizedTest
    @MethodSource("requests")
    fun `기존 단일 평탄화 정렬 파라미터일 때__변환하면__경계에서 방향이 사라지는 경우까지 실제 스토어 스프링 pageable과 일치한다`(
        source: StoreMyPage.Request,
    ) {
        // given
        val web = mockk<NativeWebRequest>()
        val profiles = mockk<UserProfileQuery>()
        val store = mockk<StoreMemberReads>()
        val parameter = MethodParameter(
            ModuleStoreMyPageTest::class.java.getDeclaredMethod("storeEndpoint", Pageable::class.java),
            0,
        )

        every { web.getParameter("page") } returns Integer.toString(source.page)
        every { web.getParameter("size") } returns Integer.toString(source.size)
        every { web.getParameterValues("sort") } returns if (source.sort == null) null else arrayOf(source.sort!!)
        every { profiles.require(any()) } returns member()
        every { store.orders(any(), any()) } returns StoreMemberReads.Page(listOf(), 0, 20, 0, listOf())

        val expected: Pageable = PageableHandlerMethodArgumentResolver().resolveArgument(parameter, null, web, null)

        // when
        ModuleStoreMyPage(profiles, Read(ArrayList()), store).orders(7L, source)

        // then
        val orders = expected.sort.stream()
            .map { order -> StoreMemberReads.Order(order.property, order.isAscending, order.isIgnoreCase) }
            .toList()
        verify(exactly = 1) { store.orders(7L, StoreMemberReads.Request(expected.pageNumber, expected.pageSize, orders)) }
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
    fun `모듈 데이터가 잘못됐을 때__매핑하면__빈 상태를 꾸며내지 않고 실패한다`(defect: String) {
        // given
        val profiles = mockk<UserProfileQuery>()
        val store = mockk<StoreMemberReads>()
        val pages = ModuleStoreMyPage(profiles, Read(ArrayList()), store)

        every { profiles.require(any()) } returns member()
        // The module contract is non-null, so a null page can only be simulated by returning null from the mock.
        every { store.orders(any(), any()) } returns nullValue()
        every { store.cart(any()) } returns Optional.of(StoreMemberReads.Cart(8L, 7L, null, null))

        // when
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

    /** The member lookup result is never used by these tests; any valid profile will do. */
    private fun member(): UserProfile {
        return UserProfile(7L, "member@fixture.invalid", "회원", "닉네임", "LOCAL", "ROLE_USER", null)
    }

    @Suppress("UNCHECKED_CAST")
    private fun <T> nullValue(): T {
        return null as T
    }

    private fun call(pages: ModuleStoreMyPage, operation: String): Any? {
        return when (operation) {
            "wishlists" -> pages.wishlists(7L, DEFAULT)
            "orders" -> pages.orders(7L, DEFAULT)
            else -> pages.cart(7L)
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
