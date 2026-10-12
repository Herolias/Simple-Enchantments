package org.herolias.plugin.enchantment;

import com.hypixel.hytale.component.Archetype;
import com.hypixel.hytale.component.ArchetypeChunk;
import com.hypixel.hytale.component.CommandBuffer;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.component.dependency.Dependency;
import com.hypixel.hytale.component.dependency.Order;
import com.hypixel.hytale.component.dependency.SystemDependency;
import com.hypixel.hytale.component.query.Query;
import com.hypixel.hytale.component.system.EntityEventSystem;
import com.hypixel.hytale.logger.HytaleLogger;
import com.hypixel.hytale.math.util.ChunkUtil;
import org.joml.Vector3d;
import org.joml.Vector3i;
import com.hypixel.hytale.server.core.asset.type.blocktype.config.BlockBreakingDropType;
import com.hypixel.hytale.server.core.asset.type.blocktype.config.BlockGathering;
import com.hypixel.hytale.server.core.asset.type.blocktype.config.BlockType;
import com.hypixel.hytale.server.core.asset.type.item.config.Item;
import com.hypixel.hytale.server.core.asset.type.item.config.ItemTool;
import com.hypixel.hytale.server.core.entity.ItemUtils;
import com.hypixel.hytale.server.core.event.events.ecs.BreakBlockEvent;
import com.hypixel.hytale.server.core.event.events.ecs.DamageBlockEvent;
import com.hypixel.hytale.server.core.modules.interaction.BlockHarvestUtils;
import com.hypixel.hytale.server.core.inventory.ItemStack;
import com.hypixel.hytale.server.core.modules.blockhealth.BlockHealthChunk;
import com.hypixel.hytale.server.core.modules.blockhealth.BlockHealthModule;
import com.hypixel.hytale.server.core.modules.blockset.BlockSetModule;
import com.hypixel.hytale.server.core.modules.interaction.BlockInteractionUtils;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.hypixel.hytale.server.core.universe.world.World;
import com.hypixel.hytale.server.core.universe.world.chunk.section.BlockSection;
import com.hypixel.hytale.server.core.universe.world.storage.ChunkStore;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import org.herolias.plugin.util.InventoryAccess;

import javax.annotation.Nonnull;
import java.util.Collections;
import java.util.List;
import java.util.Set;

/**
 * ECS system that applies Silk Touch enchantment to block break events.
 * Drops the block itself instead of the usual drops.
 *
 * We hook into DamageBlockEvent to detect when the block is about to be
 * destroyed.
 * This prevents the vanilla block dropping logic (which ignores BreakBlockEvent
 * cancellation
 * for things like gravel/rubble that have specific tool drops) from triggering.
 *
 * <p>
 * Runs after {@link EnchantmentBlockDamageSystem} so the "will this hit break
 * the block" check sees the Efficiency-boosted damage.
 * </p>
 */
public class EnchantmentSilktouchSystem extends EntityEventSystem<EntityStore, DamageBlockEvent> {

    private static final HytaleLogger LOGGER = HytaleLogger.forEnclosingClass();

    private final Set<Dependency<EntityStore>> dependencies = Set.of(
            new SystemDependency<>(Order.AFTER, EnchantmentBlockDamageSystem.class));

    private final EnchantmentManager enchantmentManager;

    public EnchantmentSilktouchSystem(EnchantmentManager enchantmentManager) {
        super(DamageBlockEvent.class);
        this.enchantmentManager = enchantmentManager;
        LOGGER.atInfo().log("EnchantmentSilktouchSystem initialized");
    }

    @Override
    @Nonnull
    public Query<EntityStore> getQuery() {
        return Archetype.empty();
    }

    @Override
    @Nonnull
    public Set<Dependency<EntityStore>> getDependencies() {
        return dependencies;
    }

