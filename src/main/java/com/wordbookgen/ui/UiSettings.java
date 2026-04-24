package com.wordbookgen.ui;

import java.util.ArrayList;
import java.util.List;

/**
 * UI 配置快照，用于保存/加载。
 */
public class UiSettings {

    /**
     * 兼容旧版配置（按行拼接 provider）。
     * 新版优先读取 providers 集合。
     */
    public String providersText = "";
    public String configDirectory = "";

    /**
     * 新版 provider 列表配置。
     */
    public List<UiProviderItem> providers = new ArrayList<>();

    public String promptText = "";
    public String inputPath = "";
    public String outputPath = "";
    public String outputFormat = "JSON";
    public String sourceLanguage = "English";
    public String targetLanguage = "Chinese";
    public String encoding = "UTF-8";

    public int batchSize = 12;
    public int parallelism = 4;
    public int maxRetries = 5;
    public int timeoutSec = 180;

    public boolean resumeFromCheckpoint = true;
    public boolean clearCheckpointOnSuccess = true;
    public boolean allowNonStandardResponses = true;

    public boolean useSystemPromptOverride = false;
    public String systemPromptTemplate = "";
    public boolean debugMode = false;

    public UiScriptHookItem preRequestHook = new UiScriptHookItem();
    public UiScriptHookItem postResponseHook = new UiScriptHookItem();
    public UiScriptHookItem postParsedHook = new UiScriptHookItem();
}
