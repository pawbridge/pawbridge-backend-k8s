package com.pawbridge.backend.boot.configuration;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.connection.RedisStandaloneConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;

class StorePaymentTransportConfigurationTest {
    private ApplicationContextRunner runner() {
        return new ApplicationContextRunner().withUserConfiguration(StorePaymentTransportConfiguration.class);
    }

    @ParameterizedTest @ValueSource(strings={"absent","false","yes","on"})
    void given__transport_not_explicitly_enabled__when__settings_are_absent__then__create_no_redis_resource(String flag) {
        var context=runner();if(!flag.equals("absent"))context=context.withPropertyValues("pawbridge.store-payment-events.enabled="+flag);
        context.run(app->assertThat(app).hasNotFailed().doesNotHaveBean(StringRedisTemplate.class)
                .doesNotHaveBean(RedisConnectionFactory.class)
                .doesNotHaveBean(StorePaymentTransportConfiguration.RedisResource.class));
    }

    @Test
    void given__an_existing_chat_template__when__store_transport_closes__then__borrow_the_same_identity_without_another_global_factory_or_owner_close() {
        var global=mock(RedisConnectionFactory.class);
        var shared=new StringRedisTemplate(global);
        var factories=mock(StorePaymentTransportConfiguration.OwnedRedisFactory.class);
        runner().withPropertyValues("pawbridge.store-payment-events.enabled=true")
                .withBean("memberChatRedisConnectionFactory",RedisConnectionFactory.class,()->global)
                .withBean("memberChatRedis",StringRedisTemplate.class,()->shared)
                .withBean("storePaymentOwnedRedisFactory",StorePaymentTransportConfiguration.OwnedRedisFactory.class,()->factories)
                .run(app->{
                    assertThat(app).hasNotFailed().hasSingleBean(RedisConnectionFactory.class);
                    assertThat(app.getBean("storePaymentRankingRedis")).isSameAs(shared);
                    assertThat(app.getBean(RedisConnectionFactory.class)).isSameAs(global);
                    app.getBean(StorePaymentTransportConfiguration.RedisResource.class).close();
                    verifyNoInteractions(factories,global);
                });
        verifyNoInteractions(factories,global);
    }

    @Test
    void given__no_chat_and_explicit_redis_settings__when__store_transport_closes__then__own_one_private_factory_and_destroy_it_exactly_once() {
        var owned=mock(LettuceConnectionFactory.class);var seen=new AtomicReference<RedisStandaloneConfiguration>();
        runner().withPropertyValues("pawbridge.store-payment-events.enabled=true","spring.data.redis.host=127.0.0.1",
                        "spring.data.redis.port=6381","spring.data.redis.database=2","spring.data.redis.username=synthetic-user",
                        "spring.data.redis.password=synthetic-private-password")
                .withBean("storePaymentOwnedRedisFactory",StorePaymentTransportConfiguration.OwnedRedisFactory.class,
                        ()->settings->{seen.set(settings);return owned;})
                .run(app->{
                    assertThat(app).hasNotFailed().hasSingleBean(StringRedisTemplate.class)
                            .doesNotHaveBean(RedisConnectionFactory.class);
                    assertThat(app.getBean("storePaymentRankingRedis",StringRedisTemplate.class).getConnectionFactory()).isSameAs(owned);
                    assertThat(seen.get().getHostName()).isEqualTo("127.0.0.1");assertThat(seen.get().getPort()).isEqualTo(6381);
                    assertThat(seen.get().getDatabase()).isEqualTo(2);assertThat(seen.get().getUsername()).isEqualTo("synthetic-user");
                    assertThat(new String(seen.get().getPassword().get())).isEqualTo("synthetic-private-password");
                    verify(owned).afterPropertiesSet();verify(owned,never()).getConnection();
                    var resource=app.getBean(StorePaymentTransportConfiguration.RedisResource.class);resource.close();resource.close();
                });
        verify(owned,times(1)).destroy();verify(owned,never()).getConnection();
    }

    @ParameterizedTest @ValueSource(strings={"missing","blank","uri","port","database"})
    void given__invalid_required_redis_settings__when__enabling_without_chat__then__fail_safely_before_factory_creation(String invalid) {
        var factories=mock(StorePaymentTransportConfiguration.OwnedRedisFactory.class);
        var context=runner().withPropertyValues("pawbridge.store-payment-events.enabled=true");
        if(!invalid.equals("missing"))context=context.withPropertyValues("spring.data.redis.host=127.0.0.1");
        context=switch(invalid) {
            case "blank"->context.withPropertyValues("spring.data.redis.host= ");
            case "uri"->context.withPropertyValues("spring.data.redis.host=redis://synthetic-secret@host.invalid");
            case "port"->context.withPropertyValues("spring.data.redis.port=70000");
            case "database"->context.withPropertyValues("spring.data.redis.database=-1");
            default->context;
        };
        context.withBean("storePaymentOwnedRedisFactory",StorePaymentTransportConfiguration.OwnedRedisFactory.class,()->factories)
                .run(app->{assertThat(app).hasFailed();assertThat(app.getStartupFailure()).hasRootCauseMessage("Explicit valid Store payment Redis settings are required");
                    assertThat(app.getStartupFailure().toString()).doesNotContain("synthetic-secret");verifyNoInteractions(factories);});
    }

    @ParameterizedTest @ValueSource(strings={"create","initialize"})
    void given__owned_initialization_failure__when__enabling__then__release_partial_ownership_and_keep_private_causes_out_of_the_startup_error(String stage) {
        var owned=mock(LettuceConnectionFactory.class);
        if(stage.equals("initialize"))doThrow(new IllegalStateException("synthetic-private-factory-message")).when(owned).afterPropertiesSet();
        runner().withPropertyValues("pawbridge.store-payment-events.enabled=true","spring.data.redis.host=127.0.0.1")
                .withBean("storePaymentOwnedRedisFactory",StorePaymentTransportConfiguration.OwnedRedisFactory.class,
                        ()->settings->{if(stage.equals("create"))throw new IllegalStateException("synthetic-private-factory-message");return owned;})
                .run(app->{assertThat(app).hasFailed();assertThat(app.getStartupFailure()).hasRootCauseMessage("Store payment Redis initialization failed");
                    assertThat(app.getStartupFailure().toString()).doesNotContain("synthetic-private-factory-message");});
        verify(owned,times(stage.equals("initialize")?1:0)).destroy();verify(owned,never()).getConnection();
    }
}