    @Override
    public void handle(int index,
            @Nonnull ArchetypeChunk<EntityStore> archetypeChunk,
            @Nonnull Store<EntityStore> store,
            @Nonnull CommandBuffer<EntityStore> commandBuffer,
            @Nonnull DamageBlockEvent event) {
        if (event.isCancelled()) {
            return;
        }

        ItemStack tool = event.getItemInHand();
        if (tool == null || tool.isEmpty()) {
            return;
        }

        // Single metadata read; reused for the activation event below.
        int pickPerfectLevel = enchantmentManager.getEnchantmentLevel(tool, EnchantmentType.PICK_PERFECT);
        if (pickPerfectLevel <= 0) {
            return;
        }

        if (!enchantmentManager.isTool(tool)) {
            return;
        }

        BlockType blockType = event.getBlockType();
        if (blockType == null) {
            return;
        }

        // Get the item representation of the block
        Item blockItem = blockType.getItem();
        if (blockItem == null) {
            return;
        }

        // Check if the item representation of the block is blacklisted
        String blockItemId = blockItem.getId();
        if (enchantmentManager.isPickPerfectBlacklistedItem(blockItemId)) {
            return;
        }

        // Only trigger Silk Touch if the block is going to break on this hit
        Vector3i targetBlock = event.getTargetBlock();
        World world = store.getExternalData().getWorld();
        ChunkStore chunkStoreManager = world.getChunkStore();
        Store<ChunkStore> chunkStore = chunkStoreManager.getStore();

        Ref<ChunkStore> sectionRef = chunkStoreManager.getChunkSectionReferenceAtBlock(
                targetBlock.x(), targetBlock.y(), targetBlock.z());
        if (sectionRef == null || !sectionRef.isValid()) {
            return;
        }

        long chunkIndex = ChunkUtil.indexChunkFromBlock(targetBlock.x(), targetBlock.z());
        Ref<ChunkStore> columnRef = chunkStoreManager.getChunkReference(chunkIndex);
        if (columnRef == null || !columnRef.isValid()) {
            return;
        }

        BlockHealthChunk healthChunk = chunkStore.getComponent(columnRef,
                BlockHealthModule.get().getBlockHealthChunkComponentType());
        if (healthChunk == null) {
            return;
        }

        boolean willBreak = healthChunk.isBlockFragile(targetBlock)
                || (event.getCurrentDamage() - event.getDamage() <= 0.001f);
        if (!willBreak) {
            return;
        }

        // Resolve drops using the block's gathering data for correct quantities.
        // When a drop list exists AND all its resolved items match the block's own item,
        // use the resolved drops (handles combined half slabs dropping 2 instead of 1).
        // Otherwise fall back to 1x block item (silk touch: always drop the block itself).
        List<ItemStack> silkTouchDrops;
        BlockGathering gathering = blockType.getGathering();
        BlockBreakingDropType breaking = gathering != null ? gathering.getBreaking() : null;

        if (breaking != null && breaking.getDropListId() != null) {
            List<ItemStack> resolvedDrops = BlockHarvestUtils.getDrops(blockType, breaking.getQuantity(),
                    null, breaking.getDropListId());
            boolean allMatchBlockItem = !resolvedDrops.isEmpty();
            for (ItemStack drop : resolvedDrops) {
                if (drop.getItem() == null || !blockItemId.equals(drop.getItem().getId())) {
                    allMatchBlockItem = false;
                    break;
                }
            }
            silkTouchDrops = allMatchBlockItem
                    ? resolvedDrops
                    : Collections.singletonList(new ItemStack(blockItemId, 1));
        } else {
            silkTouchDrops = Collections.singletonList(new ItemStack(blockItemId, 1));
        }

        Ref<EntityStore> breakerRef = archetypeChunk.getReferenceTo(index);

        if (breakerRef != null && breakerRef.isValid()) {
            BreakBlockEvent protectionEvent = new BreakBlockEvent(tool, targetBlock, blockType);
            commandBuffer.invoke(breakerRef, protectionEvent);
            if (protectionEvent.isCancelled()) {
                event.setCancelled(true);
                return;
            }

            if (!protectionEvent.getTargetBlock().equals(targetBlock)) {
                return;
            }
        }

        BlockSection blockSection = chunkStore.getComponent(sectionRef, BlockSection.getComponentType());
        if (blockSection == null)
            return;

        int setBlockSettings = 0;
        setBlockSettings |= 0x100;
        boolean naturalAction = breakerRef != null && breakerRef.isValid()
                ? BlockInteractionUtils.isNaturalAction(breakerRef, store)
                : BlockInteractionUtils.isNaturalAction(null, store);

        if (!naturalAction) {
            setBlockSettings |= 0x800; // Suppress entity drops if unnatural
        }

        int filler = blockSection.getFiller(targetBlock.x(), targetBlock.y(), targetBlock.z());

        // IMPORTANT: Cancel the DamageBlockEvent so vanilla drops don't trigger after
        // protection listeners have had their normal BreakBlockEvent chance to deny.
        event.setCancelled(true);

        PlayerRef playerRef = null;
        if (breakerRef != null && breakerRef.isValid()) {
            playerRef = store.getComponent(breakerRef, PlayerRef.getComponentType());
        }

        BlockHarvestUtils.naturallyRemoveBlock(targetBlock, blockType, filler, 0, null, null, setBlockSettings,
                sectionRef, store, chunkStore);

        // Spawn the Silk Touch drops
        Vector3d dropPosition = new Vector3d(targetBlock.x() + 0.5, targetBlock.y(), targetBlock.z() + 0.5);
        enchantmentManager.spawnDrops(commandBuffer, silkTouchDrops, dropPosition);

        EnchantmentEventHelper.fireActivated(playerRef, tool, EnchantmentType.PICK_PERFECT, pickPerfectLevel);

        // Apply durability manually, since we cancelled DamageBlockEvent and bypassed
        // the vanilla BlockHarvestUtils.performBlockDamage durability path.
        // Match vanilla's durability calculation exactly (soft block check, block
        // type/set overrides, etc.). Update 6 made the engine helper private.
        if (breakerRef != null && breakerRef.isValid()) {
            InventoryAccess.HeldSlot held = InventoryAccess.getHeldSlot(commandBuffer, breakerRef);
            if (held != null
                    && ItemUtils.canDecreaseItemStackDurability(breakerRef, store)
                    && !tool.isUnbreakable()) {
                double durabilityLoss = calculateDurabilityUse(tool.getItem(), blockType);
                if (durabilityLoss > 0) {
                    ItemUtils.updateItemStackDurability(breakerRef, tool, held.container(), held.slot(),
                            -durabilityLoss, store);
                }
            }
        }
    }

