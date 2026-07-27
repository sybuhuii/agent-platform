package com.ksyun.agent.core.approval;

/**
 * 中断操作类型。
 * <p>
 * 至少支持 TOOL 和 NODE，当前主要使用 TOOL。
 * NODE 为未来节点级中断预留。
 */
public enum OperationType {

    /** 工具调用中断 */
    TOOL,

    /** 图节点中断（预留） */
    NODE
}
