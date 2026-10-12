package com.structurereset.impl;

import com.mojang.logging.LogUtils;
import org.slf4j.Logger;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;
import com.structurereset.api.LearnedContainer;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.saveddata.SavedData;

/**
 * The durable loot index of the addon: the loot tables a structure's containers
 * still carried the first time a player captured them.
 *
 * <p>The point of this store is timing. A container keeps its
 * {@code LootTable} tag only until somebody opens it; from then on the block
 * entity no longer knows which table it used, and no mod can recover it (the
 * structure template is not reachable from the placed piece). Capturing the
 * tables <em>before</em> the chests are opened - the player presses the core's
 * left mouse button on a block inside the structure - is therefore the only
 * reliable way to make a later refill possible at all.</p>
 *
 * <p>Entries are plain JSON inside one {@link CompoundTag}, attached to the
 * overworld like {@link LearnedAnchorData}, so the index survives
 * {@code /reload} and a server restart and is shared by every player.</p>
 */
public final class LootIndexData extends SavedData {

    private static final Logger LOGGER = LogUtils.getLogger();

    /** Name the data is stored under inside the overworld data directory. */
    public static final String DATA_NAME = "structurereset_loot_index";
    /** Key of the nested tag that carries the entries as a JSON array string. */
    public static final String TAG_ENTRIES = "entries";

    private static final Gson GSON = new GsonBuilder().create();

    private final List<Entry> entries = new ArrayList<>();

    /** One captured structure: where it is, which structures it covers and what was seen. */
    public record Entry(String dimension, List<String> structureIds, int x, int y, int z,
                        double radius, List<LearnedContainer> containers, long capturedAt) {

        /** Serialises this entry; field names are stable, the file is a save format. */
        public JsonObject toJson() {
            JsonObject json = new JsonObject();
            json.addProperty("dimension", dimension);
            JsonArray ids = new JsonArray();
            for (String id : structureIds) {
                ids.add(id);
            }
            json.add("structure_ids", ids);
            json.addProperty("x", x);
            json.addProperty("y", y);
            json.addProperty("z", z);
            json.addProperty("radius", radius);
            json.addProperty("captured_at", capturedAt);
            JsonArray containersJson = new JsonArray();
            for (LearnedContainer container : containers) {
                containersJson.add(container.toJson());
            }
            json.add("containers", containersJson);
            return json;
        }

        /** Reads an entry back; throws on a malformed body so the caller can drop it. */
        public static Entry fromJson(JsonObject json) {
            String dimension = json.get("dimension").getAsString();
            List<String> ids = new ArrayList<>();
            if (json.has("structure_ids")) {
                for (JsonElement element : json.getAsJsonArray("structure_ids")) {
                    ids.add(element.getAsString());
                }
            }
            List<LearnedContainer> containers = new ArrayList<>();
            if (json.has("containers")) {
                for (JsonElement element : json.getAsJsonArray("containers")) {
                    containers.add(LearnedContainer.fromJson(element.getAsJsonObject()));
                }
            }
            return new Entry(dimension, List.copyOf(ids), json.get("x").getAsInt(),
                    json.get("y").getAsInt(), json.get("z").getAsInt(),
                    json.get("radius").getAsDouble(), List.copyOf(containers),
                    json.has("captured_at") ? json.get("captured_at").getAsLong() : 0L);
        }

        /** True when two entries describe the same place. */
        boolean samePlace(Entry other) {
            if (other == null || !dimension.equals(other.dimension)) {
                return false;
            }
            for (String id : structureIds) {
                if (other.structureIds.contains(id)) {
                    return true;
                }
            }
            double dx = x - other.x;
            double dy = y - other.y;
            double dz = z - other.z;
            return Math.sqrt(dx * dx + dy * dy + dz * dz) < 8.0D;
        }

        /** True when this entry covers a lookup for the given structures or position. */
        boolean covers(String lookupDimension, Collection<String> lookupIds, int px, int py, int pz) {
            if (!dimension.equals(lookupDimension)) {
                return false;
            }
            for (String id : structureIds) {
                if (lookupIds != null && lookupIds.contains(id)) {
                    return true;
                }
            }
            double dx = px - x;
            double dy = py - y;
            double dz = pz - z;
            return Math.sqrt(dx * dx + dy * dy + dz * dz) <= radius;
        }
    }

