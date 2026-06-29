package com.iflytek.chatbot.plugin.impl;

import com.iflytek.chatbot.plugin.ChatPlugin;
import com.iflytek.chatbot.plugin.PluginContext;
import com.iflytek.chatbot.plugin.PluginResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 计算器插件
 *
 * <p>当用户输入数学表达式时，直接计算并返回结果，不调用 LLM。</p>
 * <p>支持：加(+)、减(-)、乘(*)、除(/)、括号、小数。</p>
 */
@Component
public class CalculatorPlugin implements ChatPlugin {

    private static final Logger log = LoggerFactory.getLogger(CalculatorPlugin.class);

    /**
     * 匹配数学表达式：数字、运算符、括号、空格
     * 示例：3+5、(10-2)*3、100/4、3.14*2
     */
    private static final Pattern CALC_PATTERN = Pattern.compile(
            "^\\s*[\\d\\s\\+\\-\\*\\/\\(\\)\\.]+\\s*$"
    );

    @Override
    public String getName() {
        return "calculator";
    }

    @Override
    public int getOrder() {
        return 20;
    }

    @Override
    public PluginResult beforeRag(String query, String userId) {
        String trimmed = query.trim();

        // 去掉常见前缀
        trimmed = trimmed.replaceAll("^(计算|算一下|帮我算|calculate|calc)\\s*[:：]?\\s*", "");

        if (CALC_PATTERN.matcher(trimmed).matches() && trimmed.matches(".*\\d.*")) {
            try {
                double result = evaluate(trimmed);
                // 整数结果不显示小数点
                String formatted = result == (long) result
                        ? String.valueOf((long) result)
                        : String.valueOf(result);
                String answer = trimmed + " = " + formatted;
                return PluginResult.shortCircuit(answer, getName());
            } catch (Exception e) {
                log.warn("[CalculatorPlugin] 计算失败: {}", e.getMessage());
                // 计算失败则不拦截，交给 LLM 处理
            }
        }
        return PluginResult.continueNext();
    }

    @Override
    public String afterRag(String answer, String query, String userId, PluginContext context) {
        return answer;
    }

    /**
     * 简单的四则运算求值器（递归下降）
     */
    private double evaluate(String expr) {
        return new ExpressionParser(expr).parse();
    }

    private static class ExpressionParser {
        private final String input;
        private int pos;

        ExpressionParser(String input) {
            this.input = input.replaceAll("\\s+", "");
            this.pos = 0;
        }

        double parse() {
            double result = parseExpression();
            if (pos < input.length()) {
                throw new ParseException("Unexpected character: " + input.charAt(pos));
            }
            return result;
        }

        // expression = term (('+' | '-') term)*
        private double parseExpression() {
            double result = parseTerm();
            while (pos < input.length()) {
                char c = input.charAt(pos);
                if (c == '+') { pos++; result += parseTerm(); }
                else if (c == '-') { pos++; result -= parseTerm(); }
                else break;
            }
            return result;
        }

        // term = factor (('*' | '/') factor)*
        private double parseTerm() {
            double result = parseFactor();
            while (pos < input.length()) {
                char c = input.charAt(pos);
                if (c == '*') { pos++; result *= parseFactor(); }
                else if (c == '/') {
                    pos++;
                    double divisor = parseFactor();
                    if (divisor == 0) throw new ArithmeticException("Division by zero");
                    result /= divisor;
                }
                else break;
            }
            return result;
        }

        // factor = number | '(' expression ')'
        private double parseFactor() {
            if (pos < input.length() && input.charAt(pos) == '(') {
                pos++; // skip '('
                double result = parseExpression();
                if (pos < input.length() && input.charAt(pos) == ')') pos++;
                return result;
            }
            return parseNumber();
        }

        private double parseNumber() {
            int start = pos;
            if (pos < input.length() && (input.charAt(pos) == '-' || input.charAt(pos) == '+')) {
                pos++;
            }
            while (pos < input.length() && (Character.isDigit(input.charAt(pos)) || input.charAt(pos) == '.')) {
                pos++;
            }
            if (start == pos) throw new ParseException("Expected number at position " + pos);
            return Double.parseDouble(input.substring(start, pos));
        }
    }

    /** 计算器内部表达式解析异常，避免被外层 catch(RuntimeException) 误吞。 */
    private static class ParseException extends RuntimeException {
        ParseException(String message) {
            super(message);
        }
    }
}
