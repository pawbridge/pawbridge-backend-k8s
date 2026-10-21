package com.pawbridge.backend.store.internal.catalogwrite.infrastructure;

import com.pawbridge.backend.store.internal.catalogwrite.infrastructure.jpa.repository.CategoryRepository;
import com.pawbridge.backend.store.internal.catalogwrite.web.CategoryCreateRequest;
import com.pawbridge.backend.store.internal.catalogwrite.web.CategoryResponse;
import com.pawbridge.backend.store.internal.catalogwrite.web.CategoryUpdateRequest;
import com.pawbridge.backend.store.internal.memberread.infrastructure.jpa.model.Category;
import jakarta.persistence.EntityManager;
import org.springframework.data.jpa.repository.support.JpaRepositoryFactory;
import org.springframework.transaction.support.TransactionOperations;

/** Original CategoryService create/update/delete on the existing Store writer; responses are built inside the writer TX. */
public final class JpaCatalogCategories {
    private final CategoryRepository categories;
    private final TransactionOperations writes;

    public JpaCatalogCategories(EntityManager sharedWriter,TransactionOperations writes) {
        this(new JpaRepositoryFactory(sharedWriter).getRepository(CategoryRepository.class),writes);
    }

    JpaCatalogCategories(CategoryRepository categories,TransactionOperations writes) {
        this.categories=categories;this.writes=writes;
    }

    public CategoryResponse createCategory(CategoryCreateRequest request) {
        return writes.execute(tx->{
            JpaCatalogProducts.requireWriter();
            Category parent=null;
            if(request.getParentId()!=null) parent=categories.findById(request.getParentId())
                    .orElseThrow(()->new IllegalArgumentException("Parent category not found"));
            Category category=Category.builder().name(request.getName()).description(request.getDescription()).parent(parent).build();
            categories.save(category);
            return CategoryResponse.from(category);
        });
    }

    public void deleteCategory(Long categoryId) {
        writes.executeWithoutResult(tx->{
            JpaCatalogProducts.requireWriter();
            Category category=categories.findById(categoryId)
                    .orElseThrow(()->new IllegalArgumentException("Category not found: "+categoryId));
            if(!category.getChildren().isEmpty()) throw new IllegalStateException("하위 카테고리가 있어 삭제할 수 없습니다.");
            categories.delete(category);
        });
    }

    public CategoryResponse updateCategory(Long categoryId,CategoryUpdateRequest request) {
        return writes.execute(tx->{
            JpaCatalogProducts.requireWriter();
            Category category=categories.findById(categoryId)
                    .orElseThrow(()->new IllegalArgumentException("Category not found: "+categoryId));
            Category newParent=null;
            if(request.getParentId()!=null) {
                if(request.getParentId().equals(categoryId))
                    throw new IllegalArgumentException("카테고리는 자기 자신을 부모로 가질 수 없습니다.");
                newParent=categories.findById(request.getParentId())
                        .orElseThrow(()->new IllegalArgumentException("Parent category not found: "+request.getParentId()));
                if(isDescendant(category,newParent))
                    throw new IllegalArgumentException("자신의 하위 카테고리를 부모로 지정할 수 없습니다.");
            }
            category.update(request.getName(),request.getDescription(),newParent);
            return CategoryResponse.from(category);
        });
    }

    private static boolean isDescendant(Category parent,Category target) {
        if(parent.getChildren().isEmpty()) return false;
        for(Category child:parent.getChildren()) {
            if(child.getId().equals(target.getId())) return true;
            if(isDescendant(child,target)) return true;
        }
        return false;
    }
}
