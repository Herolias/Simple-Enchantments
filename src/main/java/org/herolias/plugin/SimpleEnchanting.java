package org.herolias.plugin;

import com.hypixel.hytale.logger.HytaleLogger;
import com.hypixel.hytale.server.core.modules.interaction.interaction.config.server.OpenCustomUIInteraction;
import com.hypixel.hytale.server.core.plugin.JavaPlugin;
import com.hypixel.hytale.server.core.plugin.JavaPluginInit;
import org.herolias.plugin.engravingtable.EngravingTableInteractionSystem;
import org.herolias.plugin.command.EnchantCommand;
import org.herolias.plugin.config.EnchantingConfig;
import org.herolias.plugin.enchantment.CreativeCategoryInjector;
import org.herolias.plugin.enchantment.EnchantmentDamageSystem;
import org.herolias.plugin.enchantment.EnchantmentAbilityStaminaSystem;
import org.herolias.plugin.enchantment.EnchantmentBlockDamageSystem;
import org.herolias.plugin.enchantment.EnchantmentDurabilitySystem;
import org.herolias.plugin.enchantment.EnchantmentFortuneSystem;
import org.herolias.plugin.enchantment.EnchantmentLootingSystem;
import org.herolias.plugin.enchantment.EnchantmentManager;
import org.herolias.plugin.enchantment.EnchantmentProjectileSpeedSystem;
import org.herolias.plugin.enchantment.EnchantmentSmeltingDropConversionSystem;
import org.herolias.plugin.enchantment.EnchantmentSmeltingSystem;
import org.herolias.plugin.enchantment.EnchantmentStaminaSystem;
import org.herolias.plugin.enchantment.EnchantmentStateTransferSystem;
import org.herolias.plugin.enchantment.EnchantmentFeatherFallingSystem;
import org.herolias.plugin.enchantment.EnchantmentWaterbreathingSystem;
import org.herolias.plugin.enchantment.EnchantmentBurnSystem;
import org.herolias.plugin.enchantment.EnchantmentPoisonSystem;
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
import org.herolias.plugin.enchantment.EnchantmentThriftSystem;
import org.herolias.plugin.enchantment.ItemCategoryManager;
import org.herolias.plugin.enchantment.EnchantmentReflectionSystem;
import org.herolias.plugin.enchantment.EnchantmentAbsorptionSystem;
import org.herolias.plugin.enchantment.EnchantmentFastSwimSystem;
import org.herolias.plugin.enchantment.EnchantmentRegenerationSystem;
import org.herolias.plugin.enchantment.EnchantmentSecondStomachSystem;
import org.herolias.plugin.enchantment.ScrollItemGenerator;

import com.al3x.HStats;

import org.herolias.plugin.ui.EnchantScrollPageSupplier;

import com.hypixel.hytale.server.core.entity.entities.Player;
import com.hypixel.hytale.server.core.event.events.player.PlayerReadyEvent;
import com.hypixel.hytale.server.core.event.events.player.PlayerDisconnectEvent;
import javax.annotation.Nonnull;

