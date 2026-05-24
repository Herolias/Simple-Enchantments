---
title: "Commands"
order: 2
published: true
draft: false
---

# Commands

Simple Enchantments currently adds 3 commands and an extension to the `/give` command.

* `/enchantconfig` - Opens the admin config UI. Requires OP permissions.
* `/enchanting` - Opens the mod walkthrough and user settings. Everyone can run this by default.
* `/enchant` - Enchants the item you are holding with a specific enchantment, up to level 100. Requires OP permissions.

Usage:

```text
/enchant <enchantment_id> <level>
```

Example:

```text
/enchant knockback 10
```

The mod also adds the `--enchants` argument to the `/give` command.

Usage:

```text
/give <item> <other args> --enchants <enchantment_id>:<level>;<other_enchantment>:<level>
```

Example:

```text
/give Tool_Pickaxe_Adamantite --enchants efficiency:3;fortune:3;durability:3;smelting:1
```
