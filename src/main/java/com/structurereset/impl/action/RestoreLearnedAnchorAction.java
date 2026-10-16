package com.structurereset.impl.action;

import com.mojang.logging.LogUtils;
import com.structurereset.StructureResetMod;
import com.structurereset.api.LearnedAnchor;
import com.structurereset.api.LearnedContainer;
import com.structurereset.core.ResetActionType;
import com.structurereset.core.ResetContext;
import com.structurereset.core.ResetResult;
import com.structurereset.impl.LearnedAnchorData;
import com.structurereset.impl.LearnedAnchorService;
import com.structurereset.impl.LootIndexService;
import java.util.List;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.RandomizableContainerBlockEntity;
import net.minecraftforge.registries.ForgeRegistries;
import org.slf4j.Logger;

/**
 * {@code structurereset:restore_learned_anchor} - replays one learned anchor.
 *
 * <p>Parameters: {@code anchor_id} (required, the id of a record stored by
 * {@link LearnedAnchorService#teach}).  The action spawns the boss the record
 * observed and refills the containers it observed, so a place that was taught
 * once comes back exactly like it was found.</p>
 *
 * <p>The record's own dimension is checked before anything is spawned: a
 * learned anchor never leaks into another dimension, even if a data pack or a
 * command points at it from there.</p>
 */
public final class RestoreLearnedAnchorAction implements ResetActionType {

    /** The name part of this action id. */
    public static final String NAME = "restore_learned_anchor";
    /** The namespaced id of this action type. */
    public static final ResourceLocation ID = new ResourceLocation(StructureResetMod.MODID, NAME);

    /** Translation key of a failed action. */
    public static final String MESSAGE_FAILED = "structurereset.message.action_failed";

    private static final Logger LOGGER = LogUtils.getLogger();

    @Override
    public String id() {
        return ID.toString();
    }

    @Override
    public ResetResult run(ResetContext ctx) {
        ServerLevel level = ctx.level();
        String anchorId = ctx.paramString(LearnedAnchorService.PARAM_ANCHOR_ID, "");
        if (anchorId.isBlank()) {
            return fail("restore_learned_anchor requires a params.anchor_id");
        }
        LearnedAnchor anchor = LearnedAnchorData.get(level).byId(anchorId);
        if (anchor == null) {
            return fail("unknown learned anchor '" + anchorId + "'");
        }
        String dimension = level.dimension().location().toString();
        if (!dimension.equals(anchor.dimension())) {
            return fail("learned anchor '" + anchorId + "' belongs to " + anchor.dimension()
                    + ", not to " + dimension);
        }
        ResourceLocation entityTypeId = ResourceLocation.tryParse(anchor.bossEntity());
        EntityType<?> type = entityTypeId == null
                ? null : ForgeRegistries.ENTITY_TYPES.getValue(entityTypeId);
        if (type == null) {
            return fail("unknown entity type '" + anchor.bossEntity() + "'");
        }
        Entity entity;
        try {
            entity = type.create(level);
        } catch (RuntimeException failure) {
            return fail("'" + anchor.bossEntity() + "' could not be created: " + failure);
        }
        if (entity == null) {
            return fail("'" + anchor.bossEntity() + "' could not be created");
        }
        try {
            // The aimed position wins over the recorded one: the player pointed at
            // where the boss should come back, and the summoning effect was played
            // exactly there. Without this the boss would appear at the recorded
            // spawn point while the particles danced somewhere else.
            BlockPos wanted = new BlockPos(anchor.x(), anchor.y(), anchor.z());
            if (ctx.actor() != null) {
                wanted = com.structurereset.impl.ResetAim.spawnOverride(ctx.actor()).orElse(wanted);
            }
            BossSpawn.Placement placement = BossSpawn.place(level, entity, wanted);
            BlockPos spot = placement.pos();
            BossSpawn.initialise(level, entity, spot);
            entity.moveTo(spot.getX() + 0.5D, (double) spot.getY(), spot.getZ() + 0.5D,
                    level.getRandom().nextFloat() * 360.0F, 0.0F);
            if (com.structurereset.impl.UnrestorableBosses.get(level).contains(anchor.bossEntity())) {
                // Watched discarding itself before: the loot below is still restored,
                // but no reset pretends to bring this boss back again.
                entity.discard();
                if (ctx.actor() != null) {
                    ctx.actor().displayClientMessage(net.minecraft.network.chat.Component.translatable(
                            "structurereset.message.spawn.unrestorable", anchor.bossEntity()), false);
                }
                LOGGER.info("[{}] {} is known to discard itself; nothing was spawned",
                        StructureResetMod.MODID, anchor.bossEntity());
            } else if (!level.addFreshEntity(entity)) {
                entity.discard();
                return fail("'" + anchor.bossEntity() + "' could not be added to the level");
            }
            // The record says this entity is the boss here, so it is bound on the
            // spot: an untracked respawned boss could never be confirmed dead and
            // would lock the structure out of every later reset. An entity that was
            // discarded above (a known unrestorable boss) is never bound: that would
            // leave a ghost record for a boss that never entered the world.
            if (!entity.isRemoved() && entity instanceof net.minecraft.world.entity.LivingEntity living) {
                com.structurereset.impl.BossBindingService.bind(level, living,
                        anchor.bossEntity(), "respawned");
            }
            LOGGER.info("[{}] spawned {} at {} (marked {}, flyer={}, lifted={}, fits={})",
                    StructureResetMod.MODID, anchor.bossEntity(), entity.blockPosition(),
                    wanted.toShortString(), BossSpawn.isFlyer(entity),
                    placement.lifted(), placement.fits());
            com.structurereset.impl.SpawnWatcher.watch(level, entity, wanted);
        } catch (RuntimeException failure) {
            // Nothing must escape this action as an exception: a broken spawn is
            // reported as an ordinary failed result instead.
            entity.discard();
            return fail("'" + anchor.bossEntity() + "' could not be spawned: " + failure);
        }
        int refilled = 0;
        int skipped = 0;
        if (anchor.containers().isEmpty()) {
            // The anchor was taught after its chests had been opened, so it carries no
            // loot table at all. The loot index a left click captured earlier is then
            // the only remaining source, and it is replayed here.
            int[] fromIndex = refillFromIndex(level, anchor);
            refilled += fromIndex[0];
            skipped += fromIndex[1];
        }
        for (LearnedContainer learned : anchor.containers()) {
            BlockPos pos = new BlockPos(learned.x(), learned.y(), learned.z());
            if (!level.isLoaded(pos)) {
                skipped++;
                continue;
            }
            BlockEntity blockEntity = level.getBlockEntity(pos);
            if (!(blockEntity instanceof RandomizableContainerBlockEntity container)) {
                skipped++;
                continue;
            }
            ResourceLocation table = ResourceLocation.tryParse(learned.lootTable());
            if (table == null) {
                skipped++;
                continue;
            }
            try {
                long seed = learned.hasSeed() ? learned.lootTableSeed() : level.getRandom().nextLong();
                container.setLootTable(table, seed);
                // The learned container is not cleared first: whatever a player put
                // into it by hand has nothing to do with the structure reset.  A
                // container that is not empty is deliberately left alone by vanilla
                // unpackLootTable, which is exactly the wanted behaviour here -
                // refilling only ever fills a container that is still empty.
                container.unpackLootTable(null);
                container.setChanged();
                refilled++;
            } catch (RuntimeException failure) {
                // One broken container never aborts the restore, exactly like an
                // unloaded one: it is skipped and counted.
                skipped++;
                LOGGER.info("[{}] learned anchor {} could not refill the container at {}",
                        StructureResetMod.MODID, anchorId, pos, failure);
            }
        }
        if (skipped > 0) {
            LOGGER.info("[{}] learned anchor {} restored {} container(s), skipped {} "
                            + "unloaded or replaced container(s)",
                    StructureResetMod.MODID, anchorId, refilled, skipped);
        }
        return ResetResult.ok(List.of(id()));
    }

