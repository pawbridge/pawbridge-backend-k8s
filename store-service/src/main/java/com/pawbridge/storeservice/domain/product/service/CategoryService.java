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
        // Fetch only root categories (parent is null) and let recursion handle children
        // Note: This requires a custom query or filtering. For MVP, we'll fetch all and filter roots
        // Better efficient way: Repository method findByParentIsNull()
        // But since we didn't add that to repo yet, let's filter in memory for now or adding simple method.
        // Or simpler: just return all flat list? User wants hierarchy usually.
        // Let's rely on standard findAll() and filter for roots if performance allows (small data).
        // Actually, to avoid N+1, batch fetching is needed. 
        // For now, simple implementation.
        
        List<Category> all = categoryRepository.findAll();
        return all.stream()
                .filter(c -> c.getParent() == null)
                .map(CategoryResponse::from)
                .collect(Collectors.toList());
    }
}
