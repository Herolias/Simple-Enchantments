package org.herolias.plugin.config;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.hypixel.hytale.logger.HytaleLogger;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.io.File;
import java.io.IOException;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

/**
 * Manages configuration loading with "Smart Merge" logic using a sidecar
 * snapshot.
 * Structure:
 * - config.json (User's active config)
 * - .config.json.snapshot (Hidden snapshot of defaults from the last run)
 * <p>
 * Robustness rules:
 * <ul>
 * <li>Every read path catches {@link Exception} (Gson throws unchecked
 * {@code JsonSyntaxException}); nothing escapes to the caller.</li>
 * <li>A corrupt user file is copied to {@code <name>.corrupt-<timestamp>.json}
 * and is <b>not</b> overwritten; the built-in defaults are returned with
 * {@link LoadResult#fallback} set so callers can refuse to save over it.</li>
 * <li>A corrupt snapshot is backed up and treated as "no snapshot"
 * (migration merge).</li>
 * </ul>
 */
public class SmartConfigManager {

    private static final HytaleLogger LOGGER = HytaleLogger.forEnclosingClass();
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create();
    private static final DateTimeFormatter BACKUP_TIMESTAMP = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss");

    /**
     * Result of a config load: the config object plus whether it is a fallback
     * (built-in defaults because the user's file could not be read).
     */
    public static final class LoadResult<T> {
        @Nonnull
        public final T config;
        /** True if {@link #config} is the built-in default because the user file was unreadable. */
        public final boolean fallback;
        /** Backup of the corrupt file, if one was written. */
        @Nullable
        public final File backupFile;

        private LoadResult(@Nonnull T config, boolean fallback, @Nullable File backupFile) {
            this.config = config;
            this.fallback = fallback;
            this.backupFile = backupFile;
        }

        static <T> LoadResult<T> ok(@Nonnull T config) {
            return new LoadResult<>(config, false, null);
        }

        static <T> LoadResult<T> fallback(@Nonnull T defaults, @Nullable File backupFile) {
            return new LoadResult<>(defaults, true, backupFile);
        }
    }

    /**
     * Loads the config, merging upgrades if necessary. Never throws.
     *
     * @param configFile  The user's configuration file.
     * @param configClass The class of the configuration object.
     * @param newDefaults The fresh, code-generated default configuration.
     * @param <T>         The type of the config.
     * @return The merged configuration object (or the defaults on failure).
     */
    @Nonnull
    public static <T> T loadAndMerge(File configFile, Class<T> configClass, T newDefaults) {
        return load(configFile, configClass, newDefaults).config;
    }

    /**
     * Loads the config, merging upgrades if necessary, and reports whether the
     * result is a fallback. Never throws.
     */
    @Nonnull
    public static <T> LoadResult<T> load(File configFile, Class<T> configClass, T newDefaults) {
        try {
            return load0(configFile, configClass, newDefaults);
        } catch (Exception e) {
            LOGGER.atSevere().withCause(e).log(
                    "Unexpected error while loading %s. Using built-in defaults; the file will not be overwritten.",
                    configFile.getName());
            return LoadResult.fallback(newDefaults, null);
        }
    }

