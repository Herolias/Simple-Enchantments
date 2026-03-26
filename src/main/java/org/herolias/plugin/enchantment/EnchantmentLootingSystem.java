package org.herolias.plugin.enchantment;

import com.hypixel.hytale.component.AddReason;
import com.hypixel.hytale.component.CommandBuffer;
import com.hypixel.hytale.component.Holder;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.component.dependency.Dependency;
import com.hypixel.hytale.component.dependency.Order;
import com.hypixel.hytale.component.dependency.SystemDependency;
import com.hypixel.hytale.component.query.Query;
import com.hypixel.hytale.logger.HytaleLogger;
import com.hypixel.hytale.math.util.MathUtil;
import com.hypixel.hytale.math.vector.Vector3d;
import com.hypixel.hytale.math.vector.Vector3f;
import com.hypixel.hytale.server.core.asset.type.gameplay.DeathConfig;
import com.hypixel.hytale.server.core.asset.type.item.config.ItemDrop;
import com.hypixel.hytale.server.core.asset.type.item.config.ItemDropList;
import com.hypixel.hytale.server.core.asset.type.item.config.container.ItemDropContainer;
import com.hypixel.hytale.server.core.asset.type.item.config.container.MultipleItemDropContainer;
import com.hypixel.hytale.server.core.entity.Entity;
import com.hypixel.hytale.server.core.entity.EntityUtils;
import com.hypixel.hytale.server.core.entity.LivingEntity;
import com.hypixel.hytale.server.core.entity.UUIDComponent;
import com.hypixel.hytale.server.core.entity.entities.Player;
import com.hypixel.hytale.server.core.inventory.Inventory;
import com.hypixel.hytale.server.core.inventory.ItemStack;
import com.hypixel.hytale.server.core.modules.entity.component.HeadRotation;
import com.hypixel.hytale.server.core.modules.entity.component.TransformComponent;
import com.hypixel.hytale.server.core.modules.entity.damage.Damage;
import com.hypixel.hytale.server.core.modules.entity.damage.DeathComponent;
import com.hypixel.hytale.server.core.modules.entity.damage.DeathSystems;
import com.hypixel.hytale.server.core.modules.entity.item.ItemComponent;
import com.hypixel.hytale.server.core.modules.entity.tracker.NetworkId;
import com.hypixel.hytale.server.core.modules.item.ItemModule;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import com.hypixel.hytale.server.npc.entities.NPCEntity;
import com.hypixel.hytale.server.npc.role.Role;
import com.hypixel.hytale.server.npc.systems.NPCDamageSystems;
import it.unimi.dsi.fastutil.objects.ObjectArrayList;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ThreadLocalRandom;
import java.util.function.DoubleSupplier;

/**
 * Applies Looting enchantment to NPC drops.
 * Also cooks bonus drops if the weapon has the Burn enchantment.
 *
 * Overhauled Logic:
 * Instead of extra rolls, this system boosts the drop chance of rare items
 * (weight &lt; 100)
 * by a multiplicative factor based on Looting level.
 */
public class EnchantmentLootingSystem extends DeathSystems.OnDeathSystem {

    private static final HytaleLogger LOGGER = HytaleLogger.forEnclosingClass();
    private static final Query<EntityStore> QUERY = Query.and(
            NPCEntity.getComponentType(),
            TransformComponent.getComponentType(),
            HeadRotation.getComponentType(),
            Query.not(Player.getComponentType()));

    // Run BEFORE built-in drop system to suppress and replace drops
    private final Set<Dependency<EntityStore>> dependencies = Set.of(
            new SystemDependency(Order.BEFORE, NPCDamageSystems.DropDeathItems.class));

    private static final Field MULTIPLE_CONTAINERS_FIELD;

    static {
        try {
            MULTIPLE_CONTAINERS_FIELD = MultipleItemDropContainer.class.getDeclaredField("containers");
            MULTIPLE_CONTAINERS_FIELD.setAccessible(true);
        } catch (NoSuchFieldException e) {
            throw new RuntimeException("Failed to access MultipleItemDropContainer.containers field", e);
        }
    }

    private final EnchantmentManager enchantmentManager;

    public EnchantmentLootingSystem(EnchantmentManager enchantmentManager) {
        this.enchantmentManager = enchantmentManager;
        LOGGER.atInfo().log("EnchantmentLootingSystem initialized");
    }

