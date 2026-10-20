package com.pawbridge.backend;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.request;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import com.pawbridge.backend.boot.configuration.ReadSecurityConfiguration;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import com.pawbridge.backend.animal.internal.travel.application.PetTravel;
import com.pawbridge.backend.animal.internal.travel.web.PetTravelController;

@WebMvcTest(excludeAutoConfiguration=org.springframework.boot.autoconfigure.security.servlet.UserDetailsServiceAutoConfiguration.class)
@ContextConfiguration(classes={PetTravelController.class,ReadSecurityConfiguration.class,F3TestInputs.SecurityInputs.class})
class AnimalTravelDisabledHttpTest {
    @Autowired MockMvc mvc; @MockitoBean PetTravel travel;
    @ParameterizedTest @CsvSource({"/api/v1/places/regions","/api/v1/places","/api/v1/places/7"})
    void given__disabled_travel__when__public_read_is_requested__then__deny_before_business(String path) throws Exception {
        mvc.perform(request(HttpMethod.GET,path)).andExpect(status().isForbidden());verifyNoInteractions(travel);
    }
}
@WebMvcTest(properties={"pawbridge.animal-travel.enabled=true","pawbridge.animal-query.backend=elasticsearch"},excludeAutoConfiguration=org.springframework.boot.autoconfigure.security.servlet.UserDetailsServiceAutoConfiguration.class)
@ContextConfiguration(classes={PetTravelController.class,ReadSecurityConfiguration.class,F3TestInputs.SecurityInputs.class})
class AnimalTravelBackendDisabledHttpTest {
    @Autowired MockMvc mvc; @MockitoBean PetTravel travel;
    @ParameterizedTest @CsvSource({"/api/v1/places/regions","/api/v1/places","/api/v1/places/7"})
    void given__wrong_read_backend__when__public_read_is_requested__then__deny_before_business(String path) throws Exception {
        mvc.perform(request(HttpMethod.GET,path)).andExpect(status().isForbidden());verifyNoInteractions(travel);
    }
}
@WebMvcTest(properties={"pawbridge.animal-travel.enabled=true","pawbridge.animal-query.backend=postgresql"},excludeAutoConfiguration=org.springframework.boot.autoconfigure.security.servlet.UserDetailsServiceAutoConfiguration.class)
@ContextConfiguration(classes={PetTravelController.class,ReadSecurityConfiguration.class,F3TestInputs.SecurityInputs.class})
class AnimalTravelNeighborDisabledHttpTest {
    @Autowired MockMvc mvc; @MockitoBean PetTravel travel;
    @ParameterizedTest @CsvSource({"POST,/api/v1/places","POST,/api/v1/internal/places/refresh","DELETE,/api/v1/places/7"})
    void given__only_public_travel_reading_enabled__when__write_or_collection_is_requested__then__deny_before_business(String method,String path) throws Exception {
        mvc.perform(request(HttpMethod.valueOf(method),path)).andExpect(status().isForbidden());verifyNoInteractions(travel);
    }
}
