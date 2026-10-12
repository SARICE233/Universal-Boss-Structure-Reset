package com.structurereset.impl.action;

import com.structurereset.StructureResetMod;
import com.structurereset.core.ResetActionType;
import com.structurereset.core.ResetContext;
import com.structurereset.core.ResetResult;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.phys.AABB;
import net.minecraftforge.registries.ForgeRegistries;

/**
 * {@code structurereset:clear_entities} - removes entities around the origin.
 *
 * <p>Parameters: {@code entities} (an optional array of entity type ids to
 * restrict the clear to), {@code radius} (default: the radius of the definition)
 * and {@code include_items} (default false, so dropped items survive unless they
 * are asked for explicitly). Players are never removed.</p>
 */
public final class ClearEntitiesAction implements ResetActionType {

    /** The name part of this action id. */
    public static final String NAME = "clear_entities";
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
        double radius = Math.max(0.0D, ctx.paramDouble("radius", ctx.definition().radius()));
        boolean includeItems = ctx.paramBoolean("include_items", false);
        Set<String> allowed = new LinkedHashSet<>(ctx.paramStringList("entities"));
        AABB box = new AABB(ctx.origin()).inflate(radius);
        List<Entity> found = level.getEntities((Entity) null, box,
                entity -> shouldClear(entity, allowed, includeItems));
        int removed = 0;
        for (Entity entity : found) {
            entity.discard();
            removed++;
        }
        return ResetResult.ok(List.of(id()));
    }

    private static boolean shouldClear(Entity entity, Set<String> allowed, boolean includeItems) {
        if (entity == null || entity instanceof ServerPlayer) {
            return false;
        }
        if (!includeItems && entity instanceof ItemEntity) {
            return false;
        }
        if (allowed.isEmpty()) {
            return true;
        }
        ResourceLocation id = ForgeRegistries.ENTITY_TYPES.getKey(entity.getType());
        return id != null && allowed.contains(id.toString());
    }
}
