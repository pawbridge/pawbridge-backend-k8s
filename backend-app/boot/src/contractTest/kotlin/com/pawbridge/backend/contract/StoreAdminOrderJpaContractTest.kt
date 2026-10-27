package com.pawbridge.backend.contract

import com.pawbridge.backend.store.internal.orderadministration.application.AdminOrderNotFoundException
import com.pawbridge.backend.store.internal.orderadministration.application.AdminOrders
import com.pawbridge.backend.store.internal.orderadministration.application.AdministratorRejectedException
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired

/** The administrator order adapter (JPA): search, read and the two status changes, on the real store tables. */
class StoreAdminOrderJpaContractTest : JpaContractBase() {
    @Autowired
    lateinit var admin: AdminOrders

    @BeforeEach
    fun seed() {
        seedMember(ADMIN, "ROLE_ADMIN")
        seedMember(CUSTOMER)
        seedMember(OTHER)
        seedDeletingMember(LEAVING_ADMIN)
        execute("UPDATE pawbridge_user.users SET role = 'ROLE_ADMIN' WHERE user_id = ?", LEAVING_ADMIN)
        seedSellable(PRODUCT, SKU, stock = 9)
        seedOrder(ORDER_A, CUSTOMER, "alpha-uuid", status = "PAID", createdAt = "2026-03-01 00:00:01")
        seedOrder(ORDER_B, CUSTOMER, "beta-uuid", status = "PENDING", createdAt = "2026-03-01 00:00:02")
        seedOrder(ORDER_C, OTHER, "gamma-uuid", status = "PAID", createdAt = "2026-03-01 00:00:03")
        execute("UPDATE pawbridge_store.orders SET receiver_name = 'Kim Tester' WHERE id = ?", ORDER_C)
        execute("UPDATE pawbridge_store.orders SET delivery_status = 'SHIPPING' WHERE id = ?", ORDER_A)
        execute(
            "INSERT INTO pawbridge_store.order_items(order_id, product_sku_id, product_name, sku_code, price, quantity) " +
                "VALUES (?, ?, '항목', 'C-1', 500, 3), (?, ?, '항목', 'C-1', 500, 1)",
            ORDER_A, SKU, ORDER_B, SKU,
        )
    }

    private fun query(
        status: String? = null,
        delivery: String? = null,
        user: Long? = null,
        keyword: String? = null,
        sortBy: String = "id",
        sortOrder: String = "asc",
        page: Int = 0,
        size: Int = 10,
    ) = AdminOrders.Query(status, delivery, user, keyword, sortBy, sortOrder, page, size)

    private fun ids(page: AdminOrders.Page) = page.content!!.map { it.orderId }

    @Test
    fun `여러 회원의 주문이 있을 때__조건을 바꿔 목록을 읽으면__조건대로 거르고 정렬하고 쪼개며 항목을 붙인다`() {
        // given
        // when
        val all = admin.list(ADMIN, query())
        val newest = admin.list(ADMIN, query(sortBy = "createdAt", sortOrder = "desc", size = 2))
        val tail = admin.list(ADMIN, query(sortBy = "createdAt", sortOrder = "desc", size = 2, page = 1))

        // then
        assertThat(ids(all)).isEqualTo(listOf(ORDER_A, ORDER_B, ORDER_C))
        assertThat(all.totalElements).isEqualTo(3)
        assertThat(all.content!!.first().items!!.map { it.quantity }).isEqualTo(listOf(3))
        assertThat(ids(newest)).isEqualTo(listOf(ORDER_C, ORDER_B))
        assertThat(ids(tail)).isEqualTo(listOf(ORDER_A))
        assertThat(ids(admin.list(ADMIN, query(status = "PAID")))).isEqualTo(listOf(ORDER_A, ORDER_C))
        assertThat(ids(admin.list(ADMIN, query(delivery = "SHIPPING")))).isEqualTo(listOf(ORDER_A))
        assertThat(ids(admin.list(ADMIN, query(user = CUSTOMER)))).isEqualTo(listOf(ORDER_A, ORDER_B))
        assertThat(ids(admin.list(ADMIN, query(keyword = "BETA")))).`as`("The order UUID, ignoring case").isEqualTo(listOf(ORDER_B))
        assertThat(ids(admin.list(ADMIN, query(keyword = "kim t")))).`as`("The receiver name").isEqualTo(listOf(ORDER_C))
        assertThat(ids(admin.list(ADMIN, query(keyword = "   ")))).`as`("A blank keyword filters nothing").hasSize(3)
        assertThat(ids(admin.list(ADMIN, query(status = "PAID", user = OTHER, keyword = "gamma")))).isEqualTo(listOf(ORDER_C))
        assertThatThrownBy { admin.list(ADMIN, query(sortBy = "password")) }.isInstanceOf(IllegalArgumentException::class.java)
    }

