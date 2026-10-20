package com.pawbridge.backend.architecture;

import org.gradle.testkit.runner.BuildResult;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.io.IOException;
import java.nio.file.Files;

import static org.gradle.testkit.runner.TaskOutcome.FAILED;
import static org.gradle.testkit.runner.TaskOutcome.SUCCESS;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class GradleArchitectureTest extends BusinessBuildFixture {
    @Test
    void givenUserStoreApiAndCommunityUserChain_whenCheck_thenPermitOnlyResolvedTransitiveStoreArtifacts() throws IOException {
        javaSource("user", "internal.infrastructure.StoreAdapter", "public class StoreAdapter { com.pawbridge.backend.store.api.StoreLookup store; }");
        BuildResult result = runner("check").build();
        assertTask(result, ":verifyArchitecture", SUCCESS);
        assertTask(result, ":verifyBusinessPackages", SUCCESS);
        assertCompiled("community", "internal.application.PostReader");
        assertCompiled("user", "internal.infrastructure.StoreAdapter");
    }

    @Test
    void givenCommunityDirectStoreEdge_whenCheck_thenStillRejectGraph() throws IOException {
        appendModuleBuild("community", "dependencies { implementation project(':store') }");
        assertGraphRejected(runner("check").buildAndFail(), "forbidden project dependency :community -> :store");
    }

    @Test
    void givenCommunityUsesTransitiveStoreApi_whenCheck_thenStillRejectActualBusinessReference() throws IOException {
        appendModuleBuild("user", "dependencies { api project(':store') }");
        javaSource("community", "internal.infrastructure.StoreBypass", "public class StoreBypass { com.pawbridge.backend.store.api.StoreLookup store; }");
        assertPackageRejected(runner("check").buildAndFail(), "BUSINESS_INTERNAL", "com.pawbridge.backend.store.api.StoreLookup");
    }

    @Test
    void givenCommunityUnregisteredStoreArtifactFile_whenCheck_thenRejectDespiteTheValidTransitiveGraph() throws IOException {
        createEmptyJar("store/build/unregistered/store-copy.jar");
        appendModuleBuild("community", "dependencies { runtimeOnly files(rootProject.file('store/build/unregistered/store-copy.jar')) }");
        assertGraphRejected(runner("check").buildAndFail(), "forbidden project dependency :community -> :store through files");
    }

    @Test
    void givenAllowedBusinessGraph_whenRootCheck_thenCompilesClassesAndCopiesLocalResources() throws IOException {
        write("boot/config/fixture.properties", "fixture.enabled=true\n");
        appendModuleBuild("boot", """
                tasks.named('processResources') {
                    from(project.file('config/fixture.properties'))
                }
                """);

        BuildResult result = runner("check").build();

        assertTask(result, ":verifyArchitecture", SUCCESS);
        assertTask(result, ":verifyBusinessPackages", SUCCESS);
        assertTask(result, ":user:compileJava", SUCCESS);
        assertTask(result, ":community:compileJava", SUCCESS);
        assertTask(result, ":animal:compileJava", SUCCESS);
        assertTask(result, ":store:compileJava", SUCCESS);
        for (String module : MODULES) {
            assertCheckCompleted(result, ":" + module + ":check");
        }
        assertCheckCompleted(result, ":check");
        assertCompiled("user", "internal.domain.UserName");
        assertCompiled("community", "internal.application.PostReader");
        assertCompiled("animal", "internal.application.AnimalReader");
        assertCompiled("store", "internal.application.StoreReader");
        assertEquals("fixture.enabled=true\n",
                Files.readString(fixture.resolve("boot/build/resources/main/fixture.properties")));
    }

    @ParameterizedTest
    @CsvSource({"user, community", "user, boot", "community, boot", "user, animal", "community, animal",
            "animal, user", "animal, community", "animal, boot",
            "community, store", "store, user", "store, community", "store, boot"})
    void givenForbiddenBusinessEdge_whenCheck_thenRejectsGraph(String source, String target) throws IOException {
        appendModuleBuild(source, "dependencies { implementation project(':" + target + "') }");

        assertGraphRejected(runner("check").buildAndFail(),
                "forbidden project dependency :" + source + " -> :" + target);
    }

    @ParameterizedTest
    @ValueSource(strings = {"api", "compileOnly", "runtimeOnly", "annotationProcessor"})
    void givenForbiddenAlternateProductionEdge_whenCheck_thenRejectsGraph(String configuration) throws IOException {
        appendModuleBuild("user", "dependencies { " + configuration + " project(':community') }");

        assertGraphRejected(runner("check").buildAndFail(), "forbidden project dependency :user -> :community");
    }

    @ParameterizedTest
    @ValueSource(strings = {"user", "community", "animal", "store"})
    void givenBusinessBootPlugin_whenCheck_thenRejectsExecutableBusinessModule(String module) throws IOException {
        appendModuleBuild(module, "apply plugin: 'org.springframework.boot'");

        assertGraphRejected(runner("check").buildAndFail(),
                "business modules must not apply Spring Boot plugin: :" + module);
    }

    @ParameterizedTest
    @ValueSource(strings = {"user", "community", "animal", "store"})
    void givenBusinessUsesPlainJavaPlugin_whenCheck_thenRejectsMissingLibraryContract(String module) throws IOException {
        write(module + "/build.gradle", "plugins { id 'java' }\n"
                + (module.equals("community") ? "dependencies { implementation project(':user') }\n" : ""));

        assertGraphRejected(runner("check").buildAndFail(),
                "business modules must apply java-library plugin: :" + module);
    }

    @Test
    void givenForbiddenEdge_whenIndividualCheck_thenRunsTheSameGraphPreflight() throws IOException {
        appendModuleBuild("user", "dependencies { implementation project(':community') }");

        assertGraphRejected(runner(":user:check").buildAndFail(),
                "forbidden project dependency :user -> :community");
    }

    @Test
    void givenAnimalBusinessModule_whenAnimalCheck_thenCompilesClassesAndRunsSharedGuards() throws IOException {
        javaSource("boot", "AnimalAssembly", """
                public class AnimalAssembly {
                    private final com.pawbridge.backend.animal.internal.application.AnimalReader animals =
                        new com.pawbridge.backend.animal.internal.application.AnimalReader();
                    public String breed() { return animals.breed(); }
                }
                """);

        BuildResult result = runner(":animal:check").build();

        assertTask(result, ":verifyArchitecture", SUCCESS);
        assertTask(result, ":verifyBusinessPackages", SUCCESS);
        for (String module : MODULES) assertTask(result, ":" + module + ":compileJava", SUCCESS);
        assertCheckCompleted(result, ":animal:check");
        assertCompiled("animal", "api.AnimalLookup");
        assertCompiled("animal", "internal.domain.AnimalName");
        assertCompiled("animal", "internal.application.AnimalReader");
        assertCompiled("store", "internal.application.StoreReader");
        assertCompiled("boot", "AnimalAssembly");
    }

    @Test
    void givenAnimalReferencesUserOutputAsFiles_whenAnimalCheck_thenRejectsOwner() throws IOException {
        appendModuleBuild("animal", """
                evaluationDependsOn(':user')
                dependencies { implementation files(project(':user').sourceSets.main.output) }
                """);

        assertGraphRejected(runner(":animal:check").buildAndFail(),
                "forbidden project dependency :animal -> :user through files");
    }

    @Test
    void givenAnimalSiblingServiceFileClasspath_whenAnimalCheck_thenRejects() throws IOException {
        appendModuleBuild("boot", "configurations.implementation.dependencies.clear()");
        createEmptyJar("../store-service/build/libs/store-service.jar");
        appendModuleBuild("animal", "dependencies { runtimeOnly files(rootProject.file('../store-service/build/libs/store-service.jar')) }");

        assertGraphRejected(runner(":animal:check").buildAndFail(),
                ":animal production classpath references a sibling service");
    }

    @Test
    void givenStoreBusinessModule_whenStoreCheck_thenCompilesClassesAndRunsSharedGuards() throws IOException {
        javaSource("boot", "StoreAssembly", """
                public class StoreAssembly {
                    private final com.pawbridge.backend.store.internal.application.StoreReader stores =
                        new com.pawbridge.backend.store.internal.application.StoreReader();
                    public String breed() { return stores.breed(); }
                }
                """);

        BuildResult result = runner(":store:check").build();

        assertTask(result, ":verifyArchitecture", SUCCESS);
        assertTask(result, ":verifyBusinessPackages", SUCCESS);
        for (String module : MODULES) assertTask(result, ":" + module + ":compileJava", SUCCESS);
        assertCheckCompleted(result, ":store:check");
        assertCompiled("store", "api.StoreLookup");
        assertCompiled("store", "internal.domain.StoreName");
        assertCompiled("store", "internal.application.StoreReader");
        assertCompiled("animal", "internal.application.AnimalReader");
        assertCompiled("boot", "StoreAssembly");
    }

    @Test
    void givenStoreReferencesUserOutputAsFiles_whenStoreCheck_thenRejectsOwner() throws IOException {
        appendModuleBuild("store", """
                evaluationDependsOn(':user')
                dependencies { implementation files(project(':user').sourceSets.main.output) }
                """);

        assertGraphRejected(runner(":store:check").buildAndFail(),
                "forbidden project dependency :store -> :user through files");
    }

    @Test
    void givenStoreSiblingServiceFileClasspath_whenStoreCheck_thenRejects() throws IOException {
        appendModuleBuild("boot", "configurations.implementation.dependencies.clear()");
        createEmptyJar("../store-service/build/libs/store-service.jar");
        appendModuleBuild("store", "dependencies { runtimeOnly files(rootProject.file('../store-service/build/libs/store-service.jar')) }");

        assertGraphRejected(runner(":store:check").buildAndFail(),
                ":store production classpath references a sibling service");
    }

    @Test
    void givenExternalDependencyImportsSubstitutedProject_whenCheck_thenRejectsHiddenGraphEdge() throws IOException {
        createEmptyJar("repository/fixture/bridge/1.0/bridge-1.0.jar");
        write("repository/fixture/bridge/1.0/bridge-1.0.pom", """
                <project>
                  <modelVersion>4.0.0</modelVersion>
                  <groupId>fixture</groupId><artifactId>bridge</artifactId><version>1.0</version>
                  <dependencies><dependency>
                    <groupId>fixture</groupId><artifactId>user</artifactId><version>1.0</version>
                  </dependency></dependencies>
                </project>
                """);
        appendModuleBuild("boot", """
                configurations.configureEach {
                    resolutionStrategy.dependencySubstitution {
                        substitute module('fixture:user') using project(':user')
                    }
                }
                dependencies { implementation 'fixture:bridge:1.0' }
                """);

        BuildResult result = runner("check").buildAndFail();

        assertGraphRejected(result, "external dependency imports project");
        assertTask(result, ":verifyArchitecture", FAILED);
    }

    @ParameterizedTest
    @ValueSource(strings = {"sourceSets.main.output", "tasks.named('jar')"})
    void givenForbiddenModuleOutputAsFiles_whenCheck_thenRejectsOwner(String output) throws IOException {
        disconnectCommunityFromUser();
        appendModuleBuild("user", """
                evaluationDependsOn(':community')
                dependencies { implementation files(project(':community').%s) }
                """.formatted(output));

        BuildResult result = runner("check").buildAndFail();

        assertGraphRejected(result, "forbidden project dependency :user -> :community through files");
        assertTask(result, ":verifyArchitecture", FAILED);
    }

    @ParameterizedTest
    @ValueSource(strings = {"sourceSets.main.output", "tasks.named('jar')"})
    void givenRelocatedForbiddenOutputAsFiles_whenCheck_thenRejectsOwner(String output) throws IOException {
        disconnectCommunityFromUser();
        appendModuleBuild("community", "layout.buildDirectory = rootProject.layout.buildDirectory.dir('community')");
        appendModuleBuild("user", """
                evaluationDependsOn(':community')
                dependencies { implementation files(project(':community').%s) }
                """.formatted(output));

        assertGraphRejected(runner("check").buildAndFail(),
                "forbidden project dependency :user -> :community through files");
    }

    @ParameterizedTest
    @CsvSource({"compileJava, classes, sourceSets.main.output", "jar, libs, tasks.named('jar')"})
    void givenIndividuallyRelocatedForbiddenOutput_whenCheck_thenRejectsOwner(
            String producerTask, String directory, String output) throws IOException {
        disconnectCommunityFromUser();
        appendModuleBuild("community", """
                tasks.named('%s') {
                    destinationDirectory = rootProject.layout.projectDirectory.dir('custom-outputs/community/%s')
                }
                """.formatted(producerTask, directory));
        appendModuleBuild("user", """
                evaluationDependsOn(':community')
                dependencies { implementation files(project(':community').%s) }
                """.formatted(output));

        assertGraphRejected(runner("check").buildAndFail(),
                "forbidden project dependency :user -> :community through files");
    }

    @ParameterizedTest
    @ValueSource(strings = {"sourceSets.main.output", "tasks.named('jar')"})
    void givenRelocatedAllowedUserOutputAsFiles_whenCheck_thenCompilesConsumer(String output) throws IOException {
        appendModuleBuild("user", "layout.buildDirectory = rootProject.layout.buildDirectory.dir('user')");
        appendModuleBuild("community", """
                evaluationDependsOn(':user')
                configurations.implementation.dependencies.clear()
                configurations.compileOnly.dependencies.clear()
                dependencies { implementation files(project(':user').%s) }
                """.formatted(output));

        BuildResult result = runner("check").build();

        assertTask(result, ":verifyArchitecture", SUCCESS);
        assertTask(result, ":verifyBusinessPackages", SUCCESS);
        assertTask(result, ":user:compileJava", SUCCESS);
        assertTask(result, ":community:compileJava", SUCCESS);
        assertTrue(Files.isRegularFile(fixture.resolve(
                "build/user/classes/java/main/com/pawbridge/backend/user/api/UserLookup.class")));
        assertCompiled("community", "internal.application.PostReader");
    }

    @Test
    void givenSharedOutputOwners_whenCheck_thenRejectsAmbiguousOwnership() throws IOException {
        appendModuleBuild("user", "layout.buildDirectory = rootProject.layout.buildDirectory.dir('shared')");
        appendModuleBuild("community", "layout.buildDirectory = rootProject.layout.buildDirectory.dir('shared')");
        createEmptyJar("build/shared/libs/shared.jar");
        appendModuleBuild("boot", "dependencies { implementation files(rootProject.file('build/shared/libs/shared.jar')) }");

        assertGraphRejected(runner("check").buildAndFail(),
                ":boot production classpath has ambiguous output ownership");
    }

    @ParameterizedTest
    @ValueSource(strings = {"dependencies { runtimeOnly files(rootProject.file('../store-service/build/libs/store-service.jar')) }",
            "sourceSets.main.compileClasspath += files(rootProject.file('../store-service/build/libs/store-service.jar'))",
            "tasks.named('compileJava') { classpath = files(rootProject.file('../store-service/build/libs/store-service.jar')) }",
            "tasks.named('compileJava') { options.annotationProcessorPath = files(rootProject.file('../store-service/build/libs/store-service.jar')) }"})
    void givenSiblingServiceFileClasspath_whenCheck_thenRejects(String configuration) throws IOException {
        // Isolate the user input: runtimeOnly otherwise propagates through legal
        // project dependencies and can be rejected first on boot or community.
        appendModuleBuild("boot", "configurations.implementation.dependencies.clear()");
        disconnectCommunityFromUser();
        createEmptyJar("../store-service/build/libs/store-service.jar");
        appendModuleBuild("user", configuration);

        assertGraphRejected(runner("check").buildAndFail(),
                ":user production classpath references a sibling service");
    }

    @Test
    void givenRootProjectDependency_whenCheck_thenRejectsAggregatorProductionPlugin() throws IOException {
        append("build.gradle", "apply plugin: 'java-library'");
        appendModuleBuild("boot", "dependencies { implementation project(':') }");

        assertGraphRejected(runner("check").buildAndFail(),
                "root project is a build aggregator and must not apply Java production plugins");
    }

    @Test
    void givenUnknownProject_whenCheck_thenRejectsUnexpectedBuildMember() throws IOException {
        append("settings.gradle", "include 'legacy'");
        write("legacy/build.gradle", "plugins { id 'java-library' }");

        assertGraphRejected(runner("check").buildAndFail(), "only boot, user, community, animal and store projects are allowed");
    }

    @Test
    void givenReassignedBusinessDirectory_whenCheck_thenRejects() throws IOException {
        write("legacy-user/build.gradle", "plugins { id 'java-library' }");
        append("settings.gradle", "project(':user').projectDir = file('legacy-user')");

        assertGraphRejected(runner("check").buildAndFail(), ":user must use its own application directory");
    }

    @Test
    void givenCompositeBuild_whenCheck_thenRejects() throws IOException {
        write("legacy/settings.gradle", "rootProject.name = 'legacy'");
        write("legacy/build.gradle", "plugins { id 'java-library' }");
        append("settings.gradle", "includeBuild 'legacy'");

        assertGraphRejected(runner("check").buildAndFail(), "included builds are not allowed");
    }

    @Test
    void givenMainSourceDirectoryOutsideModule_whenCheck_thenRejects() throws IOException {
        appendModuleBuild("user", "sourceSets.main.java.srcDir rootProject.file('legacy/src')");

        assertGraphRejected(runner("check").buildAndFail(), ":user production sources must stay inside the module");
    }

    @Test
    void givenCompilerSourceOutsideModule_whenCheck_thenRejects() throws IOException {
        write("../store-service/src/main/java/Legacy.java", "class Legacy {}\n");
        appendModuleBuild("user", """
                tasks.named('compileJava') {
                    source rootProject.file('../store-service/src/main/java/Legacy.java')
                }
                """);

        assertGraphRejected(runner("check").buildAndFail(),
                ":user production compiler sources must stay inside the module");
    }

    @Test
    void givenSiblingCompilerSourcepath_whenCheck_thenRejects() throws IOException {
        write("../store-service/src/main/java/Legacy.java", "public class Legacy {}\n");
        appendModuleBuild("user", """
                tasks.named('compileJava') {
                    options.sourcepath = files(rootProject.file('../store-service/src/main/java'))
                }
                """);

        assertGraphRejected(runner("check").buildAndFail(),
                ":user production compiler sourcepath must stay inside the module");
    }

    @Test
    void givenLocalCompilerSourcepath_whenCheck_thenCompilesImplicitSource() throws IOException {
        write("user/src/implicit/java/com/pawbridge/backend/user/internal/domain/UserStatus.java", """
                package com.pawbridge.backend.user.internal.domain;
                public enum UserStatus { ACTIVE }
                """);
        javaSource("user", "internal.domain.UserState", """
                public class UserState { UserStatus status = UserStatus.ACTIVE; }
                """);
        appendModuleBuild("user", "tasks.named('compileJava') { options.sourcepath = files('src/implicit/java') }");

        BuildResult result = runner("check").build();

        assertTask(result, ":verifyArchitecture", SUCCESS);
        assertTask(result, ":verifyBusinessPackages", SUCCESS);
        assertTask(result, ":user:compileJava", SUCCESS);
        assertCompiled("user", "internal.domain.UserStatus");
        assertCompiled("user", "internal.domain.UserState");
    }

    @Test
    void givenSiblingResourceCopyInput_whenCheck_thenRejects() throws IOException {
        write("../store-service/src/main/resources/store-manifest.sql", "select 1;\n");
        appendModuleBuild("boot", """
                tasks.named('processResources') {
                    from(rootProject.file('../store-service/src/main/resources/store-manifest.sql'))
                }
                """);

        assertGraphRejected(runner("check").buildAndFail(),
                ":boot production resource inputs must stay inside the module");
    }

    private void disconnectCommunityFromUser() throws IOException {
        // Keep the output-ownership fixture acyclic. The independent graph-cycle
        // cases above exercise the declaration preflight before task graph creation.
        appendModuleBuild("community", """
                configurations.implementation.dependencies.clear()
                configurations.compileOnly.dependencies.clear()
                """);
        javaSource("community", "internal.application.PostReader", "public class PostReader {}");
    }

    @Test
    void givenCombinedAnimalAndStore_whenRootCheck_thenCompileAllFiveModulesAndBothBootAssemblies() throws IOException {
        javaSource("boot", "CombinedAssembly", """
                public class CombinedAssembly {
                    private final com.pawbridge.backend.animal.internal.application.AnimalReader animal =
                        new com.pawbridge.backend.animal.internal.application.AnimalReader();
                    private final com.pawbridge.backend.store.internal.application.StoreReader store =
                        new com.pawbridge.backend.store.internal.application.StoreReader();
                    public String description() { return animal.breed() + store.breed(); }
                }
                """);

        BuildResult result = runner("check").build();

        assertTask(result, ":verifyArchitecture", SUCCESS);
        assertTask(result, ":verifyBusinessPackages", SUCCESS);
        for (String module : MODULES) {
            assertTask(result, ":" + module + ":compileJava", SUCCESS);
            assertCheckCompleted(result, ":" + module + ":check");
        }
        assertCheckCompleted(result, ":check");
        assertCompiled("animal", "internal.application.AnimalReader");
        assertCompiled("store", "internal.application.StoreReader");
        assertCompiled("boot", "CombinedAssembly");
    }

    @Test
    void givenCombinedAnimalReferencesStore_whenAnimalCheck_thenRejectCrossModuleDependency() throws IOException {
        appendModuleBuild("animal", "dependencies { implementation project(':store') }");

        assertGraphRejected(runner(":animal:check").buildAndFail(),
                "forbidden project dependency :animal -> :store");
    }

    @Test
    void givenCombinedStoreReferencesAnimal_whenStoreCheck_thenRejectCrossModuleDependency() throws IOException {
        appendModuleBuild("store", "dependencies { implementation project(':animal') }");

        assertGraphRejected(runner(":store:check").buildAndFail(),
                "forbidden project dependency :store -> :animal");
    }

}
