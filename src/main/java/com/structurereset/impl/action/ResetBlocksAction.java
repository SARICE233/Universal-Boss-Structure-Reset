package com.structurereset.impl.action;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;
import com.structurereset.StructureResetMod;
import com.structurereset.core.ResetActionType;
import com.structurereset.core.ResetContext;
import com.structurereset.core.ResetResult;
import java.util.List;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.chunk.LevelChunk;

/**
 * {@code structurereset:reset_blocks} - puts a fixed set of blocks back.
 *
 * <p>Parameters: {@code blocks}, an array of objects with {@code pos} (an
 * {@code [x, y, z]} offset relative to the reset origin) or {@code absolute_pos}
 * (world coordinates), {@code block} (a block id) and {@code keep_nbt} (default
 * false: copy the block entity data of the replaced block onto the new one when
 * both are of the same type).</p>
 *
 * <p>Parameters: {@code mapping} and {@code radius} - the second mode, for
 * structures that seal a room before a fight. {@code mapping} is an object from
 * an unlocked block id to the sealed one it becomes, and every matching block
 * within {@code radius} (default {@value #DEFAULT_RADIUS}, at most
 * {@value #MAX_RADIUS}) of the reset origin is put back to the sealed state,
 * reading only chunks that are already loaded. The Aether's dungeons are the
 * example: before the boss is beaten the room is made of
 * {@code aether:locked_*} blocks, the boss unlocks them when it dies, and a boss
 * spawned into an already unlocked room removes itself again - so the room has to
 * be re-sealed before the boss is summoned.</p>
 */
public final class ResetBlocksAction implements ResetActionType {

    /** The name part of this action id. */
    public static final String NAME = "reset_blocks";
    /** The namespaced id of this action type. */
    public static final ResourceLocation ID = new ResourceLocation(StructureResetMod.MODID, NAME);

    /** Translation key of a failed action. */
    public static final String MESSAGE_FAILED = "structurereset.message.action_failed";

    /** How far around the origin the {@code mapping} mode looks, by default. */
    public static final int DEFAULT_RADIUS = 32;

    /** The largest radius the {@code mapping} mode accepts. */
    public static final int MAX_RADIUS = 64;

    @Override
    public String id() {
        return ID.toString();
    }

    @Override
    public ResetResult run(ResetContext ctx) {
        // The mapping mode comes first: it is the one a sealed room needs, and it
        // never coexists with an explicit block list.
        JsonObject mapping = ctx.params() == null ? null : ctx.params().getAsJsonObject("mapping");
        if (mapping != null && mapping.size() > 0) {
            int radius = Math.max(1, Math.min(MAX_RADIUS, ctx.paramInt("radius", DEFAULT_RADIUS)));
            int remapped = applyMapping(ctx, mapping, radius);
            StructureResetMod.LOGGER.info("[{}] reset_blocks re-sealed {} block(s) within {} of {}",
                    StructureResetMod.MODID, remapped, radius, ctx.origin());
            return ResetResult.ok(List.of(id()));
        }
        JsonArray entries = ctx.paramArray("blocks");
        if (entries == null || entries.isEmpty()) {
            return ResetResult.fail(MESSAGE_FAILED, "reset_blocks requires a non-empty params.blocks array");
        }
        ServerLevel level = ctx.level();
        int changed = 0;
        int skipped = 0;
        for (JsonElement element : entries) {
            if (element == null || !element.isJsonObject()) {
                skipped++;
                continue;
            }
            JsonObject entry = element.getAsJsonObject();
            int[] absolute = coordinates(entry, "absolute_pos");
            int[] relative = absolute == null ? coordinates(entry, "pos") : null;
            if (absolute == null && relative == null) {
                skipped++;
                continue;
            }
            BlockPos target = absolute != null
                    ? new BlockPos(absolute[0], absolute[1], absolute[2])
                    : ctx.origin().offset(relative[0], relative[1], relative[2]);
            ResourceLocation blockId = ResourceLocation.tryParse(text(entry, "block", ""));
            if (blockId == null) {
                skipped++;
                continue;
            }
            Block block = BuiltInRegistries.BLOCK.get(blockId);
            if (block == null || block == Blocks.AIR) {
                skipped++;
                continue;
            }
            boolean keepNbt = flag(entry, "keep_nbt", false);
            BlockEntity existing = keepNbt ? level.getBlockEntity(target) : null;
            CompoundTag saved = existing == null ? null : existing.saveWithoutMetadata();
            BlockEntityType<?> savedType = existing == null ? null : existing.getType();
            if (level.setBlock(target, block.defaultBlockState(), Block.UPDATE_ALL)) {
                changed++;
            }
            if (saved != null) {
                BlockEntity fresh = level.getBlockEntity(target);
                if (fresh != null && fresh.getType() == savedType) {
                    fresh.load(saved);
                }
            }
        }
        if (changed == 0 && skipped > 0) {
            return ResetResult.fail(MESSAGE_FAILED,
                    "reset_blocks could not place any of the " + entries.size() + " block(s)");
        }
        return ResetResult.ok(List.of(id()));
    }

