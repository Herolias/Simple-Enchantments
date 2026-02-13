package org.herolias.plugin.enchantment;

import com.hypixel.hytale.codec.Codec;
import com.hypixel.hytale.logger.HytaleLogger;
import com.hypixel.hytale.server.core.asset.type.blocktype.config.BlockBreakingDropType;
import com.hypixel.hytale.server.core.asset.type.blocktype.config.BlockType;
import com.hypixel.hytale.server.core.asset.type.item.config.Item;
import com.hypixel.hytale.server.core.inventory.ItemStack;
import com.hypixel.hytale.server.core.inventory.container.ItemContainer;
import org.bson.BsonDocument;
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ThreadLocalRandom;
import org.herolias.plugin.SimpleEnchanting;

import javax.annotation.Nonnull;
import com.hypixel.hytale.server.core.asset.type.item.config.ItemWeapon;
import com.hypixel.hytale.server.core.modules.entitystats.asset.EntityStatType;
import com.hypixel.hytale.server.core.modules.entitystats.asset.DefaultEntityStatTypes;
import com.hypixel.hytale.server.core.modules.interaction.interaction.config.RootInteraction;
import com.hypixel.hytale.server.core.modules.interaction.interaction.config.Interaction;
import com.hypixel.hytale.server.core.modules.interaction.interaction.config.server.ChangeStatInteraction;
import com.hypixel.hytale.server.core.modules.interaction.interaction.config.none.StatsConditionInteraction;
import com.hypixel.hytale.server.core.modules.interaction.interaction.config.server.ChangeStatBaseInteraction;
import com.hypixel.hytale.server.core.modules.interaction.interaction.config.none.StatsConditionBaseInteraction;
import com.hypixel.hytale.protocol.InteractionType;
import com.hypixel.hytale.server.core.entity.LivingEntity;
import com.hypixel.hytale.server.core.inventory.Inventory;
import com.hypixel.hytale.server.core.asset.type.entityeffect.config.EntityEffect;
import com.hypixel.hytale.server.core.entity.effect.EffectControllerComponent;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.component.CommandBuffer;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import com.hypixel.hytale.server.core.modules.entity.damage.Damage;
import java.lang.reflect.Field;
import it.unimi.dsi.fastutil.ints.Int2FloatMap;
import it.unimi.dsi.fastutil.objects.Object2FloatMap;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/**
 * Central manager for the enchantment system.
 * 
 * Handles:
 * - Applying enchantments to items (stored in ItemStack metadata)
 * - Reading enchantments from items
 * - Checking enchantment applicability
 * - Calculating enchantment effects
 * 
 * Enchantments are stored directly in ItemStack metadata using BSON format,
 * which persists with items and syncs to clients automatically.
 */
public class EnchantmentManager {

    private static final HytaleLogger LOGGER = HytaleLogger.forEnclosingClass();
    private static final double EAGLES_EYE_MAX_DISTANCE = 50.0;
    private final ConcurrentHashMap<UUID, ProjectileEnchantmentData> projectileEnchantmentsByUuid = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<UUID, ProjectileEnchantmentData> burnEnchantmentsByEntityUuid = new ConcurrentHashMap<>();

    // Reflection Cache
    private static Field CHANGE_STAT_ENTITY_STAT_ASSETS;
    private static Field CHANGE_STAT_ENTITY_STATS;
    private static Field STATS_CONDITION_RAW_COSTS;
    private static Field STATS_CONDITION_COSTS;
    private static Field PROJECTILE_CREATOR_FIELD;

    static {
        try {
            CHANGE_STAT_ENTITY_STAT_ASSETS = ChangeStatBaseInteraction.class.getDeclaredField("entityStatAssets");
            CHANGE_STAT_ENTITY_STAT_ASSETS.setAccessible(true);
            
            CHANGE_STAT_ENTITY_STATS = ChangeStatBaseInteraction.class.getDeclaredField("entityStats");
            CHANGE_STAT_ENTITY_STATS.setAccessible(true);
            
            STATS_CONDITION_RAW_COSTS = StatsConditionBaseInteraction.class.getDeclaredField("rawCosts");
            STATS_CONDITION_RAW_COSTS.setAccessible(true);
            
            STATS_CONDITION_COSTS = StatsConditionBaseInteraction.class.getDeclaredField("costs");
            STATS_CONDITION_COSTS.setAccessible(true);
            
            PROJECTILE_CREATOR_FIELD = com.hypixel.hytale.server.core.entity.entities.ProjectileComponent.class.getDeclaredField("creatorUuid");
            PROJECTILE_CREATOR_FIELD.setAccessible(true);
        } catch (Throwable e) {
            LOGGER.atSevere().log("Failed to initialize reflection fields: " + e.getMessage());
        }
    }
    
    // Cache for expensive string checks
    private final ConcurrentHashMap<String, Boolean> oreOrCrystalCache = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, Boolean> manaConsumingCache = new ConcurrentHashMap<>();
    // shieldCache removed - delegated to ItemCategoryManager

    /**
     * Cached set of disabled enchantment IDs.  Rebuilt lazily when the config
     * changes (via {@link #invalidateEnabledCache()}).  Avoids repeated
     * {@code getConfig().disabledEnchantments.getOrDefault()} lookups on every
     * {@link #isEnchantmentEnabled} call.
     */
    private volatile Set<String> disabledEnchantmentIds = null;

    /**
     * Calculates the drop chance multiplier based on Looting level.
     */
    public double calculateLootingChanceMultiplier(int lootingLevel) {
        return calculateLootingMultiplier(lootingLevel, getConfig().lootingChanceMultiplierPerLevel);
    }

    /**
     * Calculates the drop quantity multiplier based on Looting level.
     */
    public double calculateLootingQuantityMultiplier(int lootingLevel) {
        return calculateLootingMultiplier(lootingLevel, getConfig().lootingQuantityMultiplierPerLevel);
    }

    private double calculateLootingMultiplier(int level, double multiplierPerLevel) {
        if (level <= 0) return 1.0;
        return 1.0 + (level * multiplierPerLevel);
    }
    
    private final ConcurrentHashMap<Integer, ProjectileEnchantmentData> projectileEnchantmentsByNetworkId = new ConcurrentHashMap<>();
    private final SmeltingRecipeRegistry smeltingRecipeRegistry = new SmeltingRecipeRegistry();
    private final CookingRecipeRegistry cookingRecipeRegistry = new CookingRecipeRegistry();
    
    public EnchantmentManager(SimpleEnchanting plugin) {
        LOGGER.atInfo().log("EnchantmentManager initialized (metadata-based storage)");
        ItemCategoryManager.getInstance().loadConfiguration();
    }

    private org.herolias.plugin.config.EnchantingConfig getConfig() {
        return SimpleEnchanting.getInstance().getConfigManager().getConfig();
    }

    /**
     * Stores enchantment data for an entity that is burning.
     * This allows us to attribute the kill to the original attacker/weapon even if they die from the DoT.
     */
    public void storeBurnEnchantments(@Nonnull UUID entityUuid, int burnLevel, int lootingLevel) {
        if (burnLevel <= 0 && lootingLevel <= 0) {
            burnEnchantmentsByEntityUuid.remove(entityUuid);
            return;
        }
        // distinct from projectile data, but reusing the class since it holds the same fields we need
        burnEnchantmentsByEntityUuid.put(entityUuid, new ProjectileEnchantmentData(0, 0, lootingLevel, 0, burnLevel, 0));
    }

    @Nullable
    public ProjectileEnchantmentData getBurnEnchantments(@Nonnull UUID entityUuid) {
        return burnEnchantmentsByEntityUuid.get(entityUuid);
    }

    public void removeBurnEnchantments(@Nonnull UUID entityUuid) {
        burnEnchantmentsByEntityUuid.remove(entityUuid);
    }

