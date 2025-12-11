package com.pawbridge.storeservice.domain.product.service;

import com.pawbridge.storeservice.domain.product.dto.CategoryCreateRequest;
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
}
