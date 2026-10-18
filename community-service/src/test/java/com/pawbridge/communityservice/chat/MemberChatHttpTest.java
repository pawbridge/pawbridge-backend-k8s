package com.pawbridge.communityservice.chat;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;

import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

class MemberChatHttpTest {
    @Test
    void givenPeerLookup_whenAuthenticated_thenBindOwnerAndReturnExistingRoom() throws Exception {
        var service = mock(MemberChatService.class);
        var room = UUID.randomUUID();
        when(service.existingRoom(7, 8)).thenReturn(new MemberChatModels.ExistingRoom(room));
        var mvc = MockMvcBuilders.standaloneSetup(new MemberChatController(service, mock(MemberChatTickets.class))).build();
        mvc.perform(get("/api/v1/chats/with/8").header("X-User-Id", "7"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.roomId").value(room.toString()));
        verify(service).existingRoom(7, 8);
    }

    @Test
    void givenPeerLookupWithoutIdentity_whenRequested_thenRejectBeforeService() throws Exception {
        var service = mock(MemberChatService.class);
        var mvc = MockMvcBuilders.standaloneSetup(new MemberChatController(service, mock(MemberChatTickets.class))).build();
        mvc.perform(get("/api/v1/chats/with/8")).andExpect(status().isBadRequest());
        verifyNoInteractions(service);
    }

    @Test
    void givenSocketHandshake_whenRestMappingsChecked_thenNotCapturedAsRoom() throws Exception {
        var service = mock(MemberChatService.class);
        var tickets = mock(MemberChatTickets.class);
        var mvc = MockMvcBuilders.standaloneSetup(new MemberChatController(service, tickets)).build();

        mvc.perform(get("/api/v1/chats/socket")).andExpect(status().isNotFound());
        verifyNoInteractions(service, tickets);
    }
}