    /**
     * Applies an enchantment to an item. Allows unsafe levels (ignoring max level cap).
     * 
     * @param item The item to enchant
     * @param type The enchantment to apply
     * @param level The level of the enchantment
     * @param unsafe If true, bypasses max level checks
     * @return Result
     */
    public EnchantmentApplicationResult applyEnchantmentToItem(@Nonnull ItemStack item, @Nonnull EnchantmentType type, int level, boolean unsafe) {
        if (item == null || item.isEmpty()) {
            LOGGER.atWarning().log("Cannot enchant null or empty item");
            return EnchantmentApplicationResult.failure("Cannot enchant null or empty item.");
        }

        // Check config
        org.herolias.plugin.config.EnchantingConfig config = getConfig();
        // Common applicability check (Config, Category, Durability, Special Rules)
        String error = checkCommonApplicability(item, type, config);
        if (error != null) {
            return EnchantmentApplicationResult.failure(error);
        }
        
        // Get existing enchantments from item metadata
        EnchantmentData data = getEnchantmentsFromItem(item);
        
        // If the data is the immutable EMPTY instance (or we want to be safe), create a mutable copy
        if (data == EnchantmentData.EMPTY || data.isEmpty()) {
            data = new EnchantmentData();
        } else {
             // We can't easily check for immutability without try-catch or exposing a method, 
             // but 'copy()' is always safe and cheap enough for an action that happens once per anvil use.
             data = data.copy();
        }

        // Check level limits (if not unsafe)
        int appliedLevel = level;
        if (!unsafe) {
            if (appliedLevel > type.getMaxLevel()) {
                appliedLevel = type.getMaxLevel(); // Clamp for safe mode
            }
        } else {
            // Even in unsafe mode, single-level enchants (maxLevel == 1) like Smelting/Burn should likely stay at 1
            // unless we specifically want to break them (but usually they are boolean flags).
            // User requested to keep these capped.
            if (type.getMaxLevel() == 1 && appliedLevel > 1) {
                 appliedLevel = 1;
            }
        }

        // Check max enchantments limit (unless upgrading existing enchantment)
        if (!data.hasEnchantment(type) && data.getAllEnchantments().size() >= config.maxEnchantmentsPerItem) {
            return EnchantmentApplicationResult.failure("Maximum number of enchantments reached for this item.");
        }
        
        // Check for conflicts
        for (EnchantmentType existing : data.getAllEnchantments().keySet()) {
            // Allow overwriting/upgrading the same enchantment
            if (existing == type) {
                continue;
            }
            if (type.conflictsWith(existing)) {
                return EnchantmentApplicationResult.failure("Enchantment conflicts with " + existing.getDisplayName());
            }
        }
        
        // Apply the enchantment
        data.addEnchantment(type, appliedLevel);
        
        // Store back to item metadata
        ItemStack enchantedItem = item.withMetadata(EnchantmentData.METADATA_KEY, data.toBson());
        
        // Update visual indicators (Tooltip and Quality)
        enchantedItem = updateItemVisuals(enchantedItem);
        
        LOGGER.atInfo().log("Applied " + type.getFormattedName(appliedLevel) + " to " + item.getItemId());
        return EnchantmentApplicationResult.success(enchantedItem, "Successfully applied " + type.getFormattedName(appliedLevel) + ".");
    }

    /**
     * Applies an enchantment to an item with strict max level checks (Safe mode).
     */
    @Nonnull
    public EnchantmentApplicationResult applyEnchantmentToItem(@Nonnull ItemStack item, @Nonnull EnchantmentType type, int level) {
        return applyEnchantmentToItem(item, type, level, false);
    }

    /**
     * Checks if an item defines the basic requirements to accept a specific enchantment.
     * Checks: Category, Config status, Durability requirements.
     * Does NOT check: Existing enchantments (conflicts/max count).
     */
    public boolean canAcceptEnchantment(@Nonnull ItemStack item, @Nonnull EnchantmentType type) {
        if (item == null || item.isEmpty()) return false;

        // Check config
        org.herolias.plugin.config.EnchantingConfig config = getConfig();
        // Common applicability check
        String error = checkCommonApplicability(item, type, config);
        if (error != null) {
            return false;
        }
        
        // Check for conflicts
        EnchantmentData data = getEnchantmentsFromItem(item);
        for (EnchantmentType existing : data.getAllEnchantments().keySet()) {
            if (existing == type) continue;
            if (type.conflictsWith(existing)) return false;
        }

        return true;
    }

    /**
     * Checks common applicability rules (Config, Category, Durability, Special cases).
     * @return null if applicable, error message otherwise.
     */
    private String checkCommonApplicability(ItemStack item, EnchantmentType type, org.herolias.plugin.config.EnchantingConfig config) {
        if (config.disabledEnchantments.getOrDefault(type.getId(), false)) {
             return "This enchantment is disabled in the server configuration.";
        }

        // Special check for Thrift
        if (type == EnchantmentType.THRIFT) {
             if (!isManaConsuming(item)) return "This enchantment requires a mana-consuming item.";
             return null;
        }

        ItemCategory category = categorizeItem(item);
        if (!type.canApplyTo(category)) {
            return "Cannot apply " + type.getDisplayName() + " to this item type.";
        }
        
        if (type.requiresDurability()) {
             Number maxDur = item.getItem().getMaxDurability();
             Integer maxStack = item.getItem().getMaxStack();
             if (maxDur == null || maxDur.doubleValue() <= 0) {
                 return "This enchantment requires an item with durability.";
             }
             if (maxStack != null && maxStack > 1) {
                 return "Cannot apply durability enchantments to stackable items.";
             }
        }
        return null;
    }

    /**
     * Checks if an item is consumed mana or magic charges to function.
     * Used for determining eligibility for Mana Weaving enchantment.
     */
    /**
     * Checks if an item is consumed mana or magic charges to function.
     * Used for determining eligibility for Thrift enchantment.
     */
    public boolean isManaConsuming(@Nonnull ItemStack item) {
        if (item == null || item.isEmpty()) return false;
        
        String itemId = item.getItemId();
        if (itemId == null) return false;

        return manaConsumingCache.computeIfAbsent(itemId, id -> {
            // 0. Check Category (Config/Mapping override)
            if (ItemCategoryManager.getInstance().categorizeItem(item) == ItemCategory.STAFF_MANA) {
                return true;
            }

            // 1. Check Family/Tags
            if (hasTag(item.getItem(), "Family", "Mana")) {
                return true;
            }

            // 2. Deep inspection of Interactions (Primary/Secondary)
            int manaStatId = DefaultEntityStatTypes.getMana();
            
            // Check main interactions
            for (String rootInteractionName : item.getItem().getInteractions().values()) {
                if (checkRootInteraction(rootInteractionName, manaStatId)) {
                    return true;
                }
            }

            // Check Interaction Variables (Overridden/Inline interactions often contain the costs)
            if (item.getItem().getInteractionVars() != null) {
                for (String rootInteractionName : item.getItem().getInteractionVars().values()) {
                    if (checkRootInteraction(rootInteractionName, manaStatId)) {
                        return true;
                    }
                }
            }
            
            return false;
        });
    }

    private boolean checkRootInteraction(String rootInteractionName, int manaStatId) {
        RootInteraction root = RootInteraction.getRootInteractionOrUnknown(rootInteractionName);
        if (root == null || root.getInteractionIds() == null) return false;

        for (String interactionId : root.getInteractionIds()) {
            Interaction interaction = Interaction.getAssetMap().getAsset(interactionId);
            if (interaction == null) continue;

            if (isManaConsumingInteraction(interaction, manaStatId)) {
                return true;
            }
        }
        return false;
    }

