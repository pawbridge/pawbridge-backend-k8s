package com.pawbridge.communityservice.service;

import com.pawbridge.communityservice.domain.entity.*;
import com.pawbridge.communityservice.domain.repository.PostRepository;
import com.pawbridge.communityservice.dto.response.*;
import java.time.*;
import java.util.TimeZone;
import org.hibernate.cfg.Configuration;
import org.hibernate.boot.model.naming.CamelCaseToUnderscoresNamingStrategy;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.data.jpa.repository.support.JpaRepositoryFactory;
import org.springframework.aop.framework.ProxyFactory;
import org.springframework.orm.jpa.JpaTransactionManager;
import org.springframework.orm.jpa.SharedEntityManagerCreator;
import org.springframework.orm.jpa.vendor.HibernateJpaVendorAdapter;
import org.springframework.transaction.annotation.AnnotationTransactionAttributeSource;
import org.springframework.transaction.interceptor.TransactionInterceptor;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

@EnabledIfEnvironmentVariable(named = "PAWBRIDGE_STATS_TEST_JDBC_URL",
        matches = "jdbc:postgresql://(127\\.0\\.0\\.1|stats-db)(:[0-9]+)?/pawbridge_admin_stats_test")
class AdminPostStatsPostgresTest {
    @Test void givenPostInsertedBetweenAggregateQueries__whenReadingPeriod__thenKeepOneSnapshot() {
        TimeZone previous = TimeZone.getDefault();
        TimeZone.setDefault(TimeZone.getTimeZone("Asia/Seoul"));
        // Match Spring's Hibernate adapter rather than the plain JPA default dialect.
        var adapter = new HibernateJpaVendorAdapter();
        try (var factory = new Configuration().addAnnotatedClass(Post.class)
                .setPhysicalNamingStrategy(new CamelCaseToUnderscoresNamingStrategy())
                .setProperty("hibernate.connection.url", System.getenv("PAWBRIDGE_STATS_TEST_JDBC_URL"))
                .setProperty("hibernate.connection.username", "postgres")
                .setProperty("hibernate.connection.password", "")
                .setProperty("hibernate.hbm2ddl.auto", "create-drop")
                .setProperty("hibernate.jdbc.time_zone", "UTC")
                .setProperty("hibernate.connection.handling_mode",
                        adapter.getJpaPropertyMap().get("hibernate.connection.handling_mode").toString())
                .buildSessionFactory()) {
            var day = LocalDate.of(2026, 10, 2);
            try (var writer = factory.openSession()) {
                var tx = writer.beginTransaction();
                writer.persist(post(day.atTime(10, 0), BoardType.COMMUNICATION, false));
                tx.commit();
            }
            var entityManager = SharedEntityManagerCreator.createSharedEntityManager(factory);
            try {
                var actual = new JpaRepositoryFactory(entityManager).getRepository(PostRepository.class);
                var boundary = mock(PostRepository.class);
                when(boundary.countDailyPosts(any(), any())).thenAnswer(call -> {
                    var rows = actual.countDailyPosts(call.getArgument(0), call.getArgument(1));
                    try (var writer = factory.openSession()) {
                        var tx = writer.beginTransaction();
                        writer.persist(post(day.atTime(11, 0), BoardType.ADOPTION, false));
                        tx.commit();
                    }
                    return rows;
                });
                when(boundary.countByBoardType(any(), any())).thenAnswer(call ->
                        actual.countByBoardType(call.getArgument(0), call.getArgument(1)));
                var manager = new JpaTransactionManager(factory);
                manager.setJpaDialect(adapter.getJpaDialect());
                var transactions = new TransactionInterceptor();
                transactions.setTransactionManager(manager);
                transactions.setTransactionAttributeSource(new AnnotationTransactionAttributeSource());
                var proxy = new ProxyFactory(new AdminPostStatsService(boundary,
                        Clock.fixed(Instant.parse("2026-10-02T03:00:00Z"), ZoneOffset.UTC)));
                proxy.setProxyTargetClass(true);
                proxy.addAdvice(transactions);
                var result = ((AdminPostStatsService) proxy.getProxy()).period(day, day);
                assertThat(result.daily()).containsExactly(new DailyPostStats(day, 1));
                assertThat(result.byBoardType()).containsExactly(new BoardTypeStats(BoardType.COMMUNICATION, 1));
                try (var reader = factory.openSession()) {
                    assertThat(reader.createQuery("select count(p) from Post p", Long.class).getSingleResult()).isEqualTo(2);
                }
            } finally {
                entityManager.close();
            }
        } finally {
            TimeZone.setDefault(previous);
        }
    }

    @Test void givenKoreanDayBoundariesAndDeletedPosts__whenAggregated__thenDailyAndTypesHaveSamePopulation() {
        TimeZone previous = TimeZone.getDefault();
        TimeZone.setDefault(TimeZone.getTimeZone("Asia/Seoul"));
        try (var factory = new Configuration().addAnnotatedClass(Post.class)
                .setPhysicalNamingStrategy(new CamelCaseToUnderscoresNamingStrategy())
                .setProperty("hibernate.connection.url", System.getenv("PAWBRIDGE_STATS_TEST_JDBC_URL"))
                .setProperty("hibernate.connection.username", "postgres")
                .setProperty("hibernate.connection.password", "")
                .setProperty("hibernate.hbm2ddl.auto", "create-drop")
                .setProperty("hibernate.jdbc.time_zone", "UTC")
                .buildSessionFactory(); var session = factory.openSession()) {
            var tx = session.beginTransaction();
            var day = LocalDate.of(2026, 10, 2);
            session.persist(post(day.minusDays(1).atTime(23, 59), BoardType.COMMUNICATION, false));
            session.persist(post(day.atStartOfDay(), BoardType.ADOPTION, false));
            session.persist(post(day.atTime(8, 59), BoardType.ADOPTION, false));
            session.persist(post(day.atTime(23, 59), BoardType.PROTECTION, false));
            session.persist(post(day.atTime(12, 0), BoardType.REPORT, true));
            session.persist(post(day.plusDays(1).atStartOfDay(), BoardType.MISSING, false));
            session.flush();
            var repository = new JpaRepositoryFactory(session).getRepository(PostRepository.class);
            assertThat(repository.countDailyPosts(day.minusDays(1).atStartOfDay(), day.plusDays(1).atStartOfDay()))
                    .containsExactly(new DailyPostStats(day.minusDays(1), 1), new DailyPostStats(day, 3));
            assertThat(repository.countByBoardType(day.atStartOfDay(), day.plusDays(1).atStartOfDay()))
                    .containsExactlyInAnyOrder(new BoardTypeStats(BoardType.ADOPTION, 2), new BoardTypeStats(BoardType.PROTECTION, 1));
            tx.rollback();
        } finally {
            TimeZone.setDefault(previous);
        }
    }

    private Post post(LocalDateTime createdAt, BoardType type, boolean deleted) {
        return Post.builder().authorId(42L).title("집계 검증").content("테스트 전용")
                .boardType(type).createdAt(createdAt).updatedAt(createdAt)
                .deletedAt(deleted ? createdAt.plusMinutes(1) : null).build();
    }
}
