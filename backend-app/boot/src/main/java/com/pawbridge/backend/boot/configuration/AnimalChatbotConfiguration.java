package com.pawbridge.backend.boot.configuration;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.pawbridge.backend.animal.internal.chatbot.infrastructure.client.FeignPythonChatbotClient;
import com.pawbridge.backend.animal.internal.chatbot.infrastructure.client.PythonChatbotClient;
import com.pawbridge.backend.animal.internal.chatbot.web.ChatbotController;
import com.pawbridge.backend.animal.internal.chatbot.web.ChatbotExceptionHandler;
import com.pawbridge.backend.animal.internal.chatbot.infrastructure.repository.ChatbotBlockLogRepository;
import com.pawbridge.backend.animal.internal.chatbot.infrastructure.repository.ChatbotMessageRepository;
import com.pawbridge.backend.animal.internal.chatbot.infrastructure.repository.ChatbotSessionRepository;
import com.pawbridge.backend.animal.internal.chatbot.infrastructure.service.*;
import com.pawbridge.backend.animal.internal.command.infrastructure.repository.AnimalRepository;
import feign.Client;
import feign.Request;
import java.net.URI;
import org.redisson.Redisson;
import org.redisson.api.RedissonClient;
import org.redisson.config.Config;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.core.env.Environment;
import org.springframework.data.jpa.repository.support.JpaRepositoryFactory;
import org.springframework.orm.jpa.JpaTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

/** Opt-in only. Reuses the existing Animal writer; no pool, schema generation, or component scan. */
@Configuration(proxyBeanMethods=false)
@ConditionalOnWebApplication(type=ConditionalOnWebApplication.Type.SERVLET)
@ConditionalOnProperty(prefix="pawbridge.animal-chatbot",name="enabled",havingValue="true",matchIfMissing=false)
@ConditionalOnExpression("'postgresql'.equals('${pawbridge.animal-query.backend:}')")
@Import(AnimalJpaWriterConfiguration.class)
public class AnimalChatbotConfiguration {
    private static final String INVALID="Animal chatbot requires explicit valid configuration";

    @Bean Settings animalChatbotSettings(Environment environment) {
        try {
            String url=required(environment,"python-ai-service.url");
            URI endpoint=URI.create(url);
            if(!("http".equalsIgnoreCase(endpoint.getScheme())||"https".equalsIgnoreCase(endpoint.getScheme()))||endpoint.getHost()==null)
                throw new IllegalArgumentException();
            String host=required(environment,"spring.data.redis.host");
            int port=environment.getProperty("spring.data.redis.port",Integer.class,6379);
            int database=environment.getProperty("spring.data.redis.database",Integer.class,0);
            if(port<1||port>65535||database<0)throw new IllegalArgumentException();
            URI redis=new URI("redis",null,host,port,null,null,null);
            if(redis.getHost()==null)throw new IllegalArgumentException();
            return new Settings(url,required(environment,"python-ai-service.internal-api-key"),required(environment,"chatbot.security.ip-hash-secret"),
                    redis.toString(),database,environment.getProperty("spring.data.redis.password",""),
                    environment.getProperty("chatbot.rate-limit.anonymous.ten-minutes",Long.class,5L),
                    environment.getProperty("chatbot.rate-limit.anonymous.daily",Long.class,20L),
                    environment.getProperty("chatbot.rate-limit.ip.ten-minutes",Long.class,20L),
                    environment.getProperty("chatbot.rate-limit.ip.daily",Long.class,100L),
                    environment.getProperty("chatbot.rate-limit.global.daily",Long.class,300L));
        } catch(Exception invalid) { throw new IllegalArgumentException(INVALID); }
    }

    private static String required(Environment environment,String name) {
        String value=environment.getProperty(name);if(value==null||value.isBlank())throw new IllegalArgumentException();return value;
    }

    @FunctionalInterface public interface RedisClientFactory { RedissonClient create(Config config); }

    @Bean @ConditionalOnMissingBean(name="animalChatbotRedisClientFactory")
    RedisClientFactory animalChatbotRedisClientFactory(){return Redisson::create;}

