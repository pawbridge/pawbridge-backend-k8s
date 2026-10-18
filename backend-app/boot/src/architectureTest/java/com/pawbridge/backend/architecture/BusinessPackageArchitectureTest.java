package com.pawbridge.backend.architecture;

import org.gradle.testkit.runner.BuildResult;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.io.IOException;
import java.util.Map;

import static org.gradle.testkit.runner.TaskOutcome.SUCCESS;

class BusinessPackageArchitectureTest extends BusinessBuildFixture {
    @ParameterizedTest
    @ValueSource(strings = {"check", ":user:check", ":community:check", ":boot:check"})
    void givenLegalBusinessClasses_whenRootOrIndividualCheck_thenRunsPackageGuard(String task) throws IOException {
        javaSource("user", "internal.profile.domain.UserProfile", """
                public record UserProfile(com.pawbridge.backend.user.internal.domain.UserName name,
                        java.util.UUID id, java.time.Instant createdAt, java.math.BigDecimal score) {}
                """);
        javaSource("user", "internal.domain.Identifier", """
                public record Identifier<T extends Comparable<T>>(T value) {}
                """);
        javaSource("user", "internal.profile.application.ProfileReader", """
                public class ProfileReader {
                    private final com.pawbridge.backend.user.internal.profile.domain.UserProfile profile;
                    public ProfileReader(com.pawbridge.backend.user.internal.profile.domain.UserProfile profile) {
                        this.profile = profile;
                    }
                }
                """);
        javaSource("user", "internal.web.UserController", """
                public class UserController {
                    private final com.pawbridge.backend.user.internal.application.UserService users;
                    public UserController(com.pawbridge.backend.user.internal.application.UserService users) {
                        this.users = users;
                    }
                }
                """);
        javaSource("user", "internal.infrastructure.UserStore", """
                public class UserStore {
                    com.pawbridge.backend.user.internal.domain.UserName name;
                }
                """);

        BuildResult result = runner(task).build();

        assertTask(result, ":verifyArchitecture", SUCCESS);
        assertTask(result, ":verifyBusinessPackages", SUCCESS);
        assertTask(result, ":user:compileJava", SUCCESS);
        assertTask(result, ":community:compileJava", SUCCESS);
        assertCheckCompleted(result, task.equals("check") ? ":check" : task);
        assertCompiled("user", "internal.profile.application.ProfileReader");
        assertCompiled("community", "internal.application.PostReader");
    }

    @Test
    void givenBootCompositionRefersToBusinessInternal_whenCheck_thenCompiles() throws IOException {
        javaSource("boot", "Assembly", """
                public class Assembly {
                    private final com.pawbridge.backend.user.internal.application.UserService users =
                        new com.pawbridge.backend.user.internal.application.UserService();
                    public String displayName() { return users.name(); }
                }
                """);

        BuildResult result = runner("check").build();

        assertTask(result, ":verifyBusinessPackages", SUCCESS);
        assertTask(result, ":boot:compileJava", SUCCESS);
        assertCompiled("boot", "Assembly");
    }

    @Test
    void givenApplicationTransactionDeclarationWithEnumMetadata_whenCheck_thenCompiles() throws IOException {
        technicalDeclarations("user", Map.of(
                "org.springframework.transaction.annotation.Transactional", """
                        @java.lang.annotation.Retention(java.lang.annotation.RetentionPolicy.RUNTIME)
                        @java.lang.annotation.Target({java.lang.annotation.ElementType.TYPE, java.lang.annotation.ElementType.METHOD})
                        public @interface Transactional {
                            Propagation propagation() default Propagation.REQUIRED;
                            Isolation isolation() default Isolation.DEFAULT;
                        }
                        """,
                "org.springframework.transaction.annotation.Propagation", "public enum Propagation { REQUIRED, SUPPORTS }",
                "org.springframework.transaction.annotation.Isolation", "public enum Isolation { DEFAULT, READ_COMMITTED }"));
        javaSource("user", "internal.application.TransactionalUserService", """
                @org.springframework.transaction.annotation.Transactional(
                    propagation = org.springframework.transaction.annotation.Propagation.SUPPORTS,
                    isolation = org.springframework.transaction.annotation.Isolation.READ_COMMITTED)
                public class TransactionalUserService {
                    public String displayName() { return "PawBridge"; }
                }
                """);

        BuildResult result = runner("check").build();

        assertTask(result, ":verifyArchitecture", SUCCESS);
        assertTask(result, ":verifyBusinessPackages", SUCCESS);
        assertTask(result, ":user:compileJava", SUCCESS);
        assertCompiled("user", "internal.application.TransactionalUserService");
    }

