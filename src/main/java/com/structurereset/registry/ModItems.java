package com.structurereset.registry;

import com.structurereset.StructureResetMod;
import net.minecraft.world.item.Item;
import net.minecraftforge.eventbus.api.IEventBus;
import net.minecraftforge.registries.DeferredRegister;
import net.minecraftforge.registries.RegistryObject;

/**
 * Item registry of the "Structure Reset" addon.
 *
 * <p>The registry is a {@link DeferredRegister} so that every entry is created
 * at the right point of the mod lifecycle and is automatically carried over to
 * the {@code structurereset:<id>} resource paths used by the models, textures,
 * language files and recipes.</p>
 */
public final class ModItems {

    /** The item registry, filled by {@link #register(IEventBus)}. */
    public static final net.minecraftforge.registries.DeferredRegister<net.minecraft.world.item.Item> ITEMS = DeferredRegister.create(net.minecraftforge.registries.ForgeRegistries.ITEMS, StructureResetMod.MODID);

    /**
     * The single item of the addon: a core that resets the structure the player
     * is standing in.
     */
    public static final RegistryObject<Item> STRUCTURE_RESET_CORE = ITEMS.register("structure_reset_core", com.structurereset.item.StructureResetItem::new);

    private ModItems() {
    }

    /**
     * Hooks the item registry into the given mod event bus.
     *
     * @param modBus the Forge mod event bus of this addon
     */
    public static void register(IEventBus modBus) {
        ITEMS.register(modBus);
    }
}
