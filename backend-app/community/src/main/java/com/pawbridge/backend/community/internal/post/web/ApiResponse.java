package com.pawbridge.backend.community.internal.post.web;

public record ApiResponse<T>(int code, String message, T data) {
}
