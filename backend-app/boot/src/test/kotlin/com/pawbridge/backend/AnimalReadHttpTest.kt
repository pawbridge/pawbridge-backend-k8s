package com.pawbridge.backend

import com.fasterxml.jackson.databind.ObjectMapper
import com.pawbridge.backend.animal.internal.discovery.application.AnimalDiscovery
import com.pawbridge.backend.animal.internal.discovery.application.AnimalNotFoundException
import com.pawbridge.backend.animal.internal.discovery.web.AnimalDiscoveryController
import com.pawbridge.backend.animal.internal.discovery.web.AnimalDiscoveryExceptionHandler
import com.pawbridge.backend.animal.internal.recommendation.application.AnimalRecommendations
import com.pawbridge.backend.animal.internal.recommendation.web.AnimalRecommendationController
import com.pawbridge.backend.animal.internal.recommendation.web.AnimalRecommendationExceptionHandler
import com.pawbridge.backend.boot.configuration.ReadSecurityConfiguration
import com.pawbridge.backend.support.anyNonNull
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.mockito.Mockito.`when` as whenever
import org.mockito.Mockito.verify
import org.mockito.Mockito.verifyNoInteractions
import org.mockito.Mockito.verifyNoMoreInteractions
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.autoconfigure.security.servlet.UserDetailsServiceAutoConfiguration
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest
import org.springframework.dao.DataAccessResourceFailureException
import org.springframework.http.HttpMethod
import org.springframework.test.context.ContextConfiguration
import org.springframework.test.context.bean.override.mockito.MockitoBean
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.request
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.content
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.header
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import java.time.LocalDate

@WebMvcTest(
    properties = [
        "pawbridge.animal-discovery.enabled=true",
        "pawbridge.animal-recommendations.enabled=true",
        "pawbridge.animal-query.backend=postgresql",
    ],
    excludeAutoConfiguration = [UserDetailsServiceAutoConfiguration::class],
)
@ContextConfiguration(
    classes = [
        AnimalDiscoveryController::class,
        AnimalDiscoveryExceptionHandler::class,
        AnimalRecommendationController::class,
        AnimalRecommendationExceptionHandler::class,
        ReadSecurityConfiguration::class,
        F3TestInputs.SecurityInputs::class,
    ],
)
class AnimalReadHttpTest {
    @Autowired
    lateinit var mvc: MockMvc

    @MockitoBean
    lateinit var animals: AnimalDiscovery

    @MockitoBean
    lateinit var recommendations: AnimalRecommendations

    @Test
    fun given__enabled_public_reads__when__anonymous_or_bad_token_requests_exact_routes__then__reach_the_owned_controllers_without_sessions() {
        whenever(animals.search(anyNonNull<AnimalDiscovery.Search>(), anyNonNull<AnimalDiscovery.PageRequest>()))
            .thenReturn(AnimalDiscovery.Page(listOf(), 0, 20, 0, 0, true, true))
        whenever(animals.findById(42)).thenThrow(AnimalNotFoundException())
        whenever(recommendations.recommend(42)).thenReturn(listOf())
        mvc.perform(get("/api/v1/animals").param("species", "DOG").header("Authorization", "Bearer invalid"))
            .andExpect(status().isOk())
            .andExpect(header().doesNotExist("Set-Cookie"))
        mvc.perform(get("/api/v1/animals/42").param("view", "detail"))
            .andExpect(status().isNotFound())
            .andExpect(jsonPath("$.code").value(404))
        mvc.perform(get("/api/v1/animals/42/similar").param("view", "cards").header("Authorization", "Bearer invalid"))
            .andExpect(status().isOk())
            .andExpect(content().json("[]"))
            .andExpect(header().doesNotExist("Set-Cookie"))
        verify(animals).search(anyNonNull<AnimalDiscovery.Search>(), anyNonNull<AnimalDiscovery.PageRequest>())
        verify(animals).findById(42)
        verify(recommendations).recommend(42)
    }

