package com.pawbridge.backend.user.internal.shelter.web;

import com.pawbridge.backend.user.internal.shelter.application.ShelterMembership;
import com.pawbridge.backend.user.internal.profile.web.UserProfileController.Response;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import static com.pawbridge.backend.user.internal.shelter.web.ShelterApplicationController.*;

@RestController
public final class AdminShelterMemberController {
    private final ShelterMembership memberships;
    public AdminShelterMemberController(ShelterMembership memberships){this.memberships=memberships;}
    @GetMapping("/api/v1/admin/users/shelters/{careRegNo}/members")
    public Response<org.springframework.data.domain.Page<ShelterMembership.Member>> members(@AuthenticationPrincipal Long current,
            @PathVariable("careRegNo") String careRegNo,@RequestParam(name="page",defaultValue="0") int page,
            @RequestParam(name="size",defaultValue="20") int size){return ok(page(memberships.members(current,careRegNo,page,size),"userId"));}
}
