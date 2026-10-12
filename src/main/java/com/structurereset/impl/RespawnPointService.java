package com.structurereset.impl;

import com.structurereset.api.RespawnBranch;
import com.structurereset.api.RespawnChoice;
import com.structurereset.api.RespawnPlanner;
import com.structurereset.api.RespawnPoint;
import java.util.Optional;
import java.util.Random;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.RandomizableContainerBlockEntity;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.levelgen.structure.BoundingBox;

/**
 * Turns the three reference points of a structure into a recommended respawn
 * position - the fallback the crosshair path uses when the player has not
 * aimed anywhere usable.
 *
 * <p>The heavy decision is the pure {@link RespawnPlanner}; this class only
 * gathers what the world knows (the recorded spawn point of the boss, the
 * centre of the structure's containers, the player) and refuses any position a
 * mob cannot actually stand on. Nothing here is called unless the aimed spot
 * was unusable, so the player's own choice always wins.</p>
 */
public final class RespawnPointService {

    /** How far up and down a safe landing spot is searched for. */
    public static final int SAFE_SEARCH = 5;

    /**
     * A recommended position and the branch that produced it.
     *
     * @param position the position to use
     * @param branch how it was chosen, for the log and the message
     */
    public record Recommendation(BlockPos position, RespawnBranch branch) {
    }

    private RespawnPointService() {
    }

    /** The recommended respawn position for a reset at {@code origin}, if any. */
    public static Optional<Recommendation> recommend(ServerLevel level, ServerPlayer player,
                                                     BlockPos origin) {
        if (level == null || origin == null) {
            return Optional.empty();
        }
        Optional<BoundingBox> box = ResetEngine.structureBoxAt(level, origin);
        if (box.isEmpty()) {
            return Optional.empty();
        }
        BoundingBox area = box.get();
        RespawnPoint centre = centreOf(area);

        Optional<BossRegistryData.BossRecord> record = BossBindingService.awaitingRespawn(level, origin);
        RespawnPoint spawnPoint = record.map(found -> new RespawnPoint(found.spawnX() + 0.5D,
                found.spawnY(), found.spawnZ() + 0.5D)).orElse(centre);
        boolean spawnInside = record
                .map(found -> area.isInside(new BlockPos(found.spawnX(), found.spawnY(), found.spawnZ())))
                .orElse(false);

        Optional<RespawnPoint> containers = containerCentre(level, area);
        RespawnPoint containerPoint = containers.orElse(centre);
        boolean containerInside = containers.isPresent();

        RespawnPoint playerPoint = new RespawnPoint(player.getX(), player.getY(), player.getZ());
        boolean playerInside = area.isInside(player.blockPosition());

        Random random = new Random(level.getRandom().nextLong());
        RespawnChoice choice = RespawnPlanner.plan(spawnPoint, spawnInside, containerPoint,
                containerInside, playerPoint, playerInside, centre, random);

        // The triangle branch may scatter a boss onto lava or into a wall, so it
        // is resampled a few times before the chain degrades.
        for (int attempt = 0; attempt <= RespawnPlanner.MAX_RESAMPLES; attempt++) {
            Optional<BlockPos> safe = findSafeSpot(level, choice.point());
            if (safe.isPresent()) {
                return Optional.of(new Recommendation(safe.get(), choice.branch()));
            }
            if (choice.branch() != RespawnBranch.TRIANGLE_SAMPLE) {
                break;
            }
            choice = RespawnPlanner.plan(spawnPoint, spawnInside, containerPoint, containerInside,
                    playerPoint, playerInside, centre, random);
        }
        // Then the weighted centre, and finally the middle of the structure.
        Optional<BlockPos> weighted = findSafeSpot(level,
                spawnPoint.weightedWith(containerPoint, playerPoint, 0.5D, 0.3D, 0.2D));
        if (weighted.isPresent()) {
            return Optional.of(new Recommendation(weighted.get(), RespawnBranch.WEIGHTED_CENTROID));
        }
        Optional<BlockPos> middle = findSafeSpot(level, centre);
        return middle.map(found -> new Recommendation(found, RespawnBranch.STRUCTURE_CENTRE));
    }

    /** The geometric centre of a bounding box. */
    public static RespawnPoint centreOf(BoundingBox area) {
        return new RespawnPoint((area.minX() + area.maxX()) / 2.0D + 0.5D,
                (area.minY() + area.maxY()) / 2.0D,
                (area.minZ() + area.maxZ()) / 2.0D + 0.5D);
    }

    /** The average position of the containers inside {@code area}, if there are any. */
    public static Optional<RespawnPoint> containerCentre(ServerLevel level, BoundingBox area) {
        double sumX = 0.0D;
        double sumY = 0.0D;
        double sumZ = 0.0D;
        int count = 0;
        for (BlockEntity blockEntity : blockEntitiesIn(level, area)) {
            if (!(blockEntity instanceof RandomizableContainerBlockEntity)) {
                continue;
            }
            BlockPos pos = blockEntity.getBlockPos();
            sumX += pos.getX() + 0.5D;
            sumY += pos.getY();
            sumZ += pos.getZ() + 0.5D;
            count++;
        }
        if (count == 0) {
            return Optional.empty();
        }
        return Optional.of(new RespawnPoint(sumX / count, sumY / count, sumZ / count));
    }

    /** The block entities of every loaded chunk the area touches. */
    private static Iterable<BlockEntity> blockEntitiesIn(ServerLevel level, BoundingBox area) {
        java.util.List<BlockEntity> found = new java.util.ArrayList<>();
        for (int chunkX = area.minX() >> 4; chunkX <= area.maxX() >> 4; chunkX++) {
            for (int chunkZ = area.minZ() >> 4; chunkZ <= area.maxZ() >> 4; chunkZ++) {
                if (!level.hasChunk(chunkX, chunkZ)) {
                    continue;
                }
                LevelChunk chunk = level.getChunk(chunkX, chunkZ);
                for (BlockEntity blockEntity : chunk.getBlockEntities().values()) {
                    BlockPos pos = blockEntity.getBlockPos();
                    if (area.isInside(pos)) {
                        found.add(blockEntity);
                    }
                }
            }
        }
        return found;
    }

    /**
     * The nearest position a mob could stand on around {@code point}.
     *
     * <p>The point itself is tried first, then upwards, then downwards, so a
     * boss never ends up inside the floor or buried in a ceiling.</p>
     *
     * @return the safe position, or empty when nothing within reach works
     */
    public static Optional<BlockPos> findSafeSpot(ServerLevel level, RespawnPoint point) {
        int baseX = (int) Math.floor(point.x());
        int baseY = (int) Math.floor(point.y());
        int baseZ = (int) Math.floor(point.z());
        for (int offset = 0; offset <= SAFE_SEARCH; offset++) {
            for (int direction : offset == 0 ? new int[] {0} : new int[] {1, -1}) {
                BlockPos candidate = new BlockPos(baseX, baseY + offset * direction, baseZ);
                if (ResetAim.isSafeStanding(level, candidate)) {
                    return Optional.of(candidate);
                }
            }
        }
        return Optional.empty();
    }

    /** Kept for the reading of a container's tag, the way the loot scan does it. */
    static CompoundTag tagOf(BlockEntity blockEntity) {
        return blockEntity.saveWithoutMetadata();
    }
}
