package com.pawbridge.backend.store.internal.catalogwrite.infrastructure

import com.fasterxml.jackson.databind.ObjectMapper
import com.pawbridge.backend.store.internal.catalogwrite.infrastructure.jpa.repository.CategoryRepository
import com.pawbridge.backend.store.internal.catalogwrite.web.CategoryCreateRequest
import com.pawbridge.backend.store.internal.catalogwrite.web.CategoryUpdateRequest
import com.pawbridge.backend.store.internal.memberread.infrastructure.jpa.model.Category
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import org.mockito.ArgumentMatchers.any
import org.mockito.Mockito.mock
import org.mockito.Mockito.never
import org.mockito.Mockito.`when`
import org.mockito.Mockito.verify
import org.mockito.Mockito.verifyNoInteractions
import org.springframework.test.util.ReflectionTestUtils
import org.springframework.transaction.TransactionDefinition
import org.springframework.transaction.support.AbstractPlatformTransactionManager
import org.springframework.transaction.support.DefaultTransactionStatus
import org.springframework.transaction.support.TransactionOperations
import org.springframework.transaction.support.TransactionSynchronizationManager
import org.springframework.transaction.support.TransactionTemplate
import java.util.Optional

class JpaCatalogCategoriesTest {
    private val categories: CategoryRepository = mock(CategoryRepository::class.java)
    private val manager = Writer()
    private val subject = JpaCatalogCategories(categories, TransactionTemplate(manager))
    private val mapper = ObjectMapper()

    @AfterEach
    fun resourcesReturned() {
        assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse()
        assertThat(TransactionSynchronizationManager.getResourceMap()).isEmpty()
    }

    @Test
    fun givenParentId__whenCreating__thenSaveUnderParentAndReturnOriginalShapeInsideWriter() {
        val parent = category(1L, "food", null)
        `when`(categories.findById(1L)).thenReturn(Optional.of(parent))
        `when`(categories.save(any(Category::class.java))).thenAnswer { call ->
            assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isTrue()
            val saved = call.getArgument<Category>(0)
            ReflectionTestUtils.setField(saved, "id", 2L)
            saved
        }

        val result = subject.createCategory(
            mapper.readValue("{\"name\":\"dry\",\"description\":\"dry food\",\"parentId\":1}", CategoryCreateRequest::class.java),
        )

        assertThat(result.id).isEqualTo(2L)
        assertThat(result.name).isEqualTo("dry")
        assertThat(result.description).isEqualTo("dry food")
        assertThat(result.parentId).isEqualTo(1L)
        assertThat(result.children).isEmpty()
        assertThat(manager.commits).isEqualTo(1)
    }

    @Test
    fun givenNoParent__whenCreating__thenSaveRootWithoutParentLookup() {
        `when`(categories.save(any(Category::class.java))).thenAnswer { call ->
            val saved = call.getArgument<Category>(0)
            ReflectionTestUtils.setField(saved, "id", 3L)
            saved
        }

        val result = subject.createCategory(mapper.readValue("{\"name\":\"toy\"}", CategoryCreateRequest::class.java))

        assertThat(result.parentId).isNull()
        verify(categories, never()).findById(any(Long::class.javaObjectType))
        assertThat(manager.commits).isEqualTo(1)
    }

    @Test
    fun givenMissingParent__whenCreating__thenRejectAsInputBeforeSaving() {
        `when`(categories.findById(9L)).thenReturn(Optional.empty())
        val request = mapper.readValue("{\"name\":\"dry\",\"parentId\":9}", CategoryCreateRequest::class.java)

        assertThatThrownBy { subject.createCategory(request) }.isInstanceOf(IllegalArgumentException::class.java)

        verify(categories, never()).save(any(Category::class.java))
        assertThat(manager.rollbacks).isEqualTo(1)
    }

    @Test
    fun givenBlankNameNullDescriptionAndNoParent__whenUpdating__thenKeepNameButClearDescriptionAndMoveToRoot() {
        val parent = category(1L, "food", null)
        val child = category(2L, "dry", parent)
        `when`(categories.findById(2L)).thenReturn(Optional.of(child))

        val result = subject.updateCategory(2L, mapper.readValue("{\"name\":\" \"}", CategoryUpdateRequest::class.java))

        assertThat(result.name).isEqualTo("dry")
        assertThat(result.description).isNull()
        assertThat(result.parentId).isNull()
        assertThat(child.parent).isNull()
        assertThat(manager.commits).isEqualTo(1)
    }

