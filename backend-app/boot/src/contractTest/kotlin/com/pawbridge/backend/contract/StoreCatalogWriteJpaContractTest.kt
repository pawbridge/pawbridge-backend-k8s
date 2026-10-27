package com.pawbridge.backend.contract

import com.fasterxml.jackson.databind.ObjectMapper
import com.pawbridge.backend.store.internal.catalogwrite.infrastructure.JpaCatalogCategories
import com.pawbridge.backend.store.internal.catalogwrite.infrastructure.JpaCatalogOptions
import com.pawbridge.backend.store.internal.catalogwrite.infrastructure.JpaCatalogProducts
import com.pawbridge.backend.store.internal.catalogwrite.web.CategoryCreateRequest
import com.pawbridge.backend.store.internal.catalogwrite.web.CategoryUpdateRequest
import com.pawbridge.backend.store.internal.catalogwrite.web.OptionGroupRequest
import com.pawbridge.backend.store.internal.catalogwrite.web.OptionValueRequest
import com.pawbridge.backend.store.internal.catalogwrite.web.ProductCreateRequest
import com.pawbridge.backend.store.internal.catalogwrite.web.ProductUpdateRequest
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired

/** The catalog write adapters (JPA): categories, option groups and values, products with their SKUs. */
class StoreCatalogWriteJpaContractTest : JpaContractBase() {
    @Autowired
    lateinit var categories: JpaCatalogCategories

    @Autowired
    lateinit var options: JpaCatalogOptions

    @Autowired
    lateinit var products: JpaCatalogProducts

    private val json = ObjectMapper()

    private fun <T> request(type: Class<T>, text: String): T = json.readValue(text, type)

    private fun category(name: String, parent: Long? = null, description: String? = null) = categories.createCategory(
        request(CategoryCreateRequest::class.java, json.writeValueAsString(mapOf("name" to name, "description" to description, "parentId" to parent))),
    )

    private fun categoryUpdate(name: String?, parent: Long?, description: String? = null) = request(
        CategoryUpdateRequest::class.java,
        json.writeValueAsString(mapOf("name" to name, "description" to description, "parentId" to parent)),
    )

    /** The SQLState of the database error a call ends with. */
    private fun sqlStateOf(action: () -> Unit): String? = sqlState(runCatching(action).exceptionOrNull())

    private fun productRows() = count("SELECT count(*) FROM pawbridge_store.products")

    private fun searchOption(product: Long) =
        scalar("SELECT source_option FROM pawbridge_store.product_search_documents WHERE product_id = ?", product)

    private fun createProduct(name: String, category: Long? = null, skus: List<Map<String, Any?>>) = products.createProduct(
        request(
            ProductCreateRequest::class.java,
            json.writeValueAsString(mapOf("name" to name, "description" to "설명", "imageUrl" to "https://fixture.invalid/p.png", "categoryId" to category, "skus" to skus)),
        ),
    )

    private fun skuSpec(code: String, price: Long, stock: Int, vararg optionValues: Long) =
        mapOf("skuCode" to code, "price" to price, "stockQuantity" to stock, "optionValueIds" to optionValues.toList())

    @Test
    fun `부모가 있고 없을 때__카테고리를 만들면__행을 저장하고 없는 부모나 겹치는 이름은 저장하지 않는다`() {
        // given
        val root = category("사료 루트", description = "설명")

        // when
        val child = category("하위", parent = root.id)

        // then
        assertThat(row("pawbridge_store.categories", "id", root.id!!).get("parent_id").isNull).isTrue()
        assertThat(row("pawbridge_store.categories", "id", child.id!!).get("parent_id").asLong()).isEqualTo(root.id)
        assertThat(child.parentId).isEqualTo(root.id)
        assertThatThrownBy { category("고아", parent = 99_999_999L) }.isInstanceOf(IllegalArgumentException::class.java)
        assertThat(sqlStateOf { category("사료 루트") }).`as`("Unique category name").isEqualTo(UNIQUE_VIOLATION)
        assertThat(count("SELECT count(*) FROM pawbridge_store.categories")).isEqualTo(2)
    }