    private boolean isManaConsumingInteraction(Interaction interaction, int manaStatId) {
        try {
            if (interaction instanceof ChangeStatBaseInteraction) {
                // Check entityStatAssets (String map)
                if (CHANGE_STAT_ENTITY_STAT_ASSETS != null) {
                    @SuppressWarnings("unchecked")
                    Object2FloatMap<String> assets = (Object2FloatMap<String>) CHANGE_STAT_ENTITY_STAT_ASSETS.get(interaction);
                    if (assets != null) {
                        for (Object2FloatMap.Entry<String> entry : assets.object2FloatEntrySet()) {
                             String key = entry.getKey();
                             if (key != null && key.equalsIgnoreCase("Mana") && entry.getFloatValue() < 0) {
                                 return true;
                             }
                        }
                    }
                }
                
                // Check entityStats (ID map)
                if (CHANGE_STAT_ENTITY_STATS != null) {
                    @SuppressWarnings("unchecked")
                    Int2FloatMap changes = (Int2FloatMap) CHANGE_STAT_ENTITY_STATS.get(interaction);
                    if (changes != null) {
                        for (Int2FloatMap.Entry entry : changes.int2FloatEntrySet()) {
                            if (entry.getIntKey() == manaStatId && entry.getFloatValue() < 0) {
                                return true;
                            }
                        }
                    }
                }
            }
            
            if (interaction instanceof StatsConditionBaseInteraction) {
                // Check rawCosts (String map)
                if (STATS_CONDITION_RAW_COSTS != null) {
                    @SuppressWarnings("unchecked")
                    Object2FloatMap<String> rawCosts = (Object2FloatMap<String>) STATS_CONDITION_RAW_COSTS.get(interaction);
                    if (rawCosts != null) {
                        for (Object2FloatMap.Entry<String> entry : rawCosts.object2FloatEntrySet()) {
                            String key = entry.getKey();
                            if (key != null && key.equalsIgnoreCase("Mana")) {
                                return true;
                            }
                        }
                    }
                }
                
                // Check costs (ID map)
                if (STATS_CONDITION_COSTS != null) {
                     @SuppressWarnings("unchecked")
                     Int2FloatMap costs = (Int2FloatMap) STATS_CONDITION_COSTS.get(interaction);
                     if (costs != null) {
                         for (Int2FloatMap.Entry entry : costs.int2FloatEntrySet()) {
                             if (entry.getIntKey() == manaStatId) {
                                 return true;
                             }
                         }
                     }
                }
            }
        } catch (IllegalAccessException e) {
            LOGGER.atWarning().log("Failed to access interaction fields: " + e.getMessage());
        }
        
        return false;
    }

    @SuppressWarnings("unchecked")
    private Object2FloatMap<String> getProtectedStringFloatMap(Object target, Class<?> clazz, String fieldName) {
        try {
            Field field = clazz.getDeclaredField(fieldName);
            field.setAccessible(true);
            return (Object2FloatMap<String>) field.get(target);
        } catch (Exception e) {
            return null;
        }
    }

    private Int2FloatMap getProtectedInt2FloatMap(Object target, Class<?> clazz, String fieldName) {
        try {
            Field field = clazz.getDeclaredField(fieldName);
            field.setAccessible(true);
            return (Int2FloatMap) field.get(target);
        } catch (Exception e) {
            // Log once per failure type to avoid spam, or just debug
            LOGGER.atWarning().log("Failed to access field " + fieldName + " on " + clazz.getSimpleName() + ": " + e.getMessage());
            return null;
        }
    }

    /**
     * Gets the enchantment data from an ItemStack's metadata.
     * 
     * @param item The item to read enchantments from
     * @return The enchantment data (never null, may be empty)
     */
    @Nonnull
    @SuppressWarnings("deprecation")
    public EnchantmentData getEnchantmentsFromItem(@Nullable ItemStack item) {
        if (item == null || item.isEmpty()) {
            return EnchantmentData.EMPTY;
        }
        
        BsonDocument enchantmentsBson = item.getFromMetadataOrNull(
            EnchantmentData.METADATA_KEY,
            Codec.BSON_DOCUMENT
        );
        if (enchantmentsBson != null && !enchantmentsBson.isEmpty()) {
            return EnchantmentData.fromBson(enchantmentsBson);
        }

        // Legacy fallback: metadata stored as a string (e.g., "sharpness:2,durability:1")
        String legacyData = item.getFromMetadataOrNull(EnchantmentData.METADATA_KEY, Codec.STRING);
        if (legacyData != null && !legacyData.isBlank()) {
            return EnchantmentData.deserialize(legacyData);
        }

        return EnchantmentData.EMPTY;
    }

    /**
     * Checks if an enchantment is enabled in the configuration.
     * Uses a cached set of disabled IDs for O(1) lookups.
     */
    public boolean isEnchantmentEnabled(EnchantmentType type) {
        if (type == null) return false;
        Set<String> disabled = disabledEnchantmentIds;
        if (disabled == null) {
            disabled = rebuildDisabledSet();
        }
        return !disabled.contains(type.getId());
    }

    /**
     * Rebuilds the cached set of disabled enchantment IDs from the current config.
     */
    private Set<String> rebuildDisabledSet() {
        Set<String> disabled = new HashSet<>();
        for (var entry : getConfig().disabledEnchantments.entrySet()) {
            if (Boolean.TRUE.equals(entry.getValue())) {
                disabled.add(entry.getKey());
            }
        }
        this.disabledEnchantmentIds = disabled;
        return disabled;
    }

    /**
     * Invalidates the cached disabled-enchantment set.
     * Must be called when the configuration is reloaded.
     */
    public void invalidateEnabledCache() {
        this.disabledEnchantmentIds = null;
    }

    /**
     * Fast check for whether an item has <em>any</em> enabled enchantment.
     * Only reads the BSON document keys — does <b>not</b> deserialize the full
     * {@link EnchantmentData}.  Used by the visuals system where we only need
     * a boolean answer, not levels.
     *
     * @return {@code true} if at least one enabled enchantment is present
     */
    public boolean hasAnyEnabledEnchantment(@Nullable ItemStack item) {
        if (item == null || item.isEmpty()) return false;

        BsonDocument bson = item.getFromMetadataOrNull(
                EnchantmentData.METADATA_KEY, Codec.BSON_DOCUMENT);
        if (bson == null || bson.isEmpty()) return false;

        for (String key : bson.keySet()) {
            EnchantmentType type = EnchantmentType.findByDisplayName(key);
            if (type == null) type = EnchantmentType.fromId(key);
            if (type != null && isEnchantmentEnabled(type)) {
                return true;
            }
        }
        return false;
    }

    /**
     * Checks if an item has a specific enchantment.
     * Optimized to avoid full BSON parsing of all enchantments.
     * Returns false if the enchantment is disabled in config.
     */
    public boolean hasEnchantment(@Nonnull ItemStack item, @Nonnull EnchantmentType type) {
        if (item == null || item.isEmpty()) {
            return false;
        }
        
        // Logical check: if it's disabled globally, acts as if it doesn't exist on the item
        if (!isEnchantmentEnabled(type)) {
            return false;
        }

        BsonDocument enchantmentsBson = item.getFromMetadataOrNull(
            EnchantmentData.METADATA_KEY,
            Codec.BSON_DOCUMENT
        );
        
        if (enchantmentsBson != null && !enchantmentsBson.isEmpty()) {
            // Check direct display name key
            if (enchantmentsBson.containsKey(type.getDisplayName())) {
                return true;
            }
            // Check ID key (compatibility)
            if (enchantmentsBson.containsKey(type.getId())) {
                return true;
            }
            return false;
        }

        // Fallback to legacy check
        EnchantmentData data = getEnchantmentsFromItem(item);
        return data.hasEnchantment(type);
    }

