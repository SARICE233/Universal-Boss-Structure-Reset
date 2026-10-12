package com.structurereset.api;

import java.util.Arrays;
import java.util.List;

/**
 * The heights a boss is tried at, free of Minecraft so it can be tested.
 *
 * <p>A flying boss is never put on the marked block: its feet belong in free
 * air, two blocks above the mark, and the search walks further up from there.
 * The order is a preference, not a guarantee - the caller still has to prove
 * with the entity's own bounding box that the height is free.</p>
 */
public final class SpawnHeights {

    /** The first height above the mark a flying boss is tried at. */
    public static final int FLYER_LIFT = 2;

    /** How many further heights upward the flyer search may try. */
    public static final int FLYER_SEARCH = 6;

    private SpawnHeights() {
    }

    /**
     * The lifts above the marked block that a flying boss is tried at, nearest
     * first, starting at {@link #FLYER_LIFT}.
     *
     * @param lift the first lift, values below 1 are treated as 1
     * @param search how many further lifts to add, negative values add none
     * @return the lifts in the order they must be tried
     */
    public static List<Integer> flyerLifts(int lift, int search) {
        int first = Math.max(1, lift);
        int steps = Math.max(0, search);
        Integer[] out = new Integer[steps + 1];
        for (int index = 0; index <= steps; index++) {
            out[index] = first + index;
        }
        return Arrays.asList(out);
    }

    /** The lifts above the mark a flying boss is tried at, with the defaults. */
    public static List<Integer> flyerLifts() {
        return flyerLifts(FLYER_LIFT, FLYER_SEARCH);
    }
}