    @Test
    fun given__unimported_neighbors_and_mutations__when__requesting_even_with_admin_token__then__deny_without_invoking_animal_code() {
        val admin = "Bearer " + F3TestInputs.token(7, "ROLE_ADMIN")
        val paths = listOf(
            "/api/v1/animals/abc",
            "/api/v1/animals/-1", "/api/v1/animals/42/extra", "/api/v1/animals/42/similar/extra",
            // Security chain only: LegacyApiPathFilter (the temporary /api -> /api/v1 rewrite) is a servlet filter outside it, so the old alias stays denied here; the rewrite is covered by GatewayFeaturesHttpTest.
            "/api/v1/animals/abc/similar", "/api/animals/42/similar", "/api/v1/animals/apms",
            "/api/v1/animals/apms/D-42/extra", "/api/v1/animals/expiring-soon/extra",
            "/api/v1/animals/expiring-soon-extra",
        )
        for (path in paths) {
            mvc.perform(get(path).header("Authorization", admin)).andExpect(status().isForbidden())
        }
        for (method in listOf(HttpMethod.POST, HttpMethod.PUT, HttpMethod.PATCH, HttpMethod.DELETE)) {
            mvc.perform(request(method, "/api/v1/animals/42").header("Authorization", admin))
                .andExpect(status().isForbidden())
        }
        for (path in listOf("/api/v1/animals/apms/D-42", "/api/v1/animals/expiring-soon")) {
            val methods = listOf(
                HttpMethod.POST, HttpMethod.PUT, HttpMethod.PATCH, HttpMethod.DELETE,
                HttpMethod.HEAD, HttpMethod.OPTIONS,
            )
            for (method in methods) {
                mvc.perform(request(method, path).header("Authorization", admin)).andExpect(status().isForbidden())
            }
        }
        verifyNoInteractions(animals, recommendations)
    }

    @Test
    fun given__existing_protected_route__when__token_is_missing_or_invalid__then__keep_jwt_rejection_before_mvc() {
        mvc.perform(get("/api/v1/users/me")).andExpect(status().isUnauthorized()).andExpect(jsonPath("$.code").value(401))
        mvc.perform(get("/api/v1/users/me").header("Authorization", "Bearer invalid"))
            .andExpect(status().isUnauthorized())
            .andExpect(header().doesNotExist("Set-Cookie"))
        verifyNoInteractions(animals, recommendations)
    }

    @Test
    fun given__apms_lookup_enabled__when__anonymous_or_bad_token_reads_exact_number__then__return_existing_28_field_detail_without_session() {
        val date = LocalDate.of(2026, 1, 2)
        val time = date.atTime(3, 4, 5, 123456000)
        whenever(animals.findByApmsDesertionNo("D-42")).thenReturn(
            AnimalDiscovery.Details(
                42L, "N-42",
                AnimalDiscovery.Species.DOG, "말티즈", AnimalDiscovery.Gender.FEMALE, AnimalDiscovery.NeuterStatus.YES,
                2022, 4, "3.5(Kg)", "흰색", "특징", AnimalDiscovery.AnimalStatus.ADOPTED, date, date.plusDays(3),
                "합성 장소", date, "one", "two", "설명", 0, 8L, "합성 보호소", "D-42", "종료(입양)", time,
                AnimalDiscovery.ApiSource.APMS_ANIMAL, time, time,
            ),
        )
        val response = mvc.perform(get("/api/v1/animals/apms/D-42").header("Authorization", "Bearer invalid"))
            .andExpect(status().isOk())
            .andExpect(header().doesNotExist("Set-Cookie"))
            .andExpect(jsonPath("$.id").value(42))
            .andExpect(jsonPath("$.status").value("ADOPTED"))
            .andExpect(jsonPath("$.apmsDesertionNo").value("D-42"))
            .andExpect(jsonPath("$.updatedAt").value("2026-01-02T03:04:05.123456"))
            .andReturn().response
        val body = ObjectMapper().readTree(response.contentAsString)
        val fields = HashSet<String>()
        body.fieldNames().forEachRemaining { name -> fields.add(name) }
        assertEquals(
            setOf(
                "id", "apmsNoticeNo", "species", "breed", "gender",
                "neuterStatus", "birthYear", "age", "weight", "color", "specialMark", "status", "noticeStartDate",
                "noticeEndDate", "happenPlace", "happenDate", "imageUrl", "imageUrl2", "description", "favoriteCount",
                "shelterId", "shelterName", "apmsDesertionNo", "apmsProcessState", "apmsUpdatedAt", "apiSource",
                "createdAt", "updatedAt",
            ),
            fields,
        )
        verify(animals).findByApmsDesertionNo("D-42")
        verifyNoMoreInteractions(animals)
        verifyNoInteractions(recommendations)
    }

