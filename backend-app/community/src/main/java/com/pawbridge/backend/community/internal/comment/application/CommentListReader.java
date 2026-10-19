package com.pawbridge.backend.community.internal.comment.application;

import java.util.List;

public interface CommentListReader {
    List<CommentSnapshot> findByPostId(long postId);
}
