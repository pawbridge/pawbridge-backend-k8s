package com.pawbridge.animalservice.lostsearch;

import com.pawbridge.animalservice.exception.GlobalExceptionHandler;
import com.pawbridge.animalservice.client.PythonLostSearchClient;
import com.pawbridge.animalservice.repository.AnimalRepository;
import com.pawbridge.animalservice.mapper.AnimalMapper;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.ImportAutoConfiguration;
import org.springframework.boot.autoconfigure.http.HttpMessageConvertersAutoConfiguration;
import org.springframework.boot.autoconfigure.validation.ValidationAutoConfiguration;
import org.springframework.boot.autoconfigure.web.servlet.DispatcherServletAutoConfiguration;
import org.springframework.boot.autoconfigure.web.embedded.EmbeddedWebServerFactoryCustomizerAutoConfiguration;
import org.springframework.boot.autoconfigure.web.servlet.MultipartAutoConfiguration;
import org.springframework.boot.autoconfigure.web.servlet.ServletWebServerFactoryAutoConfiguration;
import org.springframework.boot.autoconfigure.web.servlet.WebMvcAutoConfiguration;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.any;

// Real servlet parsing with the production application.yml limits; no external services.
@SpringBootTest(classes = LostSearchUploadTest.WebConfig.class, webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = "spring.profiles.active=test")
class LostSearchUploadTest {
    @Configuration(proxyBeanMethods = false)
    @ImportAutoConfiguration({ServletWebServerFactoryAutoConfiguration.class, EmbeddedWebServerFactoryCustomizerAutoConfiguration.class, DispatcherServletAutoConfiguration.class,
            WebMvcAutoConfiguration.class, MultipartAutoConfiguration.class, HttpMessageConvertersAutoConfiguration.class,
            ValidationAutoConfiguration.class})
    @Import({LostSearchController.class, GlobalExceptionHandler.class})
    static class WebConfig {
        @Bean PythonLostSearchClient client() { return mock(PythonLostSearchClient.class); }
        @Bean LostSearchService service(PythonLostSearchClient client) {
            return new LostSearchService(client, mock(AnimalRepository.class), new AnimalMapper(), "test-key");
        }
    }

    @LocalServerPort int port;
    @Autowired PythonLostSearchClient client;

    @BeforeEach
    void setUp() { reset(client); }

    @Test
    void givenPhotoAboveBootDefaultButWithinFiveMiB__whenUploading__thenReachSearch() throws Exception {
        when(client.search(any(), any(), any(), any(), any(), any())).thenReturn(new PythonLostSearchResponse(List.of()));
        assertThat(upload(2 * 1024 * 1024).statusCode()).isEqualTo(200);
        verify(client).search(any(), any(), any(), any(), any(), any());
    }

    @Test
    void givenPhotoAboveFiveMiB__whenUploading__thenRejectBeforePythonCall() throws Exception {
        assertThat(upload(5 * 1024 * 1024 + 1).statusCode()).isEqualTo(413);
        verifyNoInteractions(client);
    }

    @Test
    void givenBodyAboveServletLimit__whenUploading__thenReturn413BeforePythonCall() throws Exception {
        assertThat(upload(10 * 1024 * 1024 + 1).statusCode()).isEqualTo(413);
        verifyNoInteractions(client);
    }

    private HttpResponse<String> upload(int size) throws Exception {
        String boundary = "lost-search-test-boundary";
        byte[] prefix = ("--" + boundary + "\r\nContent-Disposition: form-data; name=\"species\"\r\n\r\nDOG\r\n--" + boundary
                + "\r\nContent-Disposition: form-data; name=\"image\"; filename=\"photo.png\"\r\nContent-Type: image/png\r\n\r\n").getBytes(StandardCharsets.UTF_8);
        byte[] suffix = ("\r\n--" + boundary + "--\r\n").getBytes(StandardCharsets.UTF_8);
        byte[] body = new byte[prefix.length + size + suffix.length];
        System.arraycopy(prefix, 0, body, 0, prefix.length);
        System.arraycopy(suffix, 0, body, prefix.length + size, suffix.length);
        return HttpClient.newHttpClient().send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/api/v1/animals/lost-candidates"))
                .timeout(Duration.ofSeconds(15)).header("Content-Type", "multipart/form-data; boundary=" + boundary)
                .POST(HttpRequest.BodyPublishers.ofByteArray(body)).build(), HttpResponse.BodyHandlers.ofString());
    }
}
