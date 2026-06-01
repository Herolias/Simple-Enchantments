[![Wiki](https://img.shields.io/badge/Wiki-2563EB?style=for-the-badge&logo=bookstack&logoColor=white)](https://wiki.hytalemodding.dev/mod/simple-enchantments/welcome-to-simple-enchantments) [![Discord](https://img.shields.io/badge/Discord-5865F2?style=for-the-badge&logo=discord&logoColor=white)](https://discord.com/invite/7XQAnUfQfM) [![GitHub](https://img.shields.io/badge/GitHub-30363D?style=for-the-badge&logo=github&logoColor=white)](https://github.com/Herolias/Simple-Enchantments) [![Creator Code: Herolias](https://img.shields.io/badge/Creator%20Code%3A%20Herolias-58B80F?style=for-the-badge)](https://store.hytale.com/) [![Bisect Hosting](https://img.shields.io/badge/Bisect%20Hosting-0078D7?style=for-the-badge)](https://www.bisecthosting.com/Herolias?r=CurseforgeBadge)

**Simple Enchantments** adds a complete, scroll-based enchanting system to Hytale. Craft an Enchanting Table, create Enchantment Scrolls, apply them to your gear, and keep improving your equipment as your world progresses.

The full gameplay guide, enchantment list, server owner docs, and developer API reference are now available on the [Simple Enchantments Wiki](https://wiki.hytalemodding.dev/mod/simple-enchantments/welcome-to-simple-enchantments).

> **Dependency note:** Dynamic Tooltips Lib is only required for Simple Enchantments version 1.0.0 and below. Starting with version 1.1.0, Simple Enchantments no longer requires it.

## Feature Overview

* **33 enchantments** for melee weapons, ranged weapons, tools, armor, shields, staves, gloves, and more.
* **Enchantment Scrolls** as the main way to apply enchantments, with tiered crafting progression and rare legendary scrolls.
* **Enchanting Table** for browsing and crafting scrolls. Upgrade the table to unlock stronger enchantments.
* **Engraving Table** for combining or upgrading scrolls, renaming items, and recoloring enchantment glow.
* **Scroll of Cleansing** and salvaging support for correcting mistakes and recovering enchantments.
* **Enchantment glow** and native tooltip support so enchanted items are easy to recognize.
* **In-game config UI** plus JSON configs for server owners who want deeper customization.
* **Commands** for admins and testing, including enchanting held items and giving pre-enchanted items.
* **Public API** for mod developers and add-ons that want to create custom enchantments, register custom items, add scroll recipes, or interact with enchanted items.
* **Localization for 11 languages**, with community translation improvements welcome.

## For Players

Simple Enchantments is designed around normal gameplay progression. You gather ingredients, craft scrolls at the Enchanting Table, and apply those scrolls to compatible gear. Different enchantments support different equipment types, and some enchantments conflict with each other to keep builds interesting.

For the full list of enchantments, recipes, conflicts, tiers, and gameplay details, visit the [Enchantment Guide](https://wiki.hytalemodding.dev/mod/simple-enchantments/enchantments).

## For Server Owners

Most parts of the mod can be customized. Server owners can adjust scroll recipes, table recipes, table upgrade costs, max enchantments per item, crafting availability, enabled enchantments, item blacklists, custom item categories, and more.

Start with the [Server Owner Guide](https://wiki.hytalemodding.dev/mod/simple-enchantments/server-owner-guide) if you want to configure Simple Enchantments for your server.

## For Developers

Simple Enchantments includes an API for other mods and add-ons. You can register new enchantments, add custom item categories, map your modded items to existing enchantment classes, create custom scroll recipes, inspect enchanted items, and listen to enchantment events.

Start with the [API Documentation](https://wiki.hytalemodding.dev/mod/simple-enchantments/api-documentation) or the [API example project](https://github.com/Herolias/Enchantment-API-Example).

## Compatibility

Simple Enchantments works with most weapon and equipment mods out of the box. If a custom item is not detected automatically, it can be added through config or by the mod through the API.

Optional integrations:

* **Perfect Parries** enables the Riposte and Coup de Grâce enchantments. These enchantments only appear when Perfect Parries is installed.
* **MMO Skill Tree** adds Enchantment XP integration with additional rewards.

Known limitation:

* The Durability enchantment is not compatible with SimpleStackSizeManager or mods that disable durability completely.

## Useful Links

* [Getting Started](https://wiki.hytalemodding.dev/mod/simple-enchantments/welcome-to-simple-enchantments)
* [Enchantments](https://wiki.hytalemodding.dev/mod/simple-enchantments/enchantments)
* [Server Owner Guide](https://wiki.hytalemodding.dev/mod/simple-enchantments/server-owner-guide)
* [API Documentation](https://wiki.hytalemodding.dev/mod/simple-enchantments/api-documentation)
* [Discord Server](https://discord.com/invite/7XQAnUfQfM)
