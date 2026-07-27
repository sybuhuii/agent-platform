package com.ksyun.agent.bootstrap.sample.tool;

import com.ksyun.agent.core.sample.DemoRecordStore;
import com.ksyun.agent.core.tool.AgentTool;
import com.ksyun.agent.core.tool.ToolDefinition;
import com.ksyun.agent.core.tool.ToolInvocation;
import com.ksyun.agent.core.tool.ToolResult;
import com.ksyun.agent.core.tool.ToolRiskLevel;

import java.util.Map;

/**
 * 删除演示记录工具。
 * <p>
 * riskLevel=HIGH，描述明确标注需要人工审批。
 * 真实执行时才调用 DemoRecordStore.delete。
 * 审批前不得产生任何删除副作用。
 * 返回结构化删除结果。重复执行不得产生异常副作用。
 */
public class DeleteDemoRecordTool implements AgentTool {

    private static final ToolDefinition DEFINITION = new ToolDefinition(
            "delete_demo_record",
            "删除指定的示范记录。此操作不可逆，需要人工审批。只有在确认安全后才可执行。",
            """
            {
              "type": "object",
              "properties": {
                "recordId": {
                  "type": "string",
                  "description": "要删除的演示记录 ID"
                },
                "reason": {
                  "type": "string",
                  "description": "删除原因"
                }
              },
              "required": ["recordId", "reason"]
            }
            """,
            "tool:delete_demo_record:invoke",
            ToolRiskLevel.HIGH
    );

    private final DemoRecordStore store;

    public DeleteDemoRecordTool(DemoRecordStore store) {
        this.store = store;
    }

    @Override
    public ToolDefinition definition() {
        return DEFINITION;
    }

    @Override
    public ToolResult execute(ToolInvocation invocation) {
        var args = invocation.toolCall().arguments();
        String recordId = getString(args, "recordId");
        String reason = getString(args, "reason");

        if (recordId == null || recordId.isBlank()) {
            return ToolResult.failure("INVALID_ARGUMENT", "recordId must not be blank");
        }
        if (reason == null || reason.isBlank()) {
            return ToolResult.failure("INVALID_ARGUMENT", "reason must not be blank");
        }

        // 真正执行删除，只有 Terminal 真正执行时才调用 Store.delete
        DemoRecordStore.DeleteResult result = store.delete(recordId, reason);

        if (result.deleted()) {
            return ToolResult.success("Record deleted: " + result.message());
        } else {
            return ToolResult.failure("DELETE_FAILED", result.message());
        }
    }

    private static String getString(Map<String, Object> args, String key) {
        Object value = args.get(key);
        return value != null ? String.valueOf(value) : null;
    }
}
