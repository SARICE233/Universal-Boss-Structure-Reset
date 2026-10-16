package com.structurereset.impl;

import com.structurereset.StructureResetMod;
import com.structurereset.config.StructureResetConfig;
import com.structurereset.core.ResetResult;
import com.structurereset.core.StructureResetApi;
import java.util.Optional;
import java.util.Set;
import net.minecraft.core.BlockPos;
import net.minecraft.core.particles.DustParticleOptions;
import net.minecraft.core.particles.ParticleOptions;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.levelgen.structure.BoundingBox;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.event.entity.living.LivingDeathEvent;
import net.minecraftforge.event.entity.player.PlayerEvent;
import org.joml.Vector3f;

/**
 * The crosshair driven respawn: the player points at the place the boss should
 * come back to, the mod shows that place, and only then does the boss appear.
 *
 * <p>This exists because an algorithm can only guess. A boss can be dragged out
 * of its arena, pushed into a wall or teleported by another mod, so its death
 * position is a poor respawn reference - but the player standing in the
 * structure knows exactly where it belongs. The algorithm is kept as the
 * fallback, never as the decision.</p>
 *
 * <p>The flow has two phases, and both live in the player's own persistent data
 * so no static state exists anywhere:</p>
 * <ol>
 *   <li><b>aiming</b> - the first right click only announces; the block under
 *       the crosshair is checked every tick and drawn with particles (green =
 *       legal, yellow = far from where the boss came from, red = illegal).
 *       Nothing is spawned and nothing is consumed;</li>
 *   <li><b>casting</b> - the second right click locks the position and starts
 *       the cast, which ends after {@code aimCastTicks}. Only then is the reset
 *       triggered, with the locked position as the spawn point.</li>
 * </ol>
 *
 * <p>The crosshair is read on the server ({@code Entity#pick}) instead of being
 * synchronised from the client, so the mod needs no network channel at all and
 * the server stays authoritative.</p>
 */
public final class ResetAim {

    /** The player is not aiming. */
    public static final String PHASE_NONE = "";
    /** The player is choosing a position. */
    public static final String PHASE_AIMING = "aiming";
    /** The player has locked a position and the cast is running. */
    public static final String PHASE_CASTING = "casting";

    /** Key of the current phase. */
    public static final String TAG_PHASE = "structurereset:aim_phase";
    /** Key of the aimed x coordinate. */
    public static final String TAG_X = "structurereset:aim_x";
    /** Key of the aimed y coordinate. */
    public static final String TAG_Y = "structurereset:aim_y";
    /** Key of the aimed z coordinate. */
    public static final String TAG_Z = "structurereset:aim_z";
    /** Key of the game time the current phase ends at. */
    public static final String TAG_UNTIL = "structurereset:aim_until";
    /** Key of the dimension the aim belongs to. */
    public static final String TAG_DIMENSION = "structurereset:aim_dimension";
    /** Key of the definition the aim is for. */
    public static final String TAG_DEFINITION = "structurereset:aim_definition";

    /** Status: the position is legal and matches where the boss came from. */
    public static final int STATUS_OK = 0;
    /** Status: legal, but far from the recorded spawn point. */
    public static final int STATUS_DEVIATES = 1;
    /** Status: illegal - outside the structure, or not a safe standing spot. */
    public static final int STATUS_ILLEGAL = 2;

    /** How far from the recorded spawn point a position may be before it warns. */
    public static final double DEVIATION_LIMIT = 32.0D;


    private static final ParticleOptions GREEN =
            new DustParticleOptions(new Vector3f(0.25F, 1.0F, 0.3F), 1.5F);
    private static final ParticleOptions YELLOW =
            new DustParticleOptions(new Vector3f(1.0F, 0.85F, 0.25F), 1.5F);
    private static final ParticleOptions RED =
            new DustParticleOptions(new Vector3f(1.0F, 0.25F, 0.25F), 1.5F);

