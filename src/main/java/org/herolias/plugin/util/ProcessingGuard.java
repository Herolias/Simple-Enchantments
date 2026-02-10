package org.herolias.plugin.util;

import com.hypixel.hytale.logger.HytaleLogger;

/**
 * Utility class to prevent infinite recursion in inventory change handlers.
 * 
 * Many enchantment systems modify inventories in response to inventory change events.
 * Without guard logic, these modifications would trigger new events, causing infinite loops.
 * 
 * Usage:
 * <pre>
 * private final ProcessingGuard guard = new ProcessingGuard();
 * 
 * public void onInventoryChange(LivingEntityInventoryChangeEvent event) {
 *     if (guard.isProcessing()) return;
 *     
 *     guard.runGuarded(() -> {
 *         // Modify inventory safely here
 *         container.replaceItemStackInSlot(slot, before, after);
 *     });
 * }
 * </pre>
 */
public class ProcessingGuard {
    
    private static final HytaleLogger LOGGER = HytaleLogger.forEnclosingClass();
    
    private final ThreadLocal<Boolean> processing = ThreadLocal.withInitial(() -> false);
    
    /**
     * Checks if this guard is currently active.
     * @return true if we're inside a guarded block, false otherwise
     */
    public boolean isProcessing() {
        return processing.get();
    }
    
    /**
     * Executes an action with the guard active.
     * If the guard is already active, the action is skipped.
     * 
     * @param action The action to execute
     */
    public void runGuarded(Runnable action) {
        if (processing.get()) {
            return;
        }
        try {
            processing.set(true);
            action.run();
        } catch (Exception e) {
            LOGGER.atWarning().log("Error in guarded action: " + e.getMessage());
        } finally {
            processing.set(false);
        }
    }
    
    /**
     * Executes an action with the guard active, returning a result.
     * If the guard is already active, returns the default value.
     * 
     * @param action The action to execute
     * @param defaultValue Value to return if guard is active or action fails
     * @return The result of the action, or defaultValue
     */
    public <T> T runGuardedWithResult(java.util.function.Supplier<T> action, T defaultValue) {
        if (processing.get()) {
            return defaultValue;
        }
        try {
            processing.set(true);
            return action.get();
        } catch (Exception e) {
            LOGGER.atWarning().log("Error in guarded action: " + e.getMessage());
            return defaultValue;
        } finally {
            processing.set(false);
        }
    }
}
