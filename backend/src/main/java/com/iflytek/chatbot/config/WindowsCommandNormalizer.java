package com.iflytek.chatbot.config;

import java.util.regex.Pattern;

/**
 * 把 LLM 输出的 *nix 风格 shell 命令规范化为 Windows 可直接执行的形式。
 * 规则集中在此处，方便单测覆盖：
 * <ol>
 *   <li>python3 → python</li>
 *   <li>剥掉 bash / bash -c / cmd / cmd /c 等包装前缀</li>
 *   <li>.sh → .py（旧脚本残留兼容）</li>
 *   <li>裸 .py 路径自动加 python 前缀</li>
 * </ol>
 */
final class WindowsCommandNormalizer {

    private static final Pattern WRAPPER_PREFIX =
            Pattern.compile("^(bash(\\s+-c)?\\s+|cmd(\\s+/c)?\\s+)");
    private static final Pattern PY_PATH_PREFIX =
            Pattern.compile("^(/|[A-Za-z]:/).*\\.py\\s.*");

    private WindowsCommandNormalizer() {}

    static String normalize(String command) {
        if (command == null || command.isBlank()) {
            return command;
        }
        String c = command.replace("python3 ", "python ");
        c = WRAPPER_PREFIX.matcher(c).replaceFirst("");
        c = c.replace(".sh ", ".py ");
        if (PY_PATH_PREFIX.matcher(c).matches()) {
            c = "python " + c;
        }
        return c;
    }
}
