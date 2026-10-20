package com.pawbridge.backend;

import static org.mockito.Mockito.verifyNoInteractions;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.request;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.pawbridge.backend.animal.internal.gallery.infrastructure.LostGalleryFeed;
import com.pawbridge.backend.animal.internal.gallery.infrastructure.LostGallerySnapshots;
import com.pawbridge.backend.animal.internal.gallery.web.LostGalleryFeedController;
import com.pawbridge.backend.animal.internal.gallery.web.LostGalleryPagesController;
import com.pawbridge.backend.boot.configuration.ReadSecurityConfiguration;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.http.HttpMethod;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

@WebMvcTest(properties="lost-gallery-feed.enabled=true",excludeAutoConfiguration=org.springframework.boot.autoconfigure.security.servlet.UserDetailsServiceAutoConfiguration.class)
@ContextConfiguration(classes={ReadSecurityConfiguration.class,F3TestInputs.SecurityInputs.class,AnimalGalleryFeedSecurityTest.Controllers.class})
class AnimalGalleryFeedSecurityTest {
    @Autowired MockMvc mvc;
    @MockitoBean LostGalleryFeed feed;
    @MockitoBean LostGallerySnapshots snapshots;
    @TestConfiguration(proxyBeanMethods=false) static class Controllers {
        @Bean LostGalleryFeedController feedController(LostGalleryFeed feed) {return new LostGalleryFeedController(feed,"synthetic-internal");}
        @Bean LostGalleryPagesController pagesController(LostGallerySnapshots snapshots) {return new LostGalleryPagesController(snapshots,"synthetic-internal");}
    }
    @ParameterizedTest @CsvSource({"GET,/internal/animals/lost-gallery","POST,/internal/animals/lost-gallery/snapshots","GET,/internal/animals/lost-gallery/snapshots/abc","GET,/internal/animals/lost-gallery/snapshots/abc/pages?cursor=opaque","DELETE,/internal/animals/lost-gallery/snapshots/abc"})
    void givenMissingOrWrongInternalKeyAndAdminJwt_whenCallingFiveRoutes_then401BeforeBusiness(String method,String path) throws Exception {
        mvc.perform(request(HttpMethod.valueOf(method),path).header("Authorization","Bearer "+F3TestInputs.token(7,"ADMIN")))
                .andExpect(status().isUnauthorized());
        mvc.perform(request(HttpMethod.valueOf(method),path).header("X-Internal-Api-Key","wrong-synthetic"))
                .andExpect(status().isUnauthorized());
        verifyNoInteractions(feed,snapshots);
    }
    @Test void givenOnlyExactFiveRoutesEnabled_whenCallingNeighborsOrWrongMethods_then403BeforeBusiness() throws Exception {
        for(String pair:new String[]{"POST /internal/animals/lost-gallery","GET /internal/animals/lost-gallery/snapshots","PATCH /internal/animals/lost-gallery/snapshots/abc","POST /internal/animals/lost-gallery/snapshots/abc/pages","GET /internal/animals/other","GET /internal/animals/lost-gallery/snapshots/abc/other"}) {
            String[] parts=pair.split(" ",2);mvc.perform(request(HttpMethod.valueOf(parts[0]),parts[1]).header("X-Internal-Api-Key","synthetic-internal"))
                    .andExpect(status().isForbidden());
        }
        verifyNoInteractions(feed,snapshots);
    }
}

@WebMvcTest(excludeAutoConfiguration=org.springframework.boot.autoconfigure.security.servlet.UserDetailsServiceAutoConfiguration.class)
@ContextConfiguration(classes={ReadSecurityConfiguration.class,F3TestInputs.SecurityInputs.class,AnimalGalleryFeedSecurityTest.Controllers.class})
class AnimalGalleryFeedDisabledHttpTest {
    @Autowired MockMvc mvc;
    @MockitoBean LostGalleryFeed feed;
    @MockitoBean LostGallerySnapshots snapshots;
    @Test void givenFeatureDisabledAndCorrectKey_whenCallingAllFiveRoutes_then403BeforeBusiness() throws Exception {
        for(String pair:new String[]{"GET /internal/animals/lost-gallery","POST /internal/animals/lost-gallery/snapshots","GET /internal/animals/lost-gallery/snapshots/abc","GET /internal/animals/lost-gallery/snapshots/abc/pages?cursor=opaque","DELETE /internal/animals/lost-gallery/snapshots/abc"}) {
            String[] parts=pair.split(" ",2);mvc.perform(request(HttpMethod.valueOf(parts[0]),parts[1]).header("X-Internal-Api-Key","synthetic-internal"))
                    .andExpect(status().isForbidden());
        }
        verifyNoInteractions(feed,snapshots);
    }
}
