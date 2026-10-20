package com.pawbridge.backend.store.internal.memberread.infrastructure.jpa.model;

import com.pawbridge.backend.store.internal.memberread.infrastructure.jpa.model.BaseEntity;
import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Entity
@Getter
@Table(name = "wishlists")
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Wishlist extends BaseEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private Long userId;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "sku_id", nullable = false)
    private ProductSKU sku;

    @Builder
    public Wishlist(Long userId, ProductSKU sku) {
        this.userId = userId;
        this.sku = sku;
    }
}
