package com.pawbridge.backend.community.internal.visit.web;

import com.pawbridge.backend.community.internal.visit.application.ReadTodayVisits;
import com.pawbridge.backend.community.internal.visit.application.TodayVisits;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
public final class VisitTodayController {
    private final ReadTodayVisits readTodayVisits;

    public VisitTodayController(ReadTodayVisits readTodayVisits) {
        this.readTodayVisits = readTodayVisits;
    }

    @GetMapping("/api/v1/visits/today")
    public ResponseEntity<Response<TodayVisits>> read() {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
                .body(new Response<>(200, "요청이 성공적으로 처리되었습니다.", readTodayVisits.read()));
    }

    public record Response<T>(int code, String message, T data) {
    }
}
