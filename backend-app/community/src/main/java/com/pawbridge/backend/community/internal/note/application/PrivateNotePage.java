package com.pawbridge.backend.community.internal.note.application;

import java.util.List;

public record PrivateNotePage(List<PrivateNoteView> content, long totalElements, int totalPages, int number, int size) {
}
