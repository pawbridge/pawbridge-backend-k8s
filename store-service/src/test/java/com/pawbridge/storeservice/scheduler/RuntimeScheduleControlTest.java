package com.pawbridge.storeservice.scheduler;

import com.pawbridge.storeservice.domain.cart.scheduler.CartSyncScheduler;
import com.pawbridge.storeservice.domain.cart.repository.CartRepository;
import org.redisson.api.RedissonClient;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

class RuntimeScheduleControlTest {
    private final ApplicationContextRunner context = new ApplicationContextRunner()
            .withUserConfiguration(CartSyncScheduler.class)
            .withBean(CartRepository.class, () -> mock(CartRepository.class))
            .withBean(RedissonClient.class, () -> mock(RedissonClient.class));

    @Test void disabledScheduleDoesNotRegisterTheWorker() {
        context.withPropertyValues("pawbridge.jobs.cart-sync-enabled=false")
                .run(c -> assertThat(c).doesNotHaveBean(CartSyncScheduler.class));
    }

    @Test void defaultConfigurationKeepsExistingBehavior() {
        context.run(c -> assertThat(c).hasSingleBean(CartSyncScheduler.class));
    }
}
