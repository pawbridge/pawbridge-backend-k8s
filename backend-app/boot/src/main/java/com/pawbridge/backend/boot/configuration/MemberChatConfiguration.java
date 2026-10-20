package com.pawbridge.backend.boot.configuration;

import com.pawbridge.backend.community.internal.chat.application.MemberChat;
import com.pawbridge.backend.community.internal.chat.application.MemberChatHints;
import com.pawbridge.backend.community.internal.chat.infrastructure.JdbcMemberChat;
import com.pawbridge.backend.community.internal.chat.infrastructure.JdbcMemberChatRepository;
import com.pawbridge.backend.community.internal.note.application.NoteSendStore;
import com.pawbridge.backend.user.api.ContactMemberQuery;
import java.time.Clock;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.DependsOn;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.JdbcTransactionManager;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;
import org.springframework.transaction.support.TransactionTemplate;

@Configuration(proxyBeanMethods=false)
@ConditionalOnProperty(name="pawbridge.chat.enabled",havingValue="true")
@EnableScheduling
@Import({MemberChatTransportConfiguration.class,MemberChatSocketConfiguration.class,MemberChatConfiguration.RetentionConfiguration.class})
public class MemberChatConfiguration {
    @Bean
    JdbcMemberChatRepository memberChatRepository(@Qualifier("writeJdbc") JdbcTemplate jdbc) {
        return new JdbcMemberChatRepository(jdbc);
    }

    @Bean
    MemberChat memberChat(JdbcMemberChatRepository repository,NoteSendStore contact,ContactMemberQuery users,
                          MemberChatHints hints,@Qualifier("readClock") Clock clock,
                          @Qualifier("writeTransactionManager") JdbcTransactionManager manager) {
        var transaction=new TransactionTemplate(manager);transaction.setTimeout(15);
        return new JdbcMemberChat(repository,contact,users,hints,clock,transaction);
    }

    @Bean
    @DependsOn({"writeDataSource","memberChatRedisConnectionFactory"})
    ThreadPoolTaskScheduler memberChatMaintenanceScheduler() {
        var scheduler=new ThreadPoolTaskScheduler();scheduler.setPoolSize(1);
        scheduler.setThreadNamePrefix("member-chat-maintenance-");
        scheduler.setAcceptTasksAfterContextClose(false);
        scheduler.setWaitForTasksToCompleteOnShutdown(false);
        scheduler.setContinueExistingPeriodicTasksAfterShutdownPolicy(false);
        scheduler.setExecuteExistingDelayedTasksAfterShutdownPolicy(false);
        scheduler.setRemoveOnCancelPolicy(true);scheduler.setAwaitTerminationSeconds(5);
        scheduler.setErrorHandler(failure->org.slf4j.LoggerFactory.getLogger(MemberChatConfiguration.class)
                .warn("Chat maintenance failed ({})",failure.getClass().getSimpleName()));
        return scheduler;
    }

    @Configuration(proxyBeanMethods=false)
    @ConditionalOnProperty(name="pawbridge.jobs.cleanup-enabled",havingValue="true",matchIfMissing=true)
    static class RetentionConfiguration {
        @Bean MemberChatRetention memberChatRetention(MemberChat chat) { return new MemberChatRetention(chat); }
    }

    static final class MemberChatRetention {
        private final MemberChat chat;
        MemberChatRetention(MemberChat chat) {this.chat=chat;}
        @Scheduled(scheduler="memberChatMaintenanceScheduler",fixedDelay=300_000,initialDelay=300_000)
        public void expire() {chat.expire();}
    }
}
