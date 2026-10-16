package com.structurereset.impl;

import com.mojang.logging.LogUtils;
import com.structurereset.config.StructureResetConfig;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import org.slf4j.Logger;

/**
 * Reports what became of a boss we spawned.
 *
 * <p>A reset can log a perfect spawn and the player can still see nothing. Two
 * very different things look identical in the log at that point, so every spawned
 * boss is watched tick by tick: one that is removed is reported immediately, with
 * the removal reason, and one that survives is reported after five seconds with
 * how far it moved. That is what separates "the mod discarded it" from "it flew
 * away" from "the mod never really spawned it".</p>
 */
public final class SpawnWatcher {

    private static final Logger LOGGER = LogUtils.getLogger();

    /** How long after the spawn the surviving boss is reported. */
    private static final int DELAY_TICKS = 100;

    /** How far a boss may move before the move is worth telling the players about. */
    private static final double MOVED_LIMIT = 4.0D;

    /** How many watches are kept at most, in both indexes. */
    private static final int MAX_WATCHED = 64;

    /**
     * One watched spawn. The entity itself is held on purpose: once it is removed
     * the level no longer resolves its uuid, and the removal reason - the whole
     * point of the watch - would be lost with it.
     */
    private record Watch(UUID uuid, Entity entity, String entityId, String dimension,
                         double x, double y, double z,
                         long spawnedAt, long reportAt, BlockPos marked) {
    }

    /**
     * The watches by uuid, so an entity leaving the level can be recognised.
     *
     * <p>Kept in step with {@link #WATCHED}: the same bound, the same removals.
     * A watch that only lived here would pin its entity for the lifetime of the
     * server, which is a leak that grows with every boss ever summoned.</p>
     */
    private static final Map<UUID, Watch> BY_UUID = new LinkedHashMap<>();

    private static final Map<UUID, Watch> WATCHED = new LinkedHashMap<>();

    private SpawnWatcher() {
    }

    /** Starts watching one freshly spawned boss. */
    public static void watch(ServerLevel level, Entity entity, BlockPos marked) {
        if (level == null || entity == null) {
            return;
        }
        long now = level.getGameTime();
        Watch watch = new Watch(entity.getUUID(), entity, entity.getType().toString(),
                level.dimension().location().toString(),
                entity.getX(), entity.getY(), entity.getZ(),
                now, now + DELAY_TICKS, marked);
        WATCHED.put(entity.getUUID(), watch);
        BY_UUID.put(entity.getUUID(), watch);
        while (WATCHED.size() > MAX_WATCHED) {
            UUID oldest = WATCHED.keySet().iterator().next();
            WATCHED.remove(oldest);
            BY_UUID.remove(oldest);
        }
    }

    /**
     * Reports a watched boss that is leaving the level, with the reason it is
     * leaving and the health it had. This is the hook that says why a summoned
     * boss does not stay: the level itself hands the reason over here.
     *
     * @param entity the entity that is being removed
     * @return true when the entity was one of ours and the report was written
     */
    public static boolean onRemoved(Entity entity) {
        if (entity == null) {
            return false;
        }
        Watch watch = BY_UUID.remove(entity.getUUID());
        WATCHED.remove(entity.getUUID());
        if (watch == null) {
            return false;
        }
        long life = entity.level() instanceof ServerLevel level
                ? level.getGameTime() - watch.spawnedAt() : -1L;
        LOGGER.info("[{}] spawn check: {} was removed at {} after {} tick(s), reason={}, alive={}, health={}",
                StructureResetConfig.MOD_ID, watch.entityId(), entity.blockPosition(), life,
                String.valueOf(entity.getRemovalReason()), entity.isAlive(),
                entity instanceof net.minecraft.world.entity.LivingEntity living
                        ? living.getHealth() : -1.0F);
        if (entity.level() instanceof ServerLevel level
                && com.structurereset.api.SpawnVerdict.refusedToStay(life, entity.isAlive())) {
            // It was removed while alive, almost at once: it discarded itself. A mod
            // that owns such a boss keeps the state inside its own room machinery, so
            // the type is remembered and no reset will pretend to summon it again.
            if (UnrestorableBosses.get(level).remember(watch.entityId())) {
                LOGGER.info("[{}] {} discards itself when summoned and was remembered as unrestorable",
                        StructureResetConfig.MOD_ID, watch.entityId());
            }
            announce(level, watch, Component.translatable("structurereset.message.spawn.lost",
                    watch.entityId(), String.valueOf(entity.getRemovalReason())));
        }
        return true;
    }