    /** Shown while choosing a position. */
    public static final String MESSAGE_AIMING = "item.structurereset.structure_reset_core.aim.aiming";
    /** Shown when the crosshair hits nothing usable. */
    public static final String MESSAGE_NO_TARGET = "item.structurereset.structure_reset_core.aim.no_target";
    /** Shown when the aimed spot is illegal. */
    public static final String MESSAGE_ILLEGAL = "item.structurereset.structure_reset_core.aim.illegal";
    /** Shown while the cast runs. */
    public static final String MESSAGE_CASTING = "item.structurereset.structure_reset_core.aim.casting";
    /** Shown when the aim window ran out. */
    public static final String MESSAGE_EXPIRED = "item.structurereset.structure_reset_core.aim.expired";
    /** Shown when the cast finished and the reset ran. */
    public static final String MESSAGE_CAST_DONE = "item.structurereset.structure_reset_core.aim.done";
    /** Shown when the cast finished and only loot came back (a structure without a boss). */
    public static final String MESSAGE_CAST_DONE_LOOT =
            "item.structurereset.structure_reset_core.aim.done.loot";
    /** Shown when the mod picked a position instead of the player. */
    public static final String MESSAGE_RECOMMENDED =
            "item.structurereset.structure_reset_core.aim.recommended";
    /** Shown when the structure still holds an unbeaten boss. */
    public static final String MESSAGE_DEFEATED_NEEDED =
            "item.structurereset.structure_reset_core.aim.defeated_needed";

    private ResetAim() {
    }

    // ------------------------------------------------------------- querying

    /** The current phase of {@code player}, clearing a stale one on the way. */
    public static String phase(ServerPlayer player, ServerLevel level) {
        if (player == null || level == null) {
            return PHASE_NONE;
        }
        CompoundTag data = player.getPersistentData();
        String phase = data.getString(TAG_PHASE);
        if (phase == null || phase.isEmpty()) {
            return PHASE_NONE;
        }
        if (!level.dimension().location().toString().equals(data.getString(TAG_DIMENSION))) {
            clear(player);
            return PHASE_NONE;
        }
        if (level.getGameTime() > data.getLong(TAG_UNTIL)) {
            clear(player);
            return PHASE_NONE;
        }
        return phase;
    }

    /** The aimed position of {@code player}, when one is stored. */
    public static Optional<BlockPos> aimedAt(ServerPlayer player) {
        if (player == null) {
            return Optional.empty();
        }
        CompoundTag data = player.getPersistentData();
        if (data.getString(TAG_PHASE).isEmpty()) {
            return Optional.empty();
        }
        return Optional.of(new BlockPos(data.getInt(TAG_X), data.getInt(TAG_Y), data.getInt(TAG_Z)));
    }

    /**
     * The position a boss should be spawned at for this reset, when the player
     * aimed one.
     *
     * <p>Only a locked (casting) position counts: an aim that was never
     * confirmed must not decide where a boss appears.</p>
     */
    public static Optional<BlockPos> spawnOverride(ServerPlayer player) {
        if (player == null || !PHASE_CASTING.equals(player.getPersistentData().getString(TAG_PHASE))) {
            return Optional.empty();
        }
        return aimedAt(player);
    }

    // ------------------------------------------------------------ the state

    /** Forgets everything about a pending aim of {@code player}. */
    public static void clear(ServerPlayer player) {
        if (player == null) {
            return;
        }
        CompoundTag data = player.getPersistentData();
        data.putString(TAG_PHASE, PHASE_NONE);
        data.remove(TAG_X);
        data.remove(TAG_Y);
        data.remove(TAG_Z);
        data.remove(TAG_UNTIL);
        data.remove(TAG_DEFINITION);
        data.remove(TAG_DIMENSION);
        // The active model, the picked blocks and the legacy confirmation all
        // belong to the same window, so ending the aim ends every one of them.
        CastEffect.clear(player);
        CastBar.stop(player);
        ResetActivation.clearPlayer(player);
    }

    /**
     * Starts (or restarts) the aiming phase.
     *
     * @return true when the player is now aiming
     */
    public static boolean beginAiming(ServerPlayer player, ServerLevel level, String definitionId) {
        if (player == null || level == null) {
            return false;
        }
        Optional<BlockPos> target = readCrosshair(player, level);
        boolean recommended = false;
        if (target.isEmpty() || statusAt(level, target.get(), definitionId) == STATUS_ILLEGAL) {
            // Nothing usable under the crosshair: rather than refusing outright,
            // recommend a position derived from the boss's recorded spawn point,
            // the structure's containers and the player's own position. The
            // player still has to confirm it, and aiming at a good spot always
            // overrides it on the next tick.
            Optional<RespawnPointService.Recommendation> suggestion =
                    RespawnPointService.recommend(level, player, player.blockPosition());
            if (suggestion.isPresent()) {
                target = Optional.of(suggestion.get().position());
                recommended = true;
            }
        }
        if (target.isEmpty()) {
            player.displayClientMessage(Component.translatable(MESSAGE_NO_TARGET), true);
            return false;
        }
        store(player, level, PHASE_AIMING, target.get(), definitionId,
                level.getGameTime() + StructureResetConfig.aimWindowTicks());
        outline(level, target.get(),
                recommended ? STATUS_DEVIATES : statusAt(level, target.get(), definitionId));
        if (recommended) {
            player.displayClientMessage(Component.translatable(MESSAGE_RECOMMENDED), true);
        }
        return true;
    }

