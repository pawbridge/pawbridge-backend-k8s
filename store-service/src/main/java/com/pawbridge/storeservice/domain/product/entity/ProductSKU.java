package com.pawbridge.storeservice.domain.product.entity;

import com.pawbridge.storeservice.common.entity.BaseEntity;
import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.util.ArrayList;
import java.util.List;

@Entity
@Getter
@Table(name = "product_skus")
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class ProductSKU extends BaseEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "product_id", nullable = false)
    private Product product;

    @Column(nullable = false, unique = true, length = 50)
    private String skuCode; // e.g., TSHIRT-RED-L

    @Column(nullable = false)
    private Long price;

    @Column(nullable = false)
    private Integer stockQuantity;

    @OneToMany(mappedBy = "productSKU", cascade = CascadeType.ALL, orphanRemoval = true)
    private List<SKUValue> skuValues = new ArrayList<>();

    @Builder
    public ProductSKU(Product product, String skuCode, Long price, Integer stockQuantity) {
        this.product = product;
        this.skuCode = skuCode;
        this.price = price;
        this.stockQuantity = stockQuantity;
    }

    // Business Logic: Decrease Stock
    public void decreaseStock(int quantity) {
        if (this.stockQuantity < quantity) {
            throw new IllegalStateException("Out of stock"); // Custom Exception 처리 필요
        }
        this.stockQuantity -= quantity;
    }

    // Business Logic: Increase Stock (Rollback)
    public void increaseStock(int quantity) {
        this.stockQuantity += quantity;
    }
}
