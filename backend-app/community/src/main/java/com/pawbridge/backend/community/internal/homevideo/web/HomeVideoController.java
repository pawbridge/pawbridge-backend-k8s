package com.pawbridge.backend.community.internal.homevideo.web;

import com.pawbridge.backend.community.internal.homevideo.application.HomeVideo;
import com.pawbridge.backend.community.internal.homevideo.application.ReadHomeVideos;
import java.util.List;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
public final class HomeVideoController {
    private final ReadHomeVideos readHomeVideos;

    public HomeVideoController(ReadHomeVideos readHomeVideos) {
        this.readHomeVideos = readHomeVideos;
    }

    @GetMapping("/api/v1/home/videos")
    public Response<List<HomeVideo>> read() {
        return new Response<>(200, "요청이 성공적으로 처리되었습니다.", readHomeVideos.read());
    }

    public record Response<T>(int code, String message, T data) {
    }
}
