package com.structurereset.api;

/**
 * One point in the world, as plain doubles.
 *
 * <p>The respawn maths is deliberately free of Minecraft types: the engine hands
 * in the reference points it gathered and gets a position back, which keeps the
 * whole degradation chain testable without a running game.</p>
 *
 * @param x the x coordinate
 * @param y the y coordinate
 * @param z the z coordinate
 */
public record RespawnPoint(double x, double y, double z) {

    /** The distance between this point and {@code other}. */
    public double distanceTo(RespawnPoint other) {
        double dx = x - other.x;
        double dy = y - other.y;
        double dz = z - other.z;
        return Math.sqrt(dx * dx + dy * dy + dz * dz);
    }

    /**
     * The weighted centre of three points.
     *
     * @param other the second point
     * @param third the third point
     * @param weightThis the weight of this point
     * @param weightOther the weight of {@code other}
     * @param weightThird the weight of {@code third}
     * @return the weighted centre
     */
    public RespawnPoint weightedWith(RespawnPoint other, RespawnPoint third,
                                     double weightThis, double weightOther, double weightThird) {
        double total = weightThis + weightOther + weightThird;
        if (total <= 0.0D) {
            return this;
        }
        return new RespawnPoint(
                (x * weightThis + other.x * weightOther + third.x * weightThird) / total,
                (y * weightThis + other.y * weightOther + third.y * weightThird) / total,
                (z * weightThis + other.z * weightOther + third.z * weightThird) / total);
    }

    /** The weighted centre of two points. */
    public RespawnPoint weightedWith(RespawnPoint other, double weightThis, double weightOther) {
        double total = weightThis + weightOther;
        if (total <= 0.0D) {
            return this;
        }
        return new RespawnPoint((x * weightThis + other.x * weightOther) / total,
                (y * weightThis + other.y * weightOther) / total,
                (z * weightThis + other.z * weightOther) / total);
    }

    /** This point moved towards {@code target} by {@code amount} (0 = here, 1 = there). */
    public RespawnPoint towards(RespawnPoint target, double amount) {
        double clamped = Math.max(0.0D, Math.min(1.0D, amount));
        return new RespawnPoint(x + (target.x - x) * clamped,
                y + (target.y - y) * clamped,
                z + (target.z - z) * clamped);
    }
}
