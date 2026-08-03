package com.glow.teammates.config;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.glow.teammates.GlowMyTeammates;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.storage.LevelResource;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.AccessDeniedException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.*;

/**
 * Process-wide holder of the mod's runtime state and its per-world JSON
 * persistence.
 *
 * <p><strong>Threading:</strong> all mutable state is owned by the server
 * thread and must only be written from it (command execution, ServerEntity
 * ticks, Scoreboard events). Reading live state (getters such as
 * {@code isEnabled()}) from async contexts is unsupported; only
 * {@link #getEnabledTeams()} returns a defensive snapshot that is safe to
 * iterate anywhere.
 */
public class GlowConfigManager {
    private static final Gson GSON = new GsonBuilder()
            .setPrettyPrinting()
            .disableHtmlEscaping()
            .create();
    private static final String FILENAME = "glow-my-teammates.json";
    private static final GlowConfigManager INSTANCE = new GlowConfigManager();

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
     * Whether a viewer in a glow-enabled team sees only their own teammates
     * on the locator bar (feature: locator bar filter). Default {@code false}.
     */
    private boolean locatorBarTeammatesOnly;

    /**
     * Whether non-player entities (mobs) are eligible for team glow.
     * Default {@code false} — once enabled, mob-dense farms pay per-dirty-packet
     * overhead in {@code ServerEntityMixin#redirectSendData}.
     */
    private boolean nonPlayerGlow;