    /**
     * Update 6 made Hytale's equivalent helper private. Keep the same calculation
     * here so Silk Touch still consumes exactly the durability a normal break would.
     */
    private static double calculateDurabilityUse(@Nonnull Item item, @Nonnull BlockType blockType) {
        BlockGathering gathering = blockType.getGathering();
        if (gathering == null || gathering.isSoft() || item.getTool() == null) {
            return 0.0;
        }

        ItemTool itemTool = item.getTool();
        ItemTool.DurabilityLossBlockTypes[] overrides = itemTool.getDurabilityLossBlockTypes();
        if (overrides == null) {
            return item.getDurabilityLossOnHit();
        }

        String blockTypeId = blockType.getId();
        int blockTypeIndex = BlockType.getAssetMap().getIndex(blockTypeId);
        if (blockTypeIndex == Integer.MIN_VALUE) {
            throw new IllegalArgumentException("Unknown block type: " + blockTypeId);
        }

        for (ItemTool.DurabilityLossBlockTypes override : overrides) {
            int[] blockTypeIndexes = override.getBlockTypeIndexes();
            if (blockTypeIndexes != null) {
                for (int candidate : blockTypeIndexes) {
                    if (candidate == blockTypeIndex) {
                        return override.getDurabilityLossOnHit();
                    }
                }
            }

            int[] blockSetIndexes = override.getBlockSetIndexes();
            if (blockSetIndexes != null) {
                for (int blockSetIndex : blockSetIndexes) {
                    if (blockInSet(blockSetIndex, blockTypeId)) {
                        return override.getDurabilityLossOnHit();
                    }
                }
            }
        }

        return item.getDurabilityLossOnHit();
    }

    /**
     * {@link BlockSetModule} is deprecated for removal, but vanilla
     * {@code BlockHarvestUtils.calculateDurabilityUse} still resolves block-set
     * durability overrides through it and the server offers no replacement yet.
     * This is the single place the mod touches it, so the eventual migration is
     * a one-line change.
     */
    @SuppressWarnings("removal")
    private static boolean blockInSet(int blockSetIndex, @Nonnull String blockTypeId) {
        return BlockSetModule.getInstance().blockInSet(blockSetIndex, blockTypeId);
    }
}