/**
 * SimpleEnchanting Plugin - Adds an enchanting system to Hytale
 * 
 * Features:
 * - 31 enchantments
 * - /enchant command to apply enchantments
 * - Extensible for future enchantments
 * 
 * The enchantment system uses ItemStack metadata (BSON) to store enchantment
 * data directly on items, allowing unlimited enchantment combinations without
 * creating separate item JSON files.
 * 
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
    private EnchantmentSmeltingDropConversionSystem enchantmentSmeltingDropConversionSystem;
    private EnchantmentLootingSystem enchantmentLootingSystem;
    private EnchantmentStaminaSystem enchantmentStaminaSystem;
    private EnchantmentAbilityStaminaSystem enchantmentAbilityStaminaSystem;
    private EnchantmentProjectileSpeedSystem enchantmentProjectileSpeedSystem;
    private EnchantmentEternalShotSystem eternalShotSystem;
    private EnchantmentFastSwimSystem enchantmentFastSwimSystem;
    private EnchantmentNightVisionSystem enchantmentNightVisionSystem;
    private EnchantmentWaterbreathingSystem enchantmentWaterbreathingSystem;
    private org.herolias.plugin.enchantment.EnchantmentPlayerLifecycleSystem playerLifecycleSystem;

    private org.herolias.plugin.config.ConfigManager configManager;
    private org.herolias.plugin.config.UserSettingsManager userSettingsManager;
    private org.herolias.plugin.lang.LanguageManager languageManager;
    private HStats hStats;

    public SimpleEnchanting(@Nonnull JavaPluginInit init) {
        super(init);
        instance = this;
        LOGGER.atInfo().log("SimpleEnchanting v" + this.getManifest().getVersion().toString() + " loading...");
    }

    @Override
    protected void setup() {
        LOGGER.atInfo().log("Setting up SimpleEnchanting...");
        super.setup();
        // HStats (anonymous metrics) is constructed in start(); see there.

        // --- CONFIG MIGRATION ---
        // Config lives in mods/Simple_Enchantments_Config (the server warns about any
        // non-pack folder under mods/, including its own plugin data folders, so
        // moving it would not silence that). Files from the pre-1.0 config/ folder
        // are moved in on first run.
        java.io.File newConfigDir = org.herolias.plugin.config.ConfigManager.DEFAULT_CONFIG_DIRECTORY;
        org.herolias.plugin.config.ConfigManager.migrateLegacyConfigDirectories(newConfigDir);
        // ------------------------

        // Initialize Config
        this.configManager = new org.herolias.plugin.config.ConfigManager(newConfigDir);

        // Check if this is a fresh install (no config file yet)
        boolean isFreshInstall = !this.configManager.getConfigFile().exists();

        this.configManager.loadConfig();

        // Initialize User Settings
        this.userSettingsManager = new org.herolias.plugin.config.UserSettingsManager(newConfigDir, this.configManager);
        this.userSettingsManager.loadSettings();

        // Initialize Language Manager
        this.languageManager = new org.herolias.plugin.lang.LanguageManager();

        // If fresh install, skip the tooltip announcement (users installing now likely
        // already expect tooltips)
        if (isFreshInstall) {
            EnchantingConfig config = this.configManager.getConfig();
            config.hasSkippedTooltipAnnouncement = true;
            this.configManager.saveConfig();
            LOGGER.atInfo().log("Fresh install detected: Tooltip announcement disabled.");
        }

        // Register scroll item generator (deferred to LoadedAssetsEvent<Item>)
        // Replaces ~70 static JSON files with runtime-generated items
        ScrollItemGenerator.registerEventListener(this);

        // Register event listener for recipe filtering based on config
        // This intercepts recipes as they're loaded and removes disabled enchantment
        // scrolls
        EnchantmentRecipeManager.registerEventListener(this);

        // Register runtime injection of ItemAppearanceConditions for enchantment glow
        // This replaces file-based overrides to ensure mod compatibility
        EnchantmentGlowInjector.registerEventListener(this);

        // Add the scroll sub-tab to the vanilla "Items" creative-library tab
        CreativeCategoryInjector.registerEventListener(this);

        // Register ItemCategoryManager to listen for asset loading (cache population)
        this.getEventRegistry().register(
                com.hypixel.hytale.assetstore.event.LoadedAssetsEvent.class,
                com.hypixel.hytale.server.core.asset.type.item.config.Item.class,
                ItemCategoryManager.getInstance()::onItemsLoaded);

        // Initialize the enchantment manager (handles metadata storage)
        this.enchantmentManager = new EnchantmentManager(this);

        // Register Public API
        org.herolias.plugin.api.EnchantmentApi api = new org.herolias.plugin.api.EnchantmentApiImpl(enchantmentManager);
        org.herolias.plugin.api.EnchantmentApiProvider.register(api);

        // Register Event listener for dynamic adjustments of Burn and Freeze
        // EntityEffects
        org.herolias.plugin.enchantment.EnchantmentDynamicEffects.registerEventListener(this);

        // Register custom UI page for enchantment scrolls
        this.getCodecRegistry(OpenCustomUIInteraction.PAGE_CODEC).register(
                "EnchantScroll",
                EnchantScrollPageSupplier.class,
                EnchantScrollPageSupplier.CODEC);
        LOGGER.atInfo().log("Registered EnchantScroll UI page supplier");

        // Register custom UI page for cleansing scroll
        this.getCodecRegistry(OpenCustomUIInteraction.PAGE_CODEC).register(
                "CleansingScroll",
                org.herolias.plugin.ui.CleansingScrollPageSupplier.class,
                org.herolias.plugin.ui.CleansingScrollPageSupplier.CODEC);
        LOGGER.atInfo().log("Registered CleansingScroll UI page supplier");

        // Register custom UI page for custom scroll (multi-enchantment transfer scroll)
        this.getCodecRegistry(OpenCustomUIInteraction.PAGE_CODEC).register(
                "CustomScroll",
                org.herolias.plugin.ui.CustomScrollPageSupplier.class,
                org.herolias.plugin.ui.CustomScrollPageSupplier.CODEC);
        LOGGER.atInfo().log("Registered CustomScroll UI page supplier");

        // Initialize the ECS damage system for applying enchantment effects
        this.enchantmentDamageSystem = new EnchantmentDamageSystem(enchantmentManager);
        this.enchantmentBlockDamageSystem = new EnchantmentBlockDamageSystem(enchantmentManager);
        this.enchantmentSmeltingDropConversionSystem = new EnchantmentSmeltingDropConversionSystem(enchantmentManager);
        this.enchantmentSmeltingSystem = new EnchantmentSmeltingSystem(enchantmentManager,
                enchantmentSmeltingDropConversionSystem);
        this.enchantmentFortuneSystem = new EnchantmentFortuneSystem(enchantmentManager);
        this.enchantmentLootingSystem = new EnchantmentLootingSystem(enchantmentManager);
        this.enchantmentStaminaSystem = new EnchantmentStaminaSystem(enchantmentManager);
        this.enchantmentAbilityStaminaSystem = new EnchantmentAbilityStaminaSystem(enchantmentManager);
        this.enchantmentProjectileSpeedSystem = new EnchantmentProjectileSpeedSystem(enchantmentManager);
        this.enchantmentFastSwimSystem = new EnchantmentFastSwimSystem(enchantmentManager);

        // Initialize the Eternal Shot system early so it can be wired into the
        // projectile speed system (the two collaborate: tracking + refund on spawn)
        eternalShotSystem = new EnchantmentEternalShotSystem(enchantmentManager);
        enchantmentProjectileSpeedSystem.setEternalShotSystem(eternalShotSystem);

        // Register the damage system with Hytale's ECS via EntityStoreRegistry
        try {
            this.getEntityStoreRegistry().registerSystem(enchantmentDamageSystem);
            LOGGER.atInfo().log("Registered EnchantmentDamageSystem with ECS");
            this.getEntityStoreRegistry().registerSystem(enchantmentBlockDamageSystem);
            LOGGER.atInfo().log("Registered EnchantmentBlockDamageSystem with ECS");
            this.getEntityStoreRegistry().registerSystem(enchantmentSmeltingSystem);
            LOGGER.atInfo().log("Registered EnchantmentSmeltingSystem with ECS");
            this.getEntityStoreRegistry().registerSystem(enchantmentSmeltingDropConversionSystem);
            LOGGER.atInfo().log("Registered EnchantmentSmeltingDropConversionSystem with ECS");
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
            this.getEntityStoreRegistry().registerSystem(
                    new org.herolias.plugin.enchantment.EternalShotProjectileCleanupSystem(enchantmentManager));
            LOGGER.atInfo().log("Registered EternalShotProjectileCleanupSystem with ECS");

            this.getEntityStoreRegistry().registerSystem(new EnchantmentThriftSystem(enchantmentManager));
            LOGGER.atInfo().log("Registered EnchantmentThriftSystem with ECS");

            // Register new enchantment systems (Feather Falling, Waterbreathing, Burn)
            this.getEntityStoreRegistry().registerSystem(new EnchantmentFeatherFallingSystem(enchantmentManager));
            LOGGER.atInfo().log("Registered EnchantmentFeatherFallingSystem with ECS");
            this.enchantmentWaterbreathingSystem = new EnchantmentWaterbreathingSystem(enchantmentManager);
            this.getEntityStoreRegistry().registerSystem(enchantmentWaterbreathingSystem);
            LOGGER.atInfo().log("Registered EnchantmentWaterbreathingSystem with ECS");

            // Recipe crafting guard: cancels crafts of disabled scrolls/tables at runtime.
            // CraftRecipeEvent.Pre is an ECS event, so it must be an entity-store system.
            this.getEntityStoreRegistry().registerSystem(new org.herolias.plugin.enchantment.CraftRecipeCancelSystem());
            LOGGER.atInfo().log("Registered CraftRecipeCancelSystem with ECS");
            this.getEntityStoreRegistry().registerSystem(new EnchantmentBurnSystem(enchantmentManager));
            LOGGER.atInfo().log("Registered EnchantmentBurnSystem with ECS");
            this.getEntityStoreRegistry().registerSystem(new EnchantmentPoisonSystem(enchantmentManager));
            LOGGER.atInfo().log("Registered EnchantmentPoisonSystem with ECS");
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
            this.getEntityStoreRegistry().registerSystem(enchantmentFastSwimSystem);
            LOGGER.atInfo().log("Registered EnchantmentFastSwimSystem with ECS");
            this.enchantmentNightVisionSystem = new EnchantmentNightVisionSystem(enchantmentManager);
            this.getEntityStoreRegistry().registerSystem(enchantmentNightVisionSystem);
            LOGGER.atInfo().log("Registered EnchantmentNightVisionSystem with ECS");

            // Create Second Stomach system first so Regeneration can report to it
            EnchantmentSecondStomachSystem secondStomachSystem = new EnchantmentSecondStomachSystem(enchantmentManager);

            this.getEntityStoreRegistry().registerSystem(new EnchantmentRegenerationSystem(enchantmentManager));
            LOGGER.atInfo().log("Registered EnchantmentRegenerationSystem with ECS");
            this.getEntityStoreRegistry().registerSystem(secondStomachSystem);
            LOGGER.atInfo().log("Registered EnchantmentSecondStomachSystem with ECS");

        } catch (Exception e) {
            LOGGER.atSevere().withCause(e).log("Could not register enchantment ECS systems");
        }

        // Initialize and register the state transfer system (preserves enchantments on
        // item state changes)
        EnchantmentStateTransferSystem stateTransferSystem = new EnchantmentStateTransferSystem(enchantmentManager);
        this.getEntityStoreRegistry().registerSystem(stateTransferSystem);
        LOGGER.atInfo().log("Registered EnchantmentStateTransferSystem listener");

        // Initialize and register the durability system (Event Listener)
        EnchantmentDurabilitySystem durabilitySystem = new EnchantmentDurabilitySystem(enchantmentManager);
        this.getEntityStoreRegistry().registerSystem(durabilitySystem);
        LOGGER.atInfo().log("Registered EnchantmentDurabilitySystem listener");

        // Register the Eternal Shot tracking system (InventoryChangeEvent listener)
        // Works with EnchantmentProjectileSpeedSystem: tracks consumed ammo, refunds on
        // projectile spawn
        this.getEntityStoreRegistry().registerSystem(eternalShotSystem);

        // Active-slot changes (hotbar / off-hand / tools): glow refresh + Eternal Shot
        // hand-off. Replaces the former 50 ms scheduler poll.
        this.getEntityStoreRegistry().registerSystem(
                new org.herolias.plugin.enchantment.EnchantmentActiveSlotSystem(enchantmentManager, eternalShotSystem));
        LOGGER.atInfo().log("Registered EnchantmentActiveSlotSystem with ECS");

        // Initialize and register Elemental Heart System (Essence Saver)
        EnchantmentElementalHeartSystem elementalHeartSystem = new EnchantmentElementalHeartSystem(enchantmentManager);
        this.getEntityStoreRegistry().registerSystem(elementalHeartSystem);
        LOGGER.atInfo().log("Registered EnchantmentElementalHeartSystem listener");

        // Player lifecycle: initial glow on add, and every per-player cleanup on remove.
        this.playerLifecycleSystem = new org.herolias.plugin.enchantment.EnchantmentPlayerLifecycleSystem(
                enchantmentManager,
                eternalShotSystem::cleanupPlayer,
                elementalHeartSystem::cleanupPlayer,
                enchantmentFastSwimSystem::cleanupPlayer,
                enchantmentNightVisionSystem::cleanupPlayer,
                enchantmentWaterbreathingSystem::cleanupPlayer,
                org.herolias.plugin.enchantment.EquipmentLevelCache::cleanupPlayer);
        this.getEntityStoreRegistry().registerSystem(playerLifecycleSystem);
        LOGGER.atInfo().log("Registered EnchantmentPlayerLifecycleSystem with ECS");

        // Register DropItemEventSystem to track manual drops for Eternal Shot fix
        // This prevents arrows from being duplicated when players manually drop them
        try {
            this.getEntityStoreRegistry()
                    .registerSystem(new DropItemEventSystem(eternalShotSystem, elementalHeartSystem));
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
        this.getEntityStoreRegistry().registerSystem(salvageSystem);
        LOGGER.atInfo().log("Registered EnchantmentSalvageSystem listener");

        this.getEntityStoreRegistry().registerSystem(new EngravingTableInteractionSystem(this, enchantmentManager));
        LOGGER.atInfo().log("Registered EngravingTableInteractionSystem");

        // Register EnchantmentVisualsListener (Event driven visual updates)
        // Optimized to replace heavy per-tick polling
        EnchantmentVisualsListener visualsListener = new EnchantmentVisualsListener(enchantmentManager);
        this.getEntityStoreRegistry().registerSystem(visualsListener);
        LOGGER.atInfo().log("Registered EnchantmentVisualsListener");

        // Register ScrollDescriptionManager to send global translation updates on join
        // Using PlayerReadyEvent as it ensures the player is fully connected (similar
        // to WelcomeListener)
        this.getEventRegistry().registerGlobal(PlayerReadyEvent.class, event -> {
            Player player = event.getPlayer();
            if (player.getWorld() == null || player.getReference() == null)
                return;

            player.getWorld().execute(() -> {
                com.hypixel.hytale.server.core.universe.PlayerRef playerRef = player.getWorld().getEntityStore()
                        .getStore().getComponent(player.getReference(),
                                com.hypixel.hytale.server.core.universe.PlayerRef.getComponentType());
                if (playerRef != null) {
                    String langCode = userSettingsManager.getLanguage(playerRef.getUuid());
                    languageManager.sendUpdatePacket(playerRef, langCode);
                    org.herolias.plugin.enchantment.ScrollDescriptionManager.sendUpdatePacket(playerRef);
                    org.herolias.plugin.enchantment.NativeTooltipManager.refreshPlayer(playerRef.getUuid());
                }
            });
        });
        LOGGER.atInfo().log("Registered ScrollDescriptionManager and native tooltip migration listener");

        this.getEventRegistry().registerGlobal(PlayerDisconnectEvent.class, event -> {
            if (playerLifecycleSystem != null && event.getPlayerRef() != null) {
                playerLifecycleSystem.cleanupPlayer(event.getPlayerRef().getUuid());
            }
        });
        LOGGER.atInfo().log("Registered per-player cleanup listener");

        // Smelting / cooking recipe registries follow crafting-recipe reloads
        this.getEventRegistry().register(
                com.hypixel.hytale.assetstore.event.LoadedAssetsEvent.class,
                com.hypixel.hytale.server.core.asset.type.item.config.CraftingRecipe.class,
                this::onRecipesLoaded);

        LOGGER.atInfo().log("Using native per-stack ItemDisplay metadata for enchantment tooltips");

        // Register Welcome Listener (One-time notification for tooltips)
        this.getEventRegistry().registerGlobal(
                com.hypixel.hytale.server.core.event.events.player.PlayerReadyEvent.class,
                new org.herolias.plugin.listener.WelcomeListener(this)::onPlayerReady);

        // Register commands
        this.getCommandRegistry().registerCommand(new EnchantCommand(this));
        LOGGER.atInfo().log("Registered /enchant command");

        this.getCommandRegistry().registerCommand(new org.herolias.plugin.command.EnchantingCommand(this));
        LOGGER.atInfo().log("Registered /enchanting command");

        // Register custom give command without overriding vanilla /give
        this.getCommandRegistry().registerCommand(new org.herolias.plugin.command.GiveEnchantedCommand());
        LOGGER.atInfo().log("Registered /giveenchanted command");

        // Register config editor command
        this.getCommandRegistry().registerCommand(new org.herolias.plugin.command.EnchantConfigCommand(this));
        LOGGER.atInfo().log("Registered /enchantconfig command");

        LOGGER.atInfo().log("SimpleEnchanting setup complete!");
        LOGGER.atInfo().log("Using metadata-based enchantment storage");
    }

    @Override
    protected void start() {
        // Anonymous usage metrics (HStats). The current upstream version performs
        // its HTTP requests asynchronously on the server scheduler with connect/read
        // timeouts, so constructing it here no longer blocks startup. The class itself
        // may not be modified (see its license header).
        try {
            this.hStats = new HStats("b04768bd-4189-4ecc-b29c-0f644d7c95cc",
                    this.getManifest().getVersion().toString());
        } catch (Throwable t) {
            LOGGER.atWarning().withCause(t).log("HStats metrics initialisation failed (ignored)");
        }

        try {
            org.herolias.plugin.enchantment.NativeTooltipManager.refreshAllPlayers();
        } catch (Exception e) {
            LOGGER.atWarning().withCause(e).log("Failed to refresh native tooltips on start");
        }
    }

    @Override
    protected void shutdown() {
        // Flush pending per-player settings and stop the save executor
        if (userSettingsManager != null) {
            try {
                userSettingsManager.shutdown();
            } catch (Exception e) {
                LOGGER.atWarning().withCause(e).log("Failed to flush user settings on shutdown");
            }
        }
        // Remove runtime-generated assets so a plugin reload starts clean
        try {
            EnchantmentRecipeManager.unload();
            ScrollItemGenerator.unload();
        } catch (Exception e) {
            LOGGER.atWarning().withCause(e).log("Failed to unload runtime-generated assets");
        }
        hStats = null;
        super.shutdown();
    }

    private void onRecipesLoaded(
            com.hypixel.hytale.assetstore.event.LoadedAssetsEvent<String, com.hypixel.hytale.server.core.asset.type.item.config.CraftingRecipe, com.hypixel.hytale.assetstore.map.DefaultAssetMap<String, com.hypixel.hytale.server.core.asset.type.item.config.CraftingRecipe>> event) {
        if (enchantmentManager == null) {
            return;
        }
        try {
            enchantmentManager.getSmeltingRecipeRegistry().reload();
            enchantmentManager.getCookingRecipeRegistry().reload();
        } catch (Exception e) {
            LOGGER.atWarning().withCause(e).log("Failed to rebuild smelting/cooking recipe registries");
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
