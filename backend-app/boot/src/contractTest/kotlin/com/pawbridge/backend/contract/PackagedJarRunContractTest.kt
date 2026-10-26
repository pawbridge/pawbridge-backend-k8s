package com.pawbridge.backend.contract

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import io.jsonwebtoken.Jwts
import io.jsonwebtoken.security.Keys
import java.net.URI
import java.net.URLEncoder
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.file.Files
import java.nio.file.Path
import java.security.SecureRandom
import java.time.Duration
import java.time.Instant
import java.util.Date
import java.util.HexFormat
import java.util.concurrent.TimeUnit
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.MethodOrderer
import org.junit.jupiter.api.Order
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.junit.jupiter.api.TestMethodOrder
import org.junit.jupiter.api.Timeout

/**
 * The real `bootJar` output as a running process (replaces what the packaged-JAR smokes asserted about the process).
 *
 * One JAR is started for the whole class on loopback ports; the tests share it. They only look at the process from
 * outside: management port, public port, the contract PostgreSQL and the fake object storage. The shutdown test
 * ends the process and therefore runs last. No `@SpringBootTest` context is involved.
 */
@Tag("packaged-jar")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@TestMethodOrder(MethodOrderer.OrderAnnotation::class)
@Timeout(value = 180, unit = TimeUnit.SECONDS)
class PackagedJarRunContractTest {
    private val jwtSecret = randomHex()
    private val serverPort = PackagedJar.freePort()
    private val managementPort = PackagedJar.freePort()
    private val http: HttpClient = HttpClient.newHttpClient()
    private lateinit var directory: Path
    private lateinit var app: PackagedJar.Launched

    @BeforeAll
    fun startJar() {
        removeRows()
        ContractFakeS3.reset()
        directory = Files.createTempDirectory("packaged-jar")
        seedAuthor()
        app = PackagedJar.launch(PackagedJar.environment(serverPort, managementPort, jwtSecret), directory, "run")
        awaitHealthy()
    }

    @AfterAll
    fun stopJarAndRemoveRows() {
        PackagedJar.kill(app)
        removeRows()
        directory.toFile().deleteRecursively()
    }

    @Test
    @Order(1)
    fun `기동한 JAR일 때__관리 포트를 조회하면__건강 200이고 공개 포트는 지표 경로를 403으로 숨긴다`() {
        // given
        // when
        val health = get(managementPort, "/actuator/health")
        val liveness = get(managementPort, "/actuator/health/liveness")
        val readiness = get(managementPort, "/actuator/health/readiness")
        val metrics = get(managementPort, "/actuator/prometheus")
        val publicMetrics = get(serverPort, "/actuator/prometheus")
        val publicHealth = get(serverPort, "/actuator/health")

        // then
        assertThat(health.statusCode()).isEqualTo(200)
        assertThat(MAPPER.readTree(health.body()).get("status").asText()).isEqualTo("UP")
        assertThat(liveness.statusCode()).isEqualTo(200)
        assertThat(readiness.statusCode()).isEqualTo(200)
        assertThat(metrics.statusCode()).isEqualTo(200)
        assertThat(metrics.body()).contains("hikaricp_connections", "jvm_memory_used_bytes")
        assertThat(publicMetrics.statusCode()).isEqualTo(403)
        assertThat(publicMetrics.body()).doesNotContain("jvm_memory")
        assertThat(publicHealth.statusCode()).`as`("Actuator is not served on the public port").isEqualTo(404)
    }

    @Test
    @Order(2)
    fun `저장된 글이 있을 때__공개 포트로 단건 조회하면__200 봉투와 저장된 값을 준다`() {
        // given
        val post = seedPost()

        // when
        val reply = try {
            get(serverPort, "/api/v1/posts/read/$post", headers = mapOf("Authorization" to "Bearer invalid"))
        } finally {
            // A stored post without a search projection makes every search fail closed, so it must not outlive the read.
            ContractPostgres.admin.update("DELETE FROM pawbridge_community.posts WHERE post_id = ?", post)
        }

        // then
        assertThat(reply.statusCode()).isEqualTo(200)
        val body = MAPPER.readTree(reply.body())
        assertThat(body.get("code").asInt()).isEqualTo(200)
        assertThat(body.get("message").asText()).isEqualTo("요청이 성공적으로 처리되었습니다.")
        assertThat(body.get("data").get("authorNickname").asText()).isEqualTo(NICKNAME)
        assertThat(body.get("data").get("title").asText()).isEqualTo("패키지 합성 제목")
        assertThat(body.get("data").get("imageUrls").map { it.asText() }).containsExactly("https://fixture.invalid/가.jpg")
    }

