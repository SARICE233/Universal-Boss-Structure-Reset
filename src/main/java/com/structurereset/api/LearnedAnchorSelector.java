package com.structurereset.api;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * Picks the learned anchor that applies to a reset attempt, completely free of
 * Minecraft.
 *
 * <p>The order of the rules is what makes the teaching feature predictable:</p>
 *
 * <ol>
 *   <li>only anchors of the very same dimension are considered;</li>
 *   <li>an anchor that claims one of the entered structure ids wins over an
 *       anchor that only happens to be nearby, but only while its own radius
 *       contains the position; of those the nearest wins, and recency breaks a
 *       tie, because the player walks to the place the boss appeared at;</li>
 *   <li>otherwise the nearest anchor whose radius contains the position wins;
 *       ties are broken by recency and finally by the id, so the result never
 *       depends on the iteration order of the collection.</li>
 * </ol>
 */
public final class LearnedAnchorSelector {

    private LearnedAnchorSelector() {
    }

    /**
     * The anchor that applies to a reset at ({@code x}, {@code y}, {@code z}).
     *
     * @param anchors every learned anchor, null and empty yield nothing
     * @param dimension the dimension the reset happens in
     * @param structureIds the structure ids reported at the position, may be null
     * @param x the position x coordinate
     * @param y the position y coordinate
     * @param z the position z coordinate
     * @return the selected anchor, or empty when none applies
     */
    public static Optional<LearnedAnchor> select(Collection<LearnedAnchor> anchors, String dimension,
                                                 Collection<String> structureIds,
                                                 int x, int y, int z) {
        if (anchors == null || anchors.isEmpty()) {
            return Optional.empty();
        }
        if (dimension == null || dimension.isBlank()) {
            return Optional.empty();
        }

        List<LearnedAnchor> candidates = new ArrayList<>();
        for (LearnedAnchor anchor : anchors) {
            if (anchor != null && dimension.equals(anchor.dimension())) {
                candidates.add(anchor);
            }
        }
        if (candidates.isEmpty()) {
            return Optional.empty();
        }

        Set<String> wanted = new HashSet<>();
        if (structureIds != null) {
            for (String structureId : structureIds) {
                if (structureId != null && !structureId.isBlank()) {
                    wanted.add(structureId);
                }
            }
        }

        LearnedAnchor best = null;
        double bestDistance = 0.0D;
        if (!wanted.isEmpty()) {
            // note: the fallback below reuses both variables, so it starts clean
            for (LearnedAnchor candidate : candidates) {
                if (!intersects(candidate.structureIds(), wanted)) {
                    continue;
                }
                // A shared structure id is not enough on its own. A record describes
                // one place, but a structure id can belong to something enormous that
                // wraps many places - a sky wide cloud layer, for instance - and a
                // record written for a boss 141 blocks away would then answer for a
                // dungeon it has nothing to do with. Only the record's own radius
                // decides, and no tolerance widens it.
                double radius = candidate.radius();
                double distance = squaredDistance(candidate, x, y, z);
                if (!(radius > 0.0D) || distance > radius * radius) {
                    continue;
                }
                // The player walks to the place the boss appeared at before resetting,
                // so their own position is the strongest hint available: of the records
                // that cover it, the nearest wins and recency only breaks a tie.
                if (best == null || winsByDistance(candidate, best, distance, bestDistance)) {
                    best = candidate;
                    bestDistance = distance;
                }
            }
            if (best != null) {
                return Optional.of(best);
            }
        }

        best = null;
        bestDistance = 0.0D;
        for (LearnedAnchor candidate : candidates) {
            double radius = candidate.radius();
            if (!(radius > 0.0D)) {
                // defensive: a non-positive radius never contains anything
                continue;
            }
            double distance = squaredDistance(candidate, x, y, z);
            if (distance > radius * radius) {
                continue;
            }
            if (best == null || winsByDistance(candidate, best, distance, bestDistance)) {
                best = candidate;
                bestDistance = distance;
            }
        }
        return Optional.ofNullable(best);
    }

    // ------------------------------------------------------------- helpers

    private static boolean intersects(List<String> learned, Set<String> wanted) {
        if (learned == null || learned.isEmpty()) {
            return false;
        }
        for (String structureId : learned) {
            if (structureId != null && wanted.contains(structureId)) {
                return true;
            }
        }
        return false;
    }

    private static double squaredDistance(LearnedAnchor anchor, int x, int y, int z) {
        double dx = (double) x - anchor.x();
        double dy = (double) y - anchor.y();
        double dz = (double) z - anchor.z();
        return dx * dx + dy * dy + dz * dz;
    }

    private static boolean winsByRecency(LearnedAnchor candidate, LearnedAnchor current) {
        if (candidate.learnedAtGameTime() != current.learnedAtGameTime()) {
            return candidate.learnedAtGameTime() > current.learnedAtGameTime();
        }
        return candidate.id().compareTo(current.id()) < 0;
    }

    private static boolean winsByDistance(LearnedAnchor candidate, LearnedAnchor current,
                                          double candidateDistance, double currentDistance) {
        if (candidateDistance != currentDistance) {
            return candidateDistance < currentDistance;
        }
        return winsByRecency(candidate, current);
    }
}
