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
import com.hypixel.hytale.server.core.asset.type.item.config.Item;
import com.hypixel.hytale.server.core.asset.type.item.config.ItemDrop;
import com.hypixel.hytale.server.core.asset.type.item.config.ItemDropList;
import com.hypixel.hytale.server.core.event.events.ecs.BreakBlockEvent;
import com.hypixel.hytale.server.core.inventory.ItemStack;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;

import org.joml.Vector3i;

import javax.annotation.Nonnull;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Marks block drops for Smelting conversion without cancelling BreakBlockEvent.
 * Keeping the vanilla break event alive lets protection, quest, and leveling mods
 * observe the mined ore while the paired drop conversion system rewrites the
 * spawned item entity before it can be picked up or sent to clients.
 *
 * <p>
 * Whether a block is smeltable is decided from its <em>static</em> drop list
 * (every item the list can produce), not from a random roll, and cached per
 * block type id. The cache is keyed to the smelting registry's generation so a
 * recipe reload invalidates it.
 * </p>
 */
public class EnchantmentSmeltingSystem extends EntityEventSystem<EntityStore, BreakBlockEvent> {

    private static final HytaleLogger LOGGER = HytaleLogger.forEnclosingClass();

    /** Static smeltability of one block type under one registry generation. */
    private record Smeltability(int generation, Set<String> convertibleItemIds, int maxConvertibleStacks) {
        boolean isSmeltable() {
            return !convertibleItemIds.isEmpty() && maxConvertibleStacks > 0;
        }
    }

    private final EnchantmentManager enchantmentManager;
    private final SmeltingRecipeRegistry smeltingRecipeRegistry;
    private final EnchantmentSmeltingDropConversionSystem dropConversionSystem;
    private final Map<String, Smeltability> smeltabilityByBlockId = new ConcurrentHashMap<>();

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

        // One metadata read for both enchantments.
        int[] levels = enchantmentManager.getEnchantmentLevels(tool, EnchantmentType.SMELTING,
                EnchantmentType.PICK_PERFECT);
        int smeltingLevel = levels[0];
        if (smeltingLevel <= 0 || levels[1] > 0) {
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

        Smeltability smeltability = resolveSmeltability(blockType, breaking);
        if (smeltability == null || !smeltability.isSmeltable()) {
            return;
        }

        PlayerRef playerRef = null;
        Ref<EntityStore> breakerRef = archetypeChunk.getReferenceTo(index);
        if (breakerRef != null && breakerRef.isValid()) {
            playerRef = store.getComponent(breakerRef, PlayerRef.getComponentType());
        }

        Vector3i targetBlock = event.getTargetBlock();
        dropConversionSystem.recordPending(store, targetBlock, playerRef, tool, smeltingLevel,
                smeltability.convertibleItemIds(), smeltability.maxConvertibleStacks());
    }

    /**
     * Resolves (and caches) which drops of this block can be smelted, using the
     * static drop list rather than a random roll. Returns null while the recipe
     * registry has nothing to offer yet (nothing is cached in that case).
     */
    private Smeltability resolveSmeltability(@Nonnull BlockType blockType, @Nonnull BlockBreakingDropType breaking) {
        if (!smeltingRecipeRegistry.ensureBuilt()) {
            return null;
        }
        int generation = smeltingRecipeRegistry.getGeneration();
        String blockId = blockType.getId();
        Smeltability cached = smeltabilityByBlockId.get(blockId);
        if (cached != null && cached.generation() == generation) {
            return cached;
        }

        Set<String> convertible = new HashSet<>();
        int convertibleEntries = 0;

        // Drop list: either a shared asset id or the generated id of an inline
        // "DropList" block (both live in the ItemDropList asset map).
        String dropListId = breaking.getDropListId();
        if (dropListId != null) {
            ItemDropList dropList = ItemDropList.getAssetMap().getAsset(dropListId);
            if (dropList != null && dropList.getContainer() != null) {
                List<ItemDrop> drops = dropList.getContainer().getAllDrops(new ArrayList<>());
                for (ItemDrop drop : drops) {
                    String itemId = drop != null ? drop.getItemId() : null;
                    if (itemId != null && isConvertible(itemId)) {
                        convertible.add(itemId);
                        convertibleEntries++;
                    }
                }
            }
        }
        // Vanilla rolls the list once per "Quantity" and adds one inline stack.
        int maxStacks = Math.max(1, breaking.getQuantity()) * convertibleEntries;

        String inlineItemId = breaking.getItemId();
        if (inlineItemId != null && isConvertible(inlineItemId)) {
            convertible.add(inlineItemId);
            maxStacks++;
        }

        if (dropListId == null && inlineItemId == null) {
            // No drop config at all: vanilla drops the block's own item once.
            Item blockItem = blockType.getItem();
            if (blockItem != null && isConvertible(blockItem.getId())) {
                convertible.add(blockItem.getId());
                maxStacks = 1;
            }
        }

        Smeltability result = new Smeltability(generation,
                convertible.isEmpty() ? Set.of() : Set.copyOf(convertible), maxStacks);
        smeltabilityByBlockId.put(blockId, result);
        return result;
    }

    private boolean isConvertible(@Nonnull String itemId) {
        SmeltingRecipeRegistry.SmeltingRecipe recipe = smeltingRecipeRegistry.getRecipe(new ItemStack(itemId, 1));
        return recipe != null && !itemId.equals(recipe.getOutputItemId());
    }
}
