package com.pawbridge.communityservice.controller;

import com.pawbridge.communityservice.dto.request.CreateAnimalReportRequest;
import com.pawbridge.communityservice.domain.entity.AnimalReport;
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
import org.springframework.web.bind.annotation.DeleteMapping;
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
            @RequestParam(defaultValue = "12") int size,
            @RequestParam(required = false) AnimalReport.Kind kind,
            @RequestParam(required = false) String keyword) {
        if (page < 0 || size < 1 || size > 50 || (keyword != null && keyword.length() > 100)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "페이지 범위를 확인해 주세요.");
        }
        var pageable = PageRequest.of(page, size, Sort.by(Sort.Order.desc("createdAt"), Sort.Order.desc("reportId")));
        return ResponseDTO.okWithData(reportService.list(kind, keyword, pageable));
    }

    @GetMapping("/{reportId}")
    public ResponseDTO<AnimalReportResponse> get(@PathVariable Long reportId) {
        return ResponseDTO.okWithData(reportService.get(reportId));
    }

    @PostMapping(consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseDTO<AnimalReportResponse> create(
            @RequestPart("report") CreateAnimalReportRequest request,
            @RequestPart(value = "photos", required = false) MultipartFile[] photos,
            @RequestHeader("X-User-Id") Long userId) {
        return ResponseDTO.okWithData(reportService.create(request, photos, userId));
    }

    @PutMapping(value = "/{reportId}", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseDTO<AnimalReportResponse> update(
            @PathVariable Long reportId,
            @RequestPart("report") CreateAnimalReportRequest request,
            @RequestPart(value = "photos", required = false) MultipartFile[] photos,
            @RequestHeader("X-User-Id") Long userId) {
        return ResponseDTO.okWithData(reportService.update(reportId, request, photos, userId));
    }

    @DeleteMapping("/{reportId}")
    public ResponseDTO<Void> delete(@PathVariable Long reportId,
                                    @RequestHeader("X-User-Id") Long userId) {
        reportService.delete(reportId, userId);
        return ResponseDTO.okWithMessage("제보가 삭제되었습니다.");
    }
}
