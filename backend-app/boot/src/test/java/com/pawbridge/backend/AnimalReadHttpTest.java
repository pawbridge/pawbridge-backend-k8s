package com.pawbridge.backend;

import com.pawbridge.backend.animal.internal.discovery.application.AnimalDiscovery;
import com.pawbridge.backend.animal.internal.discovery.application.AnimalNotFoundException;
import com.pawbridge.backend.animal.internal.discovery.web.AnimalDiscoveryController;
import com.pawbridge.backend.animal.internal.discovery.web.AnimalDiscoveryExceptionHandler;
import com.pawbridge.backend.animal.internal.recommendation.application.AnimalRecommendations;
import com.pawbridge.backend.animal.internal.recommendation.web.AnimalRecommendationController;
import com.pawbridge.backend.animal.internal.recommendation.web.AnimalRecommendationExceptionHandler;
import com.pawbridge.backend.boot.configuration.ReadSecurityConfiguration;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.http.HttpMethod;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@WebMvcTest(properties = {"pawbridge.animal-discovery.enabled=true", "pawbridge.animal-recommendations.enabled=true",
        "pawbridge.animal-query.backend=postgresql"},
        excludeAutoConfiguration = org.springframework.boot.autoconfigure.security.servlet.UserDetailsServiceAutoConfiguration.class)
@ContextConfiguration(classes = {AnimalDiscoveryController.class, AnimalDiscoveryExceptionHandler.class,
        AnimalRecommendationController.class, AnimalRecommendationExceptionHandler.class,
        ReadSecurityConfiguration.class, F3TestInputs.SecurityInputs.class})
class AnimalReadHttpTest {
    @Autowired MockMvc mvc;
    @MockitoBean AnimalDiscovery animals;
    @MockitoBean AnimalRecommendations recommendations;

    @Test
    void given__enabled_public_reads__when__anonymous_or_bad_token_requests_exact_routes__then__reach_the_owned_controllers_without_sessions() throws Exception {
        when(animals.search(any(), any())).thenReturn(new AnimalDiscovery.Page(List.of(), 0, 20, 0, 0, true, true));
        when(animals.findById(42)).thenThrow(new AnimalNotFoundException());
        when(recommendations.recommend(42)).thenReturn(List.of());
        mvc.perform(get("/api/v1/animals").param("species", "DOG").header("Authorization", "Bearer invalid"))
                .andExpect(status().isOk()).andExpect(header().doesNotExist("Set-Cookie"));
        mvc.perform(get("/api/v1/animals/42").param("view", "detail"))
                .andExpect(status().isNotFound()).andExpect(jsonPath("$.code").value(404));
        mvc.perform(get("/api/v1/animals/42/similar").param("view", "cards").header("Authorization", "Bearer invalid"))
                .andExpect(status().isOk()).andExpect(content().json("[]")).andExpect(header().doesNotExist("Set-Cookie"));
        verify(animals).search(any(), any());
        verify(animals).findById(42);
        verify(recommendations).recommend(42);
    }

    @Test
    void given__unimported_neighbors_and_mutations__when__requesting_even_with_admin_token__then__deny_without_invoking_animal_code() throws Exception {
        String admin = "Bearer " + F3TestInputs.token(7, "ROLE_ADMIN");
        for (String path : List.of("/api/v1/animals/abc",
                "/api/v1/animals/-1", "/api/v1/animals/42/extra", "/api/v1/animals/42/similar/extra",
                "/api/v1/animals/abc/similar", "/api/animals/42/similar", "/api/v1/animals/apms",
                "/api/v1/animals/apms/D-42/extra", "/api/v1/animals/expiring-soon/extra",
                "/api/v1/animals/expiring-soon-extra")) {
            mvc.perform(get(path).header("Authorization", admin)).andExpect(status().isForbidden());
        }
        for (HttpMethod method : List.of(HttpMethod.POST, HttpMethod.PUT, HttpMethod.PATCH, HttpMethod.DELETE)) {
            mvc.perform(request(method, "/api/v1/animals/42").header("Authorization", admin)).andExpect(status().isForbidden());
        }
        for (String path : List.of("/api/v1/animals/apms/D-42", "/api/v1/animals/expiring-soon")) {
            for (HttpMethod method : List.of(HttpMethod.POST, HttpMethod.PUT, HttpMethod.PATCH, HttpMethod.DELETE,
                    HttpMethod.HEAD, HttpMethod.OPTIONS)) {
                mvc.perform(request(method, path).header("Authorization", admin)).andExpect(status().isForbidden());
            }
        }
        verifyNoInteractions(animals, recommendations);
    }

