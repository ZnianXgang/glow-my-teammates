package com.glow.teammates.config;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.glow.teammates.GlowMyTeammates;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.storage.LevelResource;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;

public class GlowConfigManager {
    private static final Gson GSON = new GsonBuilder()
            .setPrettyPrinting()
            .disableHtmlEscaping()
            .create();
    private static final String FILENAME = "glow-my-teammates.json";
    private static GlowConfigManager INSTANCE;

    private boolean enabled = true;
    private final Set<String> enabledTeams = new LinkedHashSet<>();
    private Path configPath;

    public static GlowConfigManager getInstance() {
        if (INSTANCE == null) {
            INSTANCE = new GlowConfigManager();
        }
        return INSTANCE;
    }

    /**
     * Load config from the world save directory.
     * Called when the server starts.
     */
    public void loadFromWorld(MinecraftServer server) {
        Path worldPath = server.getWorldPath(LevelResource.ROOT);
        this.configPath = worldPath.resolve(FILENAME);
        File file = configPath.toFile();

        if (file.exists()) {
            try (Reader reader = new InputStreamReader(
                    Files.newInputStream(file.toPath()), StandardCharsets.UTF_8)) {
                ConfigData data = GSON.fromJson(reader, ConfigData.class);
                if (data != null) {
                    this.enabled = data.enabled;
                    this.enabledTeams.clear();
                    if (data.teams != null) {
                        this.enabledTeams.addAll(data.teams);
                    }
                }
                GlowMyTeammates.LOGGER.info(
                        "Loaded config: enabled={}, teams={}", enabled, enabledTeams);
            } catch (Exception e) {
                GlowMyTeammates.LOGGER.error("Failed to load config, using defaults", e);
                this.enabled = true;
                this.enabledTeams.clear();
            }
        } else {
            GlowMyTeammates.LOGGER.info(
                    "No config file found at {}, creating default", configPath);
            save();
        }
    }

    /**
     * Save current config to file.
     */
    public void save() {
        if (configPath == null) {
            GlowMyTeammates.LOGGER.warn("Cannot save config: no world path set");
            return;
        }
        try {
            Files.createDirectories(configPath.getParent());
            ConfigData data = new ConfigData(enabled, new ArrayList<>(enabledTeams));
            try (Writer writer = new OutputStreamWriter(
                    Files.newOutputStream(configPath), StandardCharsets.UTF_8)) {
                GSON.toJson(data, writer);
            }
            GlowMyTeammates.LOGGER.info("Saved config to {}", configPath);
        } catch (IOException e) {
            GlowMyTeammates.LOGGER.error("Failed to save config", e);
        }
    }

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public boolean isTeamEnabled(String teamName) {
        return enabledTeams.contains(teamName);
    }

    public void addTeam(String teamName) {
        enabledTeams.add(teamName);
    }

    public boolean removeTeam(String teamName) {
        return enabledTeams.remove(teamName);
    }

    public Set<String> getEnabledTeams() {
        return Collections.unmodifiableSet(enabledTeams);
    }

    @SuppressWarnings("unused")
    public static class ConfigData {
        boolean enabled = true;
        List<String> teams = new ArrayList<>();

        ConfigData() {}

        ConfigData(boolean enabled, List<String> teams) {
            this.enabled = enabled;
            this.teams = teams;
        }
    }
}