    @Test
    fun `카테고리 트리가 있을 때__수정하면__이름과 설명과 부모를 바꾸고 자기 자신이나 자손 아래로는 옮기지 않는다`() {
        // given
        val a = category("A")
        val b = category("B", parent = a.id)
        val c = category("C", parent = b.id)

        // when
        val moved = categories.updateCategory(c.id, categoryUpdate("C2", a.id, "새 설명"))

        // then
        assertThat(moved.name).isEqualTo("C2")
        assertThat(row("pawbridge_store.categories", "id", c.id!!).let { listOf(it.get("name").asText(), it.get("description").asText(), it.get("parent_id").asLong()) })
            .isEqualTo(listOf("C2", "새 설명", a.id))
        assertThatThrownBy { categories.updateCategory(a.id, categoryUpdate("A", a.id)) }.isInstanceOf(IllegalArgumentException::class.java)
        assertThatThrownBy { categories.updateCategory(a.id, categoryUpdate("A", b.id)) }.isInstanceOf(IllegalArgumentException::class.java)
        assertThatThrownBy { categories.updateCategory(99_999_999L, categoryUpdate("X", null)) }.isInstanceOf(IllegalArgumentException::class.java)
        assertThat(row("pawbridge_store.categories", "id", a.id!!).get("parent_id").isNull).isTrue()
        categories.updateCategory(c.id, categoryUpdate("C3", null))
        assertThat(row("pawbridge_store.categories", "id", c.id!!).get("parent_id").isNull).`as`("A null parent moves it to the root").isTrue()
    }

    @Test
    fun `자식이 있거나 상품이 쓰는 카테고리일 때__삭제하면__거부하고 잎 카테고리만 지운다`() {
        // given
        val parent = category("부모")
        val used = category("사용 중", parent = parent.id)
        val leaf = category("잎", parent = parent.id)
        seedProduct(7_700_001, "상품", category = used.id)

        // when
        categories.deleteCategory(leaf.id)

        // then
        assertThat(count("SELECT count(*) FROM pawbridge_store.categories WHERE id = ?", leaf.id!!)).isZero()
        assertThatThrownBy { categories.deleteCategory(parent.id) }.isInstanceOf(IllegalStateException::class.java)
        assertThat(sqlStateOf { categories.deleteCategory(used.id) }).`as`("A product references it").isEqualTo(FOREIGN_KEY_VIOLATION)
        assertThatThrownBy { categories.deleteCategory(99_999_999L) }.isInstanceOf(IllegalArgumentException::class.java)
        assertThat(count("SELECT count(*) FROM pawbridge_store.categories")).isEqualTo(2)
    }

    @Test
    fun `카테고리와 옵션 값이 있을 때__SKU가 있는 상품을 만들면__상품과 SKU와 옵션 연결과 검색 문서를 저장한다`() {
        // given
        val group = options.createOptionGroup(request(OptionGroupRequest::class.java, """{"name":"맛","values":["연어","닭"]}"""))
        val salmon = group.values!!.first { it.name == "연어" }.id!!
        val chicken = group.values!!.first { it.name == "닭" }.id!!
        val food = category("사료")

        // when
        val created = createProduct("연어 사료", food.id, listOf(skuSpec("S-1", 3_000, 4, salmon), skuSpec("S-2", 2_000, 6, chicken)))

        // then
        assertThat(row("pawbridge_store.products", "id", created.id!!).let { listOf(it.get("status").asText(), it.get("category_id").asLong(), it.get("name").asText()) })
            .isEqualTo(listOf("ACTIVE", food.id, "연어 사료"))
        assertThat(created.skus!!.map { it.skuCode to it.stockQuantity }.toSet()).isEqualTo(setOf("S-1" to 4, "S-2" to 6))
        assertThat(count("SELECT count(*) FROM pawbridge_store.sku_values")).isEqualTo(2)
        assertThat(count("SELECT sku_id FROM pawbridge_store.product_search_documents WHERE product_id = ?", created.id!!))
            .`as`("The cheapest SKU represents the product").isEqualTo(created.skus!!.single { it.skuCode == "S-2" }.skuId!!)
        assertThat(searchOption(created.id!!)).isEqualTo("맛: 닭")
    }

