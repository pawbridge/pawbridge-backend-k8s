package com.pawbridge.backend.community.internal.post.application;

import java.util.List;

public interface PostPageReader {
    Slice findPage(Request request);
    record Order(String property, boolean ascending, boolean ignoreCase) {}
    record Request(int number, int size, List<Order> orders) {
        public Request { orders = List.copyOf(orders); }
        public long offset() { return (long) number * size; }
    }
    record Slice(List<PostSnapshot> content, long total) {
        public Slice { content = List.copyOf(content); }
    }
}
