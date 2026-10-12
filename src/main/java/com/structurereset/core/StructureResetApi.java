package com.structurereset.core;

import com.structurereset.api.ItemSettings;
import com.structurereset.api.ResetDefinition;
import com.structurereset.core.event.ResetPostEvent;
import com.structurereset.core.event.ResetPreEvent;
import com.structurereset.impl.ResetEngine;
import com.structurereset.impl.StructureResetRegistry;
import java.util.List;
import java.util.function.Consumer;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.Item;
import net.minecraft.world.phys.AABB;
import net.minecraftforge.common.MinecraftForge;

/**
 * The public entry point of the addon: datapack independent registration of
 * definitions and action types, plus the reset trigger itself.
 *
 * <p>{@link #triggerReset} posts a cancelable {@link ResetPreEvent} before the
 * engine runs and a {@link ResetPostEvent} afterwards; both events are also
 * handed to every listener registered with {@link #addPreListener} and
 * {@link #addPostListener}.</p>
 */
public final class StructureResetApi {

    private StructureResetApi() {
    }

    // ------------------------------------------------------- registration

    /** Registers (or replaces) an action type under its namespaced id. */
    public static void registerActionType(ResourceLocation id, ResetActionType actionType) {
        StructureResetRegistry.registerActionType(id, actionType);
    }

    /** Registers (or replaces) a definition, keyed by its id. */
    public static void registerDefinition(ResetDefinition definition) {
        StructureResetRegistry.registerDefinition(definition);
    }

    /** Removes a definition; true when one was registered under {@code definitionId}. */
    public static boolean removeDefinition(String definitionId) {
        return StructureResetRegistry.removeDefinition(definitionId);
    }

    /** Every known definition, sorted by id. */
    public static List<ResetDefinition> definitions() {
        return StructureResetRegistry.definitions();
    }

    /** The definition registered under {@code definitionId}, or null. */
    public static ResetDefinition definition(String definitionId) {
        return StructureResetRegistry.definition(definitionId);
    }

    /** Adds a listener that sees every reset before it runs. */
    public static void addPreListener(Consumer<ResetPreEvent> listener) {
        StructureResetRegistry.addPreListener(listener);
    }

    /** Adds a listener that sees every finished reset. */
    public static void addPostListener(Consumer<ResetPostEvent> listener) {
        StructureResetRegistry.addPostListener(listener);
    }

    // -------------------------------------------------------- item settings

    /**
     * Overrides the behaviour settings of a reset core item at runtime.
     *
     * <p>The values live in {@link ItemSettingsRegistry}; a {@code null}
     * settings value restores the shipped defaults.</p>
     *
     * @param item the registered item whose behaviour changes
     * @param settings the new settings, or {@code null} to restore the defaults
     */
    public static void setItemSettings(Item item, ItemSettings settings) {
        ItemSettingsRegistry.set(item, settings);
    }

    /**
     * The behaviour settings in force for an item.
     *
     * @param item the item to look up, may be {@code null}
     * @return the item's settings, or the shipped defaults for an unknown item
     */
    public static ItemSettings itemSettings(Item item) {
        return ItemSettingsRegistry.get(item);
    }

    // ------------------------------------------------------------ queries

    /** The definition that applies at {@code pos}, or null. */
    public static ResetDefinition findDefinitionAt(ServerLevel level, BlockPos pos, ServerPlayer player) {
        return ResetEngine.findDefinition(level, pos, player);
    }

    /**
     * The definition that applies at {@code pos} together with the reason a
     * selection was refused: {@code (definition, null)} when one was selected,
     * {@code (null, ResetEngine.MESSAGE_AMBIGUOUS)} when an anchor block of some
     * definition is nearby and the selection stayed ambiguous, and
     * {@code (null, null)} otherwise.
     */
    public static ResetEngine.DefinitionLookup lookupDefinitionAt(ServerLevel level, BlockPos pos,
                                                                  ServerPlayer player) {
        return ResetEngine.lookup(level, pos, player);
    }

    /** True when {@link #triggerReset} would actually run something right now. */
    public static boolean canReset(ServerLevel level, BlockPos pos, ServerPlayer player,
                                   String definitionId, boolean force) {
        return canReset(level, pos, player, definitionId, force, 0);
    }

