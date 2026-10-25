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
    fun given__explicit_candidate_settings__when__validating__then__preserve_scope_without_credential_diagnostics() {
        val settings = F3TestInputs.settings()
        assertThat(settings.endpoint().host).isEqualTo("127.0.0.1")
        assertThat(settings.objectPrefix()).isEqualTo("f3-test/")
        assertThat(settings.toString()).doesNotContain("synthetic-secret", F3TestInputs.JWT_SECRET, "synthetic-writer")
    }

    @Test
    fun given__missing_sensitive_inputs__when__validating__then__fail_by_setting_name_without_fallback() {
        for (key in F3TestInputs.values().keys) {
            if (!key.startsWith("F3_")) {
                continue
            }
            val values = F3TestInputs.values()
            values.remove(key)
            assertThatThrownBy { PostWriteSettings.fromEnvironment { name -> values[name] } }
                .hasMessage("Missing or invalid explicit setting: $key")
        }
    }

    @Test
    fun given__legacy_writer_account_inputs__when__validating__then__ignore_them_for_the_single_shared_pool() {
        val values = F3TestInputs.values()
        values["F3_DB_WRITER_USER"] = "fixture-reader"
        values["F3_DB_WRITER_PASSWORD"] = "synthetic-writer-only"
        assertThatCode { PostWriteSettings.fromEnvironment { name -> values[name] } }.doesNotThrowAnyException()
        values.remove("F3_DB_WRITER_USER")
        values.remove("F3_DB_WRITER_PASSWORD")
        assertThatCode { PostWriteSettings.fromEnvironment { name -> values[name] } }.doesNotThrowAnyException()
    }

    @Test
    fun given__unsafe_inputs__when__validating__then__reject_before_listener_or_external_clients() {
        val invalid = mapOf(
            "PAWBRIDGE_JWT_SECRET" to "private-short-value",
            "PAWBRIDGE_STORAGE_ENDPOINT" to "http://private-user:private-value@localhost:39001",
            "PAWBRIDGE_STORAGE_PUBLIC_BASE_URL" to "https://fixture.invalid/?private=detail",
            "PAWBRIDGE_STORAGE_OBJECT_PREFIX" to "../unowned/",
            "PAWBRIDGE_STORAGE_BUCKET" to "invalid/private-value",
        )
        invalid.forEach { (key, value) ->
            val values = F3TestInputs.values()
            values[key] = value
            assertThatThrownBy { PostWriteSettings.fromEnvironment { name -> values[name] } }
                .hasMessage("Missing or invalid explicit setting: $key")
        }
    }

    @Test
    fun given__empty_object_prefix__when__validating__then__accept_it_as_bucket_root() {
        val values = F3TestInputs.values()
        values["PAWBRIDGE_STORAGE_OBJECT_PREFIX"] = ""
        val settings = PostWriteSettings.fromEnvironment { name -> values[name] }
        assertThat(settings.objectPrefix()).isEmpty()
    }

    @Test
    fun given__unset_object_prefix__when__validating__then__accept_it_as_bucket_root() {
        val values = F3TestInputs.values()
        values.remove("PAWBRIDGE_STORAGE_OBJECT_PREFIX")
        val settings = PostWriteSettings.fromEnvironment { name -> values[name] }
        assertThat(settings.objectPrefix()).isEmpty()
    }

    @ParameterizedTest
    @ValueSource(strings = ["posts", "posts/", "a/b-c_d/"])
    fun given__non_empty_object_prefix__when__validating__then__keep_the_value_unchanged(prefix: String) {
        val values = F3TestInputs.values()
        values["PAWBRIDGE_STORAGE_OBJECT_PREFIX"] = prefix
        val settings = PostWriteSettings.fromEnvironment { name -> values[name] }
        assertThat(settings.objectPrefix()).isEqualTo(prefix)
    }

    @ParameterizedTest
    @ValueSource(
        strings = [" ", "  ", "/", "//", "../", "a//b", "a/../b", " posts/", "posts /", "posts\\", "posts?x=1", "posts%2f"],
    )
    fun given__blank_or_unsafe_object_prefix__when__validating__then__reject_by_setting_name(prefix: String) {
        val values = F3TestInputs.values()
        values["PAWBRIDGE_STORAGE_OBJECT_PREFIX"] = prefix
        assertThatThrownBy { PostWriteSettings.fromEnvironment { name -> values[name] } }
            .hasMessage("Missing or invalid explicit setting: PAWBRIDGE_STORAGE_OBJECT_PREFIX")
    }
}
