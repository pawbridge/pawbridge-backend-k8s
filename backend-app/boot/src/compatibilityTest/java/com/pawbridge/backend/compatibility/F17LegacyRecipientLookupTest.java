package com.pawbridge.backend.compatibility;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.pawbridge.communityservice.client.UserServiceClient;
import com.pawbridge.communityservice.contact.F17LegacyRecipientService;
import com.pawbridge.communityservice.contact.PrivateNoteModels.ContactMember;
import com.pawbridge.communityservice.contact.PrivateNoteRepository;
import feign.FeignException;
import feign.Request;
import feign.Response;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.web.server.ResponseStatusException;

/** Original HTTP-boundary branches only; the in-process API has no transport-corruption contract. */
class F17LegacyRecipientLookupTest {
    @ParameterizedTest @ValueSource(strings = {"missing-body", "missing-id", "different-id"})
    void givenAnInvalidOriginalHttpProjection__whenResolvingTheCaller__thenMemberUnavailablePrecedesBlockAndTarget(String kind) {
        UserServiceClient client = mock(UserServiceClient.class);
        PrivateNoteRepository blocks = mock(PrivateNoteRepository.class);
        ContactMember response = switch (kind) {
            case "missing-body" -> null;
            case "missing-id" -> new ContactMember(null, "synthetic", true, false);
            default -> new ContactMember(3L, "synthetic", true, false);
        };
        when(client.getContactMember(1L)).thenReturn(response);
        var service = new F17LegacyRecipientService(blocks, client);
        assertThatThrownBy(() -> service.recipient(1, 2)).isInstanceOfSatisfying(ResponseStatusException.class, failure -> {
            assertThat(failure.getStatusCode().value()).isEqualTo(503);
            assertThat(failure.getReason()).isEqualTo("회원 정보를 확인할 수 없습니다. 잠시 후 다시 시도해 주세요.");
        });
        verifyNoInteractions(blocks);
    }

    @ParameterizedTest @ValueSource(ints = {404, 500})
    void givenAnOriginalHttpFailure__whenResolvingTheCaller__thenOnly404BecomesInactiveAndOtherStatusIsUnavailable(int status) {
        UserServiceClient client = mock(UserServiceClient.class);
        PrivateNoteRepository blocks = mock(PrivateNoteRepository.class);
        var request = Request.create(Request.HttpMethod.GET, "https://fixture.invalid/contact", Map.of(),
                new byte[0], StandardCharsets.UTF_8, null);
        var response = Response.builder().status(status).reason("synthetic").request(request).headers(Map.of()).build();
        when(client.getContactMember(1L)).thenThrow(FeignException.errorStatus("synthetic", response));
        var service = new F17LegacyRecipientService(blocks, client);
        assertThatThrownBy(() -> service.recipient(1, 2)).isInstanceOfSatisfying(ResponseStatusException.class, failure -> {
            assertThat(failure.getStatusCode().value()).isEqualTo(status == 404 ? 403 : 503);
            assertThat(failure.getReason()).isEqualTo(status == 404 ? "이용할 수 없는 회원입니다."
                    : "회원 정보를 확인할 수 없습니다. 잠시 후 다시 시도해 주세요.");
        });
        verifyNoInteractions(blocks);
    }
}
