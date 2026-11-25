package me.znianxgang.glow.config;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import me.znianxgang.glow.GlowMyTeammates;
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
 * Process-wide runtime state and its per-world JSON persistence.
 *
 * <p>All mutable state is owned by the server thread (commands, entity ticks,
 * scoreboard hooks) and must only be written from it. {@link #getEnabledTeams()}
 * returns a snapshot, but taking that snapshot is itself a read of a
 * server-thread-owned collection: this is not a thread-safe accessor.
 */
public class GlowConfigManager {
    private static final Gson GSON = new GsonBuilder()
            .setPrettyPrinting()
            .disableHtmlEscaping()
            .create();
    private static final String FILENAME = "glow-my-teammates.json";
    private static final GlowConfigManager INSTANCE = new GlowConfigManager();

    /** Switch defaults: single source of truth for {@code config reset} and every fallback path. */
    public static final boolean DEFAULT_LOCATOR_BAR_TEAMMATES_ONLY = false;
    public static final boolean DEFAULT_NON_PLAYER_GLOW = false;

    private boolean enabled = true;
    private final Set<String> enabledTeams = new LinkedHashSet<>();
    private Path configPath;

    /**
     * The running server, set by {@link #loadFromWorld} and cleared on
     * {@code SERVER_STOPPING}. Volatile because the scoreboard hook consults it
     * from the client thread in singleplayer/LAN.
     */
    private volatile MinecraftServer server;

    /** Bumped on every state change; the mixin uses it to invalidate cached glow state. */
    private long version;

    /** Bumped on every membership change in a glow team; invalidates viewer-side caches. */
    private long syncEpoch;

    /** A viewer in a glow-enabled team sees only their own team on the locator bar. */
    private boolean locatorBarTeammatesOnly = DEFAULT_LOCATOR_BAR_TEAMMATES_ONLY;

    /** Non-player entities are eligible for team glow. Off: mob farms would pay for it. */
    private boolean nonPlayerGlow = DEFAULT_NON_PLAYER_GLOW;

    /**
     * The file existed but could not be read. The session runs on defaults, and
     * saving must stay blocked — otherwise the first command would overwrite a
     * file that may still be perfectly valid.
     */
    private boolean configReadFailed;

    public static GlowConfigManager getInstance() {
        return INSTANCE;
    }

    /** Load from the world directory. Also the only place that sets {@code configPath}. */
    public void loadFromWorld(MinecraftServer server) {
        this.server = server;
        // LevelResource.ROOT resolves to a "." element, which would show up as a
        // duplicate separator in the logged path.
        Path worldPath = server.getWorldPath(LevelResource.ROOT);
        this.configPath = worldPath.resolve(FILENAME).normalize();
        File file = configPath.toFile();

        // Start from defaults so a previous world's state cannot leak in, and
        // bump version so entities that cached the old state resync.
        resetToDefaults();

        // Nothing has saved in this process yet, so any temp file next to the
        // config is an orphan from a run that died mid-write.
        clearOrphanedTempFile();

        if (file.exists()) {
            String rawJson;
            try {
                // Read and parse are split on purpose: only a file we could not
                // read has contents worth protecting, while a parse failure is
                // real corruption and gets repaired below.
                rawJson = Files.readString(file.toPath(), StandardCharsets.UTF_8);
            } catch (IOException e) {
                recordReadFailure(e);
                return;
            } catch (SecurityException e) {
                recordReadFailure(e);
                return;
            }
            try {
                applyLoadedConfig(rawJson);
            } catch (RuntimeException e) {
                // Unparseable or schema-invalid content: repair in place rather
                // than re-report the same error on every start.
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
     * Best-effort removal of a {@code .tmp} file orphaned by an interrupted
     * save. Never fails the load: a leftover file we cannot delete is cosmetic.
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

    /** Single definition so the save and cleanup paths cannot drift apart. */
    private Path tempPath() {
        return configPath.resolveSibling(configPath.getFileName() + ".tmp");
    }

    /**
     * State reset shared by the "no file yet" and "file unreadable" paths. Its
     * {@code version} bump covers the whole load, which is why no later step in
     * the load sequence bumps again.
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
     * A transient I/O failure is indistinguishable from corruption, so the file
     * is left untouched and every later {@link #save()} is refused.
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
     * Adopt the parsed config as live state and run schema migration, rewriting
     * the file when it is legacy. Throws on unusable content; the caller
     * repairs the file.
     */
    private void applyLoadedConfig(String rawJson) {
        ConfigData data = GSON.fromJson(rawJson, ConfigData.class);
        if (data == null) {
            // Empty, whitespace-only, or a literal JSON null.
            GlowMyTeammates.LOGGER.warn(
                    "Config file is empty or literal null, resetting to defaults");
            resetToDefaultsAndPersist();
            return;
        }
        this.enabled = data.enabled;
        this.enabledTeams.clear();
        if (data.teams != null) {
            for (String team : data.teams) {
                // An empty name can never match a real team and would be saved back.
                if (team != null && !team.isEmpty()) {
                    this.enabledTeams.add(team);
                }
            }
        }
        if (data.config != null) {
            this.locatorBarTeammatesOnly = data.config.locatorBarTeammatesOnly;
            this.nonPlayerGlow = data.config.nonPlayerGlow;
        } else {
            // Legacy config: reset the switches instead of inheriting the
            // previous world's values from the process-wide singleton.
            this.locatorBarTeammatesOnly = DEFAULT_LOCATOR_BAR_TEAMMATES_ONLY;
            this.nonPlayerGlow = DEFAULT_NON_PLAYER_GLOW;
        }
        // A missing version array (legacy), the pre-1.1.1 key rename, or a
        // literal-null `config` all rewrite the file with the current schema.
        int major = (data.configVersion == null || data.configVersion.length == 0)
                ? 0 : data.configVersion[0];
        // A NEWER major is not something this build understands; saving would
        // silently rewrite it as [1, 1], so leave it alone but say so.
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
     * Migrate the pre-1.1.1 key {@code locatorBarHideOtherGlowingTeams}. Gson
     * ignores unknown keys, so the old key is read from the raw JSON text.
     * Returns whether the file needs a rewrite.
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
            // Only the migration fails here; the caller handles a malformed file.
            GlowMyTeammates.LOGGER.warn(
                    "Failed to inspect config for locator-bar switch migration", e);
            return false;
        }
    }

    /**
     * Persist the already-reset default state, repairing a corrupt or empty
     * file instead of re-reporting it on every start. Deliberately does not
     * bump {@code version}: {@link #resetToDefaults()} already did, and both
     * call sites run after it.
     */
    private void resetToDefaultsAndPersist() {
        this.enabled = true;
        this.enabledTeams.clear();
        this.locatorBarTeammatesOnly = DEFAULT_LOCATOR_BAR_TEAMMATES_ONLY;
        this.nonPlayerGlow = DEFAULT_NON_PLAYER_GLOW;
        save();
    }

    /**
     * @return {@code true} when the config was persisted; {@code false} when
     *         the write failed or this session is blocked from writing.
     */
    public boolean save() {
        if (configPath == null) {
            GlowMyTeammates.LOGGER.warn("Cannot save config: no world path set");
            return false;
        }
        if (configReadFailed) {
            // The in-memory state is defaults, not what the file holds — writing
            // it would turn a transient read failure into data loss.
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
            // A successful save already moved the file away, so this only
            // cleans up after a failure.
            try {
                Files.deleteIfExists(tmpPath);
            } catch (IOException ignored) {
                // Cleanup only — never mask the real failure.
            }
        }
    }

    /**
     * Replace {@code target} with {@code tmpPath}, preferring an atomic move.
     * On Windows replacing an existing file can throw {@link AccessDeniedException}
     * instead of {@link AtomicMoveNotSupportedException}, so both fall back to
     * a plain move with a short retry for transient locks (antivirus, sync).
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

    /** The running server, or {@code null} on the client / outside a world. */
    public MinecraftServer getServer() {
        return server;
    }

    public void clearServer() {
        this.server = null;
    }

    /** Zero-allocation fast path used by the per-packet redirect when no team is configured. */
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
     * Call on any membership change in a glow team so viewers resync. Global on
     * purpose: the teammate predicate is evaluated against the <em>viewer's</em>
     * team, so per-team granularity would be a correctness liability.
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

    /** Not idempotent: callers must reject an already-enabled team first. */
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

    /** Idempotent — an unchanged value must not bump {@code version}. */
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

    /** Idempotent — an unchanged value must not bump {@code version}. */
    public void setNonPlayerGlow(boolean nonPlayerGlow) {
        if (this.nonPlayerGlow == nonPlayerGlow) {
            return;
        }
        this.nonPlayerGlow = nonPlayerGlow;
        this.version++;
    }

    /** Snapshot, not a live view: the caller may iterate it while the set changes. */
    public Set<String> getEnabledTeams() {
        return Collections.unmodifiableSet(new LinkedHashSet<>(enabledTeams));
    }

    @SuppressWarnings("unused")
    public static class ConfigData {
        boolean enabled = true;
        List<String> teams = new ArrayList<>();
        /** Disk schema version; {@code null} means a legacy file, migrated on load. */
        int[] configVersion;
        /** Feature switches; {@code null} means a legacy file, rewritten on load. */
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
