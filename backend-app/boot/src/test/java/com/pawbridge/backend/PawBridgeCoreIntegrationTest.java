package com.pawbridge.backend;

import com.pawbridge.backend.animal.api.AnimalCommandActors;
import com.pawbridge.backend.animal.api.AnimalMemberReads;
import com.pawbridge.backend.animal.internal.command.infrastructure.service.AnimalCommandService;
import com.pawbridge.backend.animal.internal.command.web.AnimalCommandController;
import com.pawbridge.backend.animal.internal.discovery.infrastructure.KoreanSearchAnalyzer;
import com.pawbridge.backend.boot.configuration.PostWriteSettings;
import com.pawbridge.backend.boot.configuration.ReadDatabaseSettings;
import com.pawbridge.backend.community.internal.post.application.ReadPost;
import com.pawbridge.backend.store.api.StoreMemberReads;
import com.pawbridge.backend.store.api.StoreOrderActors;
import com.pawbridge.backend.store.internal.catalog.infrastructure.KoreanSearchTerms;
import com.pawbridge.backend.store.internal.directorder.application.DirectOrders;
import com.pawbridge.backend.store.internal.directorder.infrastructure.JpaDirectOrders;
import com.pawbridge.backend.store.internal.directorder.web.DirectOrderController;
import com.pawbridge.backend.store.internal.memberread.infrastructure.JpaStoreMemberReads;
import com.pawbridge.backend.user.api.UserNicknameQuery;
import com.pawbridge.backend.user.internal.animalactivity.application.UserAnimalActivities;
import com.pawbridge.backend.user.internal.animalactivity.infrastructure.JdbcUserAnimalActivities;
import com.pawbridge.backend.user.internal.animalactivity.infrastructure.ModuleUserAnimalLookup;
import com.pawbridge.backend.user.internal.storemypage.application.StoreMyPage;
import com.pawbridge.backend.user.internal.storemypage.infrastructure.ModuleStoreMyPage;
import com.zaxxer.hikari.HikariDataSource;
import com.zaxxer.hikari.metrics.IMetricsTracker;
import jakarta.persistence.EntityManager;
import jakarta.persistence.EntityManagerFactory;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.boot.test.context.runner.WebApplicationContextRunner;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.support.JdbcTransactionManager;
import org.springframework.orm.jpa.JpaTransactionManager;
import org.springframework.orm.jpa.LocalContainerEntityManagerFactoryBean;
import org.springframework.scheduling.annotation.ScheduledAnnotationBeanPostProcessor;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.test.util.AopTestUtils;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/** Actual Main imports and actual persistence/security consumers, without a database fixture or business substitutes. */
class PawBridgeCoreIntegrationTest {
    @Test
    void given__explicit_core_modules_and_writers__when__main_assembles_and_security_rejects__then__share_named_resources_without_borrowing_and_close_them() {
        var resources = new Resources();
        runner(resources).run(context -> {
            assertThat(context).hasNotFailed().hasSingleBean(SecurityFilterChain.class);
            assertThat(context.getBeansOfType(DataSource.class)).containsOnlyKeys("dataSource", "writeDataSource");
            assertThat(context.getBeansOfType(EntityManagerFactory.class)).containsOnlyKeys(
                    "storeMemberEntityManagerFactory", "animalCommandEntityManagerFactory", "storeDirectOrderEntityManagerFactory");
            assertThat(context.getBeansOfType(PlatformTransactionManager.class)).containsOnlyKeys("transactionManager",
                    "writeTransactionManager", "storeMemberJpaTransactionManager", "animalCommandJpaTransactionManager",
                    "storeDirectOrderJpaTransactionManager");
            var reader = context.getBean("dataSource", HikariDataSource.class);
            var writer = context.getBean("writeDataSource", HikariDataSource.class);
            assertThat(reader).isNotSameAs(writer);
            assertThat(reader.isReadOnly()).isTrue();
            assertThat(writer.isReadOnly()).isFalse();
            for (String prefix : List.of("storeMember", "animalCommand", "storeDirectOrder")) {
                DataSource expected = prefix.equals("storeMember") ? reader : writer;
                var factory = context.getBean(prefix + "EntityManagerFactory", EntityManagerFactory.class);
                assertThat(context.getBean("&" + prefix + "EntityManagerFactory",
                        LocalContainerEntityManagerFactoryBean.class).getDataSource()).isSameAs(expected);
                var manager = context.getBean(prefix + "JpaTransactionManager", JpaTransactionManager.class);
                assertThat(manager.getDataSource()).isSameAs(expected);
                assertThat(manager.getEntityManagerFactory()).isSameAs(factory);
            }
            assertThat(context.getBean("transactionManager", JdbcTransactionManager.class).getDataSource()).isSameAs(reader);
            assertThat(context.getBean("writeTransactionManager", JdbcTransactionManager.class).getDataSource()).isSameAs(writer);

            // Public module APIs must be the instances consumed by real User adapters and writer facades.
            var activities = context.getBean(UserAnimalActivities.class);
            assertThat(activities).isInstanceOf(JdbcUserAnimalActivities.class);
            var lookup = field(activities, "animals");
            assertThat(lookup).isInstanceOf(ModuleUserAnimalLookup.class);
            var animalReads = context.getBean(AnimalMemberReads.class);
            assertThat(field(lookup, "animals")).isSameAs(animalReads);
            assertThat(((NamedParameterJdbcTemplate) field(animalReads, "jdbc")).getJdbcTemplate().getDataSource()).isSameAs(reader);
            assertThat(((TransactionTemplate) field(animalReads, "reads")).getTransactionManager()).isSameAs(context.getBean("transactionManager"));
            assertThat(field(activities, "jdbc")).isSameAs(context.getBean("writeJdbc"));
            assertThat(((TransactionTemplate) field(activities, "write")).getTransactionManager()).isSameAs(context.getBean("writeTransactionManager"));

            var myPage = context.getBean(StoreMyPage.class);
            assertThat(myPage).isInstanceOf(ModuleStoreMyPage.class);
            var storeReads = context.getBean(StoreMemberReads.class);
            assertThat(storeReads).isInstanceOf(JpaStoreMemberReads.class);
            assertThat(field(myPage, "store")).isSameAs(storeReads);
            assertThat(field(myPage, "read")).isSameAs(context.getBean("readOnlyTransaction"));
            assertThat(((TransactionTemplate) field(storeReads, "reads")).getTransactionManager()).isSameAs(context.getBean("storeMemberJpaTransactionManager"));
            assertThat(repositoryEntityManager(field(storeReads, "orders")).getEntityManagerFactory()).isSameAs(context.getBean("storeMemberEntityManagerFactory"));
            assertThat(field(context.getBean(ReadPost.class), "userNicknameQuery")).isSameAs(context.getBean(UserNicknameQuery.class));
            assertThat(field(context.getBean(ReadPost.class), "postReader")).isSameAs(context.getBean("postReader"));

            var animalCommands = context.getBean(AnimalCommandService.class);
            var animalActors = context.getBean(AnimalCommandActors.class);
            assertThat(field(animalCommands, "actors")).isSameAs(animalActors);
            assertThat(field(animalActors, "jdbc")).isSameAs(context.getBean("writeJdbc"));
            assertThat(((TransactionTemplate) field(animalCommands, "writes")).getTransactionManager()).isSameAs(context.getBean("animalCommandJpaTransactionManager"));
            assertThat(repositoryEntityManager(field(animalCommands, "animalRepository")).getEntityManagerFactory()).isSameAs(context.getBean("animalCommandEntityManagerFactory"));
            assertThat(field(context.getBean(AnimalCommandController.class), "animals")).isSameAs(animalCommands);
            var orders = context.getBean(DirectOrders.class);
            assertThat(orders).isInstanceOf(JpaDirectOrders.class);
            var orderActors = context.getBean(StoreOrderActors.class);
            assertThat(field(orders, "actors")).isSameAs(orderActors);
            assertThat(field(orderActors, "jdbc")).isSameAs(context.getBean("writeJdbc"));
            assertThat(field(orders, "writes")).isSameAs(context.getBean("storeDirectOrderWrites"));
            assertThat(field(orders, "reads")).isSameAs(context.getBean("storeDirectOrderReads"));
            assertThat(((TransactionTemplate) field(orders, "writes")).getTransactionManager()).isSameAs(context.getBean("storeDirectOrderJpaTransactionManager"));
            assertThat(((TransactionTemplate) field(orders, "reads")).getTransactionManager()).isSameAs(context.getBean("storeDirectOrderJpaTransactionManager"));
            assertThat(repositoryEntityManager(field(orders, "writerOrders")).getEntityManagerFactory()).isSameAs(context.getBean("storeDirectOrderEntityManagerFactory"));
            assertThat(repositoryEntityManager(field(orders, "readerOrders")).getEntityManagerFactory()).isSameAs(context.getBean("storeDirectOrderEntityManagerFactory"));
            assertThat(field(context.getBean(DirectOrderController.class), "orders")).isSameAs(orders);

            // These actual actor guards run before SQL when no owning writer transaction exists.
            assertThatThrownBy(() -> animalActors.find(7)).isInstanceOf(IllegalStateException.class).hasMessageContaining("shared writer transaction");
            assertThatThrownBy(() -> orderActors.currentForWrite(7)).isInstanceOf(IllegalStateException.class).hasMessageContaining("shared Store order transaction");
            var mvc = MockMvcBuilders.webAppContextSetup(context.getSourceApplicationContext()).apply(springSecurity()).build();
            for (String path : List.of("/api/v1/users/me", "/api/v1/users/me/favorite-animals", "/api/v1/users/me/orders")) {
                var result = mvc.perform(get(path)).andExpect(status().isUnauthorized()).andReturn();
                assertThat(result.getHandler()).isNull();
            }
            for (String path : List.of("/api/v1/animals", "/api/v1/orders/direct")) {
                var result = mvc.perform(post(path).contentType("application/json").content("{}"))
                        .andExpect(status().isUnauthorized()).andReturn();
                assertThat(result.getHandler()).isNull();
            }
            var roleDenied = mvc.perform(get("/api/v1/admin/users").header("Authorization", "Bearer " + F3TestInputs.token(7, "ROLE_USER")))
                    .andExpect(status().isForbidden()).andExpect(jsonPath("$.message").value("관리자 권한이 필요합니다.")).andReturn();
            assertThat(roleDenied.getHandler()).isNull();
            var methodDenied = mvc.perform(put("/api/v1/orders/direct").header("Authorization", "Bearer " + F3TestInputs.token(7, "ROLE_ADMIN")))
                    .andExpect(status().isForbidden()).andReturn();
            assertThat(methodDenied.getHandler()).isNull();
            assertThat(context.getBeansOfType(ScheduledAnnotationBeanPostProcessor.class)).isEmpty();
            assertThat(context.containsBean("userAnimalHttpClient")).isFalse();
            assertThat(context.containsBean("userStoreHttpClient")).isFalse();
            assertThat(context.containsBean("animalLostSearchEntityManagerFactory")).isFalse();
            assertThat(context.getBeansOfType(org.springframework.data.redis.connection.RedisConnectionFactory.class)).isEmpty();
            assertThat(context.getBeansOfType(KoreanSearchAnalyzer.class)).hasSize(1);
            assertThat(context.getBeansOfType(KoreanSearchTerms.class)).hasSize(1);
            assertThat(resources.borrowed.get()).isZero();
            assertThat(resources.timedOut.get()).isZero();
            for (var pool : resources.pools) {
                assertThat(pool.getHikariPoolMXBean().getActiveConnections()).isZero();
                assertThat(pool.getHikariPoolMXBean().getTotalConnections()).isZero();
            }
        });
        assertThat(resources.pools).hasSize(2).allSatisfy(pool -> assertThat(pool.isClosed()).isTrue());
        assertThat(resources.factories).hasSize(3).allSatisfy(factory -> assertThat(factory.isOpen()).isFalse());
        assertThat(resources.animalAnalyzers).hasSize(1);
        assertThatThrownBy(() -> resources.animalAnalyzers.getFirst().tokens("진돗개")).isInstanceOf(IllegalStateException.class).hasMessageContaining("closed");
        assertThat(resources.storeAnalyzers).hasSize(1);
        assertThatThrownBy(() -> resources.storeAnalyzers.getFirst().query("강아지 사료")).isInstanceOf(IllegalStateException.class).hasMessageContaining("closed");
        assertThat(resources.borrowed.get()).isZero();
        assertThat(resources.timedOut.get()).isZero();
    }

