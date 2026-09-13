package com.pawbridge.animalservice.controller;

import com.pawbridge.animalservice.facade.ShelterFacade;
import org.junit.jupiter.api.Test;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.web.PageableHandlerMethodArgumentResolver;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class ShelterSearchControllerTest {
    private final ShelterFacade facade = mock(ShelterFacade.class);
    private final org.springframework.test.web.servlet.MockMvc mvc = MockMvcBuilders
            .standaloneSetup(new ShelterController(facade))
            .setCustomArgumentResolvers(new PageableHandlerMethodArgumentResolver()).build();

    @Test void givenKeywordAndRegion__whenList__thenPassBothConditionsAndPage() throws Exception {
        when(facade.searchByKeywordAndAddress(eq("센터"), eq("서울"), any())).thenReturn(Page.empty(org.springframework.data.domain.PageRequest.of(0,12)));
        mvc.perform(get("/api/v1/shelters").param("keyword", "센터").param("address", "서울")
                .param("page", "2").param("size", "12")).andExpect(status().isOk());
        verify(facade).searchByKeywordAndAddress(eq("센터"), eq("서울"),
                argThat(p -> p.getPageNumber() == 2 && p.getPageSize() == 12));
        verifyNoMoreInteractions(facade);
    }

    @Test void givenKeywordOnly__whenList__thenKeepExistingSearch() throws Exception {
        when(facade.searchByNameOrAddress(eq("센터"), any())).thenReturn(Page.empty(org.springframework.data.domain.PageRequest.of(0,12)));
        mvc.perform(get("/api/v1/shelters").param("keyword", "센터")).andExpect(status().isOk());
        verify(facade).searchByNameOrAddress(eq("센터"), any(Pageable.class));
        verifyNoMoreInteractions(facade);
    }

    @Test void givenRegionOnly__whenList__thenKeepExistingSearch() throws Exception {
        when(facade.searchByAddress(eq("서울"), any())).thenReturn(Page.empty(org.springframework.data.domain.PageRequest.of(0,12)));
        mvc.perform(get("/api/v1/shelters").param("address", "서울")).andExpect(status().isOk());
        verify(facade).searchByAddress(eq("서울"), any(Pageable.class));
        verifyNoMoreInteractions(facade);
    }
}
