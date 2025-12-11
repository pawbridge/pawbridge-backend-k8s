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
    private String skuCode; // 예: TSHIRT-RED-L

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

    // 비즈니스 로직: 재고 감소
    public void decreaseStock(int quantity) {
        if (this.stockQuantity < quantity) {
            throw new IllegalStateException("재고 부족"); // Custom Exception 처리 필요
        }
        this.stockQuantity -= quantity;
    }

    // 비즈니스 로직: 재고 증가 (롤백용)
    public void increaseStock(int quantity) {
        this.stockQuantity += quantity;
    }

    public String generateOptionName() {
        if (this.skuValues.isEmpty()) {
            return "";
        }
        return this.skuValues.stream()
                .map(sv -> sv.getOptionValue().getOptionGroup().getName() + ": " + sv.getOptionValue().getName())
                .sorted()
                .reduce((a, b) -> a + ", " + b)
                .orElse("");
    }

    public void updatePrice(Long price) {
        this.price = price;
    }

    public void updateStock(Integer stockQuantity) {
        this.stockQuantity = stockQuantity;
    }
}
