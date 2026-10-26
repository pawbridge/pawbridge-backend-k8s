package com.pawbridge.backend.user.internal.shelter.web

import com.pawbridge.backend.user.internal.profile.web.UserProfileController.Response
import com.pawbridge.backend.user.internal.shelter.application.ShelterMembership
import jakarta.validation.Valid
import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.Size
import org.springframework.data.domain.Page
import org.springframework.data.domain.PageImpl
import org.springframework.data.domain.PageRequest
import org.springframework.data.domain.Sort
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController

@RestController
@RequestMapping("/api/v1/users/me/shelter-applications")
class ShelterApplicationController(private val memberships: ShelterMembership) {

    @JvmRecord
    data class SubmitRequest(
        @field:NotBlank
        @field:Size(max = 100)
        val shelterName: String?,
    )

    // The principal stays boxed like the Java signature; a missing principal fails with NullPointerException
    // exactly as the Java unboxing did.
    @PostMapping
    fun submit(
        @AuthenticationPrincipal current: Long?,
        @Valid @RequestBody request: SubmitRequest,
    ): Response<ShelterMembership.Application> = ok(memberships.submit(current!!, request.shelterName))

    @GetMapping
    fun mine(
        @AuthenticationPrincipal current: Long?,
        @RequestParam(name = "page", defaultValue = "0") page: Int,
        @RequestParam(name = "size", defaultValue = "20") size: Int,
    ): Response<Page<ShelterMembership.Application>> =
        ok(wirePage(memberships.mine(current!!, page, size), "id"))
}

internal fun <T> ok(data: T): Response<T> = Response(200, "요청이 성공적으로 처리되었습니다.", data)

internal fun <T> wirePage(result: ShelterMembership.Page<T>, sort: String): Page<T> =
    PageImpl(
        result.content,
        PageRequest.of(result.number, result.size, Sort.by(Sort.Direction.DESC, sort)),
        result.total,
    )
