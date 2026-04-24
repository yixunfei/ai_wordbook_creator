package com.wordbookgen.core.provider;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.wordbookgen.core.model.ProviderConfig;

import java.io.IOException;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

/**
 * 通过 OpenAI 兼容协议发现模型列表。
 */
public class ModelDiscoveryClient {

    private final HttpClient httpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .build();

    private final ObjectMapper mapper = new ObjectMapper();

    public List<String> discover(ProviderConfig config, Duration timeout) throws IOException, InterruptedException {
        URI modelsEndpoint = deriveModelsEndpoint(config.endpoint());
        HttpRequest request = HttpRequest.newBuilder(modelsEndpoint)
                .timeout(timeout)
                .header("Authorization", "Bearer " + config.apiKey())
                .GET()
                .build();

        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
        int status = response.statusCode();
        String body = response.body() == null ? "" : response.body();
        if (status < 200 || status >= 300) {
            throw new IOException("discover models failed: HTTP " + status + ", body=" + summarize(body));
        }

        return parseModels(body);
    }

    URI deriveModelsEndpoint(URI endpoint) {
        String path = endpoint.getPath();
        if (path == null || path.isBlank()) {
            path = "/models";
        }
        String lowerPath = path.toLowerCase(Locale.ROOT);

        String modelsPath;
        if (lowerPath.endsWith("/chat/completions")) {
            modelsPath = path.substring(0, path.length() - "/chat/completions".length()) + "/models";
        } else if (lowerPath.endsWith("/responses")) {
            modelsPath = path.substring(0, path.length() - "/responses".length()) + "/models";
        } else if (lowerPath.endsWith("/completions")) {
            modelsPath = path.substring(0, path.length() - "/completions".length()) + "/models";
        } else if (lowerPath.endsWith("/models")) {
            modelsPath = path;
        } else if (path.endsWith("/")) {
            modelsPath = path + "models";
        } else {
            modelsPath = path + "/models";
        }

        if (!modelsPath.startsWith("/")) {
            modelsPath = "/" + modelsPath;
        }
        try {
            return new URI(endpoint.getScheme(), endpoint.getAuthority(), modelsPath, null, null);
        } catch (URISyntaxException ex) {
            String base = endpoint.toString();
            int queryStart = base.indexOf('?');
            if (queryStart >= 0) {
                base = base.substring(0, queryStart);
            }
            if (base.endsWith("/")) {
                return URI.create(base + "models");
            }
            return URI.create(base + "/models");
        }
    }

    private List<String> parseModels(String body) throws IOException {
        JsonNode root = mapper.readTree(body);
        List<String> models = new ArrayList<>();

        JsonNode dataNode = root.path("data");
        if (dataNode.isArray()) {
            for (JsonNode item : dataNode) {
                String id = item.path("id").asText("");
                if (!id.isBlank()) {
                    models.add(id);
                }
            }
        }

        if (models.isEmpty()) {
            JsonNode modelsNode = root.path("models");
            if (modelsNode.isArray()) {
                for (JsonNode item : modelsNode) {
                    String id = item.path("id").asText(item.path("name").asText(""));
                    if (!id.isBlank()) {
                        models.add(id);
                    }
                }
            }
        }

        models = models.stream().distinct().sorted(Comparator.naturalOrder()).toList();
        if (models.isEmpty()) {
            throw new IOException("no models found in response");
        }
        return models;
    }

    private String summarize(String text) {
        String compact = text == null ? "" : text.replaceAll("\\s+", " ").trim();
        if (compact.length() <= 200) {
            return compact;
        }
        return compact.substring(0, 200) + "...";
    }
}
