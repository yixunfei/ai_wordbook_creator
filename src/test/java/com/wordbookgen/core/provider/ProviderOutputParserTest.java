package com.wordbookgen.core.provider;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.wordbookgen.core.model.BatchTask;
import com.wordbookgen.core.model.DictionaryFields;
import com.wordbookgen.core.provider.ProviderExceptions.RetryableProviderException;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ProviderOutputParserTest {

    private final ObjectMapper mapper = new ObjectMapper();
    private final ProviderOutputParser parser = new ProviderOutputParser(mapper, "test-provider");

    @Test
    void extractsAndValidatesStandardChatCompletionContent() throws Exception {
        String modelText = mapper.writeValueAsString(mapper.createObjectNode()
                .set("items", mapper.createArrayNode().add(dictionaryItem("example"))));
        String response = chatCompletionResponse(modelText, "stop");

        ProviderModelContent content = parser.extractModelContent(response, false, false);
        Map<String, JsonNode> entries = parser.parseAndValidateModelText(
                new BatchTask(0, List.of("example")),
                content.text(),
                false,
                false,
                null);

        assertEquals(1, entries.size());
        assertEquals("example", entries.get("example").path(DictionaryFields.WORD).asText());
    }

    @Test
    void compatibleModeAcceptsDirectDictionaryPayload() throws Exception {
        String response = mapper.writeValueAsString(mapper.createObjectNode()
                .set("items", mapper.createArrayNode().add(dictionaryItem("word"))));

        ProviderModelContent content = parser.extractModelContent(response, true, false);
        Map<String, JsonNode> entries = parser.parseAndValidateModelText(
                new BatchTask(1, List.of("word")),
                content.text(),
                false,
                true,
                null);

        assertTrue(entries.containsKey("word"));
    }

    @Test
    void strictModeRejectsTruncatedContentWhenContinuationDisabled() throws Exception {
        String response = chatCompletionResponse("{}", "length");

        RetryableProviderException ex = assertThrows(
                RetryableProviderException.class,
                () -> parser.extractModelContent(response, false, false));

        assertTrue(ex.getMessage().contains("truncated"));
    }

    private String chatCompletionResponse(String modelText, String finishReason) throws Exception {
        ObjectNode message = mapper.createObjectNode()
                .put("role", "assistant")
                .put("content", modelText);
        ObjectNode choice = mapper.createObjectNode()
                .put("finish_reason", finishReason)
                .set("message", message);
        return mapper.writeValueAsString(mapper.createObjectNode()
                .set("choices", mapper.createArrayNode().add(choice)));
    }

    private ObjectNode dictionaryItem(String word) {
        ObjectNode item = mapper.createObjectNode();
        item.put(DictionaryFields.WORD, word);
        item.put(DictionaryFields.PHONETIC, "/" + word + "/");
        item.put(DictionaryFields.PART_OF_SPEECH, "noun");
        item.put(DictionaryFields.CORE_MEANING, "meaning");
        item.put(DictionaryFields.WORD_FORMS, "plural: " + word + "s");
        item.set(DictionaryFields.COMMON_PHRASES, mapper.createArrayNode().add("sample phrase"));
        item.set(DictionaryFields.EXAMPLE_SENTENCES, bilingual("This is a sentence.", "这是一个句子。"));
        item.put(DictionaryFields.AFFIX_ANALYSIS, "root");
        item.set(DictionaryFields.MEMORY_STORY, bilingual("Remember the word.", "记住这个词。"));
        return item;
    }

    private ObjectNode bilingual(String english, String chinese) {
        return mapper.createObjectNode()
                .put(DictionaryFields.SUBFIELD_EN, english)
                .put(DictionaryFields.SUBFIELD_ZH, chinese);
    }
}
