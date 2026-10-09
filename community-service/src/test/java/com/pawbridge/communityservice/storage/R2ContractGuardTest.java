package com.pawbridge.communityservice.storage;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.HashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/** Pure safety checks: never creates a client or reads real credentials. */
class R2ContractGuardTest {
    private static final String RUN_ID = "30a4ff7b-1bb6-4f9a-baa1-33850f0acf7f";

    @Test
    void givenNoExplicitApproval_whenPrepare_thenRejectBeforeClientCreation() {
        var environment = approvedSyntheticEnvironment();
        environment.remove("PAWBRIDGE_R2_CONTRACT_APPROVED");

        assertThatThrownBy(() -> R2StorageContractTest.guardedSettings(environment, RUN_ID))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("Separate approval is required before real R2 access");
    }

    @ParameterizedTest
    @ValueSource(strings = {"R2_CONTRACT_ENDPOINT", "R2_CONTRACT_BUCKET"})
    void givenUnapprovedTarget_whenPrepare_thenRejectBeforeClientCreation(String field) {
        var environment = approvedSyntheticEnvironment();
        environment.put(field, "not-the-approved-target");

        assertThatThrownBy(() -> R2StorageContractTest.guardedSettings(environment, RUN_ID))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("Explicitly approved R2 endpoint and bucket are required");
    }

    @ParameterizedTest
    @ValueSource(strings = {"R2_CONTRACT_ACCESS_KEY_ID", "R2_CONTRACT_SECRET_ACCESS_KEY"})
    void givenMissingCredential_whenPrepare_thenRejectWithoutPrintingValue(String field) {
        var environment = approvedSyntheticEnvironment();
        environment.remove(field);

        assertThatThrownBy(() -> R2StorageContractTest.guardedSettings(environment, RUN_ID))
                .isInstanceOf(IllegalArgumentException.class).hasMessage(field + " is missing or padded");
    }

    @ParameterizedTest
    @ValueSource(strings = {"R2_CONTRACT_ACCESS_KEY_ID", "R2_CONTRACT_SECRET_ACCESS_KEY"})
    void givenPaddedCredential_whenPrepare_thenRejectWithoutPrintingValue(String field) {
        var environment = approvedSyntheticEnvironment();
        environment.put(field, " padded-synthetic-value ");

        assertThatThrownBy(() -> R2StorageContractTest.guardedSettings(environment, RUN_ID))
                .isInstanceOf(IllegalArgumentException.class).hasMessage(field + " is missing or padded");
    }

    @Test
    void givenPaddedSessionToken_whenPrepare_thenRejectWithoutPrintingValue() {
        var environment = approvedSyntheticEnvironment();
        environment.put("R2_CONTRACT_SESSION_TOKEN", " padded-synthetic-token ");

        assertThatThrownBy(() -> R2StorageContractTest.guardedSettings(environment, RUN_ID))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("R2_CONTRACT_SESSION_TOKEN must not be padded");
    }

    @Test
    void givenNonUniqueRunPath_whenPrepare_thenRejectBeforeClientCreation() {
        assertThatThrownBy(() -> R2StorageContractTest.guardedSettings(approvedSyntheticEnvironment(), "../reports"))
                .isInstanceOf(IllegalArgumentException.class).hasMessage("Unique UUID run identifier is required");
    }

    @Test
    void givenApprovedTarget_whenPrepare_thenUseOnlyUniqueDevPathAndExplicitCredentialFields() {
        var environment = approvedSyntheticEnvironment();
        // Ordinary application variables must not become the contract test's configuration.
        environment.put("R2_OBJECT_PREFIX", "reports/");
        environment.put("R2_ACCESS_KEY_ID", "not-contract-credentials");
        environment.put("R2_SESSION_TOKEN", "not-contract-session");

        var settings = R2StorageContractTest.guardedSettings(environment, RUN_ID);

        assertThat(settings.get("pawbridge.storage.object-prefix")).isEqualTo("dev/contract-tests/" + RUN_ID + "/");
        assertThat(settings.get("spring.cloud.aws.credentials.access-key")).isEqualTo("synthetic-test-access");
        assertThat(settings.get("pawbridge.storage.session-token")).isEqualTo("");
    }

    private static Map<String, String> approvedSyntheticEnvironment() {
        return new HashMap<>(Map.of(
                "PAWBRIDGE_R2_CONTRACT_APPROVED", "yes",
                "R2_CONTRACT_ENDPOINT", R2StorageContractTest.ENDPOINT,
                "R2_CONTRACT_BUCKET", R2StorageContractTest.BUCKET,
                "R2_CONTRACT_ACCESS_KEY_ID", "synthetic-test-access",
                "R2_CONTRACT_SECRET_ACCESS_KEY", "synthetic-test-secret"));
    }
}
