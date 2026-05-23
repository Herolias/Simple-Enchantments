---
title: "The JSON Configs"
order: 5
published: true
draft: false
---

# The json configs

You find the config files of Simple Enchantments in YourServerDir/mods/Simple_Enchantments_Config.
There are 3 json files:
simple_enchantments_config.json - the general config, containing everything editable in the config editor
simple_enchantments_user_config.json - The user config, containing user specific preferences like mod language or cosmetic changes, accessible in-game via /enchanting
simple_enchantments_custom_items.json - Allows for setting Enchantment Categories for different Items or Item Families and Blacklisting Items from being enchantable. json-only for now, in-game editor maybe later-on. 

I would recommend sticking to the in-game editor, as changing json files can easily render them invalid via a typo. If you decide to edit the json files anyways, I recommend using a json validator/editor. There are also snapshot files for each config file in the same folder. These are created and needed by the Mod to determine which values you changed, and which values we as the developers changed, to enable a smart updating system that keeps all your changes and only changes settings you did not touch when we decide to change default settings. Please do not edit the snapshot files, it will most likely cause all your changes to be overwritten by the system.  

## Transfering Config Files to another Server 

If you want to transfer your config files, e.g. form a test server to your main server, you can do so by simply copying the Simple_Enchantments_Config folder and pasting/replacing it on your other server in the mods folder. Make sure the server is turned off while doing so.
