package com.pawbridge.backend.contract

import com.pawbridge.backend.store.internal.wishlist.infrastructure.JdbcWishlistCommands
import java.time.Clock
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneOffset
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

/** The wishlist writes on the real tables: add with duplicate and missing-SKU branches, and the two removals. */
class StoreWishlistRepositoryContractTest : StoreRepositoryContractBase() {
    private lateinit var wishlists: JdbcWishlistCommands

    @BeforeEach
    fun build() {
        wishlists = JdbcWishlistCommands(writeJdbc(), writeTransaction(), Clock.fixed(Instant.parse("2026-03-05T01:02:03.456789Z"), ZoneOffset.UTC))
        seedProduct(10, "사료")
        seedSku(100, 10, price = 3_000)
        seedOption(100, 1, "맛", 1, "연어")
        seedProduct(11, "숨김", status = "HIDDEN")
        seedSku(110, 11, stock = 0)
    }

    private fun rows(user: Long) = count("SELECT count(*) FROM pawbridge_store.wishlists WHERE user_id = ?", user)

    @Test
    fun `SKU가 있을 때__찜하면__행을 쓰고 SKU와 옵션을 채운 결과를 주며 숨김 상품과 품절도 허용한다`() {
        // given
        // when
        val added = wishlists.add(ME, 100)
        val hidden = wishlists.add(ME, 110)

        // then
        assertThat(added.userId).isEqualTo(ME)
        assertThat(added.productName).isEqualTo("사료")
        assertThat(added.price).isEqualTo(3_000)
        assertThat(added.options).isEqualTo(mapOf("맛" to "연어"))
        assertThat(added.createdAt).isEqualTo(LocalDateTime.of(2026, 3, 5, 1, 2, 3, 456_789_000))
        assertThat(scalar("SELECT created_at = updated_at FROM pawbridge_store.wishlists WHERE id = ?", added.wishlistId)).isEqualTo("t")
        assertThat(hidden.skuId).isEqualTo(110)
        assertThat(rows(ME)).isEqualTo(2)
    }

    @Test
    fun `이미 찜했거나 없는 SKU거나 SKU가 비었을 때__찜하면__거부하고 행을 만들지 않으며 같은 SKU도 다른 회원은 찜할 수 있다`() {
        // given
        wishlists.add(ME, 100)

        // when
        // then
        assertThatThrownBy { wishlists.add(ME, 100) }.isInstanceOf(IllegalStateException::class.java).hasMessage("이미 찜한 상품입니다.")
        assertThatThrownBy { wishlists.add(ME, 999) }.isInstanceOf(IllegalArgumentException::class.java)
        assertThatThrownBy { wishlists.add(ME, null) }.isInstanceOf(IllegalArgumentException::class.java)
        assertThat(rows(ME)).isOne()
        wishlists.add(OTHER, 100)
        assertThat(rows(OTHER)).isOne()
    }

    @Test
    fun `내 찜이 하나 있을 때__회원과 SKU로 지우면__그 행만 지우고 없으면 예외이며 중복 행이 있으면 아무것도 지우지 않는다`() {
        // given
        seedWishlist(1, ME, 100)
        seedWishlist(2, OTHER, 100)
        seedWishlist(3, OTHER, 110)
        seedWishlist(4, OTHER, 110)

        // when
        wishlists.removeByMemberAndSku(ME, 100)

        // then
        assertThat(rows(ME)).isZero()
        assertThat(rows(OTHER)).isEqualTo(3)
        assertThatThrownBy { wishlists.removeByMemberAndSku(ME, 100) }.isInstanceOf(IllegalArgumentException::class.java)
        assertThatThrownBy { wishlists.removeByMemberAndSku(OTHER, 110) }.isInstanceOf(IllegalStateException::class.java)
        assertThat(rows(OTHER)).`as`("Duplicate rows are never silently deleted").isEqualTo(3)
    }

    @Test
    fun `내 찜과 남의 찜이 있을 때__번호로 지우면__내 행만 지우고 남의 행과 없는 번호는 조용히 지나간다`() {
        // given
        seedWishlist(1, ME, 100)
        seedWishlist(2, OTHER, 100)

        // when
        wishlists.removeById(ME, 2)
        wishlists.removeById(ME, 999)
        wishlists.removeById(ME, 1)
        wishlists.removeById(ME, 1)

        // then
        assertThat(rows(ME)).isZero()
        assertThat(rows(OTHER)).isOne()
    }

    private companion object {
        const val ME = 9_932_001L
        const val OTHER = 9_932_002L
    }
}