    /**
     * Puts every block named by {@code mapping} back to its sealed form.
     *
     * <p>Only chunks that are already loaded are read, so a reset can never stall
     * the server thread on a chunk load, and only the radius that was asked for is
     * walked, so the work stays bounded.</p>
     *
     * @param ctx the action context, required
     * @param mapping unlocked block id to sealed block id
     * @param radius how far around the origin to look
     * @return how many blocks were actually changed
     */
    private static int applyMapping(ResetContext ctx, JsonObject mapping, int radius) {
        ServerLevel level = ctx.level();
        BlockPos origin = ctx.origin();
        int changed = 0;
        int minY = level.getMinBuildHeight();
        int maxY = level.getMaxBuildHeight() - 1;
        for (int dx = -radius; dx <= radius; dx++) {
            int x = origin.getX() + dx;
            for (int dz = -radius; dz <= radius; dz++) {
                int z = origin.getZ() + dz;
                LevelChunk chunk = level.getChunkSource().getChunkNow(x >> 4, z >> 4);
                if (chunk == null) {
                    continue;
                }
                for (int dy = -radius; dy <= radius; dy++) {
                    int y = origin.getY() + dy;
                    if (y < minY || y > maxY) {
                        continue;
                    }
                    BlockPos pos = new BlockPos(x, y, z);
                    ResourceLocation current = BuiltInRegistries.BLOCK.getKey(chunk.getBlockState(pos).getBlock());
                    JsonElement to = mapping.get(current.toString());
                    if (to == null || !to.isJsonPrimitive()) {
                        continue;
                    }
                    ResourceLocation sealedId = ResourceLocation.tryParse(to.getAsString());
                    if (sealedId == null) {
                        continue;
                    }
                    Block sealed = BuiltInRegistries.BLOCK.get(sealedId);
                    if (sealed == null || sealed == Blocks.AIR) {
                        continue;
                    }
                    if (level.setBlock(pos, sealed.defaultBlockState(), Block.UPDATE_ALL)) {
                        changed++;
                    }
                }
            }
        }
        return changed;
    }

    private static int[] coordinates(JsonObject entry, String key) {
        JsonElement element = entry.get(key);
        if (element == null || !element.isJsonArray()) {
            return null;
        }
        JsonArray array = element.getAsJsonArray();
        if (array.size() < 3) {
            return null;
        }
        try {
            return new int[] {array.get(0).getAsInt(), array.get(1).getAsInt(), array.get(2).getAsInt()};
        } catch (RuntimeException failure) {
            return null;
        }
    }

    private static String text(JsonObject entry, String key, String fallback) {
        JsonElement element = entry.get(key);
        if (element == null || !element.isJsonPrimitive()) {
            return fallback;
        }
        JsonPrimitive primitive = element.getAsJsonPrimitive();
        return primitive.isString() ? primitive.getAsString() : fallback;
    }

    private static boolean flag(JsonObject entry, String key, boolean fallback) {
        JsonElement element = entry.get(key);
        if (element == null || !element.isJsonPrimitive()) {
            return fallback;
        }
        JsonPrimitive primitive = element.getAsJsonPrimitive();
        return primitive.isBoolean() ? primitive.getAsBoolean() : fallback;
    }
}
