package com.pawbridge.storeservice.domain.product.entity;

import com.pawbridge.storeservice.common.entity.BaseEntity;
import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.util.ArrayList;
import java.util.List;

/**
 * 옵션 그룹 (표준화된 마스터 테이블)
 * - 상품과 독립적으로 미리 등록
 * - 예: Color, Size, Material
 */
@Entity
@Getter
@Table(name = "option_groups")
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class OptionGroup extends BaseEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, length = 50, unique = true)
    private String name; // e.g., Color, Size

    @OneToMany(mappedBy = "optionGroup", cascade = CascadeType.ALL, orphanRemoval = true)
    private List<OptionValue> optionValues = new ArrayList<>();

    @Builder
    public OptionGroup(String name) {
        this.name = name;
    }

    public void updateName(String name) {
        this.name = name;
    }
}

