Here is the complete, high-specification project board for **GeminiSpoofer**. Copy and paste these tickets directly to your AI developer (Cursor, GitHub Copilot, etc.) one by one.

### Project Stack

* **Language:** Java 17+ (Spigot 1.20+).
* **Dependencies:** Citizens (API), Gson (Shaded in Spigot).
* **Database:** SQLite (Local file).
* **AI Model:** Google Gemini Flash Lite Latest (REST API).

---

### Ticket 1: The Foundation & Memory (Database)

**Objective:** Create the plugin skeleton and a persistent chat log system using SQLite.

**Requirements:**

1. **Main Class:** Create `GeminiSpoofer.java` extending `JavaPlugin`.
2. **Database Connection:**
* On `onEnable`, connect to `jdbc:sqlite:plugins/GeminiSpoofer/chat.db`.
* Create table if missing: `chat_log (id INTEGER PRIMARY KEY, sender VARCHAR(32), message TEXT, timestamp LONG)`.


3. **Methods:**
* `logMessageAsync(String sender, String message)`: Inserts a row asynchronously.
* `getRecentContextAsync(int limit)`: Returns the last `limit` rows formatted as `"Sender: Message\n"`.


4. **Listener:**
* Create `ChatListener` listening for `AsyncPlayerChatEvent`.
* If the message does NOT start with `/`, call `logMessageAsync`.
* **Constraint:** Ignore messages from players with permission `geminispoofer.ignore`.



---

### Ticket 2: The Body Manager (Citizens Wrapper)

**Objective:** Abstract the Citizens API to manage NPC entities easily.

**Requirements:**

1. **Dependency:** Add `Citizens` to `plugin.yml` as `depend`.
2. **Class:** Create `BotManager.java`.
3. **Methods:**
* `spawnBot(String name, String skinName, Location loc)`:
* Creates a Citizens NPC.
* Sets the skin using the name.
* Spawns it at the location.
* Returns the `NPC` object.


* `despawnBot(String name)`: Finds and removes the NPC.
* `getAllBots()`: Returns a List of all active Citizens NPCs belonging to this plugin.


4. **Constraint:** Ensure all Citizens API calls are done on the **Main Thread** (use `Bukkit.getScheduler().runTask`).

---

### Ticket 3: The Personality Engine (Config)

**Objective:** Load bot identities from a YAML file to give them specific character traits.

**Requirements:**

1. **Config:** Create `personalities.yml` in the resources folder.
* Structure:
```yaml
bots:
  miner_steve:
    skin: "Steve"
    system_prompt: "You are a grumpy miner. You hate gravel. Keep responses short."
  pvp_chad:
    skin: "Technoblade"
    system_prompt: "You are a PVP sweat. You only talk about K/D. Use slang."

```




2. **Class:** Create `PersonaManager.java`.
3. **Methods:**
* `loadPersonalities()`: Parse the YAML into a Java Object/Map.
* `getRandomPersona(String excludeSender)`: Returns a random Persona object.
* **Logic:** Must loop until it finds a persona that is NOT `excludeSender` (to prevent a bot replying to itself).





---

### Ticket 4: The Brain (Gemini HTTP Client)

**Objective:** Connect to the Gemini API without heavy libraries.

**Requirements:**

1. **Class:** Create `GeminiClient.java`.
2. **Library:** Use standard `java.net.http.HttpClient`.
3. **Method:** `generateReplyAsync(String systemPrompt, String chatContext)`:
* **Endpoint:** POST `https://generativelanguage.googleapis.com/v1beta/models/gemini-flash-lite-latest:generateContent?key={API_KEY}`.
* **JSON Payload:**
* `systemInstruction`: "You are a Minecraft player. Context: {systemPrompt}. **Crucial:** If you have multiple thoughts, separate them with a pipe '|'. Example: 'lol | wait what'. Do not use caps."
* `contents`: User role -> "Current Chat:\n{chatContext}".


* **Return:** A `CompletableFuture<String>` containing the raw text response.


4. **Error Handling:** If the API fails (429/500), return `null` silently.

---

### Ticket 5: The Performer (Burst & Delay Logic)

**Objective:** Make the bots type like humans (multiple lines, dynamic delays) instead of dumping text instantly.

**Requirements:**

1. **Class:** Create `BotActor.java`.
2. **Method:** `performChat(NPC npc, String rawResponse)`.
3. **Logic:**
* Split `rawResponse` by the pipe character `|`.
* Initialize `long delay = 0`.
* Loop through split parts:
* Schedule a task to run `npc.getEntity().chat(part)` after `delay` ticks.
* **Critical:** Immediately after chatting, call `Database.logMessageAsync(npc.getName(), part)` so the context stays updated for the next bot.
* Increment `delay`: `current_part_length * 2 ticks + 20 ticks` (Add "typing time" buffer).





---

### Ticket 6: The Director (The Game Loop)

**Objective:** The master scheduler that decides who talks and when.

**Requirements:**

1. **Class:** Create `DirectorTask` extending `BukkitRunnable`.
2. **Variables:**
* `lastTalkTime`: Long (timestamp).
* `heatLevel`: Double (0.0 - 1.0).


3. **Logic (Run every 100 ticks / 5 seconds):**
* **Step 1:** Check `Bukkit.getOnlinePlayers().size()`. If 0, return.
* **Step 2:** Calculate Chance.
* Base: 5%.
* If `(now - lastTalkTime) < 15 seconds`: Chance = 60% (Simulate active conversation).
* If `(now - lastTalkTime) > 5 minutes`: Chance = 1% (Dead chat).


* **Step 3:** Roll Dice. If pass:
* Get `chatContext` from DB (Last 15 lines).
* Get `lastSender` from DB.
* Pick `Persona` via `PersonaManager.getRandomPersona(lastSender)`.
* Call `GeminiClient` -> Get Response -> Call `BotActor.performChat`.