package com.ksyun.agent.bootstrap.sample;

import com.ksyun.agent.core.security.RoleDefinition;
import com.ksyun.agent.core.security.RoleProvider;
import com.ksyun.agent.core.security.SecurityPermissionCodes;
import com.ksyun.agent.core.security.ToolPermissionCodes;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.Collection;
import java.util.List;
import java.util.Set;

/**
 * Sample 角色定义装配配置。
 * <p>
 * 受 agent.sample.enabled 属性控制。
 * 通过 RoleProvider 和 RoleProviderRegistrar 自动注册。
 * 不得在 bootstrap 启动类手工调用 RoleStore.save。
 * 不得创建明文 Sample 密码。不得创建 Sample Session。
 * 无模型配置时 Sample 角色仍可注册。
 * ADMIN 全权限通过 tool:*:invoke 表达，不作为代码中的特殊绕过条件。
 */
@Configuration
@ConditionalOnProperty(name = "agent.sample.enabled", havingValue = "true", matchIfMissing = true)
public class SampleSecurityConfiguration {

    @Bean
    public RoleProvider sampleRoleProvider() {
        return new SampleRoleProvider();
    }

    private static class SampleRoleProvider implements RoleProvider {

        @Override
        public Collection<RoleDefinition> provideRoles() {
            return List.of(adminRole(), visitorRole());
        }

        private RoleDefinition adminRole() {
            return new RoleDefinition(
                    "ADMIN",
                    "Administrator with full tool and management access",
                    Set.of(
                            ToolPermissionCodes.ALL_INVOKE,
                            SecurityPermissionCodes.USER_READ,
                            SecurityPermissionCodes.USER_WRITE,
                            SecurityPermissionCodes.ROLE_READ,
                            SecurityPermissionCodes.ROLE_WRITE,
                            SecurityPermissionCodes.SESSION_REVOKE
                    )
            );
        }

        private RoleDefinition visitorRole() {
            return new RoleDefinition(
                    "VISITOR",
                    "Visitor with limited tool access",
                    Set.of(
                            ToolPermissionCodes.invoke("calculator"),
                            ToolPermissionCodes.invoke("current_time"),
                            ToolPermissionCodes.invoke("echo")
                    )
            );
        }
    }
}