    private static <T> LoadResult<T> load0(File configFile, Class<T> configClass, T newDefaults) {
        String snapshotFileName = "." + configFile.getName() + ".snapshot";
        File snapshotFile = new File(configFile.getParentFile(), snapshotFileName);
        JsonElement newDefaultsJson = GSON.toJsonTree(newDefaults);

        // Scenario 1: First run ever (No config)
        if (!configFile.exists()) {
            saveConfig(configFile, newDefaults);
            saveConfig(snapshotFile, newDefaults);
            return LoadResult.ok(newDefaults);
        }

        // Read the user's file; null means unreadable / corrupt
        JsonElement userConfigJson = readJson(configFile);
        if (userConfigJson == null || !userConfigJson.isJsonObject()) {
            File backup = backupCorruptFile(configFile);
            LOGGER.atSevere().log(
                    "Config file %s is corrupt or unreadable%s. Using built-in defaults; the file will NOT be overwritten until it is repaired or deleted.",
                    configFile.getAbsolutePath(),
                    backup != null ? " (backed up to " + backup.getName() + ")" : "");
            return LoadResult.fallback(newDefaults, backup);
        }

        // JSON-level migrations that must run BEFORE the merge fills in defaults
        JsonObject userObj = applyPreMergeMigrations(configClass, userConfigJson.getAsJsonObject(),
                newDefaultsJson.isJsonObject() ? newDefaultsJson.getAsJsonObject() : new JsonObject());

        // Snapshot (may be missing or corrupt -> migration merge against an empty snapshot)
        JsonElement snapshotJson = null;
        if (snapshotFile.exists()) {
            snapshotJson = readJson(snapshotFile);
            if (snapshotJson == null || !snapshotJson.isJsonObject()) {
                File backup = backupCorruptFile(snapshotFile);
                LOGGER.atWarning().log("Config snapshot %s is corrupt%s; treating it as missing.",
                        snapshotFile.getName(), backup != null ? " (backed up to " + backup.getName() + ")" : "");
                snapshotJson = null;
            }
        }

        if (snapshotJson == null) {
            // Scenario 2: Migration (Config exists, No snapshot)
            LOGGER.atInfo().log("Migration detected (no snapshot found). Merging existing config with latest defaults.");
            File backup = new File(configFile.getParentFile(), configFile.getName() + ".old");
            try {
                Files.copy(configFile.toPath(), backup.toPath(), StandardCopyOption.REPLACE_EXISTING);
                LOGGER.atInfo().log("Backed up old config to %s", backup.getName());
            } catch (IOException e) {
                LOGGER.atWarning().withCause(e)
                        .log("Could not back up old config before migration. Continuing with in-memory copy.");
            }
            snapshotJson = new JsonObject();
        }

        // Scenario 3: Regular Update (Config exists, Snapshot exists) - 3-way merge
        JsonElement mergedJson = merge(userObj, snapshotJson, newDefaultsJson);
        mergedJson = applyPostMergeMigrations(configClass, userObj, mergedJson);

        T mergedConfig = GSON.fromJson(mergedJson, configClass);
        if (mergedConfig == null) {
            LOGGER.atSevere().log("Merged config for %s deserialised to null. Using built-in defaults.",
                    configFile.getName());
            return LoadResult.fallback(newDefaults, null);
        }

        // Save active config (merged) and new snapshot (new defaults)
        saveConfig(configFile, mergedConfig);
        saveConfig(snapshotFile, newDefaults); // Always update snapshot to current code defaults

        return LoadResult.ok(mergedConfig);
    }

    /**
     * @return the parsed JSON, or null if the file is missing, empty, unreadable
     *         or not valid JSON (already logged).
     */
    @Nullable
    private static JsonElement readJson(File file) {
        try (Reader reader = Files.newBufferedReader(file.toPath(), StandardCharsets.UTF_8)) {
            JsonElement element = GSON.fromJson(reader, JsonElement.class);
            if (element == null || element.isJsonNull()) {
                LOGGER.atWarning().log("%s is empty", file.getName());
                return null;
            }
            return element;
        } catch (Exception e) {
            // IOException, JsonSyntaxException, JsonIOException, ...
            LOGGER.atSevere().withCause(e).log("Failed to read or parse %s", file.getAbsolutePath());
            return null;
        }
    }

