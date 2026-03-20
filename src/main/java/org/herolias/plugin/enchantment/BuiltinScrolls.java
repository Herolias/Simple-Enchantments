package org.herolias.plugin.enchantment;

import java.util.HashMap;
import java.util.Map;

/**
 * Stores quality (rarity), crafting categories, and item level metadata
 * for all built-in scroll items.
 * <p>
 * This data was previously embedded in the static JSON item files.
 * Values are extracted from git history of the deleted JSON files.
 */
public final class BuiltinScrolls {

    private static final Map<String, ScrollMeta> META = new HashMap<>();

    static {
        // ─── Absorption (Shield) ───
        reg("Scroll_Absorption_I", "Uncommon", 1, "Enchanting_Shield");
        reg("Scroll_Absorption_II", "Rare", 1, "Enchanting_Shield");
        reg("Scroll_Absorption_III", "Epic", 1, "Enchanting_Shield");

        // ─── Burn (Melee + Ranged, Legendary) ───
        reg("Scroll_Burn_I", "Legendary", 4, "Enchanting_Melee", "Enchanting_Ranged");

        // ─── Coup de Grâce (Melee) ───
        reg("Scroll_Coup_De_Grace_I", "Uncommon", 1, "Enchanting_Melee");
        reg("Scroll_Coup_De_Grace_II", "Rare", 2, "Enchanting_Melee");
        reg("Scroll_Coup_De_Grace_III", "Epic", 3, "Enchanting_Melee");

        // ─── Dexterity (Melee + Shield) ───
        reg("Scroll_Dexterity_I", "Uncommon", 1, "Enchanting_Melee", "Enchanting_Shield");
        reg("Scroll_Dexterity_II", "Rare", 2, "Enchanting_Melee", "Enchanting_Shield");
        reg("Scroll_Dexterity_III", "Epic", 3, "Enchanting_Melee", "Enchanting_Shield");

        // ─── Durability (Armor + Tools + Melee + Ranged) ───
        reg("Scroll_Durability_I", "Uncommon", 1, "Enchanting_Armor", "Enchanting_Tools", "Enchanting_Melee",
                "Enchanting_Ranged");
        reg("Scroll_Durability_II", "Rare", 2, "Enchanting_Armor", "Enchanting_Tools", "Enchanting_Melee",
                "Enchanting_Ranged");
        reg("Scroll_Durability_III", "Epic", 3, "Enchanting_Armor", "Enchanting_Tools", "Enchanting_Melee",
                "Enchanting_Ranged");

        // ─── Eagle's Eye (Ranged) ───
        reg("Scroll_Eagles_Eye_I", "Uncommon", 1, "Enchanting_Ranged");
        reg("Scroll_Eagles_Eye_II", "Rare", 2, "Enchanting_Ranged");
        reg("Scroll_Eagles_Eye_III", "Epic", 3, "Enchanting_Ranged");

        // ─── Efficiency (Tools) ───
        reg("Scroll_Efficiency_I", "Uncommon", 1, "Enchanting_Tools");
        reg("Scroll_Efficiency_II", "Rare", 2, "Enchanting_Tools");
        reg("Scroll_Efficiency_III", "Epic", 3, "Enchanting_Tools");

        // ─── Elemental Heart (Staff, Legendary) ───
        reg("Scroll_ElementalHeart_I", "Legendary", 4, "Enchanting_Staff");

        // ─── Eternal Shot (Ranged) ───
        reg("Scroll_Eternal_Shot_I", "Epic", 3, "Enchanting_Ranged");

        // ─── Fast Swim (Armor) ───
        reg("Scroll_FastSwim_I", "Uncommon", 1, "Enchanting_Armor");
        reg("Scroll_FastSwim_II", "Rare", 2, "Enchanting_Armor");
        reg("Scroll_FastSwim_III", "Epic", 3, "Enchanting_Armor");

        // ─── Feather Falling (Armor) ───
        reg("Scroll_Feather_Falling_I", "Uncommon", 1, "Enchanting_Armor");
        reg("Scroll_Feather_Falling_II", "Rare", 2, "Enchanting_Armor");
        reg("Scroll_Feather_Falling_III", "Epic", 3, "Enchanting_Armor");

        // ─── Fortune (Tools) ───
        reg("Scroll_Fortune_I", "Uncommon", 1, "Enchanting_Tools");
        reg("Scroll_Fortune_II", "Rare", 2, "Enchanting_Tools");
        reg("Scroll_Fortune_III", "Epic", 3, "Enchanting_Tools");

        // ─── Freeze (Ranged + Melee, Legendary) ───
        reg("Scroll_Freeze_I", "Legendary", 4, "Enchanting_Ranged", "Enchanting_Melee");

        // ─── Frenzy (Melee + Ranged) ───
        reg("Scroll_Frenzy_I", "Uncommon", 1, "Enchanting_Melee", "Enchanting_Ranged");
        reg("Scroll_Frenzy_II", "Rare", 2, "Enchanting_Melee", "Enchanting_Ranged");
        reg("Scroll_Frenzy_III", "Epic", 3, "Enchanting_Melee", "Enchanting_Ranged");

        // ─── Knockback (Melee) ───
        reg("Scroll_Knockback_I", "Uncommon", 1, "Enchanting_Melee");
        reg("Scroll_Knockback_II", "Rare", 2, "Enchanting_Melee");
        reg("Scroll_Knockback_III", "Epic", 3, "Enchanting_Melee");

        // ─── Life Leech (Melee, Legendary) ───
        reg("Scroll_Life_Leech_I", "Legendary", 4, "Enchanting_Melee");

        // ─── Looting (Melee + Ranged) ───
        reg("Scroll_Looting_I", "Uncommon", 1, "Enchanting_Melee", "Enchanting_Ranged");
        reg("Scroll_Looting_II", "Rare", 2, "Enchanting_Melee", "Enchanting_Ranged");
        reg("Scroll_Looting_III", "Epic", 3, "Enchanting_Melee", "Enchanting_Ranged");

        // ─── Night Vision (Armor, Legendary) ───
        reg("Scroll_Night_Vision_I", "Legendary", 4, "Enchanting_Armor");

        // ─── Protection (Armor) ───
        reg("Scroll_Protection_I", "Uncommon", 1, "Enchanting_Armor");
        reg("Scroll_Protection_II", "Rare", 2, "Enchanting_Armor");
        reg("Scroll_Protection_III", "Epic", 3, "Enchanting_Armor");

        // ─── Ranged Protection (Armor) ───
        reg("Scroll_Ranged_Protection_I", "Uncommon", 1, "Enchanting_Armor");
        reg("Scroll_Ranged_Protection_II", "Rare", 2, "Enchanting_Armor");
        reg("Scroll_Ranged_Protection_III", "Epic", 3, "Enchanting_Armor");

        // ─── Reflection (Shield) ───
        reg("Scroll_Reflection_I", "Uncommon", 1, "Enchanting_Shield");
        reg("Scroll_Reflection_II", "Rare", 2, "Enchanting_Shield");
        reg("Scroll_Reflection_III", "Epic", 3, "Enchanting_Shield");

        // ─── Riposte (Melee) ───
        reg("Scroll_Riposte_I", "Uncommon", 1, "Enchanting_Melee");
        reg("Scroll_Riposte_II", "Rare", 2, "Enchanting_Melee");
        reg("Scroll_Riposte_III", "Epic", 3, "Enchanting_Melee");

        // ─── Sharpness (Melee) ───
        reg("Scroll_Sharpness_I", "Uncommon", 1, "Enchanting_Melee");
        reg("Scroll_Sharpness_II", "Rare", 2, "Enchanting_Melee");
        reg("Scroll_Sharpness_III", "Epic", 3, "Enchanting_Melee");

        // ─── Silktouch (Tools, Legendary) ───
        reg("Scroll_Silktouch_I", "Legendary", 4, "Enchanting_Tools");

        // ─── Smelting (Tools, Legendary) ───
        reg("Scroll_Smelting_I", "Legendary", 4, "Enchanting_Tools");

        // ─── Strength (Ranged) ───
        reg("Scroll_Strength_I", "Uncommon", 1, "Enchanting_Ranged");
        reg("Scroll_Strength_II", "Rare", 2, "Enchanting_Ranged");
        reg("Scroll_Strength_III", "Epic", 3, "Enchanting_Ranged");

        // ─── Sturdy (Armor + Tools + Melee + Ranged, Legendary) ───
        reg("Scroll_Sturdy_I", "Legendary", 4, "Enchanting_Armor", "Enchanting_Tools", "Enchanting_Melee",
                "Enchanting_Ranged");

        // ─── Thrift (Staff) ───
        reg("Scroll_Thrift_I", "Uncommon", 1, "Enchanting_Staff");
        reg("Scroll_Thrift_II", "Rare", 2, "Enchanting_Staff");
        reg("Scroll_Thrift_III", "Epic", 3, "Enchanting_Staff");

        // ─── Waterbreathing (Armor) ───
        reg("Scroll_Waterbreathing_I", "Uncommon", 1, "Enchanting_Armor");
        reg("Scroll_Waterbreathing_II", "Rare", 2, "Enchanting_Armor");
        reg("Scroll_Waterbreathing_III", "Epic", 3, "Enchanting_Armor");

        // ─── Poison (Melee + Ranged, Legendary) ───
        reg("Scroll_Poison_I", "Legendary", 4, "Enchanting_Melee", "Enchanting_Ranged");

        // ─── Environmental Protection (Armor) ───
        reg("Scroll_Environmental_Protection_I", "Uncommon", 1, "Enchanting_Armor");
        reg("Scroll_Environmental_Protection_II", "Rare", 2, "Enchanting_Armor");
        reg("Scroll_Environmental_Protection_III", "Epic", 3, "Enchanting_Armor");
    }

    private static void reg(String id, String quality, int itemLevel, String... categories) {
        META.put(id, new ScrollMeta(quality, itemLevel, categories));
    }

    public static String getQuality(String scrollItemId) {
        ScrollMeta m = META.get(scrollItemId);
        return m != null ? m.quality : "Uncommon";
    }

    public static String[] getCraftingCategories(String scrollItemId) {
        ScrollMeta m = META.get(scrollItemId);
        return m != null ? m.categories : null;
    }

    public static int getItemLevel(String scrollItemId) {
        ScrollMeta m = META.get(scrollItemId);
        return m != null ? m.itemLevel : 1;
    }

    private static class ScrollMeta {
        final String quality;
        final int itemLevel;
        final String[] categories;

        ScrollMeta(String quality, int itemLevel, String[] categories) {
            this.quality = quality;
            this.itemLevel = itemLevel;
            this.categories = categories;
        }
    }

    private BuiltinScrolls() {
    }
}
