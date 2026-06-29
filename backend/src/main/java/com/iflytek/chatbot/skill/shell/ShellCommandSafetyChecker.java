package com.iflytek.chatbot.skill.shell;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.regex.Pattern;

/**
 * 静态命令安全检查：硬性 deny 规则，永远先于 LLM 审查员执行。
 *
 * <p>原则：只挡明确危险，宁可漏报不可误放；LLM 审查员是叠加层，不能放大此处的放行集。</p>
 */
@Component
public class ShellCommandSafetyChecker {

    private static final Logger log = LoggerFactory.getLogger(ShellCommandSafetyChecker.class);

    /** 命令最大长度，超过即可疑 */
    private static final int MAX_COMMAND_LEN = 2048;

    /**
     * shell 元字符 / 拼接符。一旦出现立即拒绝——本项目所有合法 skill 命令都不应包含这些。
     * 反引号、$(...)、;、&&、||、|、>、<、& 都能 chain 出额外进程；\0 是 path 注入。
     */
    private static final Pattern SHELL_METACHARS = Pattern.compile("[`;&|<>\\\\\\u0000]|\\$\\(|>>");

    /** 命令首词必须出现在此白名单中（小写匹配），按需扩展。 */
    private static final List<String> ALLOWED_EXECUTABLES = List.of(
            "python", "python3"
    );

    /**
     * 危险关键词：即使是 python 命令，也不允许出现这些 token。
     * 注意：用 \b 单词边界，避免误伤 "supdo" 这种正常子串。
     */
    private static final List<Pattern> DENY_TOKENS = List.of(
            Pattern.compile("(?i)\\bsudo\\b"),
            Pattern.compile("(?i)\\bsu\\b"),
            Pattern.compile("(?i)\\brm\\s+-[rf]"),
            Pattern.compile("(?i)\\bdel\\s+/"),
            Pattern.compile("(?i)\\bformat\\s+[a-z]:"),
            Pattern.compile("(?i)\\bmkfs\\b"),
            Pattern.compile("(?i)\\bdd\\s+if="),
            Pattern.compile("(?i)\\bcurl\\b"),
            Pattern.compile("(?i)\\bwget\\b"),
            Pattern.compile("(?i)\\bnc\\b"),
            Pattern.compile("(?i)\\bssh\\b"),
            Pattern.compile("(?i)\\bscp\\b"),
            Pattern.compile("(?i)\\bchmod\\b"),
            Pattern.compile("(?i)\\bchown\\b"),
            Pattern.compile("(?i)\\bkill\\b"),
            Pattern.compile("(?i)\\btaskkill\\b"),
            Pattern.compile("(?i)\\breg\\s+(add|delete)"),
            Pattern.compile("(?i)\\bnet\\s+(user|localgroup)"),
            // 敏感文件
            Pattern.compile("/etc/passwd"),
            Pattern.compile("/etc/shadow"),
            Pattern.compile("(?i)id_rsa"),
            Pattern.compile("\\.env\\b"),
            // python 内联 exec / system
            Pattern.compile("(?i)-c\\s+['\"].*\\bos\\.system\\b"),
            Pattern.compile("(?i)-c\\s+['\"].*\\bsubprocess\\b"),
            Pattern.compile("(?i)-c\\s+['\"].*\\b__import__\\b"),
            Pattern.compile("(?i)\\beval\\s*\\("),
            Pattern.compile("(?i)\\bexec\\s*\\("),
            // 编码 payload
            Pattern.compile("(?i)\\bbase64\\s+-d\\b"),
            Pattern.compile("(?i)FromBase64String")
    );

    /** 检查结果 */
    public record Decision(boolean allow, String reason) {
        public static Decision pass() { return new Decision(true, null); }
        public static Decision deny(String reason) { return new Decision(false, reason); }
    }

    public Decision check(String command) {
        if (command == null || command.isBlank()) {
            return Decision.deny("空命令");
        }
        if (command.length() > MAX_COMMAND_LEN) {
            return Decision.deny("命令过长 (>" + MAX_COMMAND_LEN + ")");
        }

        // 1. shell 元字符
        if (SHELL_METACHARS.matcher(command).find()) {
            return Decision.deny("包含 shell 元字符（; & | ` $() 等）");
        }

        // 2. 首词白名单
        String[] tokens = command.trim().split("\\s+");
        String executable = tokens[0];
        // 去掉 Windows 上 ProcessBuilder 可能带的 .exe 后缀
        String exeLower = executable.toLowerCase().replaceFirst("\\.exe$", "");
        // 如果是绝对路径，取最后一段（python 解释器有时带完整路径）
        int slash = Math.max(exeLower.lastIndexOf('/'), exeLower.lastIndexOf('\\'));
        if (slash >= 0) {
            exeLower = exeLower.substring(slash + 1);
        }
        if (!ALLOWED_EXECUTABLES.contains(exeLower)) {
            return Decision.deny("首词不在白名单: " + executable);
        }

        // 3. deny tokens
        for (Pattern p : DENY_TOKENS) {
            if (p.matcher(command).find()) {
                return Decision.deny("命中危险关键词: " + p.pattern());
            }
        }

        log.debug("[ShellSafety] 静态检查通过 | command={}", command);
        return Decision.pass();
    }
}
