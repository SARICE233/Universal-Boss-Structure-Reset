package com.structurereset.api;

import java.util.Random;

/**
 * Where a boss should come back to, decided from three reference points.
 *
 * <p>The three points are the recorded spawn point of the boss (the most
 * reliable one), the centre of the structure's containers and altars, and the
 * position of the player who triggers the reset. When all three are inside the
 * structure and form a usable triangle, the position is sampled uniformly
 * inside that triangle, so the boss comes back somewhere between where it
 * appeared, where the loot is and where the player stands - never exactly on
 * top of anyone.</p>
 *
 * <p>Every other case degrades one step at a time: a degenerate triangle
 * becomes a weighted centre, two usable points become their weighted centre,
 * one usable point is taken as it is, and nothing usable falls back to the
 * structure's bounding box centre. The chain never guesses outside the data it
 * was given, and it never returns a position that is not derived from a point
 * the caller marked as usable.</p>
 *
 * <p>This class is pure: no Minecraft types, no world access. The engine
 * gathers the points and the safety of the result; the maths is testable on its
 * own.</p>
 */
public final class RespawnPlanner {

    /** The longest triangle edge that still counts as usable, in blocks. */
    public static final double MAX_EDGE = 32.0D;
    /** The smallest triangle area that still counts as usable, in square blocks. */
    public static final double MIN_AREA = 4.0D;
    /** How often the engine may resample inside the triangle before degrading. */
    public static final int MAX_RESAMPLES = 3;
    /** The default pull of a sample towards the recorded spawn point (0 = none). */
    public static final double DEFAULT_BIAS_TOWARD_SPAWN = 0.0D;

    private RespawnPlanner() {
    }

    /**
     * Runs the degradation chain.
     *
     * @param spawnPoint the recorded spawn point of the boss (point A)
     * @param spawnInside whether point A is inside the structure
     * @param containerCentre the centre of the structure's containers (point B)
     * @param containerInside whether point B is inside the structure
     * @param playerPoint the position of the triggering player (point C)
     * @param playerInside whether point C is inside the structure
     * @param structureCentre the centre of the structure's bounding box
     * @param random the source the triangle is sampled with
     * @return the chosen position and the branch that produced it, never null
     */
    public static RespawnChoice plan(RespawnPoint spawnPoint, boolean spawnInside,
                                     RespawnPoint containerCentre, boolean containerInside,
                                     RespawnPoint playerPoint, boolean playerInside,
                                     RespawnPoint structureCentre, Random random) {
        RespawnPoint centre = structureCentre == null ? spawnPoint : structureCentre;
        int inside = (spawnInside ? 1 : 0) + (containerInside ? 1 : 0) + (playerInside ? 1 : 0);

        if (inside == 3) {
            if (triangleUsable(spawnPoint, containerCentre, playerPoint)) {
                return new RespawnChoice(
                        sampleInTriangle(spawnPoint, containerCentre, playerPoint, random,
                                DEFAULT_BIAS_TOWARD_SPAWN),
                        RespawnBranch.TRIANGLE_SAMPLE);
            }
            return new RespawnChoice(
                    spawnPoint.weightedWith(containerCentre, playerPoint, 0.5D, 0.3D, 0.2D),
                    RespawnBranch.WEIGHTED_CENTROID);
        }
        if (inside == 2) {
            if (spawnInside && containerInside) {
                return new RespawnChoice(spawnPoint.weightedWith(containerCentre, 0.6D, 0.4D),
                        RespawnBranch.PAIR_WEIGHTED);
            }
            if (spawnInside && playerInside) {
                return new RespawnChoice(spawnPoint.weightedWith(playerPoint, 0.7D, 0.3D),
                        RespawnBranch.PAIR_WEIGHTED);
            }
            return new RespawnChoice(containerCentre.weightedWith(playerPoint, 0.6D, 0.4D),
                    RespawnBranch.PAIR_WEIGHTED);
        }
        if (spawnInside) {
            return new RespawnChoice(spawnPoint, RespawnBranch.SINGLE_SPAWN_POINT);
        }
        if (containerInside) {
            return new RespawnChoice(containerCentre, RespawnBranch.SINGLE_CONTAINER_CENTRE);
        }
        return new RespawnChoice(centre, RespawnBranch.STRUCTURE_CENTRE);
    }

    /**
     * Whether three points form a triangle worth sampling in.
     *
     * <p>A triangle is usable when no edge is longer than {@link #MAX_EDGE} and
     * its area reaches {@link #MIN_AREA}: a very long or very thin triangle
     * would scatter the boss across the whole structure instead of placing it
     * meaningfully.</p>
     */
    public static boolean triangleUsable(RespawnPoint first, RespawnPoint second, RespawnPoint third) {
        if (first == null || second == null || third == null) {
            return false;
        }
        return maxEdge(first, second, third) <= MAX_EDGE
                && triangleArea(first, second, third) >= MIN_AREA;
    }

    /** The longest of the three edges. */
    public static double maxEdge(RespawnPoint first, RespawnPoint second, RespawnPoint third) {
        return Math.max(first.distanceTo(second),
                Math.max(second.distanceTo(third), third.distanceTo(first)));
    }

    /** The area of the triangle, in the xz plane. */
    public static double triangleArea(RespawnPoint first, RespawnPoint second, RespawnPoint third) {
        return Math.abs((second.x() - first.x()) * (third.z() - first.z())
                - (third.x() - first.x()) * (second.z() - first.z())) / 2.0D;
    }

    /**
     * A uniformly distributed point inside the triangle.
     *
     * <p>The classic barycentric trick is used: two numbers in {@code [0, 1]}
     * are mirrored into the lower half when their sum exceeds one, which maps
     * the unit square onto the triangle without clumping. {@code bias} then
     * pulls the result towards {@code first}, so a structure whose recorded
     * spawn point is trusted more can keep its boss closer to it.</p>
     *
     * @param first the point the bias pulls towards
     * @param second the second corner
     * @param third the third corner
     * @param random the source of the two numbers
     * @param bias 0 = uniformly inside the triangle, 1 = exactly at {@code first}
     * @return the sampled point
     */
    public static RespawnPoint sampleInTriangle(RespawnPoint first, RespawnPoint second,
                                                RespawnPoint third, Random random,
                                                double bias) {
        Random source = random == null ? new Random(0L) : random;
        double firstWeight = source.nextDouble();
        double secondWeight = source.nextDouble();
        if (firstWeight + secondWeight > 1.0D) {
            firstWeight = 1.0D - firstWeight;
            secondWeight = 1.0D - secondWeight;
        }
        double alpha = 1.0D - firstWeight - secondWeight;
        RespawnPoint sample = new RespawnPoint(
                first.x() * alpha + second.x() * firstWeight + third.x() * secondWeight,
                first.y() * alpha + second.y() * firstWeight + third.y() * secondWeight,
                first.z() * alpha + second.z() * firstWeight + third.z() * secondWeight);
        return sample.towards(first, bias);
    }
}
