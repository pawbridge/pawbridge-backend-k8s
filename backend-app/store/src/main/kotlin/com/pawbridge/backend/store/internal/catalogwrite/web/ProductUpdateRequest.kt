package com.pawbridge.backend.store.internal.catalogwrite.web

import com.pawbridge.backend.store.internal.memberread.infrastructure.jpa.model.ProductStatus

class ProductUpdateRequest {
    var name: String? = null
        private set

    var description: String? = null
        private set

    var imageUrl: String? = null
        private set

    var status: ProductStatus? = null
        private set

    var categoryId: Long? = null
        private set

    // 개별 SKU 업데이트 목록
    var skus: List<SkuUpdateDto>? = null
        private set
}
