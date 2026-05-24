---
title: "Enchantment Builder Reference"
order: 5
published: true
draft: false
---

Use this page when you need details for `EnchantmentBuilder`: IDs, descriptions, max levels, multipliers, scaling, applicability, conflicts, and builder validation. If you want the complete add-on flow first, start with [How to Build Your Own Enchantment](https://wiki.hytalemodding.dev/mod/simple-enchantments/api-documentation/how-to-build-your-own-enchantment).

Custom enchantments are registered through `EnchantmentApi.registerEnchantment(id, displayName)`. The method returns an `EnchantmentBuilder`, and the enchantment becomes available when you call `.build()`.

Addon enchantment IDs must be namespaced with a colon. Use your mod ID as the namespace:

```java
"example:gold_digger"
"my_mod:lightning_strike"
"tools_plus:wide_swing"
```

IDs without `:` throw `IllegalArgumentException`.

## Minimal Enchantment

Every enchantment needs an ID, display name, and at least one item category.

```java
EnchantmentType lightning = api.registerEnchantment("my_mod:lightning", "Lightning Strike")
    .description("Chance to strike enemies with lightning")
    .maxLevel(3)
    .multiplierPerLevel(0.15, "Lightning chance per level")
    .bonusDescription("Hits have a {amount}% chance to call lightning")
    .appliesTo(ItemCategory.MELEE_WEAPON)
    .build();
```

After `.build()`, the enchantment is registered in Simple Enchantments, can be looked up through the API, appears in config metadata, and includes any scroll definitions you chained before `.build()`.

## Builder Fields

| Method | Purpose |
|---|---|
| `.description(text)` | Short description shown by the mod UI. |
| `.maxLevel(level)` | Maximum level. Must be at least `1`. Defaults to `1`. |
| `.requiresDurability(true)` | Only allow the enchantment on items with durability. Defaults to `false`. |
| `.legendary(true)` | Marks the enchantment as legendary. Legendary enchantments are limited by the mod's legendary rules. |
| `.modDisplayName(name)` | Shows your add-on name in scroll descriptions and walkthrough text. |
| `.multiplierPerLevel(value)` | Creates the primary configurable effect multiplier. |
| `.multiplierPerLevel(value, label)` | Same as above, with a human-readable config label. |
| `.addMultiplier(key, defaultValue, label)` | Adds secondary configurable values for enchantments with more than one tunable effect. |
| `.scale(type)` | Applies a predefined scaling curve from `ScaleType`. |
| `.scale(exponent)` | Uses `level^exponent * multiplierPerLevel`. |
| `.scale(function)` | Uses a custom `IntToDoubleFunction`. |
| `.bonusDescription(template)` | Tooltip text for the calculated bonus. Use `{amount}` as the value placeholder. |
| `.walkthrough(text)` | Custom text for the `/enchanting` walkthrough. Uses `{amount}` too. |
| `.craftingCategory(categoryId)` | Chooses an Enchanting Table tab for generated scrolls. |
| `.scroll(level)` | Starts a scroll definition for that enchantment level. |
| `.appliesTo(categories...)` | Adds item categories the enchantment can apply to. Required before `.build()`. |
| `.build()` | Registers and returns the `EnchantmentType`. |

## Multipliers And Scaling

`multiplierPerLevel()` defines the default strength value used by `EnchantmentType.getScaledMultiplier(level)`.

```java
EnchantmentType type = api.registerEnchantment("my_mod:lightning", "Lightning Strike")
    .maxLevel(3)
    .multiplierPerLevel(0.15, "Lightning chance per level")
    .scale(ScaleType.DIMINISHING)
    .appliesTo(ItemCategory.MELEE_WEAPON)
    .build();

double chance = type.getScaledMultiplier(2);
```

For a linear multiplier of `0.15`, level 2 would normally become `0.30`. With `ScaleType.DIMINISHING`, level 2 becomes `sqrt(2) * 0.15`.

The default linear scaling and the built-in `ScaleType`/power scaling helpers read the active config multiplier when `getScaledMultiplier(level)` is called. A fully custom `.scale(function)` returns whatever your function computes, so use it only when you intentionally want to own the whole formula.

Available scale types:

| Scale type | Formula | Use when |
|---|---|---|
| `LINEAR` | `level * multiplier` | Each level should add the same amount. This is the default. |
| `QUADRATIC` | `level * level * multiplier` | Higher levels should become much stronger. |
| `DIMINISHING` | `sqrt(level) * multiplier` | Early levels should matter most. |
| `EXPONENTIAL` | `(2^level - 1) * multiplier` | Late levels should spike hard. |
| `LOGARITHMIC` | `ln(level + 1) * multiplier` | The effect should feel soft-capped. |

Use `addMultiplier()` when one enchantment has multiple configurable values:

```java
api.registerEnchantment("my_mod:burning_aura", "Burning Aura")
    .description("Burn nearby enemies")
    .maxLevel(3)
    .multiplierPerLevel(0.10, "Burn chance per level")
    .addMultiplier("duration", 4.0, "Burn duration in seconds")
    .addMultiplier("radius", 3.5, "Aura radius")
    .appliesTo(ItemCategory.CHESTPLATE)
    .build();
```

The secondary keys are stored as `my_mod:burning_aura:duration` and `my_mod:burning_aura:radius`.

## Descriptions And Tooltips

Use `{amount}` anywhere a calculated value should appear.

```java
.bonusDescription("Hits have a {amount}% chance to call lightning")
.walkthrough("Each hit has a {amount}% chance to summon a lightning strike.")
```

If you do not set a custom walkthrough, Simple Enchantments uses the bonus description as the fallback.

## Applicability

Use built-in or custom `ItemCategory` values to control what the enchantment can apply to.

```java
ItemCategory katanas = api.registerCategoryByFamily("KATANAS", "katana");

api.registerEnchantment("my_mod:parry", "Parry")
    .description("Chance to deflect an incoming melee attack")
    .maxLevel(3)
    .appliesTo(ItemCategory.MELEE_WEAPON, katanas)
    .build();
```

Calling `.build()` without at least one category throws `IllegalStateException`.

## Conflicts

Use conflicts when two enchantments should never be on the same item.

```java
api.addConflict("my_mod:lightning", "burn");
api.addConflict("my_mod:ice_edge", "burn");
```

Conflicts are stored by ID and checked case-insensitively. Add conflicts after both enchantments are registered, and use `api.isEnchantmentRegistered(id)` during development if you want to catch typos early.

## Complete Registration Example

```java
ItemCategory hoes = api.registerCategoryByItems(
    "HOES",
    "Tool_Hoe_Crude",
    "Tool_Hoe_Iron",
    "Tool_Hoe_Copper",
    "Tool_Hoe_Thorium"
);

api.registerCraftingCategory("Enchanting_Shovel", "Shovel", "Icons/CraftingCategories/ShovelTab.png");

EnchantmentType goldDigger = api.registerEnchantment("example:gold_digger", "Gold Digger")
    .description("Chance to find gold ore when digging dirt")
    .maxLevel(3)
    .multiplierPerLevel(0.10, "Gold drop chance per level")
    .bonusDescription("Mined dirt has a {amount}% chance to drop gold ore instead")
    .modDisplayName("Enchantment API Example")
    .walkthrough("While digging dirt blocks with a shovel or hoe, there is a chance "
        + "the block will drop gold ore instead of dirt. Each level increases "
        + "the chance by {amount}%.")
    .appliesTo(ItemCategory.SHOVEL, hoes)
    .craftingCategory("Enchanting_Shovel")
    .scroll(1)
        .quality("Uncommon")
        .craftingTier(1)
        .ingredient("Ingredient_Crystal_Yellow", 5)
        .ingredient("Soil_Dirt", 50)
        .ingredient("Ore_Gold", 10)
        .end()
    .scroll(2)
        .quality("Rare")
        .craftingTier(2)
        .ingredient("Ingredient_Crystal_Yellow", 10)
        .ingredient("Soil_Dirt", 100)
        .ingredient("Ore_Gold", 20)
        .end()
    .scroll(3)
        .quality("Epic")
        .craftingTier(3)
        .ingredient("Ingredient_Crystal_Yellow", 15)
        .ingredient("Soil_Dirt", 150)
        .ingredient("Ore_Gold", 30)
        .end()
    .build();
```
