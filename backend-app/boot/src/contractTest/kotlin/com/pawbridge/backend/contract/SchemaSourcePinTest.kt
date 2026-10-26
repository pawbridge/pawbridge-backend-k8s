package com.pawbridge.backend.contract

import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import java.util.HexFormat
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import org.springframework.core.io.ClassPathResource

/**
 * The migration files under `schema/` are copies of the service migrations. While the service directories
 * exist, every copy must be byte-identical to its source and no source may lack a copy. Once a service
 * directory is removed, its copies are the only source and there is nothing left to compare.
 */
@Tag("contract")
class SchemaSourcePinTest {
    @Test
    fun `사용자 서비스 마이그레이션이 있을 때__복사본을 비교하면__해시가 원본과 같다`() {
        // given
        // when
        // then
        assertCopiesMatch("user-service")
    }

    @Test
    fun `커뮤니티 서비스 마이그레이션이 있을 때__복사본을 비교하면__해시가 원본과 같다`() {
        // given
        // when
        // then
        assertCopiesMatch("community-service")
    }

    @Test
    fun `동물 서비스 마이그레이션이 있을 때__복사본을 비교하면__해시가 원본과 같다`() {
        // given
        // when
        // then
        assertCopiesMatch("animal-service")
    }

    private fun assertCopiesMatch(service: String) {
        val copies = ContractPostgres.migrationFiles("schema/$service")
        assertThat(copies).isNotEmpty()
        val sourceDirectory = sourceDirectory(service)
        if (sourceDirectory == null) {
            // Without the legacy directory the copies are the only source; nothing can drift.
            return
        }
        val sources = Files.list(sourceDirectory).use { stream ->
            stream.map { it.fileName.toString() }.filter { it.matches(Regex("V[0-9]+__.+\\.sql")) }.sorted().toList()
        }
        assertThat(copies.sorted()).`as`("every $service migration is copied and none is extra").isEqualTo(sources)
        for (name in copies) {
            val copy = ClassPathResource("schema/$service/$name").inputStream.use { sha256(it.readAllBytes()) }
            val source = sha256(Files.readAllBytes(sourceDirectory.resolve(name)))
            assertThat(copy).`as`("SHA-256 of $service/$name").isEqualTo(source)
        }
    }

    private fun sourceDirectory(service: String): Path? {
        val root = System.getProperty("pawbridge.contract.legacy-root") ?: return null
        val directory = Path.of(root, service, "src", "migration", "resources", "db", "postgresql")
        return if (Files.isDirectory(directory)) directory else null
    }

    private fun sha256(bytes: ByteArray): String {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes))
    }
}
