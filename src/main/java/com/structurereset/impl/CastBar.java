package com.structurereset.impl;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerBossEvent;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.BossEvent;

/**
 * The cast indicator: a real boss bar that drains while the cast runs.
 *
 * <p>A boss bar is drawn by the client with interpolation, so it moves smoothly
 * at frame rate instead of in twenty one-character steps. It is used instead of
 * a text bar for exactly that reason, and the remaining seconds stay in the
 * action bar, where they belong.</p>
 *
 * <p>The bar is created on the server and fed to a single player, so it needs no
 * client code and no packet of our own. Every path that ends a cast ends the bar,
 * and a player who leaves the game drops theirs.</p>
 */
public final class CastBar {

    /** Translation key of the bar's title. */
    public static final String TITLE = "item.structurereset.structure_reset_core.aim.bar";

    private static final Map<UUID, ServerBossEvent> BARS = new HashMap<>();

    private CastBar() {
    }

    /** Translation key of the bar's title when only loot is refilled. */
    public static final String TITLE_LOOT = "item.structurereset.structure_reset_core.aim.bar.loot";

    /** Starts (or restarts) the bar of a player, full and ready to drain. */
    public static void start(ServerPlayer player, int style) {
        start(player, style, true);
    }

    /**
     * Starts the bar with the wording of the reset that is about to run.
     *
     * @param player the player whose bar is started
     * @param style the effect style, which decides the colour
     * @param bringsBoss true when a boss will be summoned, false for a loot only reset
     */
    public static void start(ServerPlayer player, int style, boolean bringsBoss) {
        if (player == null) {
            return;
        }
        stop(player);
        ServerBossEvent bar = new ServerBossEvent(
                Component.translatable(bringsBoss ? TITLE : TITLE_LOOT),
                colorOf(style), BossEvent.BossBarOverlay.PROGRESS);
        bar.setProgress(1.0F);
        bar.setVisible(true);
        bar.addPlayer(player);
        BARS.put(player.getUUID(), bar);
    }

    /**
     * Drains the bar.
     *
     * @param remaining how much of the cast is left, 1 at its start and 0 at its end
     */
    public static void progress(ServerPlayer player, float remaining) {
        if (player == null) {
            return;
        }
        ServerBossEvent bar = BARS.get(player.getUUID());
        if (bar != null) {
            bar.setProgress(Math.max(0.0F, Math.min(1.0F, remaining)));
        }
    }

    /** Removes the bar of a player, if it has one. */
    public static void stop(ServerPlayer player) {
        if (player == null) {
            return;
        }
        ServerBossEvent bar = BARS.remove(player.getUUID());
        if (bar != null) {
            bar.removeAllPlayers();
            bar.setVisible(false);
        }
    }

    /** Drops every bar; used when the server stops. */
    public static void stopAll() {
        for (ServerBossEvent bar : BARS.values()) {
            bar.removeAllPlayers();
            bar.setVisible(false);
        }
        BARS.clear();
    }

    /** The bar colour of a {@link CastEffect} style. */
    private static BossEvent.BossBarColor colorOf(int style) {
        return switch (style) {
            case CastEffect.STYLE_OVERWORLD -> BossEvent.BossBarColor.BLUE;
            case CastEffect.STYLE_NETHER -> BossEvent.BossBarColor.RED;
            default -> BossEvent.BossBarColor.PURPLE;
        };
    }
}
