package com.ksyun.agent.core.approval;

import java.util.Map;

/** Explicit, stable codec for one NodeResumeData subtype. */
public interface NodeResumeDataCodec<D extends NodeResumeData> {

    String typeKey();

    Class<D> dataType();

    Map<String, Object> encode(D data);

    D decode(Map<String, Object> fields);
}
