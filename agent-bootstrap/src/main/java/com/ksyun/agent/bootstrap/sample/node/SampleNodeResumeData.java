package com.ksyun.agent.bootstrap.sample.node;

import com.ksyun.agent.core.approval.NodeResumeData;

/** 节点审批样例恢复所需的最小、不可变续跑数据。 */
public record SampleNodeResumeData(String stepId, int continuationIndex)
        implements NodeResumeData {

    public SampleNodeResumeData {
        if (stepId == null || stepId.isBlank()) {
            throw new IllegalArgumentException("stepId must not be blank");
        }
        if (continuationIndex < 0) {
            throw new IllegalArgumentException("continuationIndex must not be negative");
        }
    }
}
