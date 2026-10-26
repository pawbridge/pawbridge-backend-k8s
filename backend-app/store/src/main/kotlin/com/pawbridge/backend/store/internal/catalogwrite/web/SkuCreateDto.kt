package com.pawbridge.backend.store.internal.catalogwrite.web

/**
 * SKU 생성 DTO (표준화 버전)
 * - 옵션 값을 ID로 지정
 */
class SkuCreateDto {
    var skuCode: String? = null
        private set

    var price: Long? = null
        private set

    var stockQuantity: Int? = null
        private set

    // 옵션 값 ID 목록 (예: [1, 4] = Red + L)
    var optionValueIds: List<Long>? = null
        private set
}
