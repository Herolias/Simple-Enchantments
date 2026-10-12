package org.herolias.plugin.enchantment;

import com.hypixel.hytale.component.AddReason;
import com.hypixel.hytale.component.ArchetypeChunk;
import com.hypixel.hytale.component.CommandBuffer;
import com.hypixel.hytale.component.Holder;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.component.dependency.Dependency;
import com.hypixel.hytale.component.dependency.Order;
import com.hypixel.hytale.component.dependency.SystemDependency;
import com.hypixel.hytale.component.query.Query;
import com.hypixel.hytale.component.system.tick.EntityTickingSystem;
import com.hypixel.hytale.logger.HytaleLogger;
import com.hypixel.hytale.math.util.MathUtil;
import com.hypixel.hytale.math.vector.Rotation3f;
import com.hypixel.hytale.server.core.asset.type.gameplay.DeathConfig;
import com.hypixel.hytale.server.core.asset.type.item.config.ItemDrop;
import com.hypixel.hytale.server.core.asset.type.item.config.ItemDropList;
import com.hypixel.hytale.server.core.asset.type.item.config.container.ItemDropContainer;
import com.hypixel.hytale.server.core.asset.type.item.config.container.MultipleItemDropContainer;
import com.hypixel.hytale.server.core.entity.UUIDComponent;
import com.hypixel.hytale.server.core.entity.entities.Player;
import com.hypixel.hytale.server.core.inventory.InventoryComponent;
import com.hypixel.hytale.server.core.inventory.ItemStack;
import com.hypixel.hytale.server.core.modules.entity.component.HeadRotation;
import com.hypixel.hytale.server.core.modules.entity.component.TransformComponent;
import com.hypixel.hytale.server.core.modules.entity.damage.Damage;
import com.hypixel.hytale.server.core.modules.entity.damage.DeathComponent;
import com.hypixel.hytale.server.core.modules.entity.damage.DeathSystems;
import com.hypixel.hytale.server.core.modules.entity.damage.DeferredCorpseRemoval;
import com.hypixel.hytale.server.core.modules.entity.item.ItemComponent;
import com.hypixel.hytale.server.core.modules.item.ItemModule;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import com.hypixel.hytale.server.npc.entities.NPCEntity;
import com.hypixel.hytale.server.npc.role.Role;
import com.hypixel.hytale.server.npc.systems.NPCDamageSystems;
import it.unimi.dsi.fastutil.objects.ObjectArrayList;
import org.joml.Vector3d;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.lang.reflect.Field;
import java.util.ArrayList;
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
 * <p>
 * The system mirrors vanilla {@code NPCDamageSystems.DropDeathItems}: it is a
 * ticking system over dead NPCs that drops on the very same tick vanilla would
 * (instantly, or once the corpse timer has run out), and only when it decides
 * to take the drops over does it mark the role as dropped
 * ({@code Role.setDeathItemsDropped()}), switch the death to
 * {@code ItemsLossMode.NONE} and empty the NPC's storage. Deaths without
 * Looting are left untouched for vanilla.
 */
public class EnchantmentLootingSystem extends EntityTickingSystem<EntityStore> {

    private static final HytaleLogger LOGGER = HytaleLogger.forEnclosingClass();

    /** Same scope as vanilla DropDeathItems. */
    private static final Query<EntityStore> QUERY = Query.and(
            NPCEntity.getComponentType(),
            TransformComponent.getComponentType(),
            HeadRotation.getComponentType(),
            Query.not(Player.getComponentType()),
            DeathComponent.getComponentType());

    // Same window as vanilla DropDeathItems (after the corpse timer ticked), but
    // strictly before it so we can claim the drops first.
    private final Set<Dependency<EntityStore>> dependencies = Set.of(
            new SystemDependency(Order.AFTER, DeathSystems.TickCorpseRemoval.class),
            new SystemDependency(Order.BEFORE, NPCDamageSystems.DropDeathItems.class));

    private static final EnchantmentManager.DamageEnchantments NO_ENCHANTMENTS = new EnchantmentManager.DamageEnchantments(
            0, 0);

