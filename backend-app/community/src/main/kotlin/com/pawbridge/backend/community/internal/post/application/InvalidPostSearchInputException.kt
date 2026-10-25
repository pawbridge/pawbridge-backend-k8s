package com.pawbridge.backend.community.internal.post.application

class InvalidPostSearchInputException : RuntimeException {
    constructor() : super("검색 입력을 확인해 주세요.")

    constructor(cause: Throwable?) : super("검색 입력을 확인해 주세요.", cause)
}
