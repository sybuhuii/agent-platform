package com.ksyun.agent.infrastructure.tool.builtin;

import com.ksyun.agent.core.exception.AgentErrorCode;
import com.ksyun.agent.core.tool.AgentTool;
import com.ksyun.agent.core.tool.ToolDefinition;
import com.ksyun.agent.core.tool.ToolInvocation;
import com.ksyun.agent.core.tool.ToolResult;
import com.ksyun.agent.core.tool.ToolRiskLevel;

import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Map;

/**
 * 查询指定 IANA 时区的当前时间。
 */
public class CurrentTimeTool implements AgentTool {

    private static final String NAME = "current_time";
    private static final String INPUT_SCHEMA = """
            {
              "type": "object",
              "properties": {
                "timezone": {
                  "type": "string",
                  "description": "IANA timezone ID, defaults to UTC if not provided"
                }
              },
              "additionalProperties": false
            }
            """;
    private static final DateTimeFormatter FORMATTER = DateTimeFormatter.ISO_OFFSET_DATE_TIME;

    private static final ToolDefinition DEFINITION = new ToolDefinition(
            NAME,
            "Query the current time in a specified IANA timezone",
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
        String timezoneStr = ToolArgs.getString(args, "timezone", "UTC");

        if (timezoneStr == null) {
            return ToolResult.failure(
                    AgentErrorCode.INVALID_ARGUMENT.name(),
                    "Parameter 'timezone' must be a string"
            );
        }

        if (timezoneStr.isBlank()) {
            timezoneStr = "UTC";
        }

        ZoneId zoneId;
        try {
            zoneId = ZoneId.of(timezoneStr);
        } catch (Exception e) {
            return ToolResult.failure(
                    AgentErrorCode.INVALID_ARGUMENT.name(),
                    "Invalid timezone: " + timezoneStr
            );
        }

        ZonedDateTime now = ZonedDateTime.now(zoneId);
        String formatted = now.format(FORMATTER);

        return ToolResult.success(
                formatted,
                Map.of("timezone", zoneId.toString())
        );
    }
}
