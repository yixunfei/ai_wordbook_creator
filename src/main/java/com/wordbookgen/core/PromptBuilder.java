package com.wordbookgen.core;

import com.wordbookgen.core.model.DictionaryFields;
import com.wordbookgen.core.model.JobConfig;

import java.util.List;

/**
 * 统一构建系统提示词与用户提示词。
 */
public final class PromptBuilder {

    private PromptBuilder() {
    }

    public static String buildSystemPrompt(JobConfig config) {
        String sourceLanguage = safe(config.sourceLanguage(), "English");
        String targetLanguage = safe(config.targetLanguage(), "Chinese");

        String base;
        if (config.useSystemPromptOverride() && config.systemPromptTemplate() != null && !config.systemPromptTemplate().isBlank()) {
            base = replacePlaceholders(config.systemPromptTemplate(), sourceLanguage, targetLanguage);
        } else {
            base = buildDefaultSystemPrompt(sourceLanguage, targetLanguage);
        }

        if (config.customPrompt() != null && !config.customPrompt().isBlank()) {
            base = base + " Additional strict instruction: "
                    + replacePlaceholders(config.customPrompt(), sourceLanguage, targetLanguage);
        }
        return base;
    }

    public static String buildUserPrompt(JobConfig config, List<String> words) {
        String sourceLanguage = safe(config.sourceLanguage(), "English");
        String targetLanguage = safe(config.targetLanguage(), "Chinese");

        StringBuilder sb = new StringBuilder();
        sb.append("Process this word list: ")
                .append(String.join(",", words))
                .append("\n");
        sb.append("Return strict JSON only. Preferred top-level shape is {\"items\":[...]}.\n");
        sb.append("If you return an array directly, it must still be valid JSON.\n");
        sb.append("The item count must be exactly ")
                .append(words.size())
                .append(". Never omit any input word.\n");
        sb.append("Language scope: source=")
                .append(sourceLanguage)
                .append(", target explanation=")
                .append(targetLanguage)
                .append(".\n");
        sb.append("Strict field typing:\n");
        sb.append("- ").append(DictionaryFields.WORD).append(": string\n");
        sb.append("- ").append(DictionaryFields.PHONETIC).append(": string\n");
        sb.append("- ").append(DictionaryFields.PART_OF_SPEECH).append(": string\n");
        sb.append("- ").append(DictionaryFields.CORE_MEANING).append(": string\n");
        sb.append("- ").append(DictionaryFields.WORD_FORMS).append(": string\n");
        sb.append("- ").append(DictionaryFields.COMMON_PHRASES).append(": string[]\n");
        sb.append("- ").append(DictionaryFields.EXAMPLE_SENTENCES).append(": object with keys ")
                .append(DictionaryFields.SUBFIELD_EN).append(", ").append(DictionaryFields.SUBFIELD_ZH).append("\n");
        sb.append("- ").append(DictionaryFields.AFFIX_ANALYSIS).append(": string\n");
        sb.append("- ").append(DictionaryFields.MEMORY_STORY).append(": object with keys ")
                .append(DictionaryFields.SUBFIELD_EN).append(", ").append(DictionaryFields.SUBFIELD_ZH).append("\n");
        sb.append("Length limits to avoid truncation:\n");
        sb.append("- ").append(DictionaryFields.CORE_MEANING).append(": <= 45 Chinese chars\n");
        sb.append("- ").append(DictionaryFields.WORD_FORMS).append(": <= 80 chars\n");
        sb.append("- ").append(DictionaryFields.COMMON_PHRASES).append(": 2-4 items, each <= 6 words\n");
        sb.append("- ").append(DictionaryFields.EXAMPLE_SENTENCES).append(".").append(DictionaryFields.SUBFIELD_EN).append(": <= 24 words\n");
        sb.append("- ").append(DictionaryFields.EXAMPLE_SENTENCES).append(".").append(DictionaryFields.SUBFIELD_ZH).append(": <= 45 Chinese chars\n");
        sb.append("- ").append(DictionaryFields.AFFIX_ANALYSIS).append(": <= 90 chars\n");
        sb.append("- ").append(DictionaryFields.MEMORY_STORY).append(".").append(DictionaryFields.SUBFIELD_EN).append(": <= 28 words\n");
        sb.append("- ").append(DictionaryFields.MEMORY_STORY).append(".").append(DictionaryFields.SUBFIELD_ZH).append(": <= 50 Chinese chars\n");
        sb.append("Strict example (single item):\n")
                .append(buildStrictExampleJson())
                .append("\n");
        sb.append("Each item field '")
                .append(DictionaryFields.WORD)
                .append("' must equal one input word exactly (case-insensitive).\n");
        sb.append("No markdown, no code fence, no comments, no trailing commas, no ellipsis text like '...'.\n");
        sb.append("If uncertain about details, use conservative values, but keep all required fields present.\n");
        sb.append("Quality bar: practical dictionary style, common collocations, natural examples, avoid fabricated facts.\n");
        return sb.toString();
    }

