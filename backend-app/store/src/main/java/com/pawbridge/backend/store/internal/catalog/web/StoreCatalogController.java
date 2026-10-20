package com.pawbridge.backend.store.internal.catalog.web;

import com.pawbridge.backend.store.internal.catalog.application.StoreCatalog;
import java.util.List;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** Boot must opt in explicitly; no component scan or security registration is supplied here. */
@RestController
public final class StoreCatalogController {
    private final StoreCatalog catalog;
    public StoreCatalogController(StoreCatalog catalog) { this.catalog = catalog; }

    @GetMapping("/api/v1/products")
    public StoreCatalog.SearchResult search(
            @RequestParam(name="keyword",required=false) String keyword,
            @RequestParam(name="minPrice",required=false) Long minPrice,
            @RequestParam(name="maxPrice",required=false) Long maxPrice,
            @RequestParam(name="categoryId",required=false) Long categoryId,
            @RequestParam(name="inStockOnly",defaultValue="false") Boolean inStockOnly,
            @RequestParam(name="sortBy",required=false) String sortBy,
            @RequestParam(name="sortOrder",defaultValue="desc") String sortOrder,
            @RequestParam(name="page",defaultValue="0") Integer page,
            @RequestParam(name="size",defaultValue="20") Integer size) {
        return catalog.search(new StoreCatalog.Search(keyword,minPrice,maxPrice,categoryId,inStockOnly,sortBy,sortOrder,page,size));
    }

    @GetMapping("/api/v1/products/{productId}")
    public StoreCatalog.Product product(@PathVariable("productId") long productId) { return catalog.product(productId); }

    @GetMapping("/api/v1/categories")
    public List<StoreCatalog.Category> categories() { return catalog.categories(); }

    @GetMapping("/api/v1/option-groups")
    public List<StoreCatalog.OptionGroup> optionGroups() { return catalog.optionGroups(); }

    @GetMapping("/api/v1/option-groups/{groupId}")
    public StoreCatalog.OptionGroup optionGroup(@PathVariable("groupId") long groupId) { return catalog.optionGroup(groupId); }
}
