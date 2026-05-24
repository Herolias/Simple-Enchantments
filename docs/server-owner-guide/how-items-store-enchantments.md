---
title: "How Items Store Enchantments"
order: 4
published: true
draft: false
---

Enchantments are stored in item extended metadata.
Here is an example of how that looks on an item in a player's inventory:

```json
{
  "2": {
    "Id": "Weapon_Shortbow_Adamantite",
    "Quantity": 1,
    "Durability": 180.0,
    "MaxDurability": 180.0,
    "Metadata": {
      "Enchantments": {
        "Durability": 3,
        "Eagle's Eye": 3
      }
    },
    "OverrideDroppedItemAnimation": false
  }
}
```

The player has an Adamantite Bow with Durability III and Eagle's Eye III in their second hotbar slot. If you want to add or remove enchantments manually, edit the `Enchantments` object by display name. Enchantment metadata is persistent, meaning it stays on the item even if the mod is removed. Existing enchantments will remain on items if Simple Enchantments is deactivated for maintenance.
