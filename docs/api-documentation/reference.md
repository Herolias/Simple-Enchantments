---
title: "API Reference"
order: 9
published: true
draft: false
---

This is a compact reference for the public API classes most add-ons use.

## EnchantmentApiProvider

| Method | Returns | Notes |
|---|---|---|
| `EnchantmentApiProvider.get()` | `@Nullable EnchantmentApi` | Returns `null` if Simple Enchantments has not initialized. Optional integrations must guard this call. |

`EnchantmentApiProvider.register(api)` is for Simple Enchantments itself. Add-ons should not call it.

## EnchantmentApi

| Method | Returns | Notes |
|---|---|---|
| `addEnchantment(ItemStack item, String enchantmentId, int level)` | `ItemStack` | Adds an enchantment and returns the updated item. Throws `IllegalArgumentException` for unknown IDs. Returns the original item if normal application rules fail. |
| `removeEnchantment(ItemStack item, String enchantmentId)` | `ItemStack` | Removes an enchantment and returns the updated item. Returns the original item if missing or unknown. |
| `getEnchantmentLevel(@Nullable ItemStack item, String enchantmentId)` | `int` | Returns `0` if the item is null, empty, unknown, or not enchanted with that ID. |
| `hasEnchantment(@Nullable ItemStack item, String enchantmentId)` | `boolean` | Returns whether the item has that enchantment. |
| `getEnchantments(@Nullable ItemStack item)` | `Map<String, Integer>` | Returns all enchantment IDs and levels on the item. |
| `equippedItemEnchantments(Player player)` | `Map<String, Integer>` | Scans main hand, utility/off-hand, and armor slots. Keeps the highest level for duplicate enchantments. |
| `registerItemToCategory(String itemId, String categoryId)` | `void` | Maps one item ID to an existing category. API mappings override config mappings. |
| `registerCategoryByFamily(String categoryId, String family)` | `ItemCategory` | Creates or reuses a category and maps a Hytale item family to it. |
| `registerCategoryByItems(String categoryId, String... itemIds)` | `ItemCategory` | Creates or reuses a category and maps specific item IDs to it. |
| `getCategory(String categoryId)` | `@Nullable ItemCategory` | Looks up a category by ID. |
| `registerEnchantment(String id, String displayName)` | `EnchantmentBuilder` | Starts building a custom enchantment. Add-on IDs must be namespaced with `:`. |
| `getRegisteredEnchantment(String id)` | `@Nullable EnchantmentType` | Looks up a registered enchantment. |
| `isEnchantmentRegistered(String id)` | `boolean` | Checks if an enchantment ID exists. |
| `addConflict(String enchantmentId1, String enchantmentId2)` | `void` | Declares two enchantments mutually exclusive. |
| `registerCraftingCategory(String categoryId, String displayName, @Nullable String iconPath)` | `void` | Adds an Enchanting Table tab. Throws `IllegalArgumentException` if the crafting category ID is already registered. |

## EnchantmentBuilder

| Method | Returns | Notes |
|---|---|---|
| `description(String description)` | `EnchantmentBuilder` | Sets the enchantment description. |
| `maxLevel(int maxLevel)` | `EnchantmentBuilder` | Must be at least `1`. |
| `requiresDurability(boolean value)` | `EnchantmentBuilder` | Restricts the enchantment to durable items. |
| `legendary(boolean value)` | `EnchantmentBuilder` | Marks the enchantment as legendary. |
| `modDisplayName(String name)` | `EnchantmentBuilder` | Sets the add-on display name used in UI text. |
| `multiplierPerLevel(double multiplier)` | `EnchantmentBuilder` | Adds the primary configurable multiplier. |
| `multiplierPerLevel(double multiplier, String label)` | `EnchantmentBuilder` | Adds the primary multiplier with a human-readable label. |
| `addMultiplier(String key, double defaultValue, String label)` | `EnchantmentBuilder` | Adds a secondary configurable multiplier. |
| `scale(ScaleType scaleType)` | `EnchantmentBuilder` | Uses a predefined scaling curve. |
| `scale(double exponent)` | `EnchantmentBuilder` | Uses `level^exponent * multiplierPerLevel`. |
| `scale(IntToDoubleFunction function)` | `EnchantmentBuilder` | Uses a custom total-value function for each level. |
| `bonusDescription(String template)` | `EnchantmentBuilder` | Tooltip template. Use `{amount}`. |
| `walkthrough(String text)` | `EnchantmentBuilder` | `/enchanting` walkthrough text. Use `{amount}`. |
| `craftingCategory(String category)` | `EnchantmentBuilder` | Sets the default scroll crafting tab. |
| `scroll(int level)` | `ScrollBuilder` | Starts a scroll definition for one level. |
| `appliesTo(ItemCategory... categories)` | `EnchantmentBuilder` | Adds allowed item categories. Required before `build()`. |
| `build()` | `EnchantmentType` | Registers and returns the enchantment. |

