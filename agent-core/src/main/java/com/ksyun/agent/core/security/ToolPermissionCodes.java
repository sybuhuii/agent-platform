package com.ksyun.agent.core.security;

/**
 * 工具权限编码集中定义。
 * <p>
 * 权限编码格式：{@code tool:{toolName}:invoke}
 * <p>
 * 管理员通配权限：{@code tool:*:invoke}
 * <p>
 * 所有工具调用权限必须通过本类方法生成，
 * 业务代码不得散落字符串拼接。
 * 不得把角色名称直接当权限。
 */
public final class ToolPermissionCodes {

    private ToolPermissionCodes() {
    }

    /** 工具调用权限前缀。 */
    private static final String TOOL_PREFIX = "tool:";

    /** 工具调用权限后缀。 */
    private static final String INVOKE_SUFFIX = ":invoke";

    /** 全工具调用通配权限。 */
    public static final String ALL_INVOKE = "tool:*:invoke";

    /**
     * 创建指定工具的调用权限编码。
     *
     * @param toolName 工具名称，不能为空
     * @return 权限编码字符串，格式为 {@code tool:{toolName}:invoke}
     */
    public static String invoke(String toolName) {
        if (toolName == null || toolName.isBlank()) {
            throw new IllegalArgumentException("toolName must not be blank");
        }
        return TOOL_PREFIX + toolName.trim() + INVOKE_SUFFIX;
    }

    /**
     * 将权限编码字符串封装为 {@link PermissionCode}。
     *
     * @param code 权限编码字符串
     * @return PermissionCode 值对象
     */
    public static PermissionCode asPermissionCode(String code) {
        return new PermissionCode(code);
    }
}
