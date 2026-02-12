package com.geminispoofer;

import java.util.concurrent.CompletableFuture;

public interface LlmClient {
    CompletableFuture<String> generateReplyAsync(String systemPrompt, String chatContext);
}
