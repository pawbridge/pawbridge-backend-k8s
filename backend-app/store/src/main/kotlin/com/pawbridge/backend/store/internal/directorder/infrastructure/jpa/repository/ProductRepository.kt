package com.pawbridge.backend.store.internal.directorder.infrastructure.jpa.repository

import com.pawbridge.backend.store.internal.memberread.infrastructure.jpa.model.Product
import jakarta.persistence.LockModeType
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Lock
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param
import java.util.Optional

// Nullable parameter: Spring Data rejects null for non-null Kotlin parameters, which the Java interface accepted.
interface ProductRepository : JpaRepository<Product, Long> {

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT p FROM Product p WHERE p.id = :id")
    fun findByIdWithLock(@Param("id") id: Long?): Optional<Product>
}
