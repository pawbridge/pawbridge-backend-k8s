package com.pawbridge.storeservice.domain.product.repository;

import com.pawbridge.storeservice.domain.product.entity.SKUValue;
import org.springframework.data.jpa.repository.JpaRepository;

public interface SKUValueRepository extends JpaRepository<SKUValue, Long> {
}
