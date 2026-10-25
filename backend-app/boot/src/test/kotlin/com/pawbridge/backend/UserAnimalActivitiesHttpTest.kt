package com.pawbridge.backend

import com.pawbridge.backend.boot.configuration.AdminPostReadConfiguration
import com.pawbridge.backend.boot.configuration.ReadSecurityConfiguration
import com.pawbridge.backend.community.internal.post.application.PostPageReader
import com.pawbridge.backend.community.internal.post.application.ReadAdminPosts
import com.pawbridge.backend.community.internal.post.application.ReadPost
import com.pawbridge.backend.support.anyNonNull
import com.pawbridge.backend.user.internal.animalactivity.application.FavoriteActivityRejectedException
import com.pawbridge.backend.user.internal.animalactivity.application.UserAnimalActivities
import com.pawbridge.backend.user.internal.animalactivity.web.UserAnimalActivitiesController
import com.pawbridge.backend.user.internal.animalactivity.web.UserAnimalActivitiesExceptionHandler
import com.pawbridge.backend.user.internal.favorite.application.FavoriteStatusQuery
import com.pawbridge.backend.user.internal.favorite.web.FavoriteStatusController
import com.pawbridge.backend.user.internal.profile.application.UserNotFoundException
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import org.mockito.ArgumentMatchers.anyLong
import org.mockito.Mockito.doThrow
import org.mockito.Mockito.`when` as whenever
import org.mockito.Mockito.verify
import org.mockito.Mockito.verifyNoInteractions
import org.mockito.Mockito.verifyNoMoreInteractions
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.autoconfigure.security.servlet.UserDetailsServiceAutoConfiguration
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest
import org.springframework.security.core.context.SecurityContextHolder
import org.springframework.test.context.ContextConfiguration
import org.springframework.test.context.bean.override.mockito.MockitoBean
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.content
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.header
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import org.springframework.transaction.TransactionSystemException
import java.time.LocalDateTime

@WebMvcTest(
    properties = ["pawbridge.user-animal-activities.enabled=true"],
    excludeAutoConfiguration = [UserDetailsServiceAutoConfiguration::class],
)
@ContextConfiguration(
    classes = [
        UserAnimalActivitiesController::class,
        UserAnimalActivitiesExceptionHandler::class,
        FavoriteStatusController::class,
        AdminPostReadConfiguration::class,
        ReadSecurityConfiguration::class,
        F3TestInputs.SecurityInputs::class,
    ],
)
class UserAnimalActivitiesHttpTest {
    @Autowired
    lateinit var mvc: MockMvc

    @MockitoBean
    lateinit var activities: UserAnimalActivities

    @MockitoBean
    lateinit var statusQuery: FavoriteStatusQuery

    @MockitoBean
    lateinit var posts: ReadAdminPosts

    @MockitoBean
    lateinit var post: ReadPost

    @MockitoBean
    lateinit var postPages: PostPageReader

    @Test
    fun given__a_verified_principal_and_forged_header__when__adding_and_removing__then__retain_original_messages_nullable_fields_and_explicit_long_id() {
        whenever(activities.add(7, 8)).thenReturn(
            UserAnimalActivities.Favorite(Long.MAX_VALUE, 7L, 8L, LocalDateTime.parse("2026-10-20T01:02:03.123456789")),
        )
        mvc.perform(post("/api/v1/favorites/8").header("Authorization", token()).header("X-User-Id", "999"))
            .andExpect(status().isOk())
            .andExpect(
                content().json(
                    "{\"code\":200,\"message\":\"좋아요가 추가되었습니다.\",\"data\":{\"favoriteId\":9223372036854775807,\"userId\":7,\"animalId\":8,\"createdAt\":\"2026-10-20T01:02:03.123456789\"}}",
                ),
            )
            .andExpect(header().doesNotExist("Set-Cookie"))
        mvc.perform(delete("/api/v1/favorites/8").header("Authorization", token()))
            .andExpect(status().isOk())
            .andExpect(content().json("{\"code\":200,\"message\":\"좋아요가 제거되었습니다.\",\"data\":null}"))
        verify(activities).add(7, 8)
        verify(activities).remove(7, 8)
        verifyNoMoreInteractions(activities)
        cleared()
    }

