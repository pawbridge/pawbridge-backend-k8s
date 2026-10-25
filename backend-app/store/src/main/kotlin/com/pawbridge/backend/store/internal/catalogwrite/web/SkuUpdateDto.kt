package com.pawbridge.backend.store.internal.catalogwrite.web

class SkuUpdateDto {
    var id: Long? = null // SKU ID (수정할 SKU 식별을 위해 필수)
        private set

    var price: Long? = null
        private set

    var stockQuantity: Int? = null
        private set

    var skuCode: String? = null // 선택적 수정
        private set
}
