package com.pawbridge.backend

import com.pawbridge.backend.animal.api.AnimalCommandActors
import com.pawbridge.backend.animal.internal.discovery.infrastructure.KoreanSearchAnalyzer
import com.pawbridge.backend.boot.configuration.PostWriteSettings
import com.pawbridge.backend.boot.configuration.ReadDatabaseSettings
import com.pawbridge.backend.store.api.StoreOrderActors
import com.pawbridge.backend.store.internal.catalog.infrastructure.KoreanSearchTerms
import com.zaxxer.hikari.HikariDataSource
import com.zaxxer.hikari.metrics.IMetricsTracker
import com.zaxxer.hikari.metrics.MetricsTrackerFactory
import jakarta.persistence.EntityManagerFactory
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.config.BeanPostProcessor
import org.springframework.boot.test.context.runner.WebApplicationContextRunner
import org.springframework.data.redis.connection.RedisConnectionFactory
import org.springframework.scheduling.annotation.ScheduledAnnotationBeanPostProcessor
import org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity
import org.springframework.security.web.SecurityFilterChain
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import org.springframework.test.web.servlet.setup.DefaultMockMvcBuilder
import org.springframework.test.web.servlet.setup.MockMvcBuilders
import org.springframework.transaction.PlatformTransactionManager
import java.util.concurrent.atomic.AtomicInteger
import javax.sql.DataSource

/** Actual Main imports and actual persistence/security consumers, without a database fixture or business substitutes. */
class PawBridgeCoreIntegrationTest {
    @Test
    fun `명시적 코어 모듈과 writer가 주어졌을 때__main을 조립하고 보안이 거부하면__연결을 빌리지 않고 모든 자원을 닫는다`() {
        // given
        val resources = Resources()

        // when
        runner(resources).run { context ->
            // then
            assertThat(context).hasNotFailed()
            // The public chain plus the management-port chain, which never matches a request on the main port.
            assertThat(context.getBeansOfType(SecurityFilterChain::class.java))
                .containsOnlyKeys("postReadSecurity", "managementSecurity")
            assertThat(context.getBeansOfType(DataSource::class.java)).containsOnlyKeys("dataSource")
            assertThat(context.getBeansOfType(EntityManagerFactory::class.java)).containsOnlyKeys(
                "storeMemberEntityManagerFactory",
                "animalCommandEntityManagerFactory",
                "storeDirectOrderEntityManagerFactory",
            )
            assertThat(context.getBeansOfType(PlatformTransactionManager::class.java)).containsOnlyKeys(
                "transactionManager",
                "writeTransactionManager",
                "storeMemberJpaTransactionManager",
                "animalCommandJpaTransactionManager",
                "storeDirectOrderJpaTransactionManager",
            )
            val reader = context.getBean("dataSource", HikariDataSource::class.java)
            assertThat(reader.isReadOnly).isFalse()
            val animalActors = context.getBean(AnimalCommandActors::class.java)
            val orderActors = context.getBean(StoreOrderActors::class.java)

            // These actual actor guards run before SQL when no owning writer transaction exists.
            assertThatThrownBy { animalActors.find(7) }
                .isInstanceOf(IllegalStateException::class.java)
                .hasMessageContaining("shared writer transaction")
            assertThatThrownBy { orderActors.currentForWrite(7) }
                .isInstanceOf(IllegalStateException::class.java)
                .hasMessageContaining("shared Store order transaction")
            val mvc = MockMvcBuilders.webAppContextSetup(context.sourceApplicationContext)
                .apply<DefaultMockMvcBuilder>(springSecurity())
                .build()
            for (path in listOf("/api/v1/users/me", "/api/v1/users/me/favorite-animals", "/api/v1/users/me/orders")) {
                val result = mvc.perform(get(path)).andExpect(status().isUnauthorized()).andReturn()
                assertThat(result.handler).isNull()
            }
            for (path in listOf("/api/v1/animals", "/api/v1/orders/direct")) {
                val result = mvc.perform(post(path).contentType("application/json").content("{}"))
                    .andExpect(status().isUnauthorized())
                    .andReturn()
                assertThat(result.handler).isNull()
            }
            val roleDenied = mvc.perform(
                get("/api/v1/admin/users").header("Authorization", "Bearer " + F3TestInputs.token(7, "ROLE_USER")),
            )
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.message").value("관리자 권한이 필요합니다."))
                .andReturn()
            assertThat(roleDenied.handler).isNull()
            val methodDenied = mvc.perform(
                put("/api/v1/orders/direct").header("Authorization", "Bearer " + F3TestInputs.token(7, "ROLE_ADMIN")),
            )
                .andExpect(status().isForbidden())
                .andReturn()
            assertThat(methodDenied.handler).isNull()
            assertThat(context.getBeansOfType(ScheduledAnnotationBeanPostProcessor::class.java)).isEmpty()
            assertThat(context.containsBean("userAnimalHttpClient")).isFalse()
            assertThat(context.containsBean("userStoreHttpClient")).isFalse()
            assertThat(context.containsBean("animalLostSearchEntityManagerFactory")).isFalse()
            assertThat(context.getBeansOfType(RedisConnectionFactory::class.java)).isEmpty()
            assertThat(context.getBeansOfType(KoreanSearchAnalyzer::class.java)).hasSize(1)
            assertThat(context.getBeansOfType(KoreanSearchTerms::class.java)).hasSize(1)
            assertThat(resources.borrowed.get()).isZero()
            assertThat(resources.timedOut.get()).isZero()
            for (pool in resources.pools) {
                assertThat(pool.hikariPoolMXBean.activeConnections).isZero()
                assertThat(pool.hikariPoolMXBean.totalConnections).isZero()
            }
        }

        // then (after the context closes)
        assertThat(resources.pools).hasSize(1).allSatisfy { pool -> assertThat(pool.isClosed).isTrue() }
        assertThat(resources.factories).hasSize(3).allSatisfy { factory -> assertThat(factory.isOpen).isFalse() }
        assertThat(resources.animalAnalyzers).hasSize(1)
        assertThatThrownBy { resources.animalAnalyzers[0].tokens("진돗개") }
            .isInstanceOf(IllegalStateException::class.java)
            .hasMessageContaining("closed")
        assertThat(resources.storeAnalyzers).hasSize(1)
        assertThatThrownBy { resources.storeAnalyzers[0].query("강아지 사료") }
            .isInstanceOf(IllegalStateException::class.java)
            .hasMessageContaining("closed")
        assertThat(resources.borrowed.get()).isZero()
        assertThat(resources.timedOut.get()).isZero()
    }

