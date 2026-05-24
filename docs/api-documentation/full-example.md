---
title: "Full Example"
order: 6
published: true
draft: false
---

This page walks through a compact version of the example add-on. The enchantment is called `example:gold_digger`: when a player breaks a soil block with an enchanted shovel or hoe, there is a configurable chance to replace the normal drop with gold ore.

The full example project lives at [Herolias/Enchantment-API-Example](https://github.com/Herolias/Enchantment-API-Example), but the snippets below are adjusted to the current Simple Enchantments API.

## Plugin Setup

```java
package org.example.plugin;

import com.hypixel.hytale.logger.HytaleLogger;
import com.hypixel.hytale.server.core.plugin.JavaPlugin;
import com.hypixel.hytale.server.core.plugin.JavaPluginInit;
import org.herolias.plugin.api.EnchantmentApi;
import org.herolias.plugin.api.EnchantmentApiProvider;
import org.herolias.plugin.enchantment.EnchantmentType;
import org.herolias.plugin.enchantment.ItemCategory;

import javax.annotation.Nonnull;

public class GoldDiggerAddon extends JavaPlugin {

    private static final HytaleLogger LOGGER = HytaleLogger.forEnclosingClass();

    public GoldDiggerAddon(@Nonnull JavaPluginInit init) {
        super(init);
    }

    @Override
    protected void setup() {
        EnchantmentApi api = EnchantmentApiProvider.get();
        if (api == null) {
            throw new IllegalStateException("Simple Enchantments API is not ready");
        }

        registerGoldDigger(api);
        this.getEntityStoreRegistry().registerSystem(new GoldDiggerSystem());
        LOGGER.atInfo().log("Gold Digger add-on loaded");
    }

    private void registerGoldDigger(EnchantmentApi api) {
        ItemCategory hoes = api.registerCategoryByItems(
            "HOES",
            "Tool_Hoe_Crude",
            "Tool_Hoe_Iron",
            "Tool_Hoe_Copper",
            "Tool_Hoe_Thorium"
        );

        api.registerCraftingCategory("Enchanting_Shovel", "Shovel", "Icons/CraftingCategories/ShovelTab.png");

        EnchantmentType goldDigger = api.registerEnchantment("example:gold_digger", "Gold Digger")
            .description("Chance to find gold ore when digging dirt")
            .maxLevel(3)
            .multiplierPerLevel(0.10, "Gold drop chance per level")
            .bonusDescription("Mined dirt has a {amount}% chance to drop gold ore instead")
            .modDisplayName("Enchantment API Example")
            .walkthrough("While digging dirt blocks with a shovel or hoe, there is a chance "
                + "the block will drop gold ore instead of dirt. Each level increases "
                + "the chance by {amount}%.")
            .appliesTo(ItemCategory.SHOVEL, hoes)
            .craftingCategory("Enchanting_Shovel")
            .scroll(1)
                .quality("Uncommon")
                .craftingTier(1)
                .ingredient("Ingredient_Crystal_Yellow", 5)
                .ingredient("Soil_Dirt", 50)
                .ingredient("Ore_Gold", 10)
                .end()
            .scroll(2)
                .quality("Rare")
                .craftingTier(2)
                .ingredient("Ingredient_Crystal_Yellow", 10)
                .ingredient("Soil_Dirt", 100)
                .ingredient("Ore_Gold", 20)
                .end()
            .scroll(3)
                .quality("Epic")
                .craftingTier(3)
                .ingredient("Ingredient_Crystal_Yellow", 15)
                .ingredient("Soil_Dirt", 150)
                .ingredient("Ore_Gold", 30)
                .end()
            .build();

        LOGGER.atInfo().log("Registered " + goldDigger.getId());
    }
}
```

This setup does four things:

1. Registers a custom `HOES` item category.
2. Adds a custom `Enchanting_Shovel` crafting tab.
3. Registers the enchantment and three craftable scroll levels.
4. Registers an ECS system that implements the gameplay effect.

## Effect System

The effect system should do as little API work as possible on every event:

1. Get the tool from the event.
2. Check the enchantment level on that tool.
3. Look up the registered `EnchantmentType`.
4. Use `getScaledMultiplier(level)` for the current configured chance.
5. Run the effect.
6. Fire an activation event.

```java
public class GoldDiggerSystem extends EntityEventSystem<EntityStore, BreakBlockEvent> {

    public GoldDiggerSystem() {
        super(BreakBlockEvent.class);
    }

    @Override
    @Nonnull
    public Query<EntityStore> getQuery() {
        return Archetype.empty();
    }

    @Override
    public void handle(int index,
                       @Nonnull ArchetypeChunk<EntityStore> archetypeChunk,
                       @Nonnull Store<EntityStore> store,
                       @Nonnull CommandBuffer<EntityStore> commandBuffer,
                       @Nonnull BreakBlockEvent event) {
        if (event.isCancelled()) {
            return;
        }

        ItemStack tool = event.getItemInHand();
        if (tool == null || tool.isEmpty()) {
            return;
        }

        EnchantmentApi api = EnchantmentApiProvider.get();
        if (api == null) {
            return;
        }

        int level = api.getEnchantmentLevel(tool, "example:gold_digger");
        if (level <= 0) {
            return;
        }

        EnchantmentType type = api.getRegisteredEnchantment("example:gold_digger");
        if (type == null) {
            return;
        }

        BlockType blockType = event.getBlockType();
        if (blockType == null || blockType.getId() == null || !blockType.getId().startsWith("Soil_")) {
            return;
        }

        double chance = type.getScaledMultiplier(level);
        if (ThreadLocalRandom.current().nextDouble() >= chance) {
            return;
        }

        // Cancel the normal block drop and spawn Ore_Gold here.

        PlayerRef playerRef = null; // Derive this from the breaker entity if available.
        EnchantmentEventHelper.fireActivated(playerRef, tool, type, level);
    }
}
```

The omitted block-drop code depends on the Hytale server APIs you use for world edits and item drops. The important Simple Enchantments part is the level lookup, `getScaledMultiplier(level)`, and `EnchantmentEventHelper.fireActivated(...)`.

## Testing The Example

During development, add a small command that applies the enchantment to the held item. This keeps iteration fast while you tune the effect and recipe.

```java
ItemStack updated = api.addEnchantment(heldItem, "example:gold_digger", 1);
inventory.getHotbar().setItemStackForSlot((short) inventory.getActiveHotbarSlot(), updated);
```

After the command works, test the player-facing path:

1. Confirm the custom tab appears in the Enchanting Table.
2. Craft each scroll level.
3. Apply the scroll to a shovel or hoe.
4. Break matching blocks and confirm the activation chance respects config changes.
5. Confirm activation listeners receive `EnchantmentActivatedEvent`.
