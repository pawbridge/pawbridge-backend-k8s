package com.pawbridge.userservice.contact;

import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

class ContactMemberHttpTest {
    private final JdbcTemplate jdbc = mock(JdbcTemplate.class);
    private final MockMvc mvc = MockMvcBuilders
            .standaloneSetup(new ContactMemberController(new ContactMemberQuery(jdbc))).build();

    @Test
    void givenBatchIds_whenRequested_thenBindIdsAndReturnOnlyContactProjection() throws Exception {
        when(jdbc.query(anyString(), org.mockito.ArgumentMatchers.<RowMapper<ContactMember>>any(), eq(7L), eq(8L)))
                .thenReturn(List.of(new ContactMember(8L, "여덟", true, false), new ContactMember(7L, "일곱", true, false)));

        mvc.perform(get("/api/v1/users/internal/contacts").param("ids", "7", "8"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].userId").value(7))
                .andExpect(jsonPath("$[1].nickname").value("여덟"))
                .andExpect(jsonPath("$[0].email").doesNotExist())
                .andExpect(jsonPath("$[0].name").doesNotExist())
                .andExpect(jsonPath("$[0].password").doesNotExist())
                .andExpect(jsonPath("$[0].provider").doesNotExist());
    }

    @Test
    void givenMissingOrInvalidIds_whenRequested_thenRejectWithoutQuery() throws Exception {
        mvc.perform(get("/api/v1/users/internal/contacts")).andExpect(status().isBadRequest());
        mvc.perform(get("/api/v1/users/internal/contacts").param("ids", "0")).andExpect(status().isBadRequest());
        verifyNoInteractions(jdbc);
    }
}
