package com.pawbridge.backend.contract

import com.pawbridge.backend.store.internal.catalog.application.CatalogSearchUnavailableException
import com.pawbridge.backend.store.internal.catalog.application.StoreCatalog
import com.pawbridge.backend.store.internal.catalog.application.StoreCatalog.Search
import com.pawbridge.backend.store.internal.catalog.infrastructure.JdbcStoreCatalog
import com.pawbridge.backend.store.internal.catalog.infrastructure.KoreanSearchTerms
import com.pawbridge.backend.store.internal.directorder.infrastructure.JdbcProductSearchRebuild
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

/** The store catalog queries (search, product, categories, options) and the search document recovery on the real tables. */
class StoreCatalogRepositoryContractTest : StoreRepositoryContractBase() {
    private lateinit var catalog: JdbcStoreCatalog
    private val rebuildTerms = KoreanSearchTerms()

    @BeforeEach
    fun build() {
        catalog = JdbcStoreCatalog(readJdbc(), readTransaction())
        seedCategory(1, "사료")
        seedCategory(2, "간식", parent = 1, description = "하위")
        seedProduct(10, "연어 사료", category = 1, createdAt = "2026-03-01 00:00:01")
        seedSku(100, 10, price = 3_000, stock = 2)
        seedSku(101, 10, price = 2_000, stock = 0)
        seedOption(101, 1, "맛", 1, "연어")
        seedProduct(11, "닭고기 간식", category = 2, createdAt = "2026-03-01 00:00:02")
        seedSku(110, 11, price = 5_000, stock = 4)
        seedProduct(12, "숨김 상품", status = "HIDDEN", category = 1)
        seedSku(120, 12, price = 1_000)
        seedProduct(13, "재고 없는 장난감", createdAt = "2026-03-01 00:00:03")
        seedSku(130, 13, price = 7_000, stock = 0)
        seedProduct(14, "SKU 없는 상품")
    }

    @AfterEach
    fun close() {
        catalog.close()
        rebuildTerms.close()
    }

    private fun search(
        keyword: String? = null, min: Long? = null, max: Long? = null, category: Long? = null, inStock: Boolean? = null,
        sortBy: String? = null, sortOrder: String? = null, page: Int? = null, size: Int? = null,
    ) = catalog.search(Search(keyword, min, max, category, inStock, sortBy, sortOrder, page, size))

    @Test
    fun `공개 상품과 숨긴 상품과 SKU 없는 상품이 섞여 있을 때__조건 없이 검색하면__활성 상품의 가장 싼 SKU 하나와 전체 재고를 준다`() {
        // given
        // when
        val result = search()

        // then
        assertThat(result.items!!.map { it.skuId }).`as`("skuId descending by default").isEqualTo(listOf(130L, 110L, 101L))
        assertThat(result.totalCount).isEqualTo(3)
        val salmon = result.items!!.single { it.id == 10L }
        assertThat(salmon.price).`as`("The cheapest SKU represents the product").isEqualTo(2_000)
        assertThat(salmon.optionName).isEqualTo("맛: 연어")
        assertThat(salmon.totalStock).`as`("Stock sums every SKU").isEqualTo(2)
        assertThat(result.hasNext).isFalse()
    }

    @Test
    fun `가격과 분류와 재고 조건과 정렬과 쪽을 줄 때__검색하면__조건대로 거르고 자르며 총계를 센다`() {
        // given
        // when
        val byPrice = search(min = 3_000, max = 6_000)
        val exactPrice = search(min = 5_000, max = 5_000)
        val byCategory = search(category = 2)
        val inStock = search(inStock = true, sortBy = "price", sortOrder = "asc")
        val first = search(sortBy = "price", sortOrder = "desc", page = 0, size = 2)
        val second = search(sortBy = "price", sortOrder = "desc", page = 1, size = 2)
        val newest = search(sortBy = "createdAt", sortOrder = "desc")

        // then
        assertThat(byPrice.items!!.map { it.id }).isEqualTo(listOf(11L))
        assertThat(exactPrice.items!!.map { it.id }).`as`("Both price bounds are inclusive").isEqualTo(listOf(11L))
        assertThat(byCategory.items!!.map { it.id }).isEqualTo(listOf(11L))
        assertThat(inStock.items!!.map { it.id }).`as`("Total stock above zero").isEqualTo(listOf(10L, 11L))
        assertThat(first.items!!.map { it.id }).isEqualTo(listOf(13L, 11L))
        assertThat(first.hasNext).isTrue()
        assertThat(first.totalPages).isEqualTo(2)
        assertThat(second.items!!.map { it.id }).isEqualTo(listOf(10L))
        assertThat(second.totalCount).isEqualTo(3)
        assertThat(newest.items!!.map { it.id }).isEqualTo(listOf(13L, 11L, 10L))
    }

