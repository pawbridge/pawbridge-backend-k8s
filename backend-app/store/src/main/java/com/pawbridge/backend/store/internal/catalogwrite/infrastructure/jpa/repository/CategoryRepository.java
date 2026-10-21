package com.pawbridge.backend.store.internal.catalogwrite.infrastructure.jpa.repository;

import com.pawbridge.backend.store.internal.memberread.infrastructure.jpa.model.Category;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface CategoryRepository extends JpaRepository<Category, Long> {
    Optional<Category> findByName(String name);
}
