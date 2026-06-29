package com.iflytek.chatbot.config;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class WindowsCommandNormalizerTest {

    @Test
    void python3_replaced() {
        assertEquals("python script.py", WindowsCommandNormalizer.normalize("python3 script.py"));
    }

    @Test
    void bash_wrapper_stripped() {
        assertEquals("python s.py", WindowsCommandNormalizer.normalize("bash python s.py"));
        assertEquals("python s.py", WindowsCommandNormalizer.normalize("bash -c python s.py"));
    }

    @Test
    void cmd_wrapper_stripped() {
        assertEquals("python s.py", WindowsCommandNormalizer.normalize("cmd /c python s.py"));
    }

    @Test
    void sh_extension_swapped() {
        assertEquals("python run.py arg", WindowsCommandNormalizer.normalize("python run.sh arg"));
    }

    @Test
    void bare_unix_py_path_gets_python_prefix() {
        assertEquals("python /tmp/skills/x.py arg",
                WindowsCommandNormalizer.normalize("/tmp/skills/x.py arg"));
    }

    @Test
    void bare_windows_py_path_gets_python_prefix() {
        assertEquals("python C:/tmp/x.py arg",
                WindowsCommandNormalizer.normalize("C:/tmp/x.py arg"));
    }

    @Test
    void null_and_blank_passthrough() {
        assertNull(WindowsCommandNormalizer.normalize(null));
        assertEquals("   ", WindowsCommandNormalizer.normalize("   "));
    }
}