    @Test
    fun `SKU 코드가 겹치거나 없는 옵션 값이나 카테고리일 때__상품을 만들면__거부하고 아무것도 저장하지 않는다`() {
        // given
        createProduct("기존", skus = listOf(skuSpec("DUP", 1_000, 1)))
        val before = ContractPostgres.snapshot()

        // when
        // then
        assertThat(sqlStateOf { createProduct("중복", skus = listOf(skuSpec("DUP", 1_000, 1))) }).`as`("Unique SKU code").isEqualTo(UNIQUE_VIOLATION)
        assertThatThrownBy { createProduct("옵션 없음", skus = listOf(skuSpec("NEW-1", 1_000, 1, 99_999_999L))) }.isInstanceOf(IllegalArgumentException::class.java)
        assertThatThrownBy { createProduct("분류 없음", category = 99_999_999L, skus = listOf(skuSpec("NEW-2", 1_000, 1))) }.isInstanceOf(IllegalArgumentException::class.java)
        assertThat(ContractPostgres.snapshot()).isEqualTo(before)
    }

    @Test
    fun `SKU가 없는 상품 요청일 때__만들면__검색 문서 없이 상품만 저장한다`() {
        // given
        // when
        val created = createProduct("빈 상품", skus = emptyList())

        // then
        assertThat(productRows()).isEqualTo(1)
        assertThat(searchDocumentCount(created.id!!)).isZero()
    }

    @Test
    fun `상품이 있을 때__이름과 상태와 SKU 가격과 재고를 수정하면__행과 검색 문서가 함께 바뀐다`() {
        // given
        val created = createProduct("처음 이름", skus = listOf(skuSpec("U-1", 3_000, 4)))
        val sku = created.skus!!.single().skuId!!

        // when
        val updated = products.updateProduct(
            created.id,
            request(
                ProductUpdateRequest::class.java,
                """{"name":"바뀐 이름","status":"SOLD_OUT","skus":[{"id":$sku,"price":1500,"stockQuantity":9},{"id":99999999,"price":1}]}""",
            ),
        )

        // then
        assertThat(updated.name).isEqualTo("바뀐 이름")
        assertThat(row("pawbridge_store.products", "id", created.id!!).get("status").asText()).isEqualTo("SOLD_OUT")
        assertThat(row("pawbridge_store.product_skus", "id", sku).let { it.get("price").asLong() to it.get("stock_quantity").asInt() }).isEqualTo(1500L to 9)
        assertThat(scalar("SELECT source_name FROM pawbridge_store.product_search_documents WHERE product_id = ?", created.id!!)).isEqualTo("바뀐 이름")
        assertThatThrownBy { products.updateProduct(99_999_999L, request(ProductUpdateRequest::class.java, """{"name":"x"}""")) }
            .isInstanceOf(IllegalArgumentException::class.java)
    }

