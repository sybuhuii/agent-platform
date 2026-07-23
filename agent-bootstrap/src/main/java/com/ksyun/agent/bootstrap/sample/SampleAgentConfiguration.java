package com.ksyun.agent.bootstrap.sample;

import com.ksyun.agent.core.agent.AgentProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Sample Agent 装配配置。
 * <p>
 * 通过已有 AgentProviderRegistrar 自动注册 Sample Agent。
 * 受 agent.sample.enabled 属性控制，matchIfMissing=true 便于开发阶段。
 * 不重复创建 AgentRegistry 或 AgentProviderRegistrar。
 */
@Configuration
@ConditionalOnProperty(name = "agent.sample.enabled", havingValue = "true", matchIfMissing = true)
public class SampleAgentConfiguration {

    @Bean
    public AgentProvider sampleAgentProvider() {
        return new SampleAgentProvider();
    }
}
