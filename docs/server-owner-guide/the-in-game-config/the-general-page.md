# The General Page

On the **General Page**, you can edit core mod settings and behaviors. Here is a breakdown of all available configuration options:

| Setting | Default | Description |
| :--- | :--- | :--- |
| **Max Enchantments Per Item** | <!-- DOCSTAT:config.maxEnchantmentsPerItem;code -->`5`<!-- /DOCSTAT --> | Defines the maximum number of enchantments that can be applied to an item. *Note: This will not delete enchantments if an existing item already exceeds the new maximum.* |
| **Enable Enchantment Glow** | <!-- DOCSTAT:config.enableEnchantmentGlow;enabled_code -->`Enabled`<!-- /DOCSTAT --> | Toggles the visual glow effect on enchanted items. This can also be changed individually in user client settings. |
| **Allow Same Scroll Upgrades** | <!-- DOCSTAT:config.allowSameScrollUpgrades;enabled_code -->`Enabled`<!-- /DOCSTAT --> | Allows enchantments to upgrade to the next tier by applying the same scroll again (e.g., applying Sharpness I to a Sharpness I sword creates Sharpness II). |
| **Enchanting Table Crafting Tier** | <!-- DOCSTAT:config.enchantingTableCraftingTier;code -->`2`<!-- /DOCSTAT --> | Determines which Workbench Tier is required to unlock the Enchanting Table. |
| **Disable Enchantment Crafting** | <!-- DOCSTAT:config.disableEnchantmentCrafting;enabled_code -->`Disabled`<!-- /DOCSTAT --> | Prevents the Enchanting Table and Scrolls from being crafted. Existing tables in the world will remain but will stop functioning until this is re-enabled. |
| **Return Enchantment on Cleanse** | <!-- DOCSTAT:config.returnEnchantmentOnCleanse;enabled_code -->`Disabled`<!-- /DOCSTAT --> | Using a *[Scroll of Cleansing](../../welcome-to-simple-enchantments/scroll-of-cleansing.md)* to remove an enchantment will return that specific Enchantment Scroll to the player. |
| **Salvager Yields Scroll** | <!-- DOCSTAT:config.salvagerYieldsScroll;enabled_code -->`Enabled`<!-- /DOCSTAT --> | Salvaging an enchanted tool yields its highest-level enchantment back as a scroll. |
| **Enchanting Table Recipe** | *N/A* | Opens configuration for the Enchanting Table crafting recipe. More details can be found in the [Recipe Editor guide](the-recipes-page.md). |
| **Upgrade Tier Cost (<!-- DOCSTAT:enchantingTable.upgrade.range -->2-4<!-- /DOCSTAT -->)** | *N/A* | Configures the specific upgrade recipe requirements for the Enchanting Table crafting tiers. |
| **Show Welcome Messages** | <!-- DOCSTAT:config.showWelcomeMessage;enabled_code -->`Enabled`<!-- /DOCSTAT --> | Sends a one-time welcome chat message to players when they join the server for the first time. |
