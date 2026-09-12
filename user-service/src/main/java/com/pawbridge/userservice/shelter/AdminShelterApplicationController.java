package com.pawbridge.userservice.shelter;

import com.pawbridge.userservice.util.ResponseDTO;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.web.bind.annotation.*;

@RestController
@RequiredArgsConstructor
@RequestMapping("/api/v1/admin/users/shelter-applications")
public class AdminShelterApplicationController {
    private final ShelterApplicationService service;
    public record ApproveRequest(@NotBlank @Size(max = 50) String careRegNo,
                                 @NotBlank @Size(max = 1000) String note) {}
    public record RejectRequest(@NotBlank @Size(max = 1000) String reason) {}

    @GetMapping
    public ResponseDTO<Page<AdminShelterApplicationResponse>> list(
            @RequestHeader(value = "Authorization", required = false) String authorization,
            @RequestParam(required = false) ShelterApplicationStatus status,
            @RequestParam(defaultValue = "0") int page, @RequestParam(defaultValue = "20") int size) {
        return ResponseDTO.okWithData(service.list(authorization, status, page, size));
    }
    @GetMapping("/{id}")
    public ResponseDTO<AdminShelterApplicationResponse> detail(
            @RequestHeader(value = "Authorization", required = false) String authorization, @PathVariable Long id) {
        return ResponseDTO.okWithData(service.detail(authorization, id));
    }
    @PostMapping("/{id}/approve")
    public ResponseDTO<AdminShelterApplicationResponse> approve(
            @RequestHeader(value = "Authorization", required = false) String authorization, @PathVariable Long id,
            @Valid @RequestBody ApproveRequest request) {
        return ResponseDTO.okWithData(service.approve(authorization, id, request.careRegNo(), request.note()));
    }
    @PostMapping("/{id}/reject")
    public ResponseDTO<AdminShelterApplicationResponse> reject(
            @RequestHeader(value = "Authorization", required = false) String authorization, @PathVariable Long id,
            @Valid @RequestBody RejectRequest request) {
        return ResponseDTO.okWithData(service.reject(authorization, id, request.reason()));
    }
}
