package com.ksyun.agent.infrastructure.tool.builtin;

import com.ksyun.agent.core.exception.AgentErrorCode;
import com.ksyun.agent.core.tool.AgentTool;
import com.ksyun.agent.core.tool.ToolDefinition;
import com.ksyun.agent.core.tool.ToolInvocation;
import com.ksyun.agent.core.tool.ToolResult;
import com.ksyun.agent.core.tool.ToolRiskLevel;

import java.math.BigDecimal;
import java.math.MathContext;
import java.math.RoundingMode;
import java.util.Map;

/**
 * 安全的基础算术表达式计算工具。
 * <p>
 * 使用递归下降解析器，禁止 eval/ScriptEngine/反射等任意代码执行能力。
 */
public class CalculatorTool implements AgentTool {

    private static final String NAME = "calculator";
    private static final String INPUT_SCHEMA = """
            {
              "type": "object",
              "properties": {
                "expression": {
                  "type": "string",
                  "description": "Arithmetic expression, supports + - * / and parentheses"
                }
              },
              "required": ["expression"],
              "additionalProperties": false
            }
            """;
    private static final int MAX_EXPRESSION_LENGTH = 1000;
    private static final int DIVISION_SCALE = 12;
    private static final MathContext MATH_CONTEXT = new MathContext(34, RoundingMode.HALF_UP);

    private static final ToolDefinition DEFINITION = new ToolDefinition(
            NAME,
            "Perform safe basic arithmetic expression calculations",
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
        String expression = ToolArgs.getString(args, "expression");

        if (!args.containsKey("expression")) {
            return ToolResult.failure(
                    AgentErrorCode.INVALID_ARGUMENT.name(),
                    "Parameter 'expression' is required"
            );
        }
        if (expression == null) {
            return ToolResult.failure(
                    AgentErrorCode.INVALID_ARGUMENT.name(),
                    "Parameter 'expression' must be a string"
            );
        }
        if (expression.isBlank()) {
            return ToolResult.failure(
                    AgentErrorCode.INVALID_ARGUMENT.name(),
                    "Parameter 'expression' must not be blank"
            );
        }
        if (expression.length() > MAX_EXPRESSION_LENGTH) {
            return ToolResult.failure(
                    AgentErrorCode.INVALID_ARGUMENT.name(),
                    "Expression exceeds maximum length of " + MAX_EXPRESSION_LENGTH + " characters"
            );
        }

        try {
            BigDecimal result = new ExpressionParser(expression).parse();
            return ToolResult.success(stripTrailingZeros(result));
        } catch (ArithmeticException e) {
            return ToolResult.failure(
                    AgentErrorCode.INVALID_ARGUMENT.name(),
                    "Arithmetic error: " + e.getMessage()
            );
        } catch (IllegalArgumentException e) {
            return ToolResult.failure(
                    AgentErrorCode.INVALID_ARGUMENT.name(),
                    e.getMessage()
            );
        }
    }

    private static String stripTrailingZeros(BigDecimal value) {
        BigDecimal stripped = value.stripTrailingZeros();
        if (stripped.scale() < 0) {
            stripped = stripped.setScale(0, RoundingMode.UNNECESSARY);
        }
        return stripped.toPlainString();
    }

    // ---- 递归下降解析器 ----

    private static class ExpressionParser {
        private final String input;
        private int pos;

        ExpressionParser(String input) {
            this.input = input;
            this.pos = 0;
        }

        BigDecimal parse() {
            BigDecimal result = parseExpression();
            skipWhitespace();
            if (pos < input.length()) {
                throw new IllegalArgumentException(
                        "Unexpected character at position " + pos + ": '" + input.charAt(pos) + "'"
                );
            }
            return result;
        }

        // expression = term (('+' | '-') term)*
        private BigDecimal parseExpression() {
            BigDecimal result = parseTerm();
            while (pos < input.length()) {
                skipWhitespace();
                char op = peek();
                if (op == '+') {
                    advance();
                    result = result.add(parseTerm());
                } else if (op == '-') {
                    advance();
                    result = result.subtract(parseTerm());
                } else {
                    break;
                }
            }
            return result;
        }

        // term = factor (('*' | '/') factor)*
        private BigDecimal parseTerm() {
            BigDecimal result = parseFactor();
            while (pos < input.length()) {
                skipWhitespace();
                char op = peek();
                if (op == '*') {
                    advance();
                    result = result.multiply(parseFactor(), MATH_CONTEXT);
                } else if (op == '/') {
                    advance();
                    BigDecimal divisor = parseFactor();
                    if (divisor.compareTo(BigDecimal.ZERO) == 0) {
                        throw new ArithmeticException("Division by zero");
                    }
                    result = result.divide(divisor, DIVISION_SCALE, RoundingMode.HALF_UP);
                } else {
                    break;
                }
            }
            return result;
        }

        // factor = ['+' | '-'] atom
        private BigDecimal parseFactor() {
            skipWhitespace();
            char op = peek();
            if (op == '+') {
                advance();
                return parseAtom();
            } else if (op == '-') {
                advance();
                return parseAtom().negate();
            }
            return parseAtom();
        }

        // atom = number | '(' expression ')'
        private BigDecimal parseAtom() {
            skipWhitespace();
            if (pos >= input.length()) {
                throw new IllegalArgumentException("Unexpected end of expression");
            }

            if (peek() == '(') {
                advance(); // skip '('
                BigDecimal result = parseExpression();
                skipWhitespace();
                if (pos >= input.length() || peek() != ')') {
                    throw new IllegalArgumentException("Mismatched parentheses: missing ')'");
                }
                advance(); // skip ')'
                return result;
            }

            return parseNumber();
        }

        private BigDecimal parseNumber() {
            skipWhitespace();
            int start = pos;
            boolean hasDot = false;

            if (pos < input.length() && input.charAt(pos) == '.') {
                hasDot = true;
                pos++;
            }

            while (pos < input.length()) {
                char c = input.charAt(pos);
                if (c >= '0' && c <= '9') {
                    pos++;
                } else if (c == '.' && !hasDot) {
                    hasDot = true;
                    pos++;
                } else {
                    break;
                }
            }

            if (pos == start) {
                throw new IllegalArgumentException(
                        "Expected number at position " + start
                );
            }

            String numStr = input.substring(start, pos);
            try {
                return new BigDecimal(numStr);
            } catch (NumberFormatException e) {
                throw new IllegalArgumentException("Invalid number: " + numStr);
            }
        }

        private void skipWhitespace() {
            while (pos < input.length() && Character.isWhitespace(input.charAt(pos))) {
                pos++;
            }
        }

        private char peek() {
            if (pos >= input.length()) {
                return '\0';
            }
            return input.charAt(pos);
        }

        private void advance() {
            pos++;
        }
    }
}
