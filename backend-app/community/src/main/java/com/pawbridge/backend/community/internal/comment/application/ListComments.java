package com.pawbridge.backend.community.internal.comment.application;

import com.pawbridge.backend.user.api.UserNicknameQuery;
import com.pawbridge.backend.user.api.UserNicknameUnavailableException;
import java.util.ArrayList;
import java.util.List;

public final class ListComments {
    private final CommentListReader commentListReader;
    private final UserNicknameQuery userNicknameQuery;

    public ListComments(CommentListReader commentListReader, UserNicknameQuery userNicknameQuery) {
        this.commentListReader = commentListReader;
        this.userNicknameQuery = userNicknameQuery;
    }

    public List<CommentDetails> read(long postId) {
        List<CommentSnapshot> comments = commentListReader.findByPostId(postId);
        List<CommentDetails> details = new ArrayList<>(comments.size());
        for (CommentSnapshot comment : comments) {
            String nickname = findAuthorNickname(comment.authorId());
            details.add(new CommentDetails(
                    comment.commentId(), comment.postId(), comment.authorId(), nickname, comment.content(),
                    comment.createdAt(), comment.updatedAt()));
        }
        return details;
    }

    private String findAuthorNickname(long authorId) {
        try {
            String nickname = userNicknameQuery.findNickname(authorId)
                    .orElseGet(() -> missingNickname(authorId));
            // The legacy HTTP decoder represents a zero-byte String body as null.
            return nickname.isEmpty() ? null : nickname;
        } catch (UserNicknameUnavailableException unavailable) {
            return missingNickname(authorId);
        }
    }

    private String missingNickname(long authorId) {
        return "사용자" + authorId;
    }
}