    /**
     * As {@link #canReset(ServerLevel, BlockPos, ServerPlayer, String, boolean)}
     * with a cooldown override for an item triggered reset.
     *
     * @param cooldownSecondsOverride the cooldown in seconds the triggering item
     *        wants instead of the definition's own one; {@code 0} keeps the
     *        definition's cooldown
     */
    public static boolean canReset(ServerLevel level, BlockPos pos, ServerPlayer player,
                                   String definitionId, boolean force,
                                   int cooldownSecondsOverride) {
        return ResetEngine.canReset(level, pos, player, definitionId, force,
                cooldownSecondsOverride);
    }

    // ------------------------------------------------------------ trigger

    /**
     * Triggers one reset.
     *
     * @param level the server level, required
     * @param pos the block the reset is triggered at, required
     * @param player the acting player, may be null
     * @param definitionId an explicit definition id, or null to select one at {@code pos}
     * @param force true to ignore the cooldown and the global enable switch
     */
    public static ResetResult triggerReset(ServerLevel level, BlockPos pos, ServerPlayer player,
                                           String definitionId, boolean force) {
        return triggerReset(level, pos, player, definitionId, force, ResetResult.MESSAGE_OK);
    }

    /** As {@link #triggerReset(ServerLevel, BlockPos, ServerPlayer, String, boolean)} with a custom message key. */
    public static ResetResult triggerReset(ServerLevel level, BlockPos pos, ServerPlayer player,
                                           String definitionId, boolean force, String messageKey) {
        return triggerReset(level, pos, player, definitionId, force, messageKey, 0);
    }

    /**
     * As
     * {@link #triggerReset(ServerLevel, BlockPos, ServerPlayer, String, boolean, String)}
     * with a cooldown override for an item triggered reset.
     *
     * @param cooldownSecondsOverride the cooldown in seconds the triggering item
     *        wants instead of the definition's own one; {@code 0} keeps the
     *        definition's cooldown
     */
    public static ResetResult triggerReset(ServerLevel level, BlockPos pos, ServerPlayer player,
                                           String definitionId, boolean force, String messageKey,
                                           int cooldownSecondsOverride) {
        if (level == null || pos == null) {
            return ResetResult.fail("structurereset.message.invalid_context",
                    "a reset needs a level and an origin");
        }
        // The engine resolves an explicit id in the registry first and among the
        // learned anchors second, and falls back to a selection at the position
        // when the id is unknown, so a learned anchor is replayable by id too.
        ResetDefinition definition = ResetEngine.resolveDefinition(level, pos, player, definitionId);
        if (definition == null) {
            return ResetResult.fail("structurereset.message.no_definition",
                    "no reset definition applies at " + pos.getX() + "," + pos.getY() + "," + pos.getZ());
        }
        AABB box = ResetEngine.boxFor(definition, pos);
        ResetPreEvent preEvent = new ResetPreEvent(level, pos, box, player, definition,
                messageKey == null || messageKey.isBlank() ? ResetResult.MESSAGE_OK : messageKey);
        for (Consumer<ResetPreEvent> listener : StructureResetRegistry.preListeners()) {
            listener.accept(preEvent);
        }
        MinecraftForge.EVENT_BUS.post(preEvent);
        if (preEvent.isCanceled()) {
            return ResetResult.fail(preEvent.getMessage(), "the reset was cancelled by a listener");
        }
        ResetResult result = ResetEngine.triggerReset(level, pos, player, definition, force,
                preEvent.getMessage(), cooldownSecondsOverride);
        ResetPostEvent postEvent = new ResetPostEvent(level, pos, box, player, definition, result,
                preEvent.getMessage());
        MinecraftForge.EVENT_BUS.post(postEvent);
        for (Consumer<ResetPostEvent> listener : StructureResetRegistry.postListeners()) {
            listener.accept(postEvent);
        }
        return result;
    }

    /** The ids of every registered action type, sorted. */
    public static List<ResourceLocation> registeredActionTypeIds() {
        return StructureResetRegistry.actionTypeIds();
    }
}
