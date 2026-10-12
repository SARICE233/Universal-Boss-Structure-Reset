package com.structurereset.api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Random;
import org.junit.jupiter.api.Test;

/** Behaviour of the pure respawn degradation chain in {@link RespawnPlanner}. */
class RespawnPlannerTest {

    private static final RespawnPoint SPAWN = new RespawnPoint(0.0D, 64.0D, 0.0D);
    private static final RespawnPoint CONTAINERS = new RespawnPoint(10.0D, 64.0D, 0.0D);
    private static final RespawnPoint PLAYER = new RespawnPoint(0.0D, 64.0D, 10.0D);
    private static final RespawnPoint CENTRE = new RespawnPoint(5.0D, 64.0D, 5.0D);

    @Test
    void aUsableTriangleIsAccepted() {
        assertTrue(RespawnPlanner.triangleUsable(SPAWN, CONTAINERS, PLAYER));
    }

    @Test
    void aTriangleWithATooLongEdgeIsRejected() {
        RespawnPoint far = new RespawnPoint(80.0D, 64.0D, 0.0D);
        assertFalse(RespawnPlanner.triangleUsable(SPAWN, far, PLAYER));
    }

    @Test
    void aTriangleThatIsTooThinIsRejected() {
        RespawnPoint thin = new RespawnPoint(10.0D, 64.0D, 0.4D);
        assertFalse(RespawnPlanner.triangleUsable(SPAWN, thin, new RespawnPoint(10.0D, 64.0D, 0.0D)));
    }

    @Test
    void degenerateInputIsRejectedInsteadOfThrowing() {
        assertFalse(RespawnPlanner.triangleUsable(null, CONTAINERS, PLAYER));
    }

    @Test
    void theLongestEdgeAndTheAreaAreMeasured() {
        assertEquals(Math.sqrt(200.0D), RespawnPlanner.maxEdge(SPAWN, CONTAINERS, PLAYER), 1.0E-9D);
        assertEquals(50.0D, RespawnPlanner.triangleArea(SPAWN, CONTAINERS, PLAYER), 1.0E-9D);
    }

    @Test
    void theSamplesStayInsideTheTriangle() {
        Random random = new Random(1234L);
        for (int index = 0; index < 400; index++) {
            RespawnPoint sample = RespawnPlanner.sampleInTriangle(SPAWN, CONTAINERS, PLAYER, random, 0.0D);
            assertTrue(isInside(SPAWN, CONTAINERS, PLAYER, sample),
                    "sample " + sample + " escaped the triangle");
        }
    }

    @Test
    void aFullBiasReturnsTheSpawnPointItself() {
        RespawnPoint sample = RespawnPlanner.sampleInTriangle(SPAWN, CONTAINERS, PLAYER,
                new Random(99L), 1.0D);
        assertEquals(SPAWN.x(), sample.x(), 1.0E-9D);
        assertEquals(SPAWN.y(), sample.y(), 1.0E-9D);
        assertEquals(SPAWN.z(), sample.z(), 1.0E-9D);
    }

    @Test
    void threeInsidePointsWithAUsableTriangleAreSampled() {
        RespawnChoice choice = RespawnPlanner.plan(SPAWN, true, CONTAINERS, true, PLAYER, true,
                CENTRE, new Random(7L));
        assertSame(RespawnBranch.TRIANGLE_SAMPLE, choice.branch());
        assertTrue(isInside(SPAWN, CONTAINERS, PLAYER, choice.point()));
    }

    @Test
    void aDegenerateTriangleBecomesTheWeightedCentre() {
        // Three collinear points have no area at all, so the triangle branch is
        // refused and the weighted centre (0.5 A + 0.3 B + 0.2 C) is used.
        RespawnPoint middle = new RespawnPoint(10.0D, 64.0D, 0.0D);
        RespawnPoint far = new RespawnPoint(20.0D, 64.0D, 0.0D);
        assertFalse(RespawnPlanner.triangleUsable(SPAWN, middle, far));
        RespawnChoice choice = RespawnPlanner.plan(SPAWN, true, middle, true, far, true,
                CENTRE, new Random(7L));
        assertSame(RespawnBranch.WEIGHTED_CENTROID, choice.branch());
        RespawnPoint expected = SPAWN.weightedWith(middle, far, 0.5D, 0.3D, 0.2D);
        assertEquals(expected.x(), choice.point().x(), 1.0E-9D);
        assertEquals(expected.z(), choice.point().z(), 1.0E-9D);
    }

    @Test
    void twoInsidePointsAreWeightedWithTheSpawnPointFirst() {
        RespawnChoice choice = RespawnPlanner.plan(SPAWN, true, CONTAINERS, true, PLAYER, false,
                CENTRE, new Random(7L));
        assertSame(RespawnBranch.PAIR_WEIGHTED, choice.branch());
        assertEquals(4.0D, choice.point().x(), 1.0E-9D);
    }

    @Test
    void onlyTheSpawnPointInsideIsTakenAsItIs() {
        RespawnChoice choice = RespawnPlanner.plan(SPAWN, true, CONTAINERS, false, PLAYER, false,
                CENTRE, new Random(7L));
        assertSame(RespawnBranch.SINGLE_SPAWN_POINT, choice.branch());
        assertSame(SPAWN, choice.point());
    }

    @Test
    void onlyTheContainerCentreInsideIsTakenAsItIs() {
        RespawnChoice choice = RespawnPlanner.plan(SPAWN, false, CONTAINERS, true, PLAYER, false,
                CENTRE, new Random(7L));
        assertSame(RespawnBranch.SINGLE_CONTAINER_CENTRE, choice.branch());
        assertSame(CONTAINERS, choice.point());
    }

    @Test
    void onlyThePlayerInsideFallsBackToTheStructureCentre() {
        RespawnChoice choice = RespawnPlanner.plan(SPAWN, false, CONTAINERS, false, PLAYER, true,
                CENTRE, new Random(7L));
        assertSame(RespawnBranch.STRUCTURE_CENTRE, choice.branch());
        assertSame(CENTRE, choice.point());
    }

    @Test
    void nothingInsideFallsBackToTheStructureCentre() {
        RespawnChoice choice = RespawnPlanner.plan(SPAWN, false, CONTAINERS, false, PLAYER, false,
                CENTRE, new Random(7L));
        assertSame(RespawnBranch.STRUCTURE_CENTRE, choice.branch());
        assertSame(CENTRE, choice.point());
    }

    @Test
    void theSameSeedProducesTheSamePosition() {
        RespawnChoice first = RespawnPlanner.plan(SPAWN, true, CONTAINERS, true, PLAYER, true,
                CENTRE, new Random(42L));
        RespawnChoice second = RespawnPlanner.plan(SPAWN, true, CONTAINERS, true, PLAYER, true,
                CENTRE, new Random(42L));
        assertEquals(first.point().x(), second.point().x(), 1.0E-12D);
        assertEquals(first.point().z(), second.point().z(), 1.0E-12D);
    }

    /** True when {@code p} lies inside the triangle in the xz plane. */
    private static boolean isInside(RespawnPoint a, RespawnPoint b, RespawnPoint c, RespawnPoint p) {
        double whole = RespawnPlanner.triangleArea(a, b, c);
        double first = RespawnPlanner.triangleArea(p, b, c);
        double second = RespawnPlanner.triangleArea(a, p, c);
        double third = RespawnPlanner.triangleArea(a, b, p);
        return Math.abs(first + second + third - whole) < 1.0E-6D;
    }
}
