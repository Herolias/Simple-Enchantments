# How Items store Enchantments

Enchantments are stored in the Item Extended Metadata. 
Here is an example of how that looks on an Item in the player Inventory:
...
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
  },
...
The Player has an Adamantite Bow with Durability III and Eagle's Eye III in his second hotbar slot. If you want to remove/add enchantments to that players Inventory manually, you can do so by adding removing enchantments to the json structure via their display names. Enchantment Metadata is persistent, meaning it stays on the Item even if the mod is removed. So all enchantments will stay on the Items even if Simple Enchantments is deactivated for maintanance.
