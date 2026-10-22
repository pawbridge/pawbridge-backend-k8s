package com.pawbridge.backend;

import com.pawbridge.backend.boot.configuration.PostWriteSettings;
import java.util.Map;
import org.junit.jupiter.api.Test;
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
                "F3_JWT_SECRET","private-short-value",
                "F3_STORAGE_ENDPOINT","http://private-user:private-value@localhost:39001",
                "F3_STORAGE_PUBLIC_BASE_URL","https://fixture.invalid/?private=detail",
                "F3_STORAGE_OBJECT_PREFIX","../unowned/",
                "F3_STORAGE_BUCKET","invalid/private-value");
        invalid.forEach((key,value)-> {
            var values=F3TestInputs.values(); values.put(key,value);
            assertThatThrownBy(()->PostWriteSettings.fromEnvironment(values::get))
                    .hasMessage("Missing or invalid explicit setting: "+key);
        });
    }
}