    @Test
    fun given__expiry_lookup_enabled__when__anonymous_reads_default_page__then__use_original_default_and_seven_field_wire() {
        whenever(animals.findExpiringSoonAnimals(anyNonNull<AnimalDiscovery.PageRequest>()))
            .thenReturn(AnimalDiscovery.Page(listOf(), 0, 20, 0, 0, true, true))
        mvc.perform(get("/api/v1/animals/expiring-soon"))
            .andExpect(status().isOk())
            .andExpect(header().doesNotExist("Set-Cookie"))
            .andExpect(
                content().json(
                    "{\"content\":[],\"number\":0,\"size\":20,\"totalElements\":0,\"totalPages\":0,\"first\":true,\"last\":true}",
                    true,
                ),
            )
        verify(animals).findExpiringSoonAnimals(
            AnimalDiscovery.PageRequest(0, 20, listOf(AnimalDiscovery.Order("noticeEndDate", true)), true),
        )
        verifyNoMoreInteractions(animals)
        verifyNoInteractions(recommendations)
    }

    @Test
    fun given__extra_read_errors__when__public_queries_reach_controller__then__preserve_404_and_private_database_500() {
        whenever(animals.findByApmsDesertionNo("missing")).thenThrow(AnimalNotFoundException())
        whenever(animals.findExpiringSoonAnimals(anyNonNull<AnimalDiscovery.PageRequest>()))
            .thenThrow(DataAccessResourceFailureException("synthetic private SQL"))
        mvc.perform(get("/api/v1/animals/apms/missing"))
            .andExpect(status().isNotFound())
            .andExpect(jsonPath("$.code").value(404))
            .andExpect(jsonPath("$.message").value("동물을 찾을 수 없습니다"))
        mvc.perform(get("/api/v1/animals/expiring-soon"))
            .andExpect(status().isInternalServerError())
            .andExpect(jsonPath("$.code").value(500))
            .andExpect(jsonPath("$.message").value("데이터베이스 오류가 발생했습니다."))
        verify(animals).findByApmsDesertionNo("missing")
        verify(animals).findExpiringSoonAnimals(anyNonNull<AnimalDiscovery.PageRequest>())
        verifyNoMoreInteractions(animals)
        verifyNoInteractions(recommendations)
    }
}

@WebMvcTest(
    properties = ["pawbridge.animal-discovery.enabled=false", "pawbridge.animal-query.backend=postgresql"],
    excludeAutoConfiguration = [UserDetailsServiceAutoConfiguration::class],
)
@ContextConfiguration(
    classes = [
        AnimalDiscoveryController::class,
        AnimalDiscoveryExceptionHandler::class,
        ReadSecurityConfiguration::class,
        F3TestInputs.SecurityInputs::class,
    ],
)
class AnimalExtraReadDisabledHttpTest {
    @Autowired
    lateinit var mvc: MockMvc

    @MockitoBean
    lateinit var animals: AnimalDiscovery

