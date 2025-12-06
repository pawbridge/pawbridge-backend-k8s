package com.pawbridge.storeservice.domain.product.repository;

import com.pawbridge.storeservice.domain.product.entity.Product;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ProductRepository extends JpaRepository<Product, Long> {
}
