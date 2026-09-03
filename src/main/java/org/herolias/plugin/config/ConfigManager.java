package org.herolias.plugin.config;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.hypixel.hytale.logger.HytaleLogger;

import javax.annotation.Nonnull;
import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;

/**
 * Manages loading and saving of the plugin configuration.
 * <p>
 * The manager works from whatever directory it is given. Use
 * {@link #migrateLegacyConfigDirectories(File)} once at startup to move files
 * from the older locations ({@code config/} and
 * {@code mods/Simple_Enchantments_Config}) into that directory.
 */
public class ConfigManager {

    public static final String CONFIG_FILE_NAME = "simple_enchanting_config.json";

    /** Directory used since 1.x; still the default (see report on data-directory). */
    public static final File DEFAULT_CONFIG_DIRECTORY = new File("mods/Simple_Enchantments_Config");
    /** Pre-1.x directory. */
    public static final File LEGACY_CONFIG_DIRECTORY = new File("config");

    /** Every file this mod keeps in its config directory. */
    static final String[] MANAGED_FILES = {
            CONFIG_FILE_NAME,
            "." + CONFIG_FILE_NAME + ".snapshot",
            CONFIG_FILE_NAME + ".old",
            UserSettingsManager.SETTINGS_FILE_NAME,
            "simple_enchanting_custom_items.json",
            ".simple_enchanting_custom_items.json.snapshot",
            "simple_enchanting_custom_items.json.old"
    };

    private static final HytaleLogger LOGGER = HytaleLogger.forEnclosingClass();

    private final HytaleLogger logger = HytaleLogger.forEnclosingClass();
    private final Gson gson = new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create();
    private final File configFile;
    private volatile EnchantingConfig config;
    /** True while the in-memory config is the built-in default because the file was unreadable. */
    private volatile boolean fallbackConfig = false;

    public ConfigManager(File dataFolder) {
        if (!dataFolder.exists() && !dataFolder.mkdirs()) {
            logger.atWarning().log("Could not create config directory %s", dataFolder.getAbsolutePath());
        }
        this.configFile = new File(dataFolder, CONFIG_FILE_NAME);
        this.config = new EnchantingConfig();
    }

    // ───────────────────── Directory migration ─────────────────────

    /**
     * Moves config files from the older locations into {@code targetDir} on first
     * run. Files that already exist in the target are left alone (the old copy is
     * deleted). Old directories are removed if they end up empty. Never throws.
     */
    public static void migrateLegacyConfigDirectories(@Nonnull File targetDir) {
        migrateDirectory(LEGACY_CONFIG_DIRECTORY, targetDir);
        migrateDirectory(DEFAULT_CONFIG_DIRECTORY, targetDir);
    }

    private static void migrateDirectory(File oldDir, File targetDir) {
        try {
            if (!oldDir.isDirectory()) {
                return;
            }
            if (oldDir.getCanonicalFile().equals(targetDir.getCanonicalFile())) {
                return;
            }

            for (String fileName : MANAGED_FILES) {
                File oldFile = new File(oldDir, fileName);
                if (!oldFile.exists()) {
                    continue;
                }
                if (!targetDir.exists() && !targetDir.mkdirs()) {
                    LOGGER.atWarning().log("Could not create config directory %s; skipping migration from %s",
                            targetDir.getAbsolutePath(), oldDir.getPath());
                    return;
                }
                File newFile = new File(targetDir, fileName);
                if (newFile.exists()) {
                    if (oldFile.delete()) {
                        LOGGER.atInfo().log("Removed old %s from %s (already present in %s).", fileName,
                                oldDir.getPath(), targetDir.getPath());
                    }
                    continue;
                }
                try {
                    Files.move(oldFile.toPath(), newFile.toPath(), StandardCopyOption.ATOMIC_MOVE);
                } catch (IOException atomicFailure) {
                    Files.copy(oldFile.toPath(), newFile.toPath(), StandardCopyOption.REPLACE_EXISTING);
                    Files.deleteIfExists(oldFile.toPath());
                }
                LOGGER.atInfo().log("Migrated %s from %s to %s.", fileName, oldDir.getPath(), targetDir.getPath());
            }

            // Only succeeds if the directory is now empty
            if (oldDir.delete()) {
                LOGGER.atInfo().log("Deleted old empty config directory %s.", oldDir.getPath());
            }
        } catch (Exception e) {
            LOGGER.atWarning().withCause(e).log("Config directory migration from %s to %s failed", oldDir.getPath(),
                    targetDir.getPath());
        }
    }

