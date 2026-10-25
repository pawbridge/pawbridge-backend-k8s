package com.pawbridge.backend.store.internal.catalogwrite.web

/**
 * 상품 생성 요청 DTO (표준화 버전)
 */
class ProductCreateRequest {
    var name: String? = null
        private set

    var description: String? = null
        private set

    var imageUrl: String? = null
        private set

    var categoryId: Long? = null
        private set

    // SKU 목록 (옵션 값 ID로 조합)
    var skus: List<SkuCreateDto>? = null
        private set
}
