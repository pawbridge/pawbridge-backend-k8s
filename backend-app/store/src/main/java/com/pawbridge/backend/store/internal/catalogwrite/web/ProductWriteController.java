package com.pawbridge.backend.store.internal.catalogwrite.web;

import com.pawbridge.backend.store.internal.catalogwrite.infrastructure.JpaCatalogProducts;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

/** Root explicitly registers this controller behind the original administrator boundary. */
@RestController
@RequestMapping("/api/v1/products")
public final class ProductWriteController {
    private final JpaCatalogProducts products;
    public ProductWriteController(JpaCatalogProducts products){this.products=products;}
    @PostMapping public ResponseEntity<ProductResponse> createProduct(@RequestBody ProductCreateRequest request){
        return ResponseEntity.status(201).body(products.createProduct(request));
    }
    @PatchMapping("/{productId}") public ResponseEntity<ProductResponse> updateProduct(
            @PathVariable("productId") Long id,@RequestBody ProductUpdateRequest request){
        return ResponseEntity.ok(products.updateProduct(id,request));
    }
    @DeleteMapping("/{productId}") public ResponseEntity<Void> deleteProduct(@PathVariable("productId") Long id){
        products.deleteProduct(id);return ResponseEntity.noContent().build();
    }
}
