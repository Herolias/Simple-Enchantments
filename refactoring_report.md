# Simple-Enchantments Refactoring Report

## Summary

Comprehensive refactoring of the Simple-Enchantments mod (85 Java files, ~770KB). The changes remove dead code, eliminate duplicated boilerplate, unify nearly-identical methods, and clean up legacy/deprecated code while preserving all existing functionality.

**Total lines removed:** ~550+
**Files modified:** 26
**Files created:** 1

---

## Changes by Category

### 1. Event Dispatch Deduplication (15 ECS system files)

**Problem:** Every ECS system had the same 3-line event dispatch boilerplate:
```java
EnchantmentActivatedEvent ev = new EnchantmentActivatedEvent(playerRef, item, type, level);
HytaleServer.get().getEventBus().dispatchFor(EnchantmentActivatedEvent.class).dispatch(ev);
```
This was duplicated across 23 call sites in 15 files.

**Solution:** Created [EnchantmentEventHelper.java](file:///C:/Users/Elias/Documents/Simple-Enchantments/src/main/java/org/herolias/plugin/enchantment/EnchantmentEventHelper.java) with a single static method:
```java
EnchantmentEventHelper.fireActivated(playerRef, item, type, level);
```

**Files modified (23 dispatch sites → 1-line calls):**

| File | Instances |
|---|---|
| [EnchantmentDamageSystem.java](file:///C:/Users/Elias/Documents/Simple-Enchantments/src/main/java/org/herolias/plugin/enchantment/EnchantmentDamageSystem.java) | 6 |
| [EnchantmentDurabilitySystem.java](file:///C:/Users/Elias/Documents/Simple-Enchantments/src/main/java/org/herolias/plugin/enchantment/EnchantmentDurabilitySystem.java) | 2 |
| [EnchantmentBurnSystem.java](file:///C:/Users/Elias/Documents/Simple-Enchantments/src/main/java/org/herolias/plugin/enchantment/EnchantmentBurnSystem.java) | 1 |
| [EnchantmentFreezeSystem.java](file:///C:/Users/Elias/Documents/Simple-Enchantments/src/main/java/org/herolias/plugin/enchantment/EnchantmentFreezeSystem.java) | 1 |
| [EnchantmentKnockbackSystem.java](file:///C:/Users/Elias/Documents/Simple-Enchantments/src/main/java/org/herolias/plugin/enchantment/EnchantmentKnockbackSystem.java) | 1 |
| [EnchantmentBurnSmeltingSystem.java](file:///C:/Users/Elias/Documents/Simple-Enchantments/src/main/java/org/herolias/plugin/enchantment/EnchantmentBurnSmeltingSystem.java) | 1 |
| [EnchantmentSmeltingSystem.java](file:///C:/Users/Elias/Documents/Simple-Enchantments/src/main/java/org/herolias/plugin/enchantment/EnchantmentSmeltingSystem.java) | 1 |
| [EnchantmentFortuneSystem.java](file:///C:/Users/Elias/Documents/Simple-Enchantments/src/main/java/org/herolias/plugin/enchantment/EnchantmentFortuneSystem.java) | 1 |
| [EnchantmentSilktouchSystem.java](file:///C:/Users/Elias/Documents/Simple-Enchantments/src/main/java/org/herolias/plugin/enchantment/EnchantmentSilktouchSystem.java) | 1 |
| [EnchantmentLootingSystem.java](file:///C:/Users/Elias/Documents/Simple-Enchantments/src/main/java/org/herolias/plugin/enchantment/EnchantmentLootingSystem.java) | 1 |
| [EnchantmentBlockDamageSystem.java](file:///C:/Users/Elias/Documents/Simple-Enchantments/src/main/java/org/herolias/plugin/enchantment/EnchantmentBlockDamageSystem.java) | 1 |
| [EnchantmentFeatherFallingSystem.java](file:///C:/Users/Elias/Documents/Simple-Enchantments/src/main/java/org/herolias/plugin/enchantment/EnchantmentFeatherFallingSystem.java) | 1 |
| [EnchantmentWaterbreathingSystem.java](file:///C:/Users/Elias/Documents/Simple-Enchantments/src/main/java/org/herolias/plugin/enchantment/EnchantmentWaterbreathingSystem.java) | 1 |
| [EnchantmentNightVisionSystem.java](file:///C:/Users/Elias/Documents/Simple-Enchantments/src/main/java/org/herolias/plugin/enchantment/EnchantmentNightVisionSystem.java) | 1 |
| [EnchantmentFastSwimSystem.java](file:///C:/Users/Elias/Documents/Simple-Enchantments/src/main/java/org/herolias/plugin/enchantment/EnchantmentFastSwimSystem.java) | 1 |
| [EnchantmentStaminaSystem.java](file:///C:/Users/Elias/Documents/Simple-Enchantments/src/main/java/org/herolias/plugin/enchantment/EnchantmentStaminaSystem.java) | 1 |
| [EnchantmentAbsorptionSystem.java](file:///C:/Users/Elias/Documents/Simple-Enchantments/src/main/java/org/herolias/plugin/enchantment/EnchantmentAbsorptionSystem.java) | 1 |
| [EnchantmentReflectionSystem.java](file:///C:/Users/Elias/Documents/Simple-Enchantments/src/main/java/org/herolias/plugin/enchantment/EnchantmentReflectionSystem.java) | 1 |
| [EnchantmentEternalShotSystem.java](file:///C:/Users/Elias/Documents/Simple-Enchantments/src/main/java/org/herolias/plugin/enchantment/EnchantmentEternalShotSystem.java) | 1 |
| [EnchantmentAbilityStaminaSystem.java](file:///C:/Users/Elias/Documents/Simple-Enchantments/src/main/java/org/herolias/plugin/enchantment/EnchantmentAbilityStaminaSystem.java) | 1 |

---

### 2. Dead Code Removal from EnchantmentManager (~160 lines)

All changes in [EnchantmentManager.java](file:///C:/Users/Elias/Documents/Simple-Enchantments/src/main/java/org/herolias/plugin/enchantment/EnchantmentManager.java):

| Removed Method/Code | Lines | Reason |
|---|---|---|
| `isPickaxeItem()` | 3 | Deprecated, callers migrated to `ItemCategory.PICKAXE` |
| `isAxeItem()` | 3 | Deprecated, zero callers |
| `isShovelItem()` | 3 | Deprecated, zero callers |
| `updateItemVisuals()` | 10 | No-op method (returned item unchanged) |
| `calculateSwingSpeedMultiplier()` | 13 | Dead code — body was entirely commented out, zero callers |
| `getProtectedStringFloatMap()` | 8 | Unused reflection helper, zero callers |
| `getProtectedInt2FloatMap()` | 10 | Unused reflection helper, zero callers |
| 5 duplicate Javadoc blocks | ~20 | Copy-paste artifacts |
| 1 duplicate `@Nonnull` import | 1 | Import line 18 and 41 |

---

### 3. Protection Multiplier Unification (~40 lines saved)

**Problem:** [calculateProtectionMultiplier()](file:///C:/Users/Elias/Documents/Simple-Enchantments/src/main/java/org/herolias/plugin/enchantment/EnchantmentManager.java#877-885) and [calculateRangedProtectionMultiplier()](file:///C:/Users/Elias/Documents/Simple-Enchantments/src/main/java/org/herolias/plugin/enchantment/EnchantmentManager.java#886-894) were near-identical 40-line methods differing only in the [EnchantmentType](file:///C:/Users/Elias/Documents/Simple-Enchantments/src/main/java/org/herolias/plugin/command/EnchantCommand.java#159-174) used.

**Solution:** Created unified [calculateArmorProtectionMultiplier(ItemContainer, EnchantmentType)](file:///C:/Users/Elias/Documents/Simple-Enchantments/src/main/java/org/herolias/plugin/enchantment/EnchantmentManager.java#833-876). The old methods are kept as `@Deprecated` one-line delegates for backward compatibility.

---

### 4. Deprecated `isPickaxeItem` Migration

**Problem:** [EnchantmentFortuneSystem](file:///C:/Users/Elias/Documents/Simple-Enchantments/src/main/java/org/herolias/plugin/enchantment/EnchantmentFortuneSystem.java#27-101) and [EnchantmentSmeltingSystem](file:///C:/Users/Elias/Documents/Simple-Enchantments/src/main/java/org/herolias/plugin/enchantment/EnchantmentSmeltingSystem.java#38-188) called the deprecated `isPickaxeItem(String)` wrapper.

**Solution:** Replaced with direct `ItemCategory.PICKAXE` check:
```diff
-if (!enchantmentManager.isPickaxeItem(tool.getItemId())) {
+if (enchantmentManager.categorizeItem(tool) != ItemCategory.PICKAXE) {
```

---

### 5. EnchantmentData Cleanup

In [EnchantmentData.java](file:///C:/Users/Elias/Documents/Simple-Enchantments/src/main/java/org/herolias/plugin/enchantment/EnchantmentData.java):

- **Removed** [serialize()](file:///C:/Users/Elias/Documents/Simple-Enchantments/src/main/java/org/herolias/plugin/enchantment/EnchantmentData.java#230-272) (22 lines) — zero callers, user confirmed removal
- **Kept** [deserialize()](file:///C:/Users/Elias/Documents/Simple-Enchantments/src/main/java/org/herolias/plugin/enchantment/EnchantmentData.java#230-272) — still used by `GiveEnchantedCommand` and legacy metadata fallback

---

### 6. No-op `updateItemVisuals` Cleanup

Removed all 3 call sites of the no-op `updateItemVisuals()`:

| File | Change |
|---|---|
| [EnchantmentManager.java](file:///C:/Users/Elias/Documents/Simple-Enchantments/src/main/java/org/herolias/plugin/enchantment/EnchantmentManager.java) | Removed call in [applyEnchantmentToItem](file:///C:/Users/Elias/Documents/Simple-Enchantments/src/main/java/org/herolias/plugin/enchantment/EnchantmentManager.java#181-264) |
| [EnchantmentApiImpl.java](file:///C:/Users/Elias/Documents/Simple-Enchantments/src/main/java/org/herolias/plugin/api/EnchantmentApiImpl.java) | Changed `return manager.updateItemVisuals(newItem)` → `return newItem` |
| [RemoveEnchantmentInteraction.java](file:///C:/Users/Elias/Documents/Simple-Enchantments/src/main/java/org/herolias/plugin/ui/RemoveEnchantmentInteraction.java) | Removed `cleanedItem = manager.updateItemVisuals(cleanedItem)` |

---

### 7. Misc Cleanup

| File | What | Lines |
|---|---|---|
| [EnchantmentSmeltingSystem.java](file:///C:/Users/Elias/Documents/Simple-Enchantments/src/main/java/org/herolias/plugin/enchantment/EnchantmentSmeltingSystem.java) | Removed duplicate comment `// Apply Fortune extra rolls...` | 1 |

---

## Items Preserved (by design)

| Item | Reason |
|---|---|
| `EnchantmentData.deserialize()` | Actively used by `GiveEnchantedCommand` and [EnchantmentManager](file:///C:/Users/Elias/Documents/Simple-Enchantments/src/main/java/org/herolias/plugin/enchantment/EnchantmentManager.java#56-1606) legacy fallback |
| `EnchantmentType.getEffectMultiplier()` switch | User preference for readability over data-driven |

---

## Phase 2 Changes

### 8. EnchantmentProjectileSpeedSystem — Dead Code Removal (~285 lines removed)

Rewrote [EnchantmentProjectileSpeedSystem.java](file:///C:/Users/Elias/Documents/Simple-Enchantments/src/main/java/org/herolias/plugin/enchantment/EnchantmentProjectileSpeedSystem.java) from **392 → 107 lines**.

| Removed | Lines | Reason |
|---|---|---|
| Entire deferred velocity modification system | ~150 | All commented out — feature was disabled due to buggy behavior |
| `PendingVelocityMod` record | 5 | Dead — only used by deferred system |
| `pendingVelocityMods` ConcurrentHashMap | 3 | Dead — only used by deferred system |
| `PENDING_MAX_AGE_MS`, `MAX_RETRY_ATTEMPTS` | 2 | Dead — only used by deferred system |
| `scheduleDeferredApplication()` | ~20 | Dead — only call site was commented out |
| `processDeferredVelocityMod()` | ~40 | Dead — only called by scheduler |
| `tryApplyVelocityMultiplier()` | ~25 | Dead — only called by processDeferredVelocityMod |
| `applySimpleProjectileRange()` | ~15 | Dead — only called by tryApplyVelocityMultiplier |
| `applyStandardProjectileRange()` | ~15 | Dead — only called by tryApplyVelocityMultiplier |
| `scaleStandardNextTickVelocity()` | ~10 | Dead — only called by applyStandardProjectileRange |
| Unused imports | ~10 | No longer needed after dead code removal |

---

### 9. ProjectileEnchantmentData — Legacy Constructor Removal (~13 lines)

Removed 3 unused legacy constructors from [ProjectileEnchantmentData.java](file:///C:/Users/Elias/Documents/Simple-Enchantments/src/main/java/org/herolias/plugin/enchantment/ProjectileEnchantmentData.java):

| Removed Constructor | Reason |
|---|---|
| `ProjectileEnchantmentData(int, int, int)` | Zero callers — all use 6-arg or Builder |
| `ProjectileEnchantmentData(int, int, int, int)` | Zero callers |
| `ProjectileEnchantmentData(int, int, int, int, int)` | Zero callers |

---

### 10. EnchantmentManager — Range Multiplier & Deprecated Delegate Removal (~50 lines)

All changes in [EnchantmentManager.java](file:///C:/Users/Elias/Documents/Simple-Enchantments/src/main/java/org/herolias/plugin/enchantment/EnchantmentManager.java):

| Removed | Lines | Reason |
|---|---|---|
| `calculateProjectileRangeMultiplier(ItemStack)` | 16 | Dead — range bonus was permanently disabled (always returned 1.0) |
| `calculateProjectileRangeMultiplier(int)` | 12 | Dead — only called by removed ItemStack overload |
| `calculateProtectionMultiplier()` `@Deprecated` delegate | 8 | Callers migrated to `calculateArmorProtectionMultiplier()` directly |
| `calculateRangedProtectionMultiplier()` `@Deprecated` delegate | 8 | Callers migrated to `calculateArmorProtectionMultiplier()` directly |

Also **optimized `calculateDurabilityMultiplier()`** to use direct BSON key lookup via `getEnchantmentLevel()` instead of full `getEnchantmentsFromItem()` deserialization, consistent with `calculateDamageMultiplier()` and `calculateMiningSpeedMultiplier()`.

---

### 11. Protection/Ranged Protection Deduplication (~25 lines saved)

In [EnchantmentDamageSystem.java](file:///C:/Users/Elias/Documents/Simple-Enchantments/src/main/java/org/herolias/plugin/enchantment/EnchantmentDamageSystem.java):

**Problem:** The Protection block (lines 100–128) and Ranged Protection block (lines 130–158) were near-identical ~28-line blocks differing only in the damage type check and `EnchantmentType` used.

**Solution:** Extracted shared `applyArmorProtection(index, archetypeChunk, store, damage, protectionType)` method. Both blocks replaced with single-line calls. Migrated from removed `@Deprecated` delegates to `calculateArmorProtectionMultiplier()` directly.

---

### 12. EnchantmentType.conflictsWith() Simplification (~25 lines saved)

In [EnchantmentType.java](file:///C:/Users/Elias/Documents/Simple-Enchantments/src/main/java/org/herolias/plugin/enchantment/EnchantmentType.java):

**Before (40 lines):** Verbose bidirectional if-else chain with 8 comparisons for 4 conflict pairs.
**After (14 lines):** Static `Set<Set<EnchantmentType>> CONFLICT_PAIRS` with `contains(Set.of(this, other))`.

```java
private static final Set<Set<EnchantmentType>> CONFLICT_PAIRS = Set.of(
    Set.of(BURN, FREEZE),
    Set.of(PICK_PERFECT, FORTUNE),
    Set.of(PICK_PERFECT, SMELTING),
    Set.of(REFLECTION, ABSORPTION)
);

public boolean conflictsWith(EnchantmentType other) {
    if (this == other) return true;
    return CONFLICT_PAIRS.contains(Set.of(this, other));
}
```

Also removed duplicate Javadoc block for `getBonusDescription()` (4 lines).

---

### 13. Misc Cleanup

| File | What | Lines |
|---|---|---|
| [EnchantmentVisualsHelper.java](file:///C:/Users/Elias/Documents/Simple-Enchantments/src/main/java/org/herolias/plugin/enchantment/EnchantmentVisualsHelper.java) | Removed unused `hasEnchantments()` wrapper, inlined `manager.hasAnyEnabledEnchantment()` | 7 |
| [EnchantmentVisualsListener.java](file:///C:/Users/Elias/Documents/Simple-Enchantments/src/main/java/org/herolias/plugin/enchantment/EnchantmentVisualsListener.java) | Removed redundant `entityRef != null` check (already verified earlier) | 1 |
| [EnchantingConfig.java](file:///C:/Users/Elias/Documents/Simple-Enchantments/src/main/java/org/herolias/plugin/config/EnchantingConfig.java) | Removed commented-out `efficiencySwingSpeedMultiplier` and `burnDamagePerSecond` fields | 2 |
| [EnchantmentData.java](file:///C:/Users/Elias/Documents/Simple-Enchantments/src/main/java/org/herolias/plugin/enchantment/EnchantmentData.java) | Removed duplicate Javadoc comment | 1 |
