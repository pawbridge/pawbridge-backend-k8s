package com.pawbridge.backend.community.internal.note.application;

import java.util.List;

public record BlockedAuthors(List<Long> memberIds) {
}
