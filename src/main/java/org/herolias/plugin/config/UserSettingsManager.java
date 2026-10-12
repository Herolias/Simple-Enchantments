package org.herolias.plugin.config;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.reflect.TypeToken;
import com.hypixel.hytale.logger.HytaleLogger;

import java.io.File;
import java.io.Reader;
import java.lang.reflect.Type;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.HashMap;
import java.util.Map;
import java.util.TreeMap;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

/**
 * Manages loading, saving, and retrieving player-specific user settings.
 * <p>
 * Writes are debounced: every change schedules a single whole-file rewrite
 * {@value #SAVE_DELAY_MS} ms after the last change on a mod-owned single-thread
 * executor, so world threads never block on disk I/O. Call {@link #flush()} /
 * {@link #shutdown()} from the plugin's shutdown to persist pending changes.
 */
public class UserSettingsManager {

    public static final String SETTINGS_FILE_NAME = "simple_enchantments_user_config.json";
    private static final long SAVE_DELAY_MS = 2000L;

    private final HytaleLogger logger = HytaleLogger.forEnclosingClass();
    private final Gson gson = new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create();
    private final File settingsFile;
    private final ConfigManager configManager;
    private volatile Map<UUID, UserSettings> userSettingsMap = new ConcurrentHashMap<>();

