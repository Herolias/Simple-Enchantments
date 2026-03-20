package org.herolias.plugin.enchantment;

import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Categorizes items for enchantment applicability.
 * 
 * Used by EnchantmentType to determine which enchantments
 * can be applied to which types of items.
 * <p>
 * Refactored from enum to class to allow dynamic registration of new
 * categories.
 */
public class ItemCategory {

    // Static registry for backward compatibility and easy access

    /**
     * Melee weapons: swords, axes, maces, etc.
     * Applicable enchantments: Sharpness, Fire Aspect, Knockback, etc.
     */
    public static final ItemCategory MELEE_WEAPON = new ItemCategory("MELEE_WEAPON", true, false, false);

    /**
     * Ranged weapons: bows, crossbows, etc.
     * Applicable enchantments: Power, Flame, Infinity, etc.
     */
    public static final ItemCategory RANGED_WEAPON = new ItemCategory("RANGED_WEAPON", true, false, false);

    /**
     * Tools: hoes, scythes, sickles, shears, etc.
     * Applicable enchantments: Durability, etc.
     */
    public static final ItemCategory TOOL = new ItemCategory("TOOL", false, false, true);

    /**
     * Pickaxes (mining tools).
     * Applicable enchantments: Efficiency, Fortune, Durability, etc.
     */
    public static final ItemCategory PICKAXE = new ItemCategory("PICKAXE", false, false, true);

    /**
     * Shovels (digging tools).
     * Applicable enchantments: Efficiency, Durability, etc.
     */
    public static final ItemCategory SHOVEL = new ItemCategory("SHOVEL", false, false, true);

    /**
     * Axes and hatchets.
     * Note: "Battleaxe" is usually MELEE_WEAPON. "Hatchet" is AXE (Tool).
     */
    public static final ItemCategory AXE = new ItemCategory("AXE", false, false, true);

    /**
     * Shields.
     * Applicable enchantments: Dexterity, etc.
     */
    public static final ItemCategory SHIELD = new ItemCategory("SHIELD", false, false, false); // Shield is special

    /**
     * Boots (foot armor).
     * Applicable enchantments: Feather Falling, etc.
     */
    public static final ItemCategory BOOTS = new ItemCategory("BOOTS", false, true, false);

    /**
     * Helmets (head armor).
     * Applicable enchantments: Waterbreathing, etc.
     */
    public static final ItemCategory HELMET = new ItemCategory("HELMET", false, true, false);

    /**
     * Armor pieces: helmets, chestplates, leggings, boots
     * Applicable enchantments: Protection, Fire Protection, etc.
     */
    public static final ItemCategory ARMOR = new ItemCategory("ARMOR", false, true, false);

    /**
     * Gloves (hand armor).
     * Applicable enchantments: Fast Swim, etc.
     */
    public static final ItemCategory GLOVES = new ItemCategory("GLOVES", false, true, false);

    /**
     * Staffs (magic weapons).
     * Applicable enchantments: Thrift, Elemental Heart.
     */
    public static final ItemCategory STAFF = new ItemCategory("STAFF", true, false, false);
    /**
     * Mana Staffs (consume Mana).
     * Applicable enchantments: Thrift.
     */
    public static final ItemCategory STAFF_MANA = new ItemCategory("STAFF_MANA", true, false, false);

    /**
     * Essence Staffs (consume items).
     * Applicable enchantments: Elemental Heart.
     */
    public static final ItemCategory STAFF_ESSENCE = new ItemCategory("STAFF_ESSENCE", true, false, false);

    /**
     * Unknown or non-enchantable items
     */
    public static final ItemCategory UNKNOWN = new ItemCategory("UNKNOWN", false, false, false);

    private final String id;
    private final boolean isWeapon;
    private final boolean isArmor;
    private final boolean isTool;

    public ItemCategory(String id) {
        this(id, false, false, false);
    }

    private ItemCategory(String id, boolean isWeapon, boolean isArmor, boolean isTool) {
        this.id = id;
        this.isWeapon = isWeapon;
        this.isArmor = isArmor;
        this.isTool = isTool;
    }

    public String getId() {
        return id;
    }

    public boolean isWeapon() {
        return isWeapon;
    }

    public boolean isArmor() {
        return isArmor;
    }

    public boolean isTool() {
        return isTool;
    }

    public boolean isShield() {
        return this == SHIELD;
    }

    public boolean isMelee() {
        return this == MELEE_WEAPON || this == AXE;
    }

    public boolean isRanged() {
        return this == RANGED_WEAPON;
    }

    /**
     * Checks if this category is enchantable.
     */
    public boolean isEnchantable() {
        return !this.equals(UNKNOWN);
    }

    @Override
    public String toString() {
        return id;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o)
            return true;
        if (o == null || getClass() != o.getClass())
            return false;
        ItemCategory that = (ItemCategory) o;
        return Objects.equals(id, that.id);
    }

    @Override
    public int hashCode() {
        return Objects.hash(id);
    }
}
