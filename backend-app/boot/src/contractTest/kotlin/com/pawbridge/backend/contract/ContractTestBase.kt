package com.pawbridge.backend.contract

import com.pawbridge.backend.PawBridgeApplication
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration
import java.util.concurrent.TimeUnit
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Timeout
import org.junit.jupiter.api.parallel.Execution
import org.junit.jupiter.api.parallel.ExecutionMode
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.web.server.LocalServerPort
import org.springframework.test.context.ContextConfiguration

/**
 * Base of every database contract test.
 *
 * All subclasses share one Spring context: the complete application on a real Tomcat port, connected to the
 * Testcontainers PostgreSQL as the application role. A subclass must not add its own `@SpringBootTest`
 * properties, `@MockkBean` or `@DirtiesContext`, because any of them gives it a second context and a second
 * pool. A contract that needs a different configuration gets its own base class on purpose.
 *
 * Contracts change privileges database-wide, so they never run in parallel with each other.
 */
@Tag("contract")
@Execution(ExecutionMode.SAME_THREAD)
@Timeout(value = 120, unit = TimeUnit.SECONDS)
@SpringBootTest(
    classes = [PawBridgeApplication::class],
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = [
        "management.server.port=0",
        "pawbridge.auth.sessions-enabled=true",
        // 120 999 ms and 600 999 ms: the odd milliseconds make a wrongly rounded lifetime visible.
        "jwt.access-token-expiration=120999",
        "jwt.refresh-token-expiration=600999",
        "pawbridge.cors.allowed-origin-patterns=https://contract.example.test",
        // The read-only daily visit totals; the disabled route has its own context in VisitTotalsDisabledContractTest.
        "pawbridge.visits.enabled=true",
    ],
)
@ContextConfiguration(classes = [PawBridgeApplication::class], initializers = [ContractApplicationInitializer::class])
abstract class ContractTestBase {
    @LocalServerPort
    protected var port: Int = 0

    protected fun send(request: HttpRequest.Builder): HttpResponse<String> {
        return HTTP.send(request.timeout(Duration.ofSeconds(15)).build(), HttpResponse.BodyHandlers.ofString())
    }

    protected fun uri(path: String): URI = URI.create("http://127.0.0.1:$port$path")

    private companion object {
        val HTTP: HttpClient = HttpClient.newHttpClient()
    }
}
