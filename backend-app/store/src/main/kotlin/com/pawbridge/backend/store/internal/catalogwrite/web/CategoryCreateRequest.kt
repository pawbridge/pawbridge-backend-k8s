package com.pawbridge.backend.store.internal.catalogwrite.web

class CategoryCreateRequest {
    var name: String? = null
        private set

    var description: String? = null
        private set

    var parentId: Long? = null // 선택 사항 (없으면 루트)
        private set
}
