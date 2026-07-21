package com.ksyun.agent.runtime.run;

import java.util.UUID;

/**
 * 基于 UUID 的运行 ID 生成器。
 * <p>
 * 生成不带空格且全局唯一性足够的 runId。
 */
public class UuidRunIdGenerator implements RunIdGenerator {

    @Override
    public String nextRunId() {
        return UUID.randomUUID().toString().replace("-", "");
    }
}
