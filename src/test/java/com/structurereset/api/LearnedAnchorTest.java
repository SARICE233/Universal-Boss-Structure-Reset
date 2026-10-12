package com.structurereset.api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * The learned anchor record: every mandatory id must be {@code namespace:path}
 * (the reset definition builder enforces exactly that later on), the radius must
 * be positive and the collections are normalised instead of copied verbatim.
 */
class LearnedAnchorTest {

    private static final String BOSS = "cataclysm:the_harbinger";

    private static LearnedAnchor anchor(String id, String dimension, List<String> structureIds,
                                        String bossEntity, int x, int y, int z, double radius,
                                        List<LearnedContainer> containers, long learnedAt,
                                        String learnedBy) {
        return new LearnedAnchor(id, dimension, structureIds, bossEntity, x, y, z, radius,
                containers, learnedAt, learnedBy);
    }

    private static LearnedAnchor sample() {
        return anchor("structurereset:soul_black_smith", "minecraft:overworld",
                List.of("cataclysm:soul_black_smith"), BOSS, 100, 64, -200, 32.0D,
                List.of(LearnedContainer.of(101, 64, -200, "minecraft:chests/simple_dungeon", 7L)),
                4242L, "tester");
    }

    @Test
    void aBlankIdIsRejected() {
        assertThrows(IllegalArgumentException.class, () -> anchor(null, "minecraft:overworld",
                List.of(), BOSS, 0, 0, 0, 4.0D, List.of(), 0L, "tester"));
        assertThrows(IllegalArgumentException.class, () -> anchor("", "minecraft:overworld",
                List.of(), BOSS, 0, 0, 0, 4.0D, List.of(), 0L, "tester"));
        assertThrows(IllegalArgumentException.class, () -> anchor("   ", "minecraft:overworld",
                List.of(), BOSS, 0, 0, 0, 4.0D, List.of(), 0L, "tester"));
    }

    @Test
    void anIdThatIsNotNamespacedIsRejected() {
        String[] invalid = {"village", "minecraft:", ":village", "a:b:c", "mine craft:village"};
        for (String id : invalid) {
            assertThrows(IllegalArgumentException.class, () -> anchor(id, "minecraft:overworld",
                    List.of(), BOSS, 0, 0, 0, 4.0D, List.of(), 0L, "tester"), id);
        }
        assertEquals("structurereset:soul_black_smith", sample().id());
    }

    @Test
    void aBlankOrUnnamespacedDimensionIsRejected() {
        assertThrows(IllegalArgumentException.class, () -> anchor("structurereset:a", null,
                List.of(), BOSS, 0, 0, 0, 4.0D, List.of(), 0L, "tester"));
        assertThrows(IllegalArgumentException.class, () -> anchor("structurereset:a", "",
                List.of(), BOSS, 0, 0, 0, 4.0D, List.of(), 0L, "tester"));
        assertThrows(IllegalArgumentException.class, () -> anchor("structurereset:a", "overworld",
                List.of(), BOSS, 0, 0, 0, 4.0D, List.of(), 0L, "tester"));
    }

    @Test
    void aBossEntityThatIsNotNamespacedIsRejected() {
        assertThrows(IllegalArgumentException.class, () -> anchor("structurereset:a",
                "minecraft:overworld", List.of(), null, 0, 0, 0, 4.0D, List.of(), 0L, "tester"));
        assertThrows(IllegalArgumentException.class, () -> anchor("structurereset:a",
                "minecraft:overworld", List.of(), "zombie", 0, 0, 0, 4.0D, List.of(), 0L, "tester"));
        assertThrows(IllegalArgumentException.class, () -> anchor("structurereset:a",
                "minecraft:overworld", List.of(), "cataclysm:", 0, 0, 0, 4.0D, List.of(), 0L,
                "tester"));
    }

    @Test
    void aNonPositiveOrNanRadiusIsRejected() {
        assertThrows(IllegalArgumentException.class, () -> anchor("structurereset:a",
                "minecraft:overworld", List.of(), BOSS, 0, 0, 0, 0.0D, List.of(), 0L, "tester"));
        assertThrows(IllegalArgumentException.class, () -> anchor("structurereset:a",
                "minecraft:overworld", List.of(), BOSS, 0, 0, 0, -1.0D, List.of(), 0L, "tester"));
        assertThrows(IllegalArgumentException.class, () -> anchor("structurereset:a",
                "minecraft:overworld", List.of(), BOSS, 0, 0, 0, -0.001D, List.of(), 0L, "tester"));
        assertThrows(IllegalArgumentException.class, () -> anchor("structurereset:a",
                "minecraft:overworld", List.of(), BOSS, 0, 0, 0, Double.NaN, List.of(), 0L,
                "tester"));
        assertEquals(4.0D, anchor("structurereset:a", "minecraft:overworld", List.of(), BOSS,
                0, 0, 0, 4.0D, List.of(), 0L, "tester").radius());
    }

