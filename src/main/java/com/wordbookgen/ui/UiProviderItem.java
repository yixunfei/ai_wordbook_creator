package com.wordbookgen.ui;

/**
 * UI 中单个 provider 的持久化配置项。
 */
public class UiProviderItem {

    public String name = "";
    public String url = "";
    public String apiKey = "";
    public String model = "";

    public int concurrency = 8;
    public int quota = 1000;
    public int windowMinutes = 300;
}
