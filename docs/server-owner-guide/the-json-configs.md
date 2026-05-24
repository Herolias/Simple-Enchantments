---
title: "The JSON Configs"
order: 5
published: true
draft: false
---

You can find the Simple Enchantments config files in `YourServerDir/mods/Simple_Enchantments_Config`.
There are 3 JSON files:

* `simple_enchantments_config.json` - The general config, containing everything editable in the config editor.
* `simple_enchantments_user_config.json` - The user config, containing user-specific preferences like mod language and cosmetic changes. Players can edit these in-game with `/enchanting`.
* `simple_enchantments_custom_items.json` - Lets you set enchantment categories for different items or item families and blacklist items from being enchantable. This is JSON-only for now; an in-game editor may come later.

I recommend sticking to the in-game editor, because a typo can make a JSON file invalid. If you edit the JSON files manually anyway, use a JSON validator or editor. Each config file also has a snapshot file in the same folder. The mod uses these snapshots to tell which values you changed and which defaults changed between mod updates, letting the smart updater keep your edits while still applying new default settings you have not touched. Please do not edit the snapshot files; doing so will most likely cause your changes to be overwritten.

## Transferring Config Files to Another Server

To move your config files from one server to another, copy the `Simple_Enchantments_Config` folder into the `mods` folder on the target server and replace the existing folder. Make sure the target server is turned off while you do this.