    @Test
    @Order(3)
    fun `유효한 토큰일 때__글을 작성하면__저장되고 파일은 저장소로 가며 검색 투영이 생긴다`() {
        // given
        val image = "packaged-jar-image".toByteArray()

        // when
        val reply = create("패키지 작성 글", "본문", listOf(Upload("dog.jpg", "image/jpeg", image)))

        // then
        assertThat(reply.statusCode()).isEqualTo(200)
        val data = MAPPER.readTree(reply.body()).get("data")
        val postId = data.get("postId").asLong()
        assertThat(data.get("authorId").asLong()).isEqualTo(AUTHOR)
        assertThat(data.get("authorNickname").asText()).isEqualTo(NICKNAME)
        assertThat(
            ContractPostgres.admin.queryForObject(
                "SELECT count(*) FROM pawbridge_community.post_search_documents d JOIN pawbridge_community.posts p USING(post_id) " +
                    "WHERE d.post_id = ? AND d.title_hash = md5(p.title) AND d.analyzer_version = 'nori-9.12.3-v1'",
                Long::class.javaObjectType,
                postId,
            ),
        ).isOne()
        val puts = ContractFakeS3.requests().filter { it.method == "PUT" && it.status == 200 }
        assertThat(puts).hasSize(1)
        assertThat(ContractFakeS3.objects().values.single().sha256).isEqualTo(ContractFakeS3.sha256(image))
        assertThat(data.get("imageUrls").single().asText()).isEqualTo(ContractFakeS3.publicUrl(puts.single().key!!))
    }

    @Test
    @Order(4)
    fun `조사가 붙은 제목의 글이 있을 때__어간으로 검색하면__JAR 안의 Nori 사전으로 찾는다`() {
        // given
        val postId = MAPPER.readTree(create("강아지를 입양합니다", "보호소 이야기").body()).get("data").get("postId").asLong()
        create("고양이를 입양합니다", "다른 이야기")

        // when
        val reply = get(serverPort, "/api/v1/posts/search?keyword=" + URLEncoder.encode("강아지", Charsets.UTF_8))

        // then
        assertThat(reply.statusCode()).isEqualTo(200)
        val found: JsonNode = MAPPER.readTree(reply.body()).get("data")
        assertThat(found.map { it.get("postId").asLong() }).containsExactly(postId)
    }

    @Test
    @Order(5)
    fun `토큰이 없거나 틀렸을 때__글을 작성하면__401이고 아무것도 저장되지 않는다`() {
        // given
        val before = postCount()
        val requestsBefore = ContractFakeS3.requests().size

        // when
        val missing = create("거부될 글", "본문", token = null)
        val forged = create("거부될 글", "본문", token = forgedToken())

        // then
        assertThat(missing.statusCode()).isEqualTo(401)
        assertThat(MAPPER.readTree(missing.body()).get("message").asText()).isEqualTo("인증 토큰이 필요합니다.")
        assertThat(forged.statusCode()).isEqualTo(401)
        assertThat(postCount()).isEqualTo(before)
        assertThat(ContractFakeS3.requests()).hasSize(requestsBefore)
    }

    @Test
    @Order(6)
    fun `요청을 여러 번 처리한 뒤__로그를 보면__한도 안이고 비밀 값이 없다`() {
        // given
        // when
        val log = app.output()

        // then
        assertThat(app.log.length()).isLessThan(LOG_LIMIT_BYTES)
        assertThat(log).contains("Started PawBridgeApplication")
        assertNoSecrets(log)
    }

    @Test
    @Order(7)
    fun `실행 중인 JAR일 때__SIGTERM을 보내면__정상 종료하고 연결이 하나도 남지 않는다`() {
        // given
        assertThat(appConnections()).`as`("The running application holds pooled connections").isPositive()

        // when
        app.process.destroy()
        val ended = app.process.waitFor(30, TimeUnit.SECONDS)

        // then
        assertThat(ended).`as`("The process ends within 30 seconds of SIGTERM").isTrue()
        assertThat(app.process.exitValue()).`as`("Exit code of a clean SIGTERM shutdown").isIn(0, 143)
        val log = app.output()
        assertThat(log).contains("Commencing graceful shutdown", "Graceful shutdown complete")
        assertThat(log.split("pawbridge-db - Shutdown completed.")).`as`("One pool, closed once").hasSize(2)
        assertThat(waitForNoConnections()).`as`("Connections of the application role after the process ended").isZero()
        assertThat(app.log.length()).isLessThan(LOG_LIMIT_BYTES)
        assertNoSecrets(log)
    }

    // ---- process ----------------------------------------------------------------------------------------------

    private fun awaitHealthy() {
        val deadline = System.nanoTime() + Duration.ofSeconds(90).toNanos()
        while (System.nanoTime() < deadline) {
            check(app.process.isAlive) {
                "The packaged JAR exited during startup with code ${app.process.exitValue()}:\n${app.output().takeLast(3000)}"
            }
            try {
                if (get(managementPort, "/actuator/health").statusCode() == 200) {
                    return
                }
            } catch (notYet: java.io.IOException) {
                // The listener is not up yet.
            }
            TimeUnit.MILLISECONDS.sleep(250)
        }
        error("The packaged JAR was not healthy within 90 seconds:\n${app.output().takeLast(3000)}")
    }

    private fun appConnections(): Long = ContractPostgres.admin.queryForObject(
        "SELECT count(*) FROM pg_stat_activity WHERE usename = ?", Long::class.javaObjectType, ContractPostgres.APP_ROLE,
    )!!

