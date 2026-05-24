---
title: "API Documentation"
order: 3
published: true
draft: false
---

Simple Enchantments exposes a Java API for mods that want to add enchantments, make custom items enchantable, create craftable scrolls, read or edit enchanted items, and react to enchantment events.

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

## What Do You Want To Do?

| Goal | Open this page |
|---|---|
| Add Simple Enchantments to your build and manifest. | [Getting Started](https://wiki.hytalemodding.dev/mod/simple-enchantments/api-documentation/getting-started) |
| Build a complete new enchantment with categories, scrolls, and effect logic. | [How to Build Your Own Enchantment](https://wiki.hytalemodding.dev/mod/simple-enchantments/api-documentation/how-to-build-your-own-enchantment) |
| Make your own items work with existing enchantments. | [Register Items to Categories](https://wiki.hytalemodding.dev/mod/simple-enchantments/api-documentation/items-and-categories) |
| Read, add, or remove enchantments on an `ItemStack`. | [Work With Enchanted Items](https://wiki.hytalemodding.dev/mod/simple-enchantments/api-documentation/working-with-enchanted-items) |
| Configure builder fields such as max level, scaling, multipliers, and conflicts. | [Enchantment Builder Reference](https://wiki.hytalemodding.dev/mod/simple-enchantments/api-documentation/registering-enchantments) |
| Add scroll recipes or Enchanting Table tabs. | [Scrolls and Crafting](https://wiki.hytalemodding.dev/mod/simple-enchantments/api-documentation/scrolls-and-crafting) |
| Listen for enchantment activity or report your own activation. | [Events](https://wiki.hytalemodding.dev/mod/simple-enchantments/api-documentation/events) |
| See a full add-on from setup through effect system. | [Full Example](https://wiki.hytalemodding.dev/mod/simple-enchantments/api-documentation/full-example) |
| Check method signatures and return behavior. | [API Reference](https://wiki.hytalemodding.dev/mod/simple-enchantments/api-documentation/reference) |

If you are writing an enchantment add-on, start with **Getting Started**, then follow **How to Build Your Own Enchantment**. If you are only integrating your existing items with Simple Enchantments, start with **Register Items to Categories**.

## Core Concepts

| Concept | What it means |
|---|---|
| `EnchantmentApi` | The main interface for reading, editing, and registering enchantments. |
| `EnchantmentType` | The registered definition of one enchantment. It stores ID, name, max level, scaling, categories, scrolls, and config metadata. |
| `ItemCategory` | An applicability group such as `MELEE_WEAPON`, `SHOVEL`, or a custom category. Enchantments use categories to decide which items they can apply to. |
| `ScrollDefinition` | One craftable scroll level, including quality, required table tier, ingredients, and optional visual overrides. |
| Crafting category | An Enchanting Table tab such as `Enchanting_Melee` or a custom tab registered by an add-on. This decides where scroll recipes appear. |
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
