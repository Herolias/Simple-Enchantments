package org.herolias.plugin;

import com.hypixel.hytale.logger.HytaleLogger;
import com.hypixel.hytale.server.core.modules.interaction.interaction.config.server.OpenCustomUIInteraction;
import com.hypixel.hytale.server.core.plugin.JavaPlugin;
import com.hypixel.hytale.server.core.plugin.JavaPluginInit;
import org.herolias.plugin.command.EnchantCommand;
import org.herolias.plugin.config.EnchantingConfig;
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
import org.herolias.plugin.enchantment.EnchantmentStateTransferSystem;
import org.herolias.plugin.enchantment.EnchantmentFeatherFallingSystem;
import org.herolias.plugin.enchantment.EnchantmentWaterbreathingSystem;
import org.herolias.plugin.enchantment.EnchantmentBurnSystem;
import org.herolias.plugin.enchantment.EnchantmentFreezeSystem;
import org.herolias.plugin.enchantment.EnchantmentBurnSmeltingSystem;
import org.herolias.plugin.enchantment.EnchantmentEternalShotSystem;
import org.herolias.plugin.enchantment.EnchantmentNightVisionSystem;
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
import org.herolias.plugin.enchantment.TooltipBridge;
import org.herolias.plugin.enchantment.ItemCategoryManager;
import org.herolias.plugin.crafting.WorkbenchRefreshSystem;
import org.herolias.plugin.enchantment.EnchantmentReflectionSystem;
import org.herolias.plugin.enchantment.EnchantmentAbsorptionSystem;
import org.herolias.plugin.enchantment.EnchantmentFastSwimSystem;

import com.al3x.HStats;

