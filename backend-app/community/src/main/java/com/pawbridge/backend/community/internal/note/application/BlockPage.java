package com.pawbridge.backend.community.internal.note.application;

import java.time.Instant;
import java.util.List;

public record BlockPage(List<BlockView> content, long totalElements, int totalPages, int number) {
    public record BlockView(Long memberId, String nickname, Instant createdAt) {
    }
}
