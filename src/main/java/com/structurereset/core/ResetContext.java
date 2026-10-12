package com.structurereset.core;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;
import com.structurereset.api.Placeholders;
import com.structurereset.api.ResetDefinition;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.phys.AABB;

/**
 * Everything one reset action needs in order to run.
 *
 * <p>This is the Minecraft facing half of the public API: the pure logic layer
 * stays in {@code com.structurereset.api} and never references Minecraft, while
 * this package and {@code com.structurereset.impl} may.</p>
 *
 * <p>One context is built per action, so {@link #params()} is exactly the
 * {@code params} object of the {@link com.structurereset.api.ResetActionSpec}
 * that is currently being executed. {@link #vars()} exposes the placeholder
 * variables that {@link Placeholders} resolves inside {@code run_command} and
 * {@code message} parameters.</p>
 */
public final class ResetContext {

    /** Placeholder key of the origin x coordinate. */
    public static final String VAR_X = "x";
    /** Placeholder key of the origin y coordinate. */
    public static final String VAR_Y = "y";
    /** Placeholder key of the origin z coordinate. */
    public static final String VAR_Z = "z";
    /** Placeholder key of the acting player name (or {@code server}). */
    public static final String VAR_PLAYER = "player";
    /** Placeholder key of the primary structure reference. */
    public static final String VAR_STRUCTURE = "structure";
    /** Placeholder key of the definition id. */
    public static final String VAR_DEFINITION = "definition";
    /** Placeholder key of the dimension id. */
    public static final String VAR_DIMENSION = "dimension";
    /** Placeholder keys of the six box bounds. */
    public static final List<String> VAR_BOX = List.of(
            "box_min_x", "box_min_y", "box_min_z", "box_max_x", "box_max_y", "box_max_z");

    private final ServerLevel level;
    private final BlockPos origin;
    private final AABB box;
    private final ServerPlayer actor;
    private final ResetDefinition definition;
    private final JsonObject params;
    private final Map<String, String> vars;

    /**
     * Creates one action context.
     *
     * @param level the server level the reset happens in, required
     * @param origin the origin of the reset, required
     * @param box the affected area; null falls back to the single origin block
     * @param actor the acting player, may be null for a server side reset
     * @param definition the definition being executed, required
     * @param params the parameters of the running action, null becomes an empty object
     */
    public ResetContext(ServerLevel level, BlockPos origin, AABB box, ServerPlayer actor,
                        ResetDefinition definition, JsonObject params) {
        if (level == null) {
            throw new IllegalArgumentException("reset context needs a level");
        }
        if (origin == null) {
            throw new IllegalArgumentException("reset context needs an origin");
        }
        if (definition == null) {
            throw new IllegalArgumentException("reset context needs a definition");
        }
        this.level = level;
        this.origin = origin;
        this.box = box == null ? new AABB(origin) : box;
        this.actor = actor;
        this.definition = definition;
        this.params = params == null ? new JsonObject() : params;
        this.vars = buildVars();
    }

    // ---------------------------------------------------------- accessors

    /** The server level the reset happens in. */
    public ServerLevel level() {
        return level;
    }

    /** The block the reset was triggered at. */
    public BlockPos origin() {
        return origin;
    }

    /** The affected area of this reset. */
    public AABB box() {
        return box;
    }

    /** The acting player, or null for a purely server side reset. */
    public ServerPlayer actor() {
        return actor;
    }

    /** True when a player is part of this reset. */
    public boolean hasActor() {
        return actor != null;
    }

    /** The definition being executed. */
    public ResetDefinition definition() {
        return definition;
    }

    /** The read only parameters of the running action, never null. */
    public JsonObject params() {
        return params;
    }

    /** JavaBean alias of {@link #level()}. */
    public ServerLevel getLevel() {
        return level;
    }

    /** JavaBean alias of {@link #origin()}. */
    public BlockPos getOrigin() {
        return origin;
    }

    /** JavaBean alias of {@link #box()}. */
    public AABB getBox() {
        return box;
    }

    /** JavaBean alias of {@link #actor()}. */
    public ServerPlayer getActor() {
        return actor;
    }

    /** JavaBean alias of {@link #definition()}. */
    public ResetDefinition getDefinition() {
        return definition;
    }

    /** JavaBean alias of {@link #params()}. */
    public JsonObject getParams() {
        return params;
    }

    /** The placeholder variables of this reset, never null. */
    public Map<String, String> vars() {
        return vars;
    }

    /** The value of one placeholder variable, or null when it is unknown. */
    public String var(String key) {
        return key == null ? null : vars.get(key);
    }

