package com.structurereset.api;

/**
 * Pure cooldown arithmetic for one reset scope.
 *
 * <p>A non-positive cooldown means "always ready". A clock that jumped backwards
 * (now &lt; last) restarts the cooldown from {@code last}, so a reset can never
 * be granted twice by moving the clock.</p>
 */
public final class CooldownPolicy {

    private CooldownPolicy() {
    }

    /** True when the cooldown of {@code cooldownSeconds} has elapsed. */
    public static boolean ready(long now, long last, int cooldownSeconds) {
        return remainingMillis(now, last, cooldownSeconds) == 0L;
    }

    /**
     * True when a two-click confirmation that was armed at {@code armedAt} is
     * still inside its {@code windowSeconds} window at {@code now}.
     *
     * <p>Both timestamps are milliseconds of the same clock (the arm side
     * converts the world game time with {@code gameTime * 50L}). A non-positive
     * window and an unset ({@code <= 0}) arming time can never confirm, and a
     * clock that jumped backwards does not either: only
     * {@code armedAt <= now <= armedAt + windowSeconds * 1000} counts.</p>
     */
    public static boolean withinWindow(long now, long armedAt, int windowSeconds) {
        if (windowSeconds <= 0) {
            return false;
        }
        if (armedAt <= 0L) {
            return false;
        }
        return now >= armedAt && (now - armedAt) <= (long) windowSeconds * 1000L;
    }

    /** Milliseconds left before the next reset, never negative. */
    public static long remainingMillis(long now, long last, int cooldownSeconds) {
        if (cooldownSeconds <= 0) {
            return 0L;
        }
        long cooldown = cooldownSeconds * 1000L;
        if (now < last) {
            return cooldown;
        }
        long elapsed = now - last;
        return Math.max(0L, cooldown - elapsed);
    }
}
