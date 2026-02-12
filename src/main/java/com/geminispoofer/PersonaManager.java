package com.geminispoofer;

import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.File;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ThreadLocalRandom;

public class PersonaManager {
    private final JavaPlugin plugin;
    private final List<Persona> personas = new ArrayList<>();
    private final java.util.Map<String, Persona> personasByName = new java.util.HashMap<>();

    public PersonaManager(JavaPlugin plugin) {
        this.plugin = plugin;
    }

    /**
     * Loads (or reloads) all bot personalities from personalities.yml.
     */
    public void loadPersonalities() {
        personas.clear();
        personasByName.clear();

        plugin.saveResource("personalities.yml", false);
        File file = new File(plugin.getDataFolder(), "personalities.yml");
        YamlConfiguration config = YamlConfiguration.loadConfiguration(file);

        ConfigurationSection botsSection = config.getConfigurationSection("bots");
        if (botsSection == null) {
            plugin.getLogger().warning("No 'bots' section found in personalities.yml");
            return;
        }

        for (String key : botsSection.getKeys(false)) {
            ConfigurationSection entry = botsSection.getConfigurationSection(key);
            if (entry == null) {
                continue;
            }
            String skin = entry.getString("skin", key);
            String systemPrompt = entry.getString("system_prompt", "");
            Persona persona = new Persona(key, skin, systemPrompt);
            personas.add(persona);
            personasByName.put(key.toLowerCase(Locale.ROOT), persona);
        }

        plugin.getLogger().info("Loaded " + personas.size() + " persona(s).");
    }

    /**
     * Returns an unmodifiable view of all loaded personas.
     */
    public List<Persona> getPersonas() {
        return Collections.unmodifiableList(personas);
    }

    /**
     * Returns a persona by name (case-insensitive), or null if not found.
     */
    public Persona getPersona(String name) {
        if (name == null) {
            return null;
        }
        return personasByName.get(name.toLowerCase(Locale.ROOT));
    }

    /**
     * Picks a random persona whose name does NOT match {@code excludeSender}
     * (case-insensitive). This prevents a bot from replying to itself.
     *
     * @param excludeSender the name to exclude, or null to allow any
     * @return a random Persona, or null if none are available
     */
    public Persona getRandomPersona(String excludeSender) {
        if (personas.isEmpty()) {
            return null;
        }

        List<Persona> candidates = new ArrayList<>(personas);
        if (excludeSender != null) {
            String excluded = excludeSender.toLowerCase(Locale.ROOT);
            candidates.removeIf(p -> p.name().toLowerCase(Locale.ROOT).equals(excluded));
        }

        if (candidates.isEmpty()) {
            return null;
        }

        return candidates.get(ThreadLocalRandom.current().nextInt(candidates.size()));
    }
}
