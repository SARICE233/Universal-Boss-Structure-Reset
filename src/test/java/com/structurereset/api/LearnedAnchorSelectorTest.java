package com.structurereset.api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;

/**
 * Selection of a learned anchor: dimension first, then the structure id
 * intersection backed by the record's own radius, then the radius alone, with a
 * deterministic order independent of the iteration order of the collection.
 */
class LearnedAnchorSelectorTest {

    private static final String OVERWORLD = "minecraft:overworld";
    private static final String NETHER = "minecraft:the_nether";
    private static final String SOUL_BLACK_SMITH = "cataclysm:soul_black_smith";

    private static LearnedAnchor anchor(String id, String dimension, List<String> structureIds,
                                        int x, int y, int z, double radius, long learnedAt) {
        return new LearnedAnchor(id, dimension, structureIds, "cataclysm:the_harbinger",
                x, y, z, radius, List.of(), learnedAt, "tester");
    }

    @Test
    void anotherDimensionIsNeverSelected() {
        LearnedAnchor overworld = anchor("structurereset:ow", OVERWORLD,
                List.of(SOUL_BLACK_SMITH), 0, 0, 0, 64.0D, 1L);
        LearnedAnchor nether = anchor("structurereset:nether", NETHER,
                List.of(SOUL_BLACK_SMITH), 0, 0, 0, 64.0D, 99L);

        assertSame(overworld, LearnedAnchorSelector.select(List.of(nether, overworld), OVERWORLD,
                List.of(SOUL_BLACK_SMITH), 0, 0, 0).orElseThrow());
        assertSame(nether, LearnedAnchorSelector.select(List.of(nether, overworld), NETHER,
                List.of(SOUL_BLACK_SMITH), 0, 0, 0).orElseThrow());
        // a dimension nobody ever learned in stays empty even though an anchor is nearby
        assertTrue(LearnedAnchorSelector.select(List.of(overworld), "minecraft:the_end",
                List.of(SOUL_BLACK_SMITH), 0, 0, 0).isEmpty());
    }

    @Test
    void aStructureIntersectionOutranksBeingNearby() {
        // the far anchor claims the entered structure and is still inside its own
        // radius, the near one claims a different structure
        LearnedAnchor far = anchor("structurereset:far", OVERWORLD, List.of(SOUL_BLACK_SMITH),
                15, 5, 15, 64.0D, 1L);
        LearnedAnchor near = anchor("structurereset:near", OVERWORLD, List.of("minecraft:village"),
                0, 0, 0, 64.0D, 2L);

        assertSame(far, LearnedAnchorSelector.select(List.of(near, far), OVERWORLD,
                List.of(SOUL_BLACK_SMITH), 0, 0, 0).orElseThrow());
        assertSame(far, LearnedAnchorSelector.select(List.of(far, near), OVERWORLD,
                List.of(SOUL_BLACK_SMITH), 0, 0, 0).orElseThrow());
        // without an entered structure id the same two anchors are decided by distance
        assertSame(near, LearnedAnchorSelector.select(List.of(far, near), OVERWORLD,
                List.of(), 0, 0, 0).orElseThrow());
    }

    @Test
    void aStructureMatchOutsideItsOwnRadiusDoesNotHijack() {
        // The reported bug: a record written for a boss 141 blocks away shared the
        // huge "large_aercloud" structure id and answered for a gold dungeon it has
        // nothing to do with, so the wrong boss was summoned there.
        String cloud = "aether:large_aercloud";
        LearnedAnchor elsewhere = anchor("structurereset:cloud", OVERWORLD, List.of(cloud),
                179, 80, 29, 64.0D, 1L);
        LearnedAnchor here = anchor("structurereset:here", OVERWORLD,
                List.of(cloud, "aether:gold_dungeon"), 188, 60, 173, 64.0D, 2L);

        // the record that really describes this spot wins, even though it is older
        assertSame(here, LearnedAnchorSelector.select(List.of(elsewhere, here), OVERWORLD,
                List.of("aether:gold_dungeon", cloud), 187, 60, 170).orElseThrow());
        // on its own the far record must not answer at all
        assertTrue(LearnedAnchorSelector.select(List.of(elsewhere), OVERWORLD,
                List.of("aether:gold_dungeon", cloud), 187, 60, 170).isEmpty());
    }

