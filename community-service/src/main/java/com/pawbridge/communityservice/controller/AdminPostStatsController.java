package com.pawbridge.communityservice.controller;

import com.pawbridge.communityservice.dto.response.PostPeriodStatsResponse;
import com.pawbridge.communityservice.service.AdminPostStatsService;
import com.pawbridge.communityservice.util.ResponseDTO;
import java.time.LocalDate;
import lombok.RequiredArgsConstructor;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

/** Admin authorization follows the existing gateway-only admin post boundary. */
@RestController
@RequiredArgsConstructor
@RequestMapping("/api/v1/admin/posts/stats")
public class AdminPostStatsController {
    private final AdminPostStatsService service;

    @GetMapping("/period")
    public ResponseDTO<PostPeriodStatsResponse> period(
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate startDate,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate endDate) {
        return ResponseDTO.okWithData(service.period(startDate, endDate));
    }

    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    public ResponseEntity<Void> malformedDate() {
        return ResponseEntity.badRequest().build();
    }
}
