package org.herolias.plugin.enchantment;

import com.hypixel.hytale.component.AddReason;
import com.hypixel.hytale.component.Archetype;
import com.hypixel.hytale.component.ArchetypeChunk;
import com.hypixel.hytale.component.CommandBuffer;
import com.hypixel.hytale.component.Holder;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.component.query.Query;
import com.hypixel.hytale.component.system.EntityEventSystem;
import com.hypixel.hytale.logger.HytaleLogger;
import com.hypixel.hytale.math.util.ChunkUtil;
import com.hypixel.hytale.math.vector.Vector3d;
import com.hypixel.hytale.math.vector.Vector3f;
import com.hypixel.hytale.math.vector.Vector3i;
import com.hypixel.hytale.server.core.asset.type.blocktype.config.BlockBreakingDropType;
import com.hypixel.hytale.server.core.asset.type.blocktype.config.BlockGathering;
import com.hypixel.hytale.server.core.asset.type.blocktype.config.BlockType;
import com.hypixel.hytale.server.core.event.events.ecs.BreakBlockEvent;
import com.hypixel.hytale.server.core.inventory.ItemStack;
import com.hypixel.hytale.server.core.modules.entity.item.ItemComponent;
import com.hypixel.hytale.server.core.modules.interaction.BlockHarvestUtils;
import com.hypixel.hytale.server.core.modules.interaction.BlockInteractionUtils;
import com.hypixel.hytale.server.core.universe.world.World;
import com.hypixel.hytale.server.core.universe.world.chunk.BlockChunk;
import com.hypixel.hytale.server.core.universe.world.chunk.section.BlockSection;
import com.hypixel.hytale.server.core.universe.world.storage.ChunkStore;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;

import javax.annotation.Nonnull;
import java.util.ArrayList;
import java.util.List;

/**
 * ECS system that applies Smelting enchantment to block break events.
 */
public class EnchantmentSmeltingSystem extends EntityEventSystem<EntityStore, BreakBlockEvent> {

    private static final HytaleLogger LOGGER = HytaleLogger.forEnclosingClass();

    private final EnchantmentManager enchantmentManager;
    private final SmeltingRecipeRegistry smeltingRecipeRegistry;

    public EnchantmentSmeltingSystem(EnchantmentManager enchantmentManager) {
        super(BreakBlockEvent.class);
        this.enchantmentManager = enchantmentManager;
        this.smeltingRecipeRegistry = enchantmentManager.getSmeltingRecipeRegistry();
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
        if (event.isCancelled()) {
            return;
        }

        ItemStack tool = event.getItemInHand();
        if (tool == null || tool.isEmpty()) {
            return;
        }

        if (!enchantmentManager.hasEnchantment(tool, EnchantmentType.SMELTING)) {
            return;
        }

        if (!enchantmentManager.isPickaxeItem(tool.getItemId())) {
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

        List<ItemStack> baseDrops = BlockHarvestUtils.getDrops(blockType, breaking.getQuantity(), breaking.getItemId(), breaking.getDropListId());
        if (baseDrops.isEmpty()) {
            return;
        }

        List<ItemStack> smeltedDrops = new ArrayList<>();
        boolean anySmelted = false;
        for (ItemStack drop : baseDrops) {
            if (drop == null || drop.isEmpty()) {
                continue;
            }
            SmeltingRecipeRegistry.SmeltingRecipe recipe = smeltingRecipeRegistry.getRecipe(drop);
            if (recipe == null) {
                smeltedDrops.add(drop);
                continue;
            }

            ItemStack output = recipe.createOutput(drop.getQuantity());
            if (output == null || output.isEmpty() || output.getItemId().equals(drop.getItemId())) {
                smeltedDrops.add(drop);
                continue;
            }

            anySmelted = true;
            smeltedDrops.add(output);
        }

        if (!anySmelted) {
            return;
        }

        // Apply Fortune extra rolls on top of smelted drops (if applicable).
        // Apply Fortune extra rolls on top of smelted drops (if applicable).
        int fortuneLevel = enchantmentManager.getEnchantmentLevel(tool, EnchantmentType.FORTUNE);
        if (fortuneLevel > 0) {
            List<ItemStack> extraDrops = enchantmentManager.getFortuneDrops(blockType, breaking, fortuneLevel);
            for (ItemStack drop : extraDrops) {
                if (drop == null || drop.isEmpty()) {
                    continue;
                }
                SmeltingRecipeRegistry.SmeltingRecipe recipe = smeltingRecipeRegistry.getRecipe(drop);
                ItemStack output = recipe != null ? recipe.createOutput(drop.getQuantity()) : null;
                smeltedDrops.add(output != null && !output.isEmpty() ? output : drop);
            }
        }

        if (smeltedDrops.isEmpty()) {
            return;
        }

        Vector3i targetBlock = event.getTargetBlock();
        World world = store.getExternalData().getWorld();
        Store<ChunkStore> chunkStore = world.getChunkStore().getStore();
        Ref<ChunkStore> chunkRef = chunkStore.getExternalData().getChunkReference(
            ChunkUtil.indexChunkFromBlock(targetBlock.getX(), targetBlock.getZ())
        );
        if (chunkRef == null || !chunkRef.isValid()) {
            return;
        }

        BlockChunk blockChunk = chunkStore.getComponent(chunkRef, BlockChunk.getComponentType());
        if (blockChunk == null) {
            return;
        }

        BlockSection blockSection = blockChunk.getSectionAtBlockY(targetBlock.getY());
        int filler = blockSection.getFiller(targetBlock.getX(), targetBlock.getY(), targetBlock.getZ());

        Ref<EntityStore> breakerRef = archetypeChunk.getReferenceTo(index);
        boolean naturalAction = breakerRef != null && breakerRef.isValid()
            ? BlockInteractionUtils.isNaturalAction(breakerRef, store)
            : BlockInteractionUtils.isNaturalAction(null, store);

        int setBlockSettings = 0;
        setBlockSettings |= 0x100;
        if (!naturalAction) {
            setBlockSettings |= 0x800;
        }

        event.setCancelled(true);
        BlockHarvestUtils.naturallyRemoveBlock(targetBlock, blockType, filler, 0, null, null, setBlockSettings, chunkRef, store, chunkStore);

        Vector3d dropPosition = new Vector3d(targetBlock.getX() + 0.5, targetBlock.getY(), targetBlock.getZ() + 0.5);
        Holder<EntityStore>[] itemEntities = ItemComponent.generateItemDrops(commandBuffer, smeltedDrops, dropPosition, Vector3f.ZERO);
        if (itemEntities.length > 0) {
            commandBuffer.addEntities(itemEntities, AddReason.SPAWN);
        }
    }
}