    /**
     * Locks the aimed position and starts the cast.
     *
     * @return true when the cast started
     */
    public static boolean lockAndCast(ServerPlayer player, ServerLevel level, String definitionId) {
        if (player == null || level == null) {
            return false;
        }
        Optional<BlockPos> target = aimedAt(player);
        if (target.isEmpty()) {
            return false;
        }
        int status = statusAt(level, target.get(), definitionId);
        if (status == STATUS_ILLEGAL) {
            player.displayClientMessage(Component.translatable(MESSAGE_ILLEGAL), true);
            return false;
        }
        store(player, level, PHASE_CASTING, target.get(), definitionId,
                level.getGameTime() + CastEffect.castTicks());
        CastEffect.begin(player, level, target.get());
        CastBar.start(player, CastEffect.styleOf(level),
                bringsBoss(level, target.get(), player, definitionId));
        return true;
    }

    /** Stores one phase and its position in the player's persistent data. */
    private static void store(ServerPlayer player, ServerLevel level, String phase,
                              BlockPos pos, String definitionId, long until) {
        CompoundTag data = player.getPersistentData();
        data.putString(TAG_PHASE, phase);
        data.putInt(TAG_X, pos.getX());
        data.putInt(TAG_Y, pos.getY());
        data.putInt(TAG_Z, pos.getZ());
        data.putLong(TAG_UNTIL, until);
        data.putString(TAG_DEFINITION, definitionId == null ? "" : definitionId);
        data.putString(TAG_DIMENSION, level.dimension().location().toString());
    }

    // ------------------------------------------------------------ the aim

    /** The block under the crosshair, in front of its face, or empty. */
    public static Optional<BlockPos> readCrosshair(ServerPlayer player, ServerLevel level) {
        double reach = Math.max(4, StructureResetConfig.aimReach());
        HitResult hit = player.pick(reach, 1.0F, false);
        if (hit == null || hit.getType() != HitResult.Type.BLOCK) {
            return Optional.empty();
        }
        BlockHitResult blockHit = (BlockHitResult) hit;
        return Optional.of(blockHit.getBlockPos().relative(blockHit.getDirection()));
    }

    /**
     * How legal an aimed position is.
     *
     * <p>Inside the structure and standing on solid ground is legal. A position
     * that is legal but more than {@link #DEVIATION_LIMIT} blocks away from the
     * recorded spawn point is reported as a deviation, so the mod can warn
     * instead of silently respawning a boss somewhere else entirely.</p>
     */
    public static int statusAt(ServerLevel level, BlockPos pos, String definitionId) {
        Optional<BoundingBox> box = ResetEngine.structureBoxAt(level, pos);
        if (StructureResetConfig.aimRequireInsideStructure()
                && (box.isEmpty() || !box.get().isInside(pos))) {
            return STATUS_ILLEGAL;
        }
        if (!isSafeStanding(level, pos)) {
            return STATUS_ILLEGAL;
        }
        Optional<BossRegistryData.BossRecord> record = BossBindingService.awaitingRespawn(level, pos);
        if (record.isEmpty()) {
            return STATUS_OK;
        }
        BossRegistryData.BossRecord known = record.get();
        double dx = pos.getX() - known.spawnX();
        double dy = pos.getY() - known.spawnY();
        double dz = pos.getZ() - known.spawnZ();
        return Math.sqrt(dx * dx + dy * dy + dz * dz) > DEVIATION_LIMIT
                ? STATUS_DEVIATES : STATUS_OK;
    }

    /** True when a mob can stand at {@code pos}: solid below, free here and above. */
    public static boolean isSafeStanding(ServerLevel level, BlockPos pos) {
        if (!level.getBlockState(pos).getCollisionShape(level, pos).isEmpty()) {
            return false;
        }
        if (!level.getBlockState(pos.above()).getCollisionShape(level, pos.above()).isEmpty()) {
            return false;
        }
        BlockPos below = pos.below();
        return !level.getBlockState(below).getCollisionShape(level, below).isEmpty();
    }

    /** Draws the 3x3 outline of an aimed position in the colour of its status. */
    public static void outline(ServerLevel level, BlockPos pos, int status) {
        ParticleOptions particle = status == STATUS_OK ? GREEN
                : (status == STATUS_DEVIATES ? YELLOW : RED);
        for (int dx = -1; dx <= 1; dx++) {
            for (int dz = -1; dz <= 1; dz++) {
                if (dx != 0 && dz != 0) {
                    continue;
                }
                level.sendParticles(particle, pos.getX() + 0.5D + dx * 0.5D,
                        pos.getY() + 0.15D, pos.getZ() + 0.5D + dz * 0.5D,
                        1, 0.0D, 0.0D, 0.0D, 0.0D);
            }
        }
        level.sendParticles(particle, pos.getX() + 0.5D, pos.getY() + 0.15D,
                pos.getZ() + 0.5D, 3, 0.15D, 0.05D, 0.15D, 0.0D);
    }