    @Test
    void nullCollectionsBecomeEmptyAndANullAuthorBecomesAnEmptyString() {
        LearnedAnchor anchor = anchor("structurereset:a", "minecraft:overworld", null, BOSS,
                1, 2, 3, 8.0D, null, 5L, null);
        assertTrue(anchor.structureIds().isEmpty());
        assertTrue(anchor.containers().isEmpty());
        assertEquals("", anchor.learnedBy());
    }

    @Test
    void jsonRoundTripKeepsStructuresContainersAndAuthor() {
        LearnedAnchor anchor = anchor("structurereset:soul_black_smith", "minecraft:overworld",
                List.of("cataclysm:soul_black_smith", "minecraft:village"), BOSS, 100, 64, -200,
                32.5D,
                List.of(LearnedContainer.of(101, 64, -200, "minecraft:chests/simple_dungeon", 7L),
                        new LearnedContainer(102, 64, -200,
                                "minecraft:chests/abandoned_mineshaft", 0L, false)),
                4242L, "tester");

        JsonObject json = anchor.toJson();
        assertEquals(2, json.getAsJsonArray("structure_ids").size());
        assertEquals(2, json.getAsJsonArray("containers").size());
        assertEquals(100, json.get("x").getAsInt());
        assertEquals(32.5D, json.get("radius").getAsDouble());
        assertEquals(4242L, json.get("learned_at_game_time").getAsLong());
        assertEquals("tester", json.get("learned_by").getAsString());

        assertEquals(anchor, LearnedAnchor.fromJson(json));
    }

    @Test
    void fromJsonToleratesMissingOptionalFields() {
        JsonObject json = new JsonObject();
        json.addProperty("id", "structurereset:minimal");
        json.addProperty("dimension", "minecraft:the_nether");
        json.addProperty("boss_entity", "cataclysm:ignis");
        json.addProperty("radius", 16.0D);

        LearnedAnchor anchor = LearnedAnchor.fromJson(json);
        assertTrue(anchor.structureIds().isEmpty());
        assertTrue(anchor.containers().isEmpty());
        assertEquals("", anchor.learnedBy());
        assertEquals(0, anchor.x());
        assertEquals(0, anchor.y());
        assertEquals(0, anchor.z());
        assertEquals(0L, anchor.learnedAtGameTime());
        assertEquals(16.0D, anchor.radius());
    }

    @Test
    void fromJsonRejectsAMissingOrMalformedMandatoryField() {
        JsonObject missingRadius = new JsonObject();
        missingRadius.addProperty("id", "structurereset:a");
        missingRadius.addProperty("dimension", "minecraft:overworld");
        missingRadius.addProperty("boss_entity", BOSS);
        assertThrows(IllegalArgumentException.class, () -> LearnedAnchor.fromJson(missingRadius));

        JsonObject missingId = new JsonObject();
        missingId.addProperty("dimension", "minecraft:overworld");
        missingId.addProperty("boss_entity", BOSS);
        missingId.addProperty("radius", 4.0D);
        assertThrows(IllegalArgumentException.class, () -> LearnedAnchor.fromJson(missingId));

        JsonObject malformedBoss = new JsonObject();
        malformedBoss.addProperty("id", "structurereset:a");
        malformedBoss.addProperty("dimension", "minecraft:overworld");
        malformedBoss.addProperty("boss_entity", "zombie");
        malformedBoss.addProperty("radius", 4.0D);
        assertThrows(IllegalArgumentException.class, () -> LearnedAnchor.fromJson(malformedBoss));

        assertThrows(IllegalArgumentException.class, () -> LearnedAnchor.fromJson(null));
    }

    @Test
    void fromJsonNormalisesNonStringEntriesOutOfTheStructureList() {
        JsonObject json = new JsonObject();
        json.addProperty("id", "structurereset:a");
        json.addProperty("dimension", "minecraft:overworld");
        json.addProperty("boss_entity", BOSS);
        json.addProperty("radius", 4.0D);
        JsonArray structures = new JsonArray();
        structures.add("minecraft:village");
        structures.add(7);
        json.add("structure_ids", structures);

        LearnedAnchor anchor = LearnedAnchor.fromJson(json);
        assertEquals(List.of("minecraft:village"), anchor.structureIds());
        assertFalse(anchor.containers().size() > 0);
    }
}
