package org.herolias.plugin;

import com.hypixel.hytale.logger.HytaleLogger;
import com.hypixel.hytale.server.core.modules.interaction.interaction.config.server.OpenCustomUIInteraction;
import com.hypixel.hytale.server.core.plugin.JavaPlugin;
import com.hypixel.hytale.server.core.plugin.JavaPluginInit;
import org.herolias.plugin.command.EnchantCommand;
import org.herolias.plugin.enchantment.EnchantmentDamageSystem;
import org.herolias.plugin.enchantment.EnchantmentAbilityStaminaSystem;
import org.herolias.plugin.enchantment.EnchantmentBlockDamageSystem;
import org.herolias.plugin.enchantment.EnchantmentDurabilitySystem;
import org.herolias.plugin.enchantment.EnchantmentFortuneSystem;
import org.herolias.plugin.enchantment.EnchantmentLootingSystem;
import org.herolias.plugin.enchantment.EnchantmentManager;
import org.herolias.plugin.enchantment.EnchantmentProjectileSpeedSystem;
import org.herolias.plugin.enchantment.EnchantmentSmeltingSystem;
import org.herolias.plugin.enchantment.EnchantmentStaminaSystem;
import org.herolias.plugin.enchantment.EnchantmentFeatherFallingSystem;
import org.herolias.plugin.enchantment.EnchantmentWaterbreathingSystem;
import org.herolias.plugin.enchantment.EnchantmentBurnSystem;
import org.herolias.plugin.enchantment.EnchantmentFreezeSystem;
import org.herolias.plugin.enchantment.EnchantmentBurnSmeltingSystem;
import org.herolias.plugin.enchantment.EnchantmentEternalShotSystem;
import org.herolias.plugin.enchantment.DropItemEventSystem;
import org.herolias.plugin.enchantment.EnchantmentRecipeManager;
import org.herolias.plugin.enchantment.EnchantmentSalvageSystem;
import org.herolias.plugin.enchantment.SalvagerInteractionSystem;
import org.herolias.plugin.enchantment.EnchantmentGlowInjector;
import org.herolias.plugin.enchantment.EnchantmentElementalHeartSystem;
import org.herolias.plugin.enchantment.EnchantmentSilktouchSystem;
import org.herolias.plugin.enchantment.EnchantmentVisualsListener;
import org.herolias.plugin.enchantment.EnchantmentKnockbackSystem;
import org.herolias.plugin.enchantment.EnchantmentSlotTracker;
import org.herolias.plugin.enchantment.EnchantmentThriftSystem;
import org.herolias.plugin.enchantment.EnchantmentTooltipManager;
import org.herolias.plugin.enchantment.ItemCategoryManager;
import org.herolias.plugin.enchantment.EnchantmentReflectionSystem;
import org.herolias.plugin.enchantment.EnchantmentAbsorptionSystem;
import org.herolias.plugin.enchantment.EnchantmentFastSwimSystem;

import org.herolias.plugin.listener.EnchantingTableListener;
import org.herolias.plugin.ui.EnchantScrollPageSupplier;
import com.hypixel.hytale.server.core.event.events.entity.LivingEntityInventoryChangeEvent;

import javax.annotation.Nonnull;

/**
 * SimpleEnchanting Plugin - Adds an enchanting system to Hytale
 * 
 * Features:
 * - Metadata-based enchantment storage (no separate item files needed!)
 * - Sharpness enchantment (+10% melee damage per level)
 * - Durability enchantment (reduces durability loss)
 * - Protection enchantment (reduces physical damage)
 * - Efficiency enchantment (increases mining speed)
 * - Fortune enchantment (extra ore/crystal drops)
 * - Smelting enchantment (auto-smelts mined drops)
 * - Strength enchantment (projectile damage + range)
 * - Eagle's Eye enchantment (distance-based projectile damage)
 * - Looting enchantment (bonus enemy drops)
 * - Sturdy enchantment (prevents repair durability penalty)
 * - /enchant command to apply enchantments
 * - Extensible for future enchantments
 * 
 * The enchantment system uses ItemStack metadata (BSON) to store enchantment
 * data directly on items, allowing unlimited enchantment combinations without
 * creating separate item JSON files.
 * 
 * Note: Enchantment tooltips are not displayed in item hover (Hytale limitation).
 * The /enchant command shows enchantments in chat when applied.
 */
