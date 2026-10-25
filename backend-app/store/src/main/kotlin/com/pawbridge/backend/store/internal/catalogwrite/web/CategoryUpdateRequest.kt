package com.pawbridge.backend.store.internal.catalogwrite.web

/**
 * 카테고리 수정 요청 DTO
 */
class CategoryUpdateRequest {
    var name: String? = null
        private set

    var description: String? = null
        private set

    var parentId: Long? = null // null이면 루트 카테고리로 변경
        private set
}
