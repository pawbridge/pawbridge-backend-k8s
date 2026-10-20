package com.pawbridge.backend.user.internal.shelter.web;

import com.pawbridge.backend.user.internal.shelter.application.ShelterMembership;
import com.pawbridge.backend.user.internal.profile.web.UserProfileController.Response;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDate;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.ResponseEntity;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import static com.pawbridge.backend.user.internal.shelter.web.ShelterApplicationController.*;

@RestController
@RequestMapping("/api/v1/admin/users/shelter-applications")
public final class AdminShelterApplicationController {
    private final ShelterMembership memberships;
    public AdminShelterApplicationController(ShelterMembership memberships){this.memberships=memberships;}
    public record ApproveRequest(@NotBlank @Size(max=50) String careRegNo,@NotBlank @Size(max=1000) String note) { }
    public record RejectRequest(@NotBlank @Size(max=1000) String reason) { }
    @GetMapping("/stats") public Response<ShelterMembership.Statistics> statistics(@AuthenticationPrincipal Long current,
            @RequestParam(name="startDate") @DateTimeFormat(iso=DateTimeFormat.ISO.DATE) LocalDate start,
            @RequestParam(name="endDate") @DateTimeFormat(iso=DateTimeFormat.ISO.DATE) LocalDate end){return ok(memberships.statistics(current,start,end));}
    @GetMapping public Response<org.springframework.data.domain.Page<ShelterMembership.AdminApplication>> list(@AuthenticationPrincipal Long current,
            @RequestParam(name="status",required=false) ShelterMembership.Status status,
            @RequestParam(name="page",defaultValue="0") int page,@RequestParam(name="size",defaultValue="20") int size){return ok(page(memberships.list(current,status,page,size),"id"));}
    @GetMapping("/{id}") public Response<ShelterMembership.AdminApplication> detail(@AuthenticationPrincipal Long current,@PathVariable("id") long id){return ok(memberships.detail(current,id));}
    @PostMapping("/{id}/approve") public Response<ShelterMembership.AdminApplication> approve(@AuthenticationPrincipal Long current,@PathVariable("id") long id,
            @Valid @RequestBody ApproveRequest request){return ok(memberships.approve(current,id,request.careRegNo(),request.note()));}
    @PostMapping("/{id}/reject") public Response<ShelterMembership.AdminApplication> reject(@AuthenticationPrincipal Long current,@PathVariable("id") long id,
            @Valid @RequestBody RejectRequest request){return ok(memberships.reject(current,id,request.reason()));}
    @ExceptionHandler(MethodArgumentTypeMismatchException.class) public ResponseEntity<Void> malformedParameter(){return ResponseEntity.badRequest().build();}
}
