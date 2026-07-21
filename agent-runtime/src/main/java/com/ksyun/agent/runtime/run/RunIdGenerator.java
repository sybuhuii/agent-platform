package com.ksyun.agent.runtime.run;

/**
 * 运行 ID 生成器接口。
 */
public interface RunIdGenerator {

    /**
     * 生成下一个运行 ID。
     */
    String nextRunId();
}