    // ------------------------------------------------------------ the ticks

    /** Advances every aiming and casting player once per server tick. */
    public static void onPlayerTick(TickEvent.PlayerTickEvent event) {
        try {
            tickPlayer(event);
        } catch (Throwable failure) {
            // This runs for every player, every tick. A failure must never reach
            // the server tick loop, so the frame is logged and dropped.
            StructureResetMod.LOGGER.warn("[{}] aimed respawn tick skipped",
                    StructureResetMod.MODID, failure);
        }
    }

    /** The real body of {@link #onPlayerTick(TickEvent.PlayerTickEvent)}. */
    private static void tickPlayer(TickEvent.PlayerTickEvent event) {
        if (event == null || event.phase != TickEvent.Phase.END) {
            return;
        }
        if (!(event.player instanceof ServerPlayer player)) {
            return;
        }
        if (!(player.level() instanceof ServerLevel level)) {
            return;
        }
        // Expiry is announced once here, because phase() clears a stale state
        // silently and the player would otherwise never learn that it ran out.
        CompoundTag data = player.getPersistentData();
        String raw = data.getString(TAG_PHASE);
        if (!raw.isEmpty() && level.getGameTime() > data.getLong(TAG_UNTIL)) {
            if (PHASE_AIMING.equals(raw)) {
                player.displayClientMessage(Component.translatable(MESSAGE_EXPIRED), true);
            }
            clear(player);
            return;
        }
        String phase = phase(player, level);
        if (PHASE_AIMING.equals(phase)) {
            tickAiming(player, level);
        } else if (PHASE_CASTING.equals(phase)) {
            tickCasting(player, level);
        }
    }

    /** The aiming phase: keep following the crosshair and draw it. */
    private static void tickAiming(ServerPlayer player, ServerLevel level) {
        CompoundTag data = player.getPersistentData();
        long until = data.getLong(TAG_UNTIL);
        String definitionId = data.getString(TAG_DEFINITION);
        Optional<BlockPos> crosshair = readCrosshair(player, level);
        // Only a usable aim takes over. A crosshair pointing nowhere usable must
        // never destroy the position the mod recommended a moment ago - that
        // recommendation exists precisely because the crosshair was unusable.
        if (crosshair.isPresent()) {
            // One status per tick and position: statusAt() walks the structure
            // manager and the boss registry, so asking it twice in the same tick
            // for the same spot is repeated work for no gain.
            int status = statusAt(level, crosshair.get(), definitionId);
            if (status != STATUS_ILLEGAL) {
                store(player, level, PHASE_AIMING, crosshair.get(), definitionId, until);
                outline(level, crosshair.get(), status);
                return;
            }
        }
        aimedAt(player).ifPresent(current ->
                outline(level, current, statusAt(level, current, definitionId)));
    }

    /** The casting phase: draw the locked position and finish the cast. */
    private static void tickCasting(ServerPlayer player, ServerLevel level) {
        Optional<BlockPos> target = aimedAt(player);
        if (target.isEmpty()) {
            clear(player);
            return;
        }
        long remaining = player.getPersistentData().getLong(TAG_UNTIL) - level.getGameTime();
        if (remaining > 0L) {
            outline(level, target.get(), STATUS_OK);
            int total = CastEffect.castTicks();
            long elapsed = Math.max(0L, Math.min(total, total - remaining));
            CastEffect.tick(player, level, target.get(), elapsed / (double) total);
            // The bar is drawn by the client and interpolated every frame, so it
            // drains smoothly; it carries no number, so the seconds stay in the
            // action bar where they are actually readable.
            CastBar.progress(player, (float) (total - elapsed) / (float) total);
            long seconds = (remaining + 19L) / 20L;
            player.displayClientMessage(Component.translatable(MESSAGE_CASTING, seconds), true);
            return;
        }
        finish(player, level, target.get());
    }

