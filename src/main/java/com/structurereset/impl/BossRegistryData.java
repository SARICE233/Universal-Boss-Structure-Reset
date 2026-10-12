package com.structurereset.impl;

import com.mojang.logging.LogUtils;
import org.slf4j.Logger;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.TreeMap;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.saveddata.SavedData;

/**
 * The durable boss registry: what boss a structure generated, where it came
 * from, whether it was already beaten, and how often it has been brought back.
 *
 * <p>It follows the two observations of the "preserve at generation, confirm at
 * death" rule: a boss that enters the world inside a structure is remembered
 * with its UUID and its exact spawn position (the most reliable reference for a
 * later respawn), and its death is confirmed later on the very same record. A
 * structure whose boss has not been beaten yet is therefore known, and so is
 * one that is ready to be brought back.</p>
 *
 * <p>The store is one JSON array inside one {@link CompoundTag}, attached to
 * the overworld like {@link LearnedAnchorData} and {@link LootIndexData}, so it
 * survives {@code /reload} and a restart and is shared by every player.</p>
 */
public final class BossRegistryData extends SavedData {

    private static final Logger LOGGER = LogUtils.getLogger();

    /** Name the data is stored under inside the overworld data directory. */
    public static final String DATA_NAME = "structurereset_boss_registry";
    /** Key of the nested tag that carries the records as a JSON array string. */
    public static final String TAG_RECORDS = "records";

    private static final Gson GSON = new GsonBuilder().create();

    /** The durable memory of one boss, keyed by its UUID. */
    public record BossRecord(String bossUuid, String bossEntityType, List<String> structureIds,
                             String dimension, int spawnX, int spawnY, int spawnZ,
                             float spawnYaw, float spawnPitch, long spawnGameTime,
                             long deathGameTime, int deathX, int deathY, int deathZ,
                             String killerUuid, boolean defeated, int resetCount,
                             String evidence) {

        /** Serialises this record; field names are a save format and stay stable. */
        public JsonObject toJson() {
            JsonObject json = new JsonObject();
            json.addProperty("boss_uuid", bossUuid);
            json.addProperty("boss_entity_type", bossEntityType);
            JsonArray ids = new JsonArray();
            for (String id : structureIds) {
                ids.add(id);
            }
            json.add("structure_ids", ids);
            json.addProperty("dimension", dimension);
            json.addProperty("spawn_x", spawnX);
            json.addProperty("spawn_y", spawnY);
            json.addProperty("spawn_z", spawnZ);
            json.addProperty("spawn_yaw", spawnYaw);
            json.addProperty("spawn_pitch", spawnPitch);
            json.addProperty("spawn_game_time", spawnGameTime);
            json.addProperty("death_game_time", deathGameTime);
            json.addProperty("death_x", deathX);
            json.addProperty("death_y", deathY);
            json.addProperty("death_z", deathZ);
            json.addProperty("killer_uuid", killerUuid == null ? "" : killerUuid);
            json.addProperty("defeated", defeated);
            json.addProperty("reset_count", resetCount);
            json.addProperty("evidence", evidence == null ? "" : evidence);
            return json;
        }

        /** Reads a record back; throws on a malformed body so the caller drops it. */
        public static BossRecord fromJson(JsonObject json) {
            List<String> ids = new ArrayList<>();
            if (json.has("structure_ids")) {
                for (JsonElement element : json.getAsJsonArray("structure_ids")) {
                    ids.add(element.getAsString());
                }
            }
            return new BossRecord(json.get("boss_uuid").getAsString(),
                    json.get("boss_entity_type").getAsString(), List.copyOf(ids),
                    json.get("dimension").getAsString(),
                    json.get("spawn_x").getAsInt(), json.get("spawn_y").getAsInt(),
                    json.get("spawn_z").getAsInt(),
                    json.has("spawn_yaw") ? json.get("spawn_yaw").getAsFloat() : 0.0F,
                    json.has("spawn_pitch") ? json.get("spawn_pitch").getAsFloat() : 0.0F,
                    json.has("spawn_game_time") ? json.get("spawn_game_time").getAsLong() : 0L,
                    json.has("death_game_time") ? json.get("death_game_time").getAsLong() : 0L,
                    json.has("death_x") ? json.get("death_x").getAsInt() : 0,
                    json.has("death_y") ? json.get("death_y").getAsInt() : 0,
                    json.has("death_z") ? json.get("death_z").getAsInt() : 0,
                    json.has("killer_uuid") ? json.get("killer_uuid").getAsString() : "",
                    json.has("defeated") && json.get("defeated").getAsBoolean(),
                    json.has("reset_count") ? json.get("reset_count").getAsInt() : 0,
                    json.has("evidence") ? json.get("evidence").getAsString() : "");
        }

        /** A copy of this record marked as beaten. */
        public BossRecord defeatedBy(String killer, long gameTime, int x, int y, int z) {
            return new BossRecord(bossUuid, bossEntityType, structureIds, dimension,
                    spawnX, spawnY, spawnZ, spawnYaw, spawnPitch, spawnGameTime,
                    gameTime, x, y, z, killer == null ? "" : killer, true, resetCount, evidence);
        }

        /** A copy of this record for a boss that was just spawned again. */
        public BossRecord respawnedAt(int x, int y, int z, float yaw, float pitch, long gameTime) {
            return new BossRecord(bossUuid, bossEntityType, structureIds, dimension,
                    x, y, z, yaw, pitch, gameTime, 0L, 0, 0, 0, "", false,
                    resetCount + 1, evidence);
        }

        /** True when this record overlaps the given structures in the given dimension. */
        public boolean covers(String otherDimension, Collection<String> otherIds) {
            if (otherDimension == null || !otherDimension.equals(dimension)) {
                return false;
            }
            for (String id : structureIds) {
                if (otherIds != null && otherIds.contains(id)) {
                    return true;
                }
            }
            return false;
        }
    }