    private static String buildDefaultSystemPrompt(String sourceLanguage, String targetLanguage) {
        return "You are a professional dictionary editor. "
                + "Output strict JSON only. No markdown/code fence/explanations. "
                + "Field names must exactly match: " + String.join(", ", DictionaryFields.REQUIRED) + ". "
                + "Each input word appears exactly once. "
                + "Do not truncate output. "
                + "Source language: " + sourceLanguage + ". "
                + "Target explanation language: " + targetLanguage + ". "
                + "For sentences and memory story, use object keys '"
                + DictionaryFields.SUBFIELD_EN + "' and '" + DictionaryFields.SUBFIELD_ZH + "'.";
    }

    private static String buildStrictExampleJson() {
        StringBuilder sb = new StringBuilder();
        sb.append("{\"items\":[{")
                .append("\"").append(DictionaryFields.WORD).append("\":\"example\",")
                .append("\"").append(DictionaryFields.PHONETIC).append("\":\"/ɪɡˈzɑːmpəl/\",")
                .append("\"").append(DictionaryFields.PART_OF_SPEECH).append("\":\"名词\",")
                .append("\"").append(DictionaryFields.CORE_MEANING).append("\":\"示例；例子\",")
                .append("\"").append(DictionaryFields.WORD_FORMS).append("\":\"复数：examples\",")
                .append("\"").append(DictionaryFields.COMMON_PHRASES).append("\":[\"for example\",\"set an example\"],")
                .append("\"").append(DictionaryFields.EXAMPLE_SENTENCES).append("\":{")
                .append("\"").append(DictionaryFields.SUBFIELD_EN).append("\":\"This is an example sentence.\",")
                .append("\"").append(DictionaryFields.SUBFIELD_ZH).append("\":\"这是一个示例句子。\"},")
                .append("\"").append(DictionaryFields.AFFIX_ANALYSIS).append("\":\"ex- (out) + -ample (take)\",")
                .append("\"").append(DictionaryFields.MEMORY_STORY).append("\":{")
                .append("\"").append(DictionaryFields.SUBFIELD_EN).append("\":\"Imagine showing one clear sample to explain an idea.\",")
                .append("\"").append(DictionaryFields.SUBFIELD_ZH).append("\":\"想象你拿出一个清晰样本来解释观点。\"}")
                .append("}]}");
        return sb.toString();
    }

    private static String replacePlaceholders(String template, String sourceLanguage, String targetLanguage) {
        if (template == null) {
            return "";
        }
        return template
                .replace("{{sourceLanguage}}", sourceLanguage)
                .replace("{{targetLanguage}}", targetLanguage)
                .replace("{{requiredFields}}", String.join(", ", DictionaryFields.REQUIRED));
    }

    private static String safe(String value, String fallback) {
        if (value == null || value.isBlank()) {
            return fallback;
        }
        return value.trim();
    }
}
