package com.ksyun.agent.core.approval;

import java.io.Serializable;

/**
 * 节点恢复数据标记接口。
 * 每个可中断节点定义自己的不可变 record，禁止保存框架对象、Bean 或 Future。
 */
public interface NodeResumeData extends Serializable {
}