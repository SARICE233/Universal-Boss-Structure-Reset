package com.structurereset.impl;

import com.structurereset.api.CooldownPolicy;
import com.structurereset.config.StructureResetConfig;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.event.entity.living.LivingDeathEvent;
import net.minecraftforge.event.entity.player.PlayerEvent;
import org.jetbrains.annotations.Nullable;

/**
 * The per player state machine of the two click confirmation of the structure
 * reset core.
 *
 * <p>The first right click never resets anything: it only announces what would
 * happen and arms the player here. A second right click inside
 * {@link StructureResetConfig#confirmWindowSeconds()} runs the ordinary reset
 * flow; a later one is treated as a brand new first click.</p>
 *
 * <p>The state lives in the player's own persistent data
 * ({@code ServerPlayer#getPersistentData()}), never in a static field, so it is
 * per player and survives exactly as long as the player does. Three things are
 * remembered: the world clock (in milliseconds, so it matches
 * {@link CooldownPolicy#withinWindow(long, long, int)}) at which the reset was
 * announced, the dimension it was announced in, and the id of the announced
 * definition.</p>
 *
 * <p>The state is dropped again on logout, on a dimension change and on death,
 * because none of those may carry a pending confirmation into another context.
 * The engine also clears it as soon as a check finds it expired or belonging to
 * another dimension.</p>
 */
public final class ResetConfirmation {

    /** Key of the arming world clock (milliseconds) inside the player's persistent data. */
    public static final String TAG_ARMED_AT = "structurereset:confirm_armed_at";
    /** Key of the dimension id the confirmation was armed in. */
    public static final String TAG_DIMENSION = "structurereset:confirm_dimension";
    /** Key of the definition id the confirmation was armed for. */
    public static final String TAG_DEFINITION = "structurereset:confirm_definition";

    private ResetConfirmation() {
    }

    /**
     * Arms the two click confirmation of {@code player} for {@code definitionId}
     * in {@code level}.
     *
     * <p>The world game time is converted to milliseconds ({@code gameTime * 50})
     * because that is the unit
     * {@link CooldownPolicy#withinWindow(long, long, int)} compares against
     * {@code windowSeconds * 1000L}.</p>
     */
    public static void arm(ServerPlayer player, ServerLevel level, String definitionId) {
        if (player == null || level == null) {
            return;
        }
        CompoundTag data = player.getPersistentData();
        data.putLong(TAG_ARMED_AT, level.getGameTime() * 50L);
        data.putString(TAG_DIMENSION, level.dimension().location().toString());
        data.putString(TAG_DEFINITION, definitionId == null ? "" : definitionId);
    }

    /**
     * True when a second right click at {@code nowMillis} really confirms a
     * reset of {@code level}.
     *
     * <p>A confirmation armed in another dimension or outside the configured
     * window is stale: it is cleared here and reported as not armed, so the
     * click is handled as a new first click.</p>
     *
     * @param player the acting player, may be null
     * @param level the level the click happens in, may be null
     * @param nowMillis the current world clock in milliseconds
     */
    public static boolean isArmed(ServerPlayer player, ServerLevel level, long nowMillis) {
        if (player == null || level == null) {
            return false;
        }
        CompoundTag data = player.getPersistentData();
        long armedAt = data.getLong(TAG_ARMED_AT);
        if (armedAt <= 0L) {
            return false;
        }
        if (!level.dimension().location().toString().equals(data.getString(TAG_DIMENSION))) {
            clear(player);
            return false;
        }
        if (!CooldownPolicy.withinWindow(nowMillis, armedAt, StructureResetConfig.confirmWindowSeconds())) {
            clear(player);
            return false;
        }
        return true;
    }

    /**
     * The definition id the player's pending confirmation was armed for, or
     * null when none is pending. Purely informational: the confirming click
     * selects its definition again, exactly like a single click flow does.
     */
    @Nullable
    public static String armedDefinitionId(ServerPlayer player) {
        if (player == null) {
            return null;
        }
        CompoundTag data = player.getPersistentData();
        if (data.getLong(TAG_ARMED_AT) <= 0L) {
            return null;
        }
        return data.getString(TAG_DEFINITION);
    }

    /** Forgets a pending confirmation; safe to call when there is none. */
    public static void clear(ServerPlayer player) {
        if (player == null) {
            return;
        }
        CompoundTag data = player.getPersistentData();
        data.remove(TAG_ARMED_AT);
        data.remove(TAG_DIMENSION);
        data.remove(TAG_DEFINITION);
    }

    // ------------------------------------------------------ forge events

    /** A pending confirmation never survives leaving the server. */
    public static void onPlayerLoggedOut(PlayerEvent.PlayerLoggedOutEvent event) {
        if (event != null && event.getEntity() instanceof ServerPlayer player) {
            clear(player);
        }
    }

    /** A pending confirmation is bound to one dimension. */
    public static void onPlayerChangedDimension(PlayerEvent.PlayerChangedDimensionEvent event) {
        if (event != null && event.getEntity() instanceof ServerPlayer player) {
            clear(player);
        }
    }

    /** Death drops the pending confirmation of a player, and nothing else. */
    public static void onLivingDeath(LivingDeathEvent event) {
        if (event != null && event.getEntity() instanceof ServerPlayer player) {
            clear(player);
        }
    }
}
