package com.pawbridge.backend;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import com.pawbridge.backend.boot.configuration.ReadSecurityConfiguration;
import com.pawbridge.backend.boot.configuration.AdminPostReadConfiguration;
import com.pawbridge.backend.community.internal.post.application.*;
import com.pawbridge.backend.user.internal.animalactivity.application.*;
import com.pawbridge.backend.user.internal.animalactivity.web.*;
import com.pawbridge.backend.user.internal.favorite.application.FavoriteStatusQuery;
import com.pawbridge.backend.user.internal.favorite.web.FavoriteStatusController;
import java.time.LocalDateTime;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

@WebMvcTest(properties="pawbridge.user-animal-activities.enabled=true",excludeAutoConfiguration=org.springframework.boot.autoconfigure.security.servlet.UserDetailsServiceAutoConfiguration.class)
@ContextConfiguration(classes={UserAnimalActivitiesController.class,UserAnimalActivitiesExceptionHandler.class,FavoriteStatusController.class,
        AdminPostReadConfiguration.class,ReadSecurityConfiguration.class,F3TestInputs.SecurityInputs.class})
class UserAnimalActivitiesHttpTest {
    @Autowired MockMvc mvc;@MockitoBean UserAnimalActivities activities;@MockitoBean FavoriteStatusQuery statusQuery;
    @MockitoBean ReadAdminPosts posts;@MockitoBean ReadPost post;@MockitoBean PostPageReader postPages;
    @Test void given__a_verified_principal_and_forged_header__when__adding_and_removing__then__retain_original_messages_nullable_fields_and_explicit_long_id()throws Exception{
        when(activities.add(7,8)).thenReturn(new UserAnimalActivities.Favorite(Long.MAX_VALUE,7L,8L,LocalDateTime.parse("2026-10-20T01:02:03.123456789")));
        mvc.perform(post("/api/v1/favorites/8").header("Authorization",token()).header("X-User-Id","999")).andExpect(status().isOk())
            .andExpect(content().json("{\"code\":200,\"message\":\"좋아요가 추가되었습니다.\",\"data\":{\"favoriteId\":9223372036854775807,\"userId\":7,\"animalId\":8,\"createdAt\":\"2026-10-20T01:02:03.123456789\"}}"))
            .andExpect(header().doesNotExist("Set-Cookie"));
        mvc.perform(delete("/api/v1/favorites/8").header("Authorization",token())).andExpect(status().isOk()).andExpect(content().json("{\"code\":200,\"message\":\"좋아요가 제거되었습니다.\",\"data\":null}"));
        verify(activities).add(7,8);verify(activities).remove(7,8);verifyNoMoreInteractions(activities);cleared();
    }
    @Test void given__favorite_and_registered_pages__when__reading__then__return_source_dto_and_default_or_multi_sort_forwarding()throws Exception{
        when(activities.favorites(7)).thenReturn(new UserAnimalActivities.Favorites(7L,0,List.of()));
        when(activities.registered(anyLong(),any())).thenReturn(new UserAnimalActivities.AnimalPage(List.of(),0,20,41,3,false));
        mvc.perform(get("/api/v1/users/me/favorite-animals").header("Authorization",token())).andExpect(status().isOk()).andExpect(jsonPath("$.data.totalCount").value(0));
        mvc.perform(get("/api/v1/users/me/registered-animals").header("Authorization",token())).andExpect(status().isOk()).andExpect(jsonPath("$.data.page").value(0)).andExpect(jsonPath("$.data.totalElements").value(41));
        mvc.perform(get("/api/v1/users/me/registered-animals").param("page","3").param("sort","name,desc","createdAt,asc").header("Authorization",token())).andExpect(status().isOk());
        verify(activities).favorites(7);verify(activities).registered(7,new UserAnimalActivities.PageRequest(0,20,"createdAt,desc"));verify(activities).registered(7,new UserAnimalActivities.PageRequest(3,20,"name,desc,createdAt,asc"));cleared();
    }
    @Test void given__missing_jwt_or_forged_identity__when__a_bad_path_is_sent__then__authenticate_before_binding_and_do_no_business()throws Exception{
        mvc.perform(post("/api/v1/favorites/bad").header("X-User-Id","7")).andExpect(status().isUnauthorized());
        mvc.perform(post("/api/v1/favorites/bad").header("Authorization",token())).andExpect(status().isInternalServerError());verifyNoInteractions(activities);cleared();
    }
    @ParameterizedTest @ValueSource(strings={"duplicate","missing","user","runtime","commit"})
    void given__business_or_storage_failure__when__mutating__then__retain_original_wire_and_never_expose_private_exception_text(String phase)throws Exception{
        RuntimeException failure=switch(phase){case "duplicate"->new FavoriteActivityRejectedException(FavoriteActivityRejectedException.Reason.ALREADY_EXISTS);case "missing"->new FavoriteActivityRejectedException(FavoriteActivityRejectedException.Reason.NOT_FOUND);
            case "user"->new com.pawbridge.backend.user.internal.profile.application.UserNotFoundException();case "commit"->new org.springframework.transaction.TransactionSystemException("synthetic-private-secret");default->new IllegalStateException("synthetic-private-secret");};
        if(phase.equals("missing"))doThrow(failure).when(activities).remove(7,8);else when(activities.add(7,8)).thenThrow(failure);int status=phase.equals("duplicate")?409:phase.equals("missing")?404:phase.equals("user")?400:500;
        var response=mvc.perform((phase.equals("missing")?delete("/api/v1/favorites/8"):post("/api/v1/favorites/8")).header("Authorization",token())).andExpect(status().is(status)).andExpect(header().doesNotExist("Set-Cookie")).andReturn().getResponse();
        assertThat(response.getContentAsString()).doesNotContain("synthetic-private-secret");assertThat(response.getHeader("Cache-Control")).contains("no-store");cleared();
    }
    @Test void given__source_non_shelter_database_rejection__when__registered_animals_are_requested__then__preserve_generic500_instead_of_inventing403()throws Exception{
        when(activities.registered(anyLong(),any())).thenThrow(new IllegalStateException("synthetic-private"));mvc.perform(get("/api/v1/users/me/registered-animals").header("Authorization",token())).andExpect(status().isInternalServerError()).andExpect(jsonPath("$.message").value("서버 에러!"));cleared();
    }
    @Test void given__the_existing_status_read_and_adjacent_mutations__when__requesting__then__keep_status_sam_and_default_deny_intact()throws Exception{
        when(statusQuery.isFavorite(7,8)).thenReturn(true);mvc.perform(get("/api/v1/favorites/8/check").header("Authorization",token())).andExpect(status().isOk()).andExpect(jsonPath("$.data").value(true));verify(statusQuery).isFavorite(7,8);
        mvc.perform(put("/api/v1/favorites/8").header("Authorization",token())).andExpect(status().isForbidden());mvc.perform(post("/api/v1/favorites/8/extra").header("Authorization",token())).andExpect(status().isForbidden());verifyNoInteractions(activities);
    }
    private static String token(){return "Bearer "+F3TestInputs.token(7,"ROLE_USER");}private static void cleared(){assertThat(SecurityContextHolder.getContext().getAuthentication()).isNull();}
}
