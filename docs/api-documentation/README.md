---
title: "API Documentation"
order: 3
published: true
draft: false
---

Simple Enchantments exposes a Java API for mods that want to read enchantments, edit enchanted items, register custom item categories, add new enchantments, create craftable scrolls, and react to enchantment events.

The API is useful for two kinds of projects:

* **Enchantment add-ons:** Mods that exist specifically to add new enchantments and scroll recipes.
* **Optional integrations:** Mods that work on their own, but can become enchantment-aware when Simple Enchantments is installed.

The main entry point is `EnchantmentApiProvider.get()`, which returns an `EnchantmentApi` instance after Simple Enchantments has initialized.

```java
import org.herolias.plugin.api.EnchantmentApi;
import org.herolias.plugin.api.EnchantmentApiProvider;

EnchantmentApi api = EnchantmentApiProvider.get();
if (api == null) {
    return;
}
```

## Pages

* **[Getting Started](https://wiki.hytalemodding.dev/mod/simple-enchantments/api-documentation/getting-started):** Add the jar, choose a dependency style, and access the API safely.
* **[Items and Categories](https://wiki.hytalemodding.dev/mod/simple-enchantments/api-documentation/items-and-categories):** Read, add, remove, and list enchantments on items, plus register custom item categories.
* **[Registering Enchantments](https://wiki.hytalemodding.dev/mod/simple-enchantments/api-documentation/registering-enchantments):** Use the fluent builder to create enchantments with descriptions, scaling, config multipliers, conflicts, and applicability rules.
* **[Scrolls and Crafting](https://wiki.hytalemodding.dev/mod/simple-enchantments/api-documentation/scrolls-and-crafting):** Define craftable scrolls, ingredients, crafting tiers, and Enchanting Table tabs.
* **[Events](https://wiki.hytalemodding.dev/mod/simple-enchantments/api-documentation/events):** Listen for item enchantments and fire activation events from your own enchantment logic.
* **[Full Example](https://wiki.hytalemodding.dev/mod/simple-enchantments/api-documentation/full-example):** A complete "Gold Digger" enchantment walkthrough based on the example add-on.
* **[API Reference](https://wiki.hytalemodding.dev/mod/simple-enchantments/api-documentation/reference):** Method tables for the public API classes.

## Core Concepts

| Concept | What it means |
|---|---|
| `EnchantmentApi` | The main interface for reading, editing, and registering enchantments. |
| `EnchantmentType` | The registered definition of one enchantment. It stores ID, name, max level, scaling, categories, scrolls, and config metadata. |
| `ItemCategory` | A category such as `MELEE_WEAPON`, `SHOVEL`, or a custom category. Enchantments use categories to decide which items they can apply to. |
| `ScrollDefinition` | One craftable scroll level, including quality, required table tier, ingredients, and optional visual overrides. |
| Crafting category | An Enchanting Table tab such as `Enchanting_Melee` or a custom tab registered by an add-on. |
| Events | Hooks for when an item is enchanted or when an enchantment effect activates. |

The API stores enchantments on `ItemStack` metadata and usually returns a new `ItemStack` when it changes an item. When you modify a held item, chest item, or custom inventory slot, assign the returned item back to the inventory/container.

## Package Names

Most API classes live under these packages:

```java
import org.herolias.plugin.api.EnchantmentApi;
import org.herolias.plugin.api.EnchantmentApiProvider;
import org.herolias.plugin.api.ScaleType;
import org.herolias.plugin.api.event.EnchantmentActivatedEvent;
import org.herolias.plugin.api.event.ItemEnchantedEvent;
import org.herolias.plugin.enchantment.EnchantmentType;
import org.herolias.plugin.enchantment.ItemCategory;
```

For optional integrations, keep all Simple Enchantments imports and calls inside code that is protected by a `try/catch (NoClassDefFoundError ignored)` block. See the Getting Started page for a complete pattern.
