package com.structurereset.impl.action;

import com.structurereset.api.SpawnHeights;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.FlyingMob;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.ai.navigation.FlyingPathNavigation;
import net.minecraft.world.phys.AABB;

/**
 * Where a boss is really put into the world.
 *
 * <p>Ground bosses keep the old rule: the marked block when it can be stood in,
 * otherwise the nearest such block in a small cube around it.</p>
 *
 * <p>Flying bosses are handled differently, because a block that is "air, air"
 * says nothing about whether a wide body fits: their feet are lifted two blocks
 * into free air and the search keeps going up while the entity's real bounding
 * box does not fit. That is what stopped an Aether sun spirit - and every other
 * flyer - from being squeezed into the floor or the ceiling of the room it was
 * summoned into.</p>
 */
public final class BossSpawn {

    /** How far around the mark a stand-able block is searched for. */
    private static final int GROUND_SEARCH = 4;
    /** How far up and down that search may go. */
    private static final int GROUND_SEARCH_Y = 3;

    private BossSpawn() {
    }

    /**
     * True when the entity is one that belongs in the air.
     *
     * <p>Being a {@link FlyingMob} is the direct answer; a modded flyer often is
     * not one, but it navigates like a flyer, which is the second signal.</p>
     */
    public static boolean isFlyer(Entity entity) {
        if (entity == null) {
            return false;
        }
        if (entity instanceof FlyingMob) {
            return true;
        }
        return entity instanceof Mob mob && mob.getNavigation() instanceof FlyingPathNavigation;
    }

    /** Where an entity was put, and why, so a spawn can be traced from the log. */
    public record Placement(BlockPos pos, boolean lifted, int lift, boolean fits) {
    }

    /** The horizontal offsets tried around the mark when a column has no free air. */
    private static final int[][] RING = {{0, 0}, {2, 0}, {-2, 0}, {0, 2}, {0, -2}};

    /** The public room methods a boss mod may expose, in the order they are tried. */
    private static final java.util.List<String> ROOM_METHODS =
            java.util.List.of("closeRoom", "resealRoom", "resetRoom");

    /**
     * Asks a boss to put its own room back into its pre-fight state.
     *
     * <p>Some bosses cannot exist without their room: the Aether's Sun Spirit, for
     * example, belongs to a room that is sealed before the fight and unlocked when
     * it dies, and one spawned into an already unlocked room evaporates itself on
     * its first tick. Replacing the blocks by hand is not enough, because the open
     * or closed state also lives inside that mod's own room tracker. Aether's bosses
     * expose exactly the needed call as the public interface method
     * {@code closeRoom()}, so it is invoked through reflection: that keeps this
     * addon free of any compile time dependency on the other mod, and a boss that
     * has no such method is simply skipped instead of failing.</p>
     *
     * @param mob the boss that was just created and initialised
     */
    private static void prepareRoom(net.minecraft.world.entity.Mob mob) {
        for (String name : ROOM_METHODS) {
            try {
                java.lang.reflect.Method method = mob.getClass().getMethod(name);
                method.invoke(mob);
                com.structurereset.StructureResetMod.LOGGER.info(
                        "[structurereset] called {}() so the boss can reclaim its room", name);
                return;
            } catch (NoSuchMethodException missing) {
                // not a boss with a room API: try the next name
            } catch (Throwable failure) {
                // Expected for a boss whose room state lives in its own mod: the call
                // reaches the other mod's code and throws there. One line at debug is
                // enough - the reset itself is not affected, and the boss type is
                // remembered by the watch that follows, so this is not retried.
                com.structurereset.StructureResetMod.LOGGER.debug(
                        "[structurereset] the boss did not accept room preparation: {}",
                        failure.getCause() == null ? failure.toString() : failure.getCause().toString());
                return;
            }
        }
    }

    /**
     * The position the entity should be moved to before it is added to the level.
     *
     * @param level the level, required
     * @param entity the entity that is about to be spawned, required
     * @param target the block the player marked or the definition names
     * @return the position to place the entity at, or the target as a last resort
     */
    public static BlockPos spawnPos(ServerLevel level, Entity entity, BlockPos target) {
        return place(level, entity, target).pos();
    }

