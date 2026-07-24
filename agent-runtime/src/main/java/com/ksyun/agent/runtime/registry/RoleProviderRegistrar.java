package com.ksyun.agent.runtime.registry;

import com.ksyun.agent.core.security.RoleDefinition;
import com.ksyun.agent.core.security.RoleProvider;
import com.ksyun.agent.core.store.RoleStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Collection;
import java.util.List;

/**
 * 接收多个 RoleProvider，将其返回的全部 RoleDefinition 注册到 RoleStore。
 * <p>
 * 不吞重复角色异常。没有 Provider 时应用正常启动。
 * 不得创建第二套 RoleRegistry；RoleStore 就是角色定义的存取边界。
 */
public class RoleProviderRegistrar {

    private static final Logger log = LoggerFactory.getLogger(RoleProviderRegistrar.class);

    private final RoleStore roleStore;
    private final List<RoleProvider> providers;

    public RoleProviderRegistrar(RoleStore roleStore, List<RoleProvider> providers) {
        this.roleStore = roleStore;
        this.providers = providers != null ? providers : List.of();
    }

    /**
     * 将所有 Provider 提供的角色注册到 RoleStore。
     */
    public void registerAll() {
        for (RoleProvider provider : providers) {
            Collection<RoleDefinition> roles = safeProvide(provider);
            for (RoleDefinition role : roles) {
                try {
                    roleStore.save(role);
                    log.info("Registered role: {}", role.roleName());
                } catch (Exception e) {
                    log.error("Failed to register role: {}", role.roleName(), e);
                    throw e;
                }
            }
        }
    }

    private Collection<RoleDefinition> safeProvide(RoleProvider provider) {
        try {
            Collection<RoleDefinition> result = provider.provideRoles();
            return result != null ? result : List.of();
        } catch (Exception e) {
            throw new IllegalStateException(
                    "RoleProvider " + provider.getClass().getName() + " failed to provide roles", e
            );
        }
    }
}
