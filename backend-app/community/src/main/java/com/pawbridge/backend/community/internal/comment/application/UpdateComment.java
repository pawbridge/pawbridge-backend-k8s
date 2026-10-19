package com.pawbridge.backend.community.internal.comment.application;

import com.pawbridge.backend.community.internal.comment.domain.CommentOwnership;
import com.pawbridge.backend.user.api.UserNicknameQuery;
import com.pawbridge.backend.user.api.UserNicknameUnavailableException;

public final class UpdateComment {
    private final CommentReader comments;
    private final UserNicknameQuery userNicknameQuery;
    private final CommentModification modification;

    public UpdateComment(CommentReader comments, UserNicknameQuery userNicknameQuery, CommentModification modification) {
        this.comments = comments;
        this.userNicknameQuery = userNicknameQuery;
        this.modification = modification;
    }

    public CommentDetails update(long commentId, long authorId, String content) {
        CommentSnapshot comment = comments.findById(commentId).orElseThrow(CommentNotFoundException::new);
        CommentOwnership.requireAuthor(comment.authorId(), authorId);
        // The active snapshot read has returned its connection before nickname lookup.
        String nickname = findAuthorNickname(comment.authorId());
        if (!comment.content().equals(content)) {
            modification.update(comment.commentId(), content);
        }
        // Legacy captures the response before dirty checking updates the stored audit time.
        return new CommentDetails(comment.commentId(), comment.postId(), comment.authorId(), nickname, content,
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
