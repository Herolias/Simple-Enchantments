package org.herolias.plugin.enchantment;

import org.bson.BsonDocument;
import org.bson.BsonInt32;
import org.bson.BsonValue;

import java.util.HashMap;
import java.util.Map;
import java.util.Collections;
import javax.annotation.Nullable;

/**
 * Represents the enchantment data stored on an item.
 * Contains all enchantments and their levels applied to an item.
 * 
 * This class supports BSON serialization for storage in Hytale's ItemStack metadata.
 */
public class EnchantmentData {
    
    /**
     * The metadata key used to store enchantments in ItemStack.
     * Usage: itemStack.withMetadata(METADATA_KEY, enchantmentData.toBson())
     */
    public static final String METADATA_KEY = "Enchantments";
    
    private final Map<EnchantmentType, Integer> enchantments;
    
    public EnchantmentData() {
        this.enchantments = new HashMap<>();
    }
    
    public EnchantmentData(Map<EnchantmentType, Integer> enchantments) {
        this.enchantments = new HashMap<>(enchantments);
    }
    
    /**
     * Adds or upgrades an enchantment on this item.
     * 
     * @param type The enchantment type
     * @param level The enchantment level (clamped to max level)
     */
    public void addEnchantment(EnchantmentType type, int level) {
        enchantments.put(type, level);
    }
    
    /**
     * Removes an enchantment from this item.
     */
    public void removeEnchantment(EnchantmentType type) {
        enchantments.remove(type);
    }
    
    /**
     * Gets the level of a specific enchantment.
     * 
     * @return The enchantment level, or 0 if not present
     */
    public int getLevel(EnchantmentType type) {
        return enchantments.getOrDefault(type, 0);
    }
    
    /**
     * Checks if this item has a specific enchantment.
     */
    public boolean hasEnchantment(EnchantmentType type) {
        return enchantments.containsKey(type);
    }
    
    /**
     * Gets all enchantments on this item.
     */
    public Map<EnchantmentType, Integer> getAllEnchantments() {
        return Collections.unmodifiableMap(enchantments);
    }
    
    /**
     * Checks if this item has any enchantments.
     */
    public boolean isEmpty() {
        return enchantments.isEmpty();
    }
    
    /**
     * Creates a copy of this enchantment data.
     */
    public EnchantmentData copy() {
        return new EnchantmentData(this.enchantments);
    }
    
    // ========== BSON Serialization for ItemStack Metadata ==========
    
    /**
     * Serializes enchantment data to a BsonDocument for ItemStack metadata storage.
     * Format: { "Sharpness": 2, "Durability": 1 }
     */
    public BsonDocument toBson() {
        BsonDocument doc = new BsonDocument();
        enchantments.forEach((type, level) -> {
            doc.put(type.getDisplayName(), new BsonInt32(level));
        });
        return doc;
    }
    
    /**
     * Deserializes enchantment data from a BsonDocument.
     * 
     * @param bson The BsonDocument from ItemStack metadata, or null
     * @return EnchantmentData containing the parsed enchantments
     */
    public static EnchantmentData fromBson(BsonDocument bson) {
        EnchantmentData result = new EnchantmentData();
        
        if (bson == null || bson.isEmpty()) {
            return result;
        }
        
        for (Map.Entry<String, BsonValue> entry : bson.entrySet()) {
            String enchantName = entry.getKey();
            BsonValue value = entry.getValue();
            
            // Find the enchantment type by display name
            EnchantmentType type = findByDisplayName(enchantName);
            if (type == null) {
                type = EnchantmentType.fromId(enchantName.toLowerCase());
            }
            Integer level = parseLevel(value);
            if (type != null && level != null) {
                result.addEnchantment(type, level);
            }
        }
        
        return result;
    }
    
    /**
     * Finds an EnchantmentType by its display name.
     */
    private static EnchantmentType findByDisplayName(String displayName) {
        return EnchantmentType.findByDisplayName(displayName);
    }

    @Nullable
    private static Integer parseLevel(BsonValue value) {
        if (value == null) {
            return null;
        }
        if (value.isInt32()) {
            return value.asInt32().getValue();
        }
        if (value.isInt64()) {
            return (int)value.asInt64().getValue();
        }
        if (value.isDouble()) {
            return (int)Math.round(value.asDouble().getValue());
        }
        return null;
    }
    
    // ========== Legacy String Serialization (kept for compatibility) ==========
    
    /**
     * Serializes enchantment data to a string for storage.
     * Format: "enchantmentId:level,enchantmentId:level,..."
     * @deprecated Use {@link #toBson()} for ItemStack metadata storage
     */
    @Deprecated
    public String serialize() {
        if (enchantments.isEmpty()) {
            return "";
        }
        
        StringBuilder sb = new StringBuilder();
        enchantments.forEach((type, level) -> {
            if (sb.length() > 0) {
                sb.append(",");
            }
            sb.append(type.getId()).append(":").append(level);
        });
        return sb.toString();
    }
    
    /**
     * Deserializes enchantment data from a string.
     * @deprecated Use {@link #fromBson(BsonDocument)} for ItemStack metadata storage
     */
    @Deprecated
    public static EnchantmentData deserialize(String data) {
        EnchantmentData result = new EnchantmentData();
        
        if (data == null || data.isEmpty()) {
            return result;
        }
        
        // Split by comma, whitespace, semicolon, plus, or pipe
        String[] parts = data.split("[,\\s;+|]+");
        for (String part : parts) {
            if (part.isEmpty()) continue;
            
            String[] keyValue = part.split(":");
            if (keyValue.length == 2) {
                String key = keyValue[0].trim();
                String valueStr = keyValue[1].trim();
                
                // Try ID first
                EnchantmentType type = EnchantmentType.fromId(key);
                // Try Display Name if ID failed
                if (type == null) {
                    type = EnchantmentType.findByDisplayName(key);
                }
                
                if (type != null) {
                    try {
                        int level = Integer.parseInt(valueStr);
                        result.addEnchantment(type, level);
                    } catch (NumberFormatException ignored) {
                        // Skip invalid levels
                    }
                }
            }
        }
        
        return result;
    }
}
