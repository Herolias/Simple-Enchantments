---
title: "Items and Categories"
order: 2
published: true
draft: false
---

Use the item APIs when you want to inspect enchanted items, modify an `ItemStack`, or make custom items eligible for existing and add-on enchantments.

## Reading Enchantments

```java
EnchantmentApi api = EnchantmentApiProvider.get();
if (api == null) {
    return;
}

int sharpnessLevel = api.getEnchantmentLevel(heldItem, "sharpness");
boolean hasFortune = api.hasEnchantment(heldItem, "fortune");
Map<String, Integer> allEnchantments = api.getEnchantments(heldItem);
```

`getEnchantmentLevel()` returns `0` when the item is null, empty, unknown, or does not contain the enchantment. `hasEnchantment()` returns `false` for the same cases. `getEnchantments()` returns an empty map when the item has no enchantments.

## Adding And Removing Enchantments

`addEnchantment()` and `removeEnchantment()` return an `ItemStack`. Assign the returned item back to the slot you changed.

```java
ItemStack updated = api.addEnchantment(heldItem, "sharpness", 3);
inventory.getHotbar().setItemStackForSlot((short) inventory.getActiveHotbarSlot(), updated);
```

`addEnchantment()` throws `IllegalArgumentException` when the enchantment ID is not registered. If the enchantment is known but cannot be applied because of normal Simple Enchantments rules, such as conflicts or max enchantment limits, the original item is returned.

```java
try {
    ItemStack updated = api.addEnchantment(heldItem, "example:gold_digger", 1);
    updateHeldItem(inventory, updated);
} catch (IllegalArgumentException e) {
    // Unknown enchantment ID.
}
```

`removeEnchantment()` returns the original item when the item is empty, the enchantment ID is unknown, or the item does not have that enchantment.

```java
ItemStack cleaned = api.removeEnchantment(heldItem, "sharpness");
updateHeldItem(inventory, cleaned);
```

## Equipped Enchantments

`equippedItemEnchantments(player)` scans the player's main hand, utility/off-hand, and armor slots. If the same enchantment appears on multiple equipped items, the highest level wins.

```java
Map<String, Integer> equipped = api.equippedItemEnchantments(player);
int protection = equipped.getOrDefault("protection", 0);
```

This is useful for passive effects that care about the player's full equipment set rather than one triggering item.

## Built-In Item Categories

Enchantments use item categories to decide what they can apply to.

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

`BOOTS` still exists as a deprecated source-compatible alias for `LEGS`. New integrations should use `LEGS`.

## Registering Existing Items

Use `registerItemToCategory()` when you know an item ID and want Simple Enchantments to treat it as an existing category.

```java
api.registerItemToCategory("My_Mod_Steel_Katana", "MELEE_WEAPON");
api.registerItemToCategory("My_Mod_Tower_Shield", "SHIELD");
```

The category ID must already exist. API item mappings have higher priority than JSON config mappings and automatic family detection.

## Registering Custom Categories

Use custom categories when your enchantment applies to a group that the built-in categories cannot express cleanly.

```java
ItemCategory hoes = api.registerCategoryByItems(
    "HOES",
    "Tool_Hoe_Crude",
    "Tool_Hoe_Iron",
    "Tool_Hoe_Copper",
    "Tool_Hoe_Thorium"
);
```

Then use the returned category in your enchantment builder:

```java
api.registerEnchantment("example:gold_digger", "Gold Digger")
    .description("Chance to find gold ore when digging dirt")
    .maxLevel(3)
    .appliesTo(ItemCategory.SHOVEL, hoes)
    .build();
```

You can also map a Hytale item family to a category:

```java
ItemCategory katanas = api.registerCategoryByFamily("KATANAS", "katana");
```

Family mappings are useful when every item in a family should behave the same way. Explicit item mappings are better when only specific item IDs should be included.

## Looking Up Categories

```java
ItemCategory category = api.getCategory("MELEE_WEAPON");
if (category != null && category.isEnchantable()) {
    // Use the category in registration or validation code.
}
```

Useful helper methods on `ItemCategory` include `getId()`, `isWeapon()`, `isArmor()`, `isTool()`, `isShield()`, `isMelee()`, `isRanged()`, and `isEnchantable()`.

## Minimal Command Pattern

This is the core pattern for a command that enchants the item in the player's hand:

```java
private void addToHeldItem(Player player, String enchantmentId, int level) {
    EnchantmentApi api = EnchantmentApiProvider.get();
    if (api == null) {
        return;
    }

    Inventory inventory = player.getInventory();
    ItemStack heldItem = inventory.getItemInHand();
    if (heldItem == null || heldItem.isEmpty()) {
        return;
    }

    ItemStack updated = api.addEnchantment(heldItem, enchantmentId, level);
    int activeHotbarSlot = inventory.getActiveHotbarSlot();
    if (activeHotbarSlot != -1) {
        inventory.getHotbar().setItemStackForSlot((short) activeHotbarSlot, updated);
    }
}
```
