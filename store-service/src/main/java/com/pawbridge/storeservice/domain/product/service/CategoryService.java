package com.pawbridge.storeservice.domain.product.service;

import com.pawbridge.storeservice.domain.product.dto.CategoryCreateRequest;
import com.pawbridge.storeservice.domain.product.dto.CategoryUpdateRequest;
import com.pawbridge.storeservice.domain.product.dto.CategoryResponse;
import com.pawbridge.storeservice.domain.product.entity.Category;
import com.pawbridge.storeservice.domain.product.repository.CategoryRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class CategoryService {

    private final CategoryRepository categoryRepository;

    @Transactional
    public CategoryResponse createCategory(CategoryCreateRequest request) {
        Category parent = null;
        if (request.getParentId() != null) {
            parent = categoryRepository.findById(request.getParentId())
                    .orElseThrow(() -> new IllegalArgumentException("Parent category not found"));
        }

        Category category = Category.builder()
                .name(request.getName())
                .parent(parent)
                .build();
        
        categoryRepository.save(category);
        return CategoryResponse.from(category);
    }

    @Transactional(readOnly = true)
    public List<CategoryResponse> getAllCategories() {
        // 현재는 상위 카테고리가 없는(null) 루트 카테고리만 가져와서, 자식 카테고리는 재귀호출이나 지연 로딩으로 처리됨을 가정
        // 참고: 이 방식은 커스텀 쿼리나 필터링이 필요할 수 있음
        // 개선안: Repository에 findByParentIsNull() 메서드를 추가하는 것이 효율적임
        // MVP 단계이므로 모든 카테고리를 가져와서 메모리에서 필터링하거나, 간단한 구현을 유지
        // N+1 문제를 방지하기 위해 추후 Batch Fetching 적용 필요
        
        List<Category> all = categoryRepository.findAll();
        return all.stream()
                .filter(c -> c.getParent() == null)
                .map(CategoryResponse::from)
                .collect(Collectors.toList());
    }

    @Transactional
    public void deleteCategory(Long categoryId) {
        Category category = categoryRepository.findById(categoryId)
                .orElseThrow(() -> new IllegalArgumentException("Category not found: " + categoryId));
        
        // 하위 카테고리가 있는지 확인
        if (!category.getChildren().isEmpty()) {
            throw new IllegalStateException("하위 카테고리가 있어 삭제할 수 없습니다.");
        }
        
        categoryRepository.delete(category);
    }

    /**
     * 카테고리 수정
     * @param categoryId 수정할 카테고리 ID
     * @param request 수정 요청 DTO
     * @return 수정된 카테고리 응답
     */
    @Transactional
    public CategoryResponse updateCategory(Long categoryId, CategoryUpdateRequest request) {
        Category category = categoryRepository.findById(categoryId)
                .orElseThrow(() -> new IllegalArgumentException("Category not found: " + categoryId));

        // 부모 카테고리 변경 처리
        Category newParent = null;
        if (request.getParentId() != null) {
            // 자기 자신을 부모로 지정하는 것 방지
            if (request.getParentId().equals(categoryId)) {
                throw new IllegalArgumentException("카테고리는 자기 자신을 부모로 가질 수 없습니다.");
            }
            newParent = categoryRepository.findById(request.getParentId())
                    .orElseThrow(() -> new IllegalArgumentException("Parent category not found: " + request.getParentId()));
            
            // 순환 참조 방지: 자신의 자손을 부모로 지정하는 것 방지
            if (isDescendant(category, newParent)) {
                throw new IllegalArgumentException("자신의 하위 카테고리를 부모로 지정할 수 없습니다.");
            }
        }

        category.update(request.getName(), newParent);
        return CategoryResponse.from(category);
    }

    /**
     * 순환 참조 체크: target이 parent의 자손인지 확인
     * @param parent 부모 카테고리
     * @param target 대상 카테고리
     * @return target이 parent의 자손이면 true
     */
    private boolean isDescendant(Category parent, Category target) {
        if (parent.getChildren().isEmpty()) {
            return false;
        }
        for (Category child : parent.getChildren()) {
            if (child.getId().equals(target.getId())) {
                return true;
            }
            if (isDescendant(child, target)) {
                return true;
            }
        }
        return false;
    }
}