    @Test
    fun `쪽 범위나 가격이나 정렬이 어긋날 때__검색하면__질의 전에 거부한다`() {
        // given
        // when
        // then
        for (bad in listOf(
            { search(page = -1) }, { search(size = 0) }, { search(size = 101) }, { search(min = -1) }, { search(min = 5, max = 1) },
            { search(sortBy = "password") }, { search(sortOrder = "sideways") },
        )) {
            assertThatThrownBy { bad() }.isInstanceOf(IllegalArgumentException::class.java)
        }
    }

    @Test
    fun `검색 문서가 없을 때__키워드로 검색하면__준비 중 예외이고 복구 뒤에는 이름과 옵션으로 찾으며 이름이 바뀌면 다시 준비 중이다`() {
        // given
        val rebuild = JdbcProductSearchRebuild(writeJdbc(), writeTransaction(), rebuildTerms)

        // when
        assertThatThrownBy { search(keyword = "연어") }.isInstanceOf(CatalogSearchUnavailableException::class.java)
        val rebuilt = rebuild.rebuildPage(100)
        val byName = search(keyword = "연어")
        val byOption = search(keyword = "맛")
        val none = search(keyword = "고래")
        execute("UPDATE pawbridge_store.products SET name = '고래 사료' WHERE id = 10")
        val renamedUnavailable = runCatching { search(keyword = "연어") }.exceptionOrNull()
        execute("UPDATE pawbridge_store.products SET name = '연어 사료' WHERE id = 10")
        val restored = search(keyword = "연어")
        execute("INSERT INTO pawbridge_store.product_skus(id, product_id, sku_code, price, stock_quantity) VALUES (999, 10, 'CHEAPEST', 1, 1)")
        seedOption(999, 1, "맛", 1, "연어")

        // then
        assertThat(rebuilt).isEqualTo(3)
        assertThat(byName.items!!.map { it.id }).isEqualTo(listOf(10L))
        assertThat(byOption.items!!.map { it.id }).isEqualTo(listOf(10L))
        assertThat(none.items).isEmpty()
        assertThat(renamedUnavailable).`as`("A renamed product makes its document stale").isInstanceOf(CatalogSearchUnavailableException::class.java)
        assertThat(restored.items!!.map { it.id }).isEqualTo(listOf(10L))
        assertThatThrownBy { search(keyword = "연어") }.`as`("A new representative SKU makes the document stale").isInstanceOf(CatalogSearchUnavailableException::class.java)
    }

    @Test
    fun `분류와 SKU 옵션이 있는 상품일 때__상세를 읽으면__분류 이름과 SKU별 옵션 맵을 주고 없는 상품은 예외다`() {
        // given
        seedOption(100, 1, "맛", 2, "닭")
        seedOption(100, 2, "크기", 3, "대")

        // when
        val product = catalog.product(10)

        // then
        assertThat(product.categoryName).isEqualTo("사료")
        assertThat(product.viewCount).isEqualTo(3)
        assertThat(product.skus!!.associate { it.skuId to it.options }).isEqualTo(
            mapOf(100L to mapOf("맛" to "닭", "크기" to "대"), 101L to mapOf("맛" to "연어")),
        )
        assertThat(catalog.product(14).skus).isEmpty()
        assertThatThrownBy { catalog.product(999) }.isInstanceOf(IllegalArgumentException::class.java)
    }

    @Test
    fun `부모와 자식 분류와 옵션 그룹이 있을 때__분류와 옵션을 읽으면__트리와 값 목록을 주고 없는 그룹은 예외다`() {
        // given
        seedOption(110, 7, "색", 70, "빨강")
        seedOption(110, 7, "색", 71, "파랑")
        execute("INSERT INTO pawbridge_store.option_groups(id, name) VALUES (8, '빈 그룹')")

        // when
        val categories = catalog.categories()
        val groups = catalog.optionGroups()
        val color = catalog.optionGroup(7)

        // then
        assertThat(categories.map { it.name }).isEqualTo(listOf("사료"))
        assertThat(categories.single().children!!.map { it.name to it.parentId }).isEqualTo(listOf("간식" to 1L))
        assertThat(groups.single { it.id == 8L }.values).isEmpty()
        assertThat(color.values!!.map { it.name }.toSet()).isEqualTo(setOf("빨강", "파랑"))
        assertThatThrownBy { catalog.optionGroup(999) }.isInstanceOf(IllegalArgumentException::class.java)
    }
}
