package com.structurereset.api;

/**
 * Which branch of the respawn degradation chain produced a position.
 *
 * <p>The chain runs from the most precise choice to the most defensive one, so
 * an operator reading a log line always knows how a boss ended up where it
 * did.</p>
 */
public enum RespawnBranch {

    /** All three reference points were inside and the triangle was usable. */
    TRIANGLE_SAMPLE,
    /** All three were inside but the triangle was degenerate: weighted centre. */
    WEIGHTED_CENTROID,
    /** Two of the three were inside: weighted centre of those two. */
    PAIR_WEIGHTED,
    /** Only the recorded spawn point was inside: use it as it is. */
    SINGLE_SPAWN_POINT,
    /** Only the container centre was inside: use it as it is. */
    SINGLE_CONTAINER_CENTRE,
    /** Nothing usable was inside: the structure's bounding box centre. */
    STRUCTURE_CENTRE
}
