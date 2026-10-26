package com.pawbridge.backend.store.internal.catalogwrite.infrastructure.jpa.repository

import com.pawbridge.backend.store.internal.memberread.infrastructure.jpa.model.Category
import org.springframework.data.jpa.repository.JpaRepository
import java.util.Optional

// Nullable parameter: Spring Data rejects null for non-null Kotlin parameters, which the Java interface accepted.
interface CategoryRepository : JpaRepository<Category, Long> {
    fun findByName(name: String?): Optional<Category>
}
