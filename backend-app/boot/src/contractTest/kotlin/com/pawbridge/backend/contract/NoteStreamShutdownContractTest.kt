package com.pawbridge.backend.contract

import com.pawbridge.backend.PawBridgeApplication
import com.zaxxer.hikari.HikariDataSource
import java.net.URI
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.boot.builder.SpringApplicationBuilder
import org.springframework.boot.web.context.WebServerApplicationContext
import org.springframework.context.ConfigurableApplicationContext

/**
 * What a normal application shutdown does to streams that are still open.
 *
 * The application is closed inside the test, so it cannot be the context the other contracts share. This class
 * starts its own application, with the same configuration as [ContractTestBase] and its own pool, and closes it.
 */
class NoteStreamShutdownContractTest : NoteContractBase() {
    override val ids = 9_985_000L..9_985_999L

    @BeforeEach
    fun seedMembers() {
        seedUser(CALLER, "F26caller$CALLER")
        seedUser(OTHER, "F26other$OTHER")
        seedMember(CALLER)
        seedMember(OTHER)
    }

    private fun startOwnApplication(): ConfigurableApplicationContext {
        return SpringApplicationBuilder(PawBridgeApplication::class.java)
            .initializers(ContractApplicationInitializer())
            .properties(
                "server.port=0",
                "management.server.port=0",
                "pawbridge.auth.sessions-enabled=true",
                "jwt.access-token-expiration=120999",
                "jwt.refresh-token-expiration=600999",
                "pawbridge.cors.allowed-origin-patterns=https://contract.example.test",
            )
            .run()
    }

    @Test
    fun `열린 스트림이 둘 있을 때__서버를 정상 종료하면__두 스트림을 정상 종료하고 자원 정리와 풀 종료를 남긴다`() {
        // given
        val application = startOwnApplication()
        try {
            val ownPort = (application as WebServerApplicationContext).webServer.port
            val pool = application.getBean("writeDataSource", HikariDataSource::class.java)
            val streamUri = URI.create("http://127.0.0.1:$ownPort/api/v1/notes/stream")
            val first = NoteEventStream.open(streamUri, token(CALLER)).also { track(it) }
            val second = NoteEventStream.open(streamUri, token(OTHER)).also { track(it) }
            first.await("ready", 1)
            second.await("ready", 1)
            assertThat(pool.isClosed).isFalse()

            // when
            application.close()

            // then
            first.awaitEnd()
            second.awaitEnd()
            assertThat(first.count("resync")).isZero()
            assertThat(second.count("resync")).isZero()
            val log = capturedLog()
            assertThat(log.split("Private note stream resources stopped")).hasSize(2)
            assertThat(log).doesNotContain("Private note stream resources did not stop within budget")
            assertThat(pool.isClosed).isTrue()
        } finally {
            application.close()
        }
    }

    private companion object {
        const val CALLER = 9_985_001L
        const val OTHER = 9_985_002L
    }
}
