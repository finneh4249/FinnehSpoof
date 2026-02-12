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

public class OpenAiClient implements LlmClient {
    private static final String ENDPOINT = "https://api.openai.com/v1/chat/completions";
    private final JavaPlugin plugin;
    private final HttpClient httpClient;
    private final Gson gson = new Gson();
    private boolean warnedMissingKey = false;

    public OpenAiClient(JavaPlugin plugin) {
        this.plugin = plugin;
        this.httpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .build();
    }

    @Override
    public CompletableFuture<String> generateReplyAsync(String systemPrompt, String chatContext) {
        String apiKey = plugin.getConfig().getString("openai_api_key", "").trim();
        if (apiKey.isEmpty()) {
            warnMissingKeyOnce("OpenAI");
            return CompletableFuture.completedFuture(null);
        }
        String model = plugin.getConfig().getString("openai_model", "gpt-4o-mini");

        JsonObject payload = new JsonObject();
        payload.addProperty("model", model);

        JsonArray messages = new JsonArray();
        messages.add(buildMessage("system", buildSystemPrompt(systemPrompt)));
        messages.add(buildMessage("user", PromptBuilder.buildUserPrompt(chatContext)));
        payload.add("messages", messages);

        HttpRequest request = HttpRequest.newBuilder()
            .uri(URI.create(ENDPOINT))
            .timeout(Duration.ofSeconds(20))
            .header("Content-Type", "application/json")
            .header("Authorization", "Bearer " + apiKey)
            .POST(HttpRequest.BodyPublishers.ofString(gson.toJson(payload), StandardCharsets.UTF_8))
            .build();

        return httpClient
            .sendAsync(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8))
            .thenApply(response -> {
                int status = response.statusCode();
                if (status < 200 || status >= 300) {
                    plugin.getLogger().log(Level.WARNING, "OpenAI API returned status " + status + ": " + response.body());
                    return null;
                }
                return parseResponseText(response.body());
            })
            .exceptionally(exception -> {
                plugin.getLogger().log(Level.WARNING, "OpenAI API request failed: " + exception.getMessage());
                return null;
            });
    }

    private JsonObject buildMessage(String role, String content) {
        JsonObject message = new JsonObject();
        message.addProperty("role", role);
        message.addProperty("content", content);
        return message;
    }

    private String parseResponseText(String body) {
        try {
            JsonObject root = JsonParser.parseString(body).getAsJsonObject();
            JsonArray choices = root.getAsJsonArray("choices");
            if (choices == null || choices.size() == 0) {
                return null;
            }
            JsonObject first = choices.get(0).getAsJsonObject();
            JsonObject message = first.getAsJsonObject("message");
            if (message == null || !message.has("content")) {
                return null;
            }
            return message.get("content").getAsString();
        } catch (Exception exception) {
            plugin.getLogger().log(Level.SEVERE, "Error parsing OpenAI API response: " + body, exception);
            return null;
        }
    }

    private String buildSystemPrompt(String systemPrompt) {
        return PromptBuilder.buildSystemPrompt(systemPrompt);
    }

    private void warnMissingKeyOnce(String provider) {
        if (!warnedMissingKey) {
            warnedMissingKey = true;
            plugin.getLogger().warning(provider + " API key is missing. Configure openai_api_key in config.yml.");
        }
    }
}
