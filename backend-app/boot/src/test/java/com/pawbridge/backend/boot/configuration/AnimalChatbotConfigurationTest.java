package com.pawbridge.backend.boot.configuration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.pawbridge.backend.animal.internal.chatbot.infrastructure.client.PythonChatbotClient;
import com.pawbridge.backend.animal.internal.chatbot.web.ChatbotController;
import com.pawbridge.backend.animal.internal.chatbot.web.ChatbotExceptionHandler;
import com.pawbridge.backend.animal.internal.chatbot.infrastructure.repository.*;
import com.pawbridge.backend.animal.internal.chatbot.infrastructure.service.ChatbotRateLimitService;
import com.pawbridge.backend.animal.internal.chatbot.infrastructure.service.ChatbotService;
import jakarta.persistence.EntityManager;
import jakarta.persistence.EntityManagerFactory;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.redisson.api.RedissonClient;
import org.redisson.config.Config;
import org.redisson.config.SingleServerConfig;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.autoconfigure.context.PropertyPlaceholderAutoConfiguration;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.boot.test.context.runner.WebApplicationContextRunner;
import org.springframework.data.auditing.AuditingHandler;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.orm.jpa.JpaTransactionManager;
import org.springframework.orm.jpa.LocalContainerEntityManagerFactoryBean;
import org.springframework.test.util.AopTestUtils;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