    @Override
    @Nonnull
    public Query<EntityStore> getQuery() {
        return QUERY;
    }

    @Override
    @Nonnull
    public Set<Dependency<EntityStore>> getDependencies() {
        return dependencies;
    }

    @Override
    public void onComponentAdded(@Nonnull Ref<EntityStore> ref,
            @Nonnull DeathComponent component,
            @Nonnull Store<EntityStore> store,
            @Nonnull CommandBuffer<EntityStore> commandBuffer) {
        // Only process if the built-in system would also run
        if (component.getItemsLossMode() != DeathConfig.ItemsLossMode.ALL) {
            return;
        }

        Damage deathInfo = component.getDeathInfo();
        if (deathInfo == null) {
            return;
        }

        // Resolve both Looting and Burn levels
        EnchantmentManager.DamageEnchantments levels = enchantmentManager.resolveDamageEnchantments(deathInfo,
                commandBuffer, ref);
        if (levels.lootingLevel() <= 0) {
            // If no Looting, return and let Burn system or built-in system handle it
            return;
        }

        NPCEntity npcComponent = commandBuffer.getComponent(ref, NPCEntity.getComponentType());
        if (npcComponent == null) {
            return;
        }

        Role role = npcComponent.getRole();
        if (role == null) {
            return;
        }

        String dropListId = role.getDropListId();
        if (dropListId == null) {
            return;
        }

        ItemModule itemModule = ItemModule.get();
        if (itemModule == null || !itemModule.isEnabled()) {
            return;
        }

        // --- CORE LOOTING LOGIC START ---

        // suppress built-in drops
        component.setItemsLossMode(DeathConfig.ItemsLossMode.NONE);

        List<ItemStack> allDrops = new ArrayList<>();

        // 1. Calculate boosted drops from Drop List
        ItemDropList itemDropList = ItemDropList.getAssetMap().getAsset(dropListId);
        if (itemDropList != null && itemDropList.getContainer() != null) {
            double multiplier = enchantmentManager.calculateLootingChanceMultiplier(levels.lootingLevel());
            collectBoostedDrops(itemDropList.getContainer(), multiplier, levels.lootingLevel(), dropListId, allDrops);
        }

        // 2. Add inventory drops (standard logic)
        if (role.isPickupDropOnDeath()) {
            Inventory inventory = npcComponent.getInventory();
            allDrops.addAll(inventory.getStorage().dropAllItemStacks());
        }

        // 3. Cook items if Burn is active
        if (levels.burnLevel() > 0) {
            allDrops = cookDrops(allDrops);
        }

        if (allDrops.isEmpty()) {
            return;
        }

        // Spawn items
        TransformComponent transformComponent = store.getComponent(ref, TransformComponent.getComponentType());
        HeadRotation headRotationComponent = store.getComponent(ref, HeadRotation.getComponentType());
        if (transformComponent == null || headRotationComponent == null) {
            return;
        }

        Vector3d dropPosition = transformComponent.getPosition().clone().add(0.0, 1.0, 0.0);
        Vector3f headRotation = headRotationComponent.getRotation();

        Holder<EntityStore>[] drops = ItemComponent.generateItemDrops(commandBuffer, allDrops, dropPosition,
                headRotation);
        if (drops.length > 0) {
            commandBuffer.addEntities(drops, AddReason.SPAWN);
            LOGGER.atFine().log("Spawned " + drops.length + " Looting-boosted item(s)");

            // Fire event if Looting triggered and we have a valid attacker
            Ref<EntityStore> attackerRef = null;
            if (deathInfo.getSource() instanceof Damage.EntitySource entitySource) {
                attackerRef = entitySource.getRef();
            }

            if (levels.lootingLevel() > 0 && attackerRef != null && enchantmentManager
                    .getWeaponFromEntity(EntityUtils.getEntity(attackerRef, commandBuffer)) != null) {
                com.hypixel.hytale.server.core.universe.PlayerRef playerRef = store.getComponent(attackerRef,
                        com.hypixel.hytale.server.core.universe.PlayerRef.getComponentType());
                ItemStack weapon = enchantmentManager
                        .getWeaponFromEntity(EntityUtils.getEntity(attackerRef, commandBuffer));
                EnchantmentEventHelper.fireActivated(playerRef, weapon, EnchantmentType.LOOTING, levels.lootingLevel());
            }
        }

        // Clean up stored enchantment data
        com.hypixel.hytale.server.core.entity.UUIDComponent uuidComp = commandBuffer.getComponent(ref,
                com.hypixel.hytale.server.core.entity.UUIDComponent.getComponentType());
        if (uuidComp != null) {
            enchantmentManager.removeDoTEnchantments(uuidComp.getUuid());
        }
    }

