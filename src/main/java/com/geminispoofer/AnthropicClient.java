package com.geminispoofer;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.bukkit.plugin.java.JavaPlugin;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import java.util.logging.Level;

public class AnthropicClient implements LlmClient {
    private static final String ENDPOINT = "https://api.anthropic.com/v1/messages";
    private final JavaPlugin plugin;
    private final HttpClient httpClient;
    private final Gson gson = new Gson();
    private boolean warnedMissingKey = false;

    public AnthropicClient(JavaPlugin plugin) {
        this.plugin = plugin;
        this.httpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .build();
    }

    @Override
    public CompletableFuture<String> generateReplyAsync(String systemPrompt, String chatContext) {
        String apiKey = plugin.getConfig().getString("anthropic_api_key", "").trim();
        if (apiKey.isEmpty()) {
            warnMissingKeyOnce("Anthropic");
            return CompletableFuture.completedFuture(null);
        }
        String model = plugin.getConfig().getString("anthropic_model", "claude-3-5-haiku-20241022");

        JsonObject payload = new JsonObject();
        payload.addProperty("model", model);
        payload.addProperty("max_tokens", plugin.getConfig().getInt("anthropic_max_tokens", 200));
        payload.addProperty("system", buildSystemPrompt(systemPrompt));

        JsonArray messages = new JsonArray();
        JsonObject message = new JsonObject();
        message.addProperty("role", "user");
        message.addProperty("content", PromptBuilder.buildUserPrompt(chatContext));
        messages.add(message);
        payload.add("messages", messages);

        HttpRequest request = HttpRequest.newBuilder()
            .uri(URI.create(ENDPOINT))
            .timeout(Duration.ofSeconds(20))
            .header("Content-Type", "application/json")
            .header("x-api-key", apiKey)
            .header("anthropic-version", "2023-06-01")
            .POST(HttpRequest.BodyPublishers.ofString(gson.toJson(payload), StandardCharsets.UTF_8))
            .build();

        return httpClient
            .sendAsync(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8))
            .thenApply(response -> {
                int status = response.statusCode();
                if (status < 200 || status >= 300) {
                    plugin.getLogger().log(Level.WARNING, "Anthropic API returned status " + status + ": " + response.body());
                    return null;
                }
                return parseResponseText(response.body());
            })
            .exceptionally(exception -> {
                plugin.getLogger().log(Level.WARNING, "Anthropic API request failed: " + exception.getMessage());
                return null;
            });
    }

    private String parseResponseText(String body) {
        try {
            JsonObject root = JsonParser.parseString(body).getAsJsonObject();
            JsonArray content = root.getAsJsonArray("content");
            if (content == null || content.size() == 0) {
                return null;
            }
            JsonObject first = content.get(0).getAsJsonObject();
            if (!first.has("text")) {
                return null;
            }
            return first.get("text").getAsString();
        } catch (Exception exception) {
            plugin.getLogger().log(Level.SEVERE, "Error parsing Anthropic API response: " + body, exception);
            return null;
        }
    }

    private String buildSystemPrompt(String systemPrompt) {
        return PromptBuilder.buildSystemPrompt(systemPrompt);
    }

    private void warnMissingKeyOnce(String provider) {
        if (!warnedMissingKey) {
            warnedMissingKey = true;
            plugin.getLogger().warning(provider + " API key is missing. Configure anthropic_api_key in config.yml.");
        }
    }
}
