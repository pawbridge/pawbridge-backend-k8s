package com.pawbridge.backend.community.internal.post.application;

import com.pawbridge.backend.community.internal.post.domain.PostBoardType;
import com.pawbridge.backend.community.internal.post.domain.PostToCreate;
import com.pawbridge.backend.user.api.UserNicknameQuery;
import com.pawbridge.backend.user.api.UserNicknameUnavailableException;
import java.util.List;

public final class CreatePost {
    private final PostMediaUploader mediaUploader;
    private final UserNicknameQuery userNicknameQuery;
    private final PostWriter writer;

    public CreatePost(PostMediaUploader mediaUploader, UserNicknameQuery userNicknameQuery, PostWriter writer) {
        this.mediaUploader = mediaUploader;
        this.userNicknameQuery = userNicknameQuery;
        this.writer = writer;
    }

    public PostDetails create(long authorId, String title, String content,
                              PostBoardType boardType, List<PostUpload> files) {
        boardType.requireGeneralPost();
        List<String> imageUrls = mediaUploader.upload(files);
        String nickname = findAuthorNickname(authorId);
        // Each adapter owns its short transaction; uploads never hold a DB connection.
        PostSnapshot post = writer.create(new PostToCreate(authorId, title, content, boardType, imageUrls));
        return new PostDetails(post.postId(), post.authorId(), nickname, post.title(), post.content(),
                post.boardType(), post.imageUrls(), post.createdAt(), post.updatedAt());
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
