package com.pawbridge.backend.store.internal.catalogwrite.infrastructure.jpa.repository;

import com.pawbridge.backend.store.internal.memberread.infrastructure.jpa.model.SKUValue;
import org.springframework.data.jpa.repository.JpaRepository;

public interface SKUValueRepository extends JpaRepository<SKUValue, Long> {
}
