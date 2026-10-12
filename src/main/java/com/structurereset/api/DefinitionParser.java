package com.structurereset.api;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Turns the JSON body of a datapack reset file into a {@link ResetDefinition}.
 *
 * <p>Every failure is reported as a {@link DefinitionException} whose message
 * starts with the field path that caused it ({@code radius}, {@code trigger},
 * {@code actions[0].type}, ...). Unknown top level fields are preserved in
 * {@link ResetDefinition#extra()}.</p>
 */
public final class DefinitionParser {

    /** JSON key of the structure references. */
    public static final String FIELD_STRUCTURE = "structure";
    /** JSON key of the trigger radius. */
    public static final String FIELD_RADIUS = "radius";
    /** JSON key of the trigger. */
    public static final String FIELD_TRIGGER = "trigger";
    /** JSON key of the anchor blocks. */
    public static final String FIELD_ANCHOR_BLOCKS = "anchor_blocks";
    /** JSON key of the reset mode. */
    public static final String FIELD_MODE = "mode";
    /** JSON key of the cooldown. */
    public static final String FIELD_COOLDOWN_SECONDS = "cooldown_seconds";
    /** JSON key of the item consumption flag. */
    public static final String FIELD_CONSUME_ITEM = "consume_item";
    /** JSON key of the required item. */
    public static final String FIELD_REQUIRED_ITEM = "required_item";
    /** JSON key of the selection priority. */
    public static final String FIELD_PRIORITY = "priority";
    /** JSON key of the actions. */
    public static final String FIELD_ACTIONS = "actions";
    /** JSON key of the free form extra object. */
    public static final String FIELD_EXTRA = "extra";

    /** Every field this parser consumes; anything else lands in "extra". */
    public static final Set<String> KNOWN_FIELDS = Set.of(
            FIELD_STRUCTURE, FIELD_RADIUS, FIELD_TRIGGER, FIELD_ANCHOR_BLOCKS,
            FIELD_MODE, FIELD_COOLDOWN_SECONDS, FIELD_CONSUME_ITEM,
            FIELD_REQUIRED_ITEM, FIELD_PRIORITY, FIELD_ACTIONS, FIELD_EXTRA);

    private DefinitionParser() {
    }

    /**
     * Parses one definition.
     *
     * @param id the definition id, {@code namespace:path}
     * @param json the JSON body; null is treated as an empty object
     * @throws DefinitionException when a field is missing or has the wrong type
     */
    public static ResetDefinition parse(String id, JsonObject json) {
        JsonObject root = json == null ? new JsonObject() : json;
        ResetDefinition.Builder builder = ResetDefinition.builder().id(id);

        builder.structures(readStructures(root));

        if (root.has(FIELD_RADIUS)) {
            builder.radius(number(root, FIELD_RADIUS));
        }
        if (root.has(FIELD_TRIGGER)) {
            builder.trigger(readTrigger(string(root, FIELD_TRIGGER)));
        }
        if (root.has(FIELD_ANCHOR_BLOCKS)) {
            builder.anchorBlocks(stringArray(root, FIELD_ANCHOR_BLOCKS));
        }
        if (root.has(FIELD_MODE)) {
            builder.mode(readMode(string(root, FIELD_MODE)));
        }
        if (root.has(FIELD_COOLDOWN_SECONDS)) {
            builder.cooldownSeconds(integer(root, FIELD_COOLDOWN_SECONDS));
        }
        if (root.has(FIELD_CONSUME_ITEM)) {
            builder.consumeItem(bool(root, FIELD_CONSUME_ITEM));
        }
        if (root.has(FIELD_REQUIRED_ITEM)) {
            JsonElement required = root.get(FIELD_REQUIRED_ITEM);
            builder.requiredItem(isNull(required) ? null : string(root, FIELD_REQUIRED_ITEM));
        }
        if (root.has(FIELD_PRIORITY)) {
            builder.priority(integer(root, FIELD_PRIORITY));
        }

        builder.actions(readActions(root));
        builder.extra(readExtra(root));

        return builder.build();
    }

    // ---------------------------------------------------------- structure

    private static List<StructureRef> readStructures(JsonObject root) {
        JsonElement element = root.get(FIELD_STRUCTURE);
        if (isNull(element)) {
            throw DefinitionException.at(FIELD_STRUCTURE,
                    "is required and must be a string or an array of strings");
        }
        List<StructureRef> refs = new ArrayList<>();
        if (element.isJsonArray()) {
            JsonArray array = element.getAsJsonArray();
            if (array.size() == 0) {
                throw DefinitionException.at(FIELD_STRUCTURE,
                        "must contain at least one structure reference");
            }
            for (int index = 0; index < array.size(); index++) {
                refs.add(toStructureRef(array.get(index), FIELD_STRUCTURE + "[" + index + "]"));
            }
            return refs;
        }
        if (element.isJsonPrimitive() && element.getAsJsonPrimitive().isString()) {
            refs.add(toStructureRef(element, FIELD_STRUCTURE));
            return refs;
        }
        throw DefinitionException.at(FIELD_STRUCTURE,
                "must be a string or an array of strings");
    }

    private static StructureRef toStructureRef(JsonElement element, String path) {
        if (isNull(element) || !element.isJsonPrimitive()
                || !element.getAsJsonPrimitive().isString()) {
            throw DefinitionException.at(path, "must be a string structure reference");
        }
        String raw = element.getAsString();
        try {
            return StructureRef.parse(raw);
        } catch (IllegalArgumentException failure) {
            String detail = failure.getMessage();
            throw DefinitionException.at(path,
                    detail == null ? "invalid structure reference" : detail, failure);
        }
    }

    // ------------------------------------------------------------- actions

    private static List<ResetActionSpec> readActions(JsonObject root) {
        JsonElement element = root.get(FIELD_ACTIONS);
        if (isNull(element)) {
            throw DefinitionException.at(FIELD_ACTIONS,
                    "is required and must be a non-empty array of action objects");
        }
        if (!element.isJsonArray()) {
            throw DefinitionException.at(FIELD_ACTIONS,
                    "must be an array of action objects");
        }
        JsonArray array = element.getAsJsonArray();
        if (array.size() == 0) {
            throw DefinitionException.at(FIELD_ACTIONS, "must contain at least one action");
        }
        List<ResetActionSpec> specs = new ArrayList<>(array.size());
        for (int index = 0; index < array.size(); index++) {
            specs.add(readAction(array.get(index), FIELD_ACTIONS + "[" + index + "]"));
        }
        return specs;
    }

    private static ResetActionSpec readAction(JsonElement element, String path) {
        if (element == null || !element.isJsonObject()) {
            throw DefinitionException.at(path, "must be an object");
        }
        JsonObject object = element.getAsJsonObject();
        String typePath = path + ".type";
        JsonElement type = object.get("type");
        if (type == null || !type.isJsonPrimitive()
                || !type.getAsJsonPrimitive().isString()
                || type.getAsString().isBlank()) {
            throw DefinitionException.at(typePath, "must be a non-blank string");
        }
        JsonObject params = new JsonObject();
        if (object.has("params")) {
            JsonElement paramsElement = object.get("params");
            if (!isNull(paramsElement)) {
                if (!paramsElement.isJsonObject()) {
                    throw DefinitionException.at(path + ".params", "must be an object");
                }
                params = paramsElement.getAsJsonObject();
            }
        }
        return new ResetActionSpec(type.getAsString(), params);
    }

    // --------------------------------------------------------------- extra

    private static JsonObject readExtra(JsonObject root) {
        JsonObject extra = new JsonObject();
        boolean present = false;
        if (root.has(FIELD_EXTRA)) {
            JsonElement declared = root.get(FIELD_EXTRA);
            if (!isNull(declared)) {
                if (!declared.isJsonObject()) {
                    throw DefinitionException.at(FIELD_EXTRA, "must be an object");
                }
                for (Map.Entry<String, JsonElement> entry : declared.getAsJsonObject().entrySet()) {
                    extra.add(entry.getKey(), entry.getValue());
                }
                present = true;
            }
        }
        boolean unknown = false;
        for (Map.Entry<String, JsonElement> entry : root.entrySet()) {
            if (!KNOWN_FIELDS.contains(entry.getKey())) {
                extra.add(entry.getKey(), entry.getValue());
                unknown = true;
            }
        }
        return (present || unknown) ? extra : null;
    }

    // ---------------------------------------------------------- primitives

    private static boolean isNull(JsonElement element) {
        return element == null || element.isJsonNull();
    }

    private static JsonPrimitive primitive(JsonObject root, String path) {
        JsonElement element = root.get(path);
        if (element == null || !element.isJsonPrimitive()) {
            throw DefinitionException.at(path,
                    "must be a primitive value but was a " + kind(element));
        }
        return element.getAsJsonPrimitive();
    }

    private static String kind(JsonElement element) {
        if (element == null || element.isJsonNull()) {
            return "null";
        }
        if (element.isJsonArray()) {
            return "array";
        }
        if (element.isJsonObject()) {
            return "object";
        }
        return "primitive";
    }

    private static String string(JsonObject root, String path) {
        JsonPrimitive value = primitive(root, path);
        if (!value.isString()) {
            throw DefinitionException.at(path, "must be a string");
        }
        return value.getAsString();
    }

    private static double number(JsonObject root, String path) {
        JsonPrimitive value = primitive(root, path);
        if (!value.isNumber()) {
            throw DefinitionException.at(path, "must be a number");
        }
        return value.getAsDouble();
    }

    private static int integer(JsonObject root, String path) {
        JsonPrimitive value = primitive(root, path);
        if (!value.isNumber()) {
            throw DefinitionException.at(path, "must be an integer");
        }
        double asDouble = value.getAsDouble();
        if (!Double.isFinite(asDouble) || asDouble != Math.rint(asDouble)) {
            throw DefinitionException.at(path,
                    "must be a whole number but was " + value.getAsString());
        }
        if (asDouble < Integer.MIN_VALUE || asDouble > Integer.MAX_VALUE) {
            throw DefinitionException.at(path,
                    "is out of range for an integer: " + value.getAsString());
        }
        return (int) asDouble;
    }

    private static boolean bool(JsonObject root, String path) {
        JsonPrimitive value = primitive(root, path);
        if (!value.isBoolean()) {
            throw DefinitionException.at(path, "must be a boolean");
        }
        return value.getAsBoolean();
    }

    private static List<String> stringArray(JsonObject root, String path) {
        JsonElement element = root.get(path);
        if (isNull(element) || !element.isJsonArray()) {
            throw DefinitionException.at(path, "must be an array of strings");
        }
        JsonArray array = element.getAsJsonArray();
        List<String> values = new ArrayList<>(array.size());
        for (int index = 0; index < array.size(); index++) {
            JsonElement item = array.get(index);
            if (isNull(item) || !item.isJsonPrimitive() || !item.getAsJsonPrimitive().isString()) {
                throw DefinitionException.at(path + "[" + index + "]", "must be a string");
            }
            values.add(item.getAsString());
        }
        return values;
    }

    // -------------------------------------------------------------- enums

    private static ResetTrigger readTrigger(String value) {
        switch (value) {
            case "in_structure":
                return ResetTrigger.IN_STRUCTURE;
            case "anchor_block":
                return ResetTrigger.ANCHOR_BLOCK;
            case "either":
                return ResetTrigger.EITHER;
            default:
                throw DefinitionException.at(FIELD_TRIGGER,
                        "must be one of in_structure, anchor_block, either but was '" + value + "'");
        }
    }

    private static ResetMode readMode(String value) {
        switch (value) {
            case "global":
                return ResetMode.GLOBAL;
            case "per_player":
                return ResetMode.PER_PLAYER;
            default:
                throw DefinitionException.at(FIELD_MODE,
                        "must be one of global, per_player but was '" + value + "'");
        }
    }
}