    @Test
    void aRecordOnlyAnswersInsideItsOwnRadius() {
        // The record's radius is the whole of its claim: a shared structure id is not
        // enough, and nothing widens the radius.
        LearnedAnchor outside = anchor("structurereset:outside", OVERWORLD, List.of(SOUL_BLACK_SMITH),
                100, 0, 0, 64.0D, 1L);
        LearnedAnchor inside = anchor("structurereset:inside", OVERWORLD, List.of(SOUL_BLACK_SMITH),
                40, 0, 0, 64.0D, 2L);

        assertTrue(LearnedAnchorSelector.select(List.of(outside), OVERWORLD,
                List.of(SOUL_BLACK_SMITH), 0, 0, 0).isEmpty());
        assertSame(inside, LearnedAnchorSelector.select(List.of(outside, inside), OVERWORLD,
                List.of(SOUL_BLACK_SMITH), 0, 0, 0).orElseThrow());
    }

    @Test
    void theNearestStructureMatchWinsOverAMoreRecentOne() {
        // Both cover the entered structure. The player walked to the older one, and
        // where the player stands is the strongest hint there is.
        LearnedAnchor near = anchor("structurereset:near", OVERWORLD, List.of(SOUL_BLACK_SMITH),
                5, 0, 0, 64.0D, 1L);
        LearnedAnchor recent = anchor("structurereset:recent", OVERWORLD, List.of(SOUL_BLACK_SMITH),
                60, 0, 0, 64.0D, 99L);

        assertSame(near, LearnedAnchorSelector.select(List.of(recent, near), OVERWORLD,
                List.of(SOUL_BLACK_SMITH), 0, 0, 0).orElseThrow());
        assertSame(near, LearnedAnchorSelector.select(List.of(near, recent), OVERWORLD,
                List.of(SOUL_BLACK_SMITH), 0, 0, 0).orElseThrow());
    }

    @Test
    void theMostRecentlyLearnedStructureMatchWins() {
        LearnedAnchor older = anchor("structurereset:older", OVERWORLD,
                List.of(SOUL_BLACK_SMITH), 0, 0, 0, 64.0D, 10L);
        LearnedAnchor newer = anchor("structurereset:newer", OVERWORLD,
                List.of(SOUL_BLACK_SMITH), 0, 0, 0, 64.0D, 20L);

        assertSame(newer, LearnedAnchorSelector.select(List.of(older, newer), OVERWORLD,
                List.of(SOUL_BLACK_SMITH), 0, 0, 0).orElseThrow());
        assertSame(newer, LearnedAnchorSelector.select(List.of(newer, older), OVERWORLD,
                List.of(SOUL_BLACK_SMITH), 0, 0, 0).orElseThrow());
    }

    @Test
    void theRadiusBoundaryIsInclusive() {
        LearnedAnchor only = anchor("structurereset:boundary", OVERWORLD, List.of(),
                0, 0, 0, 5.0D, 1L);

        // 3*3 + 4*4 == 25 == radius * radius: exactly on the boundary
        assertSame(only, LearnedAnchorSelector.select(List.of(only), OVERWORLD, List.of(),
                3, 4, 0).orElseThrow());
        assertSame(only, LearnedAnchorSelector.select(List.of(only), OVERWORLD, List.of(),
                -3, -4, 0).orElseThrow());
        // 26 > 25 and 36 > 25: just outside
        assertTrue(LearnedAnchorSelector.select(List.of(only), OVERWORLD, List.of(),
                3, 4, 1).isEmpty());
        assertTrue(LearnedAnchorSelector.select(List.of(only), OVERWORLD, List.of(),
                0, 0, 6).isEmpty());
    }

    @Test
    void aZeroRadiusAnchorCannotEvenBeBuilt() {
        // the defensive radius<=0 branch of the selector is unreachable by design:
        // such an anchor cannot be constructed in the first place
        assertThrows(IllegalArgumentException.class, () -> new LearnedAnchor(
                "structurereset:zero", OVERWORLD, List.of(), "cataclysm:ignis",
                0, 0, 0, 0.0D, List.of(), 1L, "tester"));
    }