    /**
     * Recursively collects drops, applying chance multiplier to low-probability
     * items in MultipleItemDropContainer.
     */
    private void collectBoostedDrops(ItemDropContainer container, double multiplier, int lootingLevel,
            String dropListId, List<ItemStack> results) {
        ThreadLocalRandom random = ThreadLocalRandom.current();
        DoubleSupplier chanceProvider = random::nextDouble;
        Set<String> droplistReferences = new HashSet<>();
        droplistReferences.add(dropListId);

        if (container instanceof MultipleItemDropContainer multipleContainer) {
            try {
                ItemDropContainer[] children = (ItemDropContainer[]) MULTIPLE_CONTAINERS_FIELD.get(multipleContainer);

                for (ItemDropContainer child : children) {
                    double weight = child.getWeight();

                    double effectiveWeight = weight;
                    if (weight < 100.0) {
                        effectiveWeight = Math.min(100.0, weight * multiplier);
                    }

                    if (effectiveWeight >= random.nextDouble() * 100.0) {
                        collectBoostedDrops(child, multiplier, lootingLevel, dropListId, results);
                    }
                }

            } catch (IllegalAccessException e) {
                LOGGER.atWarning().log("Failed to reflectively access MultipleItemDropContainer: " + e.getMessage());
                ObjectArrayList<ItemDrop> fallbackDrops = new ObjectArrayList<>();
                container.populateDrops(fallbackDrops, chanceProvider, dropListId);
                convertItemDropsToStacks(fallbackDrops, lootingLevel, results);
            }
        } else {
            ObjectArrayList<ItemDrop> drops = new ObjectArrayList<>();
            container.populateDrops(drops, chanceProvider, dropListId);
            convertItemDropsToStacks(drops, lootingLevel, results);
        }
    }

    private void convertItemDropsToStacks(List<ItemDrop> drops, int lootingLevel, List<ItemStack> results) {
        ThreadLocalRandom random = ThreadLocalRandom.current();
        double quantityMultiplier = enchantmentManager.calculateLootingQuantityMultiplier(lootingLevel);

        for (ItemDrop drop : drops) {
            if (drop == null || drop.getItemId() == null)
                continue;

            int min = drop.getQuantityMin();
            int baseMax = drop.getQuantityMax();

            // Apply quantity multiplier to max quantity
            // e.g., 6 max * 1.75 (Looting III) = ~10.5 -> 11
            int newMax = (int) Math.round(baseMax * quantityMultiplier);

            // Ensure min <= newMax and at least 1 (unless base was 0, but drops usually
            // aren't 0)
            newMax = Math.max(Math.max(newMax, min), 1);

            int amount = random.nextInt(newMax - min + 1) + min;

            if (amount > 0) {
                results.add(new ItemStack(drop.getItemId(), amount, drop.getMetadata()));
            }
        }
    }

    /**
     * Cooks any cookable items in the drop list using the Campfire recipe registry.
     */
    private List<ItemStack> cookDrops(List<ItemStack> drops) {
        CookingRecipeRegistry cookingRegistry = enchantmentManager.getCookingRecipeRegistry();
        List<ItemStack> cookedDrops = new ArrayList<>();

        for (ItemStack drop : drops) {
            if (drop == null || drop.isEmpty()) {
                continue;
            }

            CookingRecipeRegistry.CookingRecipe recipe = cookingRegistry.getRecipe(drop);
            if (recipe == null) {
                cookedDrops.add(drop);
                continue;
            }

            ItemStack cookedOutput = recipe.createOutput(drop.getQuantity());
            if (cookedOutput == null || cookedOutput.isEmpty() || cookedOutput.getItemId().equals(drop.getItemId())) {
                cookedDrops.add(drop);
                continue;
            }

            cookedDrops.add(cookedOutput);
            LOGGER.atFine().log("Looting+Burn cooked " + drop.getItemId() + " -> " + cookedOutput.getItemId());
        }

        return cookedDrops;
    }

}
