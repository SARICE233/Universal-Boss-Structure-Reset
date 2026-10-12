package com.structurereset.impl;

import com.structurereset.api.ResetDefinition;
import com.structurereset.core.ResetActionType;
import com.structurereset.core.event.ResetPostEvent;
import com.structurereset.core.event.ResetPreEvent;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;
import net.minecraft.resources.ResourceLocation;

/**
 * The thread safe store behind
 * {@link com.structurereset.core.StructureResetApi}.
 *
 * <p>It holds four independent things: the definitions keyed by definition id,
 * the action types keyed by their namespaced id, the ids of the definitions that
 * came from data packs (so a reload can replace exactly those) and the
 * registered pre/post listeners.</p>
 *
 * <p>Everything is safe to touch from a server thread, a reload thread or a
 * script binding at the same time.</p>
 */
public final class StructureResetRegistry {

    private static final Map<String, ResetDefinition> DEFINITIONS = new ConcurrentHashMap<>();
    private static final Map<ResourceLocation, ResetActionType> ACTION_TYPES = new ConcurrentHashMap<>();
    private static final Set<String> DATAPACK_DEFINITIONS = ConcurrentHashMap.newKeySet();
    private static final List<Consumer<ResetPreEvent>> PRE_LISTENERS = new CopyOnWriteArrayList<>();
    private static final List<Consumer<ResetPostEvent>> POST_LISTENERS = new CopyOnWriteArrayList<>();

    private StructureResetRegistry() {
    }

    // -------------------------------------------------------- definitions

    /** Registers (or replaces) one definition. Null input is ignored. */
    public static void registerDefinition(ResetDefinition definition) {
        if (definition == null || definition.id() == null) {
            return;
        }
        DEFINITIONS.put(definition.id(), definition);
    }

    /** Removes one definition; true when something was removed. */
    public static boolean removeDefinition(String definitionId) {
        if (definitionId == null) {
            return false;
        }
        DATAPACK_DEFINITIONS.remove(definitionId);
        return DEFINITIONS.remove(definitionId) != null;
    }

    /** The definition with this id, or null. */
    public static ResetDefinition definition(String definitionId) {
        return definitionId == null ? null : DEFINITIONS.get(definitionId);
    }

    /** Every definition, sorted by id so selection is deterministic. */
    public static List<ResetDefinition> definitions() {
        List<ResetDefinition> all = new ArrayList<>(DEFINITIONS.values());
        all.sort(Comparator.comparing(ResetDefinition::id));
        return List.copyOf(all);
    }

    /** True when no definition at all is registered. */
    public static boolean isEmpty() {
        return DEFINITIONS.isEmpty();
    }

    /**
     * Replaces every definition that came from data packs with {@code parsed}.
     *
     * <p>Definitions registered through
     * {@link com.structurereset.core.StructureResetApi#registerDefinition(ResetDefinition)}
     * are left untouched, so a programmatic definition survives a
     * {@code /reload}.</p>
     */
    public static void replaceDatapackDefinitions(Collection<ResetDefinition> parsed) {
        for (String previous : DATAPACK_DEFINITIONS) {
            DEFINITIONS.remove(previous);
        }
        DATAPACK_DEFINITIONS.clear();
        if (parsed == null) {
            return;
        }
        for (ResetDefinition definition : parsed) {
            if (definition == null || definition.id() == null) {
                continue;
            }
            DEFINITIONS.put(definition.id(), definition);
            DATAPACK_DEFINITIONS.add(definition.id());
        }
    }

    /** How many definitions currently come from data packs. */
    public static int datapackDefinitionCount() {
        return DATAPACK_DEFINITIONS.size();
    }

    /**
     * True when {@code definitionId} names a definition that really came from a
     * data pack, i.e. one of the bundled / data pack driven mapping entries.
     *
     * <p>A definition that was registered programmatically is not in this set,
     * and neither is the definition a learned anchor synthesises on the fly:
     * both are therefore reported as "not listed in the bundled mapping table",
     * which is exactly what the two-click confirmation prompt tells the player.</p>
     */
    public static boolean isDatapackDefinition(String definitionId) {
        return definitionId != null && DATAPACK_DEFINITIONS.contains(definitionId);
    }

    /** Drops every definition, programmatic ones included. */
    public static void clearDefinitions() {
        DEFINITIONS.clear();
        DATAPACK_DEFINITIONS.clear();
    }

    // ------------------------------------------------------- action types

    /** Registers (or replaces) one action type. Null input is ignored. */
    public static void registerActionType(ResourceLocation id, ResetActionType actionType) {
        if (id == null || actionType == null) {
            return;
        }
        ACTION_TYPES.put(id, actionType);
    }

    /** The action type with this id, or null. */
    public static ResetActionType actionType(ResourceLocation id) {
        return id == null ? null : ACTION_TYPES.get(id);
    }

    /** Every registered action type id, sorted, as resource locations. */
    public static List<ResourceLocation> actionTypeIds() {
        List<ResourceLocation> ids = new ArrayList<>(ACTION_TYPES.keySet());
        ids.sort(Comparator.comparing(ResourceLocation::toString));
        return List.copyOf(ids);
    }

    /** Every registered action type id, sorted, as strings. */
    public static List<String> registeredActionTypeIds() {
        List<String> ids = new ArrayList<>(ACTION_TYPES.size());
        for (ResourceLocation id : ACTION_TYPES.keySet()) {
            ids.add(id.toString());
        }
        Collections.sort(ids);
        return List.copyOf(ids);
    }

    /** How many action types are registered. */
    public static int actionTypeCount() {
        return ACTION_TYPES.size();
    }

    /** Drops every action type. */
    public static void clearActionTypes() {
        ACTION_TYPES.clear();
    }

    // ----------------------------------------------------------- listeners

    /** Adds a listener that sees every reset before it runs. */
    public static void addPreListener(Consumer<ResetPreEvent> listener) {
        if (listener != null) {
            PRE_LISTENERS.add(listener);
        }
    }

    /** Adds a listener that sees every finished reset. */
    public static void addPostListener(Consumer<ResetPostEvent> listener) {
        if (listener != null) {
            POST_LISTENERS.add(listener);
        }
    }

    /** Removes a previously added pre listener; true when it was registered. */
    public static boolean removePreListener(Consumer<ResetPreEvent> listener) {
        return PRE_LISTENERS.remove(listener);
    }

    /** Removes a previously added post listener; true when it was registered. */
    public static boolean removePostListener(Consumer<ResetPostEvent> listener) {
        return POST_LISTENERS.remove(listener);
    }

    /** A stable snapshot of the pre listeners. */
    public static List<Consumer<ResetPreEvent>> preListeners() {
        return List.copyOf(PRE_LISTENERS);
    }

    /** A stable snapshot of the post listeners. */
    public static List<Consumer<ResetPostEvent>> postListeners() {
        return List.copyOf(POST_LISTENERS);
    }

    /** Drops every registered listener. */
    public static void clearListeners() {
        PRE_LISTENERS.clear();
        POST_LISTENERS.clear();
    }
}
