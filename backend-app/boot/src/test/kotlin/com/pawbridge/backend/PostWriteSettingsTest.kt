package com.pawbridge.backend

import com.pawbridge.backend.boot.configuration.PostWriteSettings
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatCode
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource

class PostWriteSettingsTest {
    @Test
    fun `명시적 후보 설정이 주어졌을 때__검증하면__범위를 유지하고 자격 증명을 진단에 남기지 않는다`() {
        // given
        val settings = F3TestInputs.settings()

        // when
        val endpoint = settings.endpoint()

        // then
        assertThat(endpoint.host).isEqualTo("127.0.0.1")
        assertThat(settings.objectPrefix()).isEqualTo("f3-test/")
        assertThat(settings.toString()).doesNotContain("synthetic-secret", F3TestInputs.JWT_SECRET, "synthetic-writer")
    }

    @Test
    fun `민감 입력이 빠졌을 때__검증하면__대체값 없이 설정 이름으로 실패한다`() {
        // given
        val keys = F3TestInputs.values().keys.filter { key -> key.startsWith("F3_") }

        // when
        val failures = keys.map { key ->
            val values = F3TestInputs.values()
            values.remove(key)
            key to assertThatThrownBy { PostWriteSettings.fromEnvironment { name -> values[name] } }
        }

        // then
        for ((key, failure) in failures) {
            failure.hasMessage("Missing or invalid explicit setting: $key")
        }
    }

    @Test
    fun `예전 작성기 계정 입력이 주어졌을 때__검증하면__단일 공유 풀에서는 무시한다`() {
        // given
        val values = F3TestInputs.values()
        values["F3_DB_WRITER_USER"] = "fixture-reader"
        values["F3_DB_WRITER_PASSWORD"] = "synthetic-writer-only"

        // when
        val withLegacy = assertThatCode { PostWriteSettings.fromEnvironment { name -> values[name] } }
        values.remove("F3_DB_WRITER_USER")
        values.remove("F3_DB_WRITER_PASSWORD")
        val withoutLegacy = assertThatCode { PostWriteSettings.fromEnvironment { name -> values[name] } }

        // then
        withLegacy.doesNotThrowAnyException()
        withoutLegacy.doesNotThrowAnyException()
    }

    @Test
    fun `안전하지 않은 입력이 주어졌을 때__검증하면__리스너나 외부 클라이언트 전에 거부한다`() {
        // given
        val invalid = mapOf(
            "PAWBRIDGE_JWT_SECRET" to "private-short-value",
            "PAWBRIDGE_STORAGE_ENDPOINT" to "http://private-user:private-value@localhost:39001",
            "PAWBRIDGE_STORAGE_PUBLIC_BASE_URL" to "https://fixture.invalid/?private=detail",
            "PAWBRIDGE_STORAGE_OBJECT_PREFIX" to "../unowned/",
            "PAWBRIDGE_STORAGE_BUCKET" to "invalid/private-value",
        )

        // when
        val failures = invalid.map { (key, value) ->
            val values = F3TestInputs.values()
            values[key] = value
            key to assertThatThrownBy { PostWriteSettings.fromEnvironment { name -> values[name] } }
        }

        // then
        for ((key, failure) in failures) {
            failure.hasMessage("Missing or invalid explicit setting: $key")
        }
    }

    @Test
    fun `빈 객체 접두사가 주어졌을 때__검증하면__버킷 루트로 받아들인다`() {
        // given
        val values = F3TestInputs.values()
        values["PAWBRIDGE_STORAGE_OBJECT_PREFIX"] = ""

        // when
        val settings = PostWriteSettings.fromEnvironment { name -> values[name] }

        // then
        assertThat(settings.objectPrefix()).isEmpty()
    }

    @Test
    fun `설정되지 않은 객체 접두사가 주어졌을 때__검증하면__버킷 루트로 받아들인다`() {
        // given
        val values = F3TestInputs.values()
        values.remove("PAWBRIDGE_STORAGE_OBJECT_PREFIX")

        // when
        val settings = PostWriteSettings.fromEnvironment { name -> values[name] }

        // then
        assertThat(settings.objectPrefix()).isEmpty()
    }

    @ParameterizedTest
    @ValueSource(strings = ["posts", "posts/", "a/b-c_d/"])
    fun `비어 있지 않은 객체 접두사가 주어졌을 때__검증하면__값을 그대로 유지한다`(prefix: String) {
        // given
        val values = F3TestInputs.values()
        values["PAWBRIDGE_STORAGE_OBJECT_PREFIX"] = prefix

        // when
        val settings = PostWriteSettings.fromEnvironment { name -> values[name] }

        // then
        assertThat(settings.objectPrefix()).isEqualTo(prefix)
    }

    @ParameterizedTest
    @ValueSource(
        strings = [" ", "  ", "/", "//", "../", "a//b", "a/../b", " posts/", "posts /", "posts\\", "posts?x=1", "posts%2f"],
    )
    fun `공백이거나 안전하지 않은 객체 접두사가 주어졌을 때__검증하면__설정 이름으로 거부한다`(prefix: String) {
        // given
        val values = F3TestInputs.values()
        values["PAWBRIDGE_STORAGE_OBJECT_PREFIX"] = prefix

        // when
        val failure = assertThatThrownBy { PostWriteSettings.fromEnvironment { name -> values[name] } }

        // then
        failure.hasMessage("Missing or invalid explicit setting: PAWBRIDGE_STORAGE_OBJECT_PREFIX")
    }
}
