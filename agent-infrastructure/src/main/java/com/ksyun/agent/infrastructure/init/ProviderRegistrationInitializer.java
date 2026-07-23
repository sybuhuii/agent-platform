package com.ksyun.agent.infrastructure.init;

import com.ksyun.agent.runtime.registry.AgentProviderRegistrar;
import com.ksyun.agent.runtime.registry.SupervisorProviderRegistrar;
import com.ksyun.agent.runtime.registry.ToolProviderRegistrar;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.CommandLineRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 应用启动阶段执行 Provider 自动注册。
 */
@Configuration
public class ProviderRegistrationInitializer {

    private static final Logger log = LoggerFactory.getLogger(ProviderRegistrationInitializer.class);

    @Bean
    public CommandLineRunner registerProviders(
            AgentProviderRegistrar agentProviderRegistrar,
            ToolProviderRegistrar toolProviderRegistrar,
            SupervisorProviderRegistrar supervisorProviderRegistrar
    ) {
        return args -> {
            log.info("Registering agents from providers...");
            agentProviderRegistrar.registerAll();
            log.info("Registering tools from providers...");
            toolProviderRegistrar.registerAll();
            log.info("Registering supervisors from providers...");
            supervisorProviderRegistrar.registerAll();
            log.info("Provider registration completed.");
        };
    }
}