    /**
     * True when the definition that is being played brings a boss back.
     *
     * <p>It decides the wording of the cast bar and of the finished message: a
     * structure without a boss refills loot only, so saying "summoning" there would
     * be wrong. The lookup uses the position the cast is locked to, never the
     * position the player happens to stand in: a player who walks out of the
     * structure during the cast would otherwise turn a real boss summon into the
     * "loot only" wording.</p>
     */
    private static boolean bringsBoss(ServerLevel level, BlockPos pos, ServerPlayer player,
                                      String definitionId) {
        try {
            com.structurereset.api.ResetDefinition definition = StructureResetApi
                    .lookupDefinitionAt(level, pos, player).definition();
            return definition != null && definition.respawnBossEntity() != null
                    && !definition.respawnBossEntity().isBlank();
        } catch (RuntimeException failure) {
            // never let a wording decision break a cast
            return true;
        }
    }

    /**
     * Runs the reset at the locked position and updates the registry.
     *
     * <p>The aim position is used both as the reset origin and, through
     * {@link #spawnOverride}, as the spawn point of the boss.</p>
     */
    private static void finish(ServerPlayer player, ServerLevel level, BlockPos target) {
        CastEffect.finish(player, level, target);
        String definitionId = player.getPersistentData().getString(TAG_DEFINITION);
        String resolved = definitionId == null || definitionId.isEmpty() ? null : definitionId;
        // The item settings that count are the ones of the stack that really started
        // this cast - not the shipped core defaults, which is what an addon item with
        // its own ItemSettings would otherwise silently get.
        net.minecraft.world.item.ItemStack triggering = triggeringCore(player);
        int cooldownSeconds = com.structurereset.item.StructureResetItem.cooldownTicksFor(
                triggering, ResetEngine.resolveDefinition(level, target, player, resolved)) / 20;
        ResetResult result = StructureResetApi.triggerReset(level, target, player,
                resolved, false, ResetResult.MESSAGE_OK, cooldownSeconds);
        Set<String> structureIds = ResetEngine.structureIdsAt(level, target);
        BossBindingService.awaitingRespawn(level, target).ifPresent(record ->
                BossRegistryData.get(level).put(record.respawnedAt(target.getX(), target.getY(),
                        target.getZ(), player.getYRot(), player.getXRot(), level.getGameTime())));
        StructureResetMod.LOGGER.info("[{}] aimed reset at {}: success={} structures={}",
                StructureResetMod.MODID, target, result.success(), structureIds);
        if (result.success()) {
            player.displayClientMessage(Component.translatable(
                    bringsBoss(level, target, player, definitionId) ? MESSAGE_CAST_DONE
                            : MESSAGE_CAST_DONE_LOOT), true);
        } else {
            player.displayClientMessage(
                    Component.translatable(result.messageKey()).withStyle(
                            net.minecraft.ChatFormatting.RED), true);
        }
        if (result.success() && !triggering.isEmpty()) {
            player.getCooldowns().addCooldown(triggering.getItem(),
                    com.structurereset.item.StructureResetItem.cooldownTicksFor(triggering,
                            ResetEngine.resolveDefinition(level, target, player, resolved)));
            ResetActivation.clearActive(triggering);
        }
        clear(player);
    }

    /**
     * The reset core the player is holding, main hand first and off hand second.
     *
     * <p>Used for the cooldown the finished cast applies: the item that started
     * the cast is the one whose {@link com.structurereset.core.ItemSettingsRegistry}
     * entry decides the cooldown.</p>
     *
     * @param player the casting player, never null here
     * @return the held core, or an empty stack when the player holds none
     */
    private static net.minecraft.world.item.ItemStack triggeringCore(ServerPlayer player) {
        net.minecraft.world.item.ItemStack main = player.getMainHandItem();
        if (main.is(com.structurereset.registry.ModItems.STRUCTURE_RESET_CORE.get())) {
            return main;
        }
        net.minecraft.world.item.ItemStack off = player.getOffhandItem();
        return off.is(com.structurereset.registry.ModItems.STRUCTURE_RESET_CORE.get())
                ? off : net.minecraft.world.item.ItemStack.EMPTY;
    }

    // ------------------------------------------------------------- cleanup

    /** Drops a pending aim when the player leaves. */
    public static void onPlayerLoggedOut(PlayerEvent.PlayerLoggedOutEvent event) {
        if (event != null && event.getEntity() instanceof ServerPlayer player) {
            clear(player);
        }
    }

    /** Drops a pending aim when the player changes dimension. */
    public static void onPlayerChangedDimension(PlayerEvent.PlayerChangedDimensionEvent event) {
        if (event != null && event.getEntity() instanceof ServerPlayer player) {
            clear(player);
        }
    }

    /** Drops a pending aim when the player dies. */
    public static void onLivingDeath(LivingDeathEvent event) {
        if (event != null && event.getEntity() instanceof ServerPlayer player) {
            clear(player);
        }
    }
}
