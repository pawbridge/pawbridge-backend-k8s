package com.pawbridge.backend

import com.pawbridge.backend.boot.configuration.PostWriteSettings
import com.pawbridge.backend.boot.configuration.ReadDatabaseSettings
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.CsvSource
import org.springframework.boot.test.autoconfigure.actuate.observability.AutoConfigureObservability
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.web.server.LocalManagementPort
import org.springframework.boot.test.web.server.LocalServerPort
import org.springframework.context.ApplicationContextInitializer
import org.springframework.context.ConfigurableApplicationContext
import org.springframework.test.context.ContextConfiguration
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration

/**
 * The real application on two real Tomcat listeners: the main port and the separate management port.
 * Spring Boot test slices switch metric export off by default, hence [AutoConfigureObservability].
 */
@AutoConfigureObservability
@SpringBootTest(
    classes = [PawBridgeApplication::class],
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = [
        "management.server.port=0",
        "pawbridge.auth.sessions-enabled=true",
        "pawbridge.auth.local-enabled=true",
        "jwt.access-token-expiration=3600000",
        "jwt.refresh-token-expiration=86400000",
    ],
)
@ContextConfiguration(classes = [PawBridgeApplication::class], initializers = [PrometheusManagementPortHttpTest.Inputs::class])
class PrometheusManagementPortHttpTest {
    class Inputs : ApplicationContextInitializer<ConfigurableApplicationContext> {
        override fun initialize(context: ConfigurableApplicationContext) {
            val values = F3TestInputs.values()
            val factory = context.beanFactory
            factory.registerSingleton(
                "readDatabaseSettings",
                ReadDatabaseSettings.fromEnvironment { name -> values[name] },
            )
            factory.registerSingleton(
                "postWriteSettings",
                PostWriteSettings.fromEnvironment { name -> values[name] },
            )
        }
    }

    @LocalServerPort
    var mainPort: Int = 0

    @LocalManagementPort
    var managementPort: Int = 0

    private fun scrape(): String {
        val response = send(managementPort, "GET", "/actuator/prometheus")
        assertThat(response.statusCode()).isEqualTo(200)
        return response.body()
    }

    @Test
    fun given__management_port__when__scraping_prometheus__then__200_with_jvm_and_pool_metrics() {
        val response = send(managementPort, "GET", "/actuator/prometheus")

        assertThat(response.statusCode()).isEqualTo(200)
        assertThat(response.headers().firstValue("Content-Type").orElse("")).startsWith("text/plain")
        assertThat(response.body()).contains("jvm_memory_used_bytes")
        assertThat(response.body()).contains("hikaricp_connections{")
        assertThat(response.body()).contains("pool=\"pawbridge-db\"")
    }

    @Test
    fun given__management_port__when__scraping__then__process_tomcat_and_logback_meters_exist() {
        val body = scrape()

        assertThat(body).contains("process_cpu_usage")
        assertThat(body).contains("jvm_threads_live_threads")
        assertThat(body).contains("tomcat_threads_busy_threads")
        assertThat(body).contains("logback_events_total")
    }

    @Test
    fun given__request_with_a_path_variable__when__scraping__then__the_uri_tag_is_the_template_not_the_value() {
        val main = send(mainPort, "GET", "/api/v1/posts/read/not-a-number-987654321")
        // The non-numeric id fails argument conversion inside MVC, without a database call; it is not denied by security.
        assertThat(main.statusCode()).isNotEqualTo(403)

        val body = scrape()

        assertThat(body).contains("http_server_requests_seconds_count")
        assertThat(body).contains("uri=\"/api/v1/posts/read/{postId}\"")
        assertThat(body).doesNotContain("987654321")
    }

    @Test
    fun given__unmatched_paths__when__scraping__then__they_do_not_create_one_series_per_path() {
        send(mainPort, "GET", "/no-such-path-123456789")
        send(managementPort, "GET", "/no-such-path-123456789")

        assertThat(scrape()).doesNotContain("123456789")
    }

    @ParameterizedTest
    @CsvSource("/actuator/prometheus", "/actuator/metrics", "/actuator/info", "/actuator/env", "/actuator")
    fun given__main_port__when__requesting_actuator_paths__then__prometheus_and_all_other_endpoints_are_denied(
        path: String,
    ) {
        val response = send(mainPort, "GET", path)

        assertThat(response.statusCode()).isEqualTo(403)
        assertThat(response.body()).doesNotContain("jvm_memory_used_bytes")
    }

    @Test
    fun given__main_port__when__requesting_health__then__the_actuator_is_not_served_there() {
        // The main chain still permits health (it matters if management.server.port is set equal to server.port),
        // but with a separate management port nothing is mapped on the main port.
        val response = send(mainPort, "GET", "/actuator/health")

        assertThat(response.statusCode()).isEqualTo(404)
    }

    @ParameterizedTest
    @CsvSource("GET,/actuator/health/liveness", "GET,/actuator/health/readiness")
    fun given__management_port__when__requesting_probes__then__they_are_public_and_up(method: String, path: String) {
        val response = send(managementPort, method, path)

        assertThat(response.statusCode()).isEqualTo(200)
        assertThat(response.body()).contains("\"status\":\"UP\"")
    }

    @Test
    fun given__management_port__when__requesting_overall_health__then__it_is_public_without_details() {
        // The test database is unreachable by design, so the overall status may be DOWN (503); it must not be 403/404.
        val response = send(managementPort, "GET", "/actuator/health")

        assertThat(response.statusCode()).isIn(200, 503)
        assertThat(response.body()).contains("\"status\"")
        assertThat(response.body()).doesNotContain("components")
    }

    @Test
    fun given__local_login_enabled__when__posting_credentials__then__only_the_main_port_reaches_the_login_chain() {
        val main = send(mainPort, "POST", "/api/v1/auth/login")
        val management = send(managementPort, "POST", "/api/v1/auth/login")

        // The login chain permits everyone and runs its own filter, so on the main port it answers with something
        // other than the security denial; on the management port the dedicated chain must win.
        assertThat(main.statusCode()).isNotEqualTo(403)
        assertThat(management.statusCode()).isEqualTo(403)
    }

    @ParameterizedTest
    @CsvSource(
        "GET,/actuator/info", "GET,/actuator/env", "GET,/actuator/metrics", "GET,/actuator/beans",
        "GET,/actuator/heapdump", "GET,/actuator", "GET,/actuator/prometheus/", "GET,/actuator/health-x",
        "POST,/actuator/prometheus", "POST,/actuator/health", "DELETE,/actuator/prometheus",
        "GET,/api/v1/animals", "GET,/api/v1/posts/read/1", "POST,/api/v1/auth/login",
        "GET,/oauth2/authorization/google", "GET,/login/oauth2/code/google", "GET,/ws/chat", "GET,/",
        "GET,/error", "GET,/no-such-path",
    )
    fun given__management_port__when__requesting_anything_else__then__denied_even_where_the_main_chains_permit_it(
        method: String,
        path: String,
    ) {
        val response = send(managementPort, method, path)

        assertThat(response.statusCode()).`as`(method + " " + path).isEqualTo(403)
    }

    companion object {
        private fun send(port: Int, method: String, path: String): HttpResponse<String> {
            val request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:$port$path"))
                .method(method, HttpRequest.BodyPublishers.noBody())
                .timeout(Duration.ofSeconds(15))
                .build()
            return HttpClient.newHttpClient().send(request, HttpResponse.BodyHandlers.ofString())
        }
    }
}