    @Test
    void given__invalid_writer_capacity_with_same_main_modules__when__startup_rejects__then__reject_before_creating_resources_or_borrowing() {
        var resources = new Resources();
        runner(resources).withPropertyValues("pawbridge.database.writer.maximum-pool-size=0").run(context -> {
            assertThat(context).hasFailed();
            assertThat(context.getStartupFailure()).hasRootCauseInstanceOf(IllegalArgumentException.class)
                    .hasStackTraceContaining("Writer pool capacity must be positive");
        });
        assertThat(resources.pools).isEmpty();
        assertThat(resources.factories).isEmpty();
        assertThat(resources.animalAnalyzers).isEmpty();
        assertThat(resources.storeAnalyzers).isEmpty();
        assertThat(resources.borrowed.get()).isZero();
        assertThat(resources.timedOut.get()).isZero();
    }

    private static WebApplicationContextRunner runner(Resources resources) {
        var values = F3TestInputs.values();
        values.put("F2_JDBC_URL", "jdbc:postgresql://127.0.0.1:1/pawbridge_main_synthetic");
        var read = ReadDatabaseSettings.fromEnvironment(values::get);
        var write = PostWriteSettings.fromEnvironment(values::get, read);
        return new WebApplicationContextRunner().withUserConfiguration(PawBridgeApplication.class)
                .withInitializer(context -> {
                    context.getBeanFactory().registerSingleton("readDatabaseSettings", read);
                    context.getBeanFactory().registerSingleton("postWriteSettings", write);
                    context.getBeanFactory().addBeanPostProcessor(resources);
                }).withPropertyValues("spring.sql.init.mode=never", "spring.jmx.enabled=false",
                        "pawbridge.user-animal-activities.enabled=true", "pawbridge.user-animal-activities.animal-backend=module",
                        "pawbridge.user-store-mypage.enabled=true", "pawbridge.user-store-mypage.backend=module",
                        "pawbridge.animal-commands.enabled=true", "pawbridge.store-direct-orders.enabled=true",
                        "pawbridge.auth.google-enabled=false", "pawbridge.auth.sessions-enabled=false", "pawbridge.chat.enabled=false",
                        "pawbridge.jobs.cleanup-enabled=false", "pawbridge.user-maintenance.enabled=false",
                        "pawbridge.community-maintenance.enabled=false", "pawbridge.favorite-delivery.enabled=false",
                        "pawbridge.visits.enabled=false", "pawbridge.visits.collection-enabled=false",
                        "pawbridge.animal-query.projection-schedule-enabled=false", "apms.batch.schedule-enabled=false",
                        "apms-photo-archive.enabled=false", "tourapi.enabled=false", "tourapi.schedule-enabled=false",
                        "lost-gallery-feed.enabled=false", "pawbridge.animal-lost-search.enabled=false",
                        "pawbridge.store-carts.enabled=false", "pawbridge.store-catalog.enabled=false",
                        "pawbridge.store-order-administration.enabled=false",
                        "pawbridge.animal-discovery.enabled=false", "pawbridge.animal-recommendations.enabled=false",
                        "pawbridge.animal-shelters.enabled=false", "pawbridge.animal-statistics.enabled=false",
                        "pawbridge.animal-travel.enabled=false", "pawbridge.shelter-commands.enabled=false",
                        "pawbridge.animal-reports.enabled=false", "pawbridge.store-wishlist.enabled=false",
                        "pawbridge.shelter-membership.enabled=false", "pawbridge.home-videos.management-enabled=false");
    }

