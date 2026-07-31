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
import java.nio.file.StandardCopyOption;
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

    /**
     * Monotonically increasing counter bumped on every state change.
     * Used by the mixin to detect when a full resync is needed.
     */
    private long version;

    /**
     * Bumped whenever a player joins or leaves ANY scoreboard team.
     * Allows {@link com.glow.teammates.mixin.ServerEntityMixin} to detect
     * viewer-side team changes and force a glow resync for affected viewers.
     */
    private long syncEpoch;

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
                        for (String team : data.teams) {
                            if (team != null) {
                                this.enabledTeams.add(team);
                            }
                        }
                    }
                    this.version++;
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
            Path tmpPath = configPath.resolveSibling(configPath.getFileName() + ".tmp");
            try (Writer writer = new OutputStreamWriter(
                    Files.newOutputStream(tmpPath), StandardCharsets.UTF_8)) {
                GSON.toJson(data, writer);
            }
            Files.move(tmpPath, configPath, StandardCopyOption.REPLACE_EXISTING,
                    StandardCopyOption.ATOMIC_MOVE);
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
        this.version++;
    }

    public long getVersion() {
        return version;
    }

    /**
     * Call when a player joins or leaves any team, so the mixin can force
     * a glow-state resync for all viewers of glowing entities.
     */
    public void bumpSyncEpoch() {
        this.syncEpoch++;
    }

    public long getSyncEpoch() {
        return syncEpoch;
    }

    public boolean isTeamEnabled(String teamName) {
        return enabledTeams.contains(teamName);
    }

    public void addTeam(String teamName) {
        enabledTeams.add(teamName);
        this.version++;
    }

    public boolean removeTeam(String teamName) {
        boolean removed = enabledTeams.remove(teamName);
        if (removed) this.version++;
        return removed;
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
