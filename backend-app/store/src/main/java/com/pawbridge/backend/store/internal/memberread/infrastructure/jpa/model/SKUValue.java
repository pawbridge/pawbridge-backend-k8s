package com.pawbridge.backend.store.internal.memberread.infrastructure.jpa.model;

import com.pawbridge.backend.store.internal.memberread.infrastructure.jpa.model.BaseEntity;
import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Entity
@Getter
@Table(name = "sku_values")
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class SKUValue extends BaseEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "product_sku_id", nullable = false)
    private ProductSKU productSKU;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "option_value_id", nullable = false)
    private OptionValue optionValue;

    @Builder
    public SKUValue(ProductSKU productSKU, OptionValue optionValue) {
        this.productSKU = productSKU;
        this.optionValue = optionValue;
    }
}