## ScrollBuilder

| Method | Returns | Notes |
|---|---|---|
| `quality(String quality)` | `ScrollBuilder` | Common values: `Common`, `Uncommon`, `Rare`, `Epic`, `Legendary`. |
| `craftingTier(int tier)` | `ScrollBuilder` | Must be `1` to `4`. |
| `craftingCategory(String category)` | `ScrollBuilder` | Overrides the tab for this scroll. |
| `ingredient(String itemId, int quantity)` | `ScrollBuilder` | Adds one recipe ingredient. Quantity must be at least `1`. |
| `icon(String iconPath)` | `ScrollBuilder` | Overrides the scroll icon. |
| `model(String modelPath)` | `ScrollBuilder` | Overrides the scroll model. |
| `texture(String texturePath)` | `ScrollBuilder` | Overrides the scroll texture. |
| `iconProperties(float scale, float x, float y, float rotX, float rotY, float rotZ)` | `ScrollBuilder` | Overrides icon rendering properties. |
| `end()` | `EnchantmentBuilder` | Adds the scroll to the parent enchantment and returns to the enchantment builder. |
| `build()` | `ScrollDefinition` | Builds a standalone scroll definition. |

## ScrollDefinition

| Method | Returns | Notes |
|---|---|---|
| `getLevel()` | `int` | Scroll level. |
| `getQuality()` | `String` | Quality string such as `Uncommon` or `Rare`. |
| `getCraftingTier()` | `int` | Required Enchanting Table tier. |
| `getCraftingCategory()` | `@Nullable String` | Per-scroll crafting tab override. |
| `getRecipe()` | `List<ScrollDefinition.Ingredient>` | Recipe ingredients. |
| `getIcon()` | `@Nullable String` | Optional icon override. |
| `getModel()` | `@Nullable String` | Optional model override. |
| `getTexture()` | `@Nullable String` | Optional texture override. |
| `getIconProperties()` | `IconProperties` | Icon transform values. |

`ScrollDefinition.Ingredient` exposes `getItemId()` and `getQuantity()`. `ScrollDefinition.IconProperties` exposes scale, translation, and rotation getters.

## ScaleType

| Value | Formula |
|---|---|
| `LINEAR` | `level * multiplier` |
| `QUADRATIC` | `level * level * multiplier` |
| `DIMINISHING` | `sqrt(level) * multiplier` |
| `EXPONENTIAL` | `(2^level - 1) * multiplier` |
| `LOGARITHMIC` | `ln(level + 1) * multiplier` |

## CraftingCategoryDefinition

Most add-ons use `api.registerCraftingCategory(...)` instead of this class directly.

| Method | Returns | Notes |
|---|---|---|
| `CraftingCategoryDefinition.get(categoryId)` | `@Nullable CraftingCategoryDefinition` | Looks up a crafting tab definition. |
| `CraftingCategoryDefinition.exists(categoryId)` | `boolean` | Checks whether a crafting tab ID is registered. |
| `CraftingCategoryDefinition.values()` | `Collection<CraftingCategoryDefinition>` | Returns built-in and add-on crafting categories. |
| `getCategoryId()` | `String` | Crafting tab ID. |
| `getDisplayName()` | `String` | Display name registered for the tab. |
| `getIconPath()` | `@Nullable String` | Icon path or null for the default icon. |
| `isBuiltIn()` | `boolean` | Whether the category is one of Simple Enchantments' built-in tabs. |

