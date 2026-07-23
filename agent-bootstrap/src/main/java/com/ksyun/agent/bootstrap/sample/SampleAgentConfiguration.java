package com.ksyun.agent.bootstrap.sample;

import com.ksyun.agent.core.agent.AgentProvider;
import com.ksyun.agent.core.supervisor.SupervisorProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Sample Agent 和 Supervisor 装配配置。
 * <p>
 * 通过已有 ProviderRegistrar 自动注册 Sample Agent 和 Supervisor。
 * 受 agent.sample.enabled 属性控制，matchIfMissing=true 便于开发阶段。
 * 不重复创建 AgentRegistry、SupervisorRegistry 或 Registrar。
 */
@Configuration
@ConditionalOnProperty(name = "agent.sample.enabled", havingValue = "true", matchIfMissing = true)
public class SampleAgentConfiguration {

    @Bean
    public AgentProvider sampleAgentProvider() {
        return new SampleAgentProvider();
    }

    @Bean
    public SupervisorProvider sampleSupervisorProvider() {
        return new SampleSupervisorProvider();
    }
}
