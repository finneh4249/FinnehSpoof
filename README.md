# GeminiSpoofer

Lightweight Spigot plugin that spawns Citizens NPCs which chat via Google Gemini (gemini-flas-lite-latest) and persist conversation context to SQLite.

## Quick info
- Language: Java 17+
- Minecraft/Spigot: 1.20+
- Dependencies: Citizens (depend), Gson (shaded)
- Database: SQLite (plugins/GeminiSpoofer/chat.db)
- AI: Google Gemini Flash Lite Latest (REST)
- Versioning: EPOCH SemVer ({EPOCH * 1000 + MAJOR}.MINOR.PATCH)

## Features
- Persistent chat log (SQLite)
- Citizens-based NPC spawn/despawn and skin control
- Persona-driven system prompts loaded from `personalities.yml`
- Gemini HTTP client using java.net.http
- Human-like typing bursts and delays
- Director scheduler deciding when NPCs speak

## Installation
1. Build the plugin jar (Java 17).
2. Place jar into `plugins/`.
3. Install Citizens plugin (required).
4. Start server to generate plugin folder.

## Configuration
- plugin config (create `plugins/GeminiSpoofer/config.yml`):
    - `api_key: "YOUR_GOOGLE_API_KEY"`
    - other runtime flags if needed

- personalities (resources/personalities.yml or `plugins/GeminiSpoofer/personalities.yml`):
```yaml
bots:
    miner_steve:
        skin: "Steve"
        system_prompt: "You are a grumpy miner. You hate gravel. Keep responses short."
    pvp_chad:
        skin: "Technoblade"
        system_prompt: "You are a PVP sweat. You only talk about K/D. Use slang."
```

## Database
- Path: `plugins/GeminiSpoofer/chat.db`
- Table: `chat_log (id INTEGER PRIMARY KEY, sender VARCHAR(32), message TEXT, timestamp LONG)`

## Permissions
- `geminispoofer.ignore` — messages from players with this permission are not logged.

## Developer Tickets (overview)
1. Foundation & Memory: main plugin class `GeminiSpoofer`, SQLite connection, async logging, `ChatListener`.
2. Body Manager: `BotManager` wrapping Citizens API (main thread calls).
3. Personality Engine: `PersonaManager` loads `personalities.yml` and returns random persona excluding sender.
4. Gemini Client: `GeminiClient` using java.net.http, `generateReplyAsync(systemPrompt, chatContext)`.
5. Bot Actor: `BotActor.performChat(NPC, rawResponse)` splits on `|`, schedules typed chat, logs bot messages.
6. Director: `DirectorTask` runs every 100 ticks, decides chance to speak, retrieves context, picks persona, requests Gemini, triggers actor.

## Runtime notes
- All Citizens API interactions must run on the main thread.
- API failures (429/500) return null silently.
- Bot messages are logged immediately after being sent so context stays current.

## Building & Testing
- Use a standard build pipeline (Maven/Gradle) targeting Java 17.
- Ensure Citizens is installed on test server.
- Provide valid Google API key in config before enabling Gemini features.

## EPOCH SemVer

The format is as follows:
{EPOCH * 1000 + MAJOR}.MINOR.PATCH

    EPOCH: Increment when you make significant or groundbreaking changes.
    MAJOR: Increment when you make minor incompatible API changes.
    MINOR: Increment when you add functionality in a backwards-compatible manner.
    PATCH: Increment when you make backwards-compatible bug fixes.

For example, UnoCSS would transition from v0.65.3 to v65.3.0 (in the case EPOCH is 0). Following SemVer, a patch release would become v65.3.1, and a feature release would be v65.4.0. If we introduced some minor incompatible changes affecting an edge case, we could bump it to v66.0.0 to alert users of potential impacts. In the event of a significant overhaul to the core, we could jump directly to v1000.0.0 to signal a new era and make a big announcement. I’d suggest assigning a code name to each non-zero EPOCH to make it more memorable and easier to refer to. This approach provides maintainers with more flexibility to communicate the scale of changes to users effectively.

Tip

We shouldn’t need to bump EPOCH often. It’s mostly useful for high-level, end-user-facing libraries or frameworks. For low-level libraries, they might never need to bump EPOCH at all (ZERO-EPOCH is essentially the same as SemVer).

Of course, I’m not suggesting that everyone should adopt this approach. It’s simply an idea to work around the existing system, and only for those packages with this need. It will be interesting to see how it performs in practice.

## License
Project-specific; add a LICENSE file.

