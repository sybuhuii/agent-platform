package com.ksyun.agent.bootstrap.sample.node;

import com.ksyun.agent.core.approval.NodeResumeDataCodec;

import java.util.LinkedHashMap;
import java.util.Map;

/** Stable codec for the sample node's resumable state. */
public final class SampleNodeResumeDataCodec implements NodeResumeDataCodec<SampleNodeResumeData> {

    @Override
    public String typeKey() {
        return "sample_node_approval";
    }

    @Override
    public Class<SampleNodeResumeData> dataType() {
        return SampleNodeResumeData.class;
    }

    @Override
    public Map<String, Object> encode(SampleNodeResumeData data) {
        Map<String, Object> fields = new LinkedHashMap<>();
        fields.put("stepId", data.stepId());
        fields.put("continuationIndex", data.continuationIndex());
        return Map.copyOf(fields);
    }

    @Override
    public SampleNodeResumeData decode(Map<String, Object> fields) {
        Object stepId = fields.get("stepId");
        Object continuationIndex = fields.get("continuationIndex");
        if (!(stepId instanceof String safeStepId) || !(continuationIndex instanceof Number safeIndex)) {
            throw new IllegalArgumentException("Invalid sample node resume data");
        }
        return new SampleNodeResumeData(safeStepId, safeIndex.intValue());
    }
}
