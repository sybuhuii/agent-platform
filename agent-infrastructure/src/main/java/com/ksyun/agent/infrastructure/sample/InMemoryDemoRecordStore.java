package com.ksyun.agent.infrastructure.sample;

import com.ksyun.agent.core.sample.DemoRecordStore;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 内存演示记录存储实现。
 * <p>
 * 初始包含 demo-1、demo-2。
 * 线程安全，使用 ConcurrentHashMap。不使用 static Map。
 * list 返回不可变快照。
 * delete 只删除内存演示记录，重复删除幂等。
 * 不访问文件、数据库和外部资源。
 */
public class InMemoryDemoRecordStore implements DemoRecordStore {

    private final ConcurrentHashMap<String, Map<String, Object>> records = new ConcurrentHashMap<>();

    public InMemoryDemoRecordStore() {
        // 初始化演示记录
        records.put("demo-1", createRecord("demo-1", "Sample Document A", "Initial demo record"));
        records.put("demo-2", createRecord("demo-2", "Sample Document B", "Initial demo record"));
    }

    @Override
    public Collection<Map<String, Object>> list() {
        List<Map<String, Object>> result = new ArrayList<>();
        for (Map<String, Object> record : records.values()) {
            result.add(copyRecord(record));
        }
        return Collections.unmodifiableList(result);
    }

    @Override
    public DeleteResult delete(String recordId, String reason) {
        if (recordId == null || recordId.isBlank()) {
            return new DeleteResult(false, "recordId must not be blank");
        }

        Map<String, Object> removed = records.remove(recordId);
        if (removed == null) {
            // 重复删除幂等：记录已不存在，返回成功并标记 alreadyAbsent
            return new DeleteResult(true,
                    "Record '" + recordId + "' already absent (idempotent)");
        }

        return new DeleteResult(true,
                "Record '" + recordId + "' deleted. Reason: " + (reason != null ? reason : "N/A"));
    }

    private static Map<String, Object> createRecord(String id, String title, String description) {
        Map<String, Object> record = new LinkedHashMap<>();
        record.put("recordId", id);
        record.put("title", title);
        record.put("description", description);
        return Collections.unmodifiableMap(record);
    }

    private static Map<String, Object> copyRecord(Map<String, Object> original) {
        Map<String, Object> copy = new LinkedHashMap<>(original);
        return Collections.unmodifiableMap(copy);
    }
}
