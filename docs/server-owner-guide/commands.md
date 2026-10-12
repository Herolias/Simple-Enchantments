---
title: "Commands"
order: 2
published: true
draft: false
---

Simple Enchantments currently adds 4 commands. Vanilla `/give` is left untouched.

* `/enchantconfig` - Opens the admin config UI. Requires OP permissions.
* `/enchanting` - Opens the mod walkthrough and user settings. Everyone can run this by default.
* `/enchant` - Enchants the item you are holding with a specific enchantment, up to level 100. Requires OP permissions.
* `/giveenchanted` - Like `/give`, but with an extra `--enchants` argument. Permissions: `hytale.command.giveenchanted.self` (default group `hytale:Builder`) and `hytale.command.giveenchanted.other` (default group `hytale:WorldEditor`).

Usage:

```text
/enchant <enchantment_id> <level>
```

Example:

```text
/enchant knockback 10
```

`/giveenchanted` accepts the same arguments as `/give` plus `--enchants`. Levels must be at least 1 and unknown enchantment ids are rejected; namespaced addon ids (`my_mod:lightning:2`) are supported.

Usage:

```text
/giveenchanted <item> <other args> --enchants <enchantment_id>:<level>;<other_enchantment>:<level>
/giveenchanted <player> <item> <other args> --enchants <enchantment_id>:<level>
```

Example:

```text
/giveenchanted Tool_Pickaxe_Adamantite --enchants efficiency:3;fortune:3;durability:3;smelting:1
```