    @Test
    void given__existing_protected_route__when__token_is_missing_or_invalid__then__keep_jwt_rejection_before_mvc() throws Exception {
        mvc.perform(get("/api/v1/users/me")).andExpect(status().isUnauthorized()).andExpect(jsonPath("$.code").value(401));
        mvc.perform(get("/api/v1/users/me").header("Authorization", "Bearer invalid"))
                .andExpect(status().isUnauthorized()).andExpect(header().doesNotExist("Set-Cookie"));
        verifyNoInteractions(animals, recommendations);
    }
    @Test
    void given__apms_lookup_enabled__when__anonymous_or_bad_token_reads_exact_number__then__return_existing_28_field_detail_without_session() throws Exception {
        var date = java.time.LocalDate.of(2026, 1, 2);
        var time = date.atTime(3, 4, 5, 123456000);
        when(animals.findByApmsDesertionNo("D-42")).thenReturn(new AnimalDiscovery.Details(42L, "N-42",
                AnimalDiscovery.Species.DOG, "말티즈", AnimalDiscovery.Gender.FEMALE, AnimalDiscovery.NeuterStatus.YES,
                2022, 4, "3.5(Kg)", "흰색", "특징", AnimalDiscovery.AnimalStatus.ADOPTED, date, date.plusDays(3),
                "합성 장소", date, "one", "two", "설명", 0, 8L, "합성 보호소", "D-42", "종료(입양)", time,
                AnimalDiscovery.ApiSource.APMS_ANIMAL, time, time));
        var response = mvc.perform(get("/api/v1/animals/apms/D-42").header("Authorization", "Bearer invalid"))
                .andExpect(status().isOk()).andExpect(header().doesNotExist("Set-Cookie"))
                .andExpect(jsonPath("$.id").value(42)).andExpect(jsonPath("$.status").value("ADOPTED"))
                .andExpect(jsonPath("$.apmsDesertionNo").value("D-42"))
                .andExpect(jsonPath("$.updatedAt").value("2026-01-02T03:04:05.123456"))
                .andReturn().getResponse();
        var body = new com.fasterxml.jackson.databind.ObjectMapper().readTree(response.getContentAsString());
        java.util.Set<String> fields = new java.util.HashSet<>(); body.fieldNames().forEachRemaining(fields::add);
        org.junit.jupiter.api.Assertions.assertEquals(java.util.Set.of("id", "apmsNoticeNo", "species", "breed", "gender",
                "neuterStatus", "birthYear", "age", "weight", "color", "specialMark", "status", "noticeStartDate",
                "noticeEndDate", "happenPlace", "happenDate", "imageUrl", "imageUrl2", "description", "favoriteCount",
                "shelterId", "shelterName", "apmsDesertionNo", "apmsProcessState", "apmsUpdatedAt", "apiSource",
                "createdAt", "updatedAt"), fields);
        verify(animals).findByApmsDesertionNo("D-42");
        verifyNoMoreInteractions(animals);
        verifyNoInteractions(recommendations);
    }

    @Test
    void given__expiry_lookup_enabled__when__anonymous_reads_default_page__then__use_original_default_and_seven_field_wire() throws Exception {
        when(animals.findExpiringSoonAnimals(any())).thenReturn(new AnimalDiscovery.Page(List.of(), 0, 20, 0, 0, true, true));
        mvc.perform(get("/api/v1/animals/expiring-soon"))
                .andExpect(status().isOk()).andExpect(header().doesNotExist("Set-Cookie"))
                .andExpect(content().json("{\"content\":[],\"number\":0,\"size\":20,\"totalElements\":0,\"totalPages\":0,\"first\":true,\"last\":true}", true));
        verify(animals).findExpiringSoonAnimals(new AnimalDiscovery.PageRequest(0, 20,
                List.of(new AnimalDiscovery.Order("noticeEndDate", true)), true));
        verifyNoMoreInteractions(animals);
        verifyNoInteractions(recommendations);
    }

    @Test
    void given__extra_read_errors__when__public_queries_reach_controller__then__preserve_404_and_private_database_500() throws Exception {
        when(animals.findByApmsDesertionNo("missing")).thenThrow(new AnimalNotFoundException());
        when(animals.findExpiringSoonAnimals(any())).thenThrow(new org.springframework.dao.DataAccessResourceFailureException("synthetic private SQL"));
        mvc.perform(get("/api/v1/animals/apms/missing")).andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value(404)).andExpect(jsonPath("$.message").value("동물을 찾을 수 없습니다"));
        mvc.perform(get("/api/v1/animals/expiring-soon")).andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.code").value(500)).andExpect(jsonPath("$.message").value("데이터베이스 오류가 발생했습니다."));
        verify(animals).findByApmsDesertionNo("missing");
        verify(animals).findExpiringSoonAnimals(any());
        verifyNoMoreInteractions(animals);
        verifyNoInteractions(recommendations);
    }

}