import org.herolias.plugin.ui.EnchantScrollPageSupplier;
import com.hypixel.hytale.server.core.event.events.entity.LivingEntityInventoryChangeEvent;
import com.hypixel.hytale.server.core.event.events.ecs.SwitchActiveSlotEvent;
import com.hypixel.hytale.server.core.entity.entities.Player;
import com.hypixel.hytale.server.core.event.events.player.PlayerReadyEvent;
import com.hypixel.hytale.server.core.event.events.player.PlayerDisconnectEvent;
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
    private EnchantmentEternalShotSystem eternalShotSystem;
    private boolean tooltipsEnabled;
    private org.herolias.plugin.config.ConfigManager configManager;
    private org.herolias.plugin.config.UserSettingsManager userSettingsManager;
    private org.herolias.plugin.lang.LanguageManager languageManager;

    public SimpleEnchanting(@Nonnull JavaPluginInit init) {
        super(init);
        instance = this;
        LOGGER.atInfo().log("SimpleEnchanting v" + this.getManifest().getVersion().toString() + " loading...");
    }

    @Override
    protected void setup() {
        LOGGER.atInfo().log("Setting up SimpleEnchanting...");
        super.setup();
        new HStats("b04768bd-4189-4ecc-b29c-0f644d7c95cc", this.getManifest().getVersion().toString());
        // Initialize Config
        this.configManager = new org.herolias.plugin.config.ConfigManager(new java.io.File("config"));
        
        // Check if this is a fresh install (no config file yet)
        boolean isFreshInstall = !this.configManager.getConfigFile().exists();
        
        this.configManager.loadConfig();

        // Initialize User Settings
        this.userSettingsManager = new org.herolias.plugin.config.UserSettingsManager(new java.io.File("config"), this.configManager);
        this.userSettingsManager.loadSettings();
        
        // Initialize Language Manager
        this.languageManager = new org.herolias.plugin.lang.LanguageManager();
        
        // If fresh install, skip the tooltip announcement (users installing now likely already expect tooltips)
        if (isFreshInstall) {
            EnchantingConfig config = this.configManager.getConfig();
            config.hasSkippedTooltipAnnouncement = true;
            this.configManager.saveConfig();
            LOGGER.atInfo().log("Fresh install detected: Tooltip announcement disabled.");
        }

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

        // Register custom Ammo Consumption interaction
        this.getCodecRegistry(com.hypixel.hytale.server.core.modules.interaction.interaction.config.Interaction.CODEC).register(
            "ConsumeAmmo",
            org.herolias.plugin.interaction.ConsumeAmmoInteraction.class,
            org.herolias.plugin.interaction.ConsumeAmmoInteraction.CODEC
        );
        LOGGER.atInfo().log("Registered ConsumeAmmo interaction");

        // Register Dynamic Projectile Launch interaction
        this.getCodecRegistry(com.hypixel.hytale.server.core.modules.interaction.interaction.config.Interaction.CODEC).register(
            "LaunchDynamicProjectile",
            org.herolias.plugin.interaction.LaunchDynamicProjectileInteraction.class,
            org.herolias.plugin.interaction.LaunchDynamicProjectileInteraction.CODEC
        );
        LOGGER.atInfo().log("Registered LaunchDynamicProjectile interaction");
        
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
            
            // Register Cleanup System for Eternal Shot projectiles
            this.getEntityStoreRegistry().registerSystem(new org.herolias.plugin.enchantment.EternalShotProjectileCleanupSystem(enchantmentManager));
            LOGGER.atInfo().log("Registered EternalShotProjectileCleanupSystem with ECS");

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
            this.getEntityStoreRegistry().registerSystem(new EnchantmentNightVisionSystem(enchantmentManager));
            LOGGER.atInfo().log("Registered EnchantmentNightVisionSystem with ECS");

            // Workbench Refresh System (Bug fix for ExtraResources not rescanning on upgrade)
            this.getEntityStoreRegistry().registerSystem(new WorkbenchRefreshSystem());
            LOGGER.atInfo().log("Registered WorkbenchRefreshSystem with ECS");

        } catch (Exception e) {
            LOGGER.atWarning().log("Could not register enchantment ECS systems: " + e.getMessage());
        }
        
        // Initialize and register the state transfer system (preserves enchantments on item state changes)
        EnchantmentStateTransferSystem stateTransferSystem = new EnchantmentStateTransferSystem(enchantmentManager);
        this.getEventRegistry().registerGlobal(LivingEntityInventoryChangeEvent.class, stateTransferSystem::onInventoryChange);
        LOGGER.atInfo().log("Registered EnchantmentStateTransferSystem listener");

        // Initialize and register the durability system (Event Listener)
        EnchantmentDurabilitySystem durabilitySystem = new EnchantmentDurabilitySystem(enchantmentManager);
        this.getEventRegistry().registerGlobal(LivingEntityInventoryChangeEvent.class, durabilitySystem::onInventoryChange);
        LOGGER.atInfo().log("Registered EnchantmentDurabilitySystem listener");
        
        // Initialize and register the Eternal Shot system (Event Listener)
        // This intercepts arrow consumption and restores arrows when weapon has Eternal Shot enchantment
        eternalShotSystem = new EnchantmentEternalShotSystem(enchantmentManager);
        this.getEventRegistry().registerGlobal(LivingEntityInventoryChangeEvent.class, eternalShotSystem::onInventoryChange);
        
        // Register SwitchActiveSlot handler to clear stale records when switching from unloaded crossbows
        // We use a dedicated System to ensure we get the EntityStore context
        this.getEntityStoreRegistry().registerSystem(new org.herolias.plugin.enchantment.SwitchActiveSlotSystem(eternalShotSystem));
        LOGGER.atInfo().log("Registered SwitchActiveSlotSystem");
        
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
        EnchantmentSalvageSystem salvageSystem = new EnchantmentSalvageSystem(enchantmentManager);
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

        // Register ScrollDescriptionManager to send global translation updates on join
        // Using PlayerReadyEvent as it ensures the player is fully connected (similar to WelcomeListener)
        this.getEventRegistry().registerGlobal(PlayerReadyEvent.class, event -> {
            Player player = event.getPlayer();
            if (player.getWorld() == null || player.getReference() == null) return;
            com.hypixel.hytale.server.core.universe.PlayerRef playerRef = player.getWorld().getEntityStore().getStore().getComponent(player.getReference(), com.hypixel.hytale.server.core.universe.PlayerRef.getComponentType());
            if (playerRef != null) {
                String langCode = userSettingsManager.getLanguage(playerRef.getUuid());
                languageManager.sendUpdatePacket(playerRef, langCode);
                org.herolias.plugin.enchantment.ScrollDescriptionManager.sendUpdatePacket(playerRef);
            }
        });
        LOGGER.atInfo().log("Registered ScrollDescriptionManager listener");


        // ── Tooltip System (via DynamicTooltipsLib, optional) ──
        // All lib references are isolated in TooltipBridge so that
        // Simple-Enchantments loads and runs normally without the lib.
        try {
            Class.forName("org.herolias.tooltips.api.DynamicTooltipsApiProvider");
            // TooltipBridge is only loaded here — after we've confirmed the lib exists.
            // It contains all compile-time references to DynamicTooltipsLib classes.
            this.tooltipsEnabled = TooltipBridge.register(enchantmentManager);
        } catch (ClassNotFoundException | NoClassDefFoundError e) {
            LOGGER.atWarning().log("DynamicTooltipsLib not found — enchantment tooltips will not display. "
                    + "Install DynamicTooltipsLib for rich enchantment tooltips.");
        }


        // Auto-disable enchantment banner if tooltips are present and we haven't done it yet
        if (tooltipsEnabled) {
            EnchantingConfig config = configManager.getConfig();
            if (!config.hasAutoDisabledBanner) {
                config.showEnchantmentBanner = false;
                config.hasAutoDisabledBanner = true;
                configManager.saveConfig();
                LOGGER.atInfo().log("Automatically disabled Enchantment Banner because DynamicTooltipsLib is installed.");
            }
        }
        
        // Register Event Logger Listener (Debug)
        org.herolias.plugin.listener.EventLoggerListener debugListener = new org.herolias.plugin.listener.EventLoggerListener();
        this.getEventRegistry().registerGlobal(org.herolias.plugin.api.event.EnchantmentActivatedEvent.class, debugListener::onEnchantmentActivated);
        this.getEventRegistry().registerGlobal(org.herolias.plugin.api.event.ItemEnchantedEvent.class, debugListener::onItemEnchanted);
        LOGGER.atInfo().log("Registered debug event logger");
        
        // Register Welcome Listener (One-time notification for tooltips)
        this.getEventRegistry().registerGlobal(com.hypixel.hytale.server.core.event.events.player.PlayerReadyEvent.class, new org.herolias.plugin.listener.WelcomeListener(this)::onPlayerReady);
        
        // Register commands
        this.getCommandRegistry().registerCommand(new EnchantCommand(this));
        LOGGER.atInfo().log("Registered /enchant command");
        
        this.getCommandRegistry().registerCommand(new org.herolias.plugin.command.EnchantingCommand(this));
        LOGGER.atInfo().log("Registered /enchanting command");
        
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
        // Register the slot tracker (handles glow updates + enchantment banner on slot change)
        try {
            EnchantmentSlotTracker slotTracker = new EnchantmentSlotTracker(enchantmentManager, eternalShotSystem);
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

        if (tooltipsEnabled) {
            LOGGER.atInfo().log("Enchantment tooltips active via DynamicTooltipsLib");
        }
    }
    @Override
    protected void shutdown() {
        super.shutdown();
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
     * Returns whether enchantment tooltips are active (DynamicTooltipsLib present).
     */
    public boolean isTooltipsEnabled() {
        return tooltipsEnabled;
    }

    /**
     * Checks if the Perfect Parries mod is present.
     */
    public boolean isPerfectParriesModPresent() {
        try {
            Class.forName("org.narwhals.plugin.PerfectParries");
            return true;
        } catch (ClassNotFoundException e) {
            return false;
        }
    }

    public org.herolias.plugin.config.ConfigManager getConfigManager() {
        return configManager;
    }

    public org.herolias.plugin.config.UserSettingsManager getUserSettingsManager() {
        return userSettingsManager;
    }

    public org.herolias.plugin.lang.LanguageManager getLanguageManager() {
        return languageManager;
    }
}