    @Test
    fun givenNewParent__whenUpdating__thenApplyNameDescriptionAndParent() {
        val target = category(2L, "dry", null)
        val parent = category(1L, "food", null)
        `when`(categories.findById(2L)).thenReturn(Optional.of(target))
        `when`(categories.findById(1L)).thenReturn(Optional.of(parent))

        val result = subject.updateCategory(
            2L,
            mapper.readValue(
                "{\"name\":\"wet\",\"description\":\"wet food\",\"parentId\":1}",
                CategoryUpdateRequest::class.java,
            ),
        )

        assertThat(result.name).isEqualTo("wet")
        assertThat(result.description).isEqualTo("wet food")
        assertThat(result.parentId).isEqualTo(1L)
    }

    @Test
    fun givenSelfOrDescendantParent__whenUpdating__thenRejectAsInputWithoutChanges() {
        val root = category(1L, "food", null)
        val child = category(2L, "dry", root)
        val grandchild = category(3L, "kibble", child)
        root.children.add(child)
        child.children.add(grandchild)
        `when`(categories.findById(1L)).thenReturn(Optional.of(root))
        `when`(categories.findById(3L)).thenReturn(Optional.of(grandchild))
        val self = mapper.readValue("{\"name\":\"x\",\"parentId\":1}", CategoryUpdateRequest::class.java)
        val descendant = mapper.readValue("{\"name\":\"x\",\"parentId\":3}", CategoryUpdateRequest::class.java)

        assertThatThrownBy { subject.updateCategory(1L, self) }.isInstanceOf(IllegalArgumentException::class.java)
        assertThatThrownBy { subject.updateCategory(1L, descendant) }.isInstanceOf(IllegalArgumentException::class.java)

        assertThat(root.name).isEqualTo("food")
        assertThat(root.parent).isNull()
        assertThat(manager.rollbacks).isEqualTo(2)
    }

    @Test
    fun givenMissingCategory__whenUpdatingOrDeleting__thenRejectAsInput() {
        `when`(categories.findById(9L)).thenReturn(Optional.empty())
        val request = mapper.readValue("{\"name\":\"x\"}", CategoryUpdateRequest::class.java)

        assertThatThrownBy { subject.updateCategory(9L, request) }.isInstanceOf(IllegalArgumentException::class.java)
        assertThatThrownBy { subject.deleteCategory(9L) }.isInstanceOf(IllegalArgumentException::class.java)

        verify(categories, never()).delete(any(Category::class.java))
        assertThat(manager.rollbacks).isEqualTo(2)
    }

    @Test
    fun givenChildCategory__whenDeletingParent__thenRefuseWithOriginalStateFailure() {
        val root = category(1L, "food", null)
        root.children.add(category(2L, "dry", root))
        `when`(categories.findById(1L)).thenReturn(Optional.of(root))

        assertThatThrownBy { subject.deleteCategory(1L) }.isInstanceOf(IllegalStateException::class.java)

        verify(categories, never()).delete(any(Category::class.java))
        assertThat(manager.rollbacks).isEqualTo(1)
    }

    @Test
    fun givenLeafCategory__whenDeleting__thenKeepOriginalPhysicalDelete() {
        val leaf = category(2L, "dry", null)
        `when`(categories.findById(2L)).thenReturn(Optional.of(leaf))

        subject.deleteCategory(2L)

        verify(categories).delete(leaf)
        assertThat(manager.commits).isEqualTo(1)
    }

    @Test
    fun givenNoActiveWriter__whenCallingWithoutTransaction__thenRefuseBeforeRepositoryAccess() {
        val outside = JpaCatalogCategories(categories, TransactionOperations.withoutTransaction())
        val request = mapper.readValue("{\"name\":\"dry\"}", CategoryCreateRequest::class.java)

        assertThatThrownBy { outside.createCategory(request) }.isInstanceOf(IllegalStateException::class.java)
        assertThatThrownBy { outside.deleteCategory(2L) }.isInstanceOf(IllegalStateException::class.java)

        verifyNoInteractions(categories)
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