@WebMvcTest(properties = {"pawbridge.animal-discovery.enabled=false", "pawbridge.animal-query.backend=postgresql"},
        excludeAutoConfiguration = org.springframework.boot.autoconfigure.security.servlet.UserDetailsServiceAutoConfiguration.class)
@ContextConfiguration(classes = {AnimalDiscoveryController.class, AnimalDiscoveryExceptionHandler.class,
        ReadSecurityConfiguration.class, F3TestInputs.SecurityInputs.class})
class AnimalExtraReadDisabledHttpTest {
    @Autowired MockMvc mvc;
    @MockitoBean AnimalDiscovery animals;

    @Test
    void given__disabled_discovery__when__requesting_extra_reads_even_as_admin__then__deny_without_query() throws Exception {
        String admin = "Bearer " + F3TestInputs.token(7, "ROLE_ADMIN");
        for (String path : List.of("/api/v1/animals/apms/D-42", "/api/v1/animals/expiring-soon")) {
            mvc.perform(get(path).header("Authorization", admin)).andExpect(status().isForbidden());
        }
        verifyNoInteractions(animals);
    }
}


@WebMvcTest(properties = {"pawbridge.animal-discovery.enabled=on", "pawbridge.animal-query.backend=postgresql"},
        excludeAutoConfiguration = org.springframework.boot.autoconfigure.security.servlet.UserDetailsServiceAutoConfiguration.class)
@ContextConfiguration(classes = {AnimalDiscoveryController.class, AnimalDiscoveryExceptionHandler.class,
        ReadSecurityConfiguration.class, F3TestInputs.SecurityInputs.class})
class AnimalExtraReadOnAliasHttpTest {
    @Autowired MockMvc mvc;
    @MockitoBean AnimalDiscovery animals;

    @Test
    void given__onalias_discovery__when__requesting_extra_reads_even_as_admin__then__deny_without_query() throws Exception {
        String admin = "Bearer " + F3TestInputs.token(7, "ROLE_ADMIN");
        for (String path : List.of("/api/v1/animals/apms/D-42", "/api/v1/animals/expiring-soon")) {
            mvc.perform(get(path).header("Authorization", admin)).andExpect(status().isForbidden());
        }
        verifyNoInteractions(animals);
    }
}


@WebMvcTest(properties = {"pawbridge.animal-discovery.enabled=yes", "pawbridge.animal-query.backend=postgresql"},
        excludeAutoConfiguration = org.springframework.boot.autoconfigure.security.servlet.UserDetailsServiceAutoConfiguration.class)
@ContextConfiguration(classes = {AnimalDiscoveryController.class, AnimalDiscoveryExceptionHandler.class,
        ReadSecurityConfiguration.class, F3TestInputs.SecurityInputs.class})
class AnimalExtraReadYesAliasHttpTest {
    @Autowired MockMvc mvc;
    @MockitoBean AnimalDiscovery animals;

    @Test
    void given__yesalias_discovery__when__requesting_extra_reads_even_as_admin__then__deny_without_query() throws Exception {
        String admin = "Bearer " + F3TestInputs.token(7, "ROLE_ADMIN");
        for (String path : List.of("/api/v1/animals/apms/D-42", "/api/v1/animals/expiring-soon")) {
            mvc.perform(get(path).header("Authorization", admin)).andExpect(status().isForbidden());
        }
        verifyNoInteractions(animals);
    }
}


@WebMvcTest(properties = {"pawbridge.animal-discovery.enabled=true", "pawbridge.animal-query.backend=elasticsearch"},
        excludeAutoConfiguration = org.springframework.boot.autoconfigure.security.servlet.UserDetailsServiceAutoConfiguration.class)
@ContextConfiguration(classes = {AnimalDiscoveryController.class, AnimalDiscoveryExceptionHandler.class,
        ReadSecurityConfiguration.class, F3TestInputs.SecurityInputs.class})
class AnimalExtraReadOtherBackendHttpTest {
    @Autowired MockMvc mvc;
    @MockitoBean AnimalDiscovery animals;

    @Test
    void given__otherbackend_discovery__when__requesting_extra_reads_even_as_admin__then__deny_without_query() throws Exception {
        String admin = "Bearer " + F3TestInputs.token(7, "ROLE_ADMIN");
        for (String path : List.of("/api/v1/animals/apms/D-42", "/api/v1/animals/expiring-soon")) {
            mvc.perform(get(path).header("Authorization", admin)).andExpect(status().isForbidden());
        }
        verifyNoInteractions(animals);
    }
}
