package com.structurereset.api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.gson.JsonObject;
import org.junit.jupiter.api.Test;

/**
 * The loot container snapshot of the teaching feature: a container carries the
 * block coordinates it was seen at, the loot table it rolled and - when the seed
 * was actually observed - the seed to replay.
 */
class LearnedContainerTest {

    @Test
    void jsonRoundTripKeepsEveryFieldOfALearnedSeed() {
        LearnedContainer container = LearnedContainer.of(10, 64, -30,
                "minecraft:chests/simple_dungeon", 1234567890123L);
        assertTrue(container.hasSeed());

        JsonObject json = container.toJson();
        assertEquals(10, json.get("x").getAsInt());
        assertEquals(64, json.get("y").getAsInt());
        assertEquals(-30, json.get("z").getAsInt());
        assertEquals("minecraft:chests/simple_dungeon", json.get("loot_table").getAsString());
        assertEquals(1234567890123L, json.get("loot_table_seed").getAsLong());
        assertTrue(json.get("has_seed").getAsBoolean());

        LearnedContainer back = LearnedContainer.fromJson(json);
        assertEquals(container, back);
        assertEquals(1234567890123L, back.lootTableSeed());
    }

    @Test
    void jsonRoundTripKeepsAnUnknownSeed() {
        LearnedContainer container = new LearnedContainer(1, 2, 3,
                "minecraft:chests/abandoned_mineshaft", 0L, false);

        JsonObject json = container.toJson();
        assertFalse(json.get("has_seed").getAsBoolean());

        LearnedContainer back = LearnedContainer.fromJson(json);
        assertEquals(container, back);
        assertFalse(back.hasSeed());
        assertEquals(0L, back.lootTableSeed());
    }

    @Test
    void aMalformedLootTableIsRejected() {
        assertThrows(IllegalArgumentException.class,
                () -> LearnedContainer.of(0, 0, 0, null, 1L));
        assertThrows(IllegalArgumentException.class,
                () -> LearnedContainer.of(0, 0, 0, "", 1L));
        assertThrows(IllegalArgumentException.class,
                () -> LearnedContainer.of(0, 0, 0, "   ", 1L));
        assertThrows(IllegalArgumentException.class,
                () -> LearnedContainer.of(0, 0, 0, "simple_dungeon", 1L));
        assertThrows(IllegalArgumentException.class,
                () -> LearnedContainer.of(0, 0, 0, ":simple_dungeon", 1L));
        assertThrows(IllegalArgumentException.class,
                () -> LearnedContainer.of(0, 0, 0, "minecraft:", 1L));
        assertThrows(IllegalArgumentException.class,
                () -> LearnedContainer.of(0, 0, 0, "minecraft:a:b", 1L));
        assertThrows(IllegalArgumentException.class,
                () -> LearnedContainer.of(0, 0, 0, "mine craft:village", 1L));
    }

    @Test
    void fromJsonRejectsAMissingOrMalformedLootTable() {
        JsonObject withoutLootTable = new JsonObject();
        withoutLootTable.addProperty("x", 1);
        assertThrows(IllegalArgumentException.class,
                () -> LearnedContainer.fromJson(withoutLootTable));

        JsonObject malformed = new JsonObject();
        malformed.addProperty("loot_table", "not_namespaced");
        assertThrows(IllegalArgumentException.class, () -> LearnedContainer.fromJson(malformed));

        assertThrows(IllegalArgumentException.class, () -> LearnedContainer.fromJson(null));
    }

    @Test
    void fromJsonFallsBackToZeroForAbsentFields() {
        JsonObject json = new JsonObject();
        json.addProperty("loot_table", "minecraft:chests/village");

        LearnedContainer container = LearnedContainer.fromJson(json);
        assertEquals(0, container.x());
        assertEquals(0, container.y());
        assertEquals(0, container.z());
        assertEquals(0L, container.lootTableSeed());
        assertFalse(container.hasSeed());
    }
}
