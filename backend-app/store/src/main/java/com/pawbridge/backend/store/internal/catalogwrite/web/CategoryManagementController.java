package com.pawbridge.backend.store.internal.catalogwrite.web;

import com.pawbridge.backend.store.internal.catalogwrite.infrastructure.JpaCatalogCategories;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

/** GET remains owned by StoreCatalogController; only original category write handlers are added. */
@RestController
@RequestMapping("/api/v1/categories")
public final class CategoryManagementController {
    private final JpaCatalogCategories categories;
    public CategoryManagementController(JpaCatalogCategories categories){this.categories=categories;}
    @PostMapping public ResponseEntity<CategoryResponse> createCategory(@RequestBody CategoryCreateRequest request){
        return ResponseEntity.status(201).body(categories.createCategory(request));
    }
    @PutMapping("/{categoryId}") public ResponseEntity<CategoryResponse> updateCategory(
            @PathVariable("categoryId") Long id,@RequestBody CategoryUpdateRequest request){
        return ResponseEntity.ok(categories.updateCategory(id,request));
    }
    @DeleteMapping("/{categoryId}") public ResponseEntity<Void> deleteCategory(@PathVariable("categoryId") Long id){
        categories.deleteCategory(id);return ResponseEntity.noContent().build();
    }
}