    @Test
    void givenCommunityReferencesUserInternal_whenCheck_thenRejectsBusinessInternalRule() throws IOException {
        javaSource("community", "internal.application.InternalUserReader", """
                public class InternalUserReader {
                    com.pawbridge.backend.user.internal.application.UserService users;
                }
                """);

        BuildResult result = runner("check").buildAndFail();

        assertPackageRejected(result, "BUSINESS_INTERNAL",
                "com.pawbridge.backend.user.internal.application.UserService");
        assertCompiled("community", "internal.application.InternalUserReader");
    }

    @ParameterizedTest
    @ValueSource(strings = {"return", "field", "generic", "generic-array-return", "generic-array-parameter",
            "generic-array-field", "throws", "inheritance"})
    void givenApiLeaksOwnInternalType_whenCheck_thenRejectsApiSignatureRule(String signature) throws IOException {
        javaSource("user", "internal.domain.InternalUserProblem", """
                public class InternalUserProblem extends RuntimeException {}
                """);
        javaSource("user", "internal.domain.InternalUserBase", """
                public class InternalUserBase {}
                """);
        String declaration = switch (signature) {
            case "return" -> """
                    public class LeakyContract {
                        public com.pawbridge.backend.user.internal.domain.UserName name() { return null; }
                    }
                    """;
            case "field" -> """
                    public class LeakyContract {
                        public com.pawbridge.backend.user.internal.domain.UserName name;
                    }
                    """;
            case "generic" -> """
                    public class LeakyContract {
                        public java.util.List<java.util.Map<String, com.pawbridge.backend.user.internal.domain.UserName>> names() {
                            return java.util.List.of();
                        }
                    }
                    """;
            case "generic-array-return" -> """
                    public class LeakyContract {
                        public java.util.List<com.pawbridge.backend.user.internal.domain.UserName>[] names() {
                            return null;
                        }
                    }
                    """;
            case "generic-array-parameter" -> """
                    public class LeakyContract {
                        public void execute(java.util.List<com.pawbridge.backend.user.internal.domain.UserName>[] names) {}
                    }
                    """;
            case "generic-array-field" -> """
                    public class LeakyContract {
                        public java.util.List<com.pawbridge.backend.user.internal.domain.UserName>[] names;
                    }
                    """;
            case "throws" -> """
                    public class LeakyContract {
                        public void execute() throws com.pawbridge.backend.user.internal.domain.InternalUserProblem {}
                    }
                    """;
            case "inheritance" -> """
                    public class LeakyContract extends com.pawbridge.backend.user.internal.domain.InternalUserBase {}
                    """;
            default -> throw new IllegalArgumentException(signature);
        };
        javaSource("user", "api.LeakyContract", declaration);

        BuildResult result = runner("check").buildAndFail();

        assertPackageRejected(result, "API_SIGNATURE", "com.pawbridge.backend.user.api.LeakyContract");
        assertCompiled("user", "api.LeakyContract");
    }

    @Test
    void givenApiCarriesSpringAnnotation_whenCheck_thenRejectsApiSignatureRule() throws IOException {
        technicalDeclarations("user", Map.of("org.springframework.stereotype.Component", "public @interface Component {}"));
        javaSource("user", "api.AnnotatedContract", """
                @org.springframework.stereotype.Component
                public interface AnnotatedContract { String name(); }
                """);

        BuildResult result = runner("check").buildAndFail();

        assertPackageRejected(result, "API_SIGNATURE", "org.springframework.stereotype.Component");
        assertCompiled("user", "api.AnnotatedContract");
    }