class AnimalChatbotConfigurationTest {
    private WebApplicationContextRunner web(){return new WebApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(PropertyPlaceholderAutoConfiguration.class)).withUserConfiguration(AnimalChatbotConfiguration.class);}

    @ParameterizedTest @ValueSource(strings={"absent","false"})
    void given__chatbot_off_without_settings__when__assembling__then__create_no_chatbot_client_entity_manager_or_database_resource(String flag) {
        var runner=web().withPropertyValues("pawbridge.animal-query.backend=postgresql");
        if(!flag.equals("absent"))runner=runner.withPropertyValues("pawbridge.animal-chatbot.enabled="+flag);
        runner.run(context->{assertThat(context).hasNotFailed().doesNotHaveBean(ChatbotService.class).doesNotHaveBean(ChatbotController.class)
                .doesNotHaveBean(PythonChatbotClient.class).doesNotHaveBean(RedissonClient.class).doesNotHaveBean(EntityManagerFactory.class).doesNotHaveBean(DataSource.class);});
    }

    @ParameterizedTest @ValueSource(strings={"absent","http","mysql"})
    void given__non_postgresql_backend__when__enabling_chatbot_without_settings__then__keep_client_and_writer_unassembled(String backend) {
        var runner=web().withPropertyValues("pawbridge.animal-chatbot.enabled=true");
        if(!backend.equals("absent"))runner=runner.withPropertyValues("pawbridge.animal-query.backend="+backend);
        runner.run(context->{assertThat(context).hasNotFailed().doesNotHaveBean(ChatbotService.class)
                .doesNotHaveBean(RedissonClient.class).doesNotHaveBean(EntityManagerFactory.class).doesNotHaveBean(DataSource.class);});
    }

    @Test void given__non_servlet_context__when__enabling_postgresql_chatbot__then__do_not_create_clients_or_writer() {
        new ApplicationContextRunner().withUserConfiguration(AnimalChatbotConfiguration.class)
                .withPropertyValues("pawbridge.animal-chatbot.enabled=true","pawbridge.animal-query.backend=postgresql")
                .run(context->{assertThat(context).hasNotFailed().doesNotHaveBean(ChatbotService.class)
                        .doesNotHaveBean(RedissonClient.class).doesNotHaveBean(EntityManagerFactory.class);});
    }

    @Test void given__explicit_settings_and_injected_redis_factory__when__assembling_then_closing__then__reuse_one_writer_shared_em_and_short_transactions_and_close_owned_client_once() {
        var inputs=new Inputs();var emf=new AtomicReference<EntityManagerFactory>();
        inputs.runner().run(context->{
            assertThat(context).hasNotFailed().hasSingleBean(ChatbotService.class).hasSingleBean(ChatbotController.class).hasSingleBean(ChatbotExceptionHandler.class)
                    .hasSingleBean(PythonChatbotClient.class).hasSingleBean(RedissonClient.class).hasSingleBean(JpaAuditingConfiguration.class).hasSingleBean(AuditingHandler.class);
            assertThat(context.getBeansOfType(EntityManagerFactory.class)).hasSize(1);assertThat(context.getBeansOfType(DataSource.class)).hasSize(2);
            emf.set(context.getBean("animalCommandEntityManagerFactory",EntityManagerFactory.class));
            assertThat(context.getBean("&animalCommandEntityManagerFactory",LocalContainerEntityManagerFactoryBean.class).getDataSource()).isSameAs(inputs.writer);
            var manager=context.getBean("animalCommandJpaTransactionManager",JpaTransactionManager.class);
            assertThat(manager.getDataSource()).isSameAs(inputs.writer);assertThat(manager.getEntityManagerFactory()).isSameAs(emf.get());assertThat(manager.getDefaultTimeout()).isEqualTo(15);
            EntityManager shared=context.getBean("animalCommandEntityManager",EntityManager.class);
            for(Object repository:List.of(context.getBean(ChatbotSessionRepository.class),context.getBean(ChatbotMessageRepository.class),context.getBean(ChatbotBlockLogRepository.class))) {
                Object target=AopTestUtils.getUltimateTargetObject(repository);
                assertThat(ReflectionTestUtils.getField(target,"entityManager")).isSameAs(shared);
            }
            assertThat(emf.get().getMetamodel().getEntities().stream().map(type->type.getName()).toList()).contains("ChatbotSession","ChatbotMessage","ChatbotBlockLog");
            var reads=context.getBean("animalChatbotReads",TransactionTemplate.class);var writes=context.getBean("animalChatbotWrites",TransactionTemplate.class);
            assertThat(reads.getTransactionManager()).isSameAs(manager);assertThat(writes.getTransactionManager()).isSameAs(manager);
            assertThat(reads.isReadOnly()).isTrue();assertThat(writes.isReadOnly()).isFalse();
            assertThat(reads.getPropagationBehavior()).isEqualTo(TransactionDefinition.PROPAGATION_REQUIRED);assertThat(writes.getPropagationBehavior()).isEqualTo(TransactionDefinition.PROPAGATION_REQUIRED);
            var service=context.getBean(ChatbotService.class);assertThat(ReflectionTestUtils.getField(service,"reads")).isSameAs(reads);assertThat(ReflectionTestUtils.getField(service,"writes")).isSameAs(writes);
            assertThat(ReflectionTestUtils.getField(service,"chatbotRateLimitService")).isSameAs(inputs.quota);
            assertThat(inputs.redisCreates.get()).isEqualTo(1);
            var redis=(SingleServerConfig)ReflectionTestUtils.getField(inputs.redisConfig.get(),"singleServerConfig");
            assertThat(redis.getAddress()).isEqualTo("redis://127.0.0.1:6379");assertThat(redis.getDatabase()).isZero();assertThat(redis.getPassword()).isEqualTo("synthetic-redis-password");
            assertThat(context.getBean("animalChatbotSettings").toString()).isEqualTo("AnimalChatbotSettings[redacted]");
            verifyNoInteractions(inputs.writer,inputs.reader,inputs.redis,inputs.quota);
        });
        assertThat(emf.get().isOpen()).isFalse();verify(inputs.redis).shutdown();verifyNoMoreInteractions(inputs.redis);verifyNoInteractions(inputs.writer,inputs.reader,inputs.quota);
    }

    @ParameterizedTest @ValueSource(strings={"python-ai-service.url","python-ai-service.internal-api-key","chatbot.security.ip-hash-secret","spring.data.redis.host"})
    void given__missing_required_configuration__when__enabling__then__fail_with_fixed_nonsecret_message_before_redis_creation(String property) {
        var inputs=new Inputs();inputs.runner().withPropertyValues(property+"=").run(context->{
            assertThat(context).hasFailed();assertThat(context.getStartupFailure()).hasRootCauseMessage("Animal chatbot requires explicit valid configuration");
            assertThat(context.getStartupFailure().toString()).doesNotContain("synthetic-python-key","synthetic-hmac-key","synthetic-redis-password");
            assertThat(inputs.redisCreates.get()).isZero();verifyNoInteractions(inputs.writer,inputs.reader,inputs.redis,inputs.quota);
        });
    }

    @Test void given__malformed_private_endpoint__when__enabling__then__reject_with_fixed_error_without_echoing_endpoint_or_credentials() {
        var inputs=new Inputs();inputs.runner().withPropertyValues("python-ai-service.url=private endpoint with token").run(context->{
            assertThat(context).hasFailed();assertThat(context.getStartupFailure()).hasRootCauseMessage("Animal chatbot requires explicit valid configuration");
            assertThat(context.getStartupFailure().toString()).doesNotContain("private endpoint with token");assertThat(inputs.redisCreates.get()).isZero();
        });
    }

    /** The factory replaces Redis bootstrap; quota is a mock here, and its five-bucket semantics have module unit tests. */
    private final class Inputs {
        final DataSource writer=mock(DataSource.class),reader=mock(DataSource.class);
        final RedissonClient redis=mock(RedissonClient.class);final ChatbotRateLimitService quota=mock(ChatbotRateLimitService.class);
        final AtomicInteger redisCreates=new AtomicInteger();final AtomicReference<Config> redisConfig=new AtomicReference<>();
        WebApplicationContextRunner runner(){return web()
                .withBean("writeDataSource",DataSource.class,()->writer).withBean("dataSource",DataSource.class,()->reader)
                .withBean("writeJdbc",JdbcTemplate.class,()->new JdbcTemplate(writer)).withBean(ObjectMapper.class,ObjectMapper::new)
                .withBean(ChatbotRateLimitService.class,()->quota)
                .withBean("animalChatbotRedisClientFactory",AnimalChatbotConfiguration.RedisClientFactory.class,
                        ()->config->{redisCreates.incrementAndGet();redisConfig.set(config);return redis;})
                .withPropertyValues("pawbridge.animal-chatbot.enabled=true","pawbridge.animal-query.backend=postgresql",
                        "python-ai-service.url=http://python.example.invalid:8000","python-ai-service.internal-api-key=synthetic-python-key",
                        "chatbot.security.ip-hash-secret=synthetic-hmac-key","spring.data.redis.host=127.0.0.1","spring.data.redis.password=synthetic-redis-password");}
    }
}
