package org.herolias.plugin.enchantment;

import org.bson.BsonDocument;
import org.bson.BsonInt32;
import org.bson.BsonValue;

import java.util.HashMap;
import java.util.Map;
import java.util.TreeMap;
import java.util.Collections;
import javax.annotation.Nonnull;
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

    /** Shared immutable empty instance — avoids allocations for items without enchantments. */
    public static final EnchantmentData EMPTY = new EnchantmentData(Collections.emptyMap(), true);
    
    private final Map<EnchantmentType, Integer> enchantments;
    private final boolean immutable;

    /** Lazily computed stable hash string.  {@code null} until first requested. */
    private volatile String cachedHash;
    
    public EnchantmentData() {
        this.enchantments = new HashMap<>();
        this.immutable = false;
    }
    
    public EnchantmentData(Map<EnchantmentType, Integer> enchantments) {
        this.enchantments = new HashMap<>(enchantments);
        this.immutable = false;
    }

    /**
     * Private constructor for creating immutable instances.
     */
    private EnchantmentData(Map<EnchantmentType, Integer> enchantments, boolean immutable) {
        this.enchantments = immutable ? Collections.unmodifiableMap(new HashMap<>(enchantments)) : new HashMap<>(enchantments);
        this.immutable = immutable;
    }
    
    /**
     * Adds or upgrades an enchantment on this item.
     * 
     * @param type The enchantment type
     * @param level The enchantment level (clamped to max level)
     * @throws UnsupportedOperationException if this instance is immutable (e.g. EMPTY)
     */
    public void addEnchantment(EnchantmentType type, int level) {
        if (immutable) {
            throw new UnsupportedOperationException("Cannot modify immutable EnchantmentData");
        }
        enchantments.put(type, level);
    }
    
    /**
     * Removes an enchantment from this item.
     * 
     * @throws UnsupportedOperationException if this instance is immutable (e.g. EMPTY)
     */
    public void removeEnchantment(EnchantmentType type) {
        if (immutable) {
            throw new UnsupportedOperationException("Cannot modify immutable EnchantmentData");
        }
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

    // ========== Hash / Equality (for caching) ==========

    /**
     * Returns a deterministic 8-char hex hash string derived from the sorted
     * enchantment IDs and levels.  Computed once and cached thereafter.
     * <p>
     * The hash is stable: the same set of enchantments always produces the
     * same string regardless of internal map iteration order.
     */
    @Nonnull
    public String computeStableHash() {
        String h = cachedHash;
        if (h == null) {
            TreeMap<String, Integer> sorted = new TreeMap<>();
            for (Map.Entry<EnchantmentType, Integer> entry : enchantments.entrySet()) {
                sorted.put(entry.getKey().getId(), entry.getValue());
            }
            StringBuilder sb = new StringBuilder();
            for (Map.Entry<String, Integer> entry : sorted.entrySet()) {
                if (sb.length() > 0) sb.append('_');
                sb.append(entry.getKey()).append(entry.getValue());
            }
            h = String.format("%08x", sb.toString().hashCode());
            cachedHash = h;
        }
        return h;
    }

    @Override
    public int hashCode() {
        return enchantments.hashCode();
    }

    @Override
    public boolean equals(Object obj) {
        if (this == obj) return true;
        if (!(obj instanceof EnchantmentData other)) return false;
        return enchantments.equals(other.enchantments);
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
    
    // ========== Legacy String Deserialization (kept for GiveEnchantedCommand and legacy item fallback) ==========
    
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
