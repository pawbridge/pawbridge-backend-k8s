package com.pawbridge.backend.architecture;

import org.gradle.testkit.runner.BuildResult;
import org.gradle.testkit.runner.GradleRunner;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Objects;
import java.util.jar.JarOutputStream;

import static org.gradle.testkit.runner.TaskOutcome.FAILED;
import static org.gradle.testkit.runner.TaskOutcome.SUCCESS;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class GradleArchitectureTest {
    private static final List<String> MODULES =
            List.of("boot", "ui", "application", "domain", "infrastructure");

    @TempDir
    static Path testKitDirectory;

    @TempDir
    Path temporaryDirectory;

    private Path fixture;

    @BeforeEach
    void createFiveLayerBuild() throws IOException {
        fixture = Files.createDirectory(temporaryDirectory.resolve("backend-app"));
        write("settings.gradle", """
                rootProject.name = 'pawbridge-backend-app'
                include 'boot', 'ui', 'application', 'domain', 'infrastructure'
                """);
        write("gradle.properties", """
                org.gradle.jvmargs=-Xmx192m -Dfile.encoding=UTF-8
                org.gradle.workers.max=1
                org.gradle.parallel=false
                org.gradle.java.installations.auto-download=false
                """);
        write("build.gradle", """
                plugins { id 'base' }
                subprojects {
                    apply plugin: 'java-library'
                    repositories { maven { url = rootProject.uri('repository') } }
                }
                apply from: 'gradle/architecture.gradle'
                tasks.named('check') {
                    dependsOn subprojects.collect { "${it.path}:check" }
                }
                """);
        for (String module : MODULES) {
            write(module + "/build.gradle", "");
        }
        Path guard = Path.of(Objects.requireNonNull(
                System.getProperty("architecture.guard.file"), "architecture.guard.file"));
        Files.createDirectories(fixture.resolve("gradle"));
        Files.copy(guard, fixture.resolve("gradle/architecture.gradle"));
    }

    @Test
    void givenAllowedGraphAndTestOnlyDomainJar_whenCheck_thenPasses() throws IOException {
        write("boot/build.gradle", """
                dependencies {
                    implementation project(':ui')
                    implementation project(':application')
                    implementation project(':infrastructure')
                    implementation project(':domain')
                }
                """);
        write("ui/build.gradle", """
                dependencies {
                    implementation project(':application')
                    implementation project(':domain')
                }
                """);
        write("application/build.gradle", "dependencies { implementation project(':domain') }");
        write("infrastructure/build.gradle", "dependencies { implementation project(':domain') }");
        createJar("local.jar");
        write("domain/build.gradle", "dependencies { testImplementation files(rootProject.file('local.jar')) }");

        BuildResult result = runner("check").build();

        assertNotNull(result.task(":verifyArchitecture"));
        assertEquals(SUCCESS, result.task(":verifyArchitecture").getOutcome());
        assertEquals(SUCCESS, result.task(":check").getOutcome());
        for (String module : MODULES) {
            assertNotNull(result.task(":" + module + ":check"));
        }
    }

    @ParameterizedTest
    @CsvSource({
            "domain, application", "domain, ui", "domain, infrastructure", "domain, boot",
            "application, ui", "application, infrastructure", "application, boot",
            "infrastructure, application", "infrastructure, ui", "infrastructure, boot",
            "ui, infrastructure", "ui, boot"
    })
    void givenForbiddenProjectEdge_whenCheck_thenRejects(String source, String target) throws IOException {
        write(source + "/build.gradle", "dependencies { implementation project(':" + target + "') }");

        assertRejected(runner("check").buildAndFail(),
                "forbidden project dependency :" + source + " -> :" + target);
    }

    @ParameterizedTest
    @ValueSource(strings = {"api", "compileOnly", "runtimeOnly", "annotationProcessor"})
    void givenForbiddenEdgeOnAlternateProductionPath_whenCheck_thenRejects(String configuration)
            throws IOException {
        write("application/build.gradle", "dependencies { " + configuration
                + " project(':infrastructure') }");

        assertRejected(runner("check").buildAndFail(),
                "forbidden project dependency :application -> :infrastructure");
    }

    @ParameterizedTest
    @ValueSource(strings = {"implementation", "compileOnly", "runtimeOnly", "annotationProcessor"})
    void givenDomainProductionFileJar_whenCheck_thenRejects(String configuration) throws IOException {
        createJar("local.jar");
        write("domain/build.gradle", "dependencies { " + configuration
                + " files(rootProject.file('local.jar')) }");

        assertRejected(runner("check").buildAndFail(), "domain production dependencies must be JDK-only");
    }

    @Test
    void givenDomainResolvedExternalJar_whenCheck_thenRejects() throws IOException {
        createLocalModule("fixture", "library", "");
        write("domain/build.gradle", "dependencies { implementation 'fixture:library:1.0' }");

        assertRejected(runner("check").buildAndFail(), "domain production dependencies must be JDK-only");
    }

    @Test
    void givenTransitiveProjectSubstitution_whenCheck_thenRejects() throws IOException {
        createLocalModule("fixture", "bridge", """
                <dependencies><dependency>
                  <groupId>fixture</groupId><artifactId>infrastructure</artifactId><version>1.0</version>
                </dependency></dependencies>
                """);
        Files.writeString(fixture.resolve("build.gradle"), """
                subprojects {
                    configurations.configureEach {
                        resolutionStrategy.dependencySubstitution {
                            substitute module('fixture:infrastructure') using project(':infrastructure')
                        }
                    }
                }
                """, java.nio.file.StandardOpenOption.APPEND);
        write("application/build.gradle", "dependencies { implementation 'fixture:bridge:1.0' }");

        assertRejected(runner("check").buildAndFail(), "external dependency imports project");
    }

    @Test
    void givenOtherModuleOutputAsFiles_whenCheck_thenRejects() throws IOException {
        write("application/build.gradle", """
                dependencies {
                    implementation files(project(':infrastructure').layout.buildDirectory.dir('classes/java/main'))
                }
                """);

        assertRejected(runner("check").buildAndFail(),
                "forbidden project dependency :application -> :infrastructure through files");
    }

    @ParameterizedTest
    @ValueSource(strings = {"sourceSets.main.output", "tasks.named('jar')"})
    void givenRelocatedForbiddenModuleOutputAsFiles_whenCheck_thenRejects(String output) throws IOException {
        write("infrastructure/build.gradle", """
                layout.buildDirectory = rootProject.layout.buildDirectory.dir('infrastructure')
                """);
        write("infrastructure/src/main/java/fixture/InfrastructureMarker.java", """
                package fixture;
                public class InfrastructureMarker {}
                """);
        write("application/build.gradle", """
                evaluationDependsOn(':infrastructure')
                dependencies {
                    implementation files(project(':infrastructure').%s)
                }
                """.formatted(output));
        write("application/src/main/java/fixture/ApplicationMarker.java", """
                package fixture;
                class ApplicationMarker { InfrastructureMarker marker; }
                """);

        assertRejected(runner("check").buildAndFail(),
                "forbidden project dependency :application -> :infrastructure through files");
    }

    @ParameterizedTest
    @ValueSource(strings = {"sourceSets.main.output", "tasks.named('jar')"})
    void givenRelocatedAllowedDomainOutputAsFiles_whenCheck_thenCompiles(String output) throws IOException {
        write("domain/build.gradle", """
                layout.buildDirectory = rootProject.layout.buildDirectory.dir('domain')
                """);
        write("domain/src/main/java/fixture/DomainMarker.java", """
                package fixture;
                public class DomainMarker {}
                """);
        write("application/build.gradle", """
                evaluationDependsOn(':domain')
                dependencies {
                    implementation files(project(':domain').%s)
                }
                """.formatted(output));
        write("application/src/main/java/fixture/ApplicationMarker.java", """
                package fixture;
                class ApplicationMarker { DomainMarker marker; }
                """);

        BuildResult result = runner("check").build();

        assertEquals(SUCCESS, result.task(":verifyArchitecture").getOutcome());
        assertEquals(SUCCESS, result.task(":application:compileJava").getOutcome());
        assertTrue(Files.exists(fixture.resolve("build/domain/classes/java/main/fixture/DomainMarker.class")));
    }

    @Test
    void givenSharedOutputDirectoryOwners_whenCheck_thenRejectsAmbiguousOwnership() throws IOException {
        write("domain/build.gradle", """
                layout.buildDirectory = rootProject.layout.buildDirectory.dir('shared')
                """);
        write("infrastructure/build.gradle", """
                layout.buildDirectory = rootProject.layout.buildDirectory.dir('shared')
                """);
        createJar("build/shared/libs/shared.jar");
        write("application/build.gradle", """
                dependencies {
                    implementation files(rootProject.file('build/shared/libs/shared.jar'))
                }
                """);

        assertRejected(runner("check").buildAndFail(),
                ":application production classpath has ambiguous output ownership");
    }

    @Test
    void givenSiblingServiceProductionClasspath_whenCheck_thenRejects() throws IOException {
        createJar("../store-service/build/libs/store-service.jar");
        write("application/build.gradle", """
                dependencies {
                    runtimeOnly files(rootProject.file('../store-service/build/libs/store-service.jar'))
                }
                """);

        assertRejected(runner("check").buildAndFail(),
                ":application production classpath references a sibling service");
    }

    @Test
    void givenDomainOverriddenMainClasspath_whenCheck_thenRejects() throws IOException {
        createJar("local.jar");
        write("domain/build.gradle", "sourceSets.main.compileClasspath += files(rootProject.file('local.jar'))");

        assertRejected(runner("check").buildAndFail(), "domain production classpaths must be JDK-only");
    }

    @ParameterizedTest
    @ValueSource(strings = {"classpath", "options.annotationProcessorPath"})
    void givenDomainCompilerInputOverride_whenCheck_thenRejects(String input) throws IOException {
        createJar("local.jar");
        write("domain/build.gradle", "tasks.named('compileJava') { " + input
                + " = files(rootProject.file('local.jar')) }");

        assertRejected(runner("check").buildAndFail(), "domain production classpaths must be JDK-only");
    }

    @Test
    void givenRootProjectDependency_whenCheck_thenRejects() throws IOException {
        Files.writeString(fixture.resolve("build.gradle"), "apply plugin: 'java-library'\n",
                java.nio.file.StandardOpenOption.APPEND);
        write("boot/build.gradle", "dependencies { implementation project(':') }");

        assertRejected(runner("check").buildAndFail(),
                "root project is a build aggregator and must not apply Java production plugins");
    }

    @Test
    void givenRootJavaOutputAsFiles_whenCheck_thenRejects() throws IOException {
        Files.writeString(fixture.resolve("build.gradle"), "apply plugin: 'java-library'\n",
                java.nio.file.StandardOpenOption.APPEND);
        write("application/build.gradle", "dependencies { implementation files(rootProject.sourceSets.main.output) }");

        assertRejected(runner("check").buildAndFail(),
                "root project is a build aggregator and must not apply Java production plugins");
    }

    @Test
    void givenUnknownProject_whenCheck_thenRejects() throws IOException {
        Files.writeString(fixture.resolve("settings.gradle"), "include 'legacy'\n",
                java.nio.file.StandardOpenOption.APPEND);
        write("legacy/build.gradle", "");

        assertRejected(runner("check").buildAndFail(), "only boot, ui, application, domain and infrastructure");
    }

    @Test
    void givenReassignedProjectDirectory_whenCheck_thenRejects() throws IOException {
        write("legacy-application/build.gradle", "");
        Files.writeString(fixture.resolve("settings.gradle"),
                "project(':application').projectDir = file('legacy-application')\n",
                java.nio.file.StandardOpenOption.APPEND);

        assertRejected(runner("check").buildAndFail(),
                ":application must use its own application directory");
    }

    @Test
    void givenForbiddenProjectEdge_whenOnlyApplicationCheck_thenRunsGuardAndRejects() throws IOException {
        write("application/build.gradle", "dependencies { implementation project(':infrastructure') }");

        BuildResult result = runner(":application:check").buildAndFail();

        assertRejected(result, "forbidden project dependency :application -> :infrastructure");
        assertEquals(FAILED, result.task(":verifyArchitecture").getOutcome());
    }

    @Test
    void givenCompositeBuild_whenCheck_thenRejects() throws IOException {
        write("legacy/settings.gradle", "rootProject.name = 'legacy'");
        write("legacy/build.gradle", "plugins { id 'java-library' }");
        Files.writeString(fixture.resolve("settings.gradle"), "includeBuild 'legacy'\n",
                java.nio.file.StandardOpenOption.APPEND);

        assertRejected(runner("check").buildAndFail(), "included builds are not allowed");
    }

    @Test
    void givenSourcesOutsideModule_whenCheck_thenRejects() throws IOException {
        write("application/build.gradle", "sourceSets.main.java.srcDir rootProject.file('legacy/src')");

        assertRejected(runner("check").buildAndFail(), ":application production sources must stay inside the module");
    }

    @Test
    void givenCompilerSourceOutsideModule_whenCheck_thenRejects() throws IOException {
        write("../store-service/src/main/java/Legacy.java", "class Legacy {}\n");
        write("application/build.gradle", """
                tasks.named('compileJava') {
                    source rootProject.file('../store-service/src/main/java/Legacy.java')
                }
                """);

        assertRejected(runner("check").buildAndFail(),
                ":application production compiler sources must stay inside the module");
    }

    @Test
    void givenSiblingServiceCompilerSourcepath_whenCheck_thenRejects() throws IOException {
        write("../store-service/src/main/java/com/pawbridge/storeservice/domain/product/entity/ProductStatus.java", """
                package com.pawbridge.storeservice.domain.product.entity;
                public enum ProductStatus { ACTIVE }
                """);
        write("domain/src/main/java/fixture/DomainProduct.java", """
                package fixture;
                import com.pawbridge.storeservice.domain.product.entity.ProductStatus;
                class DomainProduct { ProductStatus status = ProductStatus.ACTIVE; }
                """);
        write("domain/build.gradle", """
                tasks.named('compileJava') {
                    options.sourcepath = files(rootProject.file('../store-service/src/main/java'))
                }
                """);

        assertRejected(runner("check").buildAndFail(),
                ":domain production compiler sourcepath must stay inside the module");
    }

    @Test
    void givenModuleLocalCompilerSourcepath_whenCheck_thenCompilesImplicitSource() throws IOException {
        write("domain/src/implicit/java/fixture/DomainStatus.java", """
                package fixture;
                public enum DomainStatus { ACTIVE }
                """);
        write("domain/src/main/java/fixture/DomainProduct.java", """
                package fixture;
                class DomainProduct { DomainStatus status = DomainStatus.ACTIVE; }
                """);
        write("domain/build.gradle", """
                tasks.named('compileJava') {
                    options.sourcepath = files('src/implicit/java')
                }
                """);

        BuildResult result = runner("check").build();

        assertEquals(SUCCESS, result.task(":verifyArchitecture").getOutcome());
        assertEquals(SUCCESS, result.task(":domain:compileJava").getOutcome());
        assertTrue(Files.exists(fixture.resolve("domain/build/classes/java/main/fixture/DomainStatus.class")));
    }

    @Test
    void givenSiblingServiceResourceCopyInput_whenCheck_thenRejects() throws IOException {
        write("../store-service/src/main/resources/store-es-validation-manifest.sql", "select 1;\n");
        write("boot/build.gradle", """
                tasks.named('processResources') {
                    from(rootProject.file('../store-service/src/main/resources/store-es-validation-manifest.sql'))
                }
                """);

        assertRejected(runner("check").buildAndFail(),
                ":boot production resource inputs must stay inside the module");
    }

    @Test
    void givenModuleLocalResourceCopyInput_whenCheck_thenCopiesResource() throws IOException {
        write("boot/config/fixture.properties", "fixture.enabled=true\n");
        write("boot/build.gradle", """
                tasks.named('processResources') {
                    from(project.file('config/fixture.properties'))
                }
                """);

        BuildResult result = runner("check").build();

        assertEquals(SUCCESS, result.task(":verifyArchitecture").getOutcome());
        assertEquals("fixture.enabled=true\n",
                Files.readString(fixture.resolve("boot/build/resources/main/fixture.properties")));
    }

    private GradleRunner runner(String task) {
        Path gradleHome = Path.of(Objects.requireNonNull(
                System.getProperty("test.gradle.home"), "test.gradle.home"));
        // TestKit uses dedicated daemons. Reuse one small installation-backed daemon
        // sequentially instead of downloading distributions or using the host's Gradle home.
        return GradleRunner.create()
                .withProjectDir(fixture.toFile())
                .withGradleInstallation(gradleHome.toFile())
                .withTestKitDir(testKitDirectory.toFile())
                .withArguments(task, "--offline", "--max-workers=1", "--no-parallel", "--stacktrace");
    }

    private static void assertRejected(BuildResult result, String reason) {
        assertTrue(result.getOutput().contains("Architecture violation: " + reason), result.getOutput());
    }

    private void createLocalModule(String group, String artifact, String extraPom) throws IOException {
        String directory = "repository/" + group.replace('.', '/') + "/" + artifact + "/1.0/";
        createJar(directory + artifact + "-1.0.jar");
        write(directory + artifact + "-1.0.pom", """
                <project>
                  <modelVersion>4.0.0</modelVersion>
                  <groupId>%s</groupId><artifactId>%s</artifactId><version>1.0</version>
                  %s
                </project>
                """.formatted(group, artifact, extraPom));
    }

    private void createJar(String relative) throws IOException {
        Path file = fixture.resolve(relative);
        Files.createDirectories(file.getParent());
        try (JarOutputStream ignored = new JarOutputStream(Files.newOutputStream(file))) {
            // Empty but valid local artifact; no compiler, network or application code.
        }
    }

    private void write(String relative, String content) throws IOException {
        Path file = fixture.resolve(relative);
        Files.createDirectories(file.getParent());
        Files.writeString(file, content);
    }
}