    /**
     * Gets the level of a specific enchantment on an item.
     * Optimized to avoid full BSON parsing of all enchantments.
     * Returns 0 if the enchantment is disabled in config.
     * 
     * @return The enchantment level, or 0 if the item doesn't have this enchantment
     */
    public int getEnchantmentLevel(@Nonnull ItemStack item, @Nonnull EnchantmentType type) {
        if (item == null || item.isEmpty()) {
            return 0;
        }
        
        // Logical check: if it's disabled globally, returns level 0 (no effect)
        if (!isEnchantmentEnabled(type)) {
            return 0;
        }

        BsonDocument enchantmentsBson = item.getFromMetadataOrNull(
            EnchantmentData.METADATA_KEY,
            Codec.BSON_DOCUMENT
        );

        if (enchantmentsBson != null && !enchantmentsBson.isEmpty()) {
            // Try display name first
            if (enchantmentsBson.containsKey(type.getDisplayName())) {
                return parseBsonLevel(enchantmentsBson.get(type.getDisplayName()));
            }
            // Try ID second
            if (enchantmentsBson.containsKey(type.getId())) {
                return parseBsonLevel(enchantmentsBson.get(type.getId()));
            }
            return 0;
        }

        // Fallback to legacy check
        EnchantmentData data = getEnchantmentsFromItem(item);
        return data.getLevel(type);
    }

    private int parseBsonLevel(org.bson.BsonValue value) {
        if (value == null) return 0;
        if (value.isInt32()) return value.asInt32().getValue();
        if (value.isInt64()) return (int) value.asInt64().getValue();
        if (value.isDouble()) return (int) Math.round(value.asDouble().getValue());
        return 0;
    }

    /**
     * Calculates the damage multiplier from all damage-related enchantments.
     * Optimized to use direct BSON key lookup instead of full deserialization.
     * 
     * @param item The item being used to deal damage
     * @return The total damage multiplier (1.0 = no change, 1.1 = +10%, etc.)
     */
    public double calculateDamageMultiplier(@Nullable ItemStack item) {
        // Direct BSON read — avoids deserializing all enchantments just to check Sharpness
        int sharpnessLevel = getEnchantmentLevel(item, EnchantmentType.SHARPNESS);
        if (sharpnessLevel > 0) {
            return 1.0 + sharpnessLevel * EnchantmentType.SHARPNESS.getEffectMultiplier();
        }
        return 1.0;
    }

    /**
     * Calculates the durability loss multiplier from Durability enchantment.
     * 
     * @param item The item losing durability
     * @return The durability loss multiplier (1.0 = normal, 0.5 = half loss, etc.)
     */
    public double calculateDurabilityMultiplier(@Nullable ItemStack item) {
        EnchantmentData data = getEnchantmentsFromItem(item);
        if (data.isEmpty()) {
            return 1.0;
        }
        
        // Check for Durability enchantment
        int durabilityLevel = data.getLevel(EnchantmentType.DURABILITY);
        if (durabilityLevel > 0 && isEnchantmentEnabled(EnchantmentType.DURABILITY)) {
            org.herolias.plugin.config.EnchantingConfig config = getConfig();
            return Math.max(0.0, 1.0 - (durabilityLevel * config.durabilityReductionPerLevel));
        }
        
        return 1.0;
    }

    /**
     * Calculates the mining speed multiplier from Efficiency enchantment.
     * Optimized to use direct BSON key lookup instead of full deserialization.
     *
     * @param item The tool being used to mine
     * @return The mining speed multiplier (1.0 = normal, 1.2 = +20%, etc.)
     */
    public double calculateMiningSpeedMultiplier(@Nullable ItemStack item) {
        // Direct BSON read — avoids deserializing all enchantments just to check Efficiency
        int efficiencyLevel = getEnchantmentLevel(item, EnchantmentType.EFFICIENCY);
        if (efficiencyLevel > 0) {
            return 1.0 + (efficiencyLevel * EnchantmentType.EFFICIENCY.getEffectMultiplier());
        }
        return 1.0;
    }

    /**
     * Calculates the swing speed multiplier from Efficiency enchantment.
     *
     * @param item The weapon/tool being swung
     * @return The swing speed multiplier (1.0 = normal, 1.1 = +10%, etc.)
     */
    public double calculateSwingSpeedMultiplier(@Nullable ItemStack item) {
        int efficiencyLevel = getEnchantmentLevel(item, EnchantmentType.EFFICIENCY);
        if (efficiencyLevel > 0) {
            return 1.0 + (efficiencyLevel * getConfig().efficiencySwingSpeedMultiplier);
        }
        return 1.0;
    }

    /**
     * Calculates the ability charge speed multiplier from Frenzy enchantment.
     *
     * @param level The level of the Frenzy enchantment
     * @return The charge speed multiplier (0.1 = +10%, etc.)
     */
    public double calculateFrenzySpeedMultiplier(int level) {
        if (level <= 0) return 0.0;
        return level * EnchantmentType.FRENZY.getEffectMultiplier();
    }


    /**
     * Calculates the projectile damage multiplier from ranged enchantments.
     *
     * @param weapon The ranged weapon used to fire the projectile
     * @param distance The distance between shooter and target
     * @return The projectile damage multiplier (1.0 = no change)
     */
    public double calculateProjectileDamageMultiplier(@Nullable ItemStack weapon, double distance) {
        if (weapon == null || weapon.isEmpty()) {
            return 1.0;
        }
        if (!ItemCategory.RANGED_WEAPON.equals(categorizeItem(weapon))) {
            return 1.0;
        }

        EnchantmentData data = getEnchantmentsFromItem(weapon);
        if (data.isEmpty()) {
            return 1.0;
        }

        int strengthLevel = data.getLevel(EnchantmentType.STRENGTH);
        if (!isEnchantmentEnabled(EnchantmentType.STRENGTH)) strengthLevel = 0;

        int eaglesEyeLevel = data.getLevel(EnchantmentType.EAGLES_EYE);
        if (!isEnchantmentEnabled(EnchantmentType.EAGLES_EYE)) eaglesEyeLevel = 0;

        return calculateProjectileDamageMultiplier(strengthLevel, eaglesEyeLevel, distance);
    }

    /**
     * Calculates the projectile range multiplier from Strength enchantment.
     *
     * @param weapon The ranged weapon used to fire the projectile
     * @return The projectile range multiplier (1.0 = no change)
     */
    public double calculateProjectileRangeMultiplier(@Nullable ItemStack weapon) {
        if (weapon == null || weapon.isEmpty()) {
            return 1.0;
        }
        if (!ItemCategory.RANGED_WEAPON.equals(categorizeItem(weapon))) {
            return 1.0;
        }

        int strengthLevel = getEnchantmentLevel(weapon, EnchantmentType.STRENGTH);
        return calculateProjectileRangeMultiplier(strengthLevel);
    }

    /**
     * Calculates projectile damage multipliers from raw enchantment levels.
     */
    public double calculateProjectileDamageMultiplier(int strengthLevel, int eaglesEyeLevel, double distance) {
        double multiplier = 1.0;
        org.herolias.plugin.config.EnchantingConfig config = getConfig();

        if (strengthLevel > 0) {
            multiplier += strengthLevel * EnchantmentType.STRENGTH.getEffectMultiplier();
        }

        if (eaglesEyeLevel > 0 && distance > 0.0) {
            double cappedDistance = Math.min(distance, EAGLES_EYE_MAX_DISTANCE);
            double bonus = cappedDistance * config.eaglesEyeDistanceBonusPerLevel * eaglesEyeLevel;
            multiplier += bonus;
        }

        return multiplier;
    }

    /**
     * Calculates projectile range multipliers from Strength level.
     */
    public double calculateProjectileRangeMultiplier(int strengthLevel) {
        if (strengthLevel <= 0) {
            return 1.0;
        }
        org.herolias.plugin.config.EnchantingConfig config = getConfig();
        return 1.0 + (strengthLevel * config.strengthRangeMultiplierPerLevel);
    }