    private final TreeMap<String, BossRecord> records = new TreeMap<>();

    /** The registry of {@code server}, creating the store when it does not exist yet. */
    public static BossRegistryData get(MinecraftServer server) {
        return server.overworld().getDataStorage()
                .computeIfAbsent(BossRegistryData::load, BossRegistryData::new, DATA_NAME);
    }

    /** The registry of {@code level}; the registry always lives with the overworld. */
    public static BossRegistryData get(ServerLevel level) {
        MinecraftServer server = level.getServer();
        if (server != null) {
            return get(server);
        }
        return level.getDataStorage().computeIfAbsent(BossRegistryData::load, BossRegistryData::new, DATA_NAME);
    }

    /** Reads the stored records; a malformed record is dropped, never fatal. */
    public static BossRegistryData load(CompoundTag tag) {
        BossRegistryData data = new BossRegistryData();
        if (tag == null) {
            return data;
        }
        String raw = tag.getString(TAG_RECORDS);
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
                BossRecord record = BossRecord.fromJson(element.getAsJsonObject());
                data.records.put(record.bossUuid(), record);
            } catch (RuntimeException ignored) {
                // one malformed record never fails the whole load
            LOGGER.warn("structure reset: skipped one malformed entry", ignored);
            }
        }
        return data;
    }

    @Override
    public CompoundTag save(CompoundTag tag) {
        JsonArray array = new JsonArray();
        for (BossRecord record : records.values()) {
            array.add(record.toJson());
        }
        tag.putString(TAG_RECORDS, GSON.toJson(array));
        return tag;
    }

    /** Every record, oldest UUID first. */
    public List<BossRecord> all() {
        return List.copyOf(records.values());
    }

    /** The record of one boss, or empty when it is unknown. */
    public Optional<BossRecord> byUuid(String uuid) {
        if (uuid == null) {
            return Optional.empty();
        }
        return Optional.ofNullable(records.get(uuid));
    }

    /** Stores {@code record}, replacing an earlier record with the same UUID. */
    public void put(BossRecord record) {
        if (record == null || record.bossUuid() == null || record.bossUuid().isEmpty()) {
            return;
        }
        records.put(record.bossUuid(), record);
        setDirty(true);
    }

    /** Forgets one boss; true when something was removed. */
    public boolean remove(String uuid) {
        boolean removed = uuid != null && records.remove(uuid) != null;
        if (removed) {
            setDirty(true);
        }
        return removed;
    }

    /** Forgets every record. */
    public void clear() {
        if (!records.isEmpty()) {
            records.clear();
            setDirty(true);
        }
    }

    /** How many bosses are remembered. */
    public int size() {
        return records.size();
    }

    /**
     * The most recently beaten boss of the given structures that has not been
     * brought back yet - the record a reset should replay.
     *
     * @param dimension the dimension id to look in
     * @param structureIds the structures at the reset position, may be empty
     * @return the record, or empty when no beaten boss is waiting there
     */
    public Optional<BossRecord> awaitingRespawn(String dimension, Collection<String> structureIds) {
        BossRecord best = null;
        for (BossRecord record : records.values()) {
            if (!record.defeated() || !record.covers(dimension, structureIds)) {
                continue;
            }
            if (best == null || record.deathGameTime() > best.deathGameTime()) {
                best = record;
            }
        }
        return Optional.ofNullable(best);
    }

    /**
     * The nearest record of {@code entityType} that has not been beaten yet.
     *
     * <p>This is the safety net of the death confirmation: a boss that a reset
     * spawned without being bound (a hand built arena, or an entity that entered
     * the world before its structure lookup could settle) still gets confirmed,
     * so a player is never locked out by a kill nobody tracked.</p>
     *
     * @param entityType the entity type id of the boss that died
     * @param dimension the dimension it died in
     * @param x the death x coordinate
     * @param y the death y coordinate
     * @param z the death z coordinate
     * @param radius how far the record's spawn point may be away
     * @return the record, or empty when nothing plausible matches
     */
    public Optional<BossRecord> closestLiving(String entityType, String dimension,
                                              int x, int y, int z, double radius) {
        BossRecord best = null;
        double bestDistance = Double.MAX_VALUE;
        for (BossRecord record : records.values()) {
            if (record.defeated() || entityType == null || !entityType.equals(record.bossEntityType())
                    || dimension == null || !dimension.equals(record.dimension())) {
                continue;
            }
            double dx = record.spawnX() - x;
            double dy = record.spawnY() - y;
            double dz = record.spawnZ() - z;
            double distance = Math.sqrt(dx * dx + dy * dy + dz * dz);
            if (distance <= radius && distance < bestDistance) {
                bestDistance = distance;
                best = record;
            }
        }
        return Optional.ofNullable(best);
    }

    /** The newest record of the given structures, beaten or not. */
    public Optional<BossRecord> knownIn(String dimension, Collection<String> structureIds) {
        BossRecord best = null;
        for (BossRecord record : records.values()) {
            if (!record.covers(dimension, structureIds)) {
                continue;
            }
            if (best == null || record.spawnGameTime() > best.spawnGameTime()) {
                best = record;
            }
        }
        return Optional.ofNullable(best);
    }
}