public class SimpleEnchanting extends JavaPlugin {

    private static final HytaleLogger LOGGER = HytaleLogger.forEnclosingClass();
    
    private static SimpleEnchanting instance;
    private EnchantmentManager enchantmentManager;
    private EnchantmentDamageSystem enchantmentDamageSystem;
    private EnchantmentBlockDamageSystem enchantmentBlockDamageSystem;
    private EnchantmentFortuneSystem enchantmentFortuneSystem;
    private EnchantmentSmeltingSystem enchantmentSmeltingSystem;
    private EnchantmentLootingSystem enchantmentLootingSystem;
    private EnchantmentStaminaSystem enchantmentStaminaSystem;
    private EnchantmentAbilityStaminaSystem enchantmentAbilityStaminaSystem;
    private EnchantmentProjectileSpeedSystem enchantmentProjectileSpeedSystem;
    private EnchantingTableListener enchantingTableListener;
    private EnchantmentTooltipManager tooltipManager;
    private org.herolias.plugin.config.ConfigManager configManager;

    public SimpleEnchanting(@Nonnull JavaPluginInit init) {
        super(init);
        instance = this;
        LOGGER.atInfo().log("SimpleEnchanting v" + this.getManifest().getVersion().toString() + " loading...");
    }

    @Override
    protected void setup() {
        LOGGER.atInfo().log("Setting up SimpleEnchanting...");
        
        // Initialize Config
        this.configManager = new org.herolias.plugin.config.ConfigManager(new java.io.File("config"));
        this.configManager.loadConfig();

        // Register event listener for recipe filtering based on config
        // This intercepts recipes as they're loaded and removes disabled enchantment scrolls
        EnchantmentRecipeManager.registerEventListener(this);
        
        // Register runtime injection of ItemAppearanceConditions for enchantment glow
        // This replaces file-based overrides to ensure mod compatibility
        EnchantmentGlowInjector.registerEventListener(this);
        
        // Register ItemCategoryManager to listen for asset loading (cache population)
        this.getEventRegistry().register(
            com.hypixel.hytale.assetstore.event.LoadedAssetsEvent.class, 
            com.hypixel.hytale.server.core.asset.type.item.config.Item.class, 
            ItemCategoryManager.getInstance()::onItemsLoaded
        );

        // Initialize the enchantment manager (handles metadata storage)
        this.enchantmentManager = new EnchantmentManager(this);
        
        // Register Public API
        org.herolias.plugin.api.EnchantmentApi api = new org.herolias.plugin.api.EnchantmentApiImpl(enchantmentManager);
        org.herolias.plugin.api.EnchantmentApiProvider.register(api);

        // Register custom UI page for enchantment scrolls
        this.getCodecRegistry(OpenCustomUIInteraction.PAGE_CODEC).register(
            "EnchantScroll",
            EnchantScrollPageSupplier.class,
            EnchantScrollPageSupplier.CODEC
        );
        LOGGER.atInfo().log("Registered EnchantScroll UI page supplier");
        
        // Register custom UI page for cleansing scroll
        this.getCodecRegistry(OpenCustomUIInteraction.PAGE_CODEC).register(
            "CleansingScroll",
            org.herolias.plugin.ui.CleansingScrollPageSupplier.class,
            org.herolias.plugin.ui.CleansingScrollPageSupplier.CODEC
        );
        LOGGER.atInfo().log("Registered CleansingScroll UI page supplier");
        
        // Initialize the ECS damage system for applying enchantment effects
        this.enchantmentDamageSystem = new EnchantmentDamageSystem(enchantmentManager);
        this.enchantmentBlockDamageSystem = new EnchantmentBlockDamageSystem(enchantmentManager);
        this.enchantmentSmeltingSystem = new EnchantmentSmeltingSystem(enchantmentManager);
        this.enchantmentFortuneSystem = new EnchantmentFortuneSystem(enchantmentManager);
        this.enchantmentLootingSystem = new EnchantmentLootingSystem(enchantmentManager);
        this.enchantmentStaminaSystem = new EnchantmentStaminaSystem(enchantmentManager);
        this.enchantmentAbilityStaminaSystem = new EnchantmentAbilityStaminaSystem(enchantmentManager);
        this.enchantmentProjectileSpeedSystem = new EnchantmentProjectileSpeedSystem(enchantmentManager);
        
        // Register 'eternal_shot_active' stat for JSON conditions
        // This allows us to conditionalize interactions based on whether the player has Eternal Shot active
        try {
            com.hypixel.hytale.server.core.modules.entitystats.asset.EntityStatType eternalShotStat = new com.hypixel.hytale.server.core.modules.entitystats.asset.EntityStatType(
                "eternal_shot_active", 
                0, // Initial
                0, // Min
                1, // Max
                false, // Shared
                null, // Regenerating
                null, // MinEffects
                null, // MaxEffects
                com.hypixel.hytale.protocol.EntityStatResetBehavior.InitialValue
            );
            com.hypixel.hytale.server.core.modules.entitystats.asset.EntityStatType.getAssetStore().loadAssets("SimpleEnchanting:Runtime", java.util.List.of(eternalShotStat));
            LOGGER.atInfo().log("Registered 'eternal_shot_active' stat");
        } catch (Exception e) {
            LOGGER.atSevere().log("Failed to register eternal_shot_active stat: " + e.getMessage());
        }
        
        // Register the damage system with Hytale's ECS via EntityStoreRegistry
        try {
            this.getEntityStoreRegistry().registerSystem(enchantmentDamageSystem);
            LOGGER.atInfo().log("Registered EnchantmentDamageSystem with ECS");
            this.getEntityStoreRegistry().registerSystem(enchantmentBlockDamageSystem);
            LOGGER.atInfo().log("Registered EnchantmentBlockDamageSystem with ECS");
            this.getEntityStoreRegistry().registerSystem(enchantmentSmeltingSystem);
            LOGGER.atInfo().log("Registered EnchantmentSmeltingSystem with ECS");
            this.getEntityStoreRegistry().registerSystem(enchantmentFortuneSystem);
            LOGGER.atInfo().log("Registered EnchantmentFortuneSystem with ECS");
            this.getEntityStoreRegistry().registerSystem(enchantmentLootingSystem);
            LOGGER.atInfo().log("Registered EnchantmentLootingSystem with ECS");
            this.getEntityStoreRegistry().registerSystem(enchantmentStaminaSystem);
            LOGGER.atInfo().log("Registered EnchantmentStaminaSystem with ECS");
            this.getEntityStoreRegistry().registerSystem(enchantmentAbilityStaminaSystem);
            LOGGER.atInfo().log("Registered EnchantmentAbilityStaminaSystem with ECS");
            this.getEntityStoreRegistry().registerSystem(enchantmentProjectileSpeedSystem);
            LOGGER.atInfo().log("Registered EnchantmentProjectileSpeedSystem with ECS");

            this.getEntityStoreRegistry().registerSystem(new EnchantmentThriftSystem(enchantmentManager));
            LOGGER.atInfo().log("Registered EnchantmentThriftSystem with ECS");
            
            // Register new enchantment systems (Feather Falling, Waterbreathing, Burn)
            this.getEntityStoreRegistry().registerSystem(new EnchantmentFeatherFallingSystem(enchantmentManager));
            LOGGER.atInfo().log("Registered EnchantmentFeatherFallingSystem with ECS");
            this.getEntityStoreRegistry().registerSystem(new EnchantmentWaterbreathingSystem(enchantmentManager));
            LOGGER.atInfo().log("Registered EnchantmentWaterbreathingSystem with ECS");
            this.getEntityStoreRegistry().registerSystem(new EnchantmentBurnSystem(enchantmentManager));
            LOGGER.atInfo().log("Registered EnchantmentBurnSystem with ECS");
            this.getEntityStoreRegistry().registerSystem(new EnchantmentFreezeSystem(enchantmentManager));
            LOGGER.atInfo().log("Registered EnchantmentFreezeSystem with ECS");
            this.getEntityStoreRegistry().registerSystem(new EnchantmentBurnSmeltingSystem(enchantmentManager));
            LOGGER.atInfo().log("Registered EnchantmentBurnSmeltingSystem with ECS");
            this.getEntityStoreRegistry().registerSystem(new EnchantmentSilktouchSystem(enchantmentManager));
            LOGGER.atInfo().log("Registered EnchantmentSilktouchSystem with ECS");
            this.getEntityStoreRegistry().registerSystem(new EnchantmentKnockbackSystem(enchantmentManager));
            LOGGER.atInfo().log("Registered EnchantmentKnockbackSystem with ECS");
            this.getEntityStoreRegistry().registerSystem(new EnchantmentReflectionSystem(enchantmentManager));
            LOGGER.atInfo().log("Registered EnchantmentReflectionSystem with ECS");
            this.getEntityStoreRegistry().registerSystem(new EnchantmentAbsorptionSystem(enchantmentManager));
            LOGGER.atInfo().log("Registered EnchantmentAbsorptionSystem with ECS");
            this.getEntityStoreRegistry().registerSystem(new EnchantmentFastSwimSystem(enchantmentManager));
            LOGGER.atInfo().log("Registered EnchantmentFastSwimSystem with ECS");
        } catch (Exception e) {
            LOGGER.atWarning().log("Could not register enchantment ECS systems: " + e.getMessage());
        }
        
        // Initialize and register the durability system (Event Listener)
        EnchantmentDurabilitySystem durabilitySystem = new EnchantmentDurabilitySystem(enchantmentManager);
        this.getEventRegistry().registerGlobal(LivingEntityInventoryChangeEvent.class, durabilitySystem::onInventoryChange);
        LOGGER.atInfo().log("Registered EnchantmentDurabilitySystem listener");
        
        // Initialize and register the Eternal Shot system (Event Listener)
        // This intercepts arrow consumption and restores arrows when weapon has Eternal Shot enchantment
        EnchantmentEternalShotSystem eternalShotSystem = new EnchantmentEternalShotSystem(enchantmentManager);
        this.getEventRegistry().registerGlobal(LivingEntityInventoryChangeEvent.class, eternalShotSystem::onInventoryChange);
        LOGGER.atInfo().log("Registered EnchantmentEternalShotSystem listener");
        
        // Initialize and register Elemental Heart System (Essence Saver)
        EnchantmentElementalHeartSystem elementalHeartSystem = new EnchantmentElementalHeartSystem(enchantmentManager);
        this.getEventRegistry().registerGlobal(LivingEntityInventoryChangeEvent.class, elementalHeartSystem::onInventoryChange);
        LOGGER.atInfo().log("Registered EnchantmentElementalHeartSystem listener");
        
        // Register DropItemEventSystem to track manual drops for Eternal Shot fix
        // This prevents arrows from being duplicated when players manually drop them
        try {
            this.getEntityStoreRegistry().registerSystem(new DropItemEventSystem(eternalShotSystem, elementalHeartSystem));
            LOGGER.atInfo().log("Registered DropItemEventSystem with ECS");
        } catch (Exception e) {
            LOGGER.atWarning().log("Could not register DropItemEventSystem: " + e.getMessage());
        }

        // Register EnchantmentSalvageSystem (Event Listener for Salvager Bench)
        // This handles stripping metadata from enchanted items so they can be salvaged
        EnchantmentSalvageSystem salvageSystem = new EnchantmentSalvageSystem();
        // Register interaction system with ECS
        this.getEntityStoreRegistry().registerSystem(new SalvagerInteractionSystem(salvageSystem));
        // Register inventory change listener globally
        this.getEventRegistry().registerGlobal(LivingEntityInventoryChangeEvent.class, salvageSystem::onEntityInventoryChange);
        LOGGER.atInfo().log("Registered EnchantmentSalvageSystem listener");

        // Register EnchantmentVisualsListener (Event driven visual updates)
        // Optimized to replace heavy per-tick polling
        EnchantmentVisualsListener visualsListener = new EnchantmentVisualsListener(enchantmentManager);
        this.getEventRegistry().registerGlobal(LivingEntityInventoryChangeEvent.class, visualsListener::onInventoryChange);
        LOGGER.atInfo().log("Registered EnchantmentVisualsListener");

        // Register EnchantmentTooltipManager with Virtual Item ID system
        // This intercepts outbound UpdatePlayerInventory packets and replaces enchanted
        // item IDs with virtual IDs that have unique description translation keys.
        // Each enchanted item instance gets its own tooltip — no more shared descriptions!
        this.tooltipManager = new EnchantmentTooltipManager(enchantmentManager);
        this.tooltipManager.registerPacketAdapter();
        LOGGER.atInfo().log("Registered EnchantmentTooltipManager with Virtual Item ID packet adapter");

        // Initialize enchanting table listener
        this.enchantingTableListener = new EnchantingTableListener(this);
        
        // Register commands
        this.getCommandRegistry().registerCommand(new EnchantCommand(this));
        LOGGER.atInfo().log("Registered /enchant command");
        
        // Register custom /give command (overrides vanilla)
        this.getCommandRegistry().registerCommand(new org.herolias.plugin.command.GiveEnchantedCommand());
        LOGGER.atInfo().log("Registered enhanced /give command");
        
        // Register config editor command
        this.getCommandRegistry().registerCommand(new org.herolias.plugin.command.EnchantConfigCommand(this));
        LOGGER.atInfo().log("Registered /enchantconfig command");
        
        LOGGER.atInfo().log("SimpleEnchanting setup complete!");
        LOGGER.atInfo().log("Using metadata-based enchantment storage");
    }

