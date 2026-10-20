package com.pawbridge.backend.animal.internal.lostsearch.infrastructure;

import com.fasterxml.jackson.databind.ObjectMapper;
import feign.Client;
import feign.Feign;
import feign.Headers;
import feign.Logger;
import feign.Param;
import feign.Request;
import feign.RequestLine;
import feign.Retryer;
import feign.form.FormData;
import feign.form.FormEncoder;
import java.util.Objects;
import java.util.concurrent.TimeUnit;

/** Selected original multipart POST using the module's existing native Feign composition. */
public final class PythonLostSearchClient {
    private final Endpoint endpoint;

    public PythonLostSearchClient(String baseUrl, ObjectMapper mapper) {
        this(baseUrl, mapper, new Client.Default(null, null));
    }

    PythonLostSearchClient(String baseUrl, ObjectMapper mapper, Client client) {
        Objects.requireNonNull(mapper);
        this.endpoint = Feign.builder().client(client)
                .options(new Request.Options(3, TimeUnit.SECONDS, 45, TimeUnit.SECONDS, false))
                .retryer(Retryer.NEVER_RETRY).logLevel(Logger.Level.NONE)
                .encoder(new FormEncoder())
                .decoder((response, type) -> response.body() == null ? null
                        : mapper.readValue(response.body().asInputStream(), PythonLostSearchResponse.class))
                .target(Endpoint.class, baseUrl);
    }

    public PythonLostSearchResponse search(String key, FormData image, String species, String lostDate,
            String region, String description, boolean includeAdoptedOrReturned) {
        return endpoint.search(key, image, species, lostDate, region, description, includeAdoptedOrReturned);
    }

    interface Endpoint {
        @RequestLine("POST /internal/animals/lost-candidates")
        @Headers({"X-Internal-Api-Key: {key}", "Content-Type: multipart/form-data"})
        PythonLostSearchResponse search(@Param("key") String key, @Param("image") FormData image,
                @Param("species") String species, @Param("lostDate") String lostDate,
                @Param("region") String region, @Param("description") String description,
                @Param("includeAdoptedOrReturned") boolean includeAdoptedOrReturned);
    }
}