    /** The index of {@code server}, creating the store when it does not exist yet. */
    public static LootIndexData get(MinecraftServer server) {
        return server.overworld().getDataStorage()
                .computeIfAbsent(LootIndexData::load, LootIndexData::new, DATA_NAME);
    }

    /** The index of {@code level}; the index always lives with the overworld. */
    public static LootIndexData get(ServerLevel level) {
        MinecraftServer server = level.getServer();
        if (server != null) {
            return get(server);
        }
        return level.getDataStorage().computeIfAbsent(LootIndexData::load, LootIndexData::new, DATA_NAME);
    }

    /** Reads the stored entries; a malformed entry is dropped, never fatal. */
    public static LootIndexData load(CompoundTag tag) {
        LootIndexData data = new LootIndexData();
        if (tag == null) {
            return data;
        }
        String raw = tag.getString(TAG_ENTRIES);
        if (raw == null || raw.isBlank()) {
            return data;
        }
        JsonElement parsed;
        try {
            parsed = GSON.fromJson(raw, JsonElement.class);
        } catch (JsonParseException ignored) {
            return data;
        }
        if (parsed == null || !parsed.isJsonArray()) {
            return data;
        }
        for (JsonElement element : parsed.getAsJsonArray()) {
            if (element == null || !element.isJsonObject()) {
                continue;
            }
            try {
                data.entries.add(Entry.fromJson(element.getAsJsonObject()));
            } catch (RuntimeException ignored) {
                // one malformed entry never fails the whole load
            LOGGER.warn("structure reset: skipped one malformed entry", ignored);
            }
        }
        return data;
    }

    @Override
    public CompoundTag save(CompoundTag tag) {
        JsonArray array = new JsonArray();
        for (Entry entry : entries) {
            array.add(entry.toJson());
        }
        tag.putString(TAG_ENTRIES, GSON.toJson(array));
        return tag;
    }

    /** Every captured entry, as an immutable list. */
    public List<Entry> all() {
        return List.copyOf(entries);
    }

    /**
     * Stores a capture, merging it into an existing entry for the same place.
     *
     * <p>Merging keeps the first recorded loot table of every container position
     * and only adds positions that are not known yet, so capturing twice never
     * replaces an earlier (still valid) table with a later, empty one.</p>
     */
    public void put(Entry entry) {
        if (entry == null) {
            return;
        }
        for (int index = 0; index < entries.size(); index++) {
            Entry existing = entries.get(index);
            if (!existing.samePlace(entry)) {
                continue;
            }
            List<LearnedContainer> merged = new ArrayList<>(existing.containers());
            for (LearnedContainer candidate : entry.containers()) {
                boolean known = false;
                for (LearnedContainer current : merged) {
                    if (current.x() == candidate.x() && current.y() == candidate.y()
                            && current.z() == candidate.z()) {
                        known = true;
                        break;
                    }
                }
                if (!known) {
                    merged.add(candidate);
                }
            }
            entries.set(index, new Entry(existing.dimension(), existing.structureIds(),
                    existing.x(), existing.y(), existing.z(), existing.radius(),
                    List.copyOf(merged), Math.max(existing.capturedAt(), entry.capturedAt())));
            setDirty(true);
            return;
        }
        entries.add(entry);
        setDirty(true);
    }

    /**
     * The loot table a container at {@code (x, y, z)} used, when it was captured.
     *
     * @param dimension the dimension id to look in
     * @param structureIds the structures at that position, may be empty
     * @param x the container x coordinate
     * @param y the container y coordinate
     * @param z the container z coordinate
     * @return the recorded container, or empty when nothing was captured for it
     */
    public Optional<LearnedContainer> containerAt(String dimension, Collection<String> structureIds,
                                                  int x, int y, int z) {
        for (Entry entry : entries) {
            if (!entry.covers(dimension, structureIds, x, y, z)) {
                continue;
            }
            for (LearnedContainer container : entry.containers()) {
                if (container.x() == x && container.y() == y && container.z() == z) {
                    return Optional.of(container);
                }
            }
        }
        return Optional.empty();
    }

    /** How many captures are remembered. */
    public int size() {
        return entries.size();
    }
}
