package com.pawbridge.backend.architecture;

import org.gradle.testkit.runner.BuildResult;
import org.gradle.testkit.runner.GradleRunner;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.io.TempDir;

import javax.tools.JavaCompiler;
import javax.tools.ToolProvider;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;

import static org.gradle.testkit.runner.TaskOutcome.FAILED;
import static org.gradle.testkit.runner.TaskOutcome.SUCCESS;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

abstract class BusinessBuildFixture {
    static final List<String> MODULES = List.of("boot", "user", "community", "animal", "store", "payment");

    @TempDir
    static Path testKitDirectory;

    @TempDir
    Path temporaryDirectory;

    Path fixture;

    @BeforeEach
    void createBusinessBuild() throws IOException {
        fixture = Files.createDirectory(temporaryDirectory.resolve("backend-app"));
        write("settings.gradle", """
                rootProject.name = 'pawbridge-backend-app'
                include 'boot', 'user', 'community', 'animal', 'store', 'payment'
                """);
        write("gradle.properties", """
                org.gradle.jvmargs=-Xmx192m -Dfile.encoding=UTF-8
                org.gradle.workers.max=1
                org.gradle.parallel=false
                org.gradle.java.installations.auto-download=false
                """);
        write("build.gradle", """
                plugins {
                    id 'base'
                    id 'org.springframework.boot' version '3.4.11' apply false
                }
                allprojects {
                    repositories {
                        maven { url = rootProject.uri('repository') }
                        mavenCentral()
                    }
                }
                subprojects {
                    plugins.withId('java') {
                        tasks.withType(JavaCompile).configureEach { options.release = 21 }
                    }
                }
                apply from: 'gradle/architecture.gradle'
                tasks.named('check') {
                    dependsOn subprojects.collect { "${it.path}:check" }
                }
                """);
        write("boot/build.gradle", """
                plugins { id 'java-library' }
                dependencies {
                    implementation project(':user')
                    implementation project(':community')
                    implementation project(':animal')
                    implementation project(':store')
                    implementation project(':payment')
                }
                """);

        Path guard = Path.of(Objects.requireNonNull(
                System.getProperty("architecture.guard.file"), "architecture.guard.file"));
        Path productionBuild = guard.getParent().getParent();
        Files.createDirectories(fixture.resolve("gradle"));
        Files.copy(guard, fixture.resolve("gradle/architecture.gradle"));
        // Apply the actual business module scripts and production guard entry. The
        // boot fixture is a plain Java consumer so it cannot recursively run TestKit.
        for (String module : List.of("user", "community", "animal", "store", "payment")) {
            Files.createDirectories(fixture.resolve(module));
            Files.copy(productionBuild.resolve(module + "/build.gradle"),
                    fixture.resolve(module + "/build.gradle"));
        }

        javaSource("user", "api.UserLookup", """
                public interface UserLookup { String displayName(String userId); }
                """);
        javaSource("user", "internal.domain.UserName", """
                public record UserName(String value) {}
                """);
        javaSource("user", "internal.application.UserService", """
                public class UserService {
                    private final com.pawbridge.backend.user.internal.domain.UserName name =
                        new com.pawbridge.backend.user.internal.domain.UserName("PawBridge");
                    public String name() { return name.value(); }
                }
                """);
        javaSource("community", "api.PostSummary", """
                public record PostSummary(String title) {}
                """);
        javaSource("community", "internal.domain.PostTitle", """
                public record PostTitle(String value) {}
                """);
        javaSource("community", "internal.application.PostReader", """
                public class PostReader {
                    private final com.pawbridge.backend.user.api.UserLookup users;
                    public PostReader(com.pawbridge.backend.user.api.UserLookup users) { this.users = users; }
                    public String authorName(String id) { return users.displayName(id); }
                }
                """);
        javaSource("animal", "api.AnimalLookup", """
                public interface AnimalLookup { String breed(long animalId); }
                """);
        javaSource("animal", "internal.domain.AnimalName", """
                public record AnimalName(String value) {}
                """);
        javaSource("animal", "internal.application.AnimalReader", """
                public class AnimalReader {
                    private final com.pawbridge.backend.animal.internal.domain.AnimalName name =
                        new com.pawbridge.backend.animal.internal.domain.AnimalName("PawBridge");
                    public String breed() { return name.value(); }
                }
                """);
        javaSource("store", "api.StoreLookup", """
                public interface StoreLookup { String breed(long storeId); }
                """);
        javaSource("store", "internal.domain.StoreName", """
                public record StoreName(String value) {}
                """);
        javaSource("store", "internal.application.StoreReader", """
                public class StoreReader {
                    private final com.pawbridge.backend.store.internal.domain.StoreName name =
                        new com.pawbridge.backend.store.internal.domain.StoreName("PawBridge");
                    public String breed() { return name.value(); }
                }
                """);
        javaSource("payment", "api.PaymentSummary", "public record PaymentSummary(String status) {}");
        javaSource("payment", "internal.application.PaymentReader", """
                public class PaymentReader {
                    private com.pawbridge.backend.store.api.StoreLookup store;
                }
                """);
    }

    GradleRunner runner(String task) {
        Path gradleHome = Path.of(Objects.requireNonNull(
                System.getProperty("test.gradle.home"), "test.gradle.home"));
        String userHome = Objects.requireNonNull(System.getProperty("architecture.gradle.user.home"),
                "architecture.gradle.user.home");
        // Reuse the outer build's already-resolved plugin/ArchUnit cache. Offline
        // fixtures must not download a distribution, plugin, or technical library.
        return GradleRunner.create()
                .withProjectDir(fixture.toFile())
                .withGradleInstallation(gradleHome.toFile())
                .withTestKitDir(testKitDirectory.toFile())
                .withArguments(task, "--offline", "--gradle-user-home", userHome,
                        "--max-workers=1", "--no-parallel", "--stacktrace");
    }

