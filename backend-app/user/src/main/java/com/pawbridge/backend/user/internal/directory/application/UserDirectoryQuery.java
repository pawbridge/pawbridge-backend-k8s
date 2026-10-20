package com.pawbridge.backend.user.internal.directory.application;

import com.pawbridge.backend.user.internal.profile.application.UserProfile;
import java.util.List;

public interface UserDirectoryQuery {
    Slice page(String keyword, String role, Request request);
    record Order(String property, boolean ascending, boolean ignoreCase) {}
    record Request(int number, int size, List<Order> orders) {
        public Request { orders = List.copyOf(orders); }
        public long offset() { return (long) number * size; }
    }
    record Slice(List<UserProfile> content, long total) {
        public Slice { content = List.copyOf(content); }
    }
}