## MultiplierDefinition

`MultiplierDefinition` is a Java record with `key()`, `defaultValue()`, and `labelKey()`. Add-ons usually create these through `multiplierPerLevel(...)` and `addMultiplier(...)` rather than constructing the record directly.

## ItemCategory

Built-in constants:

```java
ItemCategory.MELEE_WEAPON
ItemCategory.RANGED_WEAPON
ItemCategory.TOOL
ItemCategory.PICKAXE
ItemCategory.SHOVEL
ItemCategory.AXE
ItemCategory.SHIELD
ItemCategory.HELMET
ItemCategory.CHESTPLATE
ItemCategory.LEGS
ItemCategory.ARMOR
ItemCategory.GLOVES
ItemCategory.STAFF
ItemCategory.STAFF_MANA
ItemCategory.STAFF_ESSENCE
ItemCategory.UNKNOWN
```

`ItemCategory.BOOTS` is a deprecated alias for `ItemCategory.LEGS`.

Useful methods:

| Method | Returns |
|---|---|
| `getId()` | `String` |
| `isWeapon()` | `boolean` |
| `isArmor()` | `boolean` |
| `isTool()` | `boolean` |
| `isShield()` | `boolean` |
| `isMelee()` | `boolean` |
| `isRanged()` | `boolean` |
| `isEnchantable()` | `boolean` |

## EnchantmentType

Common methods for add-ons:

| Method | Returns | Notes |
|---|---|---|
| `getId()` | `String` | The enchantment ID. |
| `getDisplayName()` | `String` | Human-readable name. |
| `getDescription()` | `String` | Description text. |
| `getMaxLevel()` | `int` | Maximum supported level. |
| `requiresDurability()` | `boolean` | Whether the enchantment requires durable items. |
| `isLegendary()` | `boolean` | Whether the enchantment is legendary. |
| `isBuiltIn()` | `boolean` | Whether the enchantment belongs to Simple Enchantments itself. |
| `getApplicableCategories()` | `Set<ItemCategory>` | Categories the enchantment can apply to. |
| `getOwnerModId()` | `@Nullable String` | Namespace/owner mod ID. Null for built-ins. |
| `getOwnerModName()` | `@Nullable String` | Display name for the owner mod. |
| `getDefaultMultiplierPerLevel()` | `double` | Default primary multiplier. |
| `getScaledMultiplier(int level)` | `double` | Config-aware scaled multiplier for the given level. |
| `getMultiplierValue(String key)` | `double` | Config-aware value for a primary or secondary multiplier. |
| `getMultiplierDefinitions()` | `List<MultiplierDefinition>` | Primary and secondary config multiplier definitions. |
| `getScrollDefinitions()` | `List<ScrollDefinition>` | Scrolls registered by the builder. |
| `getCraftingCategory()` | `@Nullable String` | Resolved crafting tab ID. |
| `conflictsWith(EnchantmentType other)` | `boolean` | Checks conflict rules. |
| `canApplyTo(ItemCategory category)` | `boolean` | Checks item category applicability. |
| `getFormattedName(int level)` | `String` | Name formatted with level. |
| `getBonusDescription(int level)` | `String` | Bonus description with calculated amount. |

Linear scaling, `ScaleType`, and power scaling read the active primary multiplier config. A custom `.scale(IntToDoubleFunction)` owns the whole returned value.

## Events

| Event | Getters |
|---|---|
| `ItemEnchantedEvent` | `getPlayerRef()`, `getItem()`, `getEnchantment()`, `getLevel()` |
| `EnchantmentActivatedEvent` | `getPlayerRef()`, `getItem()`, `getEnchantment()`, `getLevel()` |

`getPlayerRef()` may be `null`.
