package org.herolias.plugin.config;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.hypixel.hytale.logger.HytaleLogger;

import java.io.File;
import java.io.FileWriter;
import java.io.IOException;

/**
 * Manages loading and saving of the plugin configuration.
 */
public class ConfigManager {

    private final HytaleLogger logger = HytaleLogger.forEnclosingClass();
    private final Gson gson = new GsonBuilder().setPrettyPrinting().create();
    private final File configFile;
    private EnchantingConfig config;

    public ConfigManager(File dataFolder) {
        if (!dataFolder.exists()) {
            dataFolder.mkdirs();
        }
        this.configFile = new File(dataFolder, "simple_enchanting_config.json");
        this.config = new EnchantingConfig();
    }

    public void loadConfig() {
        EnchantingConfig defaults = EnchantingConfig.createDefault();
        this.config = SmartConfigManager.loadAndMerge(this.configFile, EnchantingConfig.class, defaults);

        if (this.config == null) {
            this.config = defaults;
            logger.atWarning().log("Config failed to load even after smart merge fallback. Using defaults.");
        }

        this.config.init();

        // Apply dynamic effect overrides for Burn and Freeze
        org.herolias.plugin.enchantment.EnchantmentDynamicEffects.applyOverrides(this.config);

        logger.atInfo().log("Configuration loaded.");
    }

    public void saveConfig() {
        try (FileWriter writer = new FileWriter(configFile)) {
            gson.toJson(config, writer);
            logger.atInfo().log("Configuration saved to " + configFile.getAbsolutePath());
        } catch (IOException e) {
            logger.atSevere().log("Failed to save configuration: " + e.getMessage());
            e.printStackTrace();
        }
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
        return config.disabledEnchantments.getOrDefault(enchantmentId, false);
    }

    public File getConfigDirectory() {
        return configFile.getParentFile();
    }

    public File getConfigFile() {
        return configFile;
    }
}
