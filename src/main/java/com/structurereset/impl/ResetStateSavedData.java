package com.structurereset.impl;

import com.mojang.logging.LogUtils;
import org.slf4j.Logger;
import com.structurereset.api.CooldownPolicy;
import com.structurereset.api.ResetKey;
import java.util.LinkedHashMap;
import java.util.Map;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.saveddata.SavedData;

/**
 * The persisted reset clock of one level.
 *
 * <p>The data is stored as one {@code CompoundTag} whose keys are
 * {@link ResetKey#toStorageString()} and whose values are the last reset
 * timestamp in milliseconds (world game time * 50). It is written through the
 * ordinary {@code SavedData} mechanism, so it is per level and survives a
 * server restart.</p>
 */
public final class ResetStateSavedData extends SavedData {

    private static final Logger LOGGER = LogUtils.getLogger();

    /** Name the data is stored under inside the level directory. */
    public static final String DATA_NAME = "structurereset_state";
    /** Key of the nested tag that carries the reset clocks. */
    public static final String TAG_RESETS = "resets";

    private final Map<ResetKey, Long> lastResets = new LinkedHashMap<>();

    /** The state of {@code level}, creating it when it does not exist yet. */
    public static ResetStateSavedData get(ServerLevel level) {
        if (level == null) {
            throw new IllegalArgumentException("reset state needs a level");
        }
        return level.getDataStorage().computeIfAbsent(
                ResetStateSavedData::load, ResetStateSavedData::new, DATA_NAME);
    }

    /** Reads the stored clocks out of a saved tag. */
    public static ResetStateSavedData load(CompoundTag tag) {
        ResetStateSavedData data = new ResetStateSavedData();
        if (tag == null) {
            return data;
        }
        CompoundTag stored = tag.getCompound(TAG_RESETS);
        for (String key : stored.getAllKeys()) {
            try {
                data.lastResets.put(ResetKey.fromStorageString(key), stored.getLong(key));
            } catch (IllegalArgumentException ignored) {
                // A key that no longer parses is dropped instead of failing the load.
            LOGGER.warn("structure reset: skipped one malformed entry", ignored);
            }
        }
        return data;
    }

    @Override
    public CompoundTag save(CompoundTag tag) {
        CompoundTag stored = new CompoundTag();
        for (Map.Entry<ResetKey, Long> entry : lastResets.entrySet()) {
            stored.putLong(entry.getKey().toStorageString(), entry.getValue());
        }
        tag.put(TAG_RESETS, stored);
        return tag;
    }

    /** The last reset timestamp of {@code key}, or 0 when it never ran. */
    public long lastReset(ResetKey key) {
        Long value = key == null ? null : lastResets.get(key);
        return value == null ? 0L : value;
    }

    /** True when {@code key} has no clock left to wait for. */
    public boolean isReady(ResetKey key, int cooldownSeconds, long now) {
        return CooldownPolicy.ready(now, lastReset(key), cooldownSeconds);
    }

    /** Milliseconds left before {@code key} may reset again. */
    public long remainingMillis(ResetKey key, int cooldownSeconds, long now) {
        return CooldownPolicy.remainingMillis(now, lastReset(key), cooldownSeconds);
    }

    /** Records that {@code key} ran at {@code now} and marks the data dirty. */
    public void markReset(ResetKey key, long now) {
        if (key == null) {
            return;
        }
        lastResets.put(key, now);
        setDirty(true);
    }

    /** Forgets one clock. */
    public void clear(ResetKey key) {
        if (key != null && lastResets.remove(key) != null) {
            setDirty(true);
        }
    }

    /** Forgets every clock. */
    public void clearAll() {
        if (!lastResets.isEmpty()) {
            lastResets.clear();
            setDirty(true);
        }
    }

    /** How many clocks are currently stored. */
    public int size() {
        return lastResets.size();
    }
}
