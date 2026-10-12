package com.structurereset.core;

import com.structurereset.api.ItemSettings;
import com.structurereset.config.StructureResetConfig;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import net.minecraft.world.item.Item;

/**
 * Runtime registry of the per item behaviour settings of the reset core.
 *
 * <p>The registry is a plain {@link ConcurrentHashMap} keyed by
 * {@link Item}, exactly like {@link ItemAppearanceApi} next to it: a data pack
 * or a KubeJS script can point any registered item at its own
 * {@link ItemSettings} at runtime, without a new item registration and without
 * a restart.</p>
 *
 * <p>An item without an explicit entry - and a {@code null} item - always gets
 * {@link #defaults()}, which is built from the configuration:
 * {@code maxUses} 5 and {@code unbreakable} false and {@code teachConsumes}
 * false are the shipped constants of {@link ItemSettings}, while
 * {@code cooldownSeconds} comes from
 * {@link StructureResetConfig#defaultCooldownSeconds()} because that accessor
 * is the Minecraft facing source of the shipped default.</p>
 *
 * <p>This class lives in {@code com.structurereset.core} on purpose: it is
 * Minecraft facing, while {@code com.structurereset.api} only carries the
 * Minecraft free logic layer that the offline tool chain compiles.</p>
 */
public final class ItemSettingsRegistry {

    /** item -> the settings that override its defaults. */
    private static final Map<Item, ItemSettings> SETTINGS = new ConcurrentHashMap<>();

    private ItemSettingsRegistry() {
    }

    /**
     * The shipped defaults: {@code maxUses} 5, not unbreakable, teaching free
     * and the configured default cooldown.
     *
     * @return the default settings, never null
     */
    public static ItemSettings defaults() {
        return ItemSettings.defaults(StructureResetConfig.defaultCooldownSeconds());
    }

    /**
     * Overrides the settings of an item.
     *
     * <p>A {@code null} settings value clears the entry again, so the item
     * falls back to {@link #defaults()}.</p>
     *
     * @param item the registered item whose behaviour changes
     * @param settings the new settings, or {@code null} to clear them
     */
    public static void set(Item item, ItemSettings settings) {
        if (item == null) {
            return;
        }
        if (settings == null) {
            SETTINGS.remove(item);
            return;
        }
        SETTINGS.put(item, settings);
    }

    /** Removes the override of an item, restoring {@link #defaults()}. */
    public static void clear(Item item) {
        if (item != null) {
            SETTINGS.remove(item);
        }
    }

    /**
     * The settings in force for an item.
     *
     * @param item the item to look up, may be {@code null}
     * @return the item's override, or {@link #defaults()} for an unknown or
     *         {@code null} item
     */
    public static ItemSettings get(Item item) {
        if (item == null) {
            return defaults();
        }
        ItemSettings settings = SETTINGS.get(item);
        return settings == null ? defaults() : settings;
    }

    /**
     * An immutable, ordered snapshot of every explicit override.
     *
     * @return item to settings, in insertion order
     */
    public static Map<Item, ItemSettings> all() {
        return Collections.unmodifiableMap(new LinkedHashMap<>(SETTINGS));
    }
}
