package com.pawbridge.backend.user.internal.statistics.web;

import com.pawbridge.backend.user.internal.statistics.application.TotalUserCountQuery;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
public final class TotalUserCountController {
    private final TotalUserCountQuery users;

    public TotalUserCountController(TotalUserCountQuery users) {
        this.users = users;
    }

    @GetMapping("/api/v1/admin/users/stats/total-users")
    public Response<Long> count() {
        return new Response<>(200, "요청이 성공적으로 처리되었습니다.", users.count());
    }

    public record Response<T>(int code, String message, T data) {
    }
}
