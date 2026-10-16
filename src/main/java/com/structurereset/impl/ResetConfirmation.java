package com.structurereset.impl;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.event.entity.living.LivingDeathEvent;
import net.minecraftforge.event.entity.player.PlayerEvent;

/**
 * The per player timestamp store of the aiming window of the structure reset
 * core.
 *
 * <p>The two click interaction itself is driven by {@link ResetAim}: a first
 * right click opens the aiming window, a second one locks the spot and starts
 * the cast. This class only remembers <em>when</em> that window was opened
 * (together with the dimension and the definition it belongs to), so
 * {@link ResetActivation} can show a countdown and {@link ResetAim} can tell a
 * fresh window from a stale one. It no longer runs a reset of its own - the
 * class used to own a short "confirm again to reset" window, and the second
 * click now always goes through {@code ResetAim#lockAndCast}.</p>
 *
 * <p>The state lives in the player's own persistent data
 * ({@code ServerPlayer#getPersistentData()}), never in a static field, so it is
 * per player and survives exactly as long as the player does. Three things are
 * remembered: the world clock (in milliseconds) at which the window was opened,
 * the dimension it was opened in, and the id of the definition it belongs
 * to.</p>
 *
 * <p>The state is dropped again on logout, on a dimension change and on death,
 * because none of those may carry a window into another context, and
 * {@link ResetActivation#clearPlayer(ServerPlayer)} drops it whenever the
 * window is closed.</p>
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
     * Records that the aiming window of {@code player} was opened for
     * {@code definitionId} in {@code level}.
     *
     * <p>The world game time is converted to milliseconds ({@code gameTime * 50})
     * because that is the unit
     * {@code CooldownPolicy#withinWindow(long, long, int)} compares against, which
     * is how {@link ResetActivation} turns this timestamp back into a countdown.
     * </p>
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

    /** Forgets a pending window; safe to call when there is none. */
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
