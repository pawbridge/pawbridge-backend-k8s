package com.pawbridge.backend;

import com.pawbridge.backend.boot.configuration.PostWriteSettings;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import static org.assertj.core.api.Assertions.*;

class PostWriteSettingsTest {
    @Test
    void given__explicit_candidate_settings__when__validating__then__preserve_scope_without_credential_diagnostics() {
        var settings=F3TestInputs.settings();
        assertThat(settings.endpoint().getHost()).isEqualTo("127.0.0.1");
        assertThat(settings.objectPrefix()).isEqualTo("f3-test/");
        assertThat(settings.toString()).doesNotContain("synthetic-secret", F3TestInputs.JWT_SECRET,"synthetic-writer");
    }

    @Test
    void given__missing_sensitive_inputs__when__validating__then__fail_by_setting_name_without_fallback() {
        for(String key:F3TestInputs.values().keySet()) {
            if(!key.startsWith("F3_")) continue;
            var values=F3TestInputs.values(); values.remove(key);
            assertThatThrownBy(()->PostWriteSettings.fromEnvironment(values::get))
                    .hasMessage("Missing or invalid explicit setting: "+key);
        }
    }

    @Test
    void given__legacy_writer_account_inputs__when__validating__then__ignore_them_for_the_single_shared_pool() {
        var values=F3TestInputs.values();
        values.put("F3_DB_WRITER_USER","fixture-reader"); values.put("F3_DB_WRITER_PASSWORD","synthetic-writer-only");
        assertThatCode(()->PostWriteSettings.fromEnvironment(values::get)).doesNotThrowAnyException();
        values.remove("F3_DB_WRITER_USER"); values.remove("F3_DB_WRITER_PASSWORD");
        assertThatCode(()->PostWriteSettings.fromEnvironment(values::get)).doesNotThrowAnyException();
    }

    @Test
    void given__unsafe_inputs__when__validating__then__reject_before_listener_or_external_clients() {
        Map<String,String> invalid=Map.of(
                "PAWBRIDGE_JWT_SECRET","private-short-value",
                "PAWBRIDGE_STORAGE_ENDPOINT","http://private-user:private-value@localhost:39001",
                "PAWBRIDGE_STORAGE_PUBLIC_BASE_URL","https://fixture.invalid/?private=detail",
                "PAWBRIDGE_STORAGE_OBJECT_PREFIX","../unowned/",
                "PAWBRIDGE_STORAGE_BUCKET","invalid/private-value");
        invalid.forEach((key,value)-> {
            var values=F3TestInputs.values(); values.put(key,value);
            assertThatThrownBy(()->PostWriteSettings.fromEnvironment(values::get))
                    .hasMessage("Missing or invalid explicit setting: "+key);
        });
    }

    @Test
    void given__empty_object_prefix__when__validating__then__accept_it_as_bucket_root() {
        var values=F3TestInputs.values();
        values.put("PAWBRIDGE_STORAGE_OBJECT_PREFIX","");
        var settings=PostWriteSettings.fromEnvironment(values::get);
        assertThat(settings.objectPrefix()).isEmpty();
    }

    @Test
    void given__unset_object_prefix__when__validating__then__accept_it_as_bucket_root() {
        var values=F3TestInputs.values();
        values.remove("PAWBRIDGE_STORAGE_OBJECT_PREFIX");
        var settings=PostWriteSettings.fromEnvironment(values::get);
        assertThat(settings.objectPrefix()).isEmpty();
    }

    @ParameterizedTest
    @ValueSource(strings={"posts","posts/","a/b-c_d/"})
    void given__non_empty_object_prefix__when__validating__then__keep_the_value_unchanged(String prefix) {
        var values=F3TestInputs.values();
        values.put("PAWBRIDGE_STORAGE_OBJECT_PREFIX",prefix);
        var settings=PostWriteSettings.fromEnvironment(values::get);
        assertThat(settings.objectPrefix()).isEqualTo(prefix);
    }

    @ParameterizedTest
    @ValueSource(strings={" ","  ","/","//","../","a//b","a/../b"," posts/","posts /","posts\\","posts?x=1","posts%2f"})
    void given__blank_or_unsafe_object_prefix__when__validating__then__reject_by_setting_name(String prefix) {
        var values=F3TestInputs.values();
        values.put("PAWBRIDGE_STORAGE_OBJECT_PREFIX",prefix);
        assertThatThrownBy(()->PostWriteSettings.fromEnvironment(values::get))
                .hasMessage("Missing or invalid explicit setting: PAWBRIDGE_STORAGE_OBJECT_PREFIX");
    }
}