    @Test
    void theNearestAnchorWins() {
        LearnedAnchor far = anchor("structurereset:far", OVERWORLD, List.of(), 20, 0, 0, 64.0D, 500L);
        LearnedAnchor near = anchor("structurereset:near", OVERWORLD, List.of(), 2, 0, 0, 64.0D, 1L);

        assertSame(near, LearnedAnchorSelector.select(List.of(far, near), OVERWORLD, List.of(),
                0, 0, 0).orElseThrow());
        assertSame(near, LearnedAnchorSelector.select(List.of(near, far), OVERWORLD, List.of(),
                0, 0, 0).orElseThrow());
    }

    @Test
    void anEqualDistanceIsBrokenByRecency() {
        LearnedAnchor left = anchor("structurereset:left", OVERWORLD, List.of(), -2, 0, 0, 64.0D, 10L);
        LearnedAnchor right = anchor("structurereset:right", OVERWORLD, List.of(), 2, 0, 0, 64.0D, 20L);

        assertSame(right, LearnedAnchorSelector.select(List.of(left, right), OVERWORLD, List.of(),
                0, 0, 0).orElseThrow());
        assertSame(right, LearnedAnchorSelector.select(List.of(right, left), OVERWORLD, List.of(),
                0, 0, 0).orElseThrow());
    }

    @Test
    void aFullTieIsBrokenByIdAscending() {
        LearnedAnchor b = anchor("structurereset:b", OVERWORLD, List.of(), -2, 0, 0, 64.0D, 7L);
        LearnedAnchor a = anchor("structurereset:a", OVERWORLD, List.of(), 2, 0, 0, 64.0D, 7L);

        assertSame(a, LearnedAnchorSelector.select(List.of(b, a), OVERWORLD, List.of(),
                0, 0, 0).orElseThrow());
        assertSame(a, LearnedAnchorSelector.select(List.of(a, b), OVERWORLD, List.of(),
                0, 0, 0).orElseThrow());
    }

    @Test
    void aPositionOutsideEveryRadiusSelectsNothing() {
        LearnedAnchor only = anchor("structurereset:only", OVERWORLD, List.of(), 0, 0, 0, 4.0D, 1L);

        assertTrue(LearnedAnchorSelector.select(List.of(only), OVERWORLD, List.of(),
                1000, 1000, 1000).isEmpty());
        // an entered structure id that no anchor claims does not rescue the far position
        assertTrue(LearnedAnchorSelector.select(List.of(only), OVERWORLD,
                List.of(SOUL_BLACK_SMITH), 1000, 1000, 1000).isEmpty());
        assertEquals(Optional.empty(), LearnedAnchorSelector.select(List.of(only), OVERWORLD,
                List.of(), 0, 5, 0));
    }

    @Test
    void nullOrEmptyInputsSelectNothing() {
        LearnedAnchor only = anchor("structurereset:only", OVERWORLD, List.of(SOUL_BLACK_SMITH),
                0, 0, 0, 64.0D, 1L);

        assertTrue(LearnedAnchorSelector.select(null, OVERWORLD, List.of(SOUL_BLACK_SMITH),
                0, 0, 0).isEmpty());
        assertTrue(LearnedAnchorSelector.select(List.of(), OVERWORLD, List.of(SOUL_BLACK_SMITH),
                0, 0, 0).isEmpty());
        assertTrue(LearnedAnchorSelector.select(List.of(only), null, List.of(SOUL_BLACK_SMITH),
                0, 0, 0).isEmpty());
        assertTrue(LearnedAnchorSelector.select(List.of(only), "   ", List.of(SOUL_BLACK_SMITH),
                0, 0, 0).isEmpty());
        // a null structure id collection is legal: the anchors are then distance matched
        assertSame(only, LearnedAnchorSelector.select(List.of(only), OVERWORLD, null,
                0, 0, 0).orElseThrow());
    }
}
