package com.pawbridge.backend.community.internal.post.application;

import java.util.List;

public final class ReadAdminPosts {
    private final PostPageReader posts;
    private final ReadPost details;
    public ReadAdminPosts(PostPageReader posts, ReadPost details) {
        this.posts = posts;
        this.details = details;
    }
    public Page read(PostPageReader.Request request) {
        PostPageReader.Slice page = posts.findPage(request);
        return new Page(page.content().stream().map(details::details).toList(), page.total());
    }
    public record Page(List<PostDetails> content, long total) {
        public Page { content = List.copyOf(content); }
    }
}
