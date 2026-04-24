package com.wordbookgen.core.model;

import java.util.List;

/**
 * 词典输出字段常量。
 * JSON 字段名固定为中文，避免上游/下游处理不一致。
 */
public final class DictionaryFields {

    private DictionaryFields() {
    }

    public static final String WORD = "\u5355\u8BCD";
    public static final String PHONETIC = "\u97F3\u6807";
    public static final String PART_OF_SPEECH = "\u8BCD\u6027";
    public static final String CORE_MEANING = "\u6838\u5FC3\u542B\u4E49";
    public static final String WORD_FORMS = "\u8BCD\u5F62\u53D8\u5F62";
    public static final String COMMON_PHRASES = "\u5E38\u7528\u8BCD\u7EC4";
    public static final String EXAMPLE_SENTENCES = "\u7ECF\u5178\u4F8B\u53E5";
    public static final String AFFIX_ANALYSIS = "\u8BCD\u7F00\u89E3\u6790";
    public static final String MEMORY_STORY = "\u8F85\u52A9\u8BB0\u5FC6\u5C0F\u6545\u4E8B";

    public static final String SUBFIELD_EN = "\u82F1\u6587";
    public static final String SUBFIELD_ZH = "\u4E2D\u6587";

    public static final List<String> REQUIRED = List.of(
            WORD,
            PHONETIC,
            PART_OF_SPEECH,
            CORE_MEANING,
            WORD_FORMS,
            COMMON_PHRASES,
            EXAMPLE_SENTENCES,
            AFFIX_ANALYSIS,
            MEMORY_STORY
    );
}
