package com.wordbookgen.core.model;

/**
 * 单个阶段脚本钩子配置。
 */
public record ScriptHookConfig(
        boolean enabled,
        ScriptLanguage language,
        String scriptPath,
        int timeoutSec
) {

    public static ScriptHookConfig disabled() {
        return new ScriptHookConfig(false, ScriptLanguage.JAVASCRIPT, "", 30);
    }

    public ScriptHookConfig {
        if (language == null) {
            language = ScriptLanguage.JAVASCRIPT;
        }
        if (scriptPath == null) {
            scriptPath = "";
        }
        if (timeoutSec <= 0) {
            timeoutSec = 30;
        }
    }
}
