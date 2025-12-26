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
@Table(name = "categories")
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Category extends BaseEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, unique = true)
    private String name;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "parent_id")
    private Category parent;

    @OneToMany(mappedBy = "parent", cascade = CascadeType.ALL)
    private List<Category> children = new ArrayList<>();

    @Builder
    public Category(String name, Category parent) {
        this.name = name;
        if (parent != null) {
            this.parent = parent;
            // Convenience method for bi-directional relationship if needed, 
            // but for simplicity in MVP we just set parent.
        }
    }

    /**
     * 카테고리 정보 수정
     * @param name 새로운 카테고리명
     * @param parent 새로운 부모 카테고리 (null이면 루트 카테고리)
     */
    public void update(String name, Category parent) {
        if (name != null && !name.isBlank()) {
            this.name = name;
        }
        this.parent = parent;
    }
}
