package com.pawbridge.backend.community.internal.homevideo.infrastructure

import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import org.springframework.web.server.ResponseStatusException

class YouTubeVideoUrlTest {
    @ParameterizedTest
    @ValueSource(
        strings = [
            "https://www.youtube.com/watch?v=AbCdEfGhI_1&list=ignored",
            "https://youtu.be/AbCdEfGhI_1?t=12",
            "https://m.youtube.com/shorts/AbCdEfGhI_1",
            "https://youtube.com/embed/AbCdEfGhI_1",
        ],
    )
    fun `허용된 영상 URL일 때__파싱하면__ID만 돌려준다`(url: String) {
        // given
        val expectedId = "AbCdEfGhI_1"

        // when
        val id = YouTubeVideoUrl.id(url)

        // then
        assertThat(id).isEqualTo(expectedId)
    }

    @ParameterizedTest
    @ValueSource(
        strings = [
            "http://youtu.be/AbCdEfGhI_1",
            "https://youtube.com.evil.invalid/watch?v=AbCdEfGhI_1",
            "https://youtube.com@127.0.0.1/watch?v=AbCdEfGhI_1",
            "https://youtu.be:444/AbCdEfGhI_1",
            "https://youtube.com/watch?v=AbCdEfGhI_1&v=OtherVideo1",
            "https://youtu.be/short",
            "https://www.youtube.com/playlist?list=AbCdEfGhI_1",
            "https://127.0.0.1/private",
            "https://youtu.be/AbCdEfGhI_1/extra",
            "file:///etc/passwd",
            "",
        ],
    )
    fun `지원하지 않거나 SSRF 위험이 있는 URL일 때__파싱하면__가져오지 않고 거부한다`(url: String) {
        // given
        val expectedStatus = 400

        // when
        val thrown = assertThatThrownBy { YouTubeVideoUrl.id(url) }

        // then
        thrown
            .isInstanceOf(ResponseStatusException::class.java)
            .satisfies({ e ->
                assertThat((e as ResponseStatusException).statusCode.value()).isEqualTo(expectedStatus)
            })
    }
}
