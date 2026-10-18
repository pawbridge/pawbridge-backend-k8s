package com.pawbridge.backend.community.internal.post.domain;

public final class PostBoardNotAllowedException extends RuntimeException {
    public PostBoardNotAllowedException() {
        super("실종·목격 제보는 전용 등록 화면에서 작성해 주세요.");
    }
}