    @Test
    fun given__disabled_discovery__when__requesting_extra_reads_even_as_admin__then__deny_without_query() {
        val admin = "Bearer " + F3TestInputs.token(7, "ROLE_ADMIN")
        for (path in listOf("/api/v1/animals/apms/D-42", "/api/v1/animals/expiring-soon")) {
            mvc.perform(get(path).header("Authorization", admin)).andExpect(status().isForbidden())
        }
        verifyNoInteractions(animals)
    }
}

@WebMvcTest(
    properties = ["pawbridge.animal-discovery.enabled=on", "pawbridge.animal-query.backend=postgresql"],
    excludeAutoConfiguration = [UserDetailsServiceAutoConfiguration::class],
)
@ContextConfiguration(
    classes = [
        AnimalDiscoveryController::class,
        AnimalDiscoveryExceptionHandler::class,
        ReadSecurityConfiguration::class,
        F3TestInputs.SecurityInputs::class,
    ],
)
class AnimalExtraReadOnAliasHttpTest {
    @Autowired
    lateinit var mvc: MockMvc

    @MockitoBean
    lateinit var animals: AnimalDiscovery

    @Test
    fun given__onalias_discovery__when__requesting_extra_reads_even_as_admin__then__deny_without_query() {
        val admin = "Bearer " + F3TestInputs.token(7, "ROLE_ADMIN")
        for (path in listOf("/api/v1/animals/apms/D-42", "/api/v1/animals/expiring-soon")) {
            mvc.perform(get(path).header("Authorization", admin)).andExpect(status().isForbidden())
        }
        verifyNoInteractions(animals)
    }
}

@WebMvcTest(
    properties = ["pawbridge.animal-discovery.enabled=yes", "pawbridge.animal-query.backend=postgresql"],
    excludeAutoConfiguration = [UserDetailsServiceAutoConfiguration::class],
)
@ContextConfiguration(
    classes = [
        AnimalDiscoveryController::class,
        AnimalDiscoveryExceptionHandler::class,
        ReadSecurityConfiguration::class,
        F3TestInputs.SecurityInputs::class,
    ],
)
class AnimalExtraReadYesAliasHttpTest {
    @Autowired
    lateinit var mvc: MockMvc

    @MockitoBean
    lateinit var animals: AnimalDiscovery

    @Test
    fun given__yesalias_discovery__when__requesting_extra_reads_even_as_admin__then__deny_without_query() {
        val admin = "Bearer " + F3TestInputs.token(7, "ROLE_ADMIN")
        for (path in listOf("/api/v1/animals/apms/D-42", "/api/v1/animals/expiring-soon")) {
            mvc.perform(get(path).header("Authorization", admin)).andExpect(status().isForbidden())
        }
        verifyNoInteractions(animals)
    }
}

@WebMvcTest(
    properties = ["pawbridge.animal-discovery.enabled=true", "pawbridge.animal-query.backend=elasticsearch"],
    excludeAutoConfiguration = [UserDetailsServiceAutoConfiguration::class],
)
@ContextConfiguration(
    classes = [
        AnimalDiscoveryController::class,
        AnimalDiscoveryExceptionHandler::class,
        ReadSecurityConfiguration::class,
        F3TestInputs.SecurityInputs::class,
    ],
)
class AnimalExtraReadOtherBackendHttpTest {
    @Autowired
    lateinit var mvc: MockMvc

    @MockitoBean
    lateinit var animals: AnimalDiscovery

    @Test
    fun given__otherbackend_discovery__when__requesting_extra_reads_even_as_admin__then__deny_without_query() {
        val admin = "Bearer " + F3TestInputs.token(7, "ROLE_ADMIN")
        for (path in listOf("/api/v1/animals/apms/D-42", "/api/v1/animals/expiring-soon")) {
            mvc.perform(get(path).header("Authorization", admin)).andExpect(status().isForbidden())
        }
        verifyNoInteractions(animals)
    }
}