    @ParameterizedTest
    @CsvSource({
            "user, org.springframework.stereotype.Component",
            "user, jakarta.persistence.Entity",
            "user, java.sql.Connection",
            "user, javax.sql.DataSource",
            "user, java.net.http.HttpClient",
            "user, com.pawbridge.backend.user.api.UserLookup",
            "community, com.pawbridge.backend.user.api.UserLookup"
    })
    void givenDomainReferencesTechnologyOrApi_whenCheck_thenRejectsDomainRule(String module, String target)
            throws IOException {
        if (target.equals("org.springframework.stereotype.Component")) {
            technicalDeclarations(module, Map.of(target, "public @interface Component {}"));
        } else if (target.equals("jakarta.persistence.Entity")) {
            technicalDeclarations(module, Map.of(target, "public @interface Entity {}"));
        }
        javaSource(module, "internal.domain.TechnicalDomain", "public class TechnicalDomain { " + target + " dependency; }");

        BuildResult result = runner("check").buildAndFail();

        assertPackageRejected(result, "DOMAIN_JDK_ONLY", target);
        assertCompiled(module, "internal.domain.TechnicalDomain");
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "com.pawbridge.backend.user.internal.web.UserController",
            "com.pawbridge.backend.user.internal.infrastructure.UserStore",
            "org.springframework.web.bind.annotation.RestController",
            "jakarta.persistence.Entity",
            "org.springframework.data.repository.Repository",
            "org.springframework.cloud.openfeign.FeignClient",
            "java.sql.Connection",
            "javax.sql.DataSource",
            "java.net.http.HttpClient",
            "org.springframework.transaction.support.TransactionTemplate",
            "org.springframework.transaction.annotation.EnableTransactionManagement"
    })
    void givenApplicationReferencesAdapterOrTechnology_whenCheck_thenRejectsApplicationRule(String target)
            throws IOException {
        switch (target) {
            case "com.pawbridge.backend.user.internal.web.UserController" ->
                    javaSource("user", "internal.web.UserController", "public class UserController {}");
            case "com.pawbridge.backend.user.internal.infrastructure.UserStore" ->
                    javaSource("user", "internal.infrastructure.UserStore", "public class UserStore {}");
            case "org.springframework.web.bind.annotation.RestController" ->
                    technicalDeclarations("user", Map.of(target, "public @interface RestController {}"));
            case "jakarta.persistence.Entity" ->
                    technicalDeclarations("user", Map.of(target, "public @interface Entity {}"));
            case "org.springframework.data.repository.Repository" ->
                    technicalDeclarations("user", Map.of(target, "public interface Repository<T, ID> {}"));
            case "org.springframework.cloud.openfeign.FeignClient" ->
                    technicalDeclarations("user", Map.of(target, "public @interface FeignClient {}"));
            case "org.springframework.transaction.support.TransactionTemplate" ->
                    technicalDeclarations("user", Map.of(target, "public class TransactionTemplate {}"));
            case "org.springframework.transaction.annotation.EnableTransactionManagement" ->
                    technicalDeclarations("user", Map.of(target, "public @interface EnableTransactionManagement {}"));
            default -> { /* JDK types use the actual platform classes. */ }
        }
        javaSource("user", "internal.application.TechnicalApplication",
                "public class TechnicalApplication { " + target + " dependency; }");

        BuildResult result = runner("check").buildAndFail();

        assertPackageRejected(result, "APPLICATION_DEPENDENCY", target);
        assertCompiled("user", "internal.application.TechnicalApplication");
    }

    @Test
    void givenBusinessClassOutsideApiOrInternalRole_whenCheck_thenRejectsPackageRule() throws IOException {
        javaSource("user", "legacy.LegacyUser", "public class LegacyUser {}");

        BuildResult result = runner("check").buildAndFail();

        assertPackageRejected(result, "BUSINESS_PACKAGE", "com.pawbridge.backend.user.legacy.LegacyUser");
        assertCompiled("user", "legacy.LegacyUser");
    }
}