    /** Checks every watched boss; a removed one is reported at once. */
    public static void tick(MinecraftServer server) {
        if (server == null || WATCHED.isEmpty()) {
            return;
        }
        List<UUID> finished = new ArrayList<>();
        for (Watch watch : WATCHED.values()) {
            ServerLevel level = findLevel(server, watch.dimension());
            if (level == null) {
                finished.add(watch.uuid());
                continue;
            }
            Entity entity = watch.entity();
            if (entity.isRemoved()) {
                finished.add(watch.uuid());
                LOGGER.info("[{}] spawn check: {} was gone {} tick(s) after it was spawned at {}, reason={}, alive={}",
                        StructureResetConfig.MOD_ID, watch.entityId(),
                        level.getGameTime() - watch.spawnedAt(), watch.marked().toShortString(),
                        String.valueOf(entity.getRemovalReason()), entity.isAlive());
                continue;
            }
            if (level.getGameTime() >= watch.reportAt()) {
                finished.add(watch.uuid());
                reportSurvivor(level, watch, entity);
            }
        }
        for (UUID uuid : finished) {
            WATCHED.remove(uuid);
        }
        // BY_UUID holds the same watches, so it has to forget them in exactly the
        // same place; the bound below is the second safety net for the case where
        // an entity is removed without ever passing through onRemoved().
        for (UUID uuid : finished) {
            BY_UUID.remove(uuid);
        }
        while (BY_UUID.size() > MAX_WATCHED) {
            UUID oldest = BY_UUID.keySet().iterator().next();
            BY_UUID.remove(oldest);
            WATCHED.remove(oldest);
        }
    }

    /** Forgets every watch; used when the server stops. */
    public static void clear() {
        WATCHED.clear();
        BY_UUID.clear();
    }

    private static void reportSurvivor(ServerLevel level, Watch watch, Entity entity) {
        double dx = entity.getX() - watch.x();
        double dy = entity.getY() - watch.y();
        double dz = entity.getZ() - watch.z();
        double moved = Math.sqrt(dx * dx + dy * dy + dz * dz);
        LOGGER.info("[{}] spawn check: {} survived and moved {} block(s) from {} to {} (marked {})",
                StructureResetConfig.MOD_ID, watch.entityId(), Math.round(moved),
                watch.marked().toShortString(), entity.blockPosition().toShortString(),
                watch.marked().toShortString());
        if (moved <= MOVED_LIMIT) {
            return;
        }
        for (ServerPlayer player : level.players()) {
            if (player.distanceToSqr(watch.x(), watch.y(), watch.z()) < 64.0D * 64.0D) {
                player.displayClientMessage(Component.translatable(
                        "structurereset.message.spawn.moved", watch.entityId(),
                        entity.blockPosition().toShortString()), false);
            }
        }
    }

    /** Tells the nearby players who carry a reset core what happened to the boss. */
    private static void announce(ServerLevel level, Watch watch, Component message) {
        for (ServerPlayer player : level.players()) {
            if (player.distanceToSqr(watch.x(), watch.y(), watch.z()) > 64.0D * 64.0D) {
                continue;
            }
            if (player.getInventory().hasAnyMatching(
                    stack -> stack.is(com.structurereset.registry.ModItems.STRUCTURE_RESET_CORE.get()))) {
                player.displayClientMessage(message, false);
            }
        }
    }

    /** The level with this dimension id, or null when it is not loaded. */
    private static ServerLevel findLevel(MinecraftServer server, String dimension) {
        for (ServerLevel level : server.getAllLevels()) {
            if (level.dimension().location().toString().equals(dimension)) {
                return level;
            }
        }
        return null;
    }
}
