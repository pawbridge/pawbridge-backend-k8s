package com.pawbridge.backend.store.internal.memberread.infrastructure.jpa.model;

import com.pawbridge.backend.store.internal.memberread.infrastructure.jpa.model.BaseEntity;
import com.pawbridge.backend.store.internal.memberread.infrastructure.jpa.model.ProductSKU;
import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Entity
@Getter
@Table(name = "order_items")
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class OrderItem extends BaseEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "order_id", nullable = false)
    private Order order;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "product_sku_id", nullable = false)
    private ProductSKU productSKU;

    @Column(nullable = false, length = 100)
    private String productName; // Snapshot

    @Column(nullable = false, length = 50)
    private String skuCode; // Snapshot

    @Column(nullable = false)
    private Long price; // Snapshot

    @Column(nullable = false)
    private Integer quantity;

    @Builder
    public OrderItem(Order order, ProductSKU productSKU, String productName, String skuCode, Long price, Integer quantity) {
        this.order = order;
        this.productSKU = productSKU;
        this.productName = productName;
        this.skuCode = skuCode;
        this.price = price;
        this.quantity = quantity;
    }
}
