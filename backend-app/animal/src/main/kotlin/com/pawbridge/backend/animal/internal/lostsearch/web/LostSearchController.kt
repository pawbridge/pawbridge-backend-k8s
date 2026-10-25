package com.pawbridge.backend.animal.internal.lostsearch.web

import com.pawbridge.backend.animal.internal.lostsearch.infrastructure.LostSearchRequest
import com.pawbridge.backend.animal.internal.lostsearch.infrastructure.LostSearchResponse
import com.pawbridge.backend.animal.internal.lostsearch.infrastructure.LostSearchService
import jakarta.validation.Valid
import org.springframework.http.MediaType
import org.springframework.http.ResponseEntity
import org.springframework.validation.BindException
import org.springframework.web.bind.annotation.ExceptionHandler
import org.springframework.web.bind.annotation.ModelAttribute
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RestController
import org.springframework.web.server.ResponseStatusException

@RestController
class LostSearchController(
    private val service: LostSearchService,
) {

    @PostMapping(value = ["/api/v1/animals/lost-candidates"], consumes = [MediaType.MULTIPART_FORM_DATA_VALUE])
    fun search(@Valid @ModelAttribute request: LostSearchRequest): LostSearchResponse {
        return service.search(request)
    }

    @ExceptionHandler(ResponseStatusException::class)
    fun searchError(ex: ResponseStatusException): ResponseEntity<Map<String, String>> {
        val response = ResponseEntity.status(ex.statusCode)
        if (ex.statusCode.value() == 503) {
            response.header("Retry-After", "3")
        }
        // Map.of rejected a missing reason with a NullPointerException; keep that failure path.
        return response.body(mapOf("message" to ex.reason!!))
    }

    @ExceptionHandler(BindException::class)
    fun invalidInput(): ResponseEntity<Map<String, String>> {
        return ResponseEntity.badRequest().body(mapOf("message" to "사진·종류·날짜·지역·특징 입력을 확인해 주세요"))
    }
}
