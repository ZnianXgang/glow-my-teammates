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
 * {@link #getEnabledTeams()} returns a defensive snapshot, but that copy is
 * taken on the server thread: it protects async <em>iteration</em> from a
 * mutation only if the caller snapshotting it is itself on the server thread
 * (or otherwise externally synchronized). It is not a thread-safe accessor.
 */
public class GlowConfigManager {
    private static final Gson GSON = new GsonBuilder()
            .setPrettyPrinting()
            .disableHtmlEscaping()
            .create();
    private static final String FILENAME = "glow-my-teammates.json";
    private static final GlowConfigManager INSTANCE = new GlowConfigManager();

    /**
     * Feature-switch defaults — the single source of truth for {@code config
     * reset} and every load/repair path that falls back to defaults. Exposed
     * so {@code GlowCommand}'s switch table can carry the reset value without
     * duplicating the literal.
     */
    public static final boolean DEFAULT_LOCATOR_BAR_TEAMMATES_ONLY = false;
    public static final boolean DEFAULT_NON_PLAYER_GLOW = false;

    private boolean enabled = true;
    private final Set<String> enabledTeams = new LinkedHashSet<>();
    private Path configPath;

    /**
     * The running server, set by {@link #loadFromWorld} and cleared on
     * {@code SERVER_STOPPING}. Lets server-thread hooks reach the player list
     * without {@code Entity.getServer()} (removed in 26.1+). May be
     * {@code null} on the client or before the first world load. Volatile:
     * {@code ScoreboardMixin} reads it from the client thread in
     * singleplayer/LAN (DEVELOPMENT.md §8.6).
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
    private boolean locatorBarTeammatesOnly = DEFAULT_LOCATOR_BAR_TEAMMATES_ONLY;

    /**
     * Whether non-player entities (mobs) are eligible for team glow. Default
     * {@code false} — once enabled, mob-dense farms pay per-dirty-packet
     * overhead in {@code ServerEntityMixin#redirectSendData}.
     */
    private boolean nonPlayerGlow = DEFAULT_NON_PLAYER_GLOW;

    /**
     * Set when a config file existed but could not be <em>read</em> (locked,
     * permission denied, unreadable disk). The session then runs on defaults
     * while a perfectly valid file may still be sitting on disk, so every
     * {@link #save()} is refused until the file is readable again — otherwise
     * the first admin command would overwrite that file with defaults and
     * destroy the very data this guard exists to protect.
     */
    private boolean configReadFailed;

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

        // Start every load from defaults, so a previous world's state can never
        // leak into this one — neither when a file is missing and about to be
        // created, nor when one exists but cannot be read. Bump version so
        // entities that cached the old state force a resync.
        resetToDefaults();

        // Nothing in this process has saved yet, so any temp file left next to
        // the config is an orphan from a previous run that died mid-write
        // (SIGKILL, power loss) — that path never reaches save()'s finally
        // block, and save() overwrites the name on its next write anyway. Drop
        // it here once so it cannot linger in the world directory forever.
        clearOrphanedTempFile();

        if (file.exists()) {
            String rawJson;
            try {
                // Split from the parse step on purpose: only a file we could
                // not even read has contents worth protecting. A parse failure
                // is real corruption, which is repaired below.
                rawJson = Files.readString(file.toPath(), StandardCharsets.UTF_8);
            } catch (IOException e) {
                recordReadFailure(e);
                return;
            } catch (SecurityException e) {
                // Same treatment as an I/O failure: the file's contents were
                // never seen, so they are not ours to overwrite. Unreachable
                // without a SecurityManager, kept so no read failure can escape
                // the load and skip the write guard.
                recordReadFailure(e);
                return;
            }
            try {
                applyLoadedConfig(rawJson);
            } catch (RuntimeException e) {
                // Malformed JSON, a wrong value type, or a schema-invalid
                // array: the file is genuinely unusable, so repair it in place
                // (defaults in memory, defaults on disk) instead of reporting
                // the same error on every future start.
                GlowMyTeammates.LOGGER.error(
                        "Config file {} is corrupt, resetting it to defaults", configPath, e);
                resetToDefaultsAndPersist();
                return;
            }
            GlowMyTeammates.LOGGER.info(
                    "Loaded config: enabled={}, teams={}, locator_bar_teammates_only={}, non_player_glow={}",
                    enabled, enabledTeams, locatorBarTeammatesOnly, nonPlayerGlow);
        } else {
            GlowMyTeammates.LOGGER.info(
                    "No config file found at {}, creating default", configPath);
            save();
        }
    }

    /**
     * Best-effort removal of a {@code .tmp} file orphaned by a previous run
     * that died between creating it and moving it into place. Called once at
     * startup, before this process has written anything, so the file cannot
     * belong to a live write. Never fails the load: a temp file we cannot
     * delete is a cosmetic leftover, not a reason to run without config.
     */
    private void clearOrphanedTempFile() {
        try {
            Path tmpPath = tempPath();
            if (Files.deleteIfExists(tmpPath)) {
                GlowMyTeammates.LOGGER.warn(
                        "Removed leftover temp file {} from an interrupted save", tmpPath);
            }
        } catch (IOException | SecurityException e) {
            GlowMyTeammates.LOGGER.warn("Could not remove a leftover config temp file", e);
        }
    }

    /**
     * The sibling {@code .tmp} path that {@link #save()} writes and then moves
     * into place. Single definition so the save and cleanup paths cannot drift
     * apart. Only valid once {@link #loadFromWorld} has set {@code configPath}.
     */
    private Path tempPath() {
        return configPath.resolveSibling(configPath.getFileName() + ".tmp");
    }

    /**
     * The state-resetting part of a load: everything shared by the "no file
     * yet" and "file unreadable" paths. Bumps {@code version} so entities that
     * cached the previous world's state force a resync, which also replaces
     * the single {@code version++} the old single-path loader did at its end.
     */
    private void resetToDefaults() {
        this.enabled = true;
        this.enabledTeams.clear();
        this.locatorBarTeammatesOnly = DEFAULT_LOCATOR_BAR_TEAMMATES_ONLY;
        this.nonPlayerGlow = DEFAULT_NON_PLAYER_GLOW;
        this.configReadFailed = false;
        this.version++;
    }

    /**
     * A config file exists but could not be read. Keep defaults in memory,
     * leave the file exactly as it is, and (via {@code configReadFailed})
     * refuse to write over it for the rest of the session: at this point a
     * transient I/O failure is indistinguishable from corruption, and a
     * rewrite would destroy a valid team list.
     */
    private void recordReadFailure(Exception e) {
        this.configReadFailed = true;
        GlowMyTeammates.LOGGER.error(
                "Failed to read config from {} — this session runs on defaults and will not "
                        + "save, so the file is left untouched; restart the server once it is "
                        + "readable again (delete it only if you want a reset)",
                configPath, e);
    }

    /**
     * Parse raw config text, adopt it as live state, run schema migration and
     * persist a repair when the file is legacy. Throws on unparseable content;
     * the caller repairs the file.
     */
    private void applyLoadedConfig(String rawJson) {
        ConfigData data = GSON.fromJson(rawJson, ConfigData.class);
        if (data == null) {
            // Empty, whitespace-only, or the literal JSON "null" (Gson returns
            // null without throwing) — treat it like a corrupt file: reset to
            // defaults, persist the repair, invalidate caches.
            GlowMyTeammates.LOGGER.warn(
                    "Config file is empty or literal null, resetting to defaults");
            resetToDefaultsAndPersist();
            return;
        }
        this.enabled = data.enabled;
        this.enabledTeams.clear();
        if (data.teams != null) {
            for (String team : data.teams) {
                // Skip null and empty names — an empty string can never match
                // a real team and would otherwise be persisted back on the
                // next save.
                if (team != null && !team.isEmpty()) {
                    this.enabledTeams.add(team);
                }
            }
        }
        if (data.config != null) {
            this.locatorBarTeammatesOnly = data.config.locatorBarTeammatesOnly;
            this.nonPlayerGlow = data.config.nonPlayerGlow;
        } else {
            // Legacy config (no `config` sub-object): explicitly reset to
            // defaults — never inherit a previous world's switch state from
            // the process-wide singleton.
            this.locatorBarTeammatesOnly = DEFAULT_LOCATOR_BAR_TEAMMATES_ONLY;
            this.nonPlayerGlow = DEFAULT_NON_PLAYER_GLOW;
        }
        // Schema migration: a missing version array (legacy, major 0), the
        // pre-1.1.1 key rename, or a literal-null `config` sub-object all
        // rewrite the file with the current schema. No ordering constraint
        // against version++ here — loadFromWorld's opening resetToDefaults()
        // already bumped the counter for this whole load, so the migration
        // write is covered by that bump regardless of when it lands.
        int major = (data.configVersion == null || data.configVersion.length == 0)
                ? 0 : data.configVersion[0];
        // A NEWER major is not legacy and not ours to understand: the arrays we
        // get from an unknown schema may mean something else entirely, and the
        // save below would silently rewrite the file as [1, 1]. This only
        // happens if a file was written by a future release and then downgraded
        // (or edited by hand), so warn and leave what we cannot migrate alone.
        if (major > 1) {
            GlowMyTeammates.LOGGER.warn(
                    "Config file {} declares schema version {} but this build understands "
                            + "only major 1; unknown fields are ignored and any command that "
                            + "saves will rewrite it as [1, 1]",
                    configPath, major);
        }
        boolean migratedSwitchName = migrateLocatorBarSwitchName(rawJson);
        if (major < 1 || migratedSwitchName || data.config == null) {
            if (!save()) {
                GlowMyTeammates.LOGGER.warn(
                        "Config migration/repair failed; the file will be retried on next start");
            }
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
     * Persist the already-reset default state, so a corrupt or empty/literal-null
     * config file is repaired instead of re-reporting the error on every start.
     *
     * <p>Does <em>not</em> bump {@code version}: the state reset it writes out
     * is {@link #resetToDefaults()}'s doing, and that call already bumped the
     * counter as part of the load sequence. Both call sites are inside
     * {@code loadFromWorld} after it, so the "entities that cached the old
     * state must resync" semantics are already satisfied — bumping here would
     * only double-count.
     */
    private void resetToDefaultsAndPersist() {
        this.enabled = true;
        this.enabledTeams.clear();
        this.locatorBarTeammatesOnly = DEFAULT_LOCATOR_BAR_TEAMMATES_ONLY;
        this.nonPlayerGlow = DEFAULT_NON_PLAYER_GLOW;
        save();
    }

    /**
     * Save current config to file.
     *
     * @return {@code true} if the config was persisted successfully,
     *         {@code false} if the file could not be written (logged) or the
     *         session is blocked from writing because the load failed.
     */
    public boolean save() {
        if (configPath == null) {
            GlowMyTeammates.LOGGER.warn("Cannot save config: no world path set");
            return false;
        }
        if (configReadFailed) {
            // Refuse to overwrite a file we could not read: the in-memory state
            // is defaults, not what the file holds, so writing it would turn a
            // transient read failure into permanent data loss. Commands surface
            // this as a failed-save message and roll their change back.
            GlowMyTeammates.LOGGER.warn(
                    "Cannot save config to {}: it could not be read at startup, so this session "
                            + "will not overwrite it. Fix the file and restart the server.",
                    configPath);
            return false;
        }
        Path tmpPath = tempPath();
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
     * global — see DEVELOPMENT.md §9.1 before "optimizing" to per-team granularity.
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
     * {@code GlowCommand#addTeam} does (DEVELOPMENT.md §4.2).
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
        // Snapshot, not a live view — protects a caller that iterates the result
        // from a concurrent mutation of the server-thread-owned set. Callers
        // that are not on the server thread must still synchronize externally:
        // taking this copy is itself a read of a non-thread-safe collection.
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
        boolean locatorBarTeammatesOnly = DEFAULT_LOCATOR_BAR_TEAMMATES_ONLY;
        boolean nonPlayerGlow = DEFAULT_NON_PLAYER_GLOW;

        ConfigSubData() {}
    }
}
