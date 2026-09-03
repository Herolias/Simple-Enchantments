package org.herolias.plugin.enchantment;

import com.hypixel.hytale.assetstore.event.LoadedAssetsEvent;
import com.hypixel.hytale.assetstore.map.DefaultAssetMap;
import com.hypixel.hytale.codec.ExtraInfo;
import com.hypixel.hytale.logger.HytaleLogger;
import com.hypixel.hytale.protocol.ItemArmorSlot;
import com.hypixel.hytale.server.core.asset.type.item.config.Item;
import com.hypixel.hytale.server.core.asset.type.item.config.ItemAppearanceCondition;
import com.hypixel.hytale.server.core.asset.type.item.config.ItemArmor;
import org.bson.BsonArray;
import org.bson.BsonDocument;
import org.bson.BsonDouble;
import org.bson.BsonString;
import org.herolias.plugin.SimpleEnchanting;
import org.herolias.plugin.engravingtable.EngravingTableColorOption;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.lang.reflect.Field;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Injects the enchantment glow {@link ItemAppearanceCondition}s into every
 * enchantable item at load time.
 * <p>
 * Doing this at runtime via {@link LoadedAssetsEvent} instead of shipping item
 * overrides preserves all vanilla item properties and merges with other mods'
 * changes. Conditions are decoded through {@link ItemAppearanceCondition#CODEC}
 * from the same document shape the JSON would use and shared between items
 * (they are read-only). The only reflective access left is the write to
 * {@code Item.itemAppearanceConditions}, which has no setter; the map is copied
 * once per item because templates and their children share the instance.
 */
public final class EnchantmentGlowInjector {

    private static final HytaleLogger LOGGER = HytaleLogger.forEnclosingClass();

    // Stat keys the conditions are mapped to (must match the EntityStatType assets).
    private static final String STAT_GLOW_PRIMARY = "EnchantmentGlow_Primary";
    private static final String STAT_GLOW_HEAD = "EnchantmentGlow_Head";
    private static final String STAT_GLOW_CHEST = "EnchantmentGlow_Chest";
    private static final String STAT_GLOW_HANDS = "EnchantmentGlow_Hands";
    private static final String STAT_GLOW_LEGS = "EnchantmentGlow_Legs";
    private static final String STAT_GLOW_SHIELD = "EnchantmentGlow_Shield";

    /** Keywords identifying items that use the small glow effect. */
    private static final Set<String> SMALL_WEAPON_KEYWORDS = Set.of(
            "dagger", "mace", "shortbow", "short_bow", "shovel", "battleaxe", "longsword",
            "staff", "spellbook");

    /** The one field without a setter. */
    @Nullable
    private static final Field ITEM_APPEARANCE_CONDITIONS_FIELD = resolveConditionsField();

    /** Decoded condition arrays per (smallGlow, includeSingleGlow); shared across items. */
    private static final Map<Integer, ItemAppearanceCondition[]> CONDITION_CACHE = new ConcurrentHashMap<>();

    private EnchantmentGlowInjector() {
    }

    @Nullable
    private static Field resolveConditionsField() {
        try {
            Field field = Item.class.getDeclaredField("itemAppearanceConditions");
            field.setAccessible(true);
            return field;
        } catch (NoSuchFieldException | RuntimeException e) {
            LOGGER.atSevere().withCause(e).log("Item.itemAppearanceConditions is not accessible; enchantment glow disabled");
            return null;
        }
    }

    public static void registerEventListener(@Nonnull SimpleEnchanting plugin) {
        plugin.getEventRegistry().register(
                LoadedAssetsEvent.class,
                Item.class,
                EnchantmentGlowInjector::onItemsLoaded);
        LOGGER.atInfo().log("EnchantmentGlowInjector registered");
    }

    private static void onItemsLoaded(@Nonnull LoadedAssetsEvent<String, Item, DefaultAssetMap<String, Item>> event) {
        if (ITEM_APPEARANCE_CONDITIONS_FIELD == null) {
            return;
        }
        int weapons = 0;
        int tools = 0;
        int shields = 0;
        int armor = 0;
        int failed = 0;

        ItemCategoryManager categoryManager = ItemCategoryManager.getInstance();
        for (Map.Entry<String, Item> entry : event.getLoadedAssets().entrySet()) {
            String itemId = entry.getKey();
            Item item = entry.getValue();
            ItemCategory category = categoryManager.categorizeItem(itemId, item);
            if (category == ItemCategory.UNKNOWN) {
                continue;
            }

            String statKey;
            boolean smallGlow;
            boolean includeSingleGlow;
            if (category.isShield()) {
                statKey = STAT_GLOW_SHIELD;
                smallGlow = true;
                includeSingleGlow = false;
            } else if (category.isWeapon() || category.isTool()) {
                statKey = STAT_GLOW_PRIMARY;
                smallGlow = usesSmallGlow(item, itemId);
                includeSingleGlow = true;
            } else if (category.isArmor()) {
                statKey = armorGlowStat(item);
                if (statKey == null) {
                    continue;
                }
                smallGlow = usesSmallGlow(item, itemId);
                includeSingleGlow = false;
            } else {
                continue;
            }

            try {
                if (inject(item, statKey, conditionsFor(smallGlow, includeSingleGlow))) {
                    item.invalidatePacketCache();
                    if (category.isShield()) {
                        shields++;
                    } else if (category.isWeapon()) {
                        weapons++;
                    } else if (category.isTool()) {
                        tools++;
                    } else {
                        armor++;
                    }
                }
            } catch (IllegalAccessException | RuntimeException e) {
                failed++;
                LOGGER.atWarning().withCause(e).log("Failed to inject glow conditions into item %s", itemId);
            }
        }

        if (weapons + tools + shields + armor + failed > 0) {
            LOGGER.atInfo().log("EnchantmentGlowInjector: glow conditions injected into %d weapons, %d tools, %d shields, %d armor pieces (%d failed)",
                    weapons, tools, shields, armor, failed);
        }
    }

    /** Small glow for items whose categories or id name a small weapon type. */
    private static boolean usesSmallGlow(@Nonnull Item item, @Nonnull String itemId) {
        if (item.getCategories() != null) {
            for (String category : item.getCategories()) {
                String lower = category.toLowerCase();
                for (String keyword : SMALL_WEAPON_KEYWORDS) {
                    if (lower.contains(keyword)) {
                        return true;
                    }
                }
            }
        }
        String idLower = itemId.toLowerCase();
        for (String keyword : SMALL_WEAPON_KEYWORDS) {
            if (idLower.contains(keyword)) {
                return true;
            }
        }
        return false;
    }

    /** Glow stat for the slot the armor piece is worn in, or null when the item is not wearable. */
    @Nullable
    private static String armorGlowStat(@Nonnull Item item) {
        ItemArmor armor = item.getArmor();
        if (armor == null || armor.getArmorSlot() == null) {
            return null;
        }
        return switch (armor.getArmorSlot()) {
            case Head -> STAT_GLOW_HEAD;
            case Chest -> STAT_GLOW_CHEST;
            case Hands -> STAT_GLOW_HANDS;
            case Legs -> STAT_GLOW_LEGS;
        };
    }

    /**
     * Merges the glow conditions into the item's map under the stat key with a
     * single reflective write. Returns false when the item already carries
     * them (nothing changed).
     */
    @SuppressWarnings("unchecked")
    private static boolean inject(@Nonnull Item item, @Nonnull String statKey,
            @Nonnull ItemAppearanceCondition[] glowConditions) throws IllegalAccessException {
        if (glowConditions.length == 0) {
            return false;
        }
        Map<String, ItemAppearanceCondition[]> existing = (Map<String, ItemAppearanceCondition[]>) ITEM_APPEARANCE_CONDITIONS_FIELD
                .get(item);
        ItemAppearanceCondition[] current = existing != null ? existing.get(statKey) : null;
        if (current != null && alreadyInjected(current, glowConditions)) {
            return false;
        }

        ItemAppearanceCondition[] combined;
        if (current == null || current.length == 0) {
            combined = glowConditions;
        } else {
            combined = Arrays.copyOf(current, current.length + glowConditions.length);
            System.arraycopy(glowConditions, 0, combined, current.length, glowConditions.length);
        }

        // Copy: templates and their children share the same map instance.
        Map<String, ItemAppearanceCondition[]> merged = existing != null ? new HashMap<>(existing) : new HashMap<>();
        merged.put(statKey, combined);
        ITEM_APPEARANCE_CONDITIONS_FIELD.set(item, merged);
        return true;
    }

    private static boolean alreadyInjected(@Nonnull ItemAppearanceCondition[] current,
            @Nonnull ItemAppearanceCondition[] glowConditions) {
        for (ItemAppearanceCondition condition : current) {
            if (condition == glowConditions[0]) {
                return true;
            }
        }
        return false;
    }

    /** Conditions for every engraving colour, decoded once per (small, single) combination. */
    @Nonnull
    private static ItemAppearanceCondition[] conditionsFor(boolean smallGlow, boolean includeSingleGlow) {
        int key = (smallGlow ? 1 : 0) | (includeSingleGlow ? 2 : 0);
        return CONDITION_CACHE.computeIfAbsent(key, k -> buildConditions(smallGlow, includeSingleGlow));
    }

    @Nonnull
    private static ItemAppearanceCondition[] buildConditions(boolean smallGlow, boolean includeSingleGlow) {
        EngravingTableColorOption[] colors = EngravingTableColorOption.values();
        ItemAppearanceCondition[] conditions = new ItemAppearanceCondition[colors.length * (includeSingleGlow ? 2 : 1)];
        int i = 0;
        for (EngravingTableColorOption color : colors) {
            conditions[i++] = createGlowCondition(color.getGlowVfxId(smallGlow, false), color.getGlowStatValue(false));
            if (includeSingleGlow) {
                conditions[i++] = createGlowCondition(color.getGlowVfxId(smallGlow, true), color.getGlowStatValue(true));
            }
        }
        return conditions;
    }

    /**
     * Decodes a condition that applies {@code vfxId} while the stat equals
     * {@code targetValue}, from the same document an item JSON would contain:
     * <pre>{ "Condition": [v, v], "ConditionValueType": "Absolute", "ModelVFXId": id }</pre>
     */
    @Nonnull
    private static ItemAppearanceCondition createGlowCondition(@Nonnull String vfxId, float targetValue) {
        BsonDocument document = new BsonDocument()
                .append("Condition", new BsonArray(List.of(new BsonDouble(targetValue), new BsonDouble(targetValue))))
                .append("ConditionValueType", new BsonString("Absolute"))
                .append("ModelVFXId", new BsonString(vfxId));
        // Items load before the ModelVFX assets they reference, so the codec's
        // asset-existence validator must collect instead of throw. This is the same
        // collecting ValidationResults the server's own AssetStore uses while loading
        // (see AssetStore.decode); the referenced glow VFX ship in this mod's pack.
        ExtraInfo extraInfo = new ExtraInfo(Integer.MAX_VALUE,
                com.hypixel.hytale.codec.validation.ValidationResults::new);
        ItemAppearanceCondition condition = ItemAppearanceCondition.CODEC.decode(document, extraInfo);
        if (extraInfo.getValidationResults().hasFailed()) {
            LOGGER.atFine().log("Deferred validation for glow condition %s: %s", vfxId,
                    extraInfo.getValidationResults().getResults());
        }
        return condition;
    }
}