    /**
     * Calculates the protection multiplier from Protection enchantment on armor.
     *
     * @param armorContainer The armor inventory container
     * @return The damage multiplier (1.0 = normal, 0.9 = 10% reduction, etc.)
     */
    public double calculateProtectionMultiplier(@Nullable ItemContainer armorContainer) {
        if (armorContainer == null) {
            return 1.0;
        }

        // Single check — if Protection is disabled globally, skip all armor slots.
        if (!isEnchantmentEnabled(EnchantmentType.PROTECTION)) {
            return 1.0;
        }

        double multiplier = 1.0;
        for (short slot = 0; slot < armorContainer.getCapacity(); slot = (short) (slot + 1)) {
            ItemStack armorPiece = armorContainer.getItemStack(slot);
            if (armorPiece == null || armorPiece.isEmpty()) {
                continue;
            }

            // Read BSON directly — enabled check already done above.
            BsonDocument enchBson = armorPiece.getFromMetadataOrNull(
                    EnchantmentData.METADATA_KEY, Codec.BSON_DOCUMENT);
            if (enchBson == null || enchBson.isEmpty()) continue;

            int level = 0;
            if (enchBson.containsKey(EnchantmentType.PROTECTION.getDisplayName())) {
                level = parseBsonLevel(enchBson.get(EnchantmentType.PROTECTION.getDisplayName()));
            } else if (enchBson.containsKey(EnchantmentType.PROTECTION.getId())) {
                level = parseBsonLevel(enchBson.get(EnchantmentType.PROTECTION.getId()));
            }

            if (level > 0) {
                double pieceMultiplier = 1.0 - (level * EnchantmentType.PROTECTION.getEffectMultiplier());
                multiplier *= Math.max(0.0, pieceMultiplier);
            }
        }

        return multiplier;
    }

    /**
     * Calculates the ranged protection multiplier from Ranged Protection enchantment on armor.
     * Reduces incoming projectile and magic damage.
     *
     * @param armorContainer The armor inventory container
     * @return The damage multiplier (1.0 = normal, 0.9 = 10% reduction, etc.)
     */
    public double calculateRangedProtectionMultiplier(@Nullable ItemContainer armorContainer) {
        if (armorContainer == null) {
            return 1.0;
        }

        if (!isEnchantmentEnabled(EnchantmentType.RANGED_PROTECTION)) {
            return 1.0;
        }

        double multiplier = 1.0;
        for (short slot = 0; slot < armorContainer.getCapacity(); slot = (short) (slot + 1)) {
            ItemStack armorPiece = armorContainer.getItemStack(slot);
            if (armorPiece == null || armorPiece.isEmpty()) {
                continue;
            }

            BsonDocument enchBson = armorPiece.getFromMetadataOrNull(
                    EnchantmentData.METADATA_KEY, Codec.BSON_DOCUMENT);
            if (enchBson == null || enchBson.isEmpty()) continue;

            int level = 0;
            if (enchBson.containsKey(EnchantmentType.RANGED_PROTECTION.getDisplayName())) {
                level = parseBsonLevel(enchBson.get(EnchantmentType.RANGED_PROTECTION.getDisplayName()));
            } else if (enchBson.containsKey(EnchantmentType.RANGED_PROTECTION.getId())) {
                level = parseBsonLevel(enchBson.get(EnchantmentType.RANGED_PROTECTION.getId()));
            }

            if (level > 0) {
                double pieceMultiplier = 1.0 - (level * EnchantmentType.RANGED_PROTECTION.getEffectMultiplier());
                multiplier *= Math.max(0.0, pieceMultiplier);
            }
        }

        return multiplier;
    }

    /**
     * Rolls for extra Fortune drops.
     *
     * @param fortuneLevel The Fortune level on the tool
     * @return The number of extra drop rolls to attempt
     */
    public int rollFortuneExtraRolls(int fortuneLevel) {
        if (fortuneLevel <= 0) {
            return 0;
        }

        int extraRolls = 0;
        double chancePerLevel = EnchantmentType.FORTUNE.getEffectMultiplier();
        ThreadLocalRandom random = ThreadLocalRandom.current();
        for (int i = 0; i < fortuneLevel; i++) {
            if (random.nextDouble() < chancePerLevel) {
                extraRolls++;
            }
        }
        return extraRolls;
    }



    /**
     * Stores projectile enchantment levels for later calculations.
     */
    /**
     * Stores projectile enchantment levels for later calculations.
     */
    public void storeProjectileEnchantments(@Nonnull UUID projectileUuid, int strengthLevel, int eaglesEyeLevel, int lootingLevel, int freezeLevel, int burnLevel, int eternalShotLevel) {
        if (strengthLevel <= 0 && eaglesEyeLevel <= 0 && lootingLevel <= 0 && freezeLevel <= 0 && burnLevel <= 0 && eternalShotLevel <= 0) {
            projectileEnchantmentsByUuid.remove(projectileUuid);
            return;
        }
        projectileEnchantmentsByUuid.put(projectileUuid, new ProjectileEnchantmentData(strengthLevel, eaglesEyeLevel, lootingLevel, freezeLevel, burnLevel, eternalShotLevel));
    }

    /**
     * Retrieves stored projectile enchantments (if any).
     */
    @Nullable
    public ProjectileEnchantmentData getProjectileEnchantments(@Nonnull UUID projectileUuid) {
        return projectileEnchantmentsByUuid.get(projectileUuid);
    }

    /**
     * Removes stored projectile enchantment data.
     */
    public void removeProjectileEnchantments(@Nonnull UUID projectileUuid) {
        projectileEnchantmentsByUuid.remove(projectileUuid);
    }

    /**
     * Stores projectile enchantments by network id (used by new projectile system).
     */
    public void storeProjectileEnchantments(int networkId, int strengthLevel, int eaglesEyeLevel, int lootingLevel, int freezeLevel, int burnLevel, int eternalShotLevel) {
        if (strengthLevel <= 0 && eaglesEyeLevel <= 0 && lootingLevel <= 0 && freezeLevel <= 0 && burnLevel <= 0 && eternalShotLevel <= 0) {
            projectileEnchantmentsByNetworkId.remove(networkId);
            return;
        }
        projectileEnchantmentsByNetworkId.put(networkId, new ProjectileEnchantmentData(strengthLevel, eaglesEyeLevel, lootingLevel, freezeLevel, burnLevel, eternalShotLevel));
    }

    /**
     * Retrieves stored projectile enchantments by network id.
     */
    @Nullable
    public ProjectileEnchantmentData getProjectileEnchantments(int networkId) {
        return projectileEnchantmentsByNetworkId.get(networkId);
    }

    /**
     * Removes projectile enchantments by network id.
     */
    public void removeProjectileEnchantments(int networkId) {
        projectileEnchantmentsByNetworkId.remove(networkId);
    }

    /**
     * Gets the smelting recipe registry (lazy-loaded).
     */
    public SmeltingRecipeRegistry getSmeltingRecipeRegistry() {
        return smeltingRecipeRegistry;
    }

    /**
     * Gets the cooking recipe registry for Campfire recipes (lazy-loaded).
     */
    public CookingRecipeRegistry getCookingRecipeRegistry() {
        return cookingRecipeRegistry;
    }

    /**
     * Determines if a block is a valid Fortune target.
     * Checks gather type, item tags, and block IDs.
     */
    public boolean isFortuneTarget(@Nonnull BlockType blockType, @Nonnull BlockBreakingDropType breaking) {
        // 1. Check Gather Type (Fastest fail)
        String gatherType = breaking.getGatherType();
        if (gatherType != null) {
            String lower = gatherType.toLowerCase();
            if (!lower.contains("rock") && !lower.contains("ore") && !lower.contains("crystal")) {
                return false;
            }
        }

        // 2. Check Result Item Tags (Most accurate)
        Item item = blockType.getItem();
        if (item != null && item.getData() != null) {
            if (hasTag(item, "Type", "Ore") || hasTag(item, "Type", "Crystal") || hasTag(item, "Family", "Crystal")) {
                return true;
            }
        }

        // 3. Check Block ID/Item ID naming conventions (Fallback)
        if (isBlockIdOreOrCrystal(blockType.getId())) {
            return true;
        }

        String itemId = breaking.getItemId();
        if (itemId != null && isOreOrCrystalItem(itemId)) {
            return true;
        }

        String dropListId = breaking.getDropListId();
        if (dropListId != null) {
            String lowerDrop = dropListId.toLowerCase();
            return lowerDrop.startsWith("ore_") || lowerDrop.contains("crystal");
        }

        return false;
    }

