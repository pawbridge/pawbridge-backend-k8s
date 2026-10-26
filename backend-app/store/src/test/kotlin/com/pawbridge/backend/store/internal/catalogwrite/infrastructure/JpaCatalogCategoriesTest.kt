package com.pawbridge.backend.store.internal.catalogwrite.infrastructure

import com.fasterxml.jackson.databind.ObjectMapper
import com.pawbridge.backend.store.internal.catalogwrite.infrastructure.jpa.repository.CategoryRepository
import com.pawbridge.backend.store.internal.catalogwrite.web.CategoryCreateRequest
import com.pawbridge.backend.store.internal.catalogwrite.web.CategoryUpdateRequest
import com.pawbridge.backend.store.internal.memberread.infrastructure.jpa.model.Category
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import io.mockk.confirmVerified
import io.mockk.every
import io.mockk.justRun
import io.mockk.mockk
import io.mockk.verify
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import org.springframework.test.util.ReflectionTestUtils
import org.springframework.transaction.TransactionDefinition
import org.springframework.transaction.support.AbstractPlatformTransactionManager
import org.springframework.transaction.support.DefaultTransactionStatus
import org.springframework.transaction.support.TransactionOperations
import org.springframework.transaction.support.TransactionSynchronizationManager
import org.springframework.transaction.support.TransactionTemplate
import java.util.Optional

class JpaCatalogCategoriesTest {
    private val categories = mockk<CategoryRepository>()
    private val manager = Writer()
    private val subject = JpaCatalogCategories(categories, TransactionTemplate(manager))
    private val mapper = ObjectMapper()

    @AfterEach
    fun resourcesReturned() {
        assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse()
        assertThat(TransactionSynchronizationManager.getResourceMap()).isEmpty()
    }

    @Test
    fun `부모 id가 있을 때__생성하면__부모 아래에 저장하고 쓰기 트랜잭션 안에서 원래 모양을 돌려준다`() {
        // given
        val parent = category(1L, "food", null)
        val request = mapper.readValue(
            "{\"name\":\"dry\",\"description\":\"dry food\",\"parentId\":1}",
            CategoryCreateRequest::class.java,
        )

        every { categories.findById(any()) } returns Optional.of(parent)
        every { categories.save(any()) } answers {
            assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isTrue()
            val saved = firstArg<Category>()
            ReflectionTestUtils.setField(saved, "id", 2L)
            saved
        }

        // when
        val result = subject.createCategory(request)

        // then
        assertThat(result.id).isEqualTo(2L)
        assertThat(result.name).isEqualTo("dry")
        assertThat(result.description).isEqualTo("dry food")
        assertThat(result.parentId).isEqualTo(1L)
        assertThat(result.children).isEmpty()
        assertThat(manager.commits).isEqualTo(1)
        verify(exactly = 1) { categories.findById(1L) }
    }

    @Test
    fun `부모가 없을 때__생성하면__부모 조회 없이 루트로 저장한다`() {
        // given
        val request = mapper.readValue("{\"name\":\"toy\"}", CategoryCreateRequest::class.java)

        every { categories.save(any()) } answers {
            val saved = firstArg<Category>()
            ReflectionTestUtils.setField(saved, "id", 3L)
            saved
        }

        // when
        val result = subject.createCategory(request)

        // then
        assertThat(result.parentId).isNull()
        verify(exactly = 0) { categories.findById(any<Long>()) }
        assertThat(manager.commits).isEqualTo(1)
    }

    @Test
    fun `부모가 없는 id일 때__생성하면__저장 전에 입력 오류로 거부한다`() {
        // given
        val request = mapper.readValue("{\"name\":\"dry\",\"parentId\":9}", CategoryCreateRequest::class.java)

        every { categories.findById(any()) } returns Optional.empty()

        // when
        val thrown = assertThatThrownBy { subject.createCategory(request) }

        // then
        thrown.isInstanceOf(IllegalArgumentException::class.java)
        verify(exactly = 1) { categories.findById(9L) }
        verify(exactly = 0) { categories.save(any()) }
        assertThat(manager.rollbacks).isEqualTo(1)
    }

    @Test
    fun `이름이 공백이고 설명과 부모가 없을 때__수정하면__이름은 유지하고 설명은 지우고 루트로 옮긴다`() {
        // given
        val parent = category(1L, "food", null)
        val child = category(2L, "dry", parent)
        val request = mapper.readValue("{\"name\":\" \"}", CategoryUpdateRequest::class.java)

        every { categories.findById(any()) } returns Optional.of(child)

        // when
        val result = subject.updateCategory(2L, request)

        // then
        assertThat(result.name).isEqualTo("dry")
        assertThat(result.description).isNull()
        assertThat(result.parentId).isNull()
        assertThat(child.parent).isNull()
        assertThat(manager.commits).isEqualTo(1)
        verify(exactly = 1) { categories.findById(2L) }
    }

