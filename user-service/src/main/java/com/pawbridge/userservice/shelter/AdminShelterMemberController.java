package com.pawbridge.userservice.shelter;

import com.pawbridge.userservice.util.ResponseDTO;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.web.bind.annotation.*;

@RestController
@RequiredArgsConstructor
@RequestMapping("/api/v1/admin/users/shelters")
public class AdminShelterMemberController {
    private final ShelterApplicationService service;

    @GetMapping("/{careRegNo}/members")
    public ResponseDTO<Page<ShelterMemberResponse>> members(
            @RequestHeader(value = "Authorization", required = false) String authorization,
            @PathVariable String careRegNo,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        return ResponseDTO.okWithData(service.members(authorization, careRegNo, page, size));
    }
}
