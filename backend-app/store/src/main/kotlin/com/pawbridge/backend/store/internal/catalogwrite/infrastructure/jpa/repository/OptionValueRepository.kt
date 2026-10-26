package com.pawbridge.backend.store.internal.catalogwrite.infrastructure.jpa.repository

import com.pawbridge.backend.store.internal.memberread.infrastructure.jpa.model.OptionValue
import jakarta.persistence.LockModeType
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Lock
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param
import java.util.Optional

// Nullable parameters: Spring Data rejects null for non-null Kotlin parameters, which the Java interface accepted.
interface OptionValueRepository : JpaRepository<OptionValue, Long> {

    @Query("SELECT COUNT(sv) > 0 FROM SKUValue sv WHERE sv.optionValue.id = :optionValueId")
    fun isOptionValueInUse(@Param("optionValueId") optionValueId: Long?): Boolean

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT ov FROM OptionValue ov WHERE ov.id = :id")
    fun findByIdWithLock(@Param("id") id: Long?): Optional<OptionValue>

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT ov FROM OptionValue ov WHERE ov.id IN :ids ORDER BY ov.id")
    fun findAllByIdWithLock(@Param("ids") ids: Collection<Long?>?): List<OptionValue>

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT ov FROM OptionValue ov WHERE ov.optionGroup.id = :groupId ORDER BY ov.id")
    fun findAllByOptionGroupIdWithLock(@Param("groupId") groupId: Long?): List<OptionValue>
}
