package com.pawbridge.backend.boot.configuration;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.pawbridge.backend.store.api.StorePaymentEvents;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.kafka.config.KafkaListenerEndpointRegistry;
import org.springframework.kafka.core.ConsumerFactory;
import org.springframework.kafka.core.DefaultKafkaConsumerFactory;
import org.springframework.kafka.listener.ConcurrentMessageListenerContainer;
import org.springframework.kafka.listener.DefaultErrorHandler;
import org.springframework.util.backoff.FixedBackOff;

class StorePaymentKafkaTransportConfigurationTest {
    private ApplicationContextRunner runner() {
        return new ApplicationContextRunner().withUserConfiguration(StorePaymentTransportConfiguration.class,
                StorePaymentEventsConfiguration.KafkaTransport.class)
                .withBean("memberChatRedis",StringRedisTemplate.class,()->mock(StringRedisTemplate.class))
                .withBean(StorePaymentEvents.class,()->mock(StorePaymentEvents.class));
    }

    @ParameterizedTest @CsvSource({"false,true","true,false","true,on"})
    void given__either_transport_gate_not_true__when__assembling__then__register_no_kafka_factory_or_listener(String enabled,String kafka) {
        runner().withPropertyValues("pawbridge.store-payment-events.enabled="+enabled,"pawbridge.store-payment-events.kafka-enabled="+kafka)
                .run(app->assertThat(app).hasNotFailed().doesNotHaveBean(ConsumerFactory.class).doesNotHaveBean(KafkaListenerEndpointRegistry.class));
    }

    @Test
    void given__explicit_kafka_properties__when__constructing_only_the_factory__then__use_actual_string_deserializers_and_original_effective_group() {
        new ApplicationContextRunner().withUserConfiguration(StorePaymentTransportConfiguration.class)
                .withBean("memberChatRedis",StringRedisTemplate.class,()->mock(StringRedisTemplate.class))
                .withPropertyValues("pawbridge.store-payment-events.enabled=true","pawbridge.store-payment-events.kafka-enabled=true",
                        "spring.kafka.bootstrap-servers=broker.invalid:9092")
                .run(app->{
                    assertThat(app).hasNotFailed();var factory=app.getBean("storePaymentEventsConsumerFactory",DefaultKafkaConsumerFactory.class);
                    var properties=factory.getConfigurationProperties();
                    assertThat(properties).containsEntry(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG,List.of("broker.invalid:9092"))
                            .containsEntry(ConsumerConfig.GROUP_ID_CONFIG,"payment-group")
                            .containsEntry(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG,"earliest")
                            .containsEntry(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG,StringDeserializer.class)
                            .containsEntry(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG,StringDeserializer.class);
                    assertThat(factory.getKeyDeserializer()).isInstanceOf(StringDeserializer.class);
                    assertThat(factory.getValueDeserializer()).isInstanceOf(StringDeserializer.class);
                    assertThat(factory.getValueDeserializer().deserialize("payment.events","{\"status\":\"DONE\"}".getBytes(StandardCharsets.UTF_8)))
                            .isEqualTo("{\"status\":\"DONE\"}");
                    assertThat(app).doesNotHaveBean(KafkaListenerEndpointRegistry.class);
                });
    }

    @Test
    void given__a_named_string_factory_and_an_unrelated_generic_factory__when__assembling__then__retain_the_selected_listener_and_original_retry_without_starting_a_consumer() {
        @SuppressWarnings("unchecked") ConsumerFactory<String,String> selected=mock(ConsumerFactory.class);
        @SuppressWarnings("unchecked") ConsumerFactory<Object,Object> unrelated=mock(ConsumerFactory.class);
        when(selected.getConfigurationProperties()).thenReturn(Map.of("bootstrap.servers","broker.invalid:9092"));
        when(unrelated.getConfigurationProperties()).thenReturn(Map.of("bootstrap.servers","unrelated.invalid:9092"));
        runner().withPropertyValues("pawbridge.store-payment-events.enabled=true","pawbridge.store-payment-events.kafka-enabled=true")
                .withBean("storePaymentEventsConsumerFactory",ConsumerFactory.class,()->selected)
                .withBean("unrelatedConsumerFactory",ConsumerFactory.class,()->unrelated)
                .run(app->{
                    assertThat(app).hasNotFailed();
                    var registry=app.getBean(KafkaListenerEndpointRegistry.class);assertThat(registry.getListenerContainers()).hasSize(1);
                    var listener=(ConcurrentMessageListenerContainer<?,?>)registry.getListenerContainers().iterator().next();
                    assertThat(listener.isAutoStartup()).isFalse();assertThat(listener.isRunning()).isFalse();
                    assertThat(listener.getContainerProperties().getTopics()).containsExactly("payment.events","payment");
                    assertThat(listener.getContainerProperties().getGroupId()).isEqualTo("payment-group");
                    var backoff=app.getBean("storePaymentEventsBackOff",FixedBackOff.class);
                    assertThat(backoff.getInterval()).isEqualTo(1000L);assertThat(backoff.getMaxAttempts()).isEqualTo(4L);
                    assertThat(app.getBean(DefaultErrorHandler.class).isAckAfterHandle()).isFalse();
                });
        verify(selected,never()).createConsumer(any(),any(),any(),any());
        verify(unrelated,never()).createConsumer(any(),any(),any(),any());
    }

    @Test
    void given__explicit_source_consumer_options__when__assembling__then__retain_them_while_enforcing_the_original_string_listener_group() {
        new ApplicationContextRunner().withUserConfiguration(StorePaymentTransportConfiguration.class)
                .withBean("memberChatRedis",StringRedisTemplate.class,()->mock(StringRedisTemplate.class))
                .withPropertyValues("pawbridge.store-payment-events.enabled=true","pawbridge.store-payment-events.kafka-enabled=true",
                        "spring.kafka.bootstrap-servers=broker.invalid:9092","spring.kafka.consumer.auto-offset-reset=latest",
                        "spring.kafka.consumer.max-poll-records=7","spring.kafka.consumer.enable-auto-commit=false")
                .run(app->{assertThat(app).hasNotFailed();var properties=app.getBean("storePaymentEventsConsumerFactory",ConsumerFactory.class).getConfigurationProperties();
                    assertThat(properties).containsEntry(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG,"latest")
                            .containsEntry(ConsumerConfig.MAX_POLL_RECORDS_CONFIG,7)
                            .containsEntry(ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG,false)
                            .containsEntry(ConsumerConfig.GROUP_ID_CONFIG,"payment-group");
                    assertThat(app).doesNotHaveBean(KafkaListenerEndpointRegistry.class);});
    }
}
