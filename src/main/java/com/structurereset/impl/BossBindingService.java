package com.structurereset.impl;

import com.mojang.logging.LogUtils;
import com.structurereset.StructureResetMod;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraftforge.registries.ForgeRegistries;
import org.slf4j.Logger;

/**
 * Binding a boss to the structure it was generated in - "preserve at
 * generation, confirm at death".
 *
 * <p>When a boss appears inside a structure its UUID, its entity type, its exact
 * spawn position and facing and the structure it belongs to are written to
 * {@link BossRegistryData}. Its death is written to the very same record, so
 * the registry always knows which structures still hold an unbeaten boss and
 * which ones have a beaten boss waiting to come back.</p>
 *
 * <p>The spawn position is kept because it is the most reliable respawn
 * reference: a boss can be dragged out of its arena or pushed into a wall while
 * it is being fought, but where it first appeared is where it belongs.</p>
 */
public final class BossBindingService {

    private static final Logger LOGGER = LogUtils.getLogger();

    /** How far a death may be from a recorded spawn point to still match it. */
    private static final double MATCH_RADIUS = 96.0D;

    private BossBindingService() {
    }

    /**
     * Remembers a boss that was just added to the world.
     *
     * <p>Without a structure at its position there is nothing to bind: a boss
     * fought in a hand built arena is registered through the teaching paths
     * instead. An already known UUID is never restated, so the original spawn
     * position survives.</p>
     */
    public static void bind(ServerLevel level, LivingEntity entity, String entityId, String evidence) {
        if (level == null || entity == null || entityId == null || entityId.isEmpty()) {
            return;
        }
        BlockPos pos = entity.blockPosition();
        Set<String> structureIds = ResetEngine.structureIdsAt(level, pos);
        if (structureIds.isEmpty()) {
            return;
        }
        BossRegistryData data = BossRegistryData.get(level);
        String uuid = entity.getUUID().toString();
        if (data.byUuid(uuid).isPresent()) {
            return;
        }
        data.put(new BossRegistryData.BossRecord(uuid, entityId, List.copyOf(structureIds),
                level.dimension().location().toString(), pos.getX(), pos.getY(), pos.getZ(),
                entity.getYRot(), entity.getXRot(), level.getGameTime(),
                0L, 0, 0, 0, "", false, 0, evidence));
        LOGGER.info("[{}] boss bound: {} ({}) to {} at {} [{}]",
                StructureResetMod.MODID, entityId, uuid, structureIds, pos, evidence);
    }

    /**
     * Confirms the death of a boss that had been bound.
     *
     * <p>Only a boss the registry already knows is touched, so a random strong
     * mob that happens to die inside a structure is never promoted to a boss.</p>
     */
    public static void confirmDeath(ServerLevel level, LivingEntity entity, DamageSource source) {
        if (level == null || entity == null) {
            return;
        }
        BossRegistryData data = BossRegistryData.get(level);
        String uuid = entity.getUUID().toString();
        Optional<BossRegistryData.BossRecord> found = data.byUuid(uuid);
        String how = "uuid";
        if (found.isEmpty()) {
            // A boss a reset spawned may never have been bound. Matching it by
            // type and place keeps a kill from being lost, which would otherwise
            // leave the structure permanently blocked by the defeated gate.
            ResourceLocation typeId = ForgeRegistries.ENTITY_TYPES.getKey(entity.getType());
            BlockPos death = entity.blockPosition();
            found = data.closestLiving(typeId == null ? null : typeId.toString(),
                    level.dimension().location().toString(),
                    death.getX(), death.getY(), death.getZ(), MATCH_RADIUS);
            how = "nearest " + MATCH_RADIUS + " blocks";
            if (found.isEmpty()) {
                return;
            }
        }
        String killer = source != null && source.getEntity() instanceof Player player
                ? player.getUUID().toString() : "";
        BlockPos pos = entity.blockPosition();
        data.put(found.get().defeatedBy(killer, level.getGameTime(),
                pos.getX(), pos.getY(), pos.getZ()));
        LOGGER.info("[{}] boss defeated: {} ({}) at {} by {} (matched by {})",
                StructureResetMod.MODID, found.get().bossEntityType(), uuid, pos,
                killer.isEmpty() ? "unknown" : killer, how);
    }

    /**
     * True when the registry knows a boss of this structure that has not been
     * beaten yet.
     *
     * <p>This is the "do not summon a second boss next to the first one" gate.
     * It only answers true for a structure the mod has actually observed, so a
     * never visited structure can never be locked out of a reset.</p>
     */
    /**
     * The boss of this structure that is still standing, as an entity id.
     *
     * <p>The decision is made from the world, not from a stored flag: a living
     * entity that passes the boss gate inside (or just around) the structure is
     * the boss, and its absence means the structure is ready to be reset. That
     * is deliberately robust - {@code /kill}, a command, another mod or a plain
     * fight all remove the entity, and every one of them is seen. A stored
     * "defeated" flag could be missed and would then block the structure
     * forever, which is exactly what used to happen.</p>
     *
     * @param level the level of the player, required
     * @param pos the position of the player, required
     * @return the entity id of the living boss, or empty when none stands here
     */
    public static Optional<String> aliveBossId(ServerLevel level, BlockPos pos) {
        if (level == null || pos == null) {
            return Optional.empty();
        }
        Optional<net.minecraft.world.level.levelgen.structure.BoundingBox> box =
                ResetEngine.structureBoxAt(level, pos);
        if (box.isEmpty()) {
            return Optional.empty();
        }
        // A small margin keeps a boss that was shoved just outside its arena from
        // being ignored, without scanning the whole level.
        net.minecraft.world.level.levelgen.structure.BoundingBox area = box.get().inflatedBy(16);
        net.minecraft.world.phys.AABB bounds = new net.minecraft.world.phys.AABB(
                area.minX(), area.minY(), area.minZ(),
                area.maxX() + 1.0D, area.maxY() + 1.0D, area.maxZ() + 1.0D);
        for (LivingEntity entity : level.getEntitiesOfClass(LivingEntity.class, bounds)) {
            if (!entity.isAlive() || !LearnedAnchorService.isBossCandidate(entity)) {
                continue;
            }
            ResourceLocation id = ForgeRegistries.ENTITY_TYPES.getKey(entity.getType());
            return Optional.of(id == null ? "" : id.toString());
        }
        return Optional.empty();
    }

    /**
     * The boss a reset at this position should bring back: the most recently
     * beaten one of the structures there that has not been respawned yet.
     *
     * @param level the level of the reset
     * @param pos the position the reset happens at
     * @return the record awaiting a respawn, or empty
     */
    public static Optional<BossRegistryData.BossRecord> awaitingRespawn(ServerLevel level, BlockPos pos) {
        if (level == null || pos == null) {
            return Optional.empty();
        }
        Set<String> structureIds = ResetEngine.structureIdsAt(level, pos);
        if (structureIds.isEmpty()) {
            return Optional.empty();
        }
        return BossRegistryData.get(level).awaitingRespawn(
                level.dimension().location().toString(), structureIds);
    }
}
