package com.pawbridge.animalservice.client;

import com.pawbridge.animalservice.lostsearch.PythonLostSearchResponse;
import feign.Logger;
import feign.Request;
import feign.Retryer;
import feign.form.FormData;
import java.util.concurrent.TimeUnit;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.context.annotation.Bean;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestPart;

@FeignClient(name = "python-lost-search", url = "${lost-search.python-url:${python-ai-service.url}}",
        configuration = PythonLostSearchClient.Configuration.class)
public interface PythonLostSearchClient {
    @PostMapping(value = "/internal/animals/lost-candidates", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    PythonLostSearchResponse search(
            @RequestHeader("X-Internal-Api-Key") String key,
            @RequestPart("image") FormData image,
            @RequestPart("species") String species,
            @RequestPart(value = "lostDate", required = false) String lostDate,
            @RequestPart(value = "region", required = false) String region,
            @RequestPart(value = "description", required = false) String description);

    // Only the Feign child context loads this class; it must not be component-scanned.
    class Configuration {
        @Bean
        public Logger.Level lostSearchLoggerLevel() { return Logger.Level.NONE; }
        @Bean
        public Retryer lostSearchRetryer() { return Retryer.NEVER_RETRY; }
        @Bean
        public Request.Options lostSearchOptions() {
            return new Request.Options(3, TimeUnit.SECONDS, 45, TimeUnit.SECONDS, false);
        }
    }
}
