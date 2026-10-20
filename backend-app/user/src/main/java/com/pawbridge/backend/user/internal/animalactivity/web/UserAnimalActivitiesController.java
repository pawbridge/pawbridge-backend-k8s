package com.pawbridge.backend.user.internal.animalactivity.web;

import com.pawbridge.backend.user.internal.animalactivity.application.UserAnimalActivities;
import com.pawbridge.backend.user.internal.profile.web.UserProfileController.Response;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.web.PageableDefault;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

@RestController
public final class UserAnimalActivitiesController {
    private final UserAnimalActivities activities;
    public UserAnimalActivitiesController(UserAnimalActivities activities){this.activities=activities;}
    @PostMapping("/api/v1/favorites/{animalId}") public Response<UserAnimalActivities.Favorite> add(@AuthenticationPrincipal Long current,@PathVariable("animalId") long animalId){return new Response<>(200,"좋아요가 추가되었습니다.",activities.add(current,animalId));}
    @DeleteMapping("/api/v1/favorites/{animalId}") public Response<Void> remove(@AuthenticationPrincipal Long current,@PathVariable("animalId") long animalId){activities.remove(current,animalId);return new Response<>(200,"좋아요가 제거되었습니다.",null);}
    @GetMapping("/api/v1/users/me/favorite-animals") public Response<UserAnimalActivities.Favorites> favorites(@AuthenticationPrincipal Long current){return new Response<>(200,"요청이 성공적으로 처리되었습니다.",activities.favorites(current));}
    @GetMapping("/api/v1/users/me/registered-animals") public Response<UserAnimalActivities.AnimalPage> registered(@AuthenticationPrincipal Long current,
            @PageableDefault(size=20,sort="createdAt",direction=Sort.Direction.DESC) Pageable pageable){
        String sort=pageable.getSort().stream().map(order->order.getProperty()+","+order.getDirection().name().toLowerCase(java.util.Locale.ROOT)).reduce((a,b)->a+","+b).orElse("createdAt,desc");
        return new Response<>(200,"요청이 성공적으로 처리되었습니다.",activities.registered(current,new UserAnimalActivities.PageRequest(pageable.getPageNumber(),pageable.getPageSize(),sort)));
    }
}
