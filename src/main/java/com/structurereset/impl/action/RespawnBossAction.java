package com.structurereset.impl.action;

import com.structurereset.StructureResetMod;
import com.structurereset.core.ResetActionType;
import com.structurereset.core.ResetContext;
import net.minecraft.core.BlockPos;
import com.structurereset.core.ResetResult;
import java.util.List;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraftforge.registries.ForgeRegistries;

/**
 * {@code structurereset:respawn_boss} - spawns a boss entity again.
 *
 * <p>Parameters: {@code entity} (required, an entity type id), {@code count}
 * (default 1) and {@code spread} (default 0.0, the radius the spawned entities
 * are scattered over).</p>
 */
public final class RespawnBossAction implements ResetActionType {

    /** The name part of this action id. */
    public static final String NAME = "respawn_boss";
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
        String entityId = ctx.paramString("entity", "");
        if (entityId.isBlank()) {
            return ResetResult.fail(MESSAGE_FAILED, "respawn_boss requires a params.entity");
        }
        if (com.structurereset.impl.UnrestorableBosses.get(level).contains(entityId)) {
            // This boss type has already been watched discarding itself. Summoning it
            // again would only play an effect and leave an empty room, so the action
            // succeeds without a boss and says why.
            if (ctx.actor() != null) {
                ctx.actor().displayClientMessage(net.minecraft.network.chat.Component.translatable(
                        "structurereset.message.spawn.unrestorable", entityId), false);
            }
            StructureResetMod.LOGGER.info("[{}] {} is known to discard itself; nothing was summoned",
                    StructureResetMod.MODID, entityId);
            return ResetResult.ok(List.of(id()));
        }
        ResourceLocation id = ResourceLocation.tryParse(entityId);
        if (id == null) {
            return ResetResult.fail(MESSAGE_FAILED, "invalid entity id '" + entityId + "'");
        }
        EntityType<?> type = ForgeRegistries.ENTITY_TYPES.getValue(id);
        if (type == null) {
            return ResetResult.fail(MESSAGE_FAILED, "unknown entity type '" + entityId + "'");
        }
        int count = Math.max(1, ctx.paramInt("count", 1));
        double spread = Math.max(0.0D, ctx.paramDouble("spread", 0.0D));
        int spawned = 0;
        for (int index = 0; index < count; index++) {
            Entity entity = type.create(level);
            if (entity == null) {
                continue;
            }
            double offsetX = spread <= 0.0D ? 0.0D
                    : (level.getRandom().nextDouble() - 0.5D) * 2.0D * spread;
            double offsetY = spread <= 0.0D ? 0.0D : level.getRandom().nextDouble() * spread;
            double offsetZ = spread <= 0.0D ? 0.0D
                    : (level.getRandom().nextDouble() - 0.5D) * 2.0D * spread;
            // A reset that was aimed with the crosshair brings the boss back to
            // the place the player pointed at; without an aim the definition's own
            // origin (the anchor block) decides, exactly as before.
            BlockPos spawnAt = ctx.origin();
            if (ctx.actor() != null) {
                spawnAt = com.structurereset.impl.ResetAim.spawnOverride(ctx.actor()).orElse(spawnAt);
            }
            // The marked block is not always the right block to stand on: a flying
            // boss is put into free air above it instead of into the floor of the
            // room it was summoned into.
            BossSpawn.Placement placement = BossSpawn.place(level, entity, spawnAt);
            BlockPos placed = placement.pos();
            BossSpawn.initialise(level, entity, placed);
            entity.moveTo(placed.getX() + 0.5D + offsetX,
                    placed.getY() + offsetY,
                    placed.getZ() + 0.5D + offsetZ,
                    level.getRandom().nextFloat() * 360.0F, 0.0F);
            if (level.addFreshEntity(entity)) {
                spawned++;
                StructureResetMod.LOGGER.info("[{}] spawned {} at {} (marked {}, flyer={}, lifted={}, fits={})",
                        StructureResetMod.MODID, entityId, entity.blockPosition(),
                        spawnAt.toShortString(), BossSpawn.isFlyer(entity),
                        placement.lifted(), placement.fits());
                com.structurereset.impl.SpawnWatcher.watch(level, entity, spawnAt);
                // The definition already says this entity is the boss of this
                // structure, so it is bound here instead of relying on the generic
                // observation hook - that hook may see a freshly created entity
                // before its attributes and structure lookup settle, and an
                // untracked respawned boss would lock the structure out of a reset
                // forever after it is killed again.
                if (entity instanceof net.minecraft.world.entity.LivingEntity living) {
                    com.structurereset.impl.BossBindingService.bind(level, living, entityId, "respawned");
                }
            } else {
                entity.discard();
            }
        }
        if (spawned == 0) {
            return ResetResult.fail(MESSAGE_FAILED, "no '" + entityId + "' could be spawned");
        }
        return ResetResult.ok(List.of(id()));
    }
}