    /**
     * Refills every container of an anchor's area that the loot index knows.
     *
     * <p>This is the late teaching path: the boss is restored from the record,
     * and the loot comes from a capture the player made with a left click before
     * the chests were opened.</p>
     *
     * @param level the level of the anchor
     * @param anchor the record whose containers were never captured
     * @return the number of refilled containers and the number skipped
     */
    private static int[] refillFromIndex(ServerLevel level, LearnedAnchor anchor) {
        int refilled = 0;
        int skipped = 0;
        double radius = Math.max(1.0D, anchor.radius());
        int minX = Mth.floor(anchor.x() - radius);
        int minY = Mth.floor(anchor.y() - radius);
        int minZ = Mth.floor(anchor.z() - radius);
        int maxX = Mth.floor(anchor.x() + radius);
        int maxY = Mth.floor(anchor.y() + radius);
        int maxZ = Mth.floor(anchor.z() + radius);
        for (int chunkX = minX >> 4; chunkX <= maxX >> 4; chunkX++) {
            for (int chunkZ = minZ >> 4; chunkZ <= maxZ >> 4; chunkZ++) {
                if (!level.hasChunk(chunkX, chunkZ)) {
                    continue;
                }
                for (BlockEntity blockEntity : level.getChunk(chunkX, chunkZ).getBlockEntities().values()) {
                    BlockPos pos = blockEntity.getBlockPos();
                    if (pos.getX() < minX || pos.getX() > maxX
                            || pos.getY() < minY || pos.getY() > maxY
                            || pos.getZ() < minZ || pos.getZ() > maxZ) {
                        continue;
                    }
                    if (!(blockEntity instanceof RandomizableContainerBlockEntity container)) {
                        continue;
                    }
                    var recorded = LootIndexService.lookup(level, pos);
                    if (recorded.isEmpty()) {
                        continue;
                    }
                    ResourceLocation table = ResourceLocation.tryParse(recorded.get().lootTable());
                    if (table == null) {
                        skipped++;
                        continue;
                    }
                    try {
                        long seed = recorded.get().hasSeed()
                                ? recorded.get().lootTableSeed() : level.getRandom().nextLong();
                        container.setLootTable(table, seed);
                        container.unpackLootTable(null);
                        container.setChanged();
                        refilled++;
                    } catch (RuntimeException failure) {
                        skipped++;
                    }
                }
            }
        }
        return new int[] {refilled, skipped};
    }

    private static ResetResult fail(String reason) {
        return ResetResult.fail(MESSAGE_FAILED, reason);
    }
}
