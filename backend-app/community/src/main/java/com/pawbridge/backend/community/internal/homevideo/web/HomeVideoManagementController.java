package com.pawbridge.backend.community.internal.homevideo.web;

import com.pawbridge.backend.community.internal.homevideo.application.HomeVideoManagement;
import com.pawbridge.backend.community.internal.homevideo.application.HomeVideoManagement.*;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import java.util.List;
import java.util.UUID;
import org.springframework.web.bind.annotation.*;

@RestController
public final class HomeVideoManagementController {
    private final HomeVideoManagement videos;
    public HomeVideoManagementController(HomeVideoManagement videos){this.videos=videos;}
    public record PreviewRequest(@NotBlank @Size(max=2048) String url) {}
    public record SaveRequest(@NotBlank @Size(max=2048) String url,boolean published,@Min(0) long revision) {}
    public record PublicationRequest(boolean published,@Min(0) long revision) {}
    public record OrderRequest(@NotNull @Size(max=3) List<@NotNull UUID> ids,@Min(0) long revision) {}

    @GetMapping("/api/v1/admin/videos")
    public HomeVideoController.Response<Board> board(){return ok(videos.board());}
    @PostMapping("/api/v1/admin/videos/preview")
    public HomeVideoController.Response<Metadata> preview(@Valid @RequestBody PreviewRequest request){return ok(videos.preview(request.url()));}
    @PostMapping("/api/v1/admin/videos")
    public HomeVideoController.Response<Board> create(@Valid @RequestBody SaveRequest request){return ok(videos.save(null,new Save(request.url(),request.published(),request.revision())));}
    @PutMapping("/api/v1/admin/videos/{id}")
    public HomeVideoController.Response<Board> edit(@PathVariable("id") UUID id,@Valid @RequestBody SaveRequest request){return ok(videos.save(id,new Save(request.url(),request.published(),request.revision())));}
    @PutMapping("/api/v1/admin/videos/{id}/publication")
    public HomeVideoController.Response<Board> publish(@PathVariable("id") UUID id,@Valid @RequestBody PublicationRequest request){return ok(videos.publish(id,new Publication(request.published(),request.revision())));}
    @PutMapping("/api/v1/admin/videos/order")
    public HomeVideoController.Response<Board> order(@Valid @RequestBody OrderRequest request){return ok(videos.reorder(new Order(request.ids(),request.revision())));}
    @PostMapping("/api/v1/admin/videos/{id}/recheck")
    public HomeVideoController.Response<Board> recheck(@PathVariable("id") UUID id,@Valid @RequestBody PublicationRequest request){return ok(videos.recheck(id,request.revision()));}
    private static <T> HomeVideoController.Response<T> ok(T value){return new HomeVideoController.Response<>(200,"요청이 성공적으로 처리되었습니다.",value);}
}
