package com.pawbridge.backend.contract

import com.pawbridge.backend.store.internal.cart.application.CartActorRejectedException
import com.pawbridge.backend.store.internal.cart.application.Carts
import com.pawbridge.backend.store.internal.directorder.application.CartOrders
import jakarta.persistence.PersistenceException
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired

/** The cart adapter and the cart checkout adapter (JPA) on the real store tables. */
class StoreCartJpaContractTest : JpaContractBase() {
    @Autowired
    lateinit var carts: Carts

    @Autowired
    lateinit var checkout: CartOrders

    @BeforeEach
    fun seed() {
        seedMember(ME)
        seedMember(OTHER)
        seedMember(NO_CART)
        seedSellable(PRODUCT_A, SKU_A, stock = 5, price = 1_000)
        seedProduct(PRODUCT_B, "상품 B")
        seedSku(SKU_B, PRODUCT_B, price = 2_500, stock = 3)
    }

    private fun cartItems(user: Long): Map<Long, Int> {
        val rows = ContractPostgres.admin.queryForList(
            "SELECT i.sku_id, i.quantity FROM pawbridge_store.cart_items i JOIN pawbridge_store.carts c ON c.id = i.cart_id " +
                "WHERE c.user_id = ?",
            user,
        )
        return rows.associate { (it["sku_id"] as Long) to (it["quantity"] as Int) }
    }

    private fun cartCount(user: Long) = count("SELECT count(*) FROM pawbridge_store.carts WHERE user_id = ?", user)

    private fun command() = CartOrders.Command("수령인", "010-0000-0000", "합성 주소 1", "문 앞")

    @Test
    fun `같은 SKU를 두 번 담았을 때__담고 조회하면__장바구니와 항목은 하나이고 수량이 합쳐지며 합계를 준다`() {
        // given
        carts.add(ME, SKU_A, 1)

        // when
        carts.add(ME, SKU_A, 2)
        val items = carts.get(ME)

        // then
        assertThat(cartCount(ME)).isEqualTo(1)
        assertThat(cartItems(ME)).isEqualTo(mapOf(SKU_A to 3))
        assertThat(items.single().let { listOf(it.skuId, it.skuCode, it.productName, it.price, it.quantity, it.totalPrice) })
            .isEqualTo(listOf(SKU_A, "SKU-$SKU_A", "상품 $PRODUCT_A", 1_000L, 3, 3_000L))
    }

    @Test
    fun `재고보다 많거나 없는 SKU이거나 수량이 넘칠 때__담으면__거부하고 장바구니를 만들지 않는다`() {
        // given
        // when
        // then
        assertThatThrownBy { carts.add(ME, SKU_A, 6) }.isInstanceOf(IllegalArgumentException::class.java).hasMessage("Not enough stock")
        assertThatThrownBy { carts.add(ME, 99_999_999L, 1) }.isInstanceOf(IllegalArgumentException::class.java).hasMessage("SKU not found")
        assertThatThrownBy { carts.add(ME, SKU_A, 0) }.isInstanceOf(IllegalArgumentException::class.java)
        assertThat(cartCount(ME)).isZero()

        carts.add(ME, SKU_A, 1)
        execute("UPDATE pawbridge_store.cart_items SET quantity = ?", Int.MAX_VALUE)
        assertThatThrownBy { carts.add(ME, SKU_A, 1) }.isInstanceOf(IllegalArgumentException::class.java).hasMessage("Cart quantity is out of range")
        assertThat(cartItems(ME)).isEqualTo(mapOf(SKU_A to Int.MAX_VALUE))
    }

    @Test
    fun `SKU가 사라진 항목이 있을 때__조회하면__남은 SKU만 준다`() {
        // given
        carts.add(ME, SKU_A, 1)
        execute(
            "INSERT INTO pawbridge_store.cart_items(cart_id, sku_id, product_sku_id, quantity) " +
                "SELECT id, 99999999, 99999999, 4 FROM pawbridge_store.carts WHERE user_id = ?",
            ME,
        )

        // when
        val items = carts.get(ME)

        // then
        assertThat(items.map { it.skuId }).isEqualTo(listOf(SKU_A))
    }

