---
title: "Register Items to Categories"
order: 3
published: true
draft: false
---

Use this page when you want Simple Enchantments to recognize custom items, or when your add-on needs a custom item category for a new enchantment.

Item categories decide which items an enchantment can be applied to. They are different from crafting categories: crafting categories are Enchanting Table tabs, covered on [Scrolls and Crafting](https://wiki.hytalemodding.dev/mod/simple-enchantments/api-documentation/scrolls-and-crafting).

## Built-In Item Categories

Use a built-in category whenever it expresses your item well.

| Category | Typical use |
|---|---|
| `MELEE_WEAPON` | Swords, maces, daggers, spears, scythes, longswords, battleaxes, clubs. |
| `RANGED_WEAPON` | Bows and crossbows. |
| `TOOL` | General tools, especially when a more specific category does not matter. |
| `PICKAXE` | Pickaxes and mining tools. |
| `SHOVEL` | Shovels and digging tools. |
| `AXE` | Hatchets and tool axes. |
| `SHIELD` | Shields. |
| `HELMET` | Head armor. |
| `CHESTPLATE` | Chest armor. |
| `LEGS` | Leg armor. |
| `ARMOR` | Any armor piece covered by a broad armor enchantment. |
| `GLOVES` | Gloves or hand armor. |
| `STAFF` | Broad staff category. |
| `STAFF_MANA` | Mana-consuming staffs. |
| `STAFF_ESSENCE` | Essence-consuming staffs. |
| `UNKNOWN` | Non-enchantable or uncategorized items. |

`ItemCategory.BOOTS` still exists as a deprecated source-compatible alias for `ItemCategory.LEGS`. New integrations should use `LEGS`.

## Register One Item To An Existing Category

Use `registerItemToCategory(itemId, categoryId)` when you know an item ID and want it to behave like an existing category.

```java
api.registerItemToCategory("My_Mod_Steel_Katana", "MELEE_WEAPON");
api.registerItemToCategory("My_Mod_Tower_Shield", "SHIELD");
```

The category ID must already exist. This is the right API for optional integrations where your mod has its own items and simply wants them to accept Simple Enchantments.

API item mappings override config mappings and automatic family detection.

## Register A Category By Item IDs

Use `registerCategoryByItems(categoryId, itemIds...)` when your enchantment should apply to a specific list of items that does not fit a built-in category.

```java
ItemCategory hoes = api.registerCategoryByItems(
    "HOES",
    "Tool_Hoe_Crude",
    "Tool_Hoe_Iron",
    "Tool_Hoe_Copper",
    "Tool_Hoe_Thorium"
);
```

Then pass the returned category into your enchantment builder:

```java
api.registerEnchantment("example:gold_digger", "Gold Digger")
    .description("Chance to find gold ore when digging dirt")
    .maxLevel(3)
    .appliesTo(ItemCategory.SHOVEL, hoes)
    .build();
```

If the category ID already exists, Simple Enchantments reuses it and adds the item mappings.

## Register A Category By Family

Use `registerCategoryByFamily(categoryId, family)` when all items in a Hytale item family should behave the same way.

```java
ItemCategory katanas = api.registerCategoryByFamily("KATANAS", "katana");

api.registerEnchantment("my_mod:parry", "Parry")
    .description("Chance to deflect an incoming melee attack")
    .maxLevel(3)
    .appliesTo(ItemCategory.MELEE_WEAPON, katanas)
    .build();
```

Family names are stored case-insensitively. Explicit item ID mappings are better when only some items in a family should be enchantable.

## Look Up A Category

Use `getCategory(categoryId)` when you need to reuse a category that may have been registered elsewhere.

```java
ItemCategory category = api.getCategory("MELEE_WEAPON");
if (category != null && category.isEnchantable()) {
    // Use the category in registration or validation code.
}
```

Useful helper methods on `ItemCategory` include `getId()`, `isWeapon()`, `isArmor()`, `isTool()`, `isShield()`, `isMelee()`, `isRanged()`, and `isEnchantable()`.

Custom categories created with the public API are enchantable, but their helper flags such as `isWeapon()` and `isArmor()` are `false` unless they are one of the built-in categories. Use `getId()` or direct equality with the returned category when you need custom-category checks.

## Setup Order

Register item categories during your plugin `setup()` method, before registering enchantments that use them.

For a full add-on, the usual order is:

1. Get `EnchantmentApi`.
2. Register custom item categories.
3. Register custom Enchanting Table tabs if needed.
4. Register enchantments that use those categories.
5. Register the systems or listeners that implement gameplay effects.

For optional integrations that only map your items into existing categories, call `registerItemToCategory(...)` in a guarded integration method. See [Getting Started](https://wiki.hytalemodding.dev/mod/simple-enchantments/api-documentation/getting-started) for the optional dependency pattern.
