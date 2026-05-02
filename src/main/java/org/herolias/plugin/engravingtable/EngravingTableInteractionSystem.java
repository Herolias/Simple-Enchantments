package org.herolias.plugin.engravingtable;

import com.hypixel.hytale.component.Archetype;
import com.hypixel.hytale.component.ArchetypeChunk;
import com.hypixel.hytale.component.CommandBuffer;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.component.query.Query;
import com.hypixel.hytale.component.system.EntityEventSystem;
import com.hypixel.hytale.server.core.asset.type.blocktype.config.BlockType;
import com.hypixel.hytale.server.core.entity.Entity;
import com.hypixel.hytale.server.core.entity.EntityUtils;
import com.hypixel.hytale.server.core.entity.entities.Player;
import com.hypixel.hytale.server.core.event.events.ecs.UseBlockEvent;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import org.herolias.plugin.SimpleEnchanting;
import org.herolias.plugin.enchantment.EnchantmentManager;

import javax.annotation.Nonnull;

public class EngravingTableInteractionSystem extends EntityEventSystem<EntityStore, UseBlockEvent.Pre> {
    public static final String BLOCK_ID = "Engraving_Table";

    private final SimpleEnchanting plugin;
    private final EnchantmentManager enchantmentManager;

    public EngravingTableInteractionSystem(@Nonnull SimpleEnchanting plugin,
            @Nonnull EnchantmentManager enchantmentManager) {
        super(UseBlockEvent.Pre.class);
        this.plugin = plugin;
        this.enchantmentManager = enchantmentManager;
    }

    @Override
    @Nonnull
    public Query<EntityStore> getQuery() {
        return Archetype.empty();
    }

    @Override
    public void handle(
            int index,
            @Nonnull ArchetypeChunk<EntityStore> archetypeChunk,
            @Nonnull Store<EntityStore> store,
            @Nonnull CommandBuffer<EntityStore> commandBuffer,
            @Nonnull UseBlockEvent.Pre event) {
        BlockType blockType = event.getBlockType();
        if (event.isCancelled() || blockType == null || !BLOCK_ID.equals(blockType.getId())) {
            return;
        }

        Entity entity = EntityUtils.getEntity(index, archetypeChunk);
        if (!(entity instanceof Player player)) {
            return;
        }

        Ref<EntityStore> ref = player.getReference();
        if (ref == null || !ref.isValid()) {
            return;
        }

        PlayerRef playerRef = store.getComponent(ref, PlayerRef.getComponentType());
        if (playerRef == null) {
            return;
        }

        event.setCancelled(true);
        EngravingTablePage page = new EngravingTablePage(playerRef, this.plugin, this.enchantmentManager);
        player.getPageManager().openCustomPage(ref, store, page);
    }
}
