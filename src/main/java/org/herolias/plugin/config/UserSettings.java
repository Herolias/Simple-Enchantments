package org.herolias.plugin.config;

/**
 * Data class representing a single player's personal settings.
 * Null values indicate that the player has not overridden the server default.
 */
public class UserSettings {
    public Boolean enableEnchantmentGlow = null;
    public Boolean showEnchantmentBanner = null;
    public String language = "default";
    public Boolean hasSeenGreeting = false;

    public UserSettings() {
        // Default constructor for GSON
    }
}
