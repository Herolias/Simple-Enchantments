# Commands

Simple Enchantments has currently 3 Commands and an extension to the /give command. Here is a breakdown for each command:

/enchantconfig - The Admin config. Needs OP permissions to run the command.
/enchanting - Mod Walktrough and user settings. Everyone can run the command by default.
/enchant - Enchants the Item you are holding with a specific Enchantment (with level up to 100). Needs OP permissions to run the command.
Usage: /enchant \<enchantment_id\> \<level\>
Example: /enchant knockback 10

The mod also adds the -enchants argument to the /give command.
Usage: /give \<item\> \<other args\> --enchant \<enchantment_id\>:\<lvl\>;\<other_enchant\>:\<lvl\>
Example: /give Tool_Pickaxe_Adamantite --enchants efficiency:3;fortune:3;durability:3;smelting:1
