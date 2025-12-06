package com.pawbridge.storeservice.domain.cart.entity;

import com.pawbridge.storeservice.common.entity.BaseEntity;
import com.pawbridge.storeservice.domain.product.entity.ProductSKU;
import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Entity
@Getter
@Table(name = "cart_items")
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class CartItem extends BaseEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "cart_id", nullable = false)
    private Cart cart;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "product_sku_id", nullable = false)
    private ProductSKU productSKU;

    @Column(nullable = false)
    private Integer quantity;

    @Builder
    public CartItem(Cart cart, ProductSKU productSKU, Integer quantity) {
        this.cart = cart;
        this.productSKU = productSKU;
        this.quantity = quantity;
    }
    
    public void updateQuantity(int quantity) {
        this.quantity = quantity;
    }
}
