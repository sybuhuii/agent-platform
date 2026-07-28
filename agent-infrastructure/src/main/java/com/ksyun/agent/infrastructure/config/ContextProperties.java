package com.ksyun.agent.infrastructure.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 上下文管理配置属性。
 * <p>
 * 约束：
 * - maxMessages 必须 > 0
 * - max-context-tokens 必须 > 0
 * - 所有预留值必须 >= 0
 * - 有效消息预算必须 > 0
 * - 默认值集中定义
 * - 不加入模型 API Key
 * - 不根据模型名称字符串隐式猜测窗口大小
 * - 配置非法时启动失败并给出明确原因
 */
@ConfigurationProperties(prefix = "agent.context")
public class ContextProperties {

    /** 上下文管理是否启用 */
    private boolean enabled = true;

    /** 消息数裁剪是否启用 */
    private boolean messageCountTrimmingEnabled = true;

    /** 保留的最大非 System 消息数量，必须 > 0 */
    private int maxMessages = 20;

    /** Token 裁剪是否启用 */
    private boolean tokenTrimmingEnabled = true;

    /** 模型上下文窗口大小，必须 > 0 */
    private int maxContextTokens = 8192;

    /** 预留输出 Token，必须 >= 0 */
    private int reservedOutputTokens = 1024;

    /** 协议及工具定义预留 Token，必须 >= 0 */
    private int reservedProtocolTokens = 256;

    /** 安全余量 Token，必须 >= 0 */
    private int safetyMarginTokens = 128;

    /** 摘要配置 */
    private Summary summary = new Summary();

    public ContextProperties() {
    }

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public boolean isMessageCountTrimmingEnabled() {
        return messageCountTrimmingEnabled;
    }

    public void setMessageCountTrimmingEnabled(boolean messageCountTrimmingEnabled) {
        this.messageCountTrimmingEnabled = messageCountTrimmingEnabled;
    }

    public int getMaxMessages() {
        return maxMessages;
    }

    public void setMaxMessages(int maxMessages) {
        if (maxMessages <= 0) {
            throw new IllegalArgumentException(
                    "agent.context.max-messages must be > 0, got: " + maxMessages);
        }
        this.maxMessages = maxMessages;
    }

    public boolean isTokenTrimmingEnabled() {
        return tokenTrimmingEnabled;
    }

    public void setTokenTrimmingEnabled(boolean tokenTrimmingEnabled) {
        this.tokenTrimmingEnabled = tokenTrimmingEnabled;
    }

    public int getMaxContextTokens() {
        return maxContextTokens;
    }

    public void setMaxContextTokens(int maxContextTokens) {
        if (maxContextTokens <= 0) {
            throw new IllegalArgumentException(
                    "agent.context.max-context-tokens must be > 0, got: " + maxContextTokens);
        }
        this.maxContextTokens = maxContextTokens;
    }

    public int getReservedOutputTokens() {
        return reservedOutputTokens;
    }

    public void setReservedOutputTokens(int reservedOutputTokens) {
        if (reservedOutputTokens < 0) {
            throw new IllegalArgumentException(
                    "agent.context.reserved-output-tokens must be >= 0, got: " + reservedOutputTokens);
        }
        this.reservedOutputTokens = reservedOutputTokens;
    }

    public int getReservedProtocolTokens() {
        return reservedProtocolTokens;
    }

    public void setReservedProtocolTokens(int reservedProtocolTokens) {
        if (reservedProtocolTokens < 0) {
            throw new IllegalArgumentException(
                    "agent.context.reserved-protocol-tokens must be >= 0, got: " + reservedProtocolTokens);
        }
        this.reservedProtocolTokens = reservedProtocolTokens;
    }

    public int getSafetyMarginTokens() {
        return safetyMarginTokens;
    }

    public void setSafetyMarginTokens(int safetyMarginTokens) {
        if (safetyMarginTokens < 0) {
            throw new IllegalArgumentException(
                    "agent.context.safety-margin-tokens must be >= 0, got: " + safetyMarginTokens);
        }
        this.safetyMarginTokens = safetyMarginTokens;
    }

    public Summary getSummary() {
        return summary;
    }

    public void setSummary(Summary summary) {
        this.summary = summary;
    }

    /**
     * 摘要配置属性。
     * <p>
     * 约束：
     * - trigger-ratio 必须 > 0 且 < 1
     * - min-source-tokens 必须 > 0
     * - max-summary-tokens 必须 > 0
     * - recent-groups-to-preserve 必须 >= 1
     * - max-summary-tokens 必须小于有效消息预算
     * - 默认值只集中定义一次
     * - 不得配置模型 API Key
     * - 配置非法时启动失败并给出明确原因
     */
    public static class Summary {

        /** 摘要是否启用 */
        private boolean enabled = true;

        /** 触发比例，必须 > 0 且 < 1 */
        private double triggerRatio = 0.80;

        /** 最少源 Token 数，必须 > 0 */
        private int minSourceTokens = 512;

        /** 摘要最大 Token 数，必须 > 0 */
        private int maxSummaryTokens = 512;

        /** 保留的最近原子组数量，必须 >= 1 */
        private int recentGroupsToPreserve = 4;

        public Summary() {
        }

        public boolean isEnabled() {
            return enabled;
        }

        public void setEnabled(boolean enabled) {
            this.enabled = enabled;
        }

        public double getTriggerRatio() {
            return triggerRatio;
        }

        public void setTriggerRatio(double triggerRatio) {
            if (triggerRatio <= 0 || triggerRatio >= 1) {
                throw new IllegalArgumentException(
                        "agent.context.summary.trigger-ratio must be > 0 and < 1, got: " + triggerRatio);
            }
            this.triggerRatio = triggerRatio;
        }

        public int getMinSourceTokens() {
            return minSourceTokens;
        }

        public void setMinSourceTokens(int minSourceTokens) {
            if (minSourceTokens <= 0) {
                throw new IllegalArgumentException(
                        "agent.context.summary.min-source-tokens must be > 0, got: " + minSourceTokens);
            }
            this.minSourceTokens = minSourceTokens;
        }

        public int getMaxSummaryTokens() {
            return maxSummaryTokens;
        }

        public void setMaxSummaryTokens(int maxSummaryTokens) {
            if (maxSummaryTokens <= 0) {
                throw new IllegalArgumentException(
                        "agent.context.summary.max-summary-tokens must be > 0, got: " + maxSummaryTokens);
            }
            this.maxSummaryTokens = maxSummaryTokens;
        }

        public int getRecentGroupsToPreserve() {
            return recentGroupsToPreserve;
        }

        public void setRecentGroupsToPreserve(int recentGroupsToPreserve) {
            if (recentGroupsToPreserve < 1) {
                throw new IllegalArgumentException(
                        "agent.context.summary.recent-groups-to-preserve must be >= 1, got: " + recentGroupsToPreserve);
            }
            this.recentGroupsToPreserve = recentGroupsToPreserve;
        }
    }
}
