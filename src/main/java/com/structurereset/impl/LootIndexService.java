package com.structurereset.impl;

import com.structurereset.api.LearnedContainer;
import com.structurereset.config.StructureResetConfig;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.RandomizableContainerBlockEntity;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.levelgen.structure.BoundingBox;

/**
 * Capturing and querying the loot index - the left mouse button half of the
 * addon, deliberately kept apart from the right mouse button reset path.
 *
 * <p>Left clicking a block with the core in hand records the loot tables every
 * still unopened container of the current structure carries. Opening a chest
 * erases that knowledge from the world, and the structure template a placed
 * piece came from is not reachable at runtime, so this early capture is the
 * only way a later {@code refill_loot} can restore anything.</p>
 *
 * <p>Capturing never consumes the item, never starts a reset and never touches
 * a container: it only reads.</p>
 */
public final class LootIndexService {

    /** Shown when a capture recorded at least one loot table. */
    public static final String MESSAGE_CAPTURED = "structurereset.message.loot.captured";
    /** Shown when a capture found nothing it could record. */
    public static final String MESSAGE_NONE = "structurereset.message.loot.none";

    private LootIndexService() {
    }

    /**
     * What one capture did.
     *
     * @param recorded how many container loot tables were newly recorded
     * @param alreadyOpened how many containers were seen without a loot table
     * @param structures how many structures covered the captured position
     * @param boundByRadius true when no structure was known and the configured radius was used
     * @param x the captured x coordinate
     * @param y the captured y coordinate
     * @param z the captured z coordinate
     * @param radius the radius the capture is reachable through
     */
    public record CaptureResult(int recorded, int alreadyOpened, int structures,
                                boolean boundByRadius, int x, int y, int z, double radius) {
    }

    /** Captures the loot tables around {@code player}, exactly once per container. */
    public static CaptureResult capture(ServerPlayer player) {
        ServerLevel level = player.serverLevel();
        BlockPos origin = player.blockPosition();
        Set<String> ids = new LinkedHashSet<>(ResetEngine.structureIdsAt(level, origin));
        BoundingBox box = ResetEngine.structureBoxAt(level, origin).orElse(null);
        double radius = StructureResetConfig.learningRadius();
        return capture(level, origin, ids, box, radius);
    }

    /** The capture itself, with an explicit volume so the command can reuse it. */
    public static CaptureResult capture(ServerLevel level, BlockPos origin, Set<String> structureIds,
                                        BoundingBox box, double radius) {
        int minX;
        int minY;
        int minZ;
        int maxX;
        int maxY;
        int maxZ;
        if (box != null) {
            minX = box.minX();
            minY = box.minY();
            minZ = box.minZ();
            maxX = box.maxX();
            maxY = box.maxY();
            maxZ = box.maxZ();
        } else {
            int r = (int) Math.max(1.0D, radius);
            minX = origin.getX() - r;
            minY = origin.getY() - r;
            minZ = origin.getZ() - r;
            maxX = origin.getX() + r;
            maxY = origin.getY() + r;
            maxZ = origin.getZ() + r;
        }

        List<LearnedContainer> recorded = new ArrayList<>();
        int alreadyOpened = 0;
        for (int chunkX = minX >> 4; chunkX <= maxX >> 4; chunkX++) {
            for (int chunkZ = minZ >> 4; chunkZ <= maxZ >> 4; chunkZ++) {
                if (!level.hasChunk(chunkX, chunkZ)) {
                    continue;
                }
                LevelChunk chunk = level.getChunk(chunkX, chunkZ);
                for (BlockEntity blockEntity : chunk.getBlockEntities().values()) {
                    BlockPos pos = blockEntity.getBlockPos();
                    if (pos.getX() < minX || pos.getX() > maxX
                            || pos.getY() < minY || pos.getY() > maxY
                            || pos.getZ() < minZ || pos.getZ() > maxZ) {
                        continue;
                    }
                    if (!(blockEntity instanceof RandomizableContainerBlockEntity container)) {
                        continue;
                    }
                    CompoundTag saved = container.saveWithoutMetadata();
                    if (!saved.contains(RandomizableContainerBlockEntity.LOOT_TABLE_TAG)) {
                        // already opened, or never had a table: nothing left to learn
                        alreadyOpened++;
                        continue;
                    }
                    ResourceLocation table = ResourceLocation.tryParse(
                            saved.getString(RandomizableContainerBlockEntity.LOOT_TABLE_TAG));
                    if (table == null) {
                        continue;
                    }
                    boolean hasSeed = saved.contains(RandomizableContainerBlockEntity.LOOT_TABLE_SEED_TAG);
                    long seed = hasSeed
                            ? saved.getLong(RandomizableContainerBlockEntity.LOOT_TABLE_SEED_TAG) : 0L;
                    recorded.add(new LearnedContainer(pos.getX(), pos.getY(), pos.getZ(),
                            table.toString(), seed, hasSeed));
                }
            }
        }

        if (!recorded.isEmpty()) {
            LootIndexData data = LootIndexData.get(level);
            data.put(new LootIndexData.Entry(level.dimension().location().toString(),
                    List.copyOf(structureIds), origin.getX(), origin.getY(), origin.getZ(),
                    Math.max(1.0D, radius), List.copyOf(recorded), level.getGameTime()));
        }
        return new CaptureResult(recorded.size(), alreadyOpened, structureIds.size(),
                box == null, origin.getX(), origin.getY(), origin.getZ(), Math.max(1.0D, radius));
    }

    /**
     * The loot table recorded for a container at {@code pos}, when there is one.
     *
     * <p>Looked up by structure first and by the captured radius second, so it
     * also answers for containers of structures whose definition we do not ship.</p>
     *
     * @param level the level the container is in
     * @param pos the container position
     * @return the recorded container, or empty when nothing was captured for it
     */
    public static Optional<LearnedContainer> lookup(ServerLevel level, BlockPos pos) {
        if (level == null || pos == null) {
            return Optional.empty();
        }
        Set<String> ids = ResetEngine.structureIdsAt(level, pos);
        return LootIndexData.get(level).containerAt(level.dimension().location().toString(),
                ids, pos.getX(), pos.getY(), pos.getZ());
    }
}
