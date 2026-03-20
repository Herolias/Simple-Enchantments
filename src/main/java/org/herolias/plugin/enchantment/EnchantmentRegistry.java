package org.herolias.plugin.enchantment;

import com.hypixel.hytale.logger.HytaleLogger;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Central registry for all enchantment types (built-in and addon-registered).
 * <p>
 * Thread-safe for registration during setup() phase.
 * After startup, the registry is effectively read-only.
 */
public final class EnchantmentRegistry {

    private static final HytaleLogger LOGGER = HytaleLogger.forEnclosingClass();

    private static EnchantmentRegistry instance;

    /** All registered enchantments keyed by their unique ID. */
    private final Map<String, EnchantmentType> byId = new ConcurrentHashMap<>();

    /** Lookup by display name (lowercase) for backward compatibility with BSON. */
    private final Map<String, EnchantmentType> byDisplayName = new ConcurrentHashMap<>();

    /** Conflict pairs stored as sets of two IDs. Both directions are checked. */
    private final Set<Set<String>> conflictPairs = ConcurrentHashMap.newKeySet();

    /** Ordered list preserving registration order (built-in first, then addons). */
    private final List<EnchantmentType> registrationOrder = Collections.synchronizedList(new ArrayList<>());

    private EnchantmentRegistry() {
    }

    /**
     * Gets or creates the singleton registry instance.
     */
    @Nonnull
    public static EnchantmentRegistry getInstance() {
        if (instance == null) {
            instance = new EnchantmentRegistry();
        }
        return instance;
    }

    /**
     * Registers an enchantment type. Called internally by EnchantmentType
     * constructor
     * and by the public API for addon enchantments.
     *
     * @param type The enchantment type to register
     * @throws IllegalArgumentException if the ID or display name is already
     *                                  registered
     */
    public void register(@Nonnull EnchantmentType type) {
        Objects.requireNonNull(type, "EnchantmentType cannot be null");
        String id = type.getId().toLowerCase();

        if (byId.containsKey(id)) {
            throw new IllegalArgumentException("Enchantment ID already registered: '" + id + "'");
        }

        String displayKey = type.getDisplayName().toLowerCase();
        if (byDisplayName.containsKey(displayKey)) {
            throw new IllegalArgumentException(
                    "Enchantment display name already registered: '" + type.getDisplayName()
                            + "' (conflicts with '" + byDisplayName.get(displayKey).getId() + "')");
        }

        byId.put(id, type);
        byDisplayName.put(displayKey, type);
        registrationOrder.add(type);
        LOGGER.atInfo().log("Registered enchantment: " + type.getId() + " (" + type.getDisplayName() + ")"
                + (type.isBuiltIn() ? "" : " [addon: " + type.getOwnerModId() + "]"));
    }

    /**
     * Registers a conflict pair between two enchantment IDs.
     * Conflicts are bidirectional — if A conflicts with B, then B conflicts with A.
     */
    public void addConflict(@Nonnull String id1, @Nonnull String id2) {
        conflictPairs.add(Set.of(id1.toLowerCase(), id2.toLowerCase()));
    }

    /**
     * Checks if two enchantment IDs conflict with each other.
     */
    public boolean areConflicting(@Nonnull String id1, @Nonnull String id2) {
        if (id1.equalsIgnoreCase(id2))
            return true;
        return conflictPairs.contains(Set.of(id1.toLowerCase(), id2.toLowerCase()));
    }

    /**
     * Gets an enchantment by its unique ID. Case-insensitive.
     */
    @Nullable
    public EnchantmentType getById(@Nonnull String id) {
        return byId.get(id.toLowerCase());
    }

    /**
     * Gets an enchantment by its display name. Case-insensitive.
     */
    @Nullable
    public EnchantmentType getByDisplayName(@Nonnull String displayName) {
        return byDisplayName.get(displayName.toLowerCase());
    }

    /**
     * Returns all registered enchantments in registration order.
     * Built-in enchantments appear first, followed by addon enchantments.
     */
    @Nonnull
    public Collection<EnchantmentType> getAll() {
        return Collections.unmodifiableList(new ArrayList<>(registrationOrder));
    }

    /**
     * Returns all registered enchantments as an array.
     * Drop-in replacement for the old EnchantmentType.values().
     */
    @Nonnull
    public EnchantmentType[] values() {
        return registrationOrder.toArray(new EnchantmentType[0]);
    }

    /**
     * Returns the number of registered enchantments.
     */
    public int size() {
        return byId.size();
    }

    /**
     * Checks if an enchantment ID is registered.
     */
    public boolean isRegistered(@Nonnull String id) {
        return byId.containsKey(id.toLowerCase());
    }

    /**
     * Returns only built-in (Simple Enchantments) enchantments.
     */
    @Nonnull
    public Collection<EnchantmentType> getBuiltIn() {
        List<EnchantmentType> result = new ArrayList<>();
        for (EnchantmentType type : registrationOrder) {
            if (type.isBuiltIn()) {
                result.add(type);
            }
        }
        return Collections.unmodifiableList(result);
    }

    /**
     * Returns only addon-registered enchantments.
     */
    @Nonnull
    public Collection<EnchantmentType> getAddonEnchantments() {
        List<EnchantmentType> result = new ArrayList<>();
        for (EnchantmentType type : registrationOrder) {
            if (!type.isBuiltIn()) {
                result.add(type);
            }
        }
        return Collections.unmodifiableList(result);
    }
}