    @Test
    fun `주문이 있을 때__하나를 읽으면__항목과 함께 주고 없으면 찾을 수 없음이다`() {
        // given
        // when
        val order = admin.get(ADMIN, ORDER_A)

        // then
        assertThat(order.userId).isEqualTo(CUSTOMER)
        assertThat(order.items!!.single().let { Triple(it.skuCode, it.price, it.quantity) }).isEqualTo(Triple("C-1", 500L, 3))
        assertThatThrownBy { admin.get(ADMIN, 99_999_999L) }.isInstanceOf(AdminOrderNotFoundException::class.java)
    }

    @Test
    fun `주문이 있을 때__배송 상태와 주문 상태를 바꾸면__바꾼 열만 달라진다`() {
        // given
        val before = row("pawbridge_store.orders", "id", ORDER_B)

        // when
        val shipped = admin.updateDelivery(ADMIN, ORDER_B, "SHIPPING")
        val afterDelivery = row("pawbridge_store.orders", "id", ORDER_B)
        val paid = admin.updateStatus(ADMIN, ORDER_B, "PAID")
        val afterStatus = row("pawbridge_store.orders", "id", ORDER_B)

        // then
        assertThat(shipped.deliveryStatus).isEqualTo("SHIPPING")
        assertThat(paid.status).isEqualTo("PAID")
        assertThat(afterDelivery.get("delivery_status").asText()).isEqualTo("SHIPPING")
        assertThat(omitting(afterDelivery, "delivery_status", "updated_at")).isEqualTo(omitting(before, "delivery_status", "updated_at"))
        assertThat(afterStatus.get("status").asText()).isEqualTo("PAID")
        assertThat(omitting(afterStatus, "status", "updated_at")).isEqualTo(omitting(afterDelivery, "status", "updated_at"))
        assertThat(orderField(ORDER_A, "status")).`as`("Another order is untouched").isEqualTo("PAID")
        assertThatThrownBy { admin.updateStatus(ADMIN, 99_999_999L, "PAID") }.isInstanceOf(IllegalArgumentException::class.java)
        assertThatThrownBy { admin.updateStatus(ADMIN, ORDER_B, "NOT_A_STATUS") }.isInstanceOf(IllegalArgumentException::class.java)
        val unchanged = row("pawbridge_store.orders", "id", ORDER_B)
        assertThatThrownBy { admin.updateStatus(ADMIN, ORDER_B, null) }.isInstanceOf(IllegalStateException::class.java)
        assertThatThrownBy { admin.updateDelivery(ADMIN, ORDER_B, null) }.isInstanceOf(IllegalStateException::class.java)
        assertThat(row("pawbridge_store.orders", "id", ORDER_B)).`as`("A null value changes nothing").isEqualTo(unchanged)
    }

    @Test
    fun `일반 회원이거나 탈퇴가 시작된 관리자이거나 없는 회원일 때__무엇이든 하면__관리자 거부이고 주문은 그대로다`() {
        // given
        val before = ContractPostgres.snapshot()

        // when
        // then
        for (user in listOf(CUSTOMER, LEAVING_ADMIN, UNKNOWN)) {
            assertThatThrownBy { admin.list(user, query()) }.isInstanceOf(AdministratorRejectedException::class.java)
            assertThatThrownBy { admin.get(user, ORDER_A) }.isInstanceOf(AdministratorRejectedException::class.java)
            assertThatThrownBy { admin.updateStatus(user, ORDER_A, "CANCELLED") }.isInstanceOf(AdministratorRejectedException::class.java)
            assertThatThrownBy { admin.updateDelivery(user, ORDER_A, "DELIVERED") }.isInstanceOf(AdministratorRejectedException::class.java)
        }
        assertThat(ContractPostgres.snapshot()).isEqualTo(before)
    }

    private companion object {
        const val ADMIN = 7_300_101L
        const val CUSTOMER = 7_300_102L
        const val OTHER = 7_300_103L
        const val LEAVING_ADMIN = 7_300_104L
        const val UNKNOWN = 7_300_199L
        const val PRODUCT = 7_300_010L
        const val SKU = 7_300_011L
        const val ORDER_A = 7_300_201L
        const val ORDER_B = 7_300_202L
        const val ORDER_C = 7_300_203L
    }
}
