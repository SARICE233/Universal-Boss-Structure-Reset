package com.structurereset.api;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;

/**
 * One loot container (chest, barrel, ...) that was observed while a structure
 * was learned.
 *
 * <p>Coordinates are plain {@code int} block coordinates, so the record stays
 * completely free of Minecraft types and can be serialised offline by the
 * teaching feature.  The stored loot table seed is only meaningful when
 * {@link #hasSeed()} is true: a container that was opened with a fresh random
 * seed carries no seed worth replaying.</p>
 *
 * @param x the container x coordinate
 * @param y the container y coordinate
 * @param z the container z coordinate
 * @param lootTable the loot table id, always {@code namespace:path}
 * @param lootTableSeed the seed the container rolled with
 * @param hasSeed whether {@code lootTableSeed} is a real, learned seed
 */
public record LearnedContainer(int x, int y, int z, String lootTable,
                               long lootTableSeed, boolean hasSeed) {

    /** Validates the loot table id; every other component is a primitive. */
    public LearnedContainer {
        lootTable = requireLootTable(lootTable);
    }

    /**
     * A container whose seed was actually learned.
     *
     * @param x the container x coordinate
     * @param y the container y coordinate
     * @param z the container z coordinate
     * @param lootTable the loot table id, always {@code namespace:path}
     * @param lootTableSeed the learned seed
     * @return the container, with {@code hasSeed} set to true
     * @throws IllegalArgumentException when the loot table is malformed
     */
    public static LearnedContainer of(int x, int y, int z, String lootTable, long lootTableSeed) {
        return new LearnedContainer(x, y, z, lootTable, lootTableSeed, true);
    }

    /** The JSON form written into the learn file. */
    public JsonObject toJson() {
        JsonObject json = new JsonObject();
        json.addProperty("x", x);
        json.addProperty("y", y);
        json.addProperty("z", z);
        json.addProperty("loot_table", lootTable);
        json.addProperty("loot_table_seed", lootTableSeed);
        json.addProperty("has_seed", hasSeed);
        return json;
    }

    /**
     * Reads a container back.
     *
     * <p>Absent numeric fields fall back to {@code 0} and an absent
     * {@code has_seed} falls back to {@code false}, so a hand written file can
     * omit everything it does not care about.  The loot table cannot be
     * omitted: there would be nothing left to refill.</p>
     *
     * @param json the container object
     * @return the parsed container
     * @throws IllegalArgumentException when the json is null, when the loot
     *         table is missing or when it is not {@code namespace:path}
     */
    public static LearnedContainer fromJson(JsonObject json) {
        if (json == null) {
            throw new IllegalArgumentException("learned container json must not be null");
        }
        return new LearnedContainer(intValue(json, "x"), intValue(json, "y"), intValue(json, "z"),
                stringValue(json, "loot_table"), longValue(json, "loot_table_seed"),
                booleanValue(json, "has_seed"));
    }

    // ------------------------------------------------------------- helpers

    private static String requireLootTable(String value) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("loot_table must not be null or blank but was "
                    + quote(value));
        }
        int colon = value.indexOf(':');
        if (colon <= 0 || colon != value.lastIndexOf(':') || colon == value.length() - 1) {
            throw new IllegalArgumentException("loot_table must be 'namespace:path' but was "
                    + quote(value));
        }
        for (int index = 0; index < value.length(); index++) {
            if (Character.isWhitespace(value.charAt(index))) {
                throw new IllegalArgumentException("loot_table must not contain whitespace but was "
                        + quote(value));
            }
        }
        return value;
    }

    private static String quote(String value) {
        return value == null ? "null" : "'" + value + "'";
    }

    private static String stringValue(JsonObject json, String key) {
        JsonElement element = json.get(key);
        if (element == null || !element.isJsonPrimitive()) {
            return null;
        }
        JsonPrimitive primitive = element.getAsJsonPrimitive();
        return primitive.isString() ? primitive.getAsString() : null;
    }

    private static int intValue(JsonObject json, String key) {
        long value = longValue(json, key);
        return (int) value;
    }

    private static long longValue(JsonObject json, String key) {
        JsonElement element = json.get(key);
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

    private static boolean booleanValue(JsonObject json, String key) {
        JsonElement element = json.get(key);
        if (element == null || !element.isJsonPrimitive()) {
            return false;
        }
        JsonPrimitive primitive = element.getAsJsonPrimitive();
        if (primitive.isBoolean()) {
            return primitive.getAsBoolean();
        }
        return primitive.isString() && Boolean.parseBoolean(primitive.getAsString().trim());
    }
}
