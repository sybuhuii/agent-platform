package com.ksyun.agent.bootstrap.sample;

import com.ksyun.agent.core.security.CredentialHasher;
import com.ksyun.agent.core.security.UserAccount;
import com.ksyun.agent.core.store.UserStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.CommandLineRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;

import java.util.Set;

/**
 * 示例用户初始化。
 * <p>
 * 仅负责创建示例用户（admin/visitor），不注册角色。
 * 角色统一通过 {@link SampleSecurityConfiguration} 的 RoleProvider 机制注册，
 * 避免重复注册冲突。
 */
@Configuration
@ConditionalOnProperty(name = "agent.sample.enabled", havingValue = "true")
public class SampleDataInitializer {

    private static final Logger log = LoggerFactory.getLogger(SampleDataInitializer.class);

    @Bean
    public CommandLineRunner initSampleUsers(
            UserStore userStore,
            CredentialHasher credentialHasher,
            Environment environment
    ) {
        return args -> {
            String adminPassword = environment.getProperty(
                    "AGENT_SAMPLE_ADMIN_PASSWORD", "admin123");
            String visitorPassword = environment.getProperty(
                    "AGENT_SAMPLE_VISITOR_PASSWORD", "visitor123");

            if (!userStore.existsByUsername("admin")) {
                userStore.save(new UserAccount(
                        "admin",
                        "admin",
                        credentialHasher.hash(adminPassword),
                        Set.of("ADMIN"),
                        true
                ));
                log.info("Sample user created: admin (ADMIN)");
            }

            if (!userStore.existsByUsername("visitor")) {
                userStore.save(new UserAccount(
                        "visitor",
                        "visitor",
                        credentialHasher.hash(visitorPassword),
                        Set.of("VISITOR"),
                        true
                ));
                log.info("Sample user created: visitor (VISITOR)");
            }
        };
    }
}
