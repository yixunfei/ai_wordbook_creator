package com.wordbookgen.core.model;

/**
 * 自定义脚本语言类型。
 */
public enum ScriptLanguage {
    PYTHON,
    JAVASCRIPT,
    LUA,
    JAVA;

    public static ScriptLanguage fromText(String value) {
        if (value == null || value.isBlank()) {
            return JAVASCRIPT;
        }
        try {
            return ScriptLanguage.valueOf(value.trim().toUpperCase());
        } catch (IllegalArgumentException ex) {
            return JAVASCRIPT;
        }
    }
}