    // MultipleItemDropContainer exposes neither its children nor MinCount/MaxCount
    // through getters (verified against the decompiled server), so they are read
    // reflectively. A failure falls back to vanilla population (no boost).
    @Nullable
    private static final Field MULTIPLE_CONTAINERS_FIELD = lookupField("containers");
    @Nullable
    private static final Field MULTIPLE_MIN_COUNT_FIELD = lookupField("minCount");
    @Nullable
    private static final Field MULTIPLE_MAX_COUNT_FIELD = lookupField("maxCount");

    @Nullable
    private static Field lookupField(String name) {
        try {
            Field field = MultipleItemDropContainer.class.getDeclaredField(name);
            field.setAccessible(true);
            return field;
        } catch (NoSuchFieldException | RuntimeException e) {
            LOGGER.atWarning().withCause(e).log(
                    "MultipleItemDropContainer.%s is not accessible; Looting will not boost nested drop containers",
                    name);
            return null;
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
    public boolean isParallel(int archetypeChunkSize, int taskCount) {
        return false;
    }

    @Override
    public void tick(float dt, int index,
            @Nonnull ArchetypeChunk<EntityStore> archetypeChunk,
            @Nonnull Store<EntityStore> store,
            @Nonnull CommandBuffer<EntityStore> commandBuffer) {
        DeathComponent deathComponent = archetypeChunk.getComponent(index, DeathComponent.getComponentType());
        NPCEntity npcComponent = archetypeChunk.getComponent(index, NPCEntity.getComponentType());
        if (deathComponent == null || npcComponent == null) {
            return;
        }
        Role role = npcComponent.getRole();
        if (role == null) {
            return;
        }

        UUIDComponent uuidComponent = archetypeChunk.getComponent(index, UUIDComponent.getComponentType());

        // Somebody else (vanilla, or another death system that already resolved the
        // kill) owns these drops: nothing to decide, but never keep the DoT
        // attribution around for a resolved death.
        if (deathComponent.getItemsLossMode() != DeathConfig.ItemsLossMode.ALL || role.hasDroppedDeathItems()) {
            removeDoTEnchantments(uuidComponent);
            return;
        }

        // --- Vanilla timing: instantly, or once the corpse is about to be removed ---
        if (!role.isDropDeathItemsInstantly()) {
            DeferredCorpseRemoval deferredRemoval = archetypeChunk.getComponent(index,
                    DeferredCorpseRemoval.getComponentType());
            if (deferredRemoval != null && !deferredRemoval.shouldRemove()) {
                return;
            }
        }

        // --- Read-only decision pass ---
        Ref<EntityStore> ref = archetypeChunk.getReferenceTo(index);
        Damage deathInfo = deathComponent.getDeathInfo();
        EnchantmentManager.DamageEnchantments levels = deathInfo != null
                ? enchantmentManager.resolveDamageEnchantments(deathInfo, commandBuffer, ref)
                : NO_ENCHANTMENTS;

        // The death is being resolved on this tick; the DoT attribution has served
        // its purpose whatever we decide below.
        removeDoTEnchantments(uuidComponent);

        if (levels.lootingLevel() <= 0) {
            // No Looting: leave the drops to vanilla (or the Burn cooking system)
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

        TransformComponent transformComponent = archetypeChunk.getComponent(index,
                TransformComponent.getComponentType());
        HeadRotation headRotationComponent = archetypeChunk.getComponent(index, HeadRotation.getComponentType());
        if (transformComponent == null || headRotationComponent == null) {
            return;
        }

        // --- Decision made: take the drops over (mirrors DropDeathItems) ---
        role.setDeathItemsDropped();
        deathComponent.setItemsLossMode(DeathConfig.ItemsLossMode.NONE);

        List<ItemStack> allDrops = new ArrayList<>();

        // 1. Calculate boosted drops from Drop List
        ItemDropList itemDropList = ItemDropList.getAssetMap().getAsset(dropListId);
        if (itemDropList != null && itemDropList.getContainer() != null) {
            double multiplier = enchantmentManager.calculateLootingChanceMultiplier(levels.lootingLevel());
            collectBoostedDrops(itemDropList.getContainer(), multiplier, levels.lootingLevel(), dropListId, allDrops);
        }

        // 2. Add inventory drops (standard logic)
        if (role.isPickupDropOnDeath()) {
            InventoryComponent.Storage storageComponent = archetypeChunk.getComponent(index,
                    InventoryComponent.Storage.getComponentType());
            if (storageComponent != null) {
                allDrops.addAll(storageComponent.getInventory().dropAllItemStacks());
            }
        }

        // 3. Cook items if Burn is active
        if (levels.burnLevel() > 0) {
            allDrops = cookDrops(allDrops);
        }

        if (allDrops.isEmpty()) {
            return;
        }

        // Spawn items
        Vector3d dropPosition = new Vector3d(transformComponent.getPosition()).add(0.0, 1.0, 0.0);
        Rotation3f headRotation = headRotationComponent.getRotation();

        Holder<EntityStore>[] drops = ItemComponent.generateItemDrops(commandBuffer, allDrops, dropPosition,
                headRotation);
        if (drops.length == 0) {
            return;
        }
        commandBuffer.addEntities(drops, AddReason.SPAWN);
        LOGGER.atFine().log("Spawned %d Looting-boosted item(s)", drops.length);

        // Fire event if we have a valid attacker still holding a weapon
        if (deathInfo != null && deathInfo.getSource() instanceof Damage.EntitySource entitySource) {
            Ref<EntityStore> attackerRef = entitySource.getRef();
            ItemStack weapon = enchantmentManager.getWeaponFromEntity(attackerRef, commandBuffer);
            if (weapon != null) {
                PlayerRef playerRef = store.getComponent(attackerRef, PlayerRef.getComponentType());
                EnchantmentEventHelper.fireActivated(playerRef, weapon, EnchantmentType.LOOTING,
                        levels.lootingLevel());
            }
        }
    }

    private void removeDoTEnchantments(@Nullable UUIDComponent uuidComponent) {
        if (uuidComponent != null) {
            enchantmentManager.removeDoTEnchantments(uuidComponent.getUuid());
        }
    }

    /**
     * Recursively collects drops, applying chance multiplier to low-probability
     * items in MultipleItemDropContainer. Honours {@code MinCount}/{@code MaxCount}
     * exactly like {@code MultipleItemDropContainer.populateDrops}.
     */
    private void collectBoostedDrops(ItemDropContainer container, double multiplier, int lootingLevel,
            String dropListId, List<ItemStack> results) {
        ThreadLocalRandom random = ThreadLocalRandom.current();
        DoubleSupplier chanceProvider = random::nextDouble;

        ItemDropContainer[] children = container instanceof MultipleItemDropContainer multipleContainer
                ? childrenOf(multipleContainer)
                : null;
        if (children == null) {
            // Not a multiple container (or its internals are inaccessible): vanilla population
            ObjectArrayList<ItemDrop> drops = new ObjectArrayList<>();
            container.populateDrops(drops, chanceProvider, dropListId);
            convertItemDropsToStacks(drops, lootingLevel, results);
            return;
        }

        MultipleItemDropContainer multipleContainer = (MultipleItemDropContainer) container;
        int minCount = readInt(MULTIPLE_MIN_COUNT_FIELD, multipleContainer, 1);
        int maxCount = readInt(MULTIPLE_MAX_COUNT_FIELD, multipleContainer, 1);
        int count = (int) MathUtil.fastRound(random.nextDouble() * (double) (maxCount - minCount) + (double) minCount);

        for (int roll = 0; roll < count; roll++) {
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
        }
    }

    @Nullable
    private static ItemDropContainer[] childrenOf(@Nonnull MultipleItemDropContainer container) {
        if (MULTIPLE_CONTAINERS_FIELD == null) {
            return null;
        }
        try {
            return (ItemDropContainer[]) MULTIPLE_CONTAINERS_FIELD.get(container);
        } catch (IllegalAccessException | RuntimeException e) {
            LOGGER.atWarning().withCause(e).log("Failed to read MultipleItemDropContainer children");
            return null;
        }
    }

    private static int readInt(@Nullable Field field, @Nonnull Object target, int fallback) {
        if (field == null) {
            return fallback;
        }
        try {
            return field.getInt(target);
        } catch (IllegalAccessException | RuntimeException e) {
            return fallback;
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
            LOGGER.atFine().log("Looting+Burn cooked %s -> %s", drop.getItemId(), cookedOutput.getItemId());
        }

        return cookedDrops;
    }

}
