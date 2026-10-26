package com.pawbridge.backend.community.internal.report.web

import com.pawbridge.backend.community.internal.post.application.PostUpload
import com.pawbridge.backend.community.internal.post.web.ApiResponse
import com.pawbridge.backend.community.internal.report.application.AnimalReports
import com.pawbridge.backend.community.internal.report.application.AnimalReports.AnimalType
import com.pawbridge.backend.community.internal.report.application.AnimalReports.Details
import com.pawbridge.backend.community.internal.report.application.AnimalReports.Draft
import com.pawbridge.backend.community.internal.report.application.AnimalReports.Filter
import com.pawbridge.backend.community.internal.report.application.AnimalReports.Kind
import java.time.LocalDate
import org.springframework.data.domain.Page
import org.springframework.data.domain.PageImpl
import org.springframework.data.domain.PageRequest
import org.springframework.data.domain.Sort
import org.springframework.format.annotation.DateTimeFormat
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.web.bind.annotation.DeleteMapping
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.PutMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RequestPart
import org.springframework.web.bind.annotation.RestController
import org.springframework.web.multipart.MultipartFile
import org.springframework.web.server.ResponseStatusException

@RestController
@RequestMapping("/api/v1/reports")
class AnimalReportController(private val reports: AnimalReports) {

    @GetMapping
    fun list(
        @RequestParam(name = "page", defaultValue = "0") page: Int,
        @RequestParam(name = "size", defaultValue = "12") size: Int,
        @RequestParam(name = "kind", required = false) kind: Kind?,
        @RequestParam(name = "keyword", required = false) keyword: String?,
        @RequestParam(name = "province", required = false) province: String?,
        @RequestParam(name = "district", required = false) district: String?,
        @RequestParam(name = "animalType", required = false) animalType: AnimalType?,
        @RequestParam(name = "from", required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) from: LocalDate?,
        @RequestParam(name = "to", required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) to: LocalDate?,
    ): ApiResponse<Page<Details>> {
        if (page < 0 || size < 1 || size > 50 || (keyword != null && keyword.length > 100)) {
            throw ResponseStatusException(HttpStatus.BAD_REQUEST, "페이지 범위를 확인해 주세요.")
        }
        val result = reports.list(Filter(page, size, kind, keyword, province, district, animalType, from, to))
        return ok(
            PageImpl(
                result.content,
                PageRequest.of(page, size, Sort.by(Sort.Order.desc("createdAt"), Sort.Order.desc("reportId"))),
                result.total,
            ),
        )
    }

    @GetMapping("/{reportId}")
    fun get(@PathVariable("reportId") id: Long): ApiResponse<Details> = ok(reports.get(id))

    @PostMapping(consumes = [MediaType.MULTIPART_FORM_DATA_VALUE])
    fun create(
        @RequestPart("report") request: Draft,
        @RequestPart(value = "photos", required = false) photos: Array<MultipartFile?>?,
        @AuthenticationPrincipal userId: Long?,
    ): ApiResponse<Details> = ok(reports.create(request, files(photos), userId))

    @PutMapping(value = ["/{reportId}"], consumes = [MediaType.MULTIPART_FORM_DATA_VALUE])
    fun update(
        @PathVariable("reportId") id: Long,
        @RequestPart("report") request: Draft,
        @RequestPart(value = "photos", required = false) photos: Array<MultipartFile?>?,
        @AuthenticationPrincipal userId: Long?,
    ): ApiResponse<Details> = ok(reports.update(id, request, files(photos), userId))

    @DeleteMapping("/{reportId}")
    fun delete(
        @PathVariable("reportId") id: Long,
        @AuthenticationPrincipal userId: Long?,
    ): ApiResponse<Void> {
        reports.delete(id, userId)
        return ApiResponse<Void>(200, "제보가 삭제되었습니다.", null)
    }

    private fun <T> ok(data: T): ApiResponse<T> = ApiResponse(200, "요청이 성공적으로 처리되었습니다.", data)

    private fun files(files: Array<MultipartFile?>?): List<PostUpload?>? =
        files?.map { file ->
            if (file == null) {
                null
            } else {
                PostUpload(file.originalFilename, file.contentType, file.size, PostUpload.Content { file.inputStream })
            }
        }
}
