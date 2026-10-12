package com.structurereset.api;

/**
 * The per item behaviour settings of one reset core, as a pure value object.
 *
 * <p>This record is part of the Minecraft free logic layer: it imports nothing
 * from {@code net.minecraft} or {@code net.minecraftforge}, so the offline
 * tool chain can compile and unit test it without a Forge classpath. The
 * Minecraft facing registry that hands out these values lives in
 * {@code com.structurereset.core.ItemSettingsRegistry}, and the KubeJS binding
 * that writes them lives in
 * {@code com.structurereset.kubejs.StructureResetBindings}.</p>
 *
 * <p>A compact constructor validates the two numeric fields, so an instance can
 * never describe an item that destroys itself before its first use
 * ({@code maxUses >= 1}) or a negative cooldown
 * ({@code cooldownSeconds >= 0}, where {@code 0} means "no cooldown of its
 * own").</p>
 *
 * <p>The default {@code cooldownSeconds} is deliberately <em>not</em> baked in
 * here: the shipped default comes from
 * {@code StructureResetConfig#defaultCooldownSeconds()}, which is a
 * Minecraft/Forge facing accessor and therefore cannot be referenced from this
 * package. Callers that want the shipped default pass it in, most simply via
 * {@link #defaults(int)}.</p>
 *
 * @param maxUses how many successful resets one core pays for; at least 1
 * @param unbreakable when true the core is never damaged by a reset and works
 *        forever
 * @param cooldownSeconds the cooldown a reset triggered by this item starts,
 *        in seconds; {@code 0} means "use the definition's own cooldown"
 * @param teachConsumes when true a successful teaching costs one durability
 *        point; false keeps teaching completely free
 */
public record ItemSettings(int maxUses, boolean unbreakable, int cooldownSeconds, boolean teachConsumes) {

    /** The shipped {@code maxUses} of a freshly crafted core (its {@code durability(5)}). */
    public static final int DEFAULT_MAX_USES = 5;

    /** The shipped {@code unbreakable} value: a core is damageable by default. */
    public static final boolean DEFAULT_UNBREAKABLE = false;

    /** The shipped {@code teachConsumes} value: teaching is free by default. */
    public static final boolean DEFAULT_TEACH_CONSUMES = false;

    /**
     * Validates the two numeric fields.
     *
     * @throws IllegalArgumentException when {@code maxUses} is below 1 or
     *         {@code cooldownSeconds} is negative
     */
    public ItemSettings {
        if (maxUses < 1) {
            throw new IllegalArgumentException(
                    "maxUses must be at least 1, got " + maxUses);
        }
        if (cooldownSeconds < 0) {
            throw new IllegalArgumentException(
                    "cooldownSeconds must not be negative, got " + cooldownSeconds);
        }
    }

    /**
     * The shipped defaults with an explicit default cooldown.
     *
     * <p>{@code maxUses} is {@link #DEFAULT_MAX_USES}, {@code unbreakable} is
     * {@link #DEFAULT_UNBREAKABLE} and {@code teachConsumes} is
     * {@link #DEFAULT_TEACH_CONSUMES}.</p>
     *
     * @param cooldownSeconds the shipped default cooldown, in seconds
     * @return the default settings
     */
    public static ItemSettings defaults(int cooldownSeconds) {
        return new ItemSettings(DEFAULT_MAX_USES, DEFAULT_UNBREAKABLE, cooldownSeconds,
                DEFAULT_TEACH_CONSUMES);
    }

    /** A copy of these settings with another {@code maxUses} value. */
    public ItemSettings withMaxUses(int newMaxUses) {
        return new ItemSettings(newMaxUses, unbreakable, cooldownSeconds, teachConsumes);
    }

    /** A copy of these settings with another {@code unbreakable} value. */
    public ItemSettings withUnbreakable(boolean newUnbreakable) {
        return new ItemSettings(maxUses, newUnbreakable, cooldownSeconds, teachConsumes);
    }

    /** A copy of these settings with another {@code cooldownSeconds} value. */
    public ItemSettings withCooldownSeconds(int newCooldownSeconds) {
        return new ItemSettings(maxUses, unbreakable, newCooldownSeconds, teachConsumes);
    }

    /** A copy of these settings with another {@code teachConsumes} value. */
    public ItemSettings withTeachConsumes(boolean newTeachConsumes) {
        return new ItemSettings(maxUses, unbreakable, cooldownSeconds, newTeachConsumes);
    }
}
