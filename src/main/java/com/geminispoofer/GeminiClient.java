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

public class GeminiClient implements LlmClient {
    private static final String ENDPOINT_BASE = "https://generativelanguage.googleapis.com/v1beta/models/";
    private final JavaPlugin plugin;
    private final HttpClient httpClient;
    private final Gson gson = new Gson();
    private boolean warnedMissingKey = false;

    public GeminiClient(JavaPlugin plugin) {
        this.plugin = plugin;
        this.httpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .build();
    }

    public CompletableFuture<String> generateReplyAsync(String systemPrompt, String chatContext) {
        String apiKey = plugin.getConfig().getString("gemini_api_key", "").trim();
        if (apiKey.isEmpty()) {
            apiKey = plugin.getConfig().getString("api_key", "").trim();
        }
        if (apiKey.isEmpty()) {
            warnMissingKeyOnce("Gemini");
            return CompletableFuture.completedFuture(null);
        }

        JsonObject payload = new JsonObject();
        JsonObject systemInstruction = new JsonObject();
        JsonArray systemParts = new JsonArray();
        JsonObject systemText = new JsonObject();
        systemText.addProperty(
            "text",
            PromptBuilder.buildSystemPrompt(systemPrompt)
        );
        systemParts.add(systemText);
        systemInstruction.add("parts", systemParts);
        payload.add("systemInstruction", systemInstruction);

        JsonArray contents = new JsonArray();
        JsonObject userContent = new JsonObject();
        userContent.addProperty("role", "user");
        JsonArray userParts = new JsonArray();
        JsonObject userText = new JsonObject();
        userText.addProperty("text", PromptBuilder.buildUserPrompt(chatContext));
        userParts.add(userText);
        userContent.add("parts", userParts);
        contents.add(userContent);
        payload.add("contents", contents);

        String model = plugin.getConfig().getString("gemini_model", "gemini-2.0-flash-exp");
        String endpoint = ENDPOINT_BASE + model + ":generateContent?key=" + apiKey;
        
        HttpRequest request = HttpRequest.newBuilder()
            .uri(URI.create(endpoint))
            .timeout(Duration.ofSeconds(20))
            .header("Content-Type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString(gson.toJson(payload), StandardCharsets.UTF_8))
            .build();

        return httpClient
            .sendAsync(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8))
            .thenApply(response -> {
                int status = response.statusCode();
                String body = response.body();
                
                if (plugin.getConfig().getBoolean("debug_chat", false)) {
                    plugin.getLogger().info("[ChatDebug] Gemini API response: status=" + status + ", body=" + body);
                }
                
                if (status == 429 || status == 500) {
                    plugin.getLogger().warning("Gemini API error " + status + ": " + body);
                    return null;
                }
                if (status < 200 || status >= 300) {
                    plugin.getLogger().log(Level.WARNING, "Gemini API returned status " + status + ": " + body);
                    return null;
                }
                return parseResponseText(body);
            })
            .exceptionally(exception -> {
                plugin.getLogger().log(Level.WARNING, "Gemini API request failed: " + exception.getMessage(), exception);
                return null;
            });
    }

    private void warnMissingKeyOnce(String provider) {
        if (!warnedMissingKey) {
            warnedMissingKey = true;
            plugin.getLogger().warning(provider + " API key is missing. Configure gemini_api_key or api_key in config.yml.");
        }
    }

    private String parseResponseText(String body) {
        try {
            JsonObject root = JsonParser.parseString(body).getAsJsonObject();
            JsonArray candidates = root.getAsJsonArray("candidates");
            if (candidates == null || candidates.size() == 0) {
                return null;
            }
            JsonObject firstCandidate = candidates.get(0).getAsJsonObject();
            JsonObject content = firstCandidate.getAsJsonObject("content");
            if (content == null) {
                return null;
            }
            JsonArray parts = content.getAsJsonArray("parts");
            if (parts == null || parts.size() == 0) {
                return null;
            }
            JsonObject firstPart = parts.get(0).getAsJsonObject();
            if (!firstPart.has("text")) {
                return null;
            }
            String text = firstPart.get("text").getAsString();
            if (root.has("error") && root.getAsJsonObject("error").has("code") && root.getAsJsonObject("error").get("code").getAsInt() == 429) {
                plugin.getLogger().log(Level.WARNING, "Gemini API returned 429 error: " + body);
            }
            return text;
        } catch (Exception exception) {
            plugin.getLogger().log(Level.SEVERE, "Error parsing Gemini API response: " + body, exception);
            return null;
        }
    }
}