    private boolean isBlockIdOreOrCrystal(@Nonnull String blockId) {
        return oreOrCrystalCache.computeIfAbsent(blockId, id -> {
            String lowerBlockId = id.toLowerCase();
            return lowerBlockId.startsWith("ore_") || lowerBlockId.contains("crystal");
        });
    }

    public boolean isOreOrCrystalItem(@Nonnull String itemId) {
        return oreOrCrystalCache.computeIfAbsent(itemId, id -> {
            String lowerItemId = id.toLowerCase();
            return lowerItemId.startsWith("ore_")
                || lowerItemId.startsWith("ingredient_crystal")
                || lowerItemId.startsWith("rock_crystal_")
                || lowerItemId.contains("crystal_shard")
                || lowerItemId.contains("crystalshard");
        });
    }

    private boolean hasTag(@Nonnull Item item, @Nonnull String key, @Nonnull String value) {
        if (item.getData() == null) {
            return false;
        }
        String[] values = item.getData().getRawTags().get(key);
        if (values == null) {
            return false;
        }
        for (String entry : values) {
            if (value.equalsIgnoreCase(entry)) {
                return true;
            }
        }
        return false;
    }

    /**
     * Determines the category of an item based on its ID.
     * 
     * Note: This is a simplified implementation. In production, you would
     * want to query the item's actual properties or tags from the game.
     */
    /**
     * Determines the category of an item based on its ID.
     * Delegates to ItemCategoryManager.
     */
    public ItemCategory categorizeItem(String itemTypeId) {
        return ItemCategoryManager.getInstance().categorizeItem(itemTypeId);
    }

    /**
     * Determines the category of an item based on its ItemStack (w/ Metadata).
     * Delegates to ItemCategoryManager.
     */
    public ItemCategory categorizeItem(@Nonnull ItemStack item) {
        return ItemCategoryManager.getInstance().categorizeItem(item);
    }

    // Helper methods restored for compatibility, now delegating to ItemCategory logic where possible
    @Deprecated
    public boolean isPickaxeItem(@Nonnull String itemTypeId) {
        return ItemCategory.PICKAXE.equals(categorizeItem(itemTypeId));
    }

    @Deprecated
    public boolean isAxeItem(@Nonnull String itemTypeId) {
        return ItemCategory.AXE.equals(categorizeItem(itemTypeId));
    }

    @Deprecated
    public boolean isShovelItem(@Nonnull String itemTypeId) {
        return ItemCategory.SHOVEL.equals(categorizeItem(itemTypeId));
    }

    /**
     * Generates tooltip lines for an enchanted item.
     */
    public String[] getEnchantmentTooltip(@Nonnull ItemStack item) {
        EnchantmentData data = getEnchantmentsFromItem(item);
        if (data.isEmpty()) {
            return new String[0];
        }
        
        var enchantments = data.getAllEnchantments();
        java.util.List<String> lines = new java.util.ArrayList<>();
        
        for (var entry : enchantments.entrySet()) {
            if (isEnchantmentEnabled(entry.getKey())) {
                lines.add(entry.getKey().getFormattedName(entry.getValue()));
            }
        }
        
        return lines.toArray(new String[0]);
    }

    /**
     * Updates the item's visual indicators.
     * Currently a no-op as we use the Action Bar/Title system.
     * 
     * @param item The item to update
     * @return The item (unmodified)
     */
    public ItemStack updateItemVisuals(@Nonnull ItemStack item) {
        return item;
    }

    /**
     * Generates a display message for the item's enchantments.
     * Used for visual notifications (Title/Action Bar).
     */
    @javax.annotation.Nullable
    public com.hypixel.hytale.server.core.Message getEnchantmentDisplayMessage(@javax.annotation.Nonnull ItemStack item) {
        EnchantmentData data = getEnchantmentsFromItem(item);
        if (data.isEmpty()) {
            return null;
        }

        java.util.List<java.util.Map.Entry<EnchantmentType, Integer>> enabledEnchants = new java.util.ArrayList<>();
        for (java.util.Map.Entry<EnchantmentType, Integer> entry : data.getAllEnchantments().entrySet()) {
            if (isEnchantmentEnabled(entry.getKey())) {
                enabledEnchants.add(entry);
            }
        }
        
        if (enabledEnchants.isEmpty()) {
            return null;
        }
        
        // Build composite message
        com.hypixel.hytale.server.core.Message msg = null;
        for (int i = 0; i < enabledEnchants.size(); i++) {
            java.util.Map.Entry<EnchantmentType, Integer> entry = enabledEnchants.get(i);
            
            com.hypixel.hytale.server.core.Message part = com.hypixel.hytale.server.core.Message.translation(entry.getKey().getNameKey())
                     .insert(com.hypixel.hytale.server.core.Message.raw(" " + EnchantmentType.toRoman(entry.getValue())));
            
            if (msg == null) {
                msg = part;
            } else {
                msg = msg.insert(com.hypixel.hytale.server.core.Message.raw(" | ")).insert(part);
            }
        }
        
        return msg;
    }

    // --- Centralized Helper Methods for Systems ---

    /**
     * Record holding attacker and projectile references extracted from damage source.
     */
    public record DamageContext(@Nullable Ref<EntityStore> attackerRef, @Nullable Ref<EntityStore> projectileRef) {
        public boolean hasAttacker() { return attackerRef != null && attackerRef.isValid(); }
        public boolean hasProjectile() { return projectileRef != null && projectileRef.isValid(); }
    }

    /**
     * Extracts attacker and projectile references from a Damage source.
     * Centralizes the common pattern of checking EntitySource vs ProjectileSource.
     * 
     * @param damage The damage event
     * @param commandBuffer The command buffer for entity lookups
     * @return DamageContext with attacker and optional projectile refs
     */
    public DamageContext getDamageContext(@Nonnull Damage damage, @Nonnull CommandBuffer<EntityStore> commandBuffer) {
        Ref<EntityStore> attackerRef = null;
        Ref<EntityStore> projectileRef = null;

        if (damage.getSource() instanceof Damage.ProjectileSource projectileSource) {
            attackerRef = projectileSource.getRef();
            projectileRef = projectileSource.getProjectile();
        } else if (damage.getSource() instanceof Damage.EntitySource entitySource) {
            attackerRef = entitySource.getRef();
        }

        // Handle case where shooter IS the projectile entity
        if (projectileRef == null && attackerRef != null && isProjectileEntity(attackerRef, commandBuffer)) {
            projectileRef = attackerRef;
            attackerRef = getProjectileShooter(attackerRef, commandBuffer);
        }

        return new DamageContext(attackerRef, projectileRef);
    }

    /**
     * Gets the active blocking item (shield) for an entity.
     * Checks main hand first, then off-hand, respecting weapon priority.
     * 
     * @param entity The entity to check
     * @return The shield ItemStack if actively blocking, null otherwise
     */
    @Nullable
    public ItemStack getActiveBlocker(@Nullable LivingEntity entity) {
        if (entity == null) return null;
        
        Inventory inventory = entity.getInventory();
        if (inventory == null) return null;

        ItemStack mainHand = inventory.getItemInHand();
        if (mainHand != null && !mainHand.isEmpty()) {
            ItemCategory cat = categorizeItem(mainHand);
            if (cat == ItemCategory.SHIELD) return mainHand;
        }

        ItemStack offHand = inventory.getUtilityItem();
        if (offHand != null && !offHand.isEmpty()) {
            if (categorizeItem(offHand) == ItemCategory.SHIELD) return offHand;
        }
        return null;
    }

    public boolean isCrossbow(@Nullable ItemStack item) {
        if (item == null || item.isEmpty()) return false;
        // Basic check based on item ID naming convention as we don't have a specific category for Crossbows vs Bows yet
        // and they both fall under RANGED_WEAPON.
        String lowerId = item.getItemId().toLowerCase();
        return lowerId.contains("crossbow");
    }

