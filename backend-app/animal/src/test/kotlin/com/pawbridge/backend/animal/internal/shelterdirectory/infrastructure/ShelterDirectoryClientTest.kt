package com.pawbridge.backend.animal.internal.shelterdirectory.infrastructure

import com.fasterxml.jackson.databind.ObjectMapper
import io.mockk.every
import io.mockk.spyk
import io.mockk.verify
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import java.nio.charset.StandardCharsets

class ShelterDirectoryClientTest {
    private val client: ShelterDirectoryClient = spyk(ShelterDirectoryClient("test-key", ObjectMapper()))
    private val row: Map<String, String> = mapOf("careRegNo" to "123456789012345", "careNm" to "보호소")

    private fun json(items: String): ByteArray {
        return ("{\"response\":{\"header\":{\"resultCode\":\"00\"},\"body\":{\"totalCount\":856,\"items\":" + items + "}}}")
            .toByteArray(StandardCharsets.UTF_8)
    }

    @Test
    fun `총계가 부풀려졌을 때__빈 페이지에 도달하면__중복 없는 목록을 돌려준다`() {
        // given
        every { client.fetch(1) } returns listOf(row)
        every { client.fetch(2) } returns listOf<Map<String, String>>()

        // when
        val collected = client.collect()

        // then
        assertThat(collected).containsExactly(row)
        verify(exactly = 1) { client.fetch(2) }
    }

    @Test
    fun `마지막 페이지가 빈 객체일 때__수집하면__파싱된 목록을 돌려준다`() {
        // given
        val item = ObjectMapper().writeValueAsString(row)

        every { client.fetch(1) } answers { client.parse(json("{\"item\":[" + item + "]}")) }
        every { client.fetch(2) } answers { client.parse(json("{}")) }

        // when
        val collected = client.collect()

        // then
        assertThat(collected).containsExactly(row)
        verify(exactly = 0) { client.fetch(3) }
    }

    @Test
    fun `첫 페이지가 빈 객체일 때__수집하면__빈 스냅샷을 거부한다`() {
        // given
        every { client.fetch(1) } answers { client.parse(json("{}")) }

        // when
        val thrown = assertThatThrownBy { client.collect() }

        // then
        thrown.hasMessage("SHELTER_DIRECTORY_UNAVAILABLE")
        verify(exactly = 0) { client.fetch(2) }
    }

    @Test
    fun `같은 페이지가 반복될 때__수집하면__반복하지 않고 실패한다`() {
        // given
        every { client.fetch(any()) } returns listOf(row)

        // when
        val thrown = assertThatThrownBy { client.collect() }

        // then
        thrown.hasMessage("SHELTER_DIRECTORY_UNAVAILABLE")
        verify(exactly = 2) { client.fetch(any()) }
    }

    @Test
    fun `같은 등록번호에 충돌하는 내용이 있을 때__수집하면__모호한 스냅샷을 거부한다`() {
        // given
        every { client.fetch(1) } returns
            listOf(row, mapOf("careRegNo" to "123456789012345", "careNm" to "다른 보호소"))

        // when
        val thrown = assertThatThrownBy { client.collect() }

        // then
        thrown.hasMessage("SHELTER_DIRECTORY_UNAVAILABLE")
    }

    @Test
    fun `서비스 키가 비어 있을 때__수집하면__요청 없이 실패한다`() {
        // given
        val blank = spyk(ShelterDirectoryClient(" ", ObjectMapper()))

        // when
        val thrown = assertThatThrownBy { blank.collect() }

        // then
        thrown.hasMessage("SHELTER_DIRECTORY_UNAVAILABLE")
        verify(exactly = 0) { blank.fetch(any()) }
    }

    @Test
    fun `항목이 하나일 때__파싱하면__객체와 배열을 모두 받아들인다`() {
        // given
        val item = ObjectMapper().writeValueAsString(row)

        // when
        val fromObject = client.parse(json("{\"item\":" + item + "}"))
        val fromArray = client.parse(json("{\"item\":[" + item + "]}"))
        val fromEmptyString = client.parse(json("\"\""))

        // then
        assertThat(fromObject).containsExactly(row)
        assertThat(fromArray).containsExactly(row)
        assertThat(fromEmptyString).isEmpty()
    }

    @Test
    fun `응답이 깨졌거나 인증에 실패했을 때__파싱하면__빈 성공을 돌려주지 않는다`() {
        // given
        val rawBodies = listOf("{}", "{\"header\":{\"resultCode\":\"30\"}}", "<error>test-key</error>")

        // when
        // then
        for (raw in rawBodies) {
            assertThatThrownBy { client.parse(raw.toByteArray(StandardCharsets.UTF_8)) }
                .hasMessage("SHELTER_DIRECTORY_UNAVAILABLE")
                .hasNoCause()
        }
        assertThatThrownBy { client.parse(json("{\"unexpected\":[]}")) }.hasMessage("SHELTER_DIRECTORY_UNAVAILABLE")
    }

    @Test
    fun `좌표 쌍이 유효하지 않을 때__정규화하면__좌표를 공개하거나 지우지 않는다`() {
        // given
        // when
        val invalid = ShelterPublicDetails.details(mapOf("lat" to "0", "lng" to "127"))
        val valid = ShelterPublicDetails.details(mapOf("lat" to "37.5", "lng" to "127", "dataStdDt" to "2026-09-13"))

        // then
        assertThat(invalid).doesNotContainKeys("latitude", "longitude")
        assertThat(valid)
            .containsEntry("latitude", 37.5)
            .containsEntry("longitude", 127.0)
            .containsEntry("sourceUpdatedDate", "2026-09-13")
    }
}
