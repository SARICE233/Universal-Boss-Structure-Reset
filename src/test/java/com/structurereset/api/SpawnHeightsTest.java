package com.structurereset.api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * The heights a flying boss is tried at: the search starts above the marked
 * block and only ever climbs, so a flyer never begins inside the floor of the
 * room it was summoned into.
 */
class SpawnHeightsTest {

    @Test
    void aFlyerStartsAboveTheMarkAndClimbs() {
        List<Integer> lifts = SpawnHeights.flyerLifts(2, 3);

        assertEquals(List.of(2, 3, 4, 5), lifts);
        assertTrue(lifts.get(0) >= 1, "the mark itself is never the first choice");
    }

    @Test
    void theDefaultsCoverTheWholeSearchRange() {
        List<Integer> lifts = SpawnHeights.flyerLifts();

        assertEquals(SpawnHeights.FLYER_LIFT, lifts.get(0));
        assertEquals(SpawnHeights.FLYER_SEARCH + 1, lifts.size());
        for (int lift : lifts) {
            assertTrue(lift >= 1);
        }
    }

    @Test
    void aNonsenseArgumentStillProducesUsableHeights() {
        assertEquals(List.of(1), SpawnHeights.flyerLifts(0, -5));
    }
}