    @Test
    fun `내 장바구니와 남의 장바구니가 있을 때__수정하고 제거하고 비우면__내 장바구니만 바뀐다`() {
        // given
        carts.add(ME, SKU_A, 1)
        carts.add(ME, SKU_B, 2)
        carts.add(OTHER, SKU_A, 4)

        // when
        carts.update(ME, SKU_A, 5)
        val afterUpdate = cartItems(ME)
        carts.remove(ME, SKU_A)
        val afterRemove = cartItems(ME)
        carts.update(ME, SKU_B, 0)
        val afterZero = cartItems(ME)
        carts.add(ME, SKU_A, 1)
        carts.clear(ME)

        // then
        assertThat(afterUpdate).isEqualTo(mapOf(SKU_A to 5, SKU_B to 2))
        assertThat(afterRemove).`as`("Only the removed SKU goes").isEqualTo(mapOf(SKU_B to 2))
        assertThat(afterZero).`as`("Zero quantity removes the item").isEmpty()
        assertThat(cartItems(ME)).isEmpty()
        assertThat(cartCount(ME)).`as`("The cart row stays").isEqualTo(1)
        assertThat(cartItems(OTHER)).isEqualTo(mapOf(SKU_A to 4))
        assertThatThrownBy { carts.update(ME, SKU_B, 1) }.isInstanceOf(IllegalArgumentException::class.java).hasMessage("Item not in cart")
        carts.remove(NO_CART, SKU_A)
        assertThat(cartCount(NO_CART)).`as`("Removing from a missing cart creates none").isZero()
    }

    @Test
    fun `없는 회원과 탈퇴가 시작된 회원일 때__장바구니를 쓰거나 읽으면__행위자 거부이고 행은 그대로다`() {
        // given
        seedDeletingMember(LEAVING)

        // when
        // then
        for (user in listOf(UNKNOWN, LEAVING)) {
            assertThatThrownBy { carts.add(user, SKU_A, 1) }.isInstanceOf(CartActorRejectedException::class.java)
            assertThatThrownBy { carts.get(user) }.isInstanceOf(CartActorRejectedException::class.java)
        }
        assertThat(count("SELECT count(*) FROM pawbridge_store.carts")).isZero()
    }

    @Test
    fun `장바구니에 SKU 둘이 있을 때__주문하면__주문과 구매 당시 항목을 저장하고 재고를 줄이고 장바구니를 비운다`() {
        // given
        carts.add(ME, SKU_A, 2)
        carts.add(ME, SKU_B, 1)
        carts.add(OTHER, SKU_A, 1)

        // when
        val order = checkout.create(ME, command())

        // then
        assertThat(order.totalAmount).isEqualTo(2 * 1_000L + 2_500L)
        assertThat(order.status).isEqualTo("PENDING")
        assertThat(order.items!!.map { Triple(it.skuCode, it.price, it.quantity) }.toSet())
            .isEqualTo(setOf(Triple("SKU-$SKU_A", 1_000L, 2), Triple("SKU-$SKU_B", 2_500L, 1)))
        assertThat(orderField(order.orderId!!, "user_id")).isEqualTo(ME.toString())
        assertThat(count("SELECT count(*) FROM pawbridge_store.order_items WHERE order_id = ?", order.orderId!!)).isEqualTo(2)
        assertThat(stockOf(SKU_A)).isEqualTo(3)
        assertThat(stockOf(SKU_B)).isEqualTo(2)
        assertThat(cartItems(ME)).isEmpty()
        assertThat(cartItems(OTHER)).`as`("Another member's cart is not consumed").isEqualTo(mapOf(SKU_A to 1))
        assertThat(searchDocumentCount(PRODUCT_A)).`as`("The search snapshot follows the stock change").isEqualTo(1)
    }

    @Test
    fun `장바구니가 없거나 비었을 때__주문하면__빈 장바구니 오류이고 주문은 없다`() {
        // given
        carts.add(ME, SKU_A, 1)
        carts.remove(ME, SKU_A)

        // when
        // then
        assertThatThrownBy { checkout.create(ME, command()) }.isInstanceOf(IllegalArgumentException::class.java).hasMessage("Cart is empty")
        assertThatThrownBy { checkout.create(OTHER, command()) }.isInstanceOf(IllegalArgumentException::class.java).hasMessage("Cart is empty")
        assertThat(count("SELECT count(*) FROM pawbridge_store.orders")).isZero()
    }

