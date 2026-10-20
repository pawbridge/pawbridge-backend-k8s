package com.pawbridge.backend.compatibility;

import com.pawbridge.userservice.dto.request.UpdateNicknameRequestDto;
import com.pawbridge.userservice.dto.request.AdminUserUpdateRequest;
import com.pawbridge.userservice.entity.User;
import com.pawbridge.userservice.repository.UserRepository;
import com.pawbridge.userservice.service.F13LegacyNicknameService;
import com.pawbridge.userservice.service.F13LegacyAdminUserUpdateService;
import jakarta.persistence.EntityManager;
import jakarta.persistence.EntityManagerFactory;
import java.lang.reflect.Proxy;
import java.time.Clock;
import java.time.LocalDateTime;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;
import javax.sql.DataSource;
import org.springframework.aop.support.AopUtils;
import org.springframework.boot.autoconfigure.ImportAutoConfiguration;
import org.springframework.boot.autoconfigure.domain.EntityScan;
import org.springframework.boot.autoconfigure.orm.jpa.HibernateJpaAutoConfiguration;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.MapPropertySource;
import org.springframework.data.auditing.DateTimeProvider;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.jpa.repository.config.EnableJpaAuditing;
import org.springframework.data.jpa.repository.support.SimpleJpaRepository;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.orm.jpa.SharedEntityManagerCreator;
import org.springframework.transaction.annotation.EnableTransactionManagement;

/** Verbatim original method behind a real Spring/JPA proxy; no catch is moved outside commit. */
final class F13LegacyNicknameOracle implements AutoCloseable {
    private final AnnotationConfigApplicationContext context = new AnnotationConfigApplicationContext();
    private final Runnable verifyOwnership;
    final AtomicInteger reads = new AtomicInteger();
    final AtomicInteger duplicateQueries = new AtomicInteger();
    final AtomicInteger saves = new AtomicInteger();
    private final F13LegacyNicknameService service;

    F13LegacyNicknameOracle(F3PostgresqlFixture fixture, Clock clock, Runnable verifyOwnership) {
        this(fixture, clock, verifyOwnership, () -> { }, () -> { });
    }

    F13LegacyNicknameOracle(F3PostgresqlFixture fixture, Clock clock, Runnable verifyOwnership,
                            Runnable afterRead, Runnable afterAvailable) {
        this.verifyOwnership = verifyOwnership;
        verifyOwnership.run();
        var admin = new DriverManagerDataSource(fixture.reads().jdbcUrl(),
                F3PostgresqlFixture.required("F2_TEST_ADMIN_USER"), F3PostgresqlFixture.required("F2_TEST_ADMIN_PASSWORD"));
        var options = new java.util.Properties(); options.setProperty("options", "-c statement_timeout=8000 -c lock_timeout=5000");
        options.setProperty("connectTimeout", "3"); options.setProperty("socketTimeout", "12");
        admin.setConnectionProperties(options);
        try {
            context.getEnvironment().getPropertySources().addFirst(new MapPropertySource("legacy-nickname-oracle", Map.of(
                    "spring.jpa.hibernate.ddl-auto", "none", "spring.jpa.open-in-view", "false",
                    "spring.jpa.properties.hibernate.default_schema", "pawbridge_user",
                    "spring.jpa.properties.hibernate.jdbc.time_zone", "UTC")));
            context.registerBean("dataSource", DataSource.class, () -> admin);
            context.registerBean("legacyNicknameAuditTime", DateTimeProvider.class,
                    () -> () -> Optional.of(LocalDateTime.now(clock)));
            context.registerBean(UserRepository.class, () -> {
                EntityManager em = SharedEntityManagerCreator.createSharedEntityManager(context.getBean(EntityManagerFactory.class));
                var repository = new SimpleJpaRepository<User, Long>(User.class, em);
                final String query;
                try { query = UserRepository.class.getMethod("existsByNickname", String.class).getAnnotation(Query.class).value(); }
                catch (ReflectiveOperationException failure) { throw new IllegalStateException(failure); }
                return (UserRepository) Proxy.newProxyInstance(UserRepository.class.getClassLoader(),
                        new Class<?>[] { UserRepository.class }, (proxy, method, args) -> {
                            return switch (method.getName()) {
                                case "findById" -> {
                                    reads.incrementAndGet();
                                    var user = repository.findById((Long) args[0]);
                                    afterRead.run();
                                    yield user;
                                }
                                case "findByIdForUpdate" -> {
                                    reads.incrementAndGet();
                                    var selected = UserRepository.class.getMethod("findByIdForUpdate", Long.class);
                                    var lock = selected.getAnnotation(org.springframework.data.jpa.repository.Lock.class);
                                    if (lock == null || lock.value() != jakarta.persistence.LockModeType.PESSIMISTIC_WRITE)
                                        throw new IllegalStateException("Original administrator lookup lacks its pessimistic lock");
                                    var users = em.createQuery(selected.getAnnotation(Query.class).value(), User.class)
                                            .setParameter("id", args[0]).setLockMode(lock.value()).getResultList();
                                    var user = users.stream().findFirst();
                                    afterRead.run();
                                    yield user;
                                }
                                case "existsByNickname" -> {
                                    duplicateQueries.incrementAndGet();
                                    boolean exists = em.createQuery(query, Boolean.class).setParameter("nickname", args[0]).getSingleResult();
                                    if (!exists) afterAvailable.run();
                                    yield exists;
                                }
                                case "save" -> { saves.incrementAndGet(); yield repository.save((User) args[0]); }
                                case "toString" -> "RunOwnedF13NicknameRepository";
                                case "hashCode" -> System.identityHashCode(proxy);
                                case "equals" -> proxy == args[0];
                                default -> throw new UnsupportedOperationException("Unrelated repository operation is outside the oracle");
                            };
                        });
            });
            context.registerBean(F13LegacyNicknameService.class,
                    () -> new F13LegacyNicknameService(context.getBean(UserRepository.class)));
            context.registerBean(F13LegacyAdminUserUpdateService.class,
                    () -> new F13LegacyAdminUserUpdateService(context.getBean(UserRepository.class)),
                    definition -> definition.setLazyInit(true));
            context.register(LegacyJpa.class);
            context.refresh();
            service = context.getBean(F13LegacyNicknameService.class);
            if (!AopUtils.isCglibProxy(service)) throw new IllegalStateException("Legacy nickname method lacks its transaction proxy");
        } catch (RuntimeException | Error failure) { context.close(); throw failure; }
    }

    void update(long id, String nickname) {
        verifyOwnership.run();
        service.updateNickname(id, new UpdateNicknameRequestDto(nickname));
    }

    void updateAsAdmin(long id, AdminUserUpdateRequest request) {
        verifyOwnership.run();
        var admin = context.getBean(F13LegacyAdminUserUpdateService.class);
        if (!AopUtils.isCglibProxy(admin)) throw new IllegalStateException("Original administrator method lacks its transaction proxy");
        admin.updateUserByAdmin(id, request);
    }

    @Override public void close() { context.close(); }

    @Configuration(proxyBeanMethods = false)
    @EntityScan(basePackageClasses = User.class)
    @EnableTransactionManagement(proxyTargetClass = true)
    @EnableJpaAuditing(dateTimeProviderRef = "legacyNicknameAuditTime")
    @ImportAutoConfiguration(HibernateJpaAutoConfiguration.class)
    static class LegacyJpa { }
}
