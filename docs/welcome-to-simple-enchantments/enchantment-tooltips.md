---
title: "Enchantment Tooltips"
order: 7
published: true
draft: false
---

Since Version 1.0.0 of Simple Enchantments, [DynamicTooltipsLib](https://www.curseforge.com/hytale/mods/dynamictooltipslib) is a required dependency. This library lets us add per-item tooltips using "virtual IDs." If you're interested in the technical side, you can read more in the [GitHub documentation](https://github.com/Herolias/DynamicTooltipsLib).

The enchantments you put on your items will now show up directly in the item tooltip:

![](https://raw.githubusercontent.com/Herolias/Simple-Enchantments/main/docs/media/images/LrTUPtP-b0d53da377.png)

### Tooltip Colors
Enchantment tooltips come in two distinct colors so you can easily tell them apart:
* **Purple:** Regular enchantments
* **Gold:** Legendary enchantments

### A Note on Item IDs
If you are playing in Creative Mode, you might notice a strange addition to the item ID starting with `__dtt_`. This is a **virtual ID** used by the client to display the tooltip. It does not change the actual item ID or affect how the game handles the item.