    /**
     * Applies a status effect to an entity.
     * 
     * @param targetRef The entity to apply the effect to
     * @param effectId The effect ID (e.g., "Burn", "Freeze_I")
     * @param store The entity store
     * @param commandBuffer The command buffer
     * @return true if the effect was applied successfully
     */
    public boolean applyStatusEffect(@Nonnull Ref<EntityStore> targetRef, 
                                     @Nonnull String effectId,
                                     @Nonnull Store<EntityStore> store,
                                     @Nonnull CommandBuffer<EntityStore> commandBuffer) {
        if (targetRef == null || !targetRef.isValid()) return false;

        EffectControllerComponent effectController = store.getComponent(targetRef, EffectControllerComponent.getComponentType());
        if (effectController == null) return false;

        EntityEffect effect = EntityEffect.getAssetMap().getAsset(effectId);
        if (effect == null) {
            LOGGER.atWarning().log("Effect '" + effectId + "' not found in asset map");
            return false;
        }

        effectController.addEffect(targetRef, effect, commandBuffer);
        return true;
    }

    /**
     * Applies a status effect to an entity (event listener variant).
     * Use this overload when only a Store is available (no CommandBuffer).
     * 
     * @param targetRef The entity to apply the effect to
     * @param effectId The effect ID (e.g., "Night_Vision")
     * @param store The entity store (also serves as ComponentAccessor)
     * @return true if the effect was applied successfully
     */
    public boolean applyStatusEffect(@Nonnull Ref<EntityStore> targetRef, 
                                     @Nonnull String effectId,
                                     @Nonnull Store<EntityStore> store) {
        if (targetRef == null || !targetRef.isValid()) return false;

        EffectControllerComponent effectController = store.getComponent(targetRef, EffectControllerComponent.getComponentType());
        if (effectController == null) return false;

        EntityEffect effect = EntityEffect.getAssetMap().getAsset(effectId);
        if (effect == null) {
            LOGGER.atWarning().log("Effect '" + effectId + "' not found in asset map");
            return false;
        }

        effectController.addEffect(targetRef, effect, store);
        return true;
    }

    /**
     * Removes a status effect from an entity.
     * 
     * @param targetRef The entity to remove the effect from
     * @param effectId The effect ID (e.g., "Night_Vision")
     * @param store The entity store
     * @return true if the effect was removed successfully
     */
    public boolean removeStatusEffect(@Nonnull Ref<EntityStore> targetRef, 
                                      @Nonnull String effectId,
                                      @Nonnull Store<EntityStore> store) {
        if (targetRef == null || !targetRef.isValid()) return false;

        EffectControllerComponent effectController = store.getComponent(targetRef, EffectControllerComponent.getComponentType());
        if (effectController == null) return false;

        int effectIndex = EntityEffect.getAssetMap().getIndex(effectId);
        if (effectIndex == Integer.MIN_VALUE) return false;

        // Only remove if currently active
        if (!effectController.getActiveEffects().containsKey(effectIndex)) return false;

        effectController.removeEffect(targetRef, effectIndex, store);
        return true;
    }

    /**
     * Checks whether an entity currently has a specific status effect active.
     * 
     * @param targetRef The entity to check
     * @param effectId The effect ID (e.g., "Night_Vision")
     * @param store The entity store
     * @return true if the effect is currently active on the entity
     */
    public boolean hasActiveEffect(@Nonnull Ref<EntityStore> targetRef, 
                                   @Nonnull String effectId,
                                   @Nonnull Store<EntityStore> store) {
        if (targetRef == null || !targetRef.isValid()) return false;

        EffectControllerComponent effectController = store.getComponent(targetRef, EffectControllerComponent.getComponentType());
        if (effectController == null) return false;

        int effectIndex = EntityEffect.getAssetMap().getIndex(effectId);
        if (effectIndex == Integer.MIN_VALUE) return false;

        return effectController.getActiveEffects().containsKey(effectIndex);
    }
    
    // --- Existing Helper Methods for Systems ---

    /**
     * Safely retrieves the weapon currently held by an entity.
     * @return The held item stack, or null if empty/none.
     */
    @Nullable
    public ItemStack getWeaponFromEntity(@Nullable com.hypixel.hytale.server.core.entity.Entity entity) {
        if (entity instanceof LivingEntity living) {
            Inventory inventory = living.getInventory();
            if (inventory != null) {
                ItemStack weapon = inventory.getItemInHand();
                if (weapon != null && !weapon.isEmpty()) {
                    return weapon;
                }
            }
        }
        return null;
    }

    /**
     * Retrieves enchantment data associated with a projectile.
     */
    @Nullable
    public ProjectileEnchantmentData getProjectileEnchantmentData(@Nonnull Ref<EntityStore> projectileRef,
                                                                   @Nonnull CommandBuffer<EntityStore> commandBuffer) {
        if (projectileRef == null || !projectileRef.isValid()) return null;

        com.hypixel.hytale.server.core.modules.entity.tracker.NetworkId networkId = commandBuffer.getComponent(projectileRef, com.hypixel.hytale.server.core.modules.entity.tracker.NetworkId.getComponentType());
        if (networkId != null) {
            ProjectileEnchantmentData data = getProjectileEnchantments(networkId.getId());
            if (data != null) return data;
        }

        com.hypixel.hytale.server.core.entity.UUIDComponent uuidComponent = commandBuffer.getComponent(projectileRef, com.hypixel.hytale.server.core.entity.UUIDComponent.getComponentType());
        if (uuidComponent != null) {
            return getProjectileEnchantments(uuidComponent.getUuid());
        }
        return null;
    }

    /**
     * Helper to get shooter from a projectile using standard components and reflection fallback.
     */
    @Nullable
    public Ref<EntityStore> getProjectileShooter(@Nonnull Ref<EntityStore> projectileRef,
                                                  @Nonnull CommandBuffer<EntityStore> commandBuffer) {
        com.hypixel.hytale.server.core.modules.projectile.config.StandardPhysicsProvider standardPhysics = commandBuffer.getComponent(projectileRef, com.hypixel.hytale.server.core.modules.projectile.config.StandardPhysicsProvider.getComponentType());
        if (standardPhysics != null && standardPhysics.getCreatorUuid() != null) {
            return commandBuffer.getExternalData().getRefFromUUID(standardPhysics.getCreatorUuid());
        }

        com.hypixel.hytale.server.core.entity.entities.ProjectileComponent projectileComponent = commandBuffer.getComponent(projectileRef, com.hypixel.hytale.server.core.entity.entities.ProjectileComponent.getComponentType());
        if (projectileComponent != null && PROJECTILE_CREATOR_FIELD != null) {
            try {
                Object value = PROJECTILE_CREATOR_FIELD.get(projectileComponent);
                if (value instanceof java.util.UUID uuid) {
                    return commandBuffer.getExternalData().getRefFromUUID(uuid);
                }
            } catch (IllegalAccessException e) {
                // Ignore
            }
        }
        return null;
    }

    public boolean isPhysicalDamage(@Nullable com.hypixel.hytale.server.core.modules.entity.damage.DamageCause cause) {
        return checkDamageCause(cause, "Physical");
    }

    /**
     * Checks if a damage cause is ranged (projectile or magic) — excludes melee physical damage.
     * Used by Ranged Protection enchantment.
     */
    public boolean isRangedDamage(@Nullable com.hypixel.hytale.server.core.modules.entity.damage.DamageCause cause) {
        return checkDamageCause(cause, "Projectile");
    }

    public boolean isProjectileEntity(@Nonnull Ref<EntityStore> sourceRef,
                                      @Nonnull CommandBuffer<EntityStore> commandBuffer) {
        if (commandBuffer.getComponent(sourceRef, com.hypixel.hytale.server.core.modules.projectile.config.StandardPhysicsProvider.getComponentType()) != null) {
            return true;
        }
        return commandBuffer.getComponent(sourceRef, com.hypixel.hytale.server.core.entity.entities.ProjectileComponent.getComponentType()) != null;
    }

