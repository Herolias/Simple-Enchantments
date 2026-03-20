package org.herolias.plugin.enchantment;

import com.hypixel.hytale.component.CommandBuffer;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.component.dependency.Dependency;
import com.hypixel.hytale.component.dependency.Order;
import com.hypixel.hytale.component.dependency.SystemDependency;
import com.hypixel.hytale.component.query.Query;
import com.hypixel.hytale.logger.HytaleLogger;
import com.hypixel.hytale.math.vector.Vector3d;
import com.hypixel.hytale.math.vector.Vector3f;
import com.hypixel.hytale.server.core.asset.type.gameplay.DeathConfig;
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
import com.hypixel.hytale.server.core.modules.entity.tracker.NetworkId;
import com.hypixel.hytale.server.core.modules.item.ItemModule;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import com.hypixel.hytale.server.npc.entities.NPCEntity;
import com.hypixel.hytale.server.npc.role.Role;
import com.hypixel.hytale.server.npc.systems.NPCDamageSystems;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * ECS system that converts raw food drops to cooked versions when an entity
 * is killed by a weapon with the Burn enchantment.
 * 
 * Effect: Enemies drop cooked versions of any cookable items (based on Campfire
 * recipes)
 * Applicable to: Any weapon with the Burn enchantment (melee or ranged via
 * projectile)
 * 
 * This system runs BEFORE NPCDamageSystems.DropDeathItems and suppresses normal
 * drops
 * when Burn cooking is applied, spawning all items (cooked and non-cooked)
 * itself.
 */
public class EnchantmentBurnSmeltingSystem extends DeathSystems.OnDeathSystem {

    private static final HytaleLogger LOGGER = HytaleLogger.forEnclosingClass();
    private static final Query<EntityStore> QUERY = Query.and(
            NPCEntity.getComponentType(),
            TransformComponent.getComponentType(),
            HeadRotation.getComponentType(),
            Query.not(Player.getComponentType()));

    // Run BEFORE the built-in DropDeathItems system so we can suppress its drops
    private final Set<Dependency<EntityStore>> dependencies = Set.of(
            new SystemDependency(Order.BEFORE, NPCDamageSystems.DropDeathItems.class));

    private final EnchantmentManager enchantmentManager;
    private final CookingRecipeRegistry cookingRecipeRegistry;

    public EnchantmentBurnSmeltingSystem(EnchantmentManager enchantmentManager) {
        this.enchantmentManager = enchantmentManager;
        this.cookingRecipeRegistry = enchantmentManager.getCookingRecipeRegistry();
        LOGGER.atInfo().log("EnchantmentBurnSmeltingSystem initialized");
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

        EnchantmentManager.DamageEnchantments enchantments = enchantmentManager.resolveDamageEnchantments(deathInfo,
                commandBuffer, ref);
        int burnLevel = enchantments.burnLevel();
        int lootingLevel = enchantments.lootingLevel();

        // Check for Looting enchantment - if present, let EnchantmentLootingSystem
        // handle drops
        if (lootingLevel > 0) {
            return;
        }

        if (burnLevel <= 0) {
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

        // Get the normal drops for this entity
        List<ItemStack> baseDrops = itemModule.getRandomItemDrops(dropListId);

        // Also collect any inventory items that would be dropped (if applicable)
        List<ItemStack> allDrops = new ArrayList<>();
        if (role.isPickupDropOnDeath()) {
            Inventory inventory = npcComponent.getInventory();
            allDrops.addAll(inventory.getStorage().dropAllItemStacks());
        }
        allDrops.addAll(baseDrops);

        if (allDrops.isEmpty()) {
            return;
        }

        // Convert any cookable items to their cooked versions
        List<ItemStack> finalDrops = new ArrayList<>();
        boolean anyCookingApplied = false;

        for (ItemStack drop : allDrops) {
            if (drop == null || drop.isEmpty()) {
                continue;
            }

            CookingRecipeRegistry.CookingRecipe recipe = cookingRecipeRegistry.getRecipe(drop);
            if (recipe == null) {
                // No cooking recipe found, keep original
                finalDrops.add(drop);
                continue;
            }

            ItemStack cookedOutput = recipe.createOutput(drop.getQuantity());
            if (cookedOutput == null || cookedOutput.isEmpty() || cookedOutput.getItemId().equals(drop.getItemId())) {
                // Recipe produced invalid output or same item, keep original
                finalDrops.add(drop);
                continue;
            }

            anyCookingApplied = true;
            finalDrops.add(cookedOutput);
            LOGGER.atFine().log("Burn enchantment cooked " + drop.getItemId() + " -> " + cookedOutput.getItemId());
        }

        if (!anyCookingApplied) {
            // No cooking was applied, let the normal system handle drops
            return;
        }

        // CRITICAL: Suppress the built-in DropDeathItems system by setting
        // ItemsLossMode to NONE
        // This prevents the built-in system from spawning the raw items
        component.setItemsLossMode(DeathConfig.ItemsLossMode.NONE);

        // Get the entity's position and rotation for spawning items
        TransformComponent transformComponent = store.getComponent(ref, TransformComponent.getComponentType());
        HeadRotation headRotationComponent = store.getComponent(ref, HeadRotation.getComponentType());
        if (transformComponent == null || headRotationComponent == null) {
            return;
        }

        Vector3d dropPosition = transformComponent.getPosition().clone().add(0.0, 1.0, 0.0);
        Vector3f headRotation = headRotationComponent.getRotation();

        // Spawn ALL item drops ourselves (cooked versions where applicable)
        enchantmentManager.spawnDrops(commandBuffer, finalDrops, dropPosition, headRotation);
        LOGGER.atFine().log("Spawned " + finalDrops.size() + " item(s) with Burn cooking applied");

        EnchantmentManager.DamageContext ctx = enchantmentManager.getDamageContext(deathInfo, commandBuffer);
        if (ctx.hasAttacker()) {
            Entity shooterEntity = EntityUtils.getEntity(ctx.attackerRef(), commandBuffer);
            ItemStack weapon = enchantmentManager.getWeaponFromEntity(shooterEntity);
            if (weapon != null) {
                com.hypixel.hytale.server.core.universe.PlayerRef playerRef = store.getComponent(ctx.attackerRef(),
                        com.hypixel.hytale.server.core.universe.PlayerRef.getComponentType());
                EnchantmentEventHelper.fireActivated(playerRef, weapon, EnchantmentType.BURN, burnLevel);
            }
        }

        // Clean up stored enchantment data
        com.hypixel.hytale.server.core.entity.UUIDComponent uuidComp = commandBuffer.getComponent(ref,
                com.hypixel.hytale.server.core.entity.UUIDComponent.getComponentType());
        if (uuidComp != null) {
            enchantmentManager.removeBurnEnchantments(uuidComp.getUuid());
        }
    }

}
