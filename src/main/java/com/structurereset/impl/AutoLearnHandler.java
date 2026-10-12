package com.structurereset.impl;

import com.structurereset.config.StructureResetConfig;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.LivingEntity;
import net.minecraftforge.event.entity.EntityJoinLevelEvent;
import net.minecraftforge.event.entity.living.LivingDeathEvent;
import net.minecraftforge.registries.ForgeRegistries;
import org.slf4j.Logger;
import com.mojang.logging.LogUtils;

/**
 * The optional automatic learning layer (S3): the mapping learns itself from
 * what happens in the world instead of from a player aiming at a boss.
 *
 * <p>Two observations are used. A boss that <em>enters the world</em> inside a
 * structure is usually the boss that structure just generated, and a boss that
 * <em>dies</em> inside a structure is the one that was fought there - the
 * strongest possible evidence, and it also yields the position where the boss
 * should come back.</p>
 *
 * <p>This is deliberately off by default: it writes records on its own, so a
 * pack author turns it on knowingly. Everything it writes is {@link
 * LearnedAnchorService#autoLearn} guarded, which never restates a structure an
 * earlier (stronger) record already covers.</p>
 */
public final class AutoLearnHandler {

    private static final Logger LOGGER = LogUtils.getLogger();

    private AutoLearnHandler() {
    }

    /**
     * A boss that was added to the world: structure generation or a spawner.
     *
     * <p>The binding is all this does: it is cheap, bounded to entities that pass
     * the boss gate, and it is what makes "preserve at generation" work. Structure
     * records are never written automatically - walking around would eventually
     * bind a wandering boss to the wrong structure, so every record in the world
     * is one a player or an operator asked for.</p>
     */
    public static void onEntityJoinLevel(EntityJoinLevelEvent event) {
        try {
            bindJoin(event);
        } catch (Throwable failure) {
            // An entity join happens constantly in a modded world, and this hook
            // runs even when automatic learning is off. Nothing an observer does
            // may ever reach the server tick loop: it is logged and the single
            // observation is skipped.
            LOGGER.warn("[{}] boss binding skipped one entity join",
                    StructureResetConfig.MOD_ID, failure);
        }
    }

    /** The real body of {@link #onEntityJoinLevel(EntityJoinLevelEvent)}. */
    private static void bindJoin(EntityJoinLevelEvent event) {
        if (event == null) {
            return;
        }
        // A chunk load restores every stored entity through this event; those are
        // not new observations and re-learning from them would be meaningless.
        if (event.loadedFromDisk()) {
            return;
        }
        if (!(event.getLevel() instanceof ServerLevel level)) {
            return;
        }
        if (!(event.getEntity() instanceof LivingEntity entity) || !entity.isAlive()) {
            return;
        }
        if (!LearnedAnchorService.isBossCandidate(entity)) {
            return;
        }
        BossBindingService.bind(level, entity, entityIdOf(entity), "generated");
    }

    /**
     * A boss that left the world without a death event.
     *
     * <p>{@code /kill} and other command or mod removals use
     * {@code Entity#remove(KILLED)}, which never runs {@code die()} and therefore
     * never fires {@link LivingDeathEvent}. Without this the registry would keep
     * such a boss as alive forever.</p>
     */
    public static void onEntityLeaveLevel(net.minecraftforge.event.entity.EntityLeaveLevelEvent event) {
        try {
            if (event == null || event.getEntity() == null) {
                return;
            }
            // A boss we spawned is reported the moment it leaves, with its removal
            // reason: that is what tells "the mod discarded it" from "it flew away".
            if (com.structurereset.impl.SpawnWatcher.onRemoved(event.getEntity())) {
                return;
            }
            if (event.getEntity().getRemovalReason()
                    != net.minecraft.world.entity.Entity.RemovalReason.KILLED) {
                return;
            }
            if (!(event.getLevel() instanceof ServerLevel level)) {
                return;
            }
            if (!(event.getEntity() instanceof LivingEntity entity)) {
                return;
            }
            BossBindingService.confirmDeath(level, entity, null);
        } catch (Throwable failure) {
            LOGGER.warn("[{}] boss confirmation skipped one removal",
                    StructureResetConfig.MOD_ID, failure);
        }
    }

    /** A boss that died: it really was fought in the structure it died in. */
    public static void onLivingDeath(LivingDeathEvent event) {
        try {
            confirmDeath(event);
        } catch (Throwable failure) {
            LOGGER.warn("[{}] boss confirmation skipped one death",
                    StructureResetConfig.MOD_ID, failure);
        }
    }

    /** The real body of {@link #onLivingDeath(LivingDeathEvent)}. */
    private static void confirmDeath(LivingDeathEvent event) {
        if (event == null) {
            return;
        }
        LivingEntity entity = event.getEntity();
        if (entity == null || !(entity.level() instanceof ServerLevel level)) {
            return;
        }
        if (!LearnedAnchorService.isBossCandidate(entity)) {
            return;
        }
        BossBindingService.confirmDeath(level, entity, event.getSource());
    }

    /** The registry id of an entity, or an empty string when it has none. */
    private static String entityIdOf(LivingEntity entity) {
        ResourceLocation id = ForgeRegistries.ENTITY_TYPES.getKey(entity.getType());
        return id == null ? "" : id.toString();
    }
}
