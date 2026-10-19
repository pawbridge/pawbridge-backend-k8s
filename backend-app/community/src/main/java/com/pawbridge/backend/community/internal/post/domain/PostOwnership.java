package com.pawbridge.backend.community.internal.post.domain;

/** Ownership applies to the general-post route regardless of the caller's role. */
public final class PostOwnership {
    private PostOwnership() {
    }

    public static void requireAuthor(long storedAuthorId, long requestingAuthorId) {
        if (storedAuthorId != requestingAuthorId) {
            throw new PostNotOwnedException();
        }
    }
}
