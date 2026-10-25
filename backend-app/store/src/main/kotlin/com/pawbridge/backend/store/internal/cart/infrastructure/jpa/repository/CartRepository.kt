package com.pawbridge.backend.store.internal.cart.infrastructure.jpa.repository

import com.pawbridge.backend.store.internal.cart.infrastructure.jpa.model.Cart
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.stereotype.Repository
import java.util.Optional

// Nullable parameter: Spring Data rejects null for non-null Kotlin parameters, which the Java interface accepted.
@Repository
interface CartRepository : JpaRepository<Cart, Long> {
    fun findByUserId(userId: Long?): Optional<Cart>
}
