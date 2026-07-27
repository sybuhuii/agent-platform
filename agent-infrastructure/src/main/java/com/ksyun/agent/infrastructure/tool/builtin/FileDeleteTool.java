package com.ksyun.agent.infrastructure.tool.builtin;

import com.ksyun.agent.core.tool.AgentTool;
import com.ksyun.agent.core.tool.ToolDefinition;
import com.ksyun.agent.core.tool.ToolInvocation;
import com.ksyun.agent.core.tool.ToolResult;
import com.ksyun.agent.core.tool.ToolRiskLevel;

/**
 * 模拟文件删除工具（Sample）。
 * <p>
 * riskLevel=HIGH，用于演示危险工具审批流程。
 * 实际不执行任何文件操作，仅返回模拟结果。
 * <p>
 * 本工具仅用于演示，不得在生产环境中使用。
 */
public class FileDeleteTool implements AgentTool {

    private static final ToolDefinition DEFINITION = new ToolDefinition(
            "file_delete",
            "删除指定路径的文件（模拟操作，实际不执行删除）。此操作不可逆，需要管理员审批。",
            """
            {
              "type": "object",
              "properties": {
                "path": {
                  "type": "string",
                  "description": "要删除的文件路径"
                }
              },
              "required": ["path"]
            }
            """,
            "tool:file_delete:invoke",
            ToolRiskLevel.HIGH
    );

    @Override
    public ToolDefinition definition() {
        return DEFINITION;
    }

    @Override
    public ToolResult execute(ToolInvocation invocation) {
        var args = invocation.toolCall().arguments();
        String path = ToolArgs.getString(args, "path");

        if (path == null || path.isBlank()) {
            return ToolResult.failure("INVALID_ARGUMENT", "path must not be blank");
        }

        // 模拟删除操作（实际不执行任何文件操作）
        return ToolResult.success("File deleted (simulated): " + path);
    }
}
