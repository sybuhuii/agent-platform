package com.ksyun.agent.bootstrap.sample.tool;

import com.ksyun.agent.core.sample.DemoRecordStore;
import com.ksyun.agent.core.tool.AgentTool;
import com.ksyun.agent.core.tool.ToolDefinition;
import com.ksyun.agent.core.tool.ToolInvocation;
import com.ksyun.agent.core.tool.ToolResult;
import com.ksyun.agent.core.tool.ToolRiskLevel;

import java.util.Collection;
import java.util.Map;

/**
 * 列出演示记录工具。
 * <p>
 * riskLevel=LOW，无副作用，返回真实内存记录。
 */
public class ListDemoRecordsTool implements AgentTool {

    private static final ToolDefinition DEFINITION = new ToolDefinition(
            "list_demo_records",
            "列出示范记录。返回所有可用的演示数据记录。",
            """
            {
              "type": "object",
              "properties": {},
              "required": []
            }
            """,
            "tool:list_demo_records:invoke",
            ToolRiskLevel.LOW
    );

    private final DemoRecordStore store;

    public ListDemoRecordsTool(DemoRecordStore store) {
        this.store = store;
    }

    @Override
    public ToolDefinition definition() {
        return DEFINITION;
    }

    @Override
    public ToolResult execute(ToolInvocation invocation) {
        Collection<Map<String, Object>> records = store.list();
        if (records.isEmpty()) {
            return ToolResult.success("No demo records found.");
        }

        StringBuilder sb = new StringBuilder("Demo records:\n");
        for (Map<String, Object> record : records) {
            sb.append("- ").append(record.get("recordId"))
                    .append(": ").append(record.get("title"))
                    .append(" (").append(record.get("description")).append(")\n");
        }
        return ToolResult.success(sb.toString().trim());
    }
}
