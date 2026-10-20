package com.pawbridge.backend.boot.configuration;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.pawbridge.backend.community.internal.chat.application.MemberChat;
import com.pawbridge.backend.community.internal.chat.infrastructure.MemberChatSignals;
import com.pawbridge.backend.community.internal.chat.infrastructure.MemberChatTickets;
import com.pawbridge.backend.community.internal.chat.web.*;
import java.time.Clock;
import java.time.Duration;
import io.lettuce.core.ClientOptions;
import io.lettuce.core.SocketOptions;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.connection.RedisPassword;
import org.springframework.data.redis.connection.RedisStandaloneConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceClientConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.messaging.simp.user.SimpUserRegistry;

@Configuration(proxyBeanMethods=false)
@ConditionalOnProperty(name="pawbridge.chat.enabled",havingValue="true")
public class MemberChatTransportConfiguration {
    @Bean
    LettuceConnectionFactory memberChatRedisConnectionFactory(@Value("${spring.data.redis.host}") String host,
            @Value("${spring.data.redis.port:6379}") int port,@Value("${spring.data.redis.database:0}") int database,
            @Value("${spring.data.redis.username:}") String username,@Value("${spring.data.redis.password:}") String password){
        if(host.isBlank()||port<1||port>65535||database<0)throw new IllegalArgumentException("Explicit chat Redis settings required");
        var server=new RedisStandaloneConfiguration(host,port);server.setDatabase(database);
        if(!username.isBlank())server.setUsername(username);
        if(!password.isEmpty())server.setPassword(RedisPassword.of(password));
        var client=LettuceClientConfiguration.builder().commandTimeout(Duration.ofSeconds(2)).shutdownTimeout(Duration.ofSeconds(2))
                .clientOptions(ClientOptions.builder().socketOptions(SocketOptions.builder().connectTimeout(Duration.ofSeconds(2)).build()).build()).build();
        return new LettuceConnectionFactory(server,client);
    }
    @Bean StringRedisTemplate memberChatRedis(@Qualifier("memberChatRedisConnectionFactory") LettuceConnectionFactory factory){return new StringRedisTemplate(factory);}
    @Bean MemberChatTickets memberChatTickets(@Qualifier("memberChatRedis") StringRedisTemplate redis,@Qualifier("readClock") Clock clock,@Value("${pawbridge.chat.namespace}") String namespace){return new MemberChatTickets(redis,clock,namespace);}
    @Bean MemberChatSessions memberChatSessions(@Qualifier("memberChatRedis") StringRedisTemplate redis,ObjectProvider<MemberChat> chat,@Qualifier("readClock") Clock clock,@Value("${pawbridge.chat.namespace}") String namespace){return new MemberChatSessions(redis,chat,clock,namespace);}
    @Bean MemberChatSignals memberChatSignals(@Qualifier("memberChatRedis") StringRedisTemplate redis,SimpMessagingTemplate messaging,MemberChatSessions sessions,ObjectMapper mapper,SimpUserRegistry users,@Value("${pawbridge.chat.namespace}") String namespace){return new MemberChatSignals(redis,messaging,sessions,mapper,users,namespace);}
    @Bean MemberChatController memberChatController(MemberChat chat,MemberChatTickets tickets){return new MemberChatController(chat,tickets);}
    @Bean MemberChatSocketController memberChatSocketController(MemberChat chat,SimpMessagingTemplate messaging,@Qualifier("readClock") Clock clock){return new MemberChatSocketController(chat,messaging,clock);}
    @Bean MemberChatExceptionHandler memberChatExceptionHandler(){return new MemberChatExceptionHandler();}
}
