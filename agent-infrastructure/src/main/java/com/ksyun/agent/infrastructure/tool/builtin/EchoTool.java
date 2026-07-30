package com.ksyun.agent.infrastructure.tool.builtin;

import com.ksyun.agent.core.exception.AgentErrorCode;
import com.ksyun.agent.core.tool.AgentTool;
import com.ksyun.agent.core.tool.ToolDefinition;
import com.ksyun.agent.core.tool.ToolInvocation;
import com.ksyun.agent.core.tool.ToolResult;
import com.ksyun.agent.core.tool.ToolRiskLevel;

import java.util.Map;

/**
 * 原样返回输入文本，用于验证工具调用链。
 */
public class EchoTool implements AgentTool {

    private static final String NAME = "echo";
    private static final String INPUT_SCHEMA = """
            {
              "type": "object",
              "properties": {
                "text": {
                  "type": "string",
                  "description": "Text to echo back"
                }
              },
              "required": ["text"],
              "additionalProperties": false
            }
            """;
    private static final int MAX_TEXT_LENGTH = 4000;

    private static final ToolDefinition DEFINITION = new ToolDefinition(
            NAME,
            "Echo back the input text, used for verifying the tool invocation chain",
            INPUT_SCHEMA,
            "",
            ToolRiskLevel.LOW
    );

    @Override
    public ToolDefinition definition() {
        return DEFINITION;
    }

    @Override
    public ToolResult execute(ToolInvocation invocation) {
        Map<String, Object> args = invocation.toolCall().arguments();
        String text = ToolArgs.getString(args, "text");

        if (!args.containsKey("text")) {
            return ToolResult.failure(
                    AgentErrorCode.INVALID_ARGUMENT.name(),
                    "Parameter 'text' is required"
            );
        }
        if (text == null) {
            return ToolResult.failure(
                    AgentErrorCode.INVALID_ARGUMENT.name(),
                    "Parameter 'text' must be a string"
            );
        }

        if (text.length() > MAX_TEXT_LENGTH) {
            return ToolResult.failure(
                    AgentErrorCode.INVALID_ARGUMENT.name(),
                    "Parameter 'text' exceeds maximum length of " + MAX_TEXT_LENGTH + " characters"
            );
        }

        return ToolResult.success(text);
    }
}
