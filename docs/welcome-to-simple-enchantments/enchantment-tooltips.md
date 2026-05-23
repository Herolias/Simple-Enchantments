---
title: "Enchantment Tooltips"
order: 6
published: true
draft: false
---

# Enchantment Tooltips

Since Version 1.0.0 of Simple Enchantments, [DynamicTooltipsLib](https://www.curseforge.com/hytale/mods/dynamictooltipslib) is a required dependency. This library allows us to add per-item tooltips using "virtual IDs." If you're interested in the technical side, you can read more in the [GitHub Documentation](https://github.com/Herolias/DynamicTooltipsLib).

The enchantments you put on your items will now show up directly in the item tooltip:

![](https://raw.githubusercontent.com/Herolias/Simple-Enchantments/main/docs/media/images/LrTUPtP-b0d53da377.png)

### Tooltip Colors
Enchantment tooltips come in two distinct colors so you can easily tell them apart:
* **Purple:** Regular Enchantments
* **Gold:** Legendary Enchantments

### A Note on Item IDs
If you are playing in Creative Mode, you might notice a strange addition to the Item ID starting with `__dtt_`. Don't worry, this is just a **Virtual ID** used by the client to display the tooltip. It doesn't change the actual ID of the item or affect how the game handles it!
