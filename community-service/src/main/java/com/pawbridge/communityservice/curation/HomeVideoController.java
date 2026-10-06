package com.pawbridge.communityservice.curation;

import static com.pawbridge.communityservice.curation.HomeVideoModels.*;
import com.pawbridge.communityservice.util.ResponseDTO;
import jakarta.validation.Valid;
import java.util.List;
import java.util.UUID;
import org.springframework.context.annotation.Profile;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

@RestController
@Profile("postgresql")
public class HomeVideoController {
    private final HomeVideoService service;
    public HomeVideoController(HomeVideoService service) { this.service = service; }

    @GetMapping("/api/v1/home/videos")
    public ResponseDTO<List<Video>> home() { return ResponseDTO.okWithData(service.home()); }
    @GetMapping("/api/v1/admin/videos")
    public ResponseDTO<Board> board(@RequestHeader(value="X-User-Role", required=false) String role) {
        admin(role); return ResponseDTO.okWithData(service.board());
    }
    @PostMapping("/api/v1/admin/videos/preview")
    public ResponseDTO<Metadata> preview(@RequestHeader(value="X-User-Role", required=false) String role,
                                         @Valid @RequestBody Preview request) {
        admin(role); return ResponseDTO.okWithData(service.preview(request.url()));
    }
    @PostMapping("/api/v1/admin/videos")
    public ResponseDTO<Board> create(@RequestHeader(value="X-User-Role", required=false) String role,
                                     @Valid @RequestBody Save request) {
        admin(role); return ResponseDTO.okWithData(service.save(null, request));
    }
    @PutMapping("/api/v1/admin/videos/{id}")
    public ResponseDTO<Board> edit(@RequestHeader(value="X-User-Role", required=false) String role,
                                   @PathVariable UUID id, @Valid @RequestBody Save request) {
        admin(role); return ResponseDTO.okWithData(service.save(id, request));
    }
    @PutMapping("/api/v1/admin/videos/{id}/publication")
    public ResponseDTO<Board> publish(@RequestHeader(value="X-User-Role", required=false) String role,
                                      @PathVariable UUID id, @Valid @RequestBody Publication request) {
        admin(role); return ResponseDTO.okWithData(service.publish(id, request));
    }
    @PutMapping("/api/v1/admin/videos/order")
    public ResponseDTO<Board> order(@RequestHeader(value="X-User-Role", required=false) String role,
                                    @Valid @RequestBody Order request) {
        admin(role); return ResponseDTO.okWithData(service.reorder(request));
    }
    @PostMapping("/api/v1/admin/videos/{id}/recheck")
    public ResponseDTO<Board> recheck(@RequestHeader(value="X-User-Role", required=false) String role,
                                     @PathVariable UUID id, @Valid @RequestBody Publication request) {
        admin(role); return ResponseDTO.okWithData(service.recheck(id, request.revision()));
    }
    // Verified claims are injected by the Gateway; Community must remain internal-only.
    private static void admin(String role) {
        if (!"ROLE_ADMIN".equals(role)) throw new ResponseStatusException(HttpStatus.FORBIDDEN, "관리자 권한이 필요합니다.");
    }
}