    @Test
    fun `새 부모가 있을 때__수정하면__이름과 설명과 부모를 적용한다`() {
        // given
        val target = category(2L, "dry", null)
        val parent = category(1L, "food", null)
        val request = mapper.readValue(
            "{\"name\":\"wet\",\"description\":\"wet food\",\"parentId\":1}",
            CategoryUpdateRequest::class.java,
        )

        every { categories.findById(2L) } returns Optional.of(target)
        every { categories.findById(1L) } returns Optional.of(parent)

        // when
        val result = subject.updateCategory(2L, request)

        // then
        assertThat(result.name).isEqualTo("wet")
        assertThat(result.description).isEqualTo("wet food")
        assertThat(result.parentId).isEqualTo(1L)
    }

    @Test
    fun `부모가 자기 자신이거나 자손일 때__수정하면__변경 없이 입력 오류로 거부한다`() {
        // given
        val root = category(1L, "food", null)
        val child = category(2L, "dry", root)
        val grandchild = category(3L, "kibble", child)
        root.children.add(child)
        child.children.add(grandchild)
        val self = mapper.readValue("{\"name\":\"x\",\"parentId\":1}", CategoryUpdateRequest::class.java)
        val descendant = mapper.readValue("{\"name\":\"x\",\"parentId\":3}", CategoryUpdateRequest::class.java)

        every { categories.findById(1L) } returns Optional.of(root)
        every { categories.findById(3L) } returns Optional.of(grandchild)

        // when
        val selfThrown = assertThatThrownBy { subject.updateCategory(1L, self) }
        val descendantThrown = assertThatThrownBy { subject.updateCategory(1L, descendant) }

        // then
        selfThrown.isInstanceOf(IllegalArgumentException::class.java)
        descendantThrown.isInstanceOf(IllegalArgumentException::class.java)
        assertThat(root.name).isEqualTo("food")
        assertThat(root.parent).isNull()
        assertThat(manager.rollbacks).isEqualTo(2)
    }

    @Test
    fun `카테고리가 없을 때__수정하거나 삭제하면__입력 오류로 거부한다`() {
        // given
        val request = mapper.readValue("{\"name\":\"x\"}", CategoryUpdateRequest::class.java)

        every { categories.findById(any()) } returns Optional.empty()

        // when
        val updateThrown = assertThatThrownBy { subject.updateCategory(9L, request) }
        val deleteThrown = assertThatThrownBy { subject.deleteCategory(9L) }

        // then
        updateThrown.isInstanceOf(IllegalArgumentException::class.java)
        deleteThrown.isInstanceOf(IllegalArgumentException::class.java)
        verify(exactly = 2) { categories.findById(9L) }
        verify(exactly = 0) { categories.delete(any()) }
        assertThat(manager.rollbacks).isEqualTo(2)
    }

    @Test
    fun `자식 카테고리가 있을 때__부모를 삭제하면__원래의 상태 오류로 거부한다`() {
        // given
        val root = category(1L, "food", null)
        root.children.add(category(2L, "dry", root))

        every { categories.findById(any()) } returns Optional.of(root)

        // when
        val thrown = assertThatThrownBy { subject.deleteCategory(1L) }

        // then
        thrown.isInstanceOf(IllegalStateException::class.java)
        verify(exactly = 1) { categories.findById(1L) }
        verify(exactly = 0) { categories.delete(any()) }
        assertThat(manager.rollbacks).isEqualTo(1)
    }

    @Test
    fun `잎 카테고리일 때__삭제하면__원래의 물리 삭제를 유지한다`() {
        // given
        val leaf = category(2L, "dry", null)

        every { categories.findById(any()) } returns Optional.of(leaf)
        justRun { categories.delete(any()) }

        // when
        subject.deleteCategory(2L)

        // then
        verify(exactly = 1) { categories.findById(2L) }
        verify(exactly = 1) { categories.delete(leaf) }
        assertThat(manager.commits).isEqualTo(1)
    }

    @Test
    fun `활성 쓰기 트랜잭션이 없을 때__트랜잭션 없이 호출하면__저장소 접근 전에 거부한다`() {
        // given
        val outside = JpaCatalogCategories(categories, TransactionOperations.withoutTransaction())
        val request = mapper.readValue("{\"name\":\"dry\"}", CategoryCreateRequest::class.java)

        // when
        val createThrown = assertThatThrownBy { outside.createCategory(request) }
        val deleteThrown = assertThatThrownBy { outside.deleteCategory(2L) }

        // then
        createThrown.isInstanceOf(IllegalStateException::class.java)
        deleteThrown.isInstanceOf(IllegalStateException::class.java)
        confirmVerified(categories)
    }

    private class Writer : AbstractPlatformTransactionManager() {
        var commits = 0
        var rollbacks = 0

        override fun doGetTransaction(): Any {
            return Any()
        }

        override fun doBegin(tx: Any, definition: TransactionDefinition) {
        }

        override fun doCommit(status: DefaultTransactionStatus) {
            commits++
        }

        override fun doRollback(status: DefaultTransactionStatus) {
            rollbacks++
        }
    }

    companion object {
        private fun category(id: Long, name: String, parent: Category?): Category {
            val category = Category.builder().name(name).parent(parent).build()
            ReflectionTestUtils.setField(category, "id", id)
            return category
        }
    }
}
