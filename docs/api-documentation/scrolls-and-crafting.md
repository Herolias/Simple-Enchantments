---
title: "Scrolls and Crafting"
order: 4
published: true
draft: false
---

Scroll definitions make your custom enchantments craftable at the Enchanting Table. Each `scroll(level)` call defines one craftable scroll level for the enchantment.

```java
api.registerEnchantment("my_mod:lightning", "Lightning Strike")
    .description("Chance to strike enemies with lightning")
    .maxLevel(3)
    .multiplierPerLevel(0.15)
    .appliesTo(ItemCategory.MELEE_WEAPON)
    .scroll(1)
        .quality("Uncommon")
        .craftingTier(1)
        .ingredient("Ingredient_Crystal_Blue", 5)
        .ingredient("Ingredient_Void_Essence", 3)
        .end()
    .scroll(2)
        .quality("Rare")
        .craftingTier(2)
        .ingredient("Ingredient_Crystal_Blue", 10)
        .ingredient("Ingredient_Void_Essence", 6)
        .end()
    .build();
```

Scrolls are optional from the builder's point of view. If you do not add scroll definitions, the enchantment can still exist for commands or custom systems, but players will not get craftable scroll recipes from your add-on.

## Built-In Crafting Categories

Crafting categories are Enchanting Table tabs.

| Category ID | Tab |
|---|---|
| `Enchanting_Melee` | Melee |
| `Enchanting_Ranged` | Ranged |
| `Enchanting_Armor` | Armor |
| `Enchanting_Shield` | Shield |
| `Enchanting_Staff` | Staff |
| `Enchanting_Tools` | Tools |

If you do not call `.craftingCategory(...)`, Simple Enchantments derives a tab from the enchantment's item categories.

| Item category | Default crafting category |
|---|---|
| `MELEE_WEAPON` | `Enchanting_Melee` |
| `RANGED_WEAPON` | `Enchanting_Ranged` |
| `ARMOR`, `LEGS` | `Enchanting_Armor` |
| `SHIELD` | `Enchanting_Shield` |
| `STAFF`, `STAFF_MANA`, `STAFF_ESSENCE` | `Enchanting_Staff` |
| `PICKAXE`, `AXE`, `SHOVEL`, `TOOL` | `Enchanting_Tools` |
| Anything else | `Enchanting_Melee` fallback |

## Custom Crafting Categories

Register a custom tab when your scrolls do not fit an existing tab.

```java
api.registerCraftingCategory(
    "Enchanting_Shovel",
    "Shovel",
    "Icons/CraftingCategories/ShovelTab.png"
);
```

Then assign the enchantment or individual scrolls to the category.

```java
api.registerEnchantment("example:gold_digger", "Gold Digger")
    .appliesTo(ItemCategory.SHOVEL)
    .craftingCategory("Enchanting_Shovel")
    .build();
```

The icon path is relative to your mod assets. Pass `null` to use the default enchanting icon.

## Scroll Builder Options

| Method | Purpose |
|---|---|
| `.quality(value)` | Sets the scroll rarity string. Common values are `Common`, `Uncommon`, `Rare`, `Epic`, and `Legendary`. Defaults to `Uncommon`. |
| `.craftingTier(tier)` | Required Enchanting Table tier. Must be `1` to `4`. Defaults to `1`. |
| `.craftingCategory(categoryId)` | Overrides the tab for this scroll only. Defaults to the enchantment crafting category. |
| `.ingredient(itemId, quantity)` | Adds a recipe ingredient. Quantity must be at least `1`. |
| `.icon(path)` | Overrides the inventory icon for this scroll level. |
| `.model(path)` | Overrides the scroll 3D model. |
| `.texture(path)` | Overrides the scroll texture. |
| `.iconProperties(scale, x, y, rotX, rotY, rotZ)` | Overrides how the icon is rendered. |
| `.end()` | Finishes a chained scroll and returns to the `EnchantmentBuilder`. |
| `.build()` | Builds a standalone `ScrollDefinition`. |

## Recipe Guidelines

Use one scroll per enchantment level when players should craft specific levels:

```java
.scroll(1)
    .quality("Uncommon")
    .craftingTier(1)
    .ingredient("Ingredient_Crystal_Yellow", 5)
    .ingredient("Soil_Dirt", 50)
    .end()
.scroll(2)
    .quality("Rare")
    .craftingTier(2)
    .ingredient("Ingredient_Crystal_Yellow", 10)
    .ingredient("Soil_Dirt", 100)
    .end()
.scroll(3)
    .quality("Epic")
    .craftingTier(3)
    .ingredient("Ingredient_Crystal_Yellow", 15)
    .ingredient("Soil_Dirt", 150)
    .end()
```

Good scroll recipes usually scale in three places:

* **Quality:** Higher levels should feel rarer.
* **Crafting tier:** Higher levels can require a better Enchanting Table.
* **Ingredients:** Higher levels should cost more or use rarer materials.

## Per-Scroll Category Overrides

Most enchantments should set the crafting category once on the enchantment. Use per-scroll overrides only when different levels should appear in different tabs.

```java
.scroll(1)
    .craftingCategory("Enchanting_Tools")
    .ingredient("Ingredient_Crystal_Green", 4)
    .end()
.scroll(2)
    .craftingCategory("Enchanting_Shovel")
    .ingredient("Ingredient_Crystal_Green", 8)
    .end()
```

## Visual Overrides

By default, add-on scrolls use Simple Enchantments scroll visuals. Override them only when your add-on includes its own assets.

```java
.scroll(1)
    .quality("Rare")
    .craftingTier(2)
    .icon("Icons/Scrolls/LightningScroll.png")
    .model("Items/LightningScroll.blockymodel")
    .texture("Items/LightningScroll.png")
    .iconProperties(0.84f, 5f, 15f, 90f, 45f, 0f)
    .ingredient("Ingredient_Crystal_Blue", 8)
    .end()
```

The default icon properties are `scale = 0.84`, translation `(5, 15)`, and rotation `(90, 45, 0)`.
