package com.ksyun.agent.infrastructure.config;

import com.ksyun.agent.core.conversation.MessageIdGenerator;
import com.ksyun.agent.core.store.ConversationStore;
import com.ksyun.agent.infrastructure.store.PostgresConversationStore;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;

import java.time.Clock;

/**
 * PostgreSQL 会话历史自动配置。
 * <p>
 * 仅在 {@code agent.persistence.backend=postgresql} 时激活。
 * 装配顺序早于 {@link AgentFrameworkAutoConfiguration}。
 */
@AutoConfiguration(after = PostgreSQLAutoConfiguration.class, before = AgentFrameworkAutoConfiguration.class)
@ConditionalOnProperty(name = "agent.persistence.backend", havingValue = "postgresql")
public class PostgreSQLConversationAutoConfiguration {

    @Bean
    @ConditionalOnMissingBean(ConversationStore.class)
    public ConversationStore conversationStore(JdbcTemplate jdbcTemplate,
                                                PlatformTransactionManager transactionManager,
                                                MessageIdGenerator messageIdGenerator,
                                                Clock clock) {
        return new PostgresConversationStore(jdbcTemplate, transactionManager, messageIdGenerator, clock);
    }
}
