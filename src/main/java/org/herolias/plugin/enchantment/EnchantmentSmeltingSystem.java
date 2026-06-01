package org.herolias.plugin.enchantment;

import com.hypixel.hytale.component.Archetype;
import com.hypixel.hytale.component.ArchetypeChunk;
import com.hypixel.hytale.component.CommandBuffer;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.component.query.Query;
import com.hypixel.hytale.component.system.EntityEventSystem;
import com.hypixel.hytale.logger.HytaleLogger;
import com.hypixel.hytale.server.core.asset.type.blocktype.config.BlockBreakingDropType;
import com.hypixel.hytale.server.core.asset.type.blocktype.config.BlockGathering;
import com.hypixel.hytale.server.core.asset.type.blocktype.config.BlockType;
import com.hypixel.hytale.server.core.event.events.ecs.BreakBlockEvent;
import com.hypixel.hytale.server.core.inventory.ItemStack;
import com.hypixel.hytale.server.core.modules.interaction.BlockHarvestUtils;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;

import org.joml.Vector3i;

import javax.annotation.Nonnull;
import java.util.List;

/**
 * Marks block drops for Smelting conversion without cancelling BreakBlockEvent.
 * Keeping the vanilla break event alive lets protection, quest, and leveling mods
 * observe the mined ore while the paired drop conversion system rewrites the
 * spawned item entity before it can be picked up or sent to clients.
 */
public class EnchantmentSmeltingSystem extends EntityEventSystem<EntityStore, BreakBlockEvent> {

    private static final HytaleLogger LOGGER = HytaleLogger.forEnclosingClass();

    private final EnchantmentManager enchantmentManager;
    private final SmeltingRecipeRegistry smeltingRecipeRegistry;
    private final EnchantmentSmeltingDropConversionSystem dropConversionSystem;

    public EnchantmentSmeltingSystem(EnchantmentManager enchantmentManager,
            EnchantmentSmeltingDropConversionSystem dropConversionSystem) {
        super(BreakBlockEvent.class);
        this.enchantmentManager = enchantmentManager;
        this.smeltingRecipeRegistry = enchantmentManager.getSmeltingRecipeRegistry();
        this.dropConversionSystem = dropConversionSystem;
        LOGGER.atInfo().log("EnchantmentSmeltingSystem initialized");
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
        if (event.isCancelled() || dropConversionSystem == null) {
            return;
        }

        ItemStack tool = event.getItemInHand();
        if (tool == null || tool.isEmpty()) {
            return;
        }

        if (!enchantmentManager.hasEnchantment(tool, EnchantmentType.SMELTING)
                || enchantmentManager.hasEnchantment(tool, EnchantmentType.PICK_PERFECT)) {
            return;
        }

        if (enchantmentManager.categorizeItem(tool) != ItemCategory.PICKAXE) {
            return;
        }

        BlockType blockType = event.getBlockType();
        if (blockType == null) {
            return;
        }

        BlockGathering gathering = blockType.getGathering();
        BlockBreakingDropType breaking = gathering != null ? gathering.getBreaking() : null;
        if (breaking == null) {
            return;
        }

        List<ItemStack> baseDrops = BlockHarvestUtils.getDrops(blockType, breaking.getQuantity(), breaking.getItemId(),
                breaking.getDropListId());
        if (!containsSmeltableDrop(baseDrops)) {
            return;
        }

        PlayerRef playerRef = null;
        Ref<EntityStore> breakerRef = archetypeChunk.getReferenceTo(index);
        if (breakerRef != null && breakerRef.isValid()) {
            playerRef = store.getComponent(breakerRef, PlayerRef.getComponentType());
        }

        Vector3i targetBlock = event.getTargetBlock();
        dropConversionSystem.recordPending(store.getExternalData().getWorld(), targetBlock, playerRef, tool);
    }

    private boolean containsSmeltableDrop(@Nonnull List<ItemStack> drops) {
        for (ItemStack drop : drops) {
            if (drop == null || drop.isEmpty()) {
                continue;
            }

            SmeltingRecipeRegistry.SmeltingRecipe recipe = smeltingRecipeRegistry.getRecipe(drop);
            if (recipe == null) {
                continue;
            }

            ItemStack output = recipe.createOutput(drop.getQuantity());
            if (output != null && !output.isEmpty() && !output.getItemId().equals(drop.getItemId())) {
                return true;
            }
        }
        return false;
    }
}
