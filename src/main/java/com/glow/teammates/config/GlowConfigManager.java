package com.glow.teammates.config;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.glow.teammates.GlowMyTeammates;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.storage.LevelResource;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
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

    /**
     * Whether the locator bar should show teammates only (feature: locator
     * bar filter). Default {@code false}.
     */
    private boolean locatorBarTeammatesOnly;

    /**
     * Whether non-player entities (mobs) are eligible for team glow.
     * Default {@code false} — once enabled, mob-dense farms pay per-dirty-packet
     * overhead in {@code ServerEntityMixin#redirectSendData}.
     */
    private boolean nonPlayerGlow;

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
                    if (data.config != null) {
                        this.locatorBarTeammatesOnly = data.config.locatorBarTeammatesOnly;
                        this.nonPlayerGlow = data.config.nonPlayerGlow;
                    }
                    // Schema migration: configs written before config_version
                    // existed have a null version array — treat them as legacy
                    // (major 0) and rewrite with the current schema. Runs before
                    // version++ so the migration write keeps the cache-invalidation
                    // semantics intact (the config did change: new fields appeared).
                    int major = (data.configVersion == null || data.configVersion.length == 0)
                            ? 0 : data.configVersion[0];
                    if (major < 1) {
                        save();
                    }
                    this.version++;
                }
                GlowMyTeammates.LOGGER.info(
                        "Loaded config: enabled={}, teams={}", enabled, enabledTeams);
            } catch (Exception e) {
                GlowMyTeammates.LOGGER.error("Failed to load config, using defaults", e);
                this.enabled = true;
                this.enabledTeams.clear();
                // Bump the version so entities that already cached the old
                // config state (cachedConfigVersion == old version) notice
                // the fallback to defaults and force a glow resync.
                this.version++;
            }
        } else {
            GlowMyTeammates.LOGGER.info(
                    "No config file found at {}, creating default", configPath);
            // Reset to defaults instead of inheriting the previous world's
            // config (a stale singleton across server restarts would
            // otherwise leak teams into this new world's config file).
            this.enabled = true;
            this.enabledTeams.clear();
            this.version++;
            save();
        }
    }

    /**
     * Save current config to file.
     *
     * @return {@code true} if the config was persisted successfully,
     *         {@code false} if the file could not be written (logged).
     */
    public boolean save() {
        if (configPath == null) {
            GlowMyTeammates.LOGGER.warn("Cannot save config: no world path set");
            return false;
        }
        try {
            Files.createDirectories(configPath.getParent());
            ConfigData data = new ConfigData(enabled, new ArrayList<>(enabledTeams));
            data.configVersion = new int[]{1, 0};
            ConfigSubData subConfig = new ConfigSubData();
            subConfig.locatorBarTeammatesOnly = this.locatorBarTeammatesOnly;
            subConfig.nonPlayerGlow = this.nonPlayerGlow;
            data.config = subConfig;
            Path tmpPath = configPath.resolveSibling(configPath.getFileName() + ".tmp");
            try (Writer writer = new OutputStreamWriter(
                    Files.newOutputStream(tmpPath), StandardCharsets.UTF_8)) {
                GSON.toJson(data, writer);
            }
            try {
                Files.move(tmpPath, configPath, StandardCopyOption.REPLACE_EXISTING,
                        StandardCopyOption.ATOMIC_MOVE);
            } catch (AtomicMoveNotSupportedException e) {
                // Fall back to a non-atomic move on filesystems that cannot
                // atomically replace an existing file (e.g. some network/FAT
                // volumes) instead of silently losing the save.
                Files.move(tmpPath, configPath, StandardCopyOption.REPLACE_EXISTING);
            }
            GlowMyTeammates.LOGGER.info("Saved config to {}", configPath);
            return true;
        } catch (IOException e) {
            GlowMyTeammates.LOGGER.error("Failed to save config", e);
            return false;
        }
    }

    public boolean isEnabled() {
        return enabled;
    }

    /**
     * Whether any team currently has glow enabled. Zero-allocation fast path
     * (unlike {@link #getEnabledTeams()}, which builds an unmodifiable view)
     * used by the per-packet redirect path to skip lookups when no team is
     * configured.
     */
    public boolean hasEnabledTeams() {
        return !enabledTeams.isEmpty();
    }

    public void setEnabled(boolean enabled) {
        if (this.enabled == enabled) {
            return; // Idempotent — avoid a spurious version bump + full-server resync.
        }
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

    public boolean isLocatorBarTeammatesOnly() {
        return locatorBarTeammatesOnly;
    }

    /**
     * Whether the locator bar should show teammates only. Idempotent — no-op
     * when the value is unchanged, to avoid a spurious version bump and the
     * resulting full-server resync.
     */
    public void setLocatorBarTeammatesOnly(boolean locatorBarTeammatesOnly) {
        if (this.locatorBarTeammatesOnly == locatorBarTeammatesOnly) {
            return;
        }
        this.locatorBarTeammatesOnly = locatorBarTeammatesOnly;
        this.version++;
    }

    public boolean isNonPlayerGlow() {
        return nonPlayerGlow;
    }

    /**
     * Whether non-player entities are eligible for team glow. Idempotent —
     * no-op when the value is unchanged.
     */
    public void setNonPlayerGlow(boolean nonPlayerGlow) {
        if (this.nonPlayerGlow == nonPlayerGlow) {
            return;
        }
        this.nonPlayerGlow = nonPlayerGlow;
        this.version++;
    }

    public Set<String> getEnabledTeams() {
        return Collections.unmodifiableSet(enabledTeams);
    }

    @SuppressWarnings("unused")
    public static class ConfigData {
        boolean enabled = true;
        List<String> teams = new ArrayList<>();
        /**
         * Disk schema version {@code [major, minor]}. {@code null} means the
         * file predates schema versioning (legacy) — the loader migrates it.
         */
        int[] configVersion;
        /**
         * Feature switches. {@code null} means legacy config — the loader
         * falls back to defaults and rewrites the file with the current schema.
         */
        ConfigSubData config;

        ConfigData() {}

        ConfigData(boolean enabled, List<String> teams) {
            this.enabled = enabled;
            this.teams = teams;
        }
    }

    public static class ConfigSubData {
        boolean locatorBarTeammatesOnly = false;
        boolean nonPlayerGlow = false;

        ConfigSubData() {}
    }
}
