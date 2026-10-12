package com.structurereset.api;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;
import java.util.ArrayList;
import java.util.List;

/**
 * One learned anchor: where a structure was taught, which dimension it lives in,
 * which structure ids it was entered as, which boss belongs to it and which loot
 * containers were observed.
 *
 * <p>The record is the durable memory of the teaching feature and therefore
 * survives a server restart: it is written to and read from JSON.  It is
 * deliberately Minecraft free, so the whole selection logic can be tested
 * offline.</p>
 *
 * @param id the learning id, always {@code namespace:path}
 * @param dimension the dimension id this anchor was learned in
 * @param structureIds the structure ids that were reported, never null
 * @param bossEntity the entity id of the boss to respawn
 * @param x the anchor x coordinate
 * @param y the anchor y coordinate
 * @param z the anchor z coordinate
 * @param radius how far around the anchor this learning applies
 * @param containers the loot containers seen on the anchor, never null
 * @param learnedAtGameTime the game time the anchor was learned at
 * @param learnedBy the name of the player who taught it, never null
 */
public record LearnedAnchor(String id, String dimension, List<String> structureIds, String bossEntity,
                            int x, int y, int z, double radius, List<LearnedContainer> containers,
                            long learnedAtGameTime, String learnedBy) {

    /**
     * Validates the ids and the radius and normalises the collections.
     *
     * @throws IllegalArgumentException when the id, the dimension or the boss
     *         entity is not {@code namespace:path}, or when the radius is not
     *         positive (zero and NaN included)
     */
    public LearnedAnchor {
        id = requireNamespaced(id, "id");
        dimension = requireNamespaced(dimension, "dimension");
        bossEntity = requireNamespaced(bossEntity, "boss_entity");
        if (!(radius > 0.0D)) {
            throw new IllegalArgumentException("radius must be a positive number but was " + radius);
        }
        structureIds = structureIds == null ? List.<String>of() : List.copyOf(structureIds);
        containers = containers == null ? List.<LearnedContainer>of() : List.copyOf(containers);
        learnedBy = learnedBy == null ? "" : learnedBy;
    }

    /** The JSON form written into the learn file. */
    public JsonObject toJson() {
        JsonObject json = new JsonObject();
        json.addProperty("id", id);
        json.addProperty("dimension", dimension);
        JsonArray structures = new JsonArray();
        for (String structureId : structureIds) {
            structures.add(structureId);
        }
        json.add("structure_ids", structures);
        json.addProperty("boss_entity", bossEntity);
        json.addProperty("x", x);
        json.addProperty("y", y);
        json.addProperty("z", z);
        json.addProperty("radius", radius);
        JsonArray containerArray = new JsonArray();
        for (LearnedContainer container : containers) {
            containerArray.add(container.toJson());
        }
        json.add("containers", containerArray);
        json.addProperty("learned_at_game_time", learnedAtGameTime);
        json.addProperty("learned_by", learnedBy);
        return json;
    }

    /**
     * Reads an anchor back.
     *
     * <p>{@code structure_ids}, {@code containers} and {@code learned_by} are
     * optional and fall back to an empty list or an empty string, just like the
     * coordinates fall back to {@code 0}.  The id, the dimension, the boss
     * entity and the radius are mandatory: an anchor without them could never be
     * replayed.</p>
     *
     * @param json the anchor object
     * @return the parsed anchor
     * @throws IllegalArgumentException when the json is null or when a
     *         mandatory field is missing or malformed
     */
    public static LearnedAnchor fromJson(JsonObject json) {
        if (json == null) {
            throw new IllegalArgumentException("learned anchor json must not be null");
        }
        return new LearnedAnchor(stringValue(json, "id"), stringValue(json, "dimension"),
                stringList(json, "structure_ids"), stringValue(json, "boss_entity"),
                intValue(json, "x"), intValue(json, "y"), intValue(json, "z"),
                doubleValue(json, "radius"), containerList(json, "containers"),
                longValue(json, "learned_at_game_time"), stringValue(json, "learned_by"));
    }

    // ------------------------------------------------------------- helpers

    private static String requireNamespaced(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(field + " must not be null or blank but was "
                    + quote(value));
        }
        int colon = value.indexOf(':');
        if (colon <= 0 || colon != value.lastIndexOf(':') || colon == value.length() - 1) {
            throw new IllegalArgumentException(field + " must be 'namespace:path' but was "
                    + quote(value));
        }
        for (int index = 0; index < value.length(); index++) {
            if (Character.isWhitespace(value.charAt(index))) {
                throw new IllegalArgumentException(field + " must not contain whitespace but was "
                        + quote(value));
            }
        }
        return value;
    }

    private static String quote(String value) {
        return value == null ? "null" : "'" + value + "'";
    }

    private static JsonElement element(JsonObject json, String key) {
        return json.get(key);
    }

    private static String stringValue(JsonObject json, String key) {
        JsonElement element = element(json, key);
        if (element == null || !element.isJsonPrimitive()) {
            return null;
        }
        JsonPrimitive primitive = element.getAsJsonPrimitive();
        return primitive.isString() ? primitive.getAsString() : null;
    }

    private static int intValue(JsonObject json, String key) {
        return (int) longValue(json, key);
    }

    private static long longValue(JsonObject json, String key) {
        JsonElement element = element(json, key);
        if (element == null || !element.isJsonPrimitive()) {
            return 0L;
        }
        JsonPrimitive primitive = element.getAsJsonPrimitive();
        if (primitive.isNumber()) {
            return primitive.getAsLong();
        }
        if (primitive.isString()) {
            try {
                return Long.parseLong(primitive.getAsString().trim());
            } catch (NumberFormatException ignored) {
                return 0L;
            }
        }
        return 0L;
    }

    private static double doubleValue(JsonObject json, String key) {
        JsonElement element = element(json, key);
        if (element == null || !element.isJsonPrimitive()) {
            return 0.0D;
        }
        JsonPrimitive primitive = element.getAsJsonPrimitive();
        if (primitive.isNumber()) {
            return primitive.getAsDouble();
        }
        if (primitive.isString()) {
            try {
                return Double.parseDouble(primitive.getAsString().trim());
            } catch (NumberFormatException ignored) {
                return 0.0D;
            }
        }
        return 0.0D;
    }

    private static List<String> stringList(JsonObject json, String key) {
        JsonElement element = element(json, key);
        if (element == null || !element.isJsonArray()) {
            return List.of();
        }
        List<String> values = new ArrayList<>();
        for (JsonElement entry : element.getAsJsonArray()) {
            if (entry != null && entry.isJsonPrimitive()
                    && entry.getAsJsonPrimitive().isString()) {
                values.add(entry.getAsString());
            }
        }
        return List.copyOf(values);
    }

    private static List<LearnedContainer> containerList(JsonObject json, String key) {
        JsonElement element = element(json, key);
        if (element == null || !element.isJsonArray()) {
            return List.of();
        }
        List<LearnedContainer> values = new ArrayList<>();
        for (JsonElement entry : element.getAsJsonArray()) {
            if (entry != null && entry.isJsonObject()) {
                values.add(LearnedContainer.fromJson(entry.getAsJsonObject()));
            }
        }
        return List.copyOf(values);
    }
}
