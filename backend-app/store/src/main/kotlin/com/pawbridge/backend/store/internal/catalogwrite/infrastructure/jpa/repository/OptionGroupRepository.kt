package com.pawbridge.backend.store.internal.catalogwrite.infrastructure.jpa.repository

import com.pawbridge.backend.store.internal.memberread.infrastructure.jpa.model.OptionGroup
import jakarta.persistence.LockModeType
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Lock
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param
import java.util.Optional

// Nullable parameters: Spring Data rejects null for non-null Kotlin parameters, which the Java interface accepted.
interface OptionGroupRepository : JpaRepository<OptionGroup, Long> {
    fun existsByName(name: String?): Boolean

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT og FROM OptionGroup og WHERE og.id = :id")
    fun findByIdWithLock(@Param("id") id: Long?): Optional<OptionGroup>
}