    public boolean isProjectileDamage(@Nullable com.hypixel.hytale.server.core.modules.entity.damage.DamageCause cause) {
        return checkDamageCause(cause, "Projectile") || checkDamageCause(cause, "Physical");
    }

    public boolean isFallDamage(@Nullable com.hypixel.hytale.server.core.modules.entity.damage.DamageCause cause) {
        return checkDamageCause(cause, "Fall");
    }
    
    private boolean checkDamageCause(@Nullable com.hypixel.hytale.server.core.modules.entity.damage.DamageCause cause, String targetId) {
        com.hypixel.hytale.server.core.modules.entity.damage.DamageCause current = cause;
        while (current != null) {
            if (targetId.equalsIgnoreCase(current.getId())) return true;
            String parentId = current.getInherits();
            if (parentId == null) return false;
            current = com.hypixel.hytale.server.core.modules.entity.damage.DamageCause.getAssetMap().getAsset(parentId);
        }
        return false;
    }
    
    /**
     * Calculates the extra drops from Fortune enchantment.
     * @param blockType The block being broken
     * @param breaking The breaking config of the block
     * @param fortuneLevel The level of Fortune enchantment
     * @return List of extra items dropped, or empty list if none.
     */
    public java.util.List<ItemStack> getFortuneDrops(@Nonnull com.hypixel.hytale.server.core.asset.type.blocktype.config.BlockType blockType,
                                                     @Nonnull com.hypixel.hytale.server.core.asset.type.blocktype.config.BlockBreakingDropType breaking,
                                                     int fortuneLevel) {
        java.util.List<ItemStack> extraDrops = new java.util.ArrayList<>();
        if (fortuneLevel <= 0) return extraDrops;
        
        if (!isFortuneTarget(blockType, breaking)) {
            return extraDrops;
        }

        int extraRolls = rollFortuneExtraRolls(fortuneLevel);
        if (extraRolls <= 0) {
            return extraDrops;
        }

        if (extraRolls > 0) {
            java.util.List<ItemStack> baseDrops = com.hypixel.hytale.server.core.modules.interaction.BlockHarvestUtils.getDrops(blockType, 1, breaking.getItemId(), breaking.getDropListId());
            
            for (ItemStack drop : baseDrops) {
                if (drop != null && !drop.isEmpty() && isOreOrCrystalItem(drop.getItemId())) {
                    ItemStack extraStack = drop.withQuantity(drop.getQuantity() * extraRolls);
                    if (extraStack != null) {
                        extraDrops.add(extraStack);
                    }
                }
            }
        }
        return extraDrops;
    }
    
    // Static field for projectile creator logic moved to top
    public record DamageEnchantments(int lootingLevel, int burnLevel) {}

    public DamageEnchantments resolveDamageEnchantments(@Nonnull com.hypixel.hytale.server.core.modules.entity.damage.Damage deathInfo,
                                                        @Nonnull com.hypixel.hytale.component.CommandBuffer<com.hypixel.hytale.server.core.universe.world.storage.EntityStore> commandBuffer,
                                                        @Nonnull com.hypixel.hytale.component.Ref<com.hypixel.hytale.server.core.universe.world.storage.EntityStore> targetRef) {
        com.hypixel.hytale.server.core.modules.entity.damage.Damage.Source source = deathInfo.getSource();
        
        // 1. Check standard source (Direct hit or Projectile)
        if (source instanceof com.hypixel.hytale.server.core.modules.entity.damage.Damage.EntitySource) {
            int lootingLevel = 0;
            int burnLevel = 0;
            
            // Check projectile enchantment data first (for ranged weapons)
            if (source instanceof com.hypixel.hytale.server.core.modules.entity.damage.Damage.ProjectileSource projectileSource) {
                com.hypixel.hytale.component.Ref<com.hypixel.hytale.server.core.universe.world.storage.EntityStore> projectileRef = projectileSource.getProjectile();
                ProjectileEnchantmentData data = getProjectileEnchantmentData(projectileRef, commandBuffer);
                if (data != null) {
                    lootingLevel = data.getLootingLevel();
                    burnLevel = data.getBurnLevel();
                }
            }

            if (lootingLevel > 0 || burnLevel > 0) {
                return new DamageEnchantments(lootingLevel, burnLevel);
            }

            // Check attacker's weapon for Burn/Looting enchantment (melee)
            com.hypixel.hytale.component.Ref<com.hypixel.hytale.server.core.universe.world.storage.EntityStore> attackerRef = ((com.hypixel.hytale.server.core.modules.entity.damage.Damage.EntitySource) source).getRef();
            if (attackerRef != null && attackerRef.isValid()) {
                com.hypixel.hytale.server.core.entity.Entity attackerEntity = com.hypixel.hytale.server.core.entity.EntityUtils.getEntity(attackerRef, commandBuffer);
                ItemStack weapon = getWeaponFromEntity(attackerEntity);
                if (weapon != null) {
                    return new DamageEnchantments(
                        getEnchantmentLevel(weapon, EnchantmentType.LOOTING),
                        getEnchantmentLevel(weapon, EnchantmentType.BURN)
                    );
                }
            }
        }
        
        // 2. Fallback: Check stored burn data (Indirect/DoT death)
        com.hypixel.hytale.server.core.entity.UUIDComponent uuidComp = commandBuffer.getComponent(targetRef, com.hypixel.hytale.server.core.entity.UUIDComponent.getComponentType());
        if (uuidComp != null) {
            ProjectileEnchantmentData data = getBurnEnchantments(uuidComp.getUuid());
            if (data != null) {
                return new DamageEnchantments(data.getLootingLevel(), data.getBurnLevel());
            }
        }
        
        return new DamageEnchantments(0, 0);
    }

    public boolean isTool(@Nullable ItemStack item) {
        return categorizeItem(item).isTool();
    }

    public boolean isShield(@Nullable ItemStack itemStack) {
        return categorizeItem(itemStack).isShield();
    }

    /**
     * Spawns item drops at the specified position.
     * 
     * @param commandBuffer The command buffer to use for spawning.
     * @param drops The list of items to drop.
     * @param position The position to spawn the drops at.
     */
    public void spawnDrops(@Nonnull com.hypixel.hytale.component.CommandBuffer<com.hypixel.hytale.server.core.universe.world.storage.EntityStore> commandBuffer,
                           @Nonnull java.util.List<ItemStack> drops,
                           @Nonnull com.hypixel.hytale.math.vector.Vector3d position) {
        spawnDrops(commandBuffer, drops, position, com.hypixel.hytale.math.vector.Vector3f.ZERO);
    }

    /**
     * Spawns item drops at the specified position with an initial rotation/velocity variance.
     * 
     * @param commandBuffer The command buffer to use for spawning.
     * @param drops The list of items to drop.
     * @param position The position to spawn the drops at.
     * @param rotation The rotation to apply to the drops (affects initial velocity).
     */
    public void spawnDrops(@Nonnull com.hypixel.hytale.component.CommandBuffer<com.hypixel.hytale.server.core.universe.world.storage.EntityStore> commandBuffer,
                           @Nonnull java.util.List<ItemStack> drops,
                           @Nonnull com.hypixel.hytale.math.vector.Vector3d position,
                           @Nonnull com.hypixel.hytale.math.vector.Vector3f rotation) {
        if (drops == null || drops.isEmpty()) return;

        com.hypixel.hytale.component.Holder<com.hypixel.hytale.server.core.universe.world.storage.EntityStore>[] itemEntities =
            com.hypixel.hytale.server.core.modules.entity.item.ItemComponent.generateItemDrops(
                commandBuffer,
                drops,
                position,
                rotation
            );

        if (itemEntities.length > 0) {
            commandBuffer.addEntities(itemEntities, com.hypixel.hytale.component.AddReason.SPAWN);
        }
    }
}
