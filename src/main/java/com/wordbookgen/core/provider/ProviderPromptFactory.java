package com.wordbookgen.core.provider;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.wordbookgen.core.model.BatchTask;
import com.wordbookgen.core.model.DictionaryFields;
import com.wordbookgen.core.model.JobConfig;
import com.wordbookgen.core.provider.ProviderExceptions.RetryableProviderException;

/**
 * Builds provider prompts used for JSON repair and truncated-output continuation.
 */
class ProviderPromptFactory {

    private final ObjectMapper mapper;

    ProviderPromptFactory(ObjectMapper mapper) {
        this.mapper = mapper;
    }

    String buildRepairSystemPrompt(JobConfig jobConfig) {
        String sourceLanguage = safe(jobConfig.sourceLanguage(), "English");
        String targetLanguage = safe(jobConfig.targetLanguage(), "Chinese");
        return "You repair malformed dictionary JSON. "
                + "Return strict JSON only, no markdown, no code fence, no comments, no explanations. "
                + "The top-level shape must be {\"items\":[...]}. "
                + "Use the exact required field names and produce exactly one item per input word. "
                + "If a previous value is unusable or missing, regenerate a concise dictionary value. "
                + "Source language: " + sourceLanguage + ". "
                + "Target explanation language: " + targetLanguage + ".";
    }

    String buildContinuationSystemPrompt() {
        return "You continue a truncated JSON response. "
                + "Return only the missing suffix that should be appended to the previous output. "
                + "Do not repeat prior content. Do not use markdown, code fences, comments, or explanations.";
    }

    String buildContinuationUserPrompt(
            BatchTask task,
            String originalSystemPrompt,
            String originalUserPrompt,
            String partialText
    ) {
        StringBuilder sb = new StringBuilder();
        sb.append("The previous response was cut off because it reached the token limit.\n");
        sb.append("Continue the same JSON output from the next character only.\n");
        sb.append("Input words JSON: ").append(toJsonString(task.words())).append("\n");
        sb.append("Original system prompt as JSON string:\n")
                .append(toJsonString(compactForPrompt(originalSystemPrompt, 4000)))
                .append("\n");
        sb.append("Original user prompt as JSON string:\n")
                .append(toJsonString(compactForPrompt(originalUserPrompt, 6000)))
                .append("\n");
        sb.append("Already received output as JSON string:\n")
                .append(toJsonString(compactForPrompt(partialText, 12000)))
                .append("\n");
        sb.append("Return only the remaining suffix.");
        return sb.toString();
    }

    String buildRepairUserPrompt(
            JobConfig jobConfig,
            BatchTask task,
            String invalidModelText,
            RetryableProviderException firstFailure
    ) {
        StringBuilder sb = new StringBuilder();
        sb.append("Repair the previous model output into valid JSON.\n");
        sb.append("Failure reason: ").append(detailedMessage(firstFailure)).append("\n");
        sb.append("Input words JSON: ").append(toJsonString(task.words())).append("\n");
        sb.append("Item count must be exactly ").append(task.words().size()).append(".\n");
        sb.append("Language scope: source=")
                .append(safe(jobConfig.sourceLanguage(), "English"))
                .append(", target explanation=")
                .append(safe(jobConfig.targetLanguage(), "Chinese"))
                .append(".\n");
        sb.append("Each item field '").append(DictionaryFields.WORD)
                .append("' must equal one input word exactly, case-insensitive.\n");
        sb.append("Required schema:\n");
        appendRequiredSchema(sb);
        sb.append("Output top-level JSON object only: {\"items\":[...]}\n");
        sb.append("Previous invalid model output as JSON string:\n");
        sb.append(toJsonString(compactForPrompt(invalidModelText, 12000))).append("\n");
        sb.append("Return only the repaired JSON object now.");
        return sb.toString();
    }

    private void appendRequiredSchema(StringBuilder sb) {
        sb.append("- ").append(DictionaryFields.WORD).append(": string\n");
        sb.append("- ").append(DictionaryFields.PHONETIC).append(": string\n");
        sb.append("- ").append(DictionaryFields.PART_OF_SPEECH).append(": string\n");
        sb.append("- ").append(DictionaryFields.CORE_MEANING).append(": string\n");
        sb.append("- ").append(DictionaryFields.WORD_FORMS).append(": string\n");
        sb.append("- ").append(DictionaryFields.COMMON_PHRASES).append(": string[]\n");
        sb.append("- ").append(DictionaryFields.EXAMPLE_SENTENCES)
                .append(": object with keys ")
                .append(DictionaryFields.SUBFIELD_EN)
                .append(", ")
                .append(DictionaryFields.SUBFIELD_ZH)
                .append("\n");
        sb.append("- ").append(DictionaryFields.AFFIX_ANALYSIS).append(": string\n");
        sb.append("- ").append(DictionaryFields.MEMORY_STORY)
                .append(": object with keys ")
                .append(DictionaryFields.SUBFIELD_EN)
                .append(", ")
                .append(DictionaryFields.SUBFIELD_ZH)
                .append("\n");
    }

    private String compactForPrompt(String text, int maxChars) {
        if (text == null) {
            return "";
        }
        String compact = text.trim();
        if (compact.length() <= maxChars) {
            return compact;
        }
        int head = Math.max(1000, maxChars / 2);
        int tail = Math.max(1000, maxChars - head - 120);
        return compact.substring(0, Math.min(head, compact.length()))
                + "\n...[content truncated for JSON repair]...\n"
                + compact.substring(Math.max(0, compact.length() - tail));
    }

    private String toJsonString(Object value) {
        try {
            return mapper.writeValueAsString(value);
        } catch (JsonProcessingException ex) {
            return String.valueOf(value);
        }
    }

    private String safe(String value, String fallback) {
        if (value == null || value.isBlank()) {
            return fallback;
        }
        return value.trim();
    }

    private String detailedMessage(Throwable throwable) {
        if (throwable == null) {
            return "unknown";
        }
        StringBuilder sb = new StringBuilder();
        Throwable cursor = throwable;
        int depth = 0;
        while (cursor != null && depth < 8) {
            if (depth > 0) {
                sb.append(" <- ");
            }
            String type = cursor.getClass().getSimpleName();
            String msg = cursor.getMessage();
            if (msg == null || msg.isBlank()) {
                sb.append(type);
            } else {
                sb.append(type).append(": ").append(msg);
            }
            cursor = cursor.getCause();
            depth++;
        }
        return sb.toString();
    }
}
