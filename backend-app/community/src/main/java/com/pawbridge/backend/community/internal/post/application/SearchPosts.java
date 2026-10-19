package com.pawbridge.backend.community.internal.post.application;

import com.pawbridge.backend.user.api.UserNicknameQuery;
import com.pawbridge.backend.user.api.UserNicknameUnavailableException;
import java.util.ArrayList;
import java.util.List;

public final class SearchPosts {
    private final PostSearchReader postSearchReader;
    private final UserNicknameQuery userNicknameQuery;

    public SearchPosts(PostSearchReader postSearchReader, UserNicknameQuery userNicknameQuery) {
        this.postSearchReader = postSearchReader;
        this.userNicknameQuery = userNicknameQuery;
    }

    public List<PostDetails> search(String keyword) {
        List<PostSnapshot> posts = postSearchReader.search(keyword);
        List<PostDetails> details = new ArrayList<>(posts.size());
        for (PostSnapshot post : posts) {
            String nickname = findAuthorNickname(post.authorId());
            details.add(new PostDetails(
                    post.postId(), post.authorId(), nickname, post.title(), post.content(),
                    post.boardType(), post.imageUrls(), post.createdAt(), post.updatedAt()));
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
