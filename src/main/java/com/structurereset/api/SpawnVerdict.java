package com.structurereset.api;

/**
 * Decides what a spawned boss that did not stay means, free of Minecraft.
 *
 * <p>The two ways a boss can fail to stay look completely different once the
 * numbers are read: one that was <em>killed</em> is gone while no longer alive,
 * and it stays a perfectly normal boss. One that <em>discards itself</em> is gone
 * while still alive and at full health, and it does so almost immediately - the
 * Aether's Sun Spirit removes itself one tick after it is placed, at 8500 health,
 * because it belongs to a boss room that has already been opened. Only the second
 * kind is beyond a generic addon's reach, so only the second kind is remembered.</p>
 */
public final class SpawnVerdict {

    /** How soon after a spawn a removal still counts as "it refused to stay". */
    public static final int REFUSED_LIFE_TICKS = 60;

    private SpawnVerdict() {
    }

    /**
     * True when a boss that was just spawned refused to stay.
     *
     * @param ticksAlive how many ticks it lived, negative when unknown
     * @param alive whether it was still alive when it was removed
     * @return true when the removal looks like the boss discarding itself
     */
    public static boolean refusedToStay(long ticksAlive, boolean alive) {
        return alive && ticksAlive >= 0L && ticksAlive <= REFUSED_LIFE_TICKS;
    }
}
