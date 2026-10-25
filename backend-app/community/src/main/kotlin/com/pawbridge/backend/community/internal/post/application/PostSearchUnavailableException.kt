package com.pawbridge.backend.community.internal.post.application

class PostSearchUnavailableException : RuntimeException {
    constructor() : super("검색 서비스를 사용할 수 없습니다.")

    constructor(cause: Throwable?) : super("검색 서비스를 사용할 수 없습니다.", cause)
}
