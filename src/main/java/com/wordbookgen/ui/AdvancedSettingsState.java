package com.wordbookgen.ui;

/**
 * Snapshot for advanced UI settings while editing the dialog.
 */
class AdvancedSettingsState {
    boolean useSystemPromptOverride;
    String systemPromptTemplate;
    boolean debugMode;
    UiScriptHookItem preRequestHook;
    UiScriptHookItem postResponseHook;
    UiScriptHookItem postParsedHook;

    static AdvancedSettingsState defaultState() {
        AdvancedSettingsState state = new AdvancedSettingsState();
        state.useSystemPromptOverride = false;
        state.systemPromptTemplate = "";
        state.debugMode = false;
        state.preRequestHook = new UiScriptHookItem();
        state.postResponseHook = new UiScriptHookItem();
        state.postParsedHook = new UiScriptHookItem();
        return state;
    }

    AdvancedSettingsState copy() {
        AdvancedSettingsState ret = new AdvancedSettingsState();
        ret.useSystemPromptOverride = this.useSystemPromptOverride;
        ret.systemPromptTemplate = this.systemPromptTemplate == null ? "" : this.systemPromptTemplate;
        ret.debugMode = this.debugMode;
        ret.preRequestHook = copyHook(this.preRequestHook);
        ret.postResponseHook = copyHook(this.postResponseHook);
        ret.postParsedHook = copyHook(this.postParsedHook);
        return ret;
    }

    private UiScriptHookItem copyHook(UiScriptHookItem item) {
        UiScriptHookItem ret = new UiScriptHookItem();
        if (item == null) {
            return ret;
        }
        ret.enabled = item.enabled;
        ret.language = item.language;
        ret.scriptPath = item.scriptPath;
        ret.timeoutSec = item.timeoutSec;
        return ret;
    }
}
