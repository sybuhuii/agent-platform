package com.ksyun.agent.runtime.checkpoint.thread;

import com.ksyun.agent.core.exception.AgentErrorCode;
import com.ksyun.agent.core.exception.AgentFrameworkException;

import java.util.Objects;
import java.util.regex.Pattern;

/**
 * 线程 ID 校验器，纯 Java 实现。
 * <p>
 * 规则：
 * - threadId 不能为空
 * - trim 后不能为空
 * - 最大长度 128
 * - 只允许英文字母、数字、- 和 _
 * - 不得包含空白、/、\\、..、换行
 * - 不得自动修正非法 ID
 * - 非法时抛 INVALID_THREAD_ID
 * - 错误信息不得回显完整超长输入
 * - 保持无状态和线程安全
 */
public class ThreadIdValidator {

    private static final int MAX_LENGTH = 128;
    private static final Pattern VALID_PATTERN = Pattern.compile("^[a-zA-Z0-9_-]+$");
    private static final Pattern ILLEGAL_PATTERN = Pattern.compile("\\s|/|\\\\|\\.\\.|\\n");

    /**
     * 校验线程 ID。
     *
     * @param threadId 线程 ID
     * @throws AgentFrameworkException 非法时抛 INVALID_THREAD_ID
     */
    public void validate(String threadId) {
        Objects.requireNonNull(threadId, "threadId must not be null");

        String trimmed = threadId.trim();
        if (trimmed.isEmpty()) {
            throw new AgentFrameworkException(AgentErrorCode.INVALID_THREAD_ID,
                    "threadId must not be blank");
        }
        if (trimmed.length() > MAX_LENGTH) {
            throw new AgentFrameworkException(AgentErrorCode.INVALID_THREAD_ID,
                    "threadId exceeds max length " + MAX_LENGTH + ", got length " + trimmed.length());
        }
        if (!VALID_PATTERN.matcher(trimmed).matches()) {
            throw new AgentFrameworkException(AgentErrorCode.INVALID_THREAD_ID,
                    "threadId contains invalid characters, only letters, digits, - and _ allowed");
        }
        if (ILLEGAL_PATTERN.matcher(threadId).find()) {
            throw new AgentFrameworkException(AgentErrorCode.INVALID_THREAD_ID,
                    "threadId contains prohibited characters (whitespace, slash, backslash, path traversal or newline)");
        }
    }
}
