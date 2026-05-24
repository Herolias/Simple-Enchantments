---
title: "Work With Enchanted Items"
order: 4
published: true
draft: false
---

Use this page when you want to inspect an `ItemStack`, add an enchantment, remove an enchantment, or check all enchantments on a player's equipped items.

Simple Enchantments stores enchantment data on item metadata. Methods that change an item return an `ItemStack`; assign the returned value back to the inventory or container slot you changed.

## Read Enchantments

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

## Add An Enchantment

```java
try {
    ItemStack updated = api.addEnchantment(heldItem, "example:gold_digger", 1);
    updateHeldItem(inventory, updated);
} catch (IllegalArgumentException e) {
    // Unknown enchantment ID.
}
```

`addEnchantment()` throws `IllegalArgumentException` when the enchantment ID is not registered. If the enchantment is known but cannot be applied because of normal Simple Enchantments rules, such as conflicts or max enchantment limits, the original item is returned.

## Remove An Enchantment

```java
ItemStack cleaned = api.removeEnchantment(heldItem, "sharpness");
updateHeldItem(inventory, cleaned);
```

`removeEnchantment()` returns the original item when the item is empty, the enchantment ID is unknown, or the item does not have that enchantment.

## Write The Returned Item Back

For held-item commands, write the returned item back to the active hotbar slot.

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

Use the equivalent container-slot setter for chest items, custom UI slots, or other inventories.

## Check Equipped Enchantments

`equippedItemEnchantments(player)` scans the player's main hand, utility/off-hand, and armor slots. If the same enchantment appears on multiple equipped items, the highest level wins.

```java
Map<String, Integer> equipped = api.equippedItemEnchantments(player);
int protection = equipped.getOrDefault("protection", 0);
```

This is useful for passive effects that care about the player's full equipment set rather than one triggering item.

## Common Pitfalls

| Pitfall | Fix |
|---|---|
| Calling `addEnchantment()` but ignoring the returned item. | Assign the returned `ItemStack` back to the slot. |
| Applying an unknown ID such as a typo. | Check `api.isEnchantmentRegistered(id)` or catch `IllegalArgumentException`. |
| Expecting custom items to accept an enchantment automatically. | Register the item or its family on [Register Items to Categories](https://wiki.hytalemodding.dev/mod/simple-enchantments/api-documentation/items-and-categories). |
| Reading equipped enchantments for a one-item trigger. | Use `getEnchantmentLevel(triggerItem, id)` when only the active item should matter. |
