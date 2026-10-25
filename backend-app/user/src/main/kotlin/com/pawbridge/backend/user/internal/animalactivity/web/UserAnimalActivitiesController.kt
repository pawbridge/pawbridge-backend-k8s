package com.pawbridge.backend.user.internal.animalactivity.web

import com.pawbridge.backend.user.internal.animalactivity.application.UserAnimalActivities
import com.pawbridge.backend.user.internal.profile.web.UserProfileController.Response
import org.springframework.data.domain.Pageable
import org.springframework.data.domain.Sort
import org.springframework.data.web.PageableDefault
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.web.bind.annotation.DeleteMapping
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RestController
import java.util.Locale

@RestController
class UserAnimalActivitiesController(private val activities: UserAnimalActivities) {

    // The principal stays boxed like the Java signature; a missing principal fails with NullPointerException
    // exactly as the Java unboxing did.
    @PostMapping("/api/v1/favorites/{animalId}")
    fun add(
        @AuthenticationPrincipal current: Long?,
        @PathVariable("animalId") animalId: Long,
    ): Response<UserAnimalActivities.Favorite> =
        Response(200, "좋아요가 추가되었습니다.", activities.add(current!!, animalId))

    @DeleteMapping("/api/v1/favorites/{animalId}")
    fun remove(
        @AuthenticationPrincipal current: Long?,
        @PathVariable("animalId") animalId: Long,
    ): Response<Void> {
        activities.remove(current!!, animalId)
        return Response(200, "좋아요가 제거되었습니다.", null)
    }

    @GetMapping("/api/v1/users/me/favorite-animals")
    fun favorites(@AuthenticationPrincipal current: Long?): Response<UserAnimalActivities.Favorites> =
        Response(200, "요청이 성공적으로 처리되었습니다.", activities.favorites(current!!))

    @GetMapping("/api/v1/users/me/registered-animals")
    fun registered(
        @AuthenticationPrincipal current: Long?,
        @PageableDefault(size = 20, sort = ["createdAt"], direction = Sort.Direction.DESC) pageable: Pageable,
    ): Response<UserAnimalActivities.AnimalPage> {
        val sort = pageable.sort.stream()
            .map { order -> order.property + "," + order.direction.name.lowercase(Locale.ROOT) }
            .reduce { left, right -> "$left,$right" }
            .orElse("createdAt,desc")
        return Response(
            200,
            "요청이 성공적으로 처리되었습니다.",
            activities.registered(
                current!!,
                UserAnimalActivities.PageRequest(pageable.pageNumber, pageable.pageSize, sort),
            ),
        )
    }
}
