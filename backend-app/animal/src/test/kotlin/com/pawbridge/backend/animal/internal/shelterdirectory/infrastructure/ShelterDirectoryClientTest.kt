package com.pawbridge.backend.animal.internal.shelterdirectory.infrastructure

import com.fasterxml.jackson.databind.ObjectMapper
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import org.mockito.ArgumentMatchers.anyInt
import org.mockito.Mockito.doAnswer
import org.mockito.Mockito.doReturn
import org.mockito.Mockito.never
import org.mockito.Mockito.spy
import org.mockito.Mockito.times
import org.mockito.Mockito.verify
import java.nio.charset.StandardCharsets

class ShelterDirectoryClientTest {
    private val client: ShelterDirectoryClient = spy(ShelterDirectoryClient("test-key", ObjectMapper()))
    private val row: Map<String, String> = mapOf("careRegNo" to "123456789012345", "careNm" to "보호소")

    private fun json(items: String): ByteArray {
        return ("{\"response\":{\"header\":{\"resultCode\":\"00\"},\"body\":{\"totalCount\":856,\"items\":" + items + "}}}")
            .toByteArray(StandardCharsets.UTF_8)
    }

    @Test
    fun givenInflatedTotal_whenEmptyPageReached_thenReturnsUniqueInventory() {
        doReturn(listOf(row)).`when`(client).fetch(1)
        doReturn(listOf<Map<String, String>>()).`when`(client).fetch(2)

        assertThat(client.collect()).containsExactly(row)
        verify(client, times(1)).fetch(2)
    }

    @Test
    fun givenEmptyObjectLastPage_whenCollecting_thenReturnsParsedInventory() {
        val item = ObjectMapper().writeValueAsString(row)
        doAnswer { client.parse(json("{\"item\":[" + item + "]}")) }.`when`(client).fetch(1)
        doAnswer { client.parse(json("{}")) }.`when`(client).fetch(2)

        assertThat(client.collect()).containsExactly(row)
        verify(client, never()).fetch(3)
    }

    @Test
    fun givenEmptyObjectFirstPage_whenCollecting_thenRejectsEmptySnapshot() {
        doAnswer { client.parse(json("{}")) }.`when`(client).fetch(1)

        assertThatThrownBy { client.collect() }.hasMessage("SHELTER_DIRECTORY_UNAVAILABLE")
        verify(client, never()).fetch(2)
    }

    @Test
    fun givenRepeatedPage_whenCollecting_thenFailsInsteadOfLooping() {
        doReturn(listOf(row)).`when`(client).fetch(anyInt())

        assertThatThrownBy { client.collect() }.hasMessage("SHELTER_DIRECTORY_UNAVAILABLE")
        verify(client, times(2)).fetch(anyInt())
    }

    @Test
    fun givenConflictingRegistration_whenCollecting_thenRejectsAmbiguousSnapshot() {
        doReturn(listOf(row, mapOf("careRegNo" to "123456789012345", "careNm" to "다른 보호소"))).`when`(client).fetch(1)

        assertThatThrownBy { client.collect() }.hasMessage("SHELTER_DIRECTORY_UNAVAILABLE")
    }

    @Test
    fun givenBlankServiceKey_whenCollecting_thenFailsWithoutRequest() {
        val blank = spy(ShelterDirectoryClient(" ", ObjectMapper()))

        assertThatThrownBy { blank.collect() }.hasMessage("SHELTER_DIRECTORY_UNAVAILABLE")
        verify(blank, never()).fetch(anyInt())
    }

    @Test
    fun givenSingleItem_whenParsing_thenAcceptsObjectAndArray() {
        val item = ObjectMapper().writeValueAsString(row)

        assertThat(client.parse(json("{\"item\":" + item + "}"))).containsExactly(row)
        assertThat(client.parse(json("{\"item\":[" + item + "]}"))).containsExactly(row)
        assertThat(client.parse(json("\"\""))).isEmpty()
    }

    @Test
    fun givenMalformedOrAuthFailure_whenParsing_thenNeverReturnsEmptySuccess() {
        for (raw in listOf("{}", "{\"header\":{\"resultCode\":\"30\"}}", "<error>test-key</error>")) {
            assertThatThrownBy { client.parse(raw.toByteArray(StandardCharsets.UTF_8)) }
                .hasMessage("SHELTER_DIRECTORY_UNAVAILABLE")
                .hasNoCause()
        }
        assertThatThrownBy { client.parse(json("{\"unexpected\":[]}")) }.hasMessage("SHELTER_DIRECTORY_UNAVAILABLE")
    }

    @Test
    fun givenInvalidCoordinatePair_whenNormalizing_thenDoesNotPublishOrEraseCoordinates() {
        assertThat(ShelterPublicDetails.details(mapOf("lat" to "0", "lng" to "127")))
            .doesNotContainKeys("latitude", "longitude")
        assertThat(ShelterPublicDetails.details(mapOf("lat" to "37.5", "lng" to "127", "dataStdDt" to "2026-09-13")))
            .containsEntry("latitude", 37.5)
            .containsEntry("longitude", 127.0)
            .containsEntry("sourceUpdatedDate", "2026-09-13")
    }
}
