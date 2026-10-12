package com.structurereset.impl.action;

import com.structurereset.StructureResetMod;
import com.structurereset.api.LearnedContainer;
import com.structurereset.core.ResetActionType;
import com.structurereset.core.ResetContext;
import com.structurereset.core.ResetResult;
import com.structurereset.impl.LootIndexService;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.Mth;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.RandomizableContainerBlockEntity;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.phys.AABB;

/**
 * {@code structurereset:refill_loot} - refills every loot container inside the
 * reset box.
 *
 * <p>Parameters: {@code mode} ({@code empty_only}, the default, or {@code all}),
 * {@code loot_tables} (an optional array of loot table ids to restrict the
 * refill to) and {@code reroll_seed} (default true). Only containers that
 * already carry a loot table are touched, so a chest a player filled by hand is
 * never emptied by this action.</p>
 */
public final class RefillLootAction implements ResetActionType {

    /** The name part of this action id. */
    public static final String NAME = "refill_loot";
    /** The namespaced id of this action type. */
    public static final ResourceLocation ID = new ResourceLocation(StructureResetMod.MODID, NAME);

    /** Translation key of a failed action. */
    public static final String MESSAGE_FAILED = "structurereset.message.action_failed";

    @Override
    public String id() {
        return ID.toString();
    }

    @Override
    public ResetResult run(ResetContext ctx) {
        ServerLevel level = ctx.level();
        AABB box = ctx.box();
        int minX = Mth.floor(box.minX);
        int minY = Mth.floor(box.minY);
        int minZ = Mth.floor(box.minZ);
        int maxX = Mth.floor(box.maxX);
        int maxY = Mth.floor(box.maxY);
        int maxZ = Mth.floor(box.maxZ);
        boolean emptyOnly = !"all".equalsIgnoreCase(ctx.paramString("mode", "empty_only"));
        boolean rerollSeed = ctx.paramBoolean("reroll_seed", true);
        Set<String> lootTables = new LinkedHashSet<>(ctx.paramStringList("loot_tables"));
        int refilled = 0;
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
                    if (emptyOnly && !container.isEmpty()) {
                        continue;
                    }
                    // the loot table accessors of RandomizableContainerBlockEntity are
                    // protected, so the public block entity NBT is read instead
                    CompoundTag saved = container.saveWithoutMetadata();
                    ResourceLocation table;
                    long storedSeed;
                    boolean hasSeed;
                    if (saved.contains(RandomizableContainerBlockEntity.LOOT_TABLE_TAG)) {
                        table = ResourceLocation.tryParse(
                                saved.getString(RandomizableContainerBlockEntity.LOOT_TABLE_TAG));
                        storedSeed = saved.getLong(RandomizableContainerBlockEntity.LOOT_TABLE_SEED_TAG);
                        hasSeed = true;
                    } else {
                        // The container was opened already, so its own loot table is gone
                        // from the world. A left click capture taken before that happened
                        // is the only remaining source for it.
                        Optional<LearnedContainer> recorded = LootIndexService.lookup(level, pos);
                        if (recorded.isEmpty()) {
                            continue;
                        }
                        table = ResourceLocation.tryParse(recorded.get().lootTable());
                        storedSeed = recorded.get().lootTableSeed();
                        hasSeed = recorded.get().hasSeed();
                    }
                    if (table == null) {
                        continue;
                    }
                    if (!lootTables.isEmpty() && !lootTables.contains(table.toString())) {
                        continue;
                    }
                    long seed = rerollSeed || !hasSeed ? level.getRandom().nextLong() : storedSeed;
                    container.clearContent();
                    container.setLootTable(table, seed);
                    container.unpackLootTable(null);
                    container.setChanged();
                    refilled++;
                }
            }
        }
        return ResetResult.ok(List.of(id()));
    }

    /**
     * How many containers {@link #run(ResetContext)} would refill inside
     * {@code box}, <em>without</em> touching any of them.
     *
     * <p>This is the read only preview the two click confirmation uses to tell
     * the player how many chests a confirming click would refill. It walks
     * exactly the same chunks and block entities as {@link #run(ResetContext)}
     * and applies exactly the same filters - a usable loot table id, the
     * {@code emptyOnly} rule and the optional {@code loot_tables} restriction -
     * but it only counts: no container is cleared, no loot table is set and no
     * loot is generated.</p>
     *
     * <p>The emptiness test deliberately does not call
     * {@code RandomizableContainerBlockEntity#isEmpty()}: in 1.20.1 that method
     * first unpacks the loot table, which fills the container and drops its loot
     * table, i.e. it would modify the very thing this method only wants to look
     * at. The item list stored in the block entity tag is inspected instead.</p>
     *
     * @param level the level to look at, may be null
     * @param box the area to look in, may be null
     * @param emptyOnly when true only containers that hold no item are counted
     * @param lootTables the loot table ids to restrict the count to; empty or
     *        null counts every usable loot table
     * @return the number of containers a refill would touch
     */
    public static int previewCount(ServerLevel level, AABB box, boolean emptyOnly,
                                   Set<String> lootTables) {
        if (level == null || box == null) {
            return 0;
        }
        Set<String> tables = lootTables == null ? Set.of() : lootTables;
        int minX = Mth.floor(box.minX);
        int minY = Mth.floor(box.minY);
        int minZ = Mth.floor(box.minZ);
        int maxX = Mth.floor(box.maxX);
        int maxY = Mth.floor(box.maxY);
        int maxZ = Mth.floor(box.maxZ);
        int count = 0;
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
                    ResourceLocation table;
                    if (saved.contains(RandomizableContainerBlockEntity.LOOT_TABLE_TAG)) {
                        table = ResourceLocation.tryParse(
                                saved.getString(RandomizableContainerBlockEntity.LOOT_TABLE_TAG));
                    } else {
                        table = LootIndexService.lookup(level, pos)
                                .map(recorded -> ResourceLocation.tryParse(recorded.lootTable()))
                                .orElse(null);
                    }
                    if (table == null) {
                        continue;
                    }
                    if (!tables.isEmpty() && !tables.contains(table.toString())) {
                        continue;
                    }
                    if (emptyOnly && !storedEmpty(saved)) {
                        continue;
                    }
                    count++;
                }
            }
        }
        return count;
    }

    /**
     * True when the item list stored in a block entity tag holds no item.
     *
     * @param saved the tag of one container block entity, seen through
     *        {@code saveWithoutMetadata()}
     */
    private static boolean storedEmpty(CompoundTag saved) {
        ListTag items = saved.getList("Items", Tag.TAG_COMPOUND);
        for (int index = 0; index < items.size(); index++) {
            if (!ItemStack.of(items.getCompound(index)).isEmpty()) {
                return false;
            }
        }
        return true;
    }
}
