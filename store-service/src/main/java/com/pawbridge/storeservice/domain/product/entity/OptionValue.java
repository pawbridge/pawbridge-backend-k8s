package com.pawbridge.storeservice.domain.product.entity;

import com.pawbridge.storeservice.common.entity.BaseEntity;
import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Entity
@Getter
@Table(name = "option_values")
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class OptionValue extends BaseEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "option_group_id", nullable = false)
    private OptionGroup optionGroup;

    @Column(nullable = false, length = 50)
    private String name; // e.g., Red, L

    @Builder
    public OptionValue(OptionGroup optionGroup, String name) {
        this.optionGroup = optionGroup;
        this.name = name;
    }
}
