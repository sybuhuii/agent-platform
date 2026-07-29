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
 * <p>
 * ADMIN 全权限通过 tool:*:invoke 表达，不作为代码中的特殊绕过条件。
 * ADMIN 的 tool:*:invoke 只通过 ACL，不能绕过 ToolApprovalInterceptor 审批。
 * VISITOR 无 delete_demo_record 权限，ACL 必须拒绝，不创建 Checkpoint。
 * VISITOR 有 list_demo_records 权限。
 */
@Configuration
@ConditionalOnProperty(name = "agent.sample.enabled", havingValue = "true")
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
                            ToolPermissionCodes.invoke("echo"),
                            ToolPermissionCodes.invoke("list_demo_records"),
                            ToolPermissionCodes.invoke("remember_user_memory")
                    )
            );
        }
    }
}
