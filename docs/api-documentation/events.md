---
title: "Events"
order: 7
published: true
draft: false
---

Simple Enchantments exposes events for mods that want to observe enchanting activity or report activation from custom enchantment effects.

## Event Types

| Event | Fired when | Important getters |
|---|---|---|
| `ItemEnchantedEvent` | An item is successfully enchanted. | `getPlayerRef()`, `getItem()`, `getEnchantment()`, `getLevel()` |
| `EnchantmentActivatedEvent` | An enchantment effect successfully activates. | `getPlayerRef()`, `getItem()`, `getEnchantment()`, `getLevel()` |

`getPlayerRef()` can be `null`. For example, an item may be enchanted through an API call without player context, or an activation may come from a non-player context.

## Listening To Events

Register listeners during your plugin `setup()` method.

```java
import org.herolias.plugin.api.event.EnchantmentActivatedEvent;
import org.herolias.plugin.api.event.ItemEnchantedEvent;

@Override
protected void setup() {
    this.getEventRegistry().registerGlobal(ItemEnchantedEvent.class, this::onItemEnchanted);
    this.getEventRegistry().registerGlobal(EnchantmentActivatedEvent.class, this::onEnchantmentActivated);
}

private void onItemEnchanted(ItemEnchantedEvent event) {
    String id = event.getEnchantment().getId();
    int level = event.getLevel();
    ItemStack item = event.getItem();

    // Award stats, log activity, update quests, etc.
}

private void onEnchantmentActivated(EnchantmentActivatedEvent event) {
    String id = event.getEnchantment().getId();
    int level = event.getLevel();

    // React to an effect that actually triggered.
}
```

## Firing Activation Events

If your add-on implements its own effect logic, fire `EnchantmentActivatedEvent` when the effect actually happens. This lets other mods, debug tools, and future integrations observe your enchantment consistently.

```java
import org.herolias.plugin.enchantment.EnchantmentEventHelper;
import org.herolias.plugin.enchantment.EnchantmentType;

int level = api.getEnchantmentLevel(tool, "example:gold_digger");
if (level <= 0) {
    return;
}

EnchantmentType type = api.getRegisteredEnchantment("example:gold_digger");
if (type == null) {
    return;
}

double chance = type.getScaledMultiplier(level);
if (ThreadLocalRandom.current().nextDouble() >= chance) {
    return;
}

// Your effect happens here.
EnchantmentEventHelper.fireActivated(playerRef, tool, type, level);
```

Only fire the activation event after the effect succeeds. If the player has the enchantment but the chance roll fails, no activation happened.

## Event Payload Notes

`getItem()` returns the item involved in the event. Treat it as event context. If you want to modify the item, use the regular `EnchantmentApi` item methods and write the returned `ItemStack` back to the relevant inventory slot.

`getEnchantment()` returns the registered `EnchantmentType`, so you can read metadata such as `getId()`, `getDisplayName()`, `getMaxLevel()`, `getScaledMultiplier(level)`, and `getApplicableCategories()`.

## Optional Integration Listener

Optional integrations should still wrap listener registration because the event classes are Simple Enchantments classes.

```java
private void tryRegisterSimpleEnchantmentsListeners() {
    try {
        this.getEventRegistry().registerGlobal(ItemEnchantedEvent.class, this::onItemEnchanted);
        this.getEventRegistry().registerGlobal(EnchantmentActivatedEvent.class, this::onEnchantmentActivated);
    } catch (NoClassDefFoundError ignored) {
        // Simple Enchantments is not installed.
    }
}
```