    private final ScheduledExecutorService saveExecutor = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "SimpleEnchantments-UserSettings-Save");
        t.setDaemon(true);
        return t;
    });
    private final Object saveLock = new Object();
    /** Pending debounced save; guarded by {@link #saveLock}. */
    private ScheduledFuture<?> pendingSave;

    /** True if the settings file was unreadable and we started from an empty map. */
    private volatile boolean fallbackSettings = false;

    public UserSettingsManager(File dataFolder, ConfigManager configManager) {
        if (!dataFolder.exists() && !dataFolder.mkdirs()) {
            logger.atWarning().log("Could not create config directory %s", dataFolder.getAbsolutePath());
        }
        this.settingsFile = new File(dataFolder, SETTINGS_FILE_NAME);
        this.configManager = configManager;
    }

    // ───────────────────── Load ─────────────────────

    /**
     * Loads the settings file. Never throws: a corrupt file is backed up to
     * {@code <name>.corrupt-<timestamp>.json} and the manager continues with an
     * empty map (the backup makes later overwrites safe).
     */
    public void loadSettings() {
        if (!settingsFile.exists()) {
            logger.atInfo().log("No %s found. Creating new empty setup.", SETTINGS_FILE_NAME);
            saveSettingsNow();
            return;
        }

        try (Reader reader = Files.newBufferedReader(settingsFile.toPath(), StandardCharsets.UTF_8)) {
            Type type = new TypeToken<HashMap<UUID, UserSettings>>() {
            }.getType();
            Map<UUID, UserSettings> loaded = gson.fromJson(reader, type);
            Map<UUID, UserSettings> map = new ConcurrentHashMap<>();
            if (loaded != null) {
                for (Map.Entry<UUID, UserSettings> entry : loaded.entrySet()) {
                    if (entry.getKey() != null && entry.getValue() != null) {
                        map.put(entry.getKey(), entry.getValue());
                    }
                }
            }
            this.userSettingsMap = map;
            this.fallbackSettings = false;
            logger.atInfo().log("User settings loaded (%d players).", map.size());
        } catch (Exception e) {
            // IOException, JsonSyntaxException, IllegalArgumentException (bad UUID), ...
            File backup = SmartConfigManager.backupCorruptFile(settingsFile);
            this.userSettingsMap = new ConcurrentHashMap<>();
            this.fallbackSettings = true;
            logger.atSevere().withCause(e).log(
                    "Failed to load user settings from %s%s. Continuing with empty settings; the next save will replace the corrupt file.",
                    settingsFile.getAbsolutePath(),
                    backup != null ? " (backed up to " + backup.getName() + ")" : "");
        }
    }

    /**
     * @return true if the settings file could not be read at startup and the
     *         manager started from an empty map (a backup was written).
     */
    public boolean isFallbackSettings() {
        return fallbackSettings;
    }

    // ───────────────────── Save (debounced) ─────────────────────

    /**
     * Requests a save. The actual write happens on the save executor
     * {@value #SAVE_DELAY_MS} ms after the last request.
     */
    public void saveSettings() {
        scheduleSave();
    }

    private void scheduleSave() {
        synchronized (saveLock) {
            if (saveExecutor.isShutdown()) {
                // Late change during shutdown: write synchronously so nothing is lost
                saveSettingsNow();
                return;
            }
            if (pendingSave != null) {
                pendingSave.cancel(false);
            }
            pendingSave = saveExecutor.schedule(this::runScheduledSave, SAVE_DELAY_MS, TimeUnit.MILLISECONDS);
        }
    }

    private void runScheduledSave() {
        synchronized (saveLock) {
            pendingSave = null;
        }
        saveSettingsNow();
    }

    /**
     * Writes any pending change to disk synchronously.
     */
    public void flush() {
        ScheduledFuture<?> pending;
        synchronized (saveLock) {
            pending = pendingSave;
            pendingSave = null;
        }
        if (pending == null) {
            return;
        }
        if (pending.cancel(false)) {
            // We own the save now
            saveSettingsNow();
        } else {
            // Already running on the executor: wait for it
            try {
                pending.get(10, TimeUnit.SECONDS);
            } catch (Exception e) {
                logger.atWarning().withCause(e).log("Timed out waiting for the pending user settings save");
            }
        }
    }

    /**
     * Flushes pending changes and stops the save executor. Call from plugin
     * shutdown.
     */
    public void shutdown() {
        flush();
        saveExecutor.shutdown();
        try {
            if (!saveExecutor.awaitTermination(5, TimeUnit.SECONDS)) {
                saveExecutor.shutdownNow();
            }
        } catch (InterruptedException e) {
            saveExecutor.shutdownNow();
            Thread.currentThread().interrupt();
        }
    }

    /**
     * Writes the settings file immediately on the calling thread.
     */
    public synchronized void saveSettingsNow() {
        try {
            // TreeMap: stable key order in the file
            AtomicJsonWriter.write(settingsFile, new TreeMap<>(userSettingsMap), gson);
            this.fallbackSettings = false;
            logger.atInfo().log("User settings saved to %s", settingsFile.getAbsolutePath());
        } catch (Exception e) {
            logger.atSevere().withCause(e).log("Failed to save user settings to %s", settingsFile.getAbsolutePath());
        }
    }

    // ───────────────────── Accessors ─────────────────────

    /**
     * Retrieves the player's settings, or creates a new empty one if absent.
     */
    public UserSettings getSettings(UUID playerUuid) {
        return userSettingsMap.computeIfAbsent(playerUuid, k -> new UserSettings());
    }

    /**
     * Determines whether enchantment glow should be enabled for a given player.
     * Falls back to server config if player has not specified a preference.
     */
    public boolean getEnableEnchantmentGlow(UUID playerUuid) {
        UserSettings settings = getSettings(playerUuid);
        if (settings.enableEnchantmentGlow != null) {
            return settings.enableEnchantmentGlow;
        }
        return configManager.getConfig().enableEnchantmentGlow;
    }

    /**
     * Updates and (debounced) saves the glow preference for a player.
     */
    public void setEnableEnchantmentGlow(UUID playerUuid, Boolean enabled) {
        UserSettings settings = getSettings(playerUuid);
        settings.enableEnchantmentGlow = enabled;
        saveSettings();
    }

    /**
     * Gets the language preference for a player.
     */
    public String getLanguage(UUID playerUuid) {
        UserSettings settings = getSettings(playerUuid);
        if (settings.language != null) {
            return settings.language;
        }
        return "default";
    }

    /**
     * Updates and (debounced) saves the language preference for a player.
     */
    public void setLanguage(UUID playerUuid, String language) {
        UserSettings settings = getSettings(playerUuid);
        settings.language = language;
        saveSettings();
    }

    /**
     * Determines whether the player has seen the welcome greeting.
     */
    public boolean hasSeenGreeting(UUID playerUuid) {
        UserSettings settings = getSettings(playerUuid);
        if (settings.hasSeenGreeting != null) {
            return settings.hasSeenGreeting;
        }
        return false;
    }

    /**
     * Updates and (debounced) saves whether the player has seen the welcome
     * greeting.
     */
    public void setHasSeenGreeting(UUID playerUuid, Boolean seen) {
        UserSettings settings = getSettings(playerUuid);
        settings.hasSeenGreeting = seen;
        saveSettings();
    }
}