    /** Resolves {@code ${key}} placeholders with the variables of this reset. */
    public String placeholders(String template) {
        return Placeholders.resolve(template, vars);
    }

    // ------------------------------------------------------ parameters

    /** A string parameter, or {@code fallback} when it is absent or not a string. */
    public String paramString(String key, String fallback) {
        JsonPrimitive primitive = primitive(key);
        if (primitive == null || !primitive.isString()) {
            return fallback;
        }
        return primitive.getAsString();
    }

    /** An integer parameter, or {@code fallback} when it is absent or not a number. */
    public int paramInt(String key, int fallback) {
        JsonPrimitive primitive = primitive(key);
        if (primitive == null) {
            return fallback;
        }
        if (primitive.isNumber()) {
            return (int) primitive.getAsDouble();
        }
        if (primitive.isString()) {
            try {
                return Integer.parseInt(primitive.getAsString().trim());
            } catch (NumberFormatException ignored) {
                return fallback;
            }
        }
        return fallback;
    }

    /** A floating point parameter, or {@code fallback} when it is absent or not a number. */
    public double paramDouble(String key, double fallback) {
        JsonPrimitive primitive = primitive(key);
        if (primitive == null) {
            return fallback;
        }
        if (primitive.isNumber()) {
            return primitive.getAsDouble();
        }
        if (primitive.isString()) {
            try {
                return Double.parseDouble(primitive.getAsString().trim());
            } catch (NumberFormatException ignored) {
                return fallback;
            }
        }
        return fallback;
    }

    /** A boolean parameter, or {@code fallback} when it is absent or not a boolean. */
    public boolean paramBoolean(String key, boolean fallback) {
        JsonPrimitive primitive = primitive(key);
        if (primitive == null) {
            return fallback;
        }
        if (primitive.isBoolean()) {
            return primitive.getAsBoolean();
        }
        if (primitive.isString()) {
            String value = primitive.getAsString().trim();
            if ("true".equalsIgnoreCase(value)) {
                return true;
            }
            if ("false".equalsIgnoreCase(value)) {
                return false;
            }
        }
        return fallback;
    }

    /** An array parameter, or null when it is absent or not an array. */
    public JsonArray paramArray(String key) {
        JsonElement element = params.get(key);
        return element != null && element.isJsonArray() ? element.getAsJsonArray() : null;
    }

    /** Every string entry of an array parameter; empty when it is absent. */
    public List<String> paramStringList(String key) {
        JsonArray array = paramArray(key);
        if (array == null) {
            return List.of();
        }
        List<String> values = new ArrayList<>(array.size());
        for (JsonElement element : array) {
            if (element != null && element.isJsonPrimitive()
                    && element.getAsJsonPrimitive().isString()) {
                values.add(element.getAsString());
            }
        }
        return List.copyOf(values);
    }

    private JsonPrimitive primitive(String key) {
        JsonElement element = key == null ? null : params.get(key);
        if (element == null || !element.isJsonPrimitive()) {
            return null;
        }
        return element.getAsJsonPrimitive();
    }

    // ------------------------------------------------------ variables

    private Map<String, String> buildVars() {
        Map<String, String> values = new LinkedHashMap<>();
        values.put(VAR_X, Integer.toString(origin.getX()));
        values.put(VAR_Y, Integer.toString(origin.getY()));
        values.put(VAR_Z, Integer.toString(origin.getZ()));
        values.put(VAR_PLAYER, actor == null ? "server" : actor.getGameProfile().getName());
        values.put(VAR_STRUCTURE, definition.structures().isEmpty()
                ? "" : definition.structures().get(0).raw());
        values.put(VAR_DEFINITION, definition.id());
        values.put(VAR_DIMENSION, level.dimension().location().toString());
        values.put("box_min_x", block(box.minX));
        values.put("box_min_y", block(box.minY));
        values.put("box_min_z", block(box.minZ));
        values.put("box_max_x", block(box.maxX));
        values.put("box_max_y", block(box.maxY));
        values.put("box_max_z", block(box.maxZ));
        return Collections.unmodifiableMap(values);
    }

    private static String block(double value) {
        return Long.toString((long) Math.floor(value));
    }

    @Override
    public String toString() {
        return "ResetContext[definition=" + definition.id()
                + ", origin=" + origin.getX() + "," + origin.getY() + "," + origin.getZ()
                + ", player=" + (actor == null ? "-" : actor.getGameProfile().getName())
                + ", params=" + params + "]";
    }
}
