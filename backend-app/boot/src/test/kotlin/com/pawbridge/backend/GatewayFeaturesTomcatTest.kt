package com.pawbridge.backend

import com.pawbridge.backend.boot.configuration.GatewayFeaturesConfiguration
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.boot.autoconfigure.ImportAutoConfiguration
import org.springframework.boot.autoconfigure.context.PropertyPlaceholderAutoConfiguration
import org.springframework.boot.autoconfigure.http.HttpMessageConvertersAutoConfiguration
import org.springframework.boot.autoconfigure.web.servlet.DispatcherServletAutoConfiguration
import org.springframework.boot.autoconfigure.web.servlet.MultipartAutoConfiguration
import org.springframework.boot.autoconfigure.web.servlet.ServletWebServerFactoryAutoConfiguration
import org.springframework.boot.autoconfigure.web.servlet.WebMvcAutoConfiguration
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.web.server.LocalServerPort
import org.springframework.context.annotation.Configuration
import org.springframework.context.annotation.Import
import org.springframework.http.MediaType
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RestController
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration

/**
 * Real embedded Tomcat: MockMvc cannot reproduce container path decoding, which is how a percent-encoded
 * spelling of the path could once skip the limits while still reaching the controller.
 */
@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    classes = [GatewayFeaturesTomcatTest.App::class],
    properties = ["pawbridge.animal-lost-search.enabled=true", "lost-search.max-request-bytes=64", "server.address=127.0.0.1"],
)
class GatewayFeaturesTomcatTest {
    @Configuration(proxyBeanMethods = false)
    @ImportAutoConfiguration(
        PropertyPlaceholderAutoConfiguration::class,
        ServletWebServerFactoryAutoConfiguration::class,
        DispatcherServletAutoConfiguration::class,
        WebMvcAutoConfiguration::class,
        HttpMessageConvertersAutoConfiguration::class,
        MultipartAutoConfiguration::class,
    )
    @Import(GatewayFeaturesConfiguration::class, Probe::class)
    class App

    @RestController
    class Probe {
        @PostMapping(value = ["/api/v1/animals/lost-candidates"], consumes = [MediaType.MULTIPART_FORM_DATA_VALUE])
        fun search(): String = "reached-controller"
    }

    @LocalServerPort
    var port: Int = 0

    private fun post(path: String, bytes: Int): HttpResponse<String> {
        val body = "--x\r\nContent-Disposition: form-data; name=\"f\"\r\n\r\n" + "a".repeat(bytes) + "\r\n--x--\r\n"
        val request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:$port$path"))
            .header("Content-Type", "multipart/form-data; boundary=x")
            .timeout(Duration.ofSeconds(15))
            .POST(HttpRequest.BodyPublishers.ofString(body))
            .build()
        return HttpClient.newHttpClient().send(request, HttpResponse.BodyHandlers.ofString())
    }

    @Test
    fun given__small_upload__when__posting__then__reaches_the_controller() {
        assertThat(post("/api/v1/animals/lost-candidates", 1).body()).isEqualTo("reached-controller")
    }

    @Test
    fun given__oversized_upload__when__posting_any_spelling_of_the_path__then__413_before_the_controller() {
        val paths = arrayOf(
            "/api/v1/animals/lost-candidates",
            "/api/v1/animals/lost-%63andidates",
            "/api/v1/animals/%6cost-%63andidates",
            "/api/v1/animals/lost-candidates;jsessionid=x",
        )
        for (path in paths) {
            val response = post(path, 200)
            assertThat(response.statusCode()).`as`(path).isEqualTo(413)
            assertThat(response.body()).`as`(path).doesNotContain("reached-controller")
        }
    }
}