    @Test
    fun `한 SKU의 재고가 모자랄 때__주문하면__재고 부족으로 실패하고 모든 쓰기가 롤백된다`() {
        // given
        carts.add(ME, SKU_A, 2)
        carts.add(ME, SKU_B, 1)
        execute("UPDATE pawbridge_store.product_skus SET stock_quantity = 0 WHERE id = ?", SKU_B)

        // when
        // then
        assertThatThrownBy { checkout.create(ME, command()) }.isInstanceOf(IllegalStateException::class.java)
        assertThat(count("SELECT count(*) FROM pawbridge_store.orders")).isZero()
        assertThat(stockOf(SKU_A)).`as`("The first SKU's decrease is rolled back too").isEqualTo(5)
        assertThat(cartItems(ME)).isEqualTo(mapOf(SKU_A to 2, SKU_B to 1))
    }

    @Test
    fun `사라진 SKU가 섞여 있을 때__주문하면__남은 SKU만 구매하고 장바구니 전체를 비운다`() {
        // given
        carts.add(ME, SKU_A, 1)
        execute(
            "INSERT INTO pawbridge_store.cart_items(cart_id, sku_id, product_sku_id, quantity) " +
                "SELECT id, 99999999, 99999999, 4 FROM pawbridge_store.carts WHERE user_id = ?",
            ME,
        )

        // when
        val order = checkout.create(ME, command())

        // then
        assertThat(order.items!!.map { it.skuCode }).isEqualTo(listOf("SKU-$SKU_A"))
        assertThat(cartItems(ME)).isEmpty()
    }

    @Test
    fun `같은 SKU가 장바구니에 두 행일 때__주문하면__닫힌 채로 실패하고 아무것도 바꾸지 않는다`() {
        // given
        carts.add(ME, SKU_A, 1)
        execute(
            "INSERT INTO pawbridge_store.cart_items(cart_id, sku_id, product_sku_id, quantity) " +
                "SELECT id, ?, ?, 1 FROM pawbridge_store.carts WHERE user_id = ?",
            SKU_A, SKU_A, ME,
        )

        // when
        // then
        assertThatThrownBy { checkout.create(ME, command()) }.isInstanceOf(PersistenceException::class.java)
        assertThat(count("SELECT count(*) FROM pawbridge_store.orders")).isZero()
        assertThat(stockOf(SKU_A)).isEqualTo(5)
        assertThat(count("SELECT count(*) FROM pawbridge_store.cart_items")).isEqualTo(2)
    }

    @Test
    fun `없는 회원일 때__주문하면__행위자 거부이고 장바구니도 재고도 그대로다`() {
        // given
        seedDeletingMember(LEAVING)
        execute("INSERT INTO pawbridge_store.carts(user_id) VALUES (?)", LEAVING)
        execute(
            "INSERT INTO pawbridge_store.cart_items(cart_id, sku_id, product_sku_id, quantity) " +
                "SELECT id, ?, ?, 1 FROM pawbridge_store.carts WHERE user_id = ?",
            SKU_A, SKU_A, LEAVING,
        )

        // when
        // then
        assertThatThrownBy { checkout.create(LEAVING, command()) }
            .isInstanceOf(com.pawbridge.backend.store.internal.directorder.application.OrderActorRejectedException::class.java)
        assertThat(stockOf(SKU_A)).isEqualTo(5)
        assertThat(cartItems(LEAVING)).isEqualTo(mapOf(SKU_A to 1))
    }

    private companion object {
        const val ME = 7_100_001L
        const val OTHER = 7_100_002L
        const val LEAVING = 7_100_003L
        const val NO_CART = 7_100_004L
        const val UNKNOWN = 7_100_099L
        const val PRODUCT_A = 7_100_010L
        const val SKU_A = 7_100_011L
        const val PRODUCT_B = 7_100_020L
        const val SKU_B = 7_100_021L
    }
}