    @Override
    protected void start() {
        // Initialize and register the slot tracker (Ticker)
        // This is a lightweight tracker that only updates when necessary (on slot change)
        try {
            EnchantmentSlotTracker slotTracker = new EnchantmentSlotTracker(enchantmentManager);
            com.hypixel.hytale.server.core.HytaleServer.SCHEDULED_EXECUTOR.scheduleAtFixedRate(
                slotTracker, 
                0, 
                50, // 50ms = 1 tick
                java.util.concurrent.TimeUnit.MILLISECONDS
            );
            LOGGER.atInfo().log("Registered EnchantmentSlotTracker ticker in start()");
        } catch (Exception e) {
             LOGGER.atSevere().log("Failed to register Slot Tracker: " + e.getMessage());
             e.printStackTrace();
        }
    }

    /**
     * Gets the singleton instance of the plugin.
     */
    public static SimpleEnchanting getInstance() {
        return instance;
    }

    /**
     * Gets the enchantment manager.
     */
    public EnchantmentManager getEnchantmentManager() {
        return enchantmentManager;
    }
    
    /**
     * Gets the enchantment damage system.
     */
    public EnchantmentDamageSystem getEnchantmentDamageSystem() {
        return enchantmentDamageSystem;
    }
    
    /**
     * Gets the enchanting table listener.
     */
    public EnchantingTableListener getEnchantingTableListener() {
        return enchantingTableListener;
    }

    /**
     * Gets the tooltip manager for per-player enchantment tooltip updates.
     */
    public EnchantmentTooltipManager getTooltipManager() {
        return tooltipManager;
    }

    public org.herolias.plugin.config.ConfigManager getConfigManager() {
        return configManager;
    }
}
