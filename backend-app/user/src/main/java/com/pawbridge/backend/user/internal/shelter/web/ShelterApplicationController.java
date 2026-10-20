package com.pawbridge.backend.user.internal.shelter.web;

import com.pawbridge.backend.user.internal.shelter.application.ShelterMembership;
import com.pawbridge.backend.user.internal.profile.web.UserProfileController.Response;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/users/me/shelter-applications")
public final class ShelterApplicationController {
    private final ShelterMembership memberships;
    public ShelterApplicationController(ShelterMembership memberships){this.memberships=memberships;}
    public record SubmitRequest(@NotBlank @Size(max=100) String shelterName) { }
    @PostMapping public Response<ShelterMembership.Application> submit(@AuthenticationPrincipal Long current,
            @Valid @RequestBody SubmitRequest request){return ok(memberships.submit(current,request.shelterName()));}
    @GetMapping public Response<org.springframework.data.domain.Page<ShelterMembership.Application>> mine(@AuthenticationPrincipal Long current,
            @RequestParam(name="page",defaultValue="0") int page,@RequestParam(name="size",defaultValue="20") int size){return ok(page(memberships.mine(current,page,size),"id"));}
    static <T> Response<T> ok(T data){return new Response<>(200,"요청이 성공적으로 처리되었습니다.",data);}
    static <T> org.springframework.data.domain.Page<T> page(ShelterMembership.Page<T> result,String sort){
        return new org.springframework.data.domain.PageImpl<>(result.content(),org.springframework.data.domain.PageRequest.of(result.number(),result.size(),org.springframework.data.domain.Sort.by(org.springframework.data.domain.Sort.Direction.DESC,sort)),result.total());}
}