    /** The server notices closed sockets asynchronously, so give it a moment before counting. */
    private fun waitForNoConnections(): Long {
        val deadline = System.nanoTime() + Duration.ofSeconds(10).toNanos()
        var remaining = appConnections()
        while (remaining != 0L && System.nanoTime() < deadline) {
            TimeUnit.MILLISECONDS.sleep(200)
            remaining = appConnections()
        }
        return remaining
    }

    private fun assertNoSecrets(log: String) {
        val secrets = listOf(
            ContractPostgres.appPassword,
            jwtSecret,
            ContractFakeS3.SECRET_KEY,
            PackagedJar.MAIL_PASSWORD,
            token(AUTHOR),
        )
        for (secret in secrets) {
            assertThat(log.contains(secret)).`as`("The console output never contains a configured secret or token").isFalse()
        }
    }

    // ---- HTTP -------------------------------------------------------------------------------------------------

    private fun get(port: Int, path: String, headers: Map<String, String> = emptyMap()): HttpResponse<String> {
        val builder = HttpRequest.newBuilder(URI.create("http://127.0.0.1:$port$path")).timeout(Duration.ofSeconds(15)).GET()
        headers.forEach { (name, value) -> builder.header(name, value) }
        return http.send(builder.build(), HttpResponse.BodyHandlers.ofString())
    }

    private fun create(
        title: String,
        content: String,
        files: List<Upload> = emptyList(),
        token: String? = token(AUTHOR),
    ): HttpResponse<String> {
        val body = Multipart.build(mapOf("title" to title, "content" to content, "boardType" to "COMMUNICATION"), files)
        val builder = HttpRequest.newBuilder(URI.create("http://127.0.0.1:$serverPort/api/v1/posts"))
            .timeout(Duration.ofSeconds(20))
            .header("Content-Type", body.contentType)
            .POST(HttpRequest.BodyPublishers.ofByteArray(body.bytes))
        if (token != null) {
            builder.header("Authorization", "Bearer $token")
        }
        return http.send(builder.build(), HttpResponse.BodyHandlers.ofString())
    }

    private fun token(userId: Long): String {
        val now = Instant.now()
        return Jwts.builder()
            .subject("packaged-$userId@fixture.invalid")
            .claim("userId", userId)
            .claim("name", "Packaged synthetic")
            .claim("role", "ROLE_USER")
            .issuedAt(Date.from(now))
            .expiration(Date.from(now.plusSeconds(600)))
            .signWith(Keys.hmacShaKeyFor(jwtSecret.toByteArray(Charsets.UTF_8)))
            .compact()
    }

    private fun forgedToken(): String {
        val now = Instant.now()
        return Jwts.builder()
            .claim("userId", AUTHOR)
            .claim("role", "ROLE_ADMIN")
            .issuedAt(Date.from(now))
            .expiration(Date.from(now.plusSeconds(600)))
            .signWith(Keys.hmacShaKeyFor(ByteArray(64) { (it * 5 + 1).toByte() }))
            .compact()
    }

    // ---- database ---------------------------------------------------------------------------------------------

    private fun seedAuthor() {
        ContractPostgres.admin.update(
            "INSERT INTO pawbridge_user.users(user_id, nickname, email, name, provider, role) " +
                "VALUES (?, ?, ?, ?, 'GOOGLE', 'ROLE_USER')",
            AUTHOR, NICKNAME, "packaged-$AUTHOR@fixture.invalid", "Packaged $AUTHOR",
        )
    }

    private fun seedPost(): Long = ContractPostgres.admin.queryForObject(
        "INSERT INTO pawbridge_community.posts(author_id, title, content, board_type, image_urls, created_at, updated_at) " +
            "VALUES (?, '패키지 합성 제목', '합성 내용', 'COMMUNICATION', '[\"https://fixture.invalid/가.jpg\"]'::json, now(), now()) " +
            "RETURNING post_id",
        Long::class.javaObjectType,
        AUTHOR,
    )!!

    private fun postCount(): Long = ContractPostgres.admin.queryForObject(
        "SELECT count(*) FROM pawbridge_community.posts WHERE author_id = ?", Long::class.javaObjectType, AUTHOR,
    )!!

    /** The rows this class owns, removed before and after so the other contracts see the tables as they were. */
    private fun removeRows() {
        val admin = ContractPostgres.admin
        admin.update(
            "DELETE FROM pawbridge_community.post_search_documents WHERE post_id IN " +
                "(SELECT post_id FROM pawbridge_community.posts WHERE author_id = ?)",
            AUTHOR,
        )
        admin.update("DELETE FROM pawbridge_community.posts WHERE author_id = ?", AUTHOR)
        admin.update("DELETE FROM pawbridge_user.users WHERE user_id = ?", AUTHOR)
    }

    private companion object {
        const val AUTHOR = 9_987_001L
        const val NICKNAME = "PackagedJarAuthor"
        const val LOG_LIMIT_BYTES = 1024L * 1024L
        val MAPPER = ObjectMapper()

        fun randomHex(): String {
            val bytes = ByteArray(32)
            SecureRandom().nextBytes(bytes)
            return HexFormat.of().formatHex(bytes)
        }
    }
}