    // ───────────────────── Load / save ─────────────────────

    /**
     * Loads (and smart-merges) the configuration. Never throws: on any failure the
     * built-in defaults are used and {@link #isFallbackConfig()} becomes true.
     */
    public void loadConfig() {
        EnchantingConfig defaults = EnchantingConfig.createDefault();

        try {
            SmartConfigManager.LoadResult<EnchantingConfig> result = SmartConfigManager.load(this.configFile,
                    EnchantingConfig.class, defaults);
            this.config = result.config;
            this.fallbackConfig = result.fallback;
        } catch (Exception e) {
            logger.atSevere().withCause(e).log("Config failed to load. Using built-in defaults.");
            this.config = defaults;
            this.fallbackConfig = true;
        }

        try {
            this.config.init();
        } catch (Exception e) {
            logger.atSevere().withCause(e).log("Config post-processing failed; continuing with the values as loaded.");
        }

        // Apply dynamic effect overrides for Burn, Freeze and Poison
        try {
            org.herolias.plugin.enchantment.EnchantmentDynamicEffects.applyOverrides(this.config);
        } catch (Exception e) {
            logger.atSevere().withCause(e).log("Failed to apply dynamic effect overrides from the config.");
        }

        if (this.fallbackConfig) {
            logger.atSevere().log(
                    "Running with the built-in default configuration because %s could not be read. Configuration changes will NOT be saved until the file is repaired or deleted.",
                    configFile.getAbsolutePath());
        } else {
            logger.atInfo().log("Configuration loaded from %s", configFile.getAbsolutePath());
        }
    }

    /**
     * Saves the configuration. Refuses (and returns false) while running on the
     * fallback defaults so a corrupt user file is never silently overwritten.
     *
     * @return true if the file was written
     */
    public boolean saveConfig() {
        if (this.fallbackConfig) {
            logger.atWarning().log(
                    "Refusing to save configuration: the in-memory config is the built-in fallback because %s could not be read. Repair or delete the file and restart.",
                    configFile.getAbsolutePath());
            return false;
        }
        try {
            AtomicJsonWriter.write(configFile, config, gson);
            logger.atInfo().log("Configuration saved to %s", configFile.getAbsolutePath());
            return true;
        } catch (Exception e) {
            logger.atSevere().withCause(e).log("Failed to save configuration to %s", configFile.getAbsolutePath());
            return false;
        }
    }

    /**
     * @return true if the in-memory config is the built-in default because the
     *         user's file could not be parsed. UIs should show a warning and
     *         {@link #saveConfig()} will refuse to write.
     */
    public boolean isFallbackConfig() {
        return fallbackConfig;
    }

    public EnchantingConfig getConfig() {
        return config;
    }

    /**
     * Checks if an enchantment is disabled in the config.
     *
     * @param enchantmentId The enchantment ID (e.g., "sharpness")
     * @return True if the enchantment is disabled, false if enabled
     */
    public boolean isEnchantmentDisabled(String enchantmentId) {
        return config.disabledEnchantments != null && config.disabledEnchantments.getOrDefault(enchantmentId, false);
    }

    /**
     * Atomically replaces the live configuration object. Callers build the new
     * config from a working copy and swap it in, so readers on other world
     * threads never observe a half-updated object.
     */
    public void setConfig(@javax.annotation.Nonnull EnchantingConfig config) {
        this.config = java.util.Objects.requireNonNull(config, "config");
    }

    public File getConfigDirectory() {
        return configFile.getParentFile();
    }

    public File getConfigFile() {
        return configFile;
    }
}