    @Test
    fun `장바구니에 담기지 않은 상품일 때__삭제하면__삭제 상태로 바꾸고 담긴 상품과 이미 삭제된 상품은 거부한다`() {
        // given
        val free = createProduct("자유", skus = listOf(skuSpec("F-1", 1_000, 1)))
        val carted = createProduct("담김", skus = listOf(skuSpec("C-1", 1_000, 1)))
        execute("INSERT INTO pawbridge_store.carts(id, user_id) VALUES (7700001, 7700101)")
        execute(
            "INSERT INTO pawbridge_store.cart_items(cart_id, sku_id, product_sku_id, quantity) VALUES (7700001, ?, ?, 1)",
            carted.skus!!.single().skuId, carted.skus!!.single().skuId,
        )

        // when
        products.deleteProduct(free.id)

        // then
        assertThat(row("pawbridge_store.products", "id", free.id!!).get("status").asText()).isEqualTo("DELETED")
        assertThat(count("SELECT count(*) FROM pawbridge_store.product_skus WHERE product_id = ?", free.id!!)).`as`("Soft delete").isEqualTo(1)
        assertThatThrownBy { products.deleteProduct(free.id) }.isInstanceOf(IllegalStateException::class.java)
        assertThatThrownBy { products.deleteProduct(carted.id) }.isInstanceOf(IllegalStateException::class.java)
        assertThat(row("pawbridge_store.products", "id", carted.id!!).get("status").asText()).isEqualTo("ACTIVE")
        assertThatThrownBy { products.deleteProduct(99_999_999L) }.isInstanceOf(IllegalArgumentException::class.java)
    }

    @Test
    fun `옵션이 상품에 쓰이고 있을 때__그룹과 값의 이름을 바꾸면__영향받는 상품의 검색 문서가 다시 만들어진다`() {
        // given
        val group = options.createOptionGroup(request(OptionGroupRequest::class.java, """{"name":"크기","values":["대"]}"""))
        val large = group.values!!.single().id!!
        val product = createProduct("사료", skus = listOf(skuSpec("O-1", 1_000, 1, large)))
        assertThat(searchOption(product.id!!)).isEqualTo("크기: 대")

        // when
        options.updateOptionValue(large, request(OptionValueRequest::class.java, """{"name":"특대"}"""))
        val afterValue = searchOption(product.id!!)
        options.updateOptionGroup(group.id, request(OptionGroupRequest::class.java, """{"name":"사이즈"}"""))

        // then
        assertThat(afterValue).isEqualTo("크기: 특대")
        assertThat(searchOption(product.id!!)).isEqualTo("사이즈: 특대")
        assertThatThrownBy { options.updateOptionGroup(99_999_999L, request(OptionGroupRequest::class.java, """{"name":"x"}""")) }
            .isInstanceOf(IllegalArgumentException::class.java)
    }

    @Test
    fun `쓰이는 옵션과 쓰이지 않는 옵션이 있을 때__삭제하면__쓰이는 것은 거부하고 쓰이지 않는 것만 지운다`() {
        // given
        val used = options.createOptionGroup(request(OptionGroupRequest::class.java, """{"name":"쓰임","values":["값"]}"""))
        val unused = options.createOptionGroup(request(OptionGroupRequest::class.java, """{"name":"안 쓰임","values":["하나","둘"]}"""))
        createProduct("상품", skus = listOf(skuSpec("D-1", 1_000, 1, used.values!!.single().id!!)))
        val extra = options.addOptionValue(unused.id, request(OptionValueRequest::class.java, """{"name":"셋"}"""))

        // when
        options.deleteOptionValue(extra.id)
        options.deleteOptionGroup(unused.id)

        // then
        assertThat(extra.groupId).isEqualTo(unused.id)
        assertThat(count("SELECT count(*) FROM pawbridge_store.option_groups")).isEqualTo(1)
        assertThat(count("SELECT count(*) FROM pawbridge_store.option_values")).isEqualTo(1)
        assertThatThrownBy { options.deleteOptionValue(used.values!!.single().id) }.isInstanceOf(IllegalStateException::class.java)
        assertThatThrownBy { options.deleteOptionGroup(used.id) }.isInstanceOf(IllegalStateException::class.java)
        assertThatThrownBy { options.createOptionGroup(request(OptionGroupRequest::class.java, """{"name":"쓰임"}""")) }
            .isInstanceOf(IllegalStateException::class.java)
    }

    private companion object {
        const val UNIQUE_VIOLATION = "23505"
        const val FOREIGN_KEY_VIOLATION = "23503"
    }
}
