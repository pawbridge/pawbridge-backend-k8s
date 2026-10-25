package com.pawbridge.backend.user.internal.shelter.web

import com.pawbridge.backend.user.internal.profile.web.UserProfileController.Response
import com.pawbridge.backend.user.internal.shelter.application.ShelterMembership
import jakarta.validation.Valid
import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.Size
import org.springframework.data.domain.Page
import org.springframework.format.annotation.DateTimeFormat
import org.springframework.http.ResponseEntity
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.web.bind.annotation.ExceptionHandler
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException
import java.time.LocalDate

@RestController
@RequestMapping("/api/v1/admin/users/shelter-applications")
class AdminShelterApplicationController(private val memberships: ShelterMembership) {

    @JvmRecord
    data class ApproveRequest(
        @field:NotBlank
        @field:Size(max = 50)
        val careRegNo: String?,
        @field:NotBlank
        @field:Size(max = 1000)
        val note: String?,
    )

    @JvmRecord
    data class RejectRequest(
        @field:NotBlank
        @field:Size(max = 1000)
        val reason: String?,
    )

    // The principal stays boxed like the Java signature; a missing principal fails with NullPointerException
    // exactly as the Java unboxing did.
    @GetMapping("/stats")
    fun statistics(
        @AuthenticationPrincipal current: Long?,
        @RequestParam(name = "startDate") @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) start: LocalDate,
        @RequestParam(name = "endDate") @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) end: LocalDate,
    ): Response<ShelterMembership.Statistics> = ok(memberships.statistics(current!!, start, end))

    @GetMapping
    fun list(
        @AuthenticationPrincipal current: Long?,
        @RequestParam(name = "status", required = false) status: ShelterMembership.Status?,
        @RequestParam(name = "page", defaultValue = "0") page: Int,
        @RequestParam(name = "size", defaultValue = "20") size: Int,
    ): Response<Page<ShelterMembership.AdminApplication>> =
        ok(wirePage(memberships.list(current!!, status, page, size), "id"))

    @GetMapping("/{id}")
    fun detail(
        @AuthenticationPrincipal current: Long?,
        @PathVariable("id") id: Long,
    ): Response<ShelterMembership.AdminApplication> = ok(memberships.detail(current!!, id))

    @PostMapping("/{id}/approve")
    fun approve(
        @AuthenticationPrincipal current: Long?,
        @PathVariable("id") id: Long,
        @Valid @RequestBody request: ApproveRequest,
    ): Response<ShelterMembership.AdminApplication> =
        ok(memberships.approve(current!!, id, request.careRegNo, request.note))

    @PostMapping("/{id}/reject")
    fun reject(
        @AuthenticationPrincipal current: Long?,
        @PathVariable("id") id: Long,
        @Valid @RequestBody request: RejectRequest,
    ): Response<ShelterMembership.AdminApplication> = ok(memberships.reject(current!!, id, request.reason))

    @ExceptionHandler(MethodArgumentTypeMismatchException::class)
    fun malformedParameter(): ResponseEntity<Void> = ResponseEntity.badRequest().build()
}
