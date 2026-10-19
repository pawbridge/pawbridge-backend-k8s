package com.pawbridge.backend.community.internal.post.application;

import com.pawbridge.backend.community.internal.post.domain.PostOwnership;

public final class DeletePost {
    private final PostReader reader;
    private final PostMediaRemover media;
    private final PostDeletion deletion;

    public DeletePost(PostReader reader, PostMediaRemover media, PostDeletion deletion) {
        this.reader = reader;
        this.media = media;
        this.deletion = deletion;
    }

    public void delete(long postId, long authorId) {
        PostSnapshot post = reader.findById(postId).orElseThrow(PostNotFoundException::new);
        PostOwnership.requireAuthor(post.authorId(), authorId);
        // The reader transaction has returned its connection before external I/O begins.
        media.remove(post.imageUrls());
        // Media is best effort. A later DB rollback or unknown commit never restores or retries it.
        deletion.delete(postId, authorId);
    }
}