    @Bean(destroyMethod="shutdown")
    RedissonClient animalChatbotRedis(Settings settings,
            @Qualifier("animalChatbotRedisClientFactory") RedisClientFactory factory) {
        Config config=new Config();var server=config.useSingleServer().setAddress(settings.redisAddress()).setDatabase(settings.redisDatabase());
        if(!settings.redisPassword().isBlank())server.setPassword(settings.redisPassword());
        return factory.create(config);
    }

    @Bean ChatbotSessionRepository animalChatbotSessions(@Qualifier("animalCommandRepositoryFactory") JpaRepositoryFactory repositories) {
        return repositories.getRepository(ChatbotSessionRepository.class);
    }
    @Bean ChatbotMessageRepository animalChatbotMessages(@Qualifier("animalCommandRepositoryFactory") JpaRepositoryFactory repositories) {
        return repositories.getRepository(ChatbotMessageRepository.class);
    }
    @Bean ChatbotBlockLogRepository animalChatbotBlocks(@Qualifier("animalCommandRepositoryFactory") JpaRepositoryFactory repositories) {
        return repositories.getRepository(ChatbotBlockLogRepository.class);
    }

    @Bean TransactionTemplate animalChatbotReads(@Qualifier("animalCommandJpaTransactionManager") JpaTransactionManager manager) {
        var reads=new TransactionTemplate(manager);reads.setReadOnly(true);reads.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRED);return reads;
    }
    @Bean TransactionTemplate animalChatbotWrites(@Qualifier("animalCommandJpaTransactionManager") JpaTransactionManager manager) {
        var writes=new TransactionTemplate(manager);writes.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRED);return writes;
    }
    @Bean ChatbotCookieService animalChatbotCookies(){return new ChatbotCookieService();}
    @Bean ChatbotClientIpResolver animalChatbotClientIp(){return new ChatbotClientIpResolver();}
    @Bean ChatbotGuardService animalChatbotGuard(){return new ChatbotGuardService();}
    @Bean ChatbotIpHashService animalChatbotIpHash(Settings settings){return new ChatbotIpHashService(settings.ipHashSecret());}

    @Bean @ConditionalOnMissingBean(ChatbotRateLimitService.class)
    ChatbotRateLimitService animalChatbotRateLimits(@Qualifier("animalChatbotRedis") RedissonClient client,Settings settings) {
        return new ChatbotRateLimitService(client,settings.anonymousTenMinutes(),settings.anonymousDaily(),settings.ipTenMinutes(),settings.ipDaily(),settings.globalDaily());
    }
    @Bean PythonChatbotClient animalChatbotPython(Settings settings,ObjectMapper mapper) {
        return new FeignPythonChatbotClient(settings.pythonUrl(),mapper,new Client.Default(null,null),new Request.Options());
    }
    @Bean ChatbotService animalChatbotService(AnimalRepository animals,ChatbotSessionRepository sessions,
            ChatbotMessageRepository messages,ChatbotBlockLogRepository blocks,ChatbotCookieService cookies,ChatbotClientIpResolver ips,
            ChatbotIpHashService hashes,ChatbotRateLimitService quotas,ChatbotGuardService guard,PythonChatbotClient python,Settings settings,
            @Qualifier("animalChatbotReads") TransactionTemplate reads,@Qualifier("animalChatbotWrites") TransactionTemplate writes) {
        return new ChatbotService(animals,sessions,messages,blocks,cookies,ips,hashes,quotas,guard,python,settings.pythonKey(),reads,writes);
    }
    @Bean ChatbotController animalChatbotController(ChatbotService service){return new ChatbotController(service);}
    @Bean ChatbotExceptionHandler animalChatbotAdvice(){return new ChatbotExceptionHandler();}

    private record Settings(String pythonUrl,String pythonKey,String ipHashSecret,String redisAddress,int redisDatabase,String redisPassword,
                            long anonymousTenMinutes,long anonymousDaily,long ipTenMinutes,long ipDaily,long globalDaily) {
        @Override public String toString(){return "AnimalChatbotSettings[redacted]";}
    }
}