    void javaSource(String module, String relativeType, String declaration) throws IOException {
        String qualifiedName = "com.pawbridge.backend." + module + "." + relativeType;
        int separator = qualifiedName.lastIndexOf('.');
        write(module + "/src/main/java/" + qualifiedName.replace('.', '/') + ".java",
                "package " + qualifiedName.substring(0, separator) + ";\n" + declaration);
    }

    void appendModuleBuild(String module, String content) throws IOException {
        append(module + "/build.gradle", content);
    }

    void append(String relative, String content) throws IOException {
        Files.writeString(fixture.resolve(relative), "\n" + content + "\n", StandardOpenOption.APPEND);
    }

    void write(String relative, String content) throws IOException {
        Path file = fixture.resolve(relative);
        Files.createDirectories(file.getParent());
        Files.writeString(file, content);
    }

    void createEmptyJar(String relative) throws IOException {
        Path file = fixture.resolve(relative);
        Files.createDirectories(file.getParent());
        try (JarOutputStream ignored = new JarOutputStream(Files.newOutputStream(file))) {
            // A valid artifact for file ownership checks; no fake business classes.
        }
    }

    void technicalDeclarations(String module, Map<String, String> declarations) throws IOException {
        Path sources = Files.createDirectories(temporaryDirectory.resolve("technical-markers/src"));
        Path classes = Files.createDirectories(temporaryDirectory.resolve("technical-markers/classes"));
        List<String> arguments = new ArrayList<>(List.of("--release", "21", "-d", classes.toString()));
        for (Map.Entry<String, String> declaration : declarations.entrySet()) {
            String name = declaration.getKey();
            int separator = name.lastIndexOf('.');
            Path source = sources.resolve(name.replace('.', '/') + ".java");
            Files.createDirectories(source.getParent());
            Files.writeString(source, "package " + name.substring(0, separator) + ";\n" + declaration.getValue());
            arguments.add(source.toString());
        }
        JavaCompiler compiler = ToolProvider.getSystemJavaCompiler();
        assertNotNull(compiler, "Technical declaration fixture preparation requires a JDK compiler");
        assertEquals(0, compiler.run(null, null, null, arguments.toArray(String[]::new)),
                "Technical declarations must compile before the guarded fixture build starts");
        Path jar = fixture.resolve("repository/technical-markers.jar");
        Files.createDirectories(jar.getParent());
        try (JarOutputStream output = new JarOutputStream(Files.newOutputStream(jar));
             var classFiles = Files.walk(classes)) {
            for (Path classFile : classFiles.filter(Files::isRegularFile).toList()) {
                output.putNextEntry(new JarEntry(classes.relativize(classFile).toString().replace('\\', '/')));
                Files.copy(classFile, output);
                output.closeEntry();
            }
        }
        // These are namespace markers in an external fixture jar, not copies of
        // framework implementations or extra classes in business production output.
        appendModuleBuild(module, "dependencies { compileOnly files(rootProject.file('repository/technical-markers.jar')) }");
    }

    void assertCompiled(String module, String relativeType) {
        assertTrue(Files.isRegularFile(fixture.resolve(module + "/build/classes/java/main/"
                + "com/pawbridge/backend/" + module + "/" + relativeType.replace('.', '/') + ".class")));
    }

    static void assertTask(BuildResult result, String path, org.gradle.testkit.runner.TaskOutcome outcome) {
        assertNotNull(result.task(path), () -> "Missing task " + path + "\n" + result.getOutput());
        assertEquals(outcome, result.task(path).getOutcome(), result.getOutput());
    }

    static void assertCheckCompleted(BuildResult result, String path) {
        assertNotNull(result.task(path), () -> "Missing task " + path + "\n" + result.getOutput());
        var outcome = result.task(path).getOutcome();
        assertTrue(outcome == SUCCESS || outcome == org.gradle.testkit.runner.TaskOutcome.UP_TO_DATE,
                result.getOutput());
    }

    static void assertGraphRejected(BuildResult result, String reason) {
        // Direct graph cycles/root/composite/Boot violations can fail during the
        // shared preflight, before Gradle can create a task execution graph.
        assertTrue(result.getOutput().contains("Architecture violation: " + reason), result.getOutput());
        if (result.task(":verifyArchitecture") != null) {
            assertTask(result, ":verifyArchitecture", FAILED);
        }
    }

    static void assertPackageRejected(BuildResult result, String rule, String offendingType) {
        assertTask(result, ":verifyArchitecture", SUCCESS);
        assertTask(result, ":user:compileJava", SUCCESS);
        assertTask(result, ":community:compileJava", SUCCESS);
        assertTask(result, ":animal:compileJava", SUCCESS);
        assertTask(result, ":store:compileJava", SUCCESS);
        assertTask(result, ":verifyBusinessPackages", FAILED);
        String violationPrefix = "Business package violation: [" + rule + "] ";
        assertTrue(result.getOutput().lines().anyMatch(line -> {
                    int start = line.indexOf(violationPrefix);
                    if (start < 0) {
                        return false;
                    }
                    String detail = line.substring(start + violationPrefix.length());
                    if (rule.equals("BUSINESS_PACKAGE")) {
                        return detail.contains(offendingType);
                    }
                    int target = detail.indexOf(" -> ");
                    return target >= 0 && detail.substring(target + 4).startsWith(offendingType + ": ");
                }),
                result.getOutput());
    }
}
