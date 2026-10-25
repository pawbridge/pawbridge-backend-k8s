package com.pawbridge.backend.community.internal.post.domain

/** Ownership applies to the general-post route regardless of the caller's role. */
object PostOwnership {

    @JvmStatic
    fun requireAuthor(storedAuthorId: Long, requestingAuthorId: Long) {
        if (storedAuthorId != requestingAuthorId) {
            throw PostNotOwnedException()
        }
    }
}
