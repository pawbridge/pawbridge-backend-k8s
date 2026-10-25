package com.pawbridge.backend.store.internal.catalogwrite.infrastructure

import com.pawbridge.backend.store.internal.catalogwrite.infrastructure.jpa.repository.CategoryRepository
import com.pawbridge.backend.store.internal.catalogwrite.web.CategoryCreateRequest
import com.pawbridge.backend.store.internal.catalogwrite.web.CategoryResponse
import com.pawbridge.backend.store.internal.catalogwrite.web.CategoryUpdateRequest
import com.pawbridge.backend.store.internal.memberread.infrastructure.jpa.model.Category
import jakarta.persistence.EntityManager
import org.springframework.data.jpa.repository.support.JpaRepositoryFactory
import org.springframework.transaction.support.TransactionOperations

/** Original CategoryService create/update/delete on the existing Store writer; responses are built inside the writer TX. */
class JpaCatalogCategories internal constructor(
    private val categories: CategoryRepository,
    private val writes: TransactionOperations,
) {

    constructor(sharedWriter: EntityManager, writes: TransactionOperations) : this(
        JpaRepositoryFactory(sharedWriter).getRepository(CategoryRepository::class.java),
        writes,
    )

    fun createCategory(request: CategoryCreateRequest): CategoryResponse =
        writes.execute {
            JpaCatalogProducts.requireWriter()
            var parent: Category? = null
            val parentId = request.parentId
            if (parentId != null) {
                parent = categories.findById(parentId)
                    .orElseThrow { IllegalArgumentException("Parent category not found") }
            }
            val category = Category.builder()
                .name(request.name)
                .description(request.description)
                .parent(parent)
                .build()
            categories.save(category)
            CategoryResponse.from(category)
        }!!

    fun deleteCategory(categoryId: Long?) {
        writes.executeWithoutResult {
            JpaCatalogProducts.requireWriter()
            val category = categories.findById(categoryId!!)
                .orElseThrow { IllegalArgumentException("Category not found: $categoryId") }
            if (category.children.isNotEmpty()) {
                throw IllegalStateException("하위 카테고리가 있어 삭제할 수 없습니다.")
            }
            categories.delete(category)
        }
    }

    fun updateCategory(categoryId: Long?, request: CategoryUpdateRequest): CategoryResponse =
        writes.execute {
            JpaCatalogProducts.requireWriter()
            val category = categories.findById(categoryId!!)
                .orElseThrow { IllegalArgumentException("Category not found: $categoryId") }
            var newParent: Category? = null
            val parentId = request.parentId
            if (parentId != null) {
                if (parentId == categoryId) {
                    throw IllegalArgumentException("카테고리는 자기 자신을 부모로 가질 수 없습니다.")
                }
                newParent = categories.findById(parentId)
                    .orElseThrow { IllegalArgumentException("Parent category not found: $parentId") }
                if (isDescendant(category, newParent)) {
                    throw IllegalArgumentException("자신의 하위 카테고리를 부모로 지정할 수 없습니다.")
                }
            }
            category.update(request.name, request.description, newParent)
            CategoryResponse.from(category)
        }!!

    private companion object {
        private fun isDescendant(parent: Category, target: Category): Boolean {
            if (parent.children.isEmpty()) {
                return false
            }
            for (child in parent.children) {
                if (child.id!! == target.id) {
                    return true
                }
                if (isDescendant(child, target)) {
                    return true
                }
            }
            return false
        }
    }
}
