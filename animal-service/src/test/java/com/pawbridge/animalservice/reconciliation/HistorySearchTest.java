package com.pawbridge.animalservice.reconciliation;

import org.junit.jupiter.api.Test;
import java.util.*;
import java.util.concurrent.atomic.AtomicReference;
import static org.assertj.core.api.Assertions.*;

class HistorySearchTest {
    @Test void concurrentSearchWriteFailsWithVersionGuardAndOnlyApprovedFieldsInRequest() throws Exception {
        var requestBody = new AtomicReference<com.fasterxml.jackson.databind.JsonNode>();
        var requestQuery = new AtomicReference<String>();
        var server = com.sun.net.httpserver.HttpServer.create(new java.net.InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            String path = exchange.getRequestURI().getPath();
            int status = 200;
            Object response = switch (path) {
                case "/" -> Map.of("cluster_uuid", "cluster");
                case "/_alias/animals" -> Map.of("physical", Map.of("aliases", Map.of("animals", Map.of())));
                case "/physical/_settings" -> Map.of("physical", Map.of("settings", Map.of("index", Map.of("uuid", "uuid"))));
                case "/physical/_doc/1" -> Map.of("_seq_no", 12, "_primary_term", 2, "_source", Map.of("id", 1, "apms_desertion_no", "one"));
                default -> Map.of();
            };
            if (path.equals("/physical/_update/1")) {
                requestBody.set(HistoryPlan.JSON.readTree(exchange.getRequestBody()));
                requestQuery.set(exchange.getRequestURI().getQuery()); status = 409;
            }
            byte[] bytes = HistoryPlan.JSON.writeValueAsBytes(response);
            exchange.sendResponseHeaders(status, bytes.length); exchange.getResponseBody().write(bytes); exchange.close();
        });
        server.start();
        try {
            var search = new HistorySearch("http://127.0.0.1:" + server.getAddress().getPort(), null);
            assertThatThrownBy(() -> search.sync("cluster/physical/uuid", 1,
                    Map.of("id", 1, "apms_desertion_no", "one", "status", "ADOPTED", "description", "must not send", "favorite_count", 3)))
                    .hasMessage("Remote HTTP status 409");
            assertThat(requestQuery.get()).isEqualTo("if_seq_no=12&if_primary_term=2");
            assertThat(requestBody.get().path("doc").size()).isEqualTo(4);
            assertThat(requestBody.get().path("doc").has("description")).isFalse();
            assertThat(requestBody.get().path("doc").has("favorite_count")).isFalse();
            assertThat(requestBody.get().path("doc").has("image_vector")).isFalse();
        } finally { server.stop(0); }
    }
}
