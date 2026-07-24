package com.retro.bamfos.updater.engine.fs;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PathGuardTest {

    private static String reject(String path) {
        return assertThrows(PathGuard.BadPath.class, () -> PathGuard.validate(path)).getMessage();
    }

    @Test
    void ordinaryPathsPass() {
        assertDoesNotThrow(() -> PathGuard.validate("config/bcc-common.toml"));
        assertDoesNotThrow(() -> PathGuard.validate("mods/Mod Menu (NeoForge Edition) 1.21.1-1.0.2.jar"));
        assertDoesNotThrow(() -> PathGuard.validate("options.txt"));
        // dots INSIDE a name are fine, only the '..' SEGMENT is the attack, foo..jar must pass
        assertDoesNotThrow(() -> PathGuard.validate("mods/foo..jar"));
        // console is not CON: reserved names match the bare basename only
        assertDoesNotThrow(() -> PathGuard.validate("config/console.toml"));
    }

    @Test
    void everyWindowsRuleFires() {
        assertTrue(reject("mods/../x.jar").contains("'..' segment"));
        assertTrue(reject("..\\x.jar").contains("'..' segment")); // backslash separator counts too
        assertTrue(reject("/etc/passwd").contains("absolute"));
        assertTrue(reject("C:/windows/system32").contains("drive letter"));
        assertTrue(reject("config/foo.toml:ads").contains("alternate data stream"));
        assertTrue(reject("CON").contains("reserved device name"));
        assertTrue(reject("config/com1.toml").contains("reserved device name"));
        assertTrue(reject("config/foo./bar").contains("dot or space"));
        assertTrue(reject("config/foo /bar").contains("dot or space"));
        assertTrue(reject("config//bar").contains("empty segment"));
        assertTrue(reject("").contains("empty"));
    }
}
