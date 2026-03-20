package org.herolias.plugin.config;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;
import com.hypixel.hytale.logger.HytaleLogger;

import java.io.File;
import java.io.FileReader;
import java.io.FileWriter;
import java.io.IOException;

/**
 * Manages configuration loading with "Smart Merge" logic using a sidecar
 * snapshot.
 * Structure:
 * - config.json (User's active config)
 * - .config.json.snapshot (Hidden snapshot of defaults from the last run)
 */
public class SmartConfigManager {

    private static final HytaleLogger LOGGER = HytaleLogger.forEnclosingClass();
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create();

    /**
     * Loads the config, merging upgrades if necessary.
     *
     * @param configFile  The user's configuration file.
     * @param configClass The class of the configuration object.
     * @param newDefaults The fresh, code-generated default configuration.
     * @param <T>         The type of the config.
     * @return The merged configuration object.
     */
    public static <T> T loadAndMerge(File configFile, Class<T> configClass, T newDefaults) {
        String snapshotFileName = "." + configFile.getName() + ".snapshot";
        File snapshotFile = new File(configFile.getParentFile(), snapshotFileName);

        // Scenario 1: First run ever (No config, No snapshot)
        if (!configFile.exists()) {
            saveConfig(configFile, newDefaults);
            saveConfig(snapshotFile, newDefaults);
            return newDefaults;
        }

        // Scenario 2: Migration (Config exists, No snapshot)
        // User Request: PROCEED -> Force update to new defaults to ensure everyone gets
        // new balance changes.
        if (configFile.exists() && !snapshotFile.exists()) {
            LOGGER.atInfo().log("Migration detected (No snapshot found). Force updating config to latest defaults.");
            // Backup old config just in case
            File backup = new File(configFile.getParentFile(), configFile.getName() + ".old");
            configFile.renameTo(backup);
            LOGGER.atInfo().log("Backed up old config to " + backup.getName());

            saveConfig(configFile, newDefaults);
            saveConfig(snapshotFile, newDefaults);
            return newDefaults;
        }

        // Scenario 3: Regular Update (Config exists, Snapshot exists)
        try {
            JsonElement userConfigJson = readJson(configFile);
            JsonElement snapshotJson = readJson(snapshotFile);
            JsonElement newDefaultsJson = GSON.toJsonTree(newDefaults);

            if (userConfigJson == null || snapshotJson == null) {
                LOGGER.atWarning().log("Failed to parse config or snapshot. Resetting to defaults.");
                return newDefaults;
            }

            // Perform Smart Merge
            JsonElement mergedJson = merge(userConfigJson, snapshotJson, newDefaultsJson);

            // Deserialize result
            T mergedConfig = GSON.fromJson(mergedJson, configClass);

            // Save active config (merged) and new snapshot (new defaults)
            saveConfig(configFile, mergedConfig);
            saveConfig(snapshotFile, newDefaults); // Always update snapshot to current code defaults

            return mergedConfig;

        } catch (Exception e) {
            LOGGER.atSevere().log("Error during smart config merge: " + e.getMessage(), e);
            // Fallback: Return defaults but don't overwrite user file to avoid data loss on
            // error
            return newDefaults;
        }
    }

    private static JsonElement readJson(File file) {
        try (FileReader reader = new FileReader(file)) {
            return GSON.fromJson(reader, JsonElement.class);
        } catch (IOException e) {
            return null;
        }
    }

    private static <T> void saveConfig(File file, T configObject) {
        try {
            if (file.getParentFile() != null)
                file.getParentFile().mkdirs();
            try (FileWriter writer = new FileWriter(file)) {
                GSON.toJson(configObject, writer);
            }
        } catch (IOException e) {
            LOGGER.atSevere().log("Failed to save config to " + file.getName(), e);
        }
    }

    /**
     * Recursive 3-way merge.
     * Returns:
     * - NewDefault if User == Snapshot (Safe upgrade)
     * - User if User != Snapshot (User override)
     * - NewDefault + User additions (Structure merge)
     */
    private static JsonElement merge(JsonElement user, JsonElement snapshot, JsonElement newDef) {
        // 1. Structure Mismatch: If types differ, New Default wins (Code structure
        // changed)
        if (!isSameType(user, newDef)) {
            return newDef.deepCopy();
        }

        // 2. Primitives: Value comparison
        if (user.isJsonPrimitive()) {
            // If User value matches the Old Default (Snapshot), safely update to New
            // Default.
            if (user.equals(snapshot)) {
                return newDef.deepCopy();
            } else {
                // User has changed it, keep User value.
                return user.deepCopy();
            }
        }

        // 3. Arrays: Complex to merge index-by-index.
        // Strategy: If identical to snapshot, take new default. If modified, keep user.
        // Deep merging arrays is dangerous (e.g. lists of recipes).
        if (user.isJsonArray()) {
            if (user.equals(snapshot)) {
                return newDef.deepCopy();
            } else {
                return user.deepCopy();
            }
        }

        // 4. Objects: Recurse per key
        if (user.isJsonObject()) {
            JsonObject userObj = user.getAsJsonObject();
            JsonObject snapObj = snapshot.isJsonObject() ? snapshot.getAsJsonObject() : new JsonObject();
            JsonObject defObj = newDef.getAsJsonObject();
            JsonObject result = new JsonObject();

            // Iterate over all keys in the NEW default (to ensure we have all new fields)
            for (String key : defObj.keySet()) {
                JsonElement userVal = userObj.get(key);
                JsonElement snapVal = snapObj.get(key);
                JsonElement defVal = defObj.get(key);

                if (userVal == null) {
                    // New key introduced in code: Use new default
                    result.add(key, defVal.deepCopy());
                } else if (snapVal == null) {
                    // Key exists in User but not in Snapshot?
                    // Means it was added by user manually or from a version skipped without
                    // snapshot.
                    result.add(key, merge(userVal, new JsonObject(), defVal));
                } else {
                    // Standard merge
                    result.add(key, merge(userVal, snapVal, defVal));
                }
            }

            // Preserve extra keys the user might have added (that are NOT in new defaults)
            // Useful for Maps (like custom recipes) where keys are dynamic.
            for (String key : userObj.keySet()) {
                if (!defObj.has(key)) {
                    result.add(key, userObj.get(key).deepCopy());
                }
            }

            return result;
        }

        return newDef.deepCopy(); // Fallback
    }

    private static boolean isSameType(JsonElement a, JsonElement b) {
        if (a.isJsonObject() && b.isJsonObject())
            return true;
        if (a.isJsonArray() && b.isJsonArray())
            return true;
        if (a.isJsonPrimitive() && b.isJsonPrimitive())
            return true;
        if (a.isJsonNull() && b.isJsonNull())
            return true;
        return false;
    }
}
