package com.structurereset.core;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.Item;

/**
 * Runtime appearance overlay: lets a mod or an integration pack point any
 * registered item at an arbitrary item model.
 *
 * <p>The override is expressed as an ordinary model id (for example
 * {@code structurereset:item/structure_reset_core_skin1}); the client baking
 * pass resolves it and swaps the baked model of the item, so an override never
 * needs a new item registration.</p>
 *
 * <p>This class lives in {@code com.structurereset.core} on purpose: it is
 * Minecraft facing, while {@code com.structurereset.api} only carries the
 * Minecraft free logic layer that the offline tool chain compiles.</p>
 */
public final class ItemAppearanceApi {

    /** item -> the model that should be baked in its place. */
    private static final Map<Item, ResourceLocation> MODEL_OVERRIDES = new ConcurrentHashMap<>();

    private ItemAppearanceApi() {
    }

    /**
     * Points an item at another item model.
     *
     * @param item  the registered item whose appearance changes
     * @param model the replacement model id, or {@code null} to clear the override
     */
    public static void setModelOverride(Item item, ResourceLocation model) {
        if (item == null) {
            return;
        }
        if (model == null) {
            MODEL_OVERRIDES.remove(item);
            return;
        }
        MODEL_OVERRIDES.put(item, model);
    }

    /**
     * Removes the model override of an item, restoring its own model.
     *
     * @param item the item to restore
     */
    public static void clearModelOverride(Item item) {
        if (item != null) {
            MODEL_OVERRIDES.remove(item);
        }
    }

    /**
     * The model currently overriding an item's own model.
     *
     * @param item the item to look up
     * @return the override, or {@link Optional#empty()} when the item is not overridden
     */
    public static Optional<ResourceLocation> modelOverride(Item item) {
        if (item == null) {
            return Optional.empty();
        }
        return Optional.ofNullable(MODEL_OVERRIDES.get(item));
    }

    /**
     * An immutable, ordered snapshot of every current override.
     *
     * @return item to replacement model, in insertion order
     */
    public static Map<Item, ResourceLocation> modelOverrides() {
        return Collections.unmodifiableMap(new LinkedHashMap<>(MODEL_OVERRIDES));
    }
}
