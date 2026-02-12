# Copilot instructions for FinnehSpoof

## Big picture
- This is a Spigot plugin that fakes player population with Citizens NPCs and drives their chat via Google Gemini.
- `GeminiSpoofer` wires the system: database setup, config load, and schedules `BotSessionManager` + `DirectorTask` ticks. See [src/main/java/com/geminispoofer/GeminiSpoofer.java](src/main/java/com/geminispoofer/GeminiSpoofer.java).
- Chat flow: `ChatListener` logs human chat async to SQLite -> `DirectorTask` pulls recent context + last sender -> `GeminiClient` generates reply -> `BotActor` splits on "|" and schedules typed chat. See [src/main/java/com/geminispoofer/ChatListener.java](src/main/java/com/geminispoofer/ChatListener.java), [src/main/java/com/geminispoofer/DirectorTask.java](src/main/java/com/geminispoofer/DirectorTask.java), [src/main/java/com/geminispoofer/GeminiClient.java](src/main/java/com/geminispoofer/GeminiClient.java), [src/main/java/com/geminispoofer/BotActor.java](src/main/java/com/geminispoofer/BotActor.java).
- NPC lifecycle: `BotSessionManager` decides join/leave/walk based on config and calls `BotManager` to spawn/despawn. See [src/main/java/com/geminispoofer/BotSessionManager.java](src/main/java/com/geminispoofer/BotSessionManager.java) and [src/main/java/com/geminispoofer/BotManager.java](src/main/java/com/geminispoofer/BotManager.java).

## Critical runtime constraints
- Citizens API must run on the main thread; all Citizens interactions go through `BotManager.callSync()`.
- Database I/O is async and synchronized on `dbLock`; avoid touching SQLite from main thread.
- Bot chat expects Gemini responses to use pipe separators for multi-burst messages (see `BotActor.performChat`).

## Config, data, and integration points
- Config is in [src/main/resources/config.yml](src/main/resources/config.yml) and copied to plugins/GeminiSpoofer on first run.
- Personas are loaded from [src/main/resources/personalities.yml](src/main/resources/personalities.yml) by `PersonaManager`.
- SQLite chat log lives at plugins/GeminiSpoofer/chat.db with table `chat_log (sender, message, timestamp)`.
- Gemini REST calls are in `GeminiClient` via java.net.http; API key comes from config `api_key`.
- Runtime dependencies: Citizens and FinnehCore are hard depends in [src/main/resources/plugin.yml](src/main/resources/plugin.yml).

## Build & dev workflow
- Java 17 with Maven; build with the root POM [pom.xml](pom.xml) to produce the plugin jar.
- `spigot-api` and `citizensapi` are provided-scope; test in a server with Citizens installed.

## Project-specific patterns
- Chat heat and activity scaling live in `DirectorTask` and `GeminiSpoofer.getActivityMultiplier()`; keep new chat logic consistent with those controls.
- When adding bot behaviors, prefer scheduling via Bukkit tasks and reuse `BotSessionManager` cadence (100 ticks).
- Use `FinnehSpoofCommand` for admin operations; current convention is `/finnehspoof reload` to re-read config and personalities.
