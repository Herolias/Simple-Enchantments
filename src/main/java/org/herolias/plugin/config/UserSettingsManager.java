package org.herolias.plugin.config;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.reflect.TypeToken;
import com.hypixel.hytale.logger.HytaleLogger;

import java.io.File;
import java.io.FileReader;
import java.io.FileWriter;
import java.io.IOException;
import java.lang.reflect.Type;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Manages loading, saving, and retrieving player-specific user settings.
 */
public class UserSettingsManager {

    private final HytaleLogger logger = HytaleLogger.forEnclosingClass();
    private final Gson gson = new GsonBuilder().setPrettyPrinting().create();
    private final File settingsFile;
    private final ConfigManager configManager;
    private Map<UUID, UserSettings> userSettingsMap = new ConcurrentHashMap<>();

    public UserSettingsManager(File dataFolder, ConfigManager configManager) {
        if (!dataFolder.exists()) {
            dataFolder.mkdirs();
        }
        this.settingsFile = new File(dataFolder, "simple_enchantments_user_config.json");
        this.configManager = configManager;
    }

    public void loadSettings() {
        if (!settingsFile.exists()) {
            logger.atInfo().log("No simple_enchantments_user_config.json found. Creating new empty setup.");
            saveSettings();
            return;
        }

        try (FileReader reader = new FileReader(settingsFile)) {
            Type type = new TypeToken<ConcurrentHashMap<UUID, UserSettings>>(){}.getType();
            Map<UUID, UserSettings> loaded = gson.fromJson(reader, type);
            if (loaded != null) {
                userSettingsMap = new ConcurrentHashMap<>(loaded);
            }
            logger.atInfo().log("User settings loaded.");
        } catch (IOException e) {
            logger.atSevere().log("Failed to load user settings: " + e.getMessage());
            e.printStackTrace();
        }
    }

    public void saveSettings() {
        try (FileWriter writer = new FileWriter(settingsFile)) {
            gson.toJson(userSettingsMap, writer);
            logger.atInfo().log("User settings saved to " + settingsFile.getAbsolutePath());
        } catch (IOException e) {
            logger.atSevere().log("Failed to save user settings: " + e.getMessage());
            e.printStackTrace();
        }
    }

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
     * Determines whether the enchantment banner should be shown for a given player.
     * Falls back to server config if player has not specified a preference.
     */
    public boolean getShowEnchantmentBanner(UUID playerUuid) {
        UserSettings settings = getSettings(playerUuid);
        if (settings.showEnchantmentBanner != null) {
            return settings.showEnchantmentBanner;
        }
        return configManager.getConfig().showEnchantmentBanner;
    }

    /**
     * Updates and saves the glow preference for a player.
     */
    public void setEnableEnchantmentGlow(UUID playerUuid, Boolean enabled) {
        UserSettings settings = getSettings(playerUuid);
        settings.enableEnchantmentGlow = enabled;
        saveSettings();
    }

    /**
     * Updates and saves the banner preference for a player.
     */
    public void setShowEnchantmentBanner(UUID playerUuid, Boolean show) {
        UserSettings settings = getSettings(playerUuid);
        settings.showEnchantmentBanner = show;
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
     * Updates and saves the language preference for a player.
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
     * Updates and saves whether the player has seen the welcome greeting.
     */
    public void setHasSeenGreeting(UUID playerUuid, Boolean seen) {
        UserSettings settings = getSettings(playerUuid);
        settings.hasSeenGreeting = seen;
        saveSettings();
    }
}
