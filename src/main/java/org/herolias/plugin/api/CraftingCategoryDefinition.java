package org.herolias.plugin.api;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Represents a crafting category (tab) in the Enchanting Table.
 * <p>
 * Built-in categories are pre-registered. Addon mods can register custom
 * categories
 * via {@link EnchantmentApi#registerCraftingCategory(String, String, String)}.
 * <p>
 * The registry is thread-safe: addons may register from their own setup while
 * other plugins read it. {@link #values()} preserves registration order.
 */
public class CraftingCategoryDefinition {

    private static final Map<String, CraftingCategoryDefinition> REGISTRY = new ConcurrentHashMap<>();
    /** Registration order, since ConcurrentHashMap does not keep insertion order. */
    private static final List<CraftingCategoryDefinition> ORDERED = new CopyOnWriteArrayList<>();

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
        put(new CraftingCategoryDefinition(id, name, null, true));
    }

    private static void put(@Nonnull CraftingCategoryDefinition def) {
        if (REGISTRY.putIfAbsent(def.categoryId, def) != null) {
            throw new IllegalArgumentException("Crafting category already registered: '" + def.categoryId + "'");
        }
        ORDERED.add(def);
    }

    /**
     * Registers a custom crafting category.
     *
     * @throws IllegalArgumentException if the category ID is blank or already registered
     */
    public static CraftingCategoryDefinition register(@Nonnull String categoryId,
            @Nonnull String displayName,
            @Nullable String iconPath) {
        if (categoryId == null || categoryId.isBlank()) {
            throw new IllegalArgumentException("Crafting category ID must not be blank");
        }
        if (displayName == null || displayName.isBlank()) {
            throw new IllegalArgumentException("Crafting category display name must not be blank");
        }
        CraftingCategoryDefinition def = new CraftingCategoryDefinition(categoryId, displayName, iconPath, false);
        put(def);
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

    /** Returns all registered categories in registration order (read-only snapshot). */
    public static Collection<CraftingCategoryDefinition> values() {
        return Collections.unmodifiableList(ORDERED);
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
