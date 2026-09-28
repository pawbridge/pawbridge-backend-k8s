package com.pawbridge.communityservice.controller;

import com.pawbridge.communityservice.dto.request.CreateAnimalReportRequest;
import com.pawbridge.communityservice.dto.response.AnimalReportResponse;
import com.pawbridge.communityservice.service.AnimalReportService;
import com.pawbridge.communityservice.util.ResponseDTO;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.server.ResponseStatusException;

@RestController
@RequestMapping("/api/v1/reports")
@RequiredArgsConstructor
public class AnimalReportController {
    private final AnimalReportService reportService;

    @GetMapping
    public ResponseDTO<Page<AnimalReportResponse>> list(
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "12") int size) {
        if (page < 0 || size < 1 || size > 50) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "페이지 범위를 확인해 주세요.");
        }
        var pageable = PageRequest.of(page, size, Sort.by(Sort.Order.desc("createdAt"), Sort.Order.desc("postId")));
        return ResponseDTO.okWithData(reportService.list(pageable));
    }

    @GetMapping("/{postId}")
    public ResponseDTO<AnimalReportResponse> get(@PathVariable Long postId) {
        return ResponseDTO.okWithData(reportService.get(postId));
    }

    @PostMapping(consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseDTO<AnimalReportResponse> create(
            @RequestPart("report") CreateAnimalReportRequest request,
            @RequestPart(value = "photos", required = false) MultipartFile[] photos,
            @RequestHeader("X-User-Id") Long userId) {
        return ResponseDTO.okWithData(reportService.create(request, photos, userId));
    }

    @PutMapping(value = "/{postId}", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseDTO<AnimalReportResponse> update(
            @PathVariable Long postId,
            @RequestPart("report") CreateAnimalReportRequest request,
            @RequestPart(value = "photos", required = false) MultipartFile[] photos,
            @RequestHeader("X-User-Id") Long userId) {
        return ResponseDTO.okWithData(reportService.update(postId, request, photos, userId));
    }
}
