package com.ksyun.agent.infrastructure.tool.builtin;

import com.ksyun.agent.core.exception.AgentErrorCode;
import com.ksyun.agent.core.tool.AgentTool;
import com.ksyun.agent.core.tool.ToolDefinition;
import com.ksyun.agent.core.tool.ToolInvocation;
import com.ksyun.agent.core.tool.ToolResult;
import com.ksyun.agent.core.tool.ToolRiskLevel;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 在用户提供的文本中搜索关键词并返回匹配行。
 */
public class TextSearchTool implements AgentTool {

    private static final String NAME = "text_search";
    private static final String INPUT_SCHEMA = """
            {
              "type": "object",
              "properties": {
                "text": {
                  "type": "string",
                  "description": "The text to search within"
                },
                "keyword": {
                  "type": "string",
                  "description": "The keyword to search for"
                },
                "caseSensitive": {
                  "type": "boolean",
                  "description": "Whether the search is case sensitive, defaults to false",
                  "default": false
                },
                "maxMatches": {
                  "type": "integer",
                  "description": "Maximum number of matches to return, range 1-100, defaults to 20",
                  "default": 20
                }
              },
              "required": ["text", "keyword"],
              "additionalProperties": false
            }
            """;
    private static final int MAX_TEXT_LENGTH = 100_000;
    private static final int MAX_LINE_DISPLAY_LENGTH = 500;
    private static final int MAX_RESULT_CONTENT_LENGTH = 50_000;
    private static final int DEFAULT_MAX_MATCHES = 20;

    private static final ToolDefinition DEFINITION = new ToolDefinition(
            NAME,
            "Search for a keyword in the provided text and return matching lines",
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
        if (text == null) {
            return ToolResult.failure(
                    AgentErrorCode.INVALID_ARGUMENT.name(),
                    "Parameter 'text' is required"
            );
        }
        if (text.length() > MAX_TEXT_LENGTH) {
            return ToolResult.failure(
                    AgentErrorCode.INVALID_ARGUMENT.name(),
                    "Parameter 'text' exceeds maximum length of " + MAX_TEXT_LENGTH + " characters"
            );
        }

        String keyword = ToolArgs.getString(args, "keyword");
        if (keyword == null || keyword.isEmpty()) {
            return ToolResult.failure(
                    AgentErrorCode.INVALID_ARGUMENT.name(),
                    "Parameter 'keyword' is required and must not be empty"
            );
        }

        boolean caseSensitive = ToolArgs.getBoolean(args, "caseSensitive", false);

        Integer maxMatchesVal = ToolArgs.getInteger(args, "maxMatches");
        int maxMatches = maxMatchesVal != null ? maxMatchesVal : DEFAULT_MAX_MATCHES;
        if (maxMatches < 1 || maxMatches > 100) {
            return ToolResult.failure(
                    AgentErrorCode.INVALID_ARGUMENT.name(),
                    "Parameter 'maxMatches' must be between 1 and 100"
            );
        }

        String searchTarget = text;
        String searchKeyword = keyword;
        if (!caseSensitive) {
            searchTarget = text.toLowerCase();
            searchKeyword = keyword.toLowerCase();
        }

        String[] lines = text.split("\\R", -1);
        StringBuilder result = new StringBuilder();
        int matchCount = 0;
        boolean truncated = false;

        for (int i = 0; i < lines.length; i++) {
            String originalLine = lines[i];
            String compareLine = caseSensitive ? originalLine : originalLine.toLowerCase();

            if (compareLine.contains(searchKeyword)) {
                matchCount++;
                if (matchCount > maxMatches) {
                    truncated = true;
                    break;
                }

                String displayLine = originalLine;
                if (displayLine.length() > MAX_LINE_DISPLAY_LENGTH) {
                    displayLine = displayLine.substring(0, MAX_LINE_DISPLAY_LENGTH) + "...[truncated]";
                }

                if (!result.isEmpty()) {
                    result.append('\n');
                }
                result.append(i + 1).append(':').append(displayLine);

                if (result.length() > MAX_RESULT_CONTENT_LENGTH) {
                    truncated = true;
                    break;
                }
            }
        }

        String content;
        if (matchCount == 0) {
            content = "No matches found for keyword";
        } else {
            content = result.toString();
        }

        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("matchCount", matchCount);
        if (truncated) {
            metadata.put("truncated", true);
        }

        return ToolResult.success(content, Map.copyOf(metadata));
    }
}
