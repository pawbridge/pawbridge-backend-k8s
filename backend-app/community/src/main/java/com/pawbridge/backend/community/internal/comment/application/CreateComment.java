package com.pawbridge.backend.community.internal.comment.application;

import com.pawbridge.backend.community.internal.post.application.PostNotFoundException;
import com.pawbridge.backend.community.internal.post.application.PostReader;
import com.pawbridge.backend.user.api.UserNicknameQuery;
import com.pawbridge.backend.user.api.UserNicknameUnavailableException;

public final class CreateComment {
    private final PostReader posts;
    private final UserNicknameQuery userNicknameQuery;
    private final CommentWriter writer;

    public CreateComment(PostReader posts, UserNicknameQuery userNicknameQuery, CommentWriter writer) {
        this.posts = posts;
        this.userNicknameQuery = userNicknameQuery;
        this.writer = writer;
    }

    public CommentDetails create(long postId, long authorId, String content) {
        posts.findById(postId).orElseThrow(PostNotFoundException::new);
        String nickname = findAuthorNickname(authorId);
        // Both reads have returned before the short comment write transaction starts.
        CommentSnapshot comment = writer.create(postId, authorId, content);
        return new CommentDetails(
                comment.commentId(), comment.postId(), comment.authorId(), nickname, comment.content(),
                comment.createdAt(), comment.updatedAt());
    }

    private String findAuthorNickname(long authorId) {
        try {
            String nickname = userNicknameQuery.findNickname(authorId).orElse("사용자" + authorId);
            return nickname.isEmpty() ? null : nickname;
        } catch (UserNicknameUnavailableException unavailable) {
            return "사용자" + authorId;
        }
    }
}
