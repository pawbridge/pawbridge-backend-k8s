package com.pawbridge.animalservice.shelter;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class ShelterDirectoryClientTest {
    private final ShelterDirectoryClient client = spy(new ShelterDirectoryClient("test-key",new ObjectMapper()));
    private final Map<String,String> row = Map.of("careRegNo","123456789012345","careNm","보호소");
    private byte[] json(String items) {
        return ("{\"response\":{\"header\":{\"resultCode\":\"00\"},\"body\":{\"totalCount\":856,\"items\":"+items+"}}}").getBytes(StandardCharsets.UTF_8);
    }
    @Test void givenInflatedTotal_whenEmptyPageReached_thenReturnsUniqueInventory() {
        doReturn(List.of(row)).when(client).fetch(1);
        doReturn(List.of()).when(client).fetch(2);
        assertThat(client.collect()).containsExactly(row);
        verify(client,times(1)).fetch(2);
    }
    @Test void givenRepeatedPage_whenCollecting_thenFailsInsteadOfLooping() {
        doReturn(List.of(row)).when(client).fetch(anyInt());
        assertThatThrownBy(client::collect).hasMessage("SHELTER_DIRECTORY_UNAVAILABLE");
        verify(client,times(2)).fetch(anyInt());
    }
    @Test void givenConflictingRegistration_whenCollecting_thenRejectsAmbiguousSnapshot() {
        doReturn(List.of(row, Map.of("careRegNo","123456789012345","careNm","다른 보호소"))).when(client).fetch(1);
        assertThatThrownBy(client::collect).hasMessage("SHELTER_DIRECTORY_UNAVAILABLE");
    }
    @Test void givenSingleItem_whenParsing_thenAcceptsObjectAndArray() throws Exception {
        String item=new ObjectMapper().writeValueAsString(row);
        assertThat(client.parse(json("{\"item\":"+item+"}"))).containsExactly(row);
        assertThat(client.parse(json("{\"item\":["+item+"]}"))).containsExactly(row);
        assertThat(client.parse(json("\"\""))).isEmpty();
    }
    @Test void givenMalformedOrAuthFailure_whenParsing_thenNeverReturnsEmptySuccess() {
        for(String raw:List.of("{}","{\"header\":{\"resultCode\":\"30\"}}","<error>test-key</error>"))
            assertThatThrownBy(()->client.parse(raw.getBytes(StandardCharsets.UTF_8)))
                .hasMessage("SHELTER_DIRECTORY_UNAVAILABLE").hasNoCause();
        assertThatThrownBy(()->client.parse(json("{}"))).hasMessage("SHELTER_DIRECTORY_UNAVAILABLE");
    }
    @Test void givenInvalidCoordinatePair_whenNormalizing_thenDoesNotPublishOrEraseCoordinates() {
        assertThat(ShelterPublicInformation.details(Map.of("lat","0","lng","127"))).doesNotContainKeys("latitude","longitude");
        assertThat(ShelterPublicInformation.details(Map.of("lat","37.5","lng","127","dataStdDt","2026-09-13")))
            .containsEntry("latitude",37.5).containsEntry("longitude",127.0).containsEntry("sourceUpdatedDate","2026-09-13");
    }
}