    /**
     * Copies a corrupt file to {@code <name>.corrupt-<timestamp>.json} next to it.
     *
     * @return the backup file, or null if the copy failed
     */
    @Nullable
    static File backupCorruptFile(File file) {
        if (file == null || !file.exists()) {
            return null;
        }
        String name = file.getName();
        if (name.endsWith(".json")) {
            name = name.substring(0, name.length() - ".json".length());
        }
        File backup = new File(file.getParentFile(),
                name + ".corrupt-" + LocalDateTime.now().format(BACKUP_TIMESTAMP) + ".json");
        try {
            Files.copy(file.toPath(), backup.toPath(), StandardCopyOption.REPLACE_EXISTING);
            return backup;
        } catch (IOException e) {
            LOGGER.atSevere().withCause(e).log("Failed to back up corrupt file %s", file.getAbsolutePath());
            return null;
        }
    }

    private static <T> void saveConfig(File file, T configObject) {
        try {
            AtomicJsonWriter.write(file, configObject, GSON);
        } catch (Exception e) {
            LOGGER.atSevere().withCause(e).log("Failed to save config to %s", file.getAbsolutePath());
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
            JsonObject snapObj = snapshot != null && snapshot.isJsonObject() ? snapshot.getAsJsonObject()
                    : new JsonObject();
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

    // ───────────────────── Migrations ─────────────────────

    /**
     * Migrations applied to the raw user JSON before the 3-way merge. Legacy keys
     * are stripped here so the merge's "preserve extra user keys" rule does not
     * carry them forward forever.
     */
    @Nonnull
    private static JsonObject applyPreMergeMigrations(Class<?> configClass, @Nonnull JsonObject userObj,
            @Nonnull JsonObject defaultsObj) {
        if (configClass == EnchantingConfig.class) {
            migrateLegacyMultipliers(userObj, defaultsObj);
            migrateDisableEnchantmentCrafting(userObj);
        }
        return userObj;
    }

    private static JsonElement applyPostMergeMigrations(Class<?> configClass, JsonObject userObj,
            JsonElement mergedJson) {
        // Currently all EnchantingConfig migrations run pre-merge.
        return mergedJson;
    }

    /**
     * Legacy (1.x) per-field multiplier keys -> {@code enchantmentMultipliers} map
     * keys.
     */
    static final Map<String, String> LEGACY_MULTIPLIER_KEYS;
    /** Legacy keys that have no consumer any more and are dropped. */
    static final Set<String> DROPPED_LEGACY_KEYS = Set.of("strengthRangeMultiplierPerLevel");

    static {
        Map<String, String> m = new LinkedHashMap<>();
        m.put("sharpnessDamageMultiplierPerLevel", "sharpness");
        m.put("lifeLeechPercentage", "life_leech");
        m.put("durabilityReductionPerLevel", "durability");
        m.put("dexterityStaminaReductionPerLevel", "dexterity");
        m.put("protectionDamageReductionPerLevel", "protection");
        m.put("efficiencyMiningSpeedPerLevel", "efficiency");
        m.put("fortuneRollChancePerLevel", "fortune");
        m.put("strengthDamageMultiplierPerLevel", "strength");
        m.put("eaglesEyeDistanceBonusPerLevel", "eagles_eye");
        m.put("lootingChanceMultiplierPerLevel", "looting");
        m.put("lootingQuantityMultiplierPerLevel", "looting:quantity");
        m.put("featherFallingReductionPerLevel", "feather_falling");
        m.put("waterBreathingReductionPerLevel", "waterbreathing");
        m.put("knockbackStrengthPerLevel", "knockback");
        m.put("reflectionDamagePercentagePerLevel", "reflection");
        m.put("absorptionHealPercentagePerLevel", "absorption");
        m.put("fastSwimSpeedBonusPerLevel", "fast_swim");
        m.put("rangedProtectionDamageReductionPerLevel", "ranged_protection");
        m.put("frenzyChargeSpeedMultiplierPerLevel", "frenzy");
        m.put("riposteDamageMultiplierPerLevel", "riposte");
        m.put("coupDeGraceDamageMultiplierPerLevel", "coup_de_grace");
        m.put("thriftRestoreAmountPerLevel", "thrift");
        m.put("elementalHeartSaveChancePerLevel", "elemental_heart");
        // Former dedicated duration fields
        m.put("burnDuration", "burn:duration");
        m.put("freezeDuration", "freeze:duration");
        m.put("poisonDuration", "poison:duration");
        LEGACY_MULTIPLIER_KEYS = java.util.Collections.unmodifiableMap(m);
    }

    /**
     * Moves legacy per-field multipliers / durations into
     * {@code enchantmentMultipliers} and strips the legacy keys.
     * <p>
     * A legacy value wins over an existing map entry only if that entry still
     * equals the code default (i.e. the user never touched the new field); a
     * customised new-style value is kept. Runs regardless of whether the map
     * already exists (a previous version left legacy keys in place while filling
     * the map with defaults).
     *
     * @return number of migrated values (for tests/logging)
     */
    static int migrateLegacyMultipliers(@Nonnull JsonObject userObj, @Nonnull JsonObject defaultsObj) {
        JsonObject defaultMultipliers = defaultsObj.has("enchantmentMultipliers")
                && defaultsObj.get("enchantmentMultipliers").isJsonObject()
                        ? defaultsObj.getAsJsonObject("enchantmentMultipliers")
                        : new JsonObject();

        JsonObject multipliers = userObj.has("enchantmentMultipliers")
                && userObj.get("enchantmentMultipliers").isJsonObject()
                        ? userObj.getAsJsonObject("enchantmentMultipliers")
                        : null;

        int migrated = 0;
        for (Map.Entry<String, String> legacy : LEGACY_MULTIPLIER_KEYS.entrySet()) {
            String legacyKey = legacy.getKey();
            String targetKey = legacy.getValue();
            if (!userObj.has(legacyKey)) {
                continue;
            }
            JsonElement value = userObj.remove(legacyKey);
            if (value == null || !value.isJsonPrimitive() || !value.getAsJsonPrimitive().isNumber()) {
                continue; // null or garbage: nothing to carry over
            }
            if (multipliers == null) {
                multipliers = new JsonObject();
                userObj.add("enchantmentMultipliers", multipliers);
            }
            JsonElement existing = multipliers.get(targetKey);
            JsonElement def = defaultMultipliers.get(targetKey);
            if (existing == null || existing.equals(def)) {
                multipliers.add(targetKey, value);
                migrated++;
            } else {
                LOGGER.atInfo().log(
                        "Legacy config key '%s' dropped: '%s' already has a customised value (%s).",
                        legacyKey, targetKey, existing);
            }
        }

        for (String dropped : DROPPED_LEGACY_KEYS) {
            if (userObj.remove(dropped) != null) {
                LOGGER.atInfo().log("Dropped legacy config key '%s' (no longer used).", dropped);
            }
        }

        if (migrated > 0) {
            LOGGER.atInfo().log("Migrated %d legacy multiplier/duration value(s) into enchantmentMultipliers.",
                    migrated);
        }
        return migrated;
    }

    /**
     * {@code disableEnchantmentCrafting=true} (pre-2.1) -> both table and scroll
     * crafting disabled, unless the new keys are already present.
     */
    static void migrateDisableEnchantmentCrafting(@Nonnull JsonObject userObj) {
        JsonElement oldDisableCrafting = userObj.remove("disableEnchantmentCrafting");
        if (!isBooleanTrue(oldDisableCrafting)) {
            return;
        }

        if (!userObj.has("enableEnchantingTableCrafting")) {
            userObj.addProperty("enableEnchantingTableCrafting", false);
        }
        if (!userObj.has("enableScrollCrafting")) {
            userObj.addProperty("enableScrollCrafting", false);
        }

        LOGGER.atInfo().log("Migrated disableEnchantmentCrafting=true to disabled Enchanting Table and scroll crafting.");
    }

    private static boolean isBooleanTrue(JsonElement element) {
        return element != null
                && element.isJsonPrimitive()
                && element.getAsJsonPrimitive().isBoolean()
                && element.getAsBoolean();
    }
}
