package com.pawbridge.backend.store.internal.catalogwrite.web

class OptionGroupRequest {
    var name: String? = null
        private set

    var values: List<String>? = null // 옵션 값 목록 (생성 시 함께 추가 가능)
        private set
}
