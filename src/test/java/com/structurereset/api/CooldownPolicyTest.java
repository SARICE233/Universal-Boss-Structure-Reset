package com.structurereset.api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/** Behaviour of the pure cooldown arithmetic in {@link CooldownPolicy}. */
class CooldownPolicyTest {

    @Test
    void aZeroCooldownIsAlwaysReady() {
        assertTrue(CooldownPolicy.ready(0L, 0L, 0));
        assertTrue(CooldownPolicy.ready(1_000L, 999_999L, 0));
        assertEquals(0L, CooldownPolicy.remainingMillis(5_000L, 5_000L, 0));
    }

    @Test
    void aNegativeCooldownIsAlwaysReady() {
        assertTrue(CooldownPolicy.ready(10L, 20L, -5));
        assertEquals(0L, CooldownPolicy.remainingMillis(10L, 20L, -5));
    }

    @Test
    void reportsTheRemainingTimeBeforeTheCooldownElapses() {
        assertFalse(CooldownPolicy.ready(1_500L, 1_000L, 2));
        assertEquals(1_500L, CooldownPolicy.remainingMillis(1_500L, 1_000L, 2));
    }

    @Test
    void oneMillisecondBeforeTheEndIsStillNotReady() {
        assertFalse(CooldownPolicy.ready(2_999L, 1_000L, 2));
        assertEquals(1L, CooldownPolicy.remainingMillis(2_999L, 1_000L, 2));
    }

    @Test
    void becomesReadyExactlyWhenTheCooldownElapsed() {
        assertTrue(CooldownPolicy.ready(3_000L, 1_000L, 2));
        assertEquals(0L, CooldownPolicy.remainingMillis(3_000L, 1_000L, 2));
        assertTrue(CooldownPolicy.ready(9_999L, 1_000L, 2));
        assertEquals(0L, CooldownPolicy.remainingMillis(9_999L, 1_000L, 2));
    }

    @Test
    void aClockThatJumpedBackwardsRestartsTheWholeCooldown() {
        assertFalse(CooldownPolicy.ready(500L, 1_000L, 3));
        assertEquals(3_000L, CooldownPolicy.remainingMillis(500L, 1_000L, 3));
        assertFalse(CooldownPolicy.ready(0L, 1L, 1));
        assertEquals(1_000L, CooldownPolicy.remainingMillis(0L, 1L, 1));
    }

    @Test
    void aVeryLargeCooldownStillFitsInALong() {
        assertEquals(2_147_483_647_000L,
                CooldownPolicy.remainingMillis(0L, 0L, Integer.MAX_VALUE));
        assertFalse(CooldownPolicy.ready(0L, 0L, Integer.MAX_VALUE));
    }

    // ------------------------------------------- two click confirm window

    @Test
    void aNonPositiveConfirmWindowNeverConfirms() {
        assertFalse(CooldownPolicy.withinWindow(1_000L, 1_000L, 0));
        assertFalse(CooldownPolicy.withinWindow(1_000L, 1_000L, -3));
    }

    @Test
    void anUnsetArmingTimeNeverConfirms() {
        assertFalse(CooldownPolicy.withinWindow(5_000L, 0L, 10));
        assertFalse(CooldownPolicy.withinWindow(5_000L, -1L, 10));
    }

    @Test
    void theWindowConfirmsUntilItElapsed() {
        long armedAt = 1_000L;
        assertTrue(CooldownPolicy.withinWindow(armedAt, armedAt, 10));
        assertTrue(CooldownPolicy.withinWindow(armedAt + 1L, armedAt, 10));
        assertTrue(CooldownPolicy.withinWindow(armedAt + 10_000L, armedAt, 10));
        assertFalse(CooldownPolicy.withinWindow(armedAt + 10_001L, armedAt, 10));
        assertFalse(CooldownPolicy.withinWindow(armedAt + 60_000L, armedAt, 10));
    }

    @Test
    void aClockThatJumpedBackwardsNeverConfirms() {
        assertFalse(CooldownPolicy.withinWindow(999L, 1_000L, 10));
        assertFalse(CooldownPolicy.withinWindow(0L, 1L, 10));
    }
}
