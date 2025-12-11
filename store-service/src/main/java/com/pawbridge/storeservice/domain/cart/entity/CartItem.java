package com.pawbridge.storeservice.domain.cart.entity;

import com.pawbridge.storeservice.common.entity.BaseEntity;
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

    @Column(name = "sku_id", nullable = false)
    private Long skuId;

    @Column(name = "product_sku_id", nullable = false)
    private Long productSkuId;

    @Column(nullable = false)
    private Integer quantity;

    @Builder
    public CartItem(Long skuId, Integer quantity) {
        this.skuId = skuId;
        this.productSkuId = skuId;
        this.quantity = quantity;
    }

    public void assignCart(Cart cart) {
        this.cart = cart;
    }

    public void updateQuantity(Integer quantity) {
        this.quantity = quantity;
    }
}
