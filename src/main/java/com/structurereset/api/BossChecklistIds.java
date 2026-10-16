package com.structurereset.api;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonParser;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.Set;

/**
 * The pure parser for the boss id list the optional Boss Checklist integration
 * reads.
 *
 * <p>Boss Checklist ships no entity tag at all; its server side data pack holds
 * one JSON array at {@code data/boss_checklist/server_bosses_ids.json} whose
 * entries are single key objects of the form {@code {"id": "minecraft:wither"}}.
 * This class turns that text into the set of entity ids it names, using nothing
 * but gson, so the offline JUnit run can cover it without a Minecraft
 * classpath.</p>
 *
 * <p>It never throws: a file that is absent ({@code null} or blank), that is not
 * valid JSON, or whose top level is not an array yields an <em>unusable</em>
 * empty result, and every entry that is not an object carrying a non blank
 * string {@code id} is counted in {@link #skipped()} instead of aborting the
 * parse. The caller decides what an unusable result means - for the bridge it is
 * "no hint, warn once", never a gate.</p>
 */
public final class BossChecklistIds {

    /** Namespace of the data pack file inside the Boss Checklist jar. */
    public static final String NAMESPACE = "boss_checklist";
    /** Path of that file inside the {@code data/<namespace>/} directory. */
    public static final String RESOURCE_PATH = "server_bosses_ids.json";
    /** JSON key of the entity id of one entry. */
    public static final String FIELD_ID = "id";

    private static final BossChecklistIds EMPTY = new BossChecklistIds(Set.of(), false, 0);

    private final Set<String> ids;
    private final boolean usable;
    private final int skipped;

    private BossChecklistIds(Set<String> ids, boolean usable, int skipped) {
        this.ids = Collections.unmodifiableSet(new LinkedHashSet<>(ids));
        this.usable = usable;
        this.skipped = skipped;
    }

    /** An unusable, empty result: no list was read at all. */
    public static BossChecklistIds empty() {
        return EMPTY;
    }

    /** A usable result holding exactly {@code ids}; blank ids are dropped. */
    public static BossChecklistIds of(Collection<String> ids) {
        if (ids == null || ids.isEmpty()) {
            return new BossChecklistIds(Set.of(), true, 0);
        }
        Set<String> kept = new LinkedHashSet<>();
        int skipped = 0;
        for (String id : ids) {
            if (id == null || id.isBlank()) {
                skipped++;
                continue;
            }
            kept.add(id.trim());
        }
        return new BossChecklistIds(kept, true, skipped);
    }

    /**
     * Parses the JSON array Boss Checklist ships.
     *
     * @param json the file text; null and blank are treated as "no file"
     * @return the parsed list, never null; {@link #usable()} is false when the
     *         text could not be read as an array
     */
    public static BossChecklistIds parse(String json) {
        if (json == null || json.isBlank()) {
            return EMPTY;
        }
        JsonElement root;
        try {
            root = JsonParser.parseString(json);
        } catch (RuntimeException malformed) {
            // Includes JsonSyntaxException: a malformed file is "no list", not an error.
            return EMPTY;
        }
        if (root == null || !root.isJsonArray()) {
            return EMPTY;
        }
        JsonArray array = root.getAsJsonArray();
        Set<String> ids = new LinkedHashSet<>();
        int skipped = 0;
        for (JsonElement element : array) {
            String id = idOf(element);
            if (id == null) {
                skipped++;
                continue;
            }
            ids.add(id);
        }
        return new BossChecklistIds(ids, true, skipped);
    }

    /** The trimmed entity id of one array entry, or null when it has none. */
    private static String idOf(JsonElement element) {
        if (element == null || !element.isJsonObject()) {
            return null;
        }
        JsonElement id = element.getAsJsonObject().get(FIELD_ID);
        if (id == null || !id.isJsonPrimitive() || !id.getAsJsonPrimitive().isString()) {
            return null;
        }
        String text = id.getAsString();
        return text == null || text.isBlank() ? null : text.trim();
    }

    /**
     * True when the text was a JSON array and every entry was inspected, so the
     * list may be trusted as the whole content of the file. An empty array is
     * usable: it simply names no boss.
     */
    public boolean usable() {
        return usable;
    }

    /** The entity ids the file names, in file order, never null. */
    public Set<String> ids() {
        return ids;
    }

    /** How many entries were not an object with a non blank string id. */
    public int skipped() {
        return skipped;
    }

    /** How many entity ids were read. */
    public int size() {
        return ids.size();
    }

    /** True when the file named no entity id at all. */
    public boolean isEmpty() {
        return ids.isEmpty();
    }

    /** True when the file names exactly {@code entityId}; null never matches. */
    public boolean contains(String entityId) {
        return entityId != null && !entityId.isBlank() && ids.contains(entityId.trim());
    }
}
