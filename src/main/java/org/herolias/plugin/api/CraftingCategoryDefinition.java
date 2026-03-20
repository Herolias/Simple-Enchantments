package org.herolias.plugin.api;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Collection;

/**
 * Represents a crafting category (tab) in the Enchanting Table.
 * <p>
 * Built-in categories are pre-registered. Addon mods can register custom
 * categories
 * via {@link EnchantmentApi#registerCraftingCategory(String, String, String)}.
 */
public class CraftingCategoryDefinition {

    private static final Map<String, CraftingCategoryDefinition> REGISTRY = new LinkedHashMap<>();

    // Pre-register built-in categories
    static {
        registerBuiltIn("Enchanting_Melee", "Melee");
        registerBuiltIn("Enchanting_Ranged", "Ranged");
        registerBuiltIn("Enchanting_Armor", "Armor");
        registerBuiltIn("Enchanting_Shield", "Shield");
        registerBuiltIn("Enchanting_Staff", "Staff");
        registerBuiltIn("Enchanting_Tools", "Tools");
    }

    private final String categoryId;
    private final String displayName;
    private final String iconPath; // null = default icon
    private final boolean builtIn;

    private CraftingCategoryDefinition(String categoryId, String displayName, String iconPath, boolean builtIn) {
        this.categoryId = categoryId;
        this.displayName = displayName;
        this.iconPath = iconPath;
        this.builtIn = builtIn;
    }

    private static void registerBuiltIn(String id, String name) {
        REGISTRY.put(id, new CraftingCategoryDefinition(id, name, null, true));
    }

    /**
     * Registers a custom crafting category.
     *
     * @throws IllegalArgumentException if the category ID is already registered
     */
    public static CraftingCategoryDefinition register(@Nonnull String categoryId,
            @Nonnull String displayName,
            @Nullable String iconPath) {
        if (REGISTRY.containsKey(categoryId)) {
            throw new IllegalArgumentException("Crafting category already registered: '" + categoryId + "'");
        }
        CraftingCategoryDefinition def = new CraftingCategoryDefinition(categoryId, displayName, iconPath, false);
        REGISTRY.put(categoryId, def);
        return def;
    }

    /** Gets a registered category by ID, or null if not found. */
    @Nullable
    public static CraftingCategoryDefinition get(@Nonnull String categoryId) {
        return REGISTRY.get(categoryId);
    }

    /** Checks if a category ID is registered. */
    public static boolean exists(@Nonnull String categoryId) {
        return REGISTRY.containsKey(categoryId);
    }

    /** Returns all registered categories. */
    public static Collection<CraftingCategoryDefinition> values() {
        return REGISTRY.values();
    }

    public String getCategoryId() {
        return categoryId;
    }

    public String getDisplayName() {
        return displayName;
    }

    @Nullable
    public String getIconPath() {
        return iconPath;
    }

    public boolean isBuiltIn() {
        return builtIn;
    }
}
