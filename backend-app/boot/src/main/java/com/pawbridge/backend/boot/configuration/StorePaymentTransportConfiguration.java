package com.pawbridge.backend.boot.configuration;

import java.util.concurrent.atomic.AtomicBoolean;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;
import org.springframework.data.redis.connection.RedisPassword;
import org.springframework.data.redis.connection.RedisStandaloneConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;

/** Explicit, dormant Store transport. A privately owned Redis factory never becomes a global bean. */
@Configuration(proxyBeanMethods=false)
@ConditionalOnProperty(prefix="pawbridge.store-payment-events",name="enabled",havingValue="true")
public class StorePaymentTransportConfiguration {
    private static final String INVALID_REDIS="Explicit valid Store payment Redis settings are required";

    @FunctionalInterface
    public interface OwnedRedisFactory {
        LettuceConnectionFactory create(RedisStandaloneConfiguration settings);
    }

    @Bean @ConditionalOnMissingBean(name="storePaymentOwnedRedisFactory")
    OwnedRedisFactory storePaymentOwnedRedisFactory() { return LettuceConnectionFactory::new; }

    @Bean(destroyMethod="close")
    RedisResource storePaymentRedisResource(@Qualifier("memberChatRedis") ObjectProvider<StringRedisTemplate> shared,
            @Qualifier("storePaymentOwnedRedisFactory") OwnedRedisFactory factories,Environment environment) {
        StringRedisTemplate borrowed=shared.getIfAvailable();
        if(borrowed!=null) return new RedisResource(borrowed,null);
        RedisStandaloneConfiguration settings;
        try {
            String host=environment.getProperty("spring.data.redis.host");
            int port=environment.getProperty("spring.data.redis.port",Integer.class,6379);
            int database=environment.getProperty("spring.data.redis.database",Integer.class,0);
            if(host==null||host.isBlank()||host.contains("://")||host.contains("/")||host.contains("@")
                    ||host.chars().anyMatch(Character::isWhitespace)||port<1||port>65535||database<0)
                throw new IllegalArgumentException();
            settings=new RedisStandaloneConfiguration(host,port);settings.setDatabase(database);
            String username=environment.getProperty("spring.data.redis.username","");
            String password=environment.getProperty("spring.data.redis.password","");
            if(!username.isBlank())settings.setUsername(username);
            if(!password.isEmpty())settings.setPassword(RedisPassword.of(password));
        } catch(RuntimeException invalid) { throw new IllegalArgumentException(INVALID_REDIS); }
        LettuceConnectionFactory owned=null;
        try {
            owned=factories.create(settings);
            if(owned==null)throw new IllegalStateException();
            owned.afterPropertiesSet();
            return new RedisResource(new StringRedisTemplate(owned),owned);
        } catch(RuntimeException failure) {
            try { if(owned!=null)owned.destroy(); } catch(RuntimeException ignored) { }
            throw new IllegalStateException("Store payment Redis initialization failed");
        }
    }

    @Bean(destroyMethod="")
    StringRedisTemplate storePaymentRankingRedis(RedisResource resource) { return resource.template; }

    static final class RedisResource implements AutoCloseable {
        final StringRedisTemplate template;
        private final LettuceConnectionFactory owned;
        private final AtomicBoolean closed=new AtomicBoolean();
        RedisResource(StringRedisTemplate template,LettuceConnectionFactory owned) { this.template=template;this.owned=owned; }
        @Override public void close() {
            if(owned!=null&&closed.compareAndSet(false,true)) {
                try { owned.destroy(); }
                catch(RuntimeException failure) { throw new IllegalStateException("Store payment Redis resource did not close"); }
            }
        }
    }
}
