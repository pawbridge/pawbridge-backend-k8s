package com.pawbridge.backend.community.internal.post.application;

import com.pawbridge.backend.user.api.UserNicknameQuery;
import com.pawbridge.backend.user.api.UserNicknameUnavailableException;

public final class ReadPost {
    private final PostReader postReader;
    private final UserNicknameQuery userNicknameQuery;

    public ReadPost(PostReader postReader, UserNicknameQuery userNicknameQuery) {
        this.postReader = postReader;
        this.userNicknameQuery = userNicknameQuery;
    }

    public PostDetails read(long postId) {
        PostSnapshot post = postReader.findById(postId)
                .orElseThrow(PostNotFoundException::new);
        String nickname = findAuthorNickname(post.authorId());

        return new PostDetails(
                post.postId(), post.authorId(), nickname, post.title(), post.content(),
                post.boardType(), post.imageUrls(), post.createdAt(), post.updatedAt());
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
