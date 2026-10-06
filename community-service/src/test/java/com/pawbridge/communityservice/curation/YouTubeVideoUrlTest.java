package com.pawbridge.communityservice.curation;

import static org.assertj.core.api.Assertions.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.web.server.ResponseStatusException;

class YouTubeVideoUrlTest {
    @ParameterizedTest
    @ValueSource(strings = {"https://www.youtube.com/watch?v=AbCdEfGhI_1&list=ignored",
            "https://youtu.be/AbCdEfGhI_1?t=12", "https://m.youtube.com/shorts/AbCdEfGhI_1",
            "https://youtube.com/embed/AbCdEfGhI_1"})
    void givenAllowedVideoUrl_whenParse_thenReturnOnlyId(String url) {
        assertThat(YouTubeVideoUrl.id(url)).isEqualTo("AbCdEfGhI_1");
    }
    @ParameterizedTest
    @ValueSource(strings = {"http://youtu.be/AbCdEfGhI_1", "https://youtube.com.evil.invalid/watch?v=AbCdEfGhI_1",
            "https://youtube.com@127.0.0.1/watch?v=AbCdEfGhI_1", "https://youtu.be:444/AbCdEfGhI_1",
            "https://youtube.com/watch?v=AbCdEfGhI_1&v=OtherVideo1", "https://youtu.be/short",
            "https://www.youtube.com/playlist?list=AbCdEfGhI_1", "https://127.0.0.1/private",
            "https://youtu.be/AbCdEfGhI_1/extra", "file:///etc/passwd", ""})
    void givenUnsupportedOrSsrfUrl_whenParse_thenRejectWithoutFetching(String url) {
        assertThatThrownBy(() -> YouTubeVideoUrl.id(url)).isInstanceOf(ResponseStatusException.class)
                .satisfies(e -> assertThat(((ResponseStatusException)e).getStatusCode().value()).isEqualTo(400));
    }
}
