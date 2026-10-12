package com.structurereset.api;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/**
 * Telling "it discarded itself" apart from "it was killed": the first is what a
 * generic addon cannot undo, and only that one is remembered.
 */
class SpawnVerdictTest {

    @Test
    void aFullHealthBossThatVanishesAtOnceRefusedToStay() {
        // the Aether Sun Spirit: gone after one tick, still alive at 8500 health
        assertTrue(SpawnVerdict.refusedToStay(1L, true));
        assertTrue(SpawnVerdict.refusedToStay(SpawnVerdict.REFUSED_LIFE_TICKS, true));
    }

    @Test
    void aBossThatWasFoughtIsNotMarked() {
        // killed: not alive any more, however early
        assertFalse(SpawnVerdict.refusedToStay(20L, false));
        // alive but long lived: it simply walked away, which is not a refusal
        assertFalse(SpawnVerdict.refusedToStay(SpawnVerdict.REFUSED_LIFE_TICKS + 1L, true));
    }

    @Test
    void anUnknownLifetimeIsNeverARefusal() {
        assertFalse(SpawnVerdict.refusedToStay(-1L, true));
    }
}
