package com.pawbridge.backend.community.internal.post.application;

import com.pawbridge.backend.community.internal.post.domain.PostOwnership;
import com.pawbridge.backend.user.api.UserNicknameQuery;
import com.pawbridge.backend.user.api.UserNicknameUnavailableException;
import java.util.List;

public final class UpdatePost {
    private final PostReader reader;
    private final PostMediaRemover remover;
    private final PostMediaUploader uploader;
    private final UserNicknameQuery users;
    private final PostModification modification;

    public UpdatePost(PostReader reader, PostMediaRemover remover, PostMediaUploader uploader,
                      UserNicknameQuery users, PostModification modification) {
        this.reader=reader;this.remover=remover;this.uploader=uploader;this.users=users;this.modification=modification;
    }

    public PostDetails update(long postId, long authorId, String title, String content, List<PostUpload> files) {
        PostSnapshot previous=reader.findById(postId).orElseThrow(PostNotFoundException::new);
        PostOwnership.requireAuthor(previous.authorId(),authorId);
        return update(previous,title,content,files);
    }

    /** The separately authorized admin route changes the post without an author ownership check. */
    public PostDetails updateAsAdmin(long postId, String title, String content, List<PostUpload> files) {
        PostSnapshot previous=reader.findById(postId).orElseThrow(PostNotFoundException::new);
        return update(previous,title,content,files);
    }

    private PostDetails update(PostSnapshot previous, String title, String content, List<PostUpload> files) {
        List<String> imageUrls=null;
        // One empty multipart file still requests replacement; the uploader then returns an empty list.
        if(!files.isEmpty()) {
            remover.remove(previous.imageUrls());
            imageUrls=uploader.upload(files);
        }
        String nickname=nickname(previous.authorId());
        // Reader, media and nickname I/O have finished before the short locking writer transaction.
        // The stored author is post data, never the admin caller's identity. The writer still locks and rechecks it.
        PostSnapshot post=modification.update(previous.postId(),previous.authorId(),title,content,imageUrls);
        return new PostDetails(post.postId(),post.authorId(),nickname,post.title(),post.content(),
                post.boardType(),post.imageUrls(),post.createdAt(),post.updatedAt());
    }

    private String nickname(long authorId) {
        try {
            String value=users.findNickname(authorId).orElse("사용자"+authorId);
            return value.isEmpty()?null:value;
        } catch(UserNicknameUnavailableException unavailable) { return "사용자"+authorId; }
    }
}
