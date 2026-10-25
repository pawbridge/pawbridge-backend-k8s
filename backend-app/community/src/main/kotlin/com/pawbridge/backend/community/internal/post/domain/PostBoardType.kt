package com.pawbridge.backend.community.internal.post.domain

enum class PostBoardType {
    MISSING,
    PROTECTION,
    REPORT,
    ADOPTION,
    COMMUNICATION,
    ;

    fun requireGeneralPost() {
        if (this == MISSING || this == REPORT) {
            throw PostBoardNotAllowedException()
        }
    }
}