    /**
     * The placement of the entity, with the reason it was chosen.
     *
     * <p>A flyer is lifted into free air, and a small ring around the mark is tried
     * as well, so a thin ceiling or a pillar next to the mark does not force the
     * boss into a wall - an entity that ends up inside blocks is pushed out by the
     * collision resolver, which is how a boss can be flung far away from the place
     * it was summoned at. The last resort is still the target, but the record says
     * whether the position really fits, so the caller can log it.</p>
     */
    public static Placement place(ServerLevel level, Entity entity, BlockPos target) {
        if (level == null || entity == null || target == null) {
            return new Placement(target, false, 0, false);
        }
        // Every boss is first looked for in free air above the mark. A boss that was
        // meant to fly is then not squeezed into the floor, and a ground boss simply
        // drops the two blocks the lift put it at. What decides is the entity's own
        // bounding box: a low room falls back to the ground rule below.
        for (int lift : SpawnHeights.flyerLifts()) {
            for (int[] offset : RING) {
                BlockPos candidate = target.offset(offset[0], lift, offset[1]);
                if (fits(level, entity, candidate)) {
                    return new Placement(candidate, true, lift, true);
                }
            }
        }
        BlockPos ground = groundSpot(level, target);
        return new Placement(ground, false, 0, fits(level, entity, ground));
    }

    /**
     * Initialises a freshly created boss the way vanilla does.
     *
     * <p>{@code EntityType#create} builds the entity but never runs
     * {@code finalizeSpawn}, which is where a modded boss registers its boss bar,
     * rolls its attributes and sets its own fight state. A Sun Spirit spawned
     * without it is not a boss at all, and it removes itself again within seconds -
     * which is exactly what "the effect played but there was no boss" looked like.
     * {@code MobSpawnType#COMMAND} is the reason vanilla's own {@code /summon}
     * passes, so a reset initialises its boss the same way a command would.</p>
     *
     * @param level the level, required
     * @param entity the entity that was just created, required
     * @param pos the position the entity is placed at
     */
    public static void initialise(ServerLevel level, Entity entity, BlockPos pos) {
        if (level == null || entity == null || pos == null) {
            return;
        }
        if (!(entity instanceof Mob mob)) {
            return;
        }
        try {
            // Never let the vanilla "far from any player" rule remove a boss the
            // player just paid for: a summoned boss stays until it is fought.
            mob.setPersistenceRequired();
            mob.finalizeSpawn(level, level.getCurrentDifficultyAt(pos),
                    net.minecraft.world.entity.MobSpawnType.COMMAND, null, null);
            net.minecraft.resources.ResourceLocation typeId =
                    net.minecraftforge.registries.ForgeRegistries.ENTITY_TYPES.getKey(mob.getType());
            if (typeId == null || !com.structurereset.impl.UnrestorableBosses.get(level)
                    .contains(typeId.toString())) {
                prepareRoom(mob);
            }
        } catch (Throwable failure) {
            // A boss that refuses to be finalised is still spawned: initialisation is
            // a best effort and must never turn a reset into a failure.
            com.structurereset.StructureResetMod.LOGGER.warn(
                    "[structurereset] could not initialise a spawned boss", failure);
        }
    }

    /**
     * A position the entity can stand in: the target itself when both the block
     * and the block above it are air, otherwise the nearest such position inside
     * a small cube around the target, and the target itself as the last resort.
     */
    public static BlockPos groundSpot(ServerLevel level, BlockPos target) {
        if (level == null || target == null) {
            return target;
        }
        if (isFree(level, target)) {
            return target;
        }
        BlockPos best = null;
        double bestDistance = Double.MAX_VALUE;
        for (int dx = -GROUND_SEARCH; dx <= GROUND_SEARCH; dx++) {
            for (int dz = -GROUND_SEARCH; dz <= GROUND_SEARCH; dz++) {
                for (int dy = -GROUND_SEARCH_Y; dy <= GROUND_SEARCH_Y; dy++) {
                    BlockPos candidate = target.offset(dx, dy, dz);
                    if (!isFree(level, candidate)) {
                        continue;
                    }
                    double distance = (double) (dx * dx) + (double) (dy * dy) + (double) (dz * dz);
                    if (distance < bestDistance) {
                        bestDistance = distance;
                        best = candidate;
                    }
                }
            }
        }
        return best == null ? target : best;
    }

    /** True when the block and the block above it are both air. */
    public static boolean isFree(ServerLevel level, BlockPos pos) {
        if (level == null || pos == null) {
            return false;
        }
        return level.getBlockState(pos).getCollisionShape(level, pos).isEmpty()
                && level.getBlockState(pos.above()).getCollisionShape(level, pos.above()).isEmpty();
    }

    /**
     * True when the entity really fits at {@code pos}: its own bounding box, moved
     * there, collides with nothing.
     */
    private static boolean fits(ServerLevel level, Entity entity, BlockPos pos) {
        AABB moved = entity.getBoundingBox().move(
                pos.getX() + 0.5D - entity.getX(),
                (double) pos.getY() - entity.getY(),
                pos.getZ() + 0.5D - entity.getZ());
        return level.noCollision(entity, moved);
    }
}