    public static GlowConfigManager getInstance() {
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
            try {
                // Read the raw text once: the 1.1.1 schema migration needs to
                // inspect the old key name that Gson would silently drop.
                String rawJson = Files.readString(file.toPath(), StandardCharsets.UTF_8);
                ConfigData data = GSON.fromJson(rawJson, ConfigData.class);
                if (data != null) {
                    this.enabled = data.enabled;
                    this.enabledTeams.clear();
                    if (data.teams != null) {
                        for (String team : data.teams) {
                            // Skip null and empty names — an empty string can
                            // never match a real team (vanilla forbids it) and
                            // would otherwise be persisted back on the next save.
                            if (team != null && !team.isEmpty()) {
                                this.enabledTeams.add(team);
                            }
                        }
                    }
                    if (data.config != null) {
                        this.locatorBarTeammatesOnly = data.config.locatorBarTeammatesOnly;
                        this.nonPlayerGlow = data.config.nonPlayerGlow;
                    } else {
                        // Legacy config (no `config` sub-object): explicitly
                        // reset to defaults — never inherit a previous world's
                        // switch state from the process-wide singleton.
                        this.locatorBarTeammatesOnly = false;
                        this.nonPlayerGlow = false;
                    }
                    // Schema migration: configs written before config_version
                    // existed have a null version array — treat them as legacy
                    // (major 0) and rewrite with the current schema. The 1.1.1
                    // rename (locatorBarHideOtherGlowingTeams →
                    // locatorBarTeammatesOnly) also rewrites the file so the
                    // old key is dropped. Runs before version++ so the
                    // migration write keeps the cache-invalidation semantics
                    // intact (the config did change).
                    int major = (data.configVersion == null || data.configVersion.length == 0)
                            ? 0 : data.configVersion[0];
                    if (major < 1 || migrateLocatorBarSwitchName(rawJson)) {
                        save();
                    }
                    this.version++;
                } else {
                    // The file contains the literal JSON "null" — Gson returns
                    // null without throwing. Treat it like a corrupt file:
                    // reset to defaults, persist the repair, and invalidate
                    // any cached glow state from a previous world.
                    GlowMyTeammates.LOGGER.warn(
                            "Config file contains literal null, resetting to defaults");
                    resetToDefaultsAndPersist();
                }
                GlowMyTeammates.LOGGER.info(
                        "Loaded config: enabled={}, teams={}, locator_bar_teammates_only={}, non_player_glow={}",
                        enabled, enabledTeams, locatorBarTeammatesOnly, nonPlayerGlow);
            } catch (Exception e) {
                GlowMyTeammates.LOGGER.error("Failed to load config, using defaults", e);
                resetToDefaultsAndPersist();
            }
        } else {
            GlowMyTeammates.LOGGER.info(
                    "No config file found at {}, creating default", configPath);
            // Reset to defaults instead of inheriting the previous world's
            // config (a stale singleton across server restarts would
            // otherwise leak teams into this new world's config file).
            this.enabled = true;
            this.enabledTeams.clear();
            this.locatorBarTeammatesOnly = false;
            this.nonPlayerGlow = false;
            this.version++;
            save();
        }
    }

    /**
     * Migrate the pre-1.1.1 key {@code locatorBarHideOtherGlowingTeams} to the
     * renamed {@code locatorBarTeammatesOnly}. Gson silently ignores unknown
     * keys during deserialization, so the old key has to be inspected on the
     * raw JSON text. No-op unless the old key exists and the new one does not;
     * returns whether the file needs a rewrite to drop the old key.
     */
    private boolean migrateLocatorBarSwitchName(String rawJson) {
        try {
            JsonObject root = JsonParser.parseString(rawJson).getAsJsonObject();
            JsonObject configObj = root.getAsJsonObject("config");
            if (configObj == null || !configObj.has("locatorBarHideOtherGlowingTeams")
                    || configObj.has("locatorBarTeammatesOnly")) {
                return false;
            }
            boolean oldValue = configObj.get("locatorBarHideOtherGlowingTeams").getAsBoolean();
            if (oldValue) {
                this.locatorBarTeammatesOnly = true;
            }
            GlowMyTeammates.LOGGER.info(
                    "Migrated config: locatorBarHideOtherGlowingTeams={} → locatorBarTeammatesOnly",
                    oldValue);
            return true;
        } catch (Exception e) {
            // A malformed config is handled by the caller's fallback; here we
            // only fail the migration, not the whole load.
            GlowMyTeammates.LOGGER.warn(
                    "Failed to inspect config for locator-bar switch migration", e);
            return false;
        }
    }

    /**
     * Reset all runtime state to defaults, persist the fallback so a corrupt
     * or literal-null config file is repaired instead of re-reporting the
     * error on every server start, and bump {@code version} so entities that
     * already cached the old state notice the fallback and force a glow
     * resync.
     */
    private void resetToDefaultsAndPersist() {
        this.enabled = true;
        this.enabledTeams.clear();
        this.locatorBarTeammatesOnly = false;
        this.nonPlayerGlow = false;
        save();
        this.version++;
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
        Path tmpPath = configPath.resolveSibling(configPath.getFileName() + ".tmp");
        try {
            Files.createDirectories(configPath.getParent());
            ConfigData data = new ConfigData(enabled, new ArrayList<>(enabledTeams));
            data.configVersion = new int[]{1, 1};
            ConfigSubData subConfig = new ConfigSubData();
            subConfig.locatorBarTeammatesOnly = this.locatorBarTeammatesOnly;
            subConfig.nonPlayerGlow = this.nonPlayerGlow;
            data.config = subConfig;
            try (Writer writer = new OutputStreamWriter(
                    Files.newOutputStream(tmpPath), StandardCharsets.UTF_8)) {
                GSON.toJson(data, writer);
            }
            moveIntoPlace(tmpPath, configPath);
            GlowMyTeammates.LOGGER.info("Saved config to {}", configPath);
            return true;
        } catch (IOException e) {
            GlowMyTeammates.LOGGER.error("Failed to save config", e);
            return false;
        } finally {
            // Best-effort cleanup: a failed save may leave the temp file behind
            // (a successful save already moved it away, so this is a no-op).
            try {
                Files.deleteIfExists(tmpPath);
            } catch (IOException ignored) {
                // Cleanup only — never mask the real failure.
            }
        }
    }

    /**
     * Replace {@code target} with {@code tmpPath}, preferring an atomic move.
     *
     * <p>ATOMIC_MOVE is not supported on every filesystem, and on Windows
     * replacing an <em>existing</em> file this way can throw
     * {@link AccessDeniedException} rather than
     * {@link AtomicMoveNotSupportedException} (the underlying
     * {@code MoveFileExW} reports {@code ERROR_ACCESS_DENIED}). Either way we
     * downgrade to a plain {@code REPLACE_EXISTING} move, retrying briefly in
     * case the target is transiently locked (e.g. by an antivirus scanner or
     * cloud sync).
     */
    private static void moveIntoPlace(Path tmpPath, Path target) throws IOException {
        try {
            Files.move(tmpPath, target, StandardCopyOption.REPLACE_EXISTING,
                    StandardCopyOption.ATOMIC_MOVE);
            return;
        } catch (AtomicMoveNotSupportedException | AccessDeniedException e) {
            // Atomic replace rejected — fall through to a plain move.
        }

        IOException lastFailure = null;
        for (int attempt = 0; attempt < 3; attempt++) {
            try {
                Files.move(tmpPath, target, StandardCopyOption.REPLACE_EXISTING);
                return;
            } catch (AccessDeniedException e) {
                lastFailure = e; // Transient lock — wait and retry.
                try {
                    Thread.sleep(50L << attempt);
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    throw lastFailure;
                }
            }
        }
        throw lastFailure;
    }

    public boolean isEnabled() {
        return enabled;
    }

    /**
     * Whether any team currently has glow enabled. Zero-allocation fast path
     * (unlike {@link #getEnabledTeams()}, which builds a defensive snapshot)
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
     *
     * <p>The epoch is intentionally global: any bump resyncs every glowing
     * entity, which is acceptable on Fabric-scale servers (<100 players) and
     * collapses into one resync round per tick regardless of how many bumps
     * happen inside it (counters are compared with {@code !=}). See AGENTS.md
     * §10.1 before "optimizing" this to per-team granularity.
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
     * Whether a viewer in a glow-enabled team sees only their own teammates
     * on the locator bar. Idempotent — no-op when the value is unchanged, to
     * avoid a spurious version bump and the resulting full-server resync.
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
        // Return a snapshot, not a live view: other mods (e.g. permission
        // plugins reading from async threads) must never hit a CME while
        // iterating the server-thread-owned backing set.
        return Collections.unmodifiableSet(new LinkedHashSet<>(enabledTeams));
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
