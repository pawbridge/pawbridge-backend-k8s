package com.pawbridge.backend.compatibility;

import com.pawbridge.communityservice.domain.repository.PostRepository;
import com.pawbridge.communityservice.dto.response.PostPeriodStatsResponse;
import com.pawbridge.communityservice.service.AdminPostStatsService;
import com.pawbridge.communityservice.service.F6LegacyAdminPostTodayService;
import jakarta.persistence.EntityManager;
import jakarta.persistence.EntityManagerFactory;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import javax.sql.DataSource;
import org.springframework.aop.framework.ProxyFactory;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.core.env.MapPropertySource;
import org.springframework.data.auditing.DateTimeProvider;
import org.springframework.data.jpa.repository.support.JpaRepositoryFactory;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.orm.jpa.JpaTransactionManager;
import org.springframework.orm.jpa.SharedEntityManagerCreator;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.AnnotationTransactionAttributeSource;
import org.springframework.transaction.interceptor.TransactionInterceptor;

/** F5 actual Hibernate/JpaTM plus F6's raw Repository; original service annotations own the TX. */
final class F6LegacyAdminPostStatisticsOracle implements AutoCloseable {
    record Query(String method, LocalDateTime start, LocalDateTime end, int isolation, boolean readOnly) { }
    private final AnnotationConfigApplicationContext context = new AnnotationConfigApplicationContext();
    private final List<Query> queries = new ArrayList<>();
    private final AdminPostStatsService periods;
    private final F6LegacyAdminPostTodayService today;
    private Runnable afterDaily = () -> { };
    private final Runnable ownership;

    F6LegacyAdminPostStatisticsOracle(F3PostgresqlFixture fixture, Clock clock, Runnable ownership) {
        this.ownership = ownership;
        try {
            ownership.run();
            var dataSource = new DriverManagerDataSource(fixture.reads().jdbcUrl(),
                    F3PostgresqlFixture.required("F2_TEST_ADMIN_USER"), F3PostgresqlFixture.required("F2_TEST_ADMIN_PASSWORD"));
            context.getEnvironment().getPropertySources().addFirst(new MapPropertySource("legacy-post-statistics", Map.of(
                    "spring.jpa.hibernate.ddl-auto", "none", "spring.jpa.open-in-view", "false",
                    "spring.jpa.properties.hibernate.default_schema", "pawbridge_community",
                    "spring.jpa.properties.hibernate.jdbc.time_zone", "UTC")));
            context.registerBean("dataSource", DataSource.class, () -> dataSource);
            context.registerBean("legacyUpdateAuditTime", DateTimeProvider.class, () -> () -> Optional.of(LocalDateTime.now(clock)));
            context.register(F5LegacyPostOracle.LegacyJpa.class);
            context.refresh();
            PlatformTransactionManager manager = context.getBean(PlatformTransactionManager.class);
            if (!(manager instanceof JpaTransactionManager jpa) || jpa.getDataSource() != dataSource)
                throw new IllegalStateException("Original statistics must use the actual same-data-source JpaTM");
            EntityManager entityManager = SharedEntityManagerCreator.createSharedEntityManager(context.getBean(EntityManagerFactory.class));
            PostRepository actual = new JpaRepositoryFactory(entityManager).getRepository(PostRepository.class);
            PostRepository observed = (PostRepository) Proxy.newProxyInstance(PostRepository.class.getClassLoader(),
                    new Class<?>[] {PostRepository.class}, (proxy, method, arguments) -> {
                        ownership.run();
                        if (!List.of("countDailyPosts", "countByBoardType", "countByCreatedAtBetween").contains(method.getName()))
                            throw new UnsupportedOperationException("Only the three original statistics JPQL methods are wired");
                        Object result;
                        try { result = method.invoke(actual, arguments); }
                        catch (InvocationTargetException failure) { throw failure.getCause(); }
                        queries.add(entityManager.unwrap(org.hibernate.Session.class).doReturningWork(connection ->
                                new Query(method.getName(), (LocalDateTime) arguments[0], (LocalDateTime) arguments[1],
                                        connection.getTransactionIsolation(), connection.isReadOnly())));
                        if (method.getName().equals("countDailyPosts")) afterDaily.run();
                        return result;
                    });
            var constructor = AdminPostStatsService.class.getDeclaredConstructor(PostRepository.class, Clock.class);
            constructor.setAccessible(true);
            periods = proxy(constructor.newInstance(observed, clock), manager, AdminPostStatsService.class);
            today = proxy(new F6LegacyAdminPostTodayService(observed), manager, F6LegacyAdminPostTodayService.class);
        } catch (ReflectiveOperationException | RuntimeException | Error failure) {
            context.close();
            throw new IllegalStateException("Original post statistics wiring failed", failure);
        }
    }

    private static <T> T proxy(T target, PlatformTransactionManager manager, Class<T> type) {
        ProxyFactory factory = new ProxyFactory(target);
        factory.setProxyTargetClass(true);
        // AnnotationTransactionAttributeSource reads the original raw class/method metadata, including RR.
        factory.addAdvice(new TransactionInterceptor(manager, new AnnotationTransactionAttributeSource()));
        return type.cast(factory.getProxy());
    }

    PostPeriodStatsResponse period(LocalDate start, LocalDate end) {
        ownership.run();
        return periods.period(start, end);
    }

    long today() { ownership.run(); return today.getTodayPostCount(); }
    void afterDaily(Runnable callback) { queries.clear(); afterDaily = callback; }
    List<Query> queries() { return List.copyOf(queries); }
    @Override public void close() { context.close(); }
}
