package com.pawbridge.communityservice.curation;

import static org.assertj.core.api.Assertions.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import java.net.*;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.util.List;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.web.server.ResponseStatusException;

class YouTubeVideoClientTest {
    HttpServer server;
    YouTubeVideoClient client;
    String payload;
    int status = 200;
    String query;
    static final String ID = "AbCdEfGhI_1";
    @BeforeEach void setup() throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
        server.createContext("/videos", exchange -> {
            query = exchange.getRequestURI().getRawQuery();
            byte[] body = payload.getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(status,body.length); exchange.getResponseBody().write(body); exchange.close();
        });
        server.start();
        client = new YouTubeVideoClient(new ObjectMapper(), "synthetic-key",
                HttpClient.newHttpClient(), URI.create("http://127.0.0.1:"+server.getAddress().getPort()+"/videos"));
        payload = json("\"privacyStatus\":\"public\",\"uploadStatus\":\"processed\",\"embeddable\":true", "");
    }
    @AfterEach void stop() { server.stop(0); }
    static String json(String status, String detail) {
        return """
                {"items":[{"id":"AbCdEfGhI_1","snippet":{"title":"일상","channelTitle":"테스트 채널",
                "liveBroadcastContent":"none","thumbnails":{"high":{"url":"https://i.ytimg.com/vi/AbCdEfGhI_1/hqdefault.jpg"}}},
                "contentDetails":{"duration":"PT3M24S"%s},"status":{%s}}]}
                """.formatted(detail,status);
    }
    @Test void givenPublicEmbeddableVideo_whenLookup_thenMetadataAndSingleIdsRequest() {
        var result = client.lookup(List.of(ID, "OtherVideo1"));
        assertThat(result.get(ID).available()).isTrue();
        assertThat(result.get(ID).durationSeconds()).isEqualTo(204);
        assertThat(query).contains("part=snippet,contentDetails,status","id=AbCdEfGhI_1,OtherVideo1");
    }
    @ParameterizedTest
    @ValueSource(strings={"private","unlisted"})
    void givenNonPublicVideo_whenLookup_thenNotPlayable(String privacy) {
        payload=json("\"privacyStatus\":\""+privacy+"\",\"uploadStatus\":\"processed\",\"embeddable\":true","");
        assertThat(client.lookup(List.of(ID)).get(ID).available()).isFalse();
    }
    @ParameterizedTest
    @ValueSource(strings={",\"regionRestriction\":{\"blocked\":[\"KR\"]}",
            ",\"regionRestriction\":{\"allowed\":[\"US\"]}",",\"contentRating\":{\"ytRating\":\"ytAgeRestricted\"}"})
    void givenRegionalOrAgeRestriction_whenLookup_thenNotPlayable(String detail) {
        payload=json("\"privacyStatus\":\"public\",\"uploadStatus\":\"processed\",\"embeddable\":true",detail);
        assertThat(client.lookup(List.of(ID)).get(ID).available()).isFalse();
    }
    @Test void givenEmbeddingDisabledOrLive_whenLookup_thenNotPlayable() {
        payload=json("\"privacyStatus\":\"public\",\"uploadStatus\":\"processed\",\"embeddable\":false","");
        assertThat(client.lookup(List.of(ID)).get(ID).available()).isFalse();
        payload=json("\"privacyStatus\":\"public\",\"uploadStatus\":\"processed\",\"embeddable\":true","")
                .replace("\"none\"","\"live\"");
        assertThat(client.lookup(List.of(ID)).get(ID).available()).isFalse();
    }
    @Test void givenSuccessfulEmptyList_whenLookup_thenAbsenceNotProviderFailure() {
        payload="{\"items\":[]}";
        assertThat(client.lookup(List.of(ID))).isEmpty();
    }
    @ParameterizedTest @ValueSource(ints={403,429,500})
    void givenProviderFailure_whenLookup_thenSanitized503WithoutKeyOrCause(int code) {
        status=code;payload="synthetic-key should never escape";
        assertThatThrownBy(() -> client.lookup(List.of(ID))).isInstanceOf(ResponseStatusException.class)
                .hasMessageNotContaining("synthetic-key").hasMessageNotContaining("http://").hasMessageNotContaining("key=")
                .hasNoCause()
                .satisfies(e -> assertThat(((ResponseStatusException)e).getStatusCode().value()).isEqualTo(503));
    }
    @Test void givenMalformedProviderResponse_whenLookup_thenFailureNotAbsentVideo() {
        payload="{\"error\":\"synthetic-key\"}";
        assertThatThrownBy(() -> client.lookup(List.of(ID))).isInstanceOf(ResponseStatusException.class).hasNoCause();
    }
    @Test void givenOversizedProviderBody_whenLookup_thenBoundedSanitizedFailure() {
        payload = " ".repeat(524_289);
        assertThatThrownBy(() -> client.lookup(List.of(ID)))
                .isInstanceOf(ResponseStatusException.class).hasNoCause()
                .hasMessageNotContaining("synthetic-key");
    }
    @Test void givenForeignThumbnailHost_whenLookup_thenNotPlayable() {
        payload = payload.replace("https://i.ytimg.com/", "https://untrusted.example/");
        assertThat(client.lookup(List.of(ID)).get(ID).available()).isFalse();
    }
}
