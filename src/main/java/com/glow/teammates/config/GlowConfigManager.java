package com.glow.teammates.config;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonElement;
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
 * thread (commands, ServerEntity ticks, Scoreboard events) and must only be
 * written from it; reading live state from async contexts is unsupported.
 * Only {@link #getEnabledTeams()} returns a defensive snapshot safe to
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
     * The running server, set by {@link #loadFromWorld} and cleared on
     * {@code SERVER_STOPPING}. Lets server-thread hooks reach the player list
     * without {@code Entity.getServer()} (removed in 26.1+). May be
     * {@code null} on the client or before the first world load. Volatile:
     * {@code ScoreboardMixin} reads it from the client thread in
     * singleplayer/LAN (AGENTS.md §8.7).
     */
    private volatile MinecraftServer server;

    /**
     * Monotonically increasing counter bumped on every state change — the
     * mixin uses it to detect when a full resync is needed.
     */
    private long version;

    /**
     * Bumped on every team membership change; lets the mixin detect
     * viewer-side team changes and force a glow resync for affected viewers.
     */
    private long syncEpoch;

    /**
     * Whether a viewer in a glow-enabled team sees only their own teammates
     * on the locator bar. Default {@code false}.
     */
    private boolean locatorBarTeammatesOnly;

    /**
     * Whether non-player entities (mobs) are eligible for team glow. Default
     * {@code false} — once enabled, mob-dense farms pay per-dirty-packet
     * overhead in {@code ServerEntityMixin#redirectSendData}.
     */
    private boolean nonPlayerGlow;

    public static GlowConfigManager getInstance() {
        return INSTANCE;
    }

    /**
     * Load config from the world save directory. Called when the server starts.
     */
    public void loadFromWorld(MinecraftServer server) {
        this.server = server;
        // LevelResource.ROOT resolves to a "." element (./world/.), so the
        // bare resolve would log a redundant separator — normalize it (the
        // normalized path targets the same file; only the log text changes).
        Path worldPath = server.getWorldPath(LevelResource.ROOT);
        this.configPath = worldPath.resolve(FILENAME).normalize();
        File file = configPath.toFile();

        if (file.exists()) {
            try {
                // Read the raw text once: the schema migration needs to
                // inspect the old key name that Gson would silently drop.
                String rawJson = Files.readString(file.toPath(), StandardCharsets.UTF_8);
                ConfigData data = GSON.fromJson(rawJson, ConfigData.class);
                if (data != null) {
                    this.enabled = data.enabled;
                    this.enabledTeams.clear();
                    if (data.teams != null) {
                        for (String team : data.teams) {
                            // Skip null and empty names — an empty string can
                            // never match a real team and would otherwise be
                            // persisted back on the next save.
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
                    // Schema migration: a missing version array (legacy, major 0),
                    // the pre-1.1.1 key rename, or a literal-null `config`
                    // sub-object all rewrite the file with the current schema.
                    // Runs before version++ so the migration write keeps the
                    // cache-invalidation semantics intact.
                    int major = (data.configVersion == null || data.configVersion.length == 0)
                            ? 0 : data.configVersion[0];
                    boolean migratedSwitchName = migrateLocatorBarSwitchName(rawJson);
                    if (major < 1 || migratedSwitchName || data.config == null) {
                        if (!save()) {
                            GlowMyTeammates.LOGGER.warn(
                                    "Config migration/repair failed; the file will be retried on next start");
                        }
                    }
                    this.version++;
                } else {
                    // The file contains the literal JSON "null" (Gson returns
                    // null without throwing) — treat it like a corrupt file:
                    // reset to defaults, persist the repair, invalidate caches.
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
            save();
            this.version++;
        }
    }

    /**
     * Migrate the pre-1.1.1 key {@code locatorBarHideOtherGlowingTeams} to
     * {@code locatorBarTeammatesOnly}. Gson silently ignores unknown keys, so
     * the old key is inspected on the raw JSON text. Returns whether the file
     * needs a rewrite to drop the old key.
     */
    private boolean migrateLocatorBarSwitchName(String rawJson) {
        try {
            JsonObject root = JsonParser.parseString(rawJson).getAsJsonObject();
            JsonElement configElement = root.get("config");
            if (!(configElement instanceof JsonObject configObj)) {
                return false;
            }
            if (!configObj.has("locatorBarHideOtherGlowingTeams")) {
                return false;
            }
            boolean oldValue = configObj.get("locatorBarHideOtherGlowingTeams").getAsBoolean();
            if (oldValue && !configObj.has("locatorBarTeammatesOnly")) {
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
     * Reset to defaults and persist, so a corrupt or literal-null config file
     * is repaired instead of re-reporting the error on every start; then bump
     * {@code version} so entities that cached the old state force a resync.
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
     * replacing an <em>existing</em> file can throw {@link AccessDeniedException}
     * rather than {@link AtomicMoveNotSupportedException}. Either way we
     * downgrade to a plain {@code REPLACE_EXISTING} move, retrying briefly in
     * case the target is transiently locked (antivirus, cloud sync).
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
     * The running server, or {@code null} on the client / before the first
     * world load / after {@link #clearServer()}. Server-thread only, like
     * every other getter.
     */
    public MinecraftServer getServer() {
        return server;
    }

    public void clearServer() {
        this.server = null;
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
     * Call when a player joins or leaves any team, so the mixin can force a
     * glow-state resync for all viewers of glowing entities. Intentionally
     * global — see AGENTS.md §10.1 before "optimizing" to per-team granularity.
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

    /**
     * Deliberately <em>not</em> idempotent (unlike the other setters): the
     * caller must guard against already-enabled teams before calling, which
     * {@code GlowCommand#addTeam} does (AGENTS.md §4.2).
     */
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
        // Snapshot, not a live view: async readers (e.g. permission plugins)
        // must never hit a CME while iterating the server-thread-owned set.
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
