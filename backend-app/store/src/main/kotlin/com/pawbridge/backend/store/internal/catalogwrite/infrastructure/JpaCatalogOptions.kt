package com.pawbridge.backend.store.internal.catalogwrite.infrastructure

import com.pawbridge.backend.store.internal.catalog.infrastructure.KoreanSearchTerms
import com.pawbridge.backend.store.internal.catalogwrite.infrastructure.jpa.repository.OptionGroupRepository
import com.pawbridge.backend.store.internal.catalogwrite.infrastructure.jpa.repository.OptionValueRepository
import com.pawbridge.backend.store.internal.catalogwrite.web.OptionGroupRequest
import com.pawbridge.backend.store.internal.catalogwrite.web.OptionGroupResponse
import com.pawbridge.backend.store.internal.catalogwrite.web.OptionValueRequest
import com.pawbridge.backend.store.internal.catalogwrite.web.OptionValueResponse
import com.pawbridge.backend.store.internal.directorder.infrastructure.ProductSnapshots
import com.pawbridge.backend.store.internal.directorder.infrastructure.jpa.repository.ProductRepository
import com.pawbridge.backend.store.internal.directorder.infrastructure.jpa.repository.ProductSKURepository
import com.pawbridge.backend.store.internal.memberread.infrastructure.jpa.model.OptionGroup
import com.pawbridge.backend.store.internal.memberread.infrastructure.jpa.model.OptionValue
import jakarta.persistence.EntityManager
import org.springframework.data.jpa.repository.support.JpaRepositoryFactory
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcOperations
import org.springframework.transaction.support.TransactionOperations

/** Original OptionService writes; the existing catalog controller continues to own GET. */
class JpaCatalogOptions internal constructor(
    private val groups: OptionGroupRepository,
    private val values: OptionValueRepository,
    private val products: ProductRepository,
    private val skus: ProductSKURepository,
    private val snapshots: ProductSnapshots,
    private val writes: TransactionOperations,
) {

    constructor(
        sharedWriter: EntityManager,
        writes: TransactionOperations,
        search: NamedParameterJdbcOperations,
        terms: KoreanSearchTerms,
    ) : this(JpaRepositoryFactory(sharedWriter), writes, search, terms)

    private constructor(
        repositories: JpaRepositoryFactory,
        writes: TransactionOperations,
        search: NamedParameterJdbcOperations,
        terms: KoreanSearchTerms,
    ) : this(
        repositories.getRepository(OptionGroupRepository::class.java),
        repositories.getRepository(OptionValueRepository::class.java),
        repositories.getRepository(ProductRepository::class.java),
        repositories.getRepository(ProductSKURepository::class.java),
        ProductSnapshots(search, terms),
        writes,
    )

    fun createOptionGroup(request: OptionGroupRequest): OptionGroupResponse =
        writes.execute {
            JpaCatalogProducts.requireWriter()
            if (groups.existsByName(request.name)) {
                throw IllegalStateException("이미 존재하는 옵션 그룹입니다: " + request.name)
            }
            val group = OptionGroup.builder().name(request.name).build()
            groups.save(group)
            val names = request.values
            if (names != null && names.isNotEmpty()) {
                for (name in names) {
                    val value = OptionValue.builder().optionGroup(group).name(name).build()
                    values.save(value)
                    group.optionValues.add(value)
                }
            }
            OptionGroupResponse.from(group)
        }!!

    fun updateOptionGroup(id: Long?, request: OptionGroupRequest): OptionGroupResponse =
        writes.execute {
            JpaCatalogProducts.requireWriter()
            val group = groups.findByIdWithLock(id)
                .orElseThrow { IllegalArgumentException("옵션 그룹을 찾을 수 없습니다: $id") }
            values.findAllByOptionGroupIdWithLock(id)
            val affected = skus.findProductIdsByOptionGroupId(id)
            group.updateName(request.name)
            publishAffected(affected)
            OptionGroupResponse.from(group)
        }!!

    fun deleteOptionGroup(id: Long?) {
        writes.executeWithoutResult {
            JpaCatalogProducts.requireWriter()
            val group = groups.findById(
                id ?: throw IllegalArgumentException("The given id must not be null"),
            )
                .orElseThrow { IllegalArgumentException("옵션 그룹을 찾을 수 없습니다: $id") }
            if (group.optionValues.stream().anyMatch { value -> values.isOptionValueInUse(value.id) }) {
                throw IllegalStateException("사용 중인 옵션 그룹은 삭제할 수 없습니다.")
            }
            groups.delete(group)
        }
    }

    fun addOptionValue(groupId: Long?, request: OptionValueRequest): OptionValueResponse =
        writes.execute {
            JpaCatalogProducts.requireWriter()
            val group = groups.findById(
                groupId ?: throw IllegalArgumentException("The given id must not be null"),
            )
                .orElseThrow { IllegalArgumentException("옵션 그룹을 찾을 수 없습니다: $groupId") }
            val value = OptionValue.builder().optionGroup(group).name(request.name).build()
            values.save(value)
            group.optionValues.add(value)
            OptionValueResponse.from(value)
        }!!

    fun updateOptionValue(id: Long?, request: OptionValueRequest): OptionValueResponse =
        writes.execute {
            JpaCatalogProducts.requireWriter()
            val value = values.findByIdWithLock(id)
                .orElseThrow { IllegalArgumentException("옵션 값을 찾을 수 없습니다: $id") }
            val affected = skus.findProductIdsByOptionValueId(id)
            value.updateName(request.name)
            publishAffected(affected)
            OptionValueResponse.from(value)
        }!!

    fun deleteOptionValue(id: Long?) {
        writes.executeWithoutResult {
            JpaCatalogProducts.requireWriter()
            val value = values.findById(
                id ?: throw IllegalArgumentException("The given id must not be null"),
            )
                .orElseThrow { IllegalArgumentException("옵션 값을 찾을 수 없습니다: $id") }
            if (values.isOptionValueInUse(id)) {
                throw IllegalStateException("사용 중인 옵션 값은 삭제할 수 없습니다.")
            }
            values.delete(value)
        }
    }

    private fun publishAffected(affected: List<Long>) {
        affected.stream().sorted().forEach { id ->
            val product = products.findByIdWithLock(id)
                .orElseThrow { IllegalStateException("옵션에 연결된 상품을 찾을 수 없습니다: $id") }
            if (!skus.findAllByProductIdWithLock(id).isEmpty()) {
                snapshots.publishProductSnapshot(product)
            }
        }
    }
}
