package com.structurereset.impl;

import com.mojang.logging.LogUtils;
import org.slf4j.Logger;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonParseException;
import com.structurereset.api.LearnedAnchor;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.TreeMap;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.saveddata.SavedData;

/**
 * The durable memory of the learning/teaching feature: every anchor a player
 * taught by sneaking and using the core item on a mob.
 *
 * <p>The anchors are stored as one JSON array inside a {@link CompoundTag},
 * exactly like the learn file of the pure API layer, and the data itself is
 * attached to the overworld through the ordinary {@code SavedData} mechanism.
 * Every anchor carries its own dimension id, so one world level store is enough
 * for anchors from every dimension, and the records survive a {@code /reload}
 * as well as a full server restart.</p>
 *
 * <p>A single malformed entry never fails the load: it is dropped and the
 * remaining anchors are kept.</p>
 */
public final class LearnedAnchorData extends SavedData {

    private static final Logger LOGGER = LogUtils.getLogger();

    /** Name the data is stored under inside the overworld data directory. */
    public static final String DATA_NAME = "structurereset_learned_anchors";
    /** Key of the nested tag that carries the anchors as a JSON array string. */
    public static final String TAG_ANCHORS = "anchors";

    private static final Gson GSON = new GsonBuilder().create();

    private final TreeMap<String, LearnedAnchor> anchors = new TreeMap<>();

    /** The anchors of {@code server}, creating the store when it does not exist yet. */
    public static LearnedAnchorData get(MinecraftServer server) {
        if (server == null) {
            throw new IllegalArgumentException("learned anchors need a server");
        }
        return server.overworld().getDataStorage().computeIfAbsent(
                LearnedAnchorData::load, LearnedAnchorData::new, DATA_NAME);
    }

    /** The anchors of {@code level}; anchors always live with the overworld. */
    public static LearnedAnchorData get(ServerLevel level) {
        if (level == null) {
            throw new IllegalArgumentException("learned anchors need a level");
        }
        MinecraftServer server = level.getServer();
        if (server != null) {
            return get(server);
        }
        return level.getDataStorage().computeIfAbsent(
                LearnedAnchorData::load, LearnedAnchorData::new, DATA_NAME);
    }

    /** Reads the stored anchors out of a saved tag; never throws. */
    public static LearnedAnchorData load(CompoundTag tag) {
        LearnedAnchorData data = new LearnedAnchorData();
        if (tag == null) {
            return data;
        }
        String raw = tag.getString(TAG_ANCHORS);
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
        for (JsonElement entry : parsed.getAsJsonArray()) {
            if (entry == null || !entry.isJsonObject()) {
                continue;
            }
            try {
                LearnedAnchor anchor = LearnedAnchor.fromJson(entry.getAsJsonObject());
                data.anchors.put(anchor.id(), anchor);
            } catch (RuntimeException ignored) {
                // One malformed anchor is dropped instead of failing the whole load.
            LOGGER.warn("structure reset: skipped one malformed entry", ignored);
            }
        }
        return data;
    }

    @Override
    public CompoundTag save(CompoundTag tag) {
        JsonArray array = new JsonArray();
        for (LearnedAnchor anchor : anchors.values()) {
            array.add(anchor.toJson());
        }
        tag.putString(TAG_ANCHORS, GSON.toJson(array));
        return tag;
    }

    /** Every anchor, as an immutable list sorted by id. */
    public List<LearnedAnchor> all() {
        List<LearnedAnchor> copy = new ArrayList<>(anchors.values());
        copy.sort(Comparator.comparing(LearnedAnchor::id));
        return List.copyOf(copy);
    }

    /** The anchor with {@code id}, or null when it is unknown. */
    public LearnedAnchor byId(String id) {
        return id == null ? null : anchors.get(id);
    }

    /** Stores {@code anchor}, replacing an earlier record with the same id. */
    public void add(LearnedAnchor anchor) {
        if (anchor == null) {
            return;
        }
        anchors.put(anchor.id(), anchor);
        setDirty(true);
    }

    /** Forgets one anchor; true when something was removed. */
    public boolean remove(String id) {
        if (id == null) {
            return false;
        }
        boolean removed = anchors.remove(id) != null;
        if (removed) {
            setDirty(true);
        }
        return removed;
    }

    /** Forgets every anchor. */
    public void clear() {
        if (!anchors.isEmpty()) {
            anchors.clear();
            setDirty(true);
        }
    }

    /** How many anchors are remembered. */
    public int size() {
        return anchors.size();
    }

    /**
     * A free learning id, {@code learned:1}, {@code learned:2}, and so on,
     * skipping every id the given anchors already use.
     */
    public static String nextId(Collection<LearnedAnchor> existing) {
        Set<String> used = new HashSet<>();
        if (existing != null) {
            for (LearnedAnchor anchor : existing) {
                if (anchor != null && anchor.id() != null) {
                    used.add(anchor.id());
                }
            }
        }
        int candidate = 1;
        while (used.contains("learned:" + candidate)) {
            candidate++;
        }
        return "learned:" + candidate;
    }
}
