package com.ksyun.agent.runtime.registry;

import com.ksyun.agent.core.security.RoleDefinition;
import com.ksyun.agent.core.security.RoleProvider;
import com.ksyun.agent.core.store.RoleStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 接收多个 RoleProvider，将其返回的全部 RoleDefinition 注册到 RoleStore。
 * <p>
 * 语义：Provider 提供的是“首次启动默认角色”，数据库中已存在的角色是当前持久化配置。
 * 不吞 Provider 冲突或真实异常。没有 Provider 时应用正常启动。
 * 不得创建第二套 RoleRegistry；RoleStore 就是角色定义的存取边界。
 * <p>
 * 持久化兼容：Store 中已存在同名角色时跳过初始化，不覆盖 description 或 permissionCodes；
 * Store 中不存在时调用 save 创建。RoleStore.save 自身仍保持严格创建语义。
 * 本类保持纯 Java，不依赖 Spring、JDBC 或 PostgreSQL。
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
     * <p>
     * 步骤：
     * 1. 先收集本次所有 Provider 返回的角色，检测同次启动中不同 Provider 提供相同 roleName。
     * 2. 对每个角色，Store 中不存在时 save 创建；已存在时跳过初始化（幂等）。
     * 3. 并发首次启动时，依赖 Store.save 的严格创建语义处理检查后插入竞争：
     *    只有确认数据库中已经存在角色时才按幂等跳过，否则继续抛出真实异常。
     */
    public void registerAll() {
        // 1. 收集所有 Provider 角色并检测同次启动冲突
        Map<String, RoleDefinition> collected = new LinkedHashMap<>();
        for (RoleProvider provider : providers) {
            Collection<RoleDefinition> roles = safeProvide(provider);
            for (RoleDefinition role : roles) {
                RoleDefinition previous = collected.put(role.roleName(), role);
                if (previous != null) {
                    throw new IllegalStateException(
                            "Duplicate role provided in the same startup: " + role.roleName()
                                    + " (providers returned the same roleName)");
                }
            }
        }

        // 2. 逐个处理：已存在则跳过，不存在则创建
        for (RoleDefinition role : collected.values()) {
            registerOne(role);
        }
    }

    /**
     * 注册单个角色。
     * <p>
     * - Store 中已存在同名角色：跳过初始化，不覆盖现有配置。
     * - Store 中不存在：调用 save 创建。
     * - save 抛出“角色已存在”类异常时（并发首次启动），再次确认存在后按幂等跳过；
     *   其他真实异常继续向上抛出。
     */
    private void registerOne(RoleDefinition role) {
        String roleName = role.roleName();

        // 已存在则跳过初始化
        if (roleStore.find(roleName).isPresent()) {
            log.info("Role already exists, skip initialization: {}", roleName);
            return;
        }

        try {
            roleStore.save(role);
            log.info("Registered role: {}", roleName);
        } catch (Exception e) {
            // 并发首次启动：检查后插入竞争，确认已存在则按幂等跳过
            if (roleStore.find(roleName).isPresent()) {
                log.info("Role concurrently initialized, skip: {}", roleName);
                return;
            }
            log.error("Failed to register role: {}", roleName, e);
            throw e;
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