    private static Object field(Object bean, String name) { return ReflectionTestUtils.getField(bean, name); }

    private static EntityManager repositoryEntityManager(Object repository) {
        return (EntityManager) field(AopTestUtils.getUltimateTargetObject(repository), "entityManager");
    }

    /** Observation only: the application still creates and owns every real pool, factory, analyzer and consumer. */
    private static final class Resources implements BeanPostProcessor {
        private final List<HikariDataSource> pools = new ArrayList<>();
        private final List<EntityManagerFactory> factories = new ArrayList<>();
        private final List<KoreanSearchAnalyzer> animalAnalyzers = new ArrayList<>();
        private final List<KoreanSearchTerms> storeAnalyzers = new ArrayList<>();
        private final AtomicInteger borrowed = new AtomicInteger();
        private final AtomicInteger timedOut = new AtomicInteger();
        @Override public Object postProcessAfterInitialization(Object bean, String name) {
            if (bean instanceof HikariDataSource pool) {
                pools.add(pool);
                pool.setMetricsTrackerFactory((poolName, stats) -> new IMetricsTracker() {
                    @Override public void recordConnectionAcquiredNanos(long elapsedAcquiredNanos) { borrowed.incrementAndGet(); }
                    @Override public void recordConnectionTimeout() { timedOut.incrementAndGet(); }
                });
            }
            if (bean instanceof EntityManagerFactory factory) factories.add(factory);
            if (bean instanceof KoreanSearchAnalyzer analyzer) animalAnalyzers.add(analyzer);
            if (bean instanceof KoreanSearchTerms analyzer) storeAnalyzers.add(analyzer);
            return bean;
        }
    }
}
