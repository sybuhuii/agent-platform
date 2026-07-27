package com.ksyun.agent.infrastructure.config;

import com.ksyun.agent.application.agent.AuthenticatedAgentApplicationService;
import com.ksyun.agent.application.auth.AuthApplicationService;
import com.ksyun.agent.application.auth.PermissionAuthorizationService;
import com.ksyun.agent.application.auth.RoleManagementApplicationService;
import com.ksyun.agent.application.auth.SessionTtlConfig;
import com.ksyun.agent.application.auth.SessionValidationService;
import com.ksyun.agent.application.auth.UserManagementApplicationService;
import com.ksyun.agent.application.auth.UserSessionRevocationService;
import com.ksyun.agent.application.supervisor.AuthenticatedSupervisorApplicationService;
import com.ksyun.agent.core.security.CredentialHasher;
import com.ksyun.agent.core.security.RolePermissionResolver;
import com.ksyun.agent.core.security.RoleProvider;
import com.ksyun.agent.core.security.SessionIdGenerator;
import com.ksyun.agent.core.security.UserIdGenerator;
import com.ksyun.agent.core.store.RoleStore;
import com.ksyun.agent.core.store.SessionStore;
import com.ksyun.agent.core.store.UserStore;
import com.ksyun.agent.infrastructure.security.BCryptCredentialHasher;
import com.ksyun.agent.infrastructure.security.SecureRandomSessionIdGenerator;
import com.ksyun.agent.infrastructure.security.UuidUserIdGenerator;
import com.ksyun.agent.infrastructure.store.InMemoryRoleStore;
import com.ksyun.agent.infrastructure.store.InMemorySessionStore;
import com.ksyun.agent.infrastructure.store.InMemoryUserStore;
import com.ksyun.agent.runtime.registry.AgentRegistry;
import com.ksyun.agent.runtime.registry.RoleProviderRegistrar;
import com.ksyun.agent.runtime.registry.SupervisorRegistry;
import com.ksyun.agent.runtime.run.RunIdGenerator;
import com.ksyun.agent.runtime.security.DefaultRolePermissionResolver;
import com.ksyun.agent.runtime.supervisor.SupervisorEngine;
import com.ksyun.agent.runtime.react.ReactAgentEngine;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Duration;
import java.util.List;

/**
 * 安全基础设施配置。
 * <p>
 * 整体受 agent.auth.enabled 控制，关闭时整套安全基础设施
 * （Store、Hasher、认证服务）都不加载。
 * 所有 Bean 均使用 @ConditionalOnMissingBean，支持外部替换。
 */
@AutoConfiguration(after = {
        ReactEngineConfiguration.class,
        SupervisorEngineConfiguration.class
})
@ConditionalOnProperty(name = "agent.auth.enabled", havingValue = "true", matchIfMissing = true)
public class SecurityConfiguration {

    // ---- 底层 Store 和安全组件 ----

    @Bean
    @ConditionalOnMissingBean
    public UserStore userStore() {
        return new InMemoryUserStore();
    }

    @Bean
    @ConditionalOnMissingBean
    public RoleStore roleStore() {
        return new InMemoryRoleStore();
    }

    @Bean
    @ConditionalOnMissingBean
    public SessionStore sessionStore() {
        return new InMemorySessionStore();
    }

    @Bean
    @ConditionalOnMissingBean
    public CredentialHasher credentialHasher() {
        return new BCryptCredentialHasher();
    }

    @Bean
    @ConditionalOnMissingBean
    public SessionIdGenerator sessionIdGenerator() {
        return new SecureRandomSessionIdGenerator();
    }

    @Bean
    @ConditionalOnMissingBean
    public UserIdGenerator userIdGenerator() {
        return new UuidUserIdGenerator();
    }

    @Bean
    @ConditionalOnMissingBean
    public RolePermissionResolver rolePermissionResolver(RoleStore roleStore) {
        return new DefaultRolePermissionResolver(roleStore);
    }

    @Bean
    public RoleProviderRegistrar roleProviderRegistrar(
            RoleStore roleStore,
            List<RoleProvider> providers
    ) {
        return new RoleProviderRegistrar(roleStore, providers);
    }

    // ---- 上层认证服务 ----

    @Bean
    public SessionTtlConfig sessionTtlConfig(
            @Value("${agent.security.session.ttl:PT8H}") Duration ttl) {
        return SessionTtlConfig.from(ttl);
    }

    @Bean
    public SessionValidationService sessionValidationService(SessionStore sessionStore) {
        return new SessionValidationService(sessionStore);
    }

    @Bean
    public AuthApplicationService authApplicationService(
            UserStore userStore,
            SessionStore sessionStore,
            CredentialHasher credentialHasher,
            SessionIdGenerator sessionIdGenerator,
            RolePermissionResolver rolePermissionResolver,
            SessionValidationService sessionValidationService,
            SessionTtlConfig sessionTtlConfig) {
        return new AuthApplicationService(
                userStore, sessionStore, credentialHasher,
                sessionIdGenerator, rolePermissionResolver,
                sessionValidationService, sessionTtlConfig
        );
    }

    // ---- 管理权限和 Session 撤销 ----

    @Bean
    public PermissionAuthorizationService permissionAuthorizationService() {
        return new PermissionAuthorizationService();
    }

    @Bean
    public UserSessionRevocationService userSessionRevocationService(SessionStore sessionStore) {
        return new UserSessionRevocationService(sessionStore);
    }

    @Bean
    public UserManagementApplicationService userManagementApplicationService(
            UserStore userStore,
            RoleStore roleStore,
            CredentialHasher credentialHasher,
            PermissionAuthorizationService permissionAuthorizationService,
            UserSessionRevocationService sessionRevocationService,
            UserIdGenerator userIdGenerator) {
        return new UserManagementApplicationService(
                userStore, roleStore, credentialHasher,
                permissionAuthorizationService,
                sessionRevocationService, userIdGenerator
        );
    }

    @Bean
    public RoleManagementApplicationService roleManagementApplicationService(
            RoleStore roleStore,
            UserStore userStore,
            PermissionAuthorizationService permissionAuthorizationService,
            UserSessionRevocationService sessionRevocationService) {
        return new RoleManagementApplicationService(
                roleStore, userStore,
                permissionAuthorizationService, sessionRevocationService
        );
    }

    @Bean
    @ConditionalOnBean(ReactAgentEngine.class)
    public AuthenticatedAgentApplicationService authenticatedAgentApplicationService(
            AgentRegistry agentRegistry,
            ReactAgentEngine reactAgentEngine,
            RunIdGenerator runIdGenerator) {
        return new AuthenticatedAgentApplicationService(agentRegistry, reactAgentEngine, runIdGenerator);
    }

    @Bean
    @ConditionalOnBean(SupervisorEngine.class)
    public AuthenticatedSupervisorApplicationService authenticatedSupervisorApplicationService(
            SupervisorRegistry supervisorRegistry,
            SupervisorEngine supervisorEngine,
            RunIdGenerator runIdGenerator) {
        return new AuthenticatedSupervisorApplicationService(supervisorRegistry, supervisorEngine, runIdGenerator);
    }
}
