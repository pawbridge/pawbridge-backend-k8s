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
@RequestMapping("/api/v1/users/me/shelter-applications")
public class ShelterApplicationController {
    private final ShelterApplicationService service;
    public record SubmitRequest(@NotBlank @Size(max = 100) String shelterName) {}

    @PostMapping
    public ResponseDTO<ShelterApplicationResponse> submit(
            @RequestHeader(value = "Authorization", required = false) String authorization,
            @Valid @RequestBody SubmitRequest request) {
        return ResponseDTO.okWithData(service.submit(authorization, request.shelterName()));
    }

    @GetMapping
    public ResponseDTO<Page<ShelterApplicationResponse>> mine(
            @RequestHeader(value = "Authorization", required = false) String authorization,
            @RequestParam(defaultValue = "0") int page, @RequestParam(defaultValue = "20") int size) {
        return ResponseDTO.okWithData(service.mine(authorization, page, size));
    }
}
