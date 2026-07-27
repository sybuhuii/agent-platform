package com.ksyun.agent.core.sample;

import java.util.Collection;
import java.util.Map;

/**
 * 演示记录存储接口。
 * <p>
 * 仅保存非敏感演示数据。线程安全。不使用 static Map。
 * 不连接真实数据库。不删除真实文件或外部资源。
 */
public interface DemoRecordStore {

    /**
     * 列出所有演示记录，返回不可变快照。
     */
    Collection<Map<String, Object>> list();

    /**
     * 按 recordId 删除演示记录。
     * 重复删除幂等，返回是否实际删除。
     *
     * @param recordId 记录 ID
     * @param reason   删除原因
     * @return 删除结果，包含是否成功和说明
     */
    DeleteResult delete(String recordId, String reason);

    /**
     * 删除结果。
     */
    record DeleteResult(boolean deleted, String message) {}
}