    @Test
    fun given__favorite_and_registered_pages__when__reading__then__return_source_dto_and_default_or_multi_sort_forwarding() {
        whenever(activities.favorites(7)).thenReturn(UserAnimalActivities.Favorites(7L, 0, listOf()))
        whenever(activities.registered(anyLong(), anyNonNull<UserAnimalActivities.PageRequest>()))
            .thenReturn(UserAnimalActivities.AnimalPage(listOf(), 0, 20, 41, 3, false))
        mvc.perform(get("/api/v1/users/me/favorite-animals").header("Authorization", token()))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.totalCount").value(0))
        mvc.perform(get("/api/v1/users/me/registered-animals").header("Authorization", token()))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.page").value(0))
            .andExpect(jsonPath("$.data.totalElements").value(41))
        mvc.perform(
            get("/api/v1/users/me/registered-animals")
                .param("page", "3")
                .param("sort", "name,desc", "createdAt,asc")
                .header("Authorization", token()),
        )
            .andExpect(status().isOk())
        verify(activities).favorites(7)
        verify(activities).registered(7, UserAnimalActivities.PageRequest(0, 20, "createdAt,desc"))
        verify(activities).registered(7, UserAnimalActivities.PageRequest(3, 20, "name,desc,createdAt,asc"))
        cleared()
    }

    @Test
    fun given__missing_jwt_or_forged_identity__when__a_bad_path_is_sent__then__authenticate_before_binding_and_do_no_business() {
        mvc.perform(post("/api/v1/favorites/bad").header("X-User-Id", "7")).andExpect(status().isUnauthorized())
        mvc.perform(post("/api/v1/favorites/bad").header("Authorization", token()))
            .andExpect(status().isInternalServerError())
        verifyNoInteractions(activities)
        cleared()
    }

    @ParameterizedTest
    @ValueSource(strings = ["duplicate", "missing", "animal", "user", "runtime", "commit"])
    fun given__business_or_storage_failure__when__mutating__then__retain_original_wire_and_never_expose_private_exception_text(
        phase: String,
    ) {
        val failure: RuntimeException = when (phase) {
            "duplicate" -> FavoriteActivityRejectedException(FavoriteActivityRejectedException.Reason.ALREADY_EXISTS)
            "missing" -> FavoriteActivityRejectedException(FavoriteActivityRejectedException.Reason.NOT_FOUND)
            "animal" -> FavoriteActivityRejectedException(FavoriteActivityRejectedException.Reason.ANIMAL_NOT_FOUND)
            "user" -> UserNotFoundException()
            "commit" -> TransactionSystemException("synthetic-private-secret")
            else -> IllegalStateException("synthetic-private-secret")
        }
        if (phase == "missing") {
            doThrow(failure).`when`(activities).remove(7, 8)
        } else {
            whenever(activities.add(7, 8)).thenThrow(failure)
        }
        val expectedStatus = if (phase == "duplicate") {
            409
        } else if (phase == "missing" || phase == "animal") {
            404
        } else if (phase == "user") {
            400
        } else {
            500
        }
        val request = if (phase == "missing") delete("/api/v1/favorites/8") else post("/api/v1/favorites/8")
        val response = mvc.perform(request.header("Authorization", token()))
            .andExpect(status().`is`(expectedStatus))
            .andExpect(header().doesNotExist("Set-Cookie"))
            .andReturn().response
        assertThat(response.contentAsString).doesNotContain("synthetic-private-secret")
        assertThat(response.getHeader("Cache-Control")).contains("no-store")
        cleared()
    }

    @Test
    fun given__source_non_shelter_database_rejection__when__registered_animals_are_requested__then__preserve_generic500_instead_of_inventing403() {
        whenever(activities.registered(anyLong(), anyNonNull<UserAnimalActivities.PageRequest>()))
            .thenThrow(IllegalStateException("synthetic-private"))
        mvc.perform(get("/api/v1/users/me/registered-animals").header("Authorization", token()))
            .andExpect(status().isInternalServerError())
            .andExpect(jsonPath("$.message").value("서버 에러!"))
        cleared()
    }

    @Test
    fun given__the_existing_status_read_and_adjacent_mutations__when__requesting__then__keep_status_sam_and_default_deny_intact() {
        whenever(statusQuery.isFavorite(7, 8)).thenReturn(true)
        mvc.perform(get("/api/v1/favorites/8/check").header("Authorization", token()))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data").value(true))
        verify(statusQuery).isFavorite(7, 8)
        mvc.perform(put("/api/v1/favorites/8").header("Authorization", token())).andExpect(status().isForbidden())
        mvc.perform(post("/api/v1/favorites/8/extra").header("Authorization", token()))
            .andExpect(status().isForbidden())
        verifyNoInteractions(activities)
    }

    companion object {
        private fun token(): String = "Bearer " + F3TestInputs.token(7, "ROLE_USER")

        private fun cleared() {
            assertThat(SecurityContextHolder.getContext().authentication).isNull()
        }
    }
}
