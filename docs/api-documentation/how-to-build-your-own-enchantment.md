---
title: "How to Build Your Own Enchantment"
order: 2
published: true
draft: false
---

Use this page when you want to add a new enchantment to Simple Enchantments. It shows the path from plugin setup to a working effect, and links to the deeper reference pages when you need details.

Simple Enchantments handles registration, item metadata, config UI entries, scroll items, scroll recipes, and event reporting hooks. Your add-on still owns the gameplay effect: you register an ECS system, listener, command, or other logic that checks whether an item has your enchantment and then applies the effect.

## 1. Add The Dependency

If your mod is mainly an enchantment add-on, make Simple Enchantments a full dependency in `manifest.json`.

```json
{
  "Main": "com.example.plugin.MyEnchantmentAddon",
  "Dependencies": {
    "org.herolias:SimpleEnchantments": "1.1.0"
  }
}
```

Then compile against the jar with `compileOnly`. See [Getting Started](https://wiki.hytalemodding.dev/mod/simple-enchantments/api-documentation/getting-started) for Gradle examples and optional integration patterns.

## 2. Get The API In setup()

Register API content during your plugin `setup()` method.

```java
import org.herolias.plugin.api.EnchantmentApi;
import org.herolias.plugin.api.EnchantmentApiProvider;

@Override
protected void setup() {
    EnchantmentApi api = EnchantmentApiProvider.get();
    if (api == null) {
        throw new IllegalStateException("Simple Enchantments API is not ready");
    }

    registerMyEnchantments(api);
    registerMyEffectSystems();
}
```

If Simple Enchantments is a full dependency, Hytale loads it before your add-on. The null check is still useful during development because it fails loudly if the dependency is missing or load order is wrong.

## 3. Decide What Items Can Use It

Every enchantment must have at least one `ItemCategory`. Use a built-in category when possible.

```java
.appliesTo(ItemCategory.MELEE_WEAPON)
.appliesTo(ItemCategory.SHOVEL, ItemCategory.PICKAXE)
```

If your item group is not covered by a built-in category, register one.

```java
ItemCategory hoes = api.registerCategoryByItems(
    "HOES",
    "Tool_Hoe_Crude",
    "Tool_Hoe_Iron",
    "Tool_Hoe_Copper",
    "Tool_Hoe_Thorium"
);
```

Use [Register Items to Categories](https://wiki.hytalemodding.dev/mod/simple-enchantments/api-documentation/items-and-categories) when you want existing Simple Enchantments to work on your custom items, or when you need custom categories.

## 4. Register The Enchantment

This is the smallest useful registration. The important parts are the namespaced ID, display name, at least one category, and `.build()`.

```java
import org.herolias.plugin.enchantment.EnchantmentType;
import org.herolias.plugin.enchantment.ItemCategory;

EnchantmentType lightning = api.registerEnchantment("my_mod:lightning", "Lightning Strike")
    .description("Chance to strike enemies with lightning")
    .maxLevel(3)
    .multiplierPerLevel(0.15, "Lightning chance per level")
    .bonusDescription("Hits have a {amount}% chance to call lightning")
    .appliesTo(ItemCategory.MELEE_WEAPON)
    .build();
```

After `.build()`, the enchantment can be looked up by ID, appears in config metadata, and can be applied by commands or API calls. The ID must contain `:`; use your mod ID as the namespace.

For every builder option, see [Enchantment Builder Reference](https://wiki.hytalemodding.dev/mod/simple-enchantments/api-documentation/registering-enchantments).

## 5. Add Craftable Scrolls

Scrolls are optional. Without scroll definitions, the enchantment can still exist for commands, testing, or custom systems, but players will not get craftable scroll recipes from your add-on.

```java
api.registerEnchantment("my_mod:lightning", "Lightning Strike")
    .description("Chance to strike enemies with lightning")
    .maxLevel(3)
    .multiplierPerLevel(0.15, "Lightning chance per level")
    .bonusDescription("Hits have a {amount}% chance to call lightning")
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

Each `.scroll(level)` call defines one craftable scroll level. Call `.end()` to return to the enchantment builder before adding the next scroll or calling `.build()`.

Use [Scrolls and Crafting](https://wiki.hytalemodding.dev/mod/simple-enchantments/api-documentation/scrolls-and-crafting) for recipe tiers, custom Enchanting Table tabs, and visual overrides.

## 6. Implement The Effect

Simple Enchantments stores and applies the enchantment to items, but your add-on decides what the enchantment does.

The usual runtime pattern is:

1. Get the relevant `ItemStack`.
2. Read your enchantment level with `api.getEnchantmentLevel(item, "my_mod:lightning")`.
3. Look up the registered `EnchantmentType`.
4. Use `type.getScaledMultiplier(level)` or `type.getMultiplierValue(key)` for configurable values.
5. Apply the effect.
6. Fire `EnchantmentEventHelper.fireActivated(...)` only after the effect actually happens.

```java
int level = api.getEnchantmentLevel(weapon, "my_mod:lightning");
if (level <= 0) {
    return;
}

EnchantmentType type = api.getRegisteredEnchantment("my_mod:lightning");
if (type == null) {
    return;
}

double chance = type.getScaledMultiplier(level);
if (ThreadLocalRandom.current().nextDouble() >= chance) {
    return;
}

// Apply your effect here.
EnchantmentEventHelper.fireActivated(playerRef, weapon, type, level);
```

See [Events](https://wiki.hytalemodding.dev/mod/simple-enchantments/api-documentation/events) for activation event details and [Full Example](https://wiki.hytalemodding.dev/mod/simple-enchantments/api-documentation/full-example) for a complete effect-system sketch.

## 7. Test The Player Path

During development, add a small command or debug action that enchants the held item:

```java
ItemStack updated = api.addEnchantment(heldItem, "my_mod:lightning", 1);
inventory.getHotbar().setItemStackForSlot((short) inventory.getActiveHotbarSlot(), updated);
```

Then test the real flow:

1. The enchantment appears in config UI metadata.
2. Scrolls appear under the expected Enchanting Table tab.
3. The scroll can apply to the intended item categories.
4. The effect only runs when the item has the enchantment.
5. Config multiplier changes affect the effect value you use at runtime.
6. Activation listeners receive `EnchantmentActivatedEvent` when the effect succeeds.
