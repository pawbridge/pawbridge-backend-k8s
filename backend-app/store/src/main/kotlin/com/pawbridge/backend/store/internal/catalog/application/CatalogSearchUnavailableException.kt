package com.pawbridge.backend.store.internal.catalog.application

class CatalogSearchUnavailableException : RuntimeException {

    constructor() : super(MESSAGE)

    constructor(cause: Throwable?) : super(MESSAGE, cause)

    private companion object {
        private const val MESSAGE = "검색 서비스를 준비 중입니다. 잠시 후 다시 시도해 주세요."
    }
}