    private fun runner(resources: Resources): WebApplicationContextRunner {
        val values = F3TestInputs.values()
        values["PAWBRIDGE_DB_URL"] = "jdbc:postgresql://127.0.0.1:1/pawbridge_main_synthetic"
        val read = ReadDatabaseSettings.fromEnvironment(values::get)
        val write = PostWriteSettings.fromEnvironment(values::get)
        return WebApplicationContextRunner().withUserConfiguration(PawBridgeApplication::class.java)
            .withInitializer { context ->
                context.beanFactory.registerSingleton("readDatabaseSettings", read)
                context.beanFactory.registerSingleton("postWriteSettings", write)
                context.beanFactory.addBeanPostProcessor(resources)
            }
            .withPropertyValues(
                "spring.sql.init.mode=never",
                "spring.jmx.enabled=false",
                "pawbridge.user-animal-activities.enabled=true",
                "pawbridge.user-store-mypage.enabled=true",
                "pawbridge.animal-commands.enabled=true",
                "pawbridge.store-direct-orders.enabled=true",
                "pawbridge.auth.google-enabled=false",
                "pawbridge.auth.sessions-enabled=false",
                "pawbridge.chat.enabled=false",
                "pawbridge.jobs.cleanup-enabled=false",
                "pawbridge.user-maintenance.enabled=false",
                "pawbridge.community-maintenance.enabled=false",
                "pawbridge.visits.enabled=false",
                "pawbridge.visits.collection-enabled=false",
                "pawbridge.animal-query.projection-schedule-enabled=false",
                "apms.batch.schedule-enabled=false",
                "apms-photo-archive.enabled=false",
                "tourapi.enabled=false",
                "tourapi.schedule-enabled=false",
                "lost-gallery-feed.enabled=false",
                "pawbridge.animal-lost-search.enabled=false",
                "pawbridge.store-carts.enabled=false",
                "pawbridge.store-catalog.enabled=false",
                "pawbridge.store-order-administration.enabled=false",
                "pawbridge.animal-discovery.enabled=false",
                "pawbridge.animal-recommendations.enabled=false",
                "pawbridge.animal-shelters.enabled=false",
                "pawbridge.animal-statistics.enabled=false",
                "pawbridge.animal-travel.enabled=false",
                "pawbridge.shelter-commands.enabled=false",
                "pawbridge.animal-reports.enabled=false",
                "pawbridge.store-wishlist.enabled=false",
                "pawbridge.shelter-membership.enabled=false",
                "pawbridge.home-videos.management-enabled=false",
            )
    }

    /** Observation only: the application still creates and owns every real pool, factory, analyzer and consumer. */
    private class Resources : BeanPostProcessor {
        val pools: MutableList<HikariDataSource> = ArrayList()
        val factories: MutableList<EntityManagerFactory> = ArrayList()
        val animalAnalyzers: MutableList<KoreanSearchAnalyzer> = ArrayList()
        val storeAnalyzers: MutableList<KoreanSearchTerms> = ArrayList()
        val borrowed = AtomicInteger()
        val timedOut = AtomicInteger()

        override fun postProcessAfterInitialization(bean: Any, name: String): Any {
            if (bean is HikariDataSource) {
                pools.add(bean)
                bean.metricsTrackerFactory = MetricsTrackerFactory { _, _ ->
                    object : IMetricsTracker {
                        override fun recordConnectionAcquiredNanos(elapsedAcquiredNanos: Long) {
                            borrowed.incrementAndGet()
                        }

                        override fun recordConnectionTimeout() {
                            timedOut.incrementAndGet()
                        }
                    }
                }
            }
            if (bean is EntityManagerFactory) {
                factories.add(bean)
            }
            if (bean is KoreanSearchAnalyzer) {
                animalAnalyzers.add(bean)
            }
            if (bean is KoreanSearchTerms) {
                storeAnalyzers.add(bean)
            }
            return bean
        }
    }
}
