package com.pawbridge.backend.user.internal.shelter.web

import com.pawbridge.backend.user.internal.profile.web.UserProfileController.Response
import com.pawbridge.backend.user.internal.shelter.application.ShelterMembership
import org.springframework.data.domain.Page
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController

@RestController
class AdminShelterMemberController(private val memberships: ShelterMembership) {

    @GetMapping("/api/v1/admin/users/shelters/{careRegNo}/members")
    fun members(
        @AuthenticationPrincipal current: Long?,
        @PathVariable("careRegNo") careRegNo: String,
        @RequestParam(name = "page", defaultValue = "0") page: Int,
        @RequestParam(name = "size", defaultValue = "20") size: Int,
    ): Response<Page<ShelterMembership.Member>> =
        ok(wirePage(memberships.members(current!!, careRegNo, page, size), "userId"))
}
