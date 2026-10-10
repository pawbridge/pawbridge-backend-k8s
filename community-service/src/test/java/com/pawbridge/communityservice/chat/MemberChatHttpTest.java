package com.pawbridge.communityservice.chat;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

class MemberChatHttpTest {
    @Test
    void givenSocketHandshake_whenRestMappingsChecked_thenNotCapturedAsRoom() throws Exception {
        var service = mock(MemberChatService.class);
        var tickets = mock(MemberChatTickets.class);
        var mvc = MockMvcBuilders.standaloneSetup(new MemberChatController(service, tickets)).build();

        mvc.perform(get("/api/v1/chats/socket")).andExpect(status().isNotFound());
        verifyNoInteractions(service, tickets);
    }
}
