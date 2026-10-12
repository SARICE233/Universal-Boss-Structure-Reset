package com.structurereset.impl;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonParseException;
import com.mojang.logging.LogUtils;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.saveddata.SavedData;
import org.slf4j.Logger;

/**
 * The bosses this addon has watched discard themselves.
 *
 * <p>Some bosses cannot be brought back by recreating the entity. The Aether's
 * Sun Spirit is the example that started this: it belongs to a room that is
 * sealed before the fight and unlocked when it dies, so a boss spawned into an
 * already unlocked room removes itself on its first tick, at full health. Its
 * state lives inside that mod's own room tracker, which no generic addon can
 * reach, so the honest answer is to stop pretending: the first time it happens the
 * entity type is remembered, and from then on the reset restores the loot and says
 * plainly that this boss cannot be recreated.</p>
 *
 * <p>Entries are a plain JSON array inside one {@link CompoundTag}, attached to the
 * overworld, so the list survives restarts and is shared by every player. An
 * operator can clear it after the other mod fixes its boss.</p>
 */
public final class UnrestorableBosses extends SavedData {

    private static final Logger LOGGER = LogUtils.getLogger();

    /** Name the data is stored under inside the overworld data directory. */
    public static final String DATA_NAME = "structurereset_unrestorable";
    /** Key of the tag that carries the entity ids as a JSON array string. */
    public static final String TAG_BOSSES = "bosses";

    private static final Gson GSON = new GsonBuilder().create();

    private final Set<String> bosses = new LinkedHashSet<>();

    /** The store of the server of {@code level}. */
    public static UnrestorableBosses get(ServerLevel level) {
        if (level == null) {
            return new UnrestorableBosses();
        }
        MinecraftServer server = level.getServer();
        if (server != null) {
            return get(server);
        }
        return level.getDataStorage().computeIfAbsent(
                UnrestorableBosses::load, UnrestorableBosses::new, DATA_NAME);
    }

    /** The store of one server. */
    public static UnrestorableBosses get(MinecraftServer server) {
        return server.overworld().getDataStorage().computeIfAbsent(
                UnrestorableBosses::load, UnrestorableBosses::new, DATA_NAME);
    }

    /** Reads the stored ids; never throws. */
    public static UnrestorableBosses load(CompoundTag tag) {
        UnrestorableBosses data = new UnrestorableBosses();
        if (tag == null) {
            return data;
        }
        String raw = tag.getString(TAG_BOSSES);
        if (raw == null || raw.isBlank()) {
            return data;
        }
        try {
            JsonArray array = GSON.fromJson(raw, JsonArray.class);
            if (array != null) {
                for (JsonElement element : array) {
                    if (element != null && element.isJsonPrimitive()) {
                        data.bosses.add(element.getAsString());
                    }
                }
            }
        } catch (JsonParseException ignored) {
            // A list that no longer parses is dropped instead of failing the load;
            // it is a cache of observations and rebuilding it costs one reset.
            LOGGER.warn("[structurereset] the unrestorable boss list was not readable and was dropped");
        }
        return data;
    }

    @Override
    public CompoundTag save(CompoundTag tag) {
        JsonArray array = new JsonArray();
        for (String id : bosses) {
            array.add(id);
        }
        tag.putString(TAG_BOSSES, GSON.toJson(array));
        return tag;
    }

    /** True when this boss type has already been watched discarding itself. */
    public boolean contains(String bossId) {
        return bossId != null && !bossId.isBlank() && bosses.contains(bossId);
    }

    /**
     * Remembers a boss type.
     *
     * @param bossId the entity id, ignored when null or blank
     * @return true when the list actually changed
     */
    public boolean remember(String bossId) {
        if (bossId == null || bossId.isBlank() || !bosses.add(bossId)) {
            return false;
        }
        setDirty(true);
        return true;
    }

    /** Forgets one boss type; returns true when it was listed. */
    public boolean forget(String bossId) {
        if (bossId == null || !bosses.remove(bossId)) {
            return false;
        }
        setDirty(true);
        return true;
    }

    /** Forgets every boss type; returns how many were listed. */
    public int forgetAll() {
        int size = bosses.size();
        if (size > 0) {
            bosses.clear();
            setDirty(true);
        }
        return size;
    }

    /** Every remembered boss id, in the order it was remembered. */
    public List<String> all() {
        return List.copyOf(bosses);
    }
}
