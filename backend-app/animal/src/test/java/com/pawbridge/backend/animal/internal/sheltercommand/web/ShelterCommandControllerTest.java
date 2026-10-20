package com.pawbridge.backend.animal.internal.sheltercommand.web;

import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.pawbridge.backend.animal.internal.sheltercommand.application.*;
import jakarta.persistence.EntityNotFoundException;
import java.time.LocalDateTime;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.core.MethodParameter;
import org.springframework.http.converter.json.MappingJackson2HttpMessageConverter;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.test.web.servlet.*;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.request.NativeWebRequest;
import org.springframework.web.method.support.*;
import org.springframework.web.bind.support.WebDataBinderFactory;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class ShelterCommandControllerTest {
    final ShelterCommands shelters=mock(ShelterCommands.class);
    final ObjectMapper mapper=new ObjectMapper().registerModule(new JavaTimeModule()).disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
    Long principal=10L;
    final MockMvc mvc=MockMvcBuilders.standaloneSetup(new ShelterCommandController(shelters))
            .setControllerAdvice(new ShelterCommandExceptionHandler()).setMessageConverters(new MappingJackson2HttpMessageConverter(mapper))
            .setCustomArgumentResolvers(new HandlerMethodArgumentResolver() {
                public boolean supportsParameter(MethodParameter p){return p.hasParameterAnnotation(AuthenticationPrincipal.class);}
                public Object resolveArgument(MethodParameter p,ModelAndViewContainer c,NativeWebRequest q,WebDataBinderFactory b){return principal;}
            }).build();
    final LocalDateTime now=LocalDateTime.of(2026,10,21,3,0);
    final ShelterCommands.Details result=new ShelterCommands.Details(7L,"care-7","센터","02","서울","대표","기관","a@example.test","소개","절차","09-18",now,now);
    @Test void givenFullCreate__whenPosting__thenUseVerifiedPrincipalAndReturnOriginal201ThirteenFields() throws Exception {
        when(shelters.create(eq(10L),any())).thenReturn(result);
        var response=mvc.perform(post("/api/v1/shelters").header("X-User-Id","999").header("X-User-Role","ROLE_ADMIN")
                .contentType("application/json").content("""
                {"careRegNo":"care-7","name":"센터","phone":"02","address":"서울","ownerName":"대표","organizationName":"기관",
                 "email":"a@example.test","introduction":"소개","adoptionProcedure":"절차","operatingHours":"09-18"}
                """)).andExpect(status().isCreated()).andReturn().getResponse();
        JsonNode body=mapper.readTree(response.getContentAsString(java.nio.charset.StandardCharsets.UTF_8));
        assertThat(body.size()).isEqualTo(13);assertThat(body.path("id").asLong()).isEqualTo(7L);
        assertThat(body.path("createdAt").asText()).isEqualTo("2026-10-21T03:00:00");assertThat(body.has("publicInformation")).isFalse();
        verify(shelters).create(10L,new ShelterCommands.Create("care-7","센터","02","서울","대표","기관","a@example.test","소개","절차","09-18"));
    }
    @Test void givenEmptyPut__whenUpdating__thenForwardFiveNullsAndOmitNullResponseFields() throws Exception {
        when(shelters.update(eq(10L),eq(7L),any())).thenReturn(new ShelterCommands.Details(7L,"care-7","센터",null,null,null,null,null,null,null,null,now,null));
        var body=mapper.readTree(mvc.perform(put("/api/v1/shelters/7").contentType("application/json").content("{}"))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString(java.nio.charset.StandardCharsets.UTF_8));
        assertThat(body.has("phone")).isFalse();assertThat(body.has("updatedAt")).isFalse();
        verify(shelters).update(10L,7L,new ShelterCommands.Update(null,null,null,null,null));
    }
    @Test void givenValidDelete__whenDeleting__thenReturn204WithoutBody() throws Exception {
        var response=mvc.perform(delete("/api/v1/shelters/7")).andExpect(status().isNoContent()).andReturn().getResponse();
        assertThat(response.getContentAsByteArray()).isEmpty();verify(shelters).delete(10L,7L);
    }
    @Test void givenSpoofedHeadersWithoutPrincipal__whenDeleting__thenNeverUseHeaderIdentityOrRole() throws Exception {
        principal=null;doThrow(new ShelterCommandForbiddenException()).when(shelters).delete(null,7L);
        mvc.perform(delete("/api/v1/shelters/7").header("X-User-Id","10").header("X-User-Role","ROLE_ADMIN"))
                .andExpect(status().isForbidden());verify(shelters).delete(null,7L);
    }
    @Test void givenReferenceConflict__whenDeleting__thenReturnFixed409Body() throws Exception {
        doThrow(new ShelterCommandConflictException()).when(shelters).delete(10L,7L);
        var body=mapper.readTree(mvc.perform(delete("/api/v1/shelters/7")).andExpect(status().isConflict()).andReturn().getResponse().getContentAsString(java.nio.charset.StandardCharsets.UTF_8));
        assertThat(body.path("code").asInt()).isEqualTo(409);
        assertThat(body.path("message").asText()).isEqualTo(new ShelterCommandConflictException().getMessage());
        assertThat(body.path("data").isNull()).isTrue();
    }
    @Test void givenMissingEntity__whenDeleting__thenKeepOriginal500WithoutInternalMessage() throws Exception {
        doThrow(new EntityNotFoundException("secret-table-detail")).when(shelters).delete(10L,7L);
        var response=mvc.perform(delete("/api/v1/shelters/7")).andExpect(status().isInternalServerError()).andReturn().getResponse();
        assertThat(response.getContentAsString()).doesNotContain("secret-table-detail");
    }
    @ParameterizedTest @ValueSource(strings={"{broken","[]"})
    void givenMalformedBody__whenCreating__thenReturn400WithoutCallingService(String body) throws Exception {
        mvc.perform(post("/api/v1/shelters").contentType("application/json").content(body)).andExpect(status().isBadRequest());
        verifyNoInteractions(shelters);
    }
}
