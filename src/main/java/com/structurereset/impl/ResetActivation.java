package com.structurereset.impl;

import com.structurereset.api.CooldownPolicy;
import com.structurereset.config.StructureResetConfig;
import com.structurereset.registry.ModItems;
import net.minecraft.ChatFormatting;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.ItemStack;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.event.entity.living.LivingDeathEvent;
import net.minecraftforge.event.entity.player.PlayerEvent;

/**
 * The armed "activation window" of the two click confirmation of the structure
 * reset core.
 *
 * <p>The first right click arms a pending confirmation (see
 * {@link ResetConfirmation}) and additionally expresses that armed state on the
 * held stack, so the player can <em>see</em> it: the classic 1.20.1 NBT tag
 * {@code CustomModelData = 100} selects the
 * {@code structurereset:item/structure_reset_core_active} model and the item
 * glows with its active texture. While the window is open a server side
 * {@link TickEvent.PlayerTickEvent} handler shows the remaining whole seconds on
 * the action bar, and when the window closes the tag is removed again and the
 * player is told once that the window expired.</p>
 *
 * <p>The vanilla item cooldown is deliberately <em>not</em> used for this:
 * {@code ServerPlayerGameMode#useItem} refuses to use a stack whose item is on
 * cooldown ({@code ServerPlayer#getCooldowns().isOnCooldown(item)}), so an
 * activation window expressed as {@code addCooldown} would swallow the very
 * click that confirms the reset. The purple cooldown mask therefore stays
 * reserved for the cooldown applied <em>after</em> a real reset, and the
 * activation window is expressed with the item model override instead - exactly
 * what {@code CustomModelData} is for in 1.20.1.</p>
 *
 * <p>The mark is cleared whenever the window closes, a reset runs, the player
 * logs out, changes dimension or dies, so no stale "active" model can survive
 * into another context.</p>
 */
public final class ResetActivation {

    /** The {@code CustomModelData} value that selects the active item model. */
    public static final int ACTIVE_MODEL_DATA = 100;

    /** The classic 1.20.1 NBT key carrying the item model override. */
    public static final String TAG_CUSTOM_MODEL_DATA = "CustomModelData";

    /** Persistent data flag: the player really had an active activation window. */
    public static final String TAG_ACTIVE = "structurereset:confirm_active";

    /** Persistent data value: the whole second of the window that was last shown. */
    public static final String TAG_SHOWN_SECOND = "structurereset:confirm_shown_second";

    /** Translation key of the armed countdown line. */
    public static final String MESSAGE_ACTIVE = "item.structurereset.structure_reset_core.aim.aiming";
    /** Translation key of the "the window expired" line. */
    public static final String MESSAGE_EXPIRED = "item.structurereset.structure_reset_core.aim.expired";

    private ResetActivation() {
    }

    // ------------------------------------------------------------- the mark

    /**
     * Marks a stack as "activation window open" by writing
     * {@code CustomModelData = 100} into its NBT.
     *
     * @param stack the stack to mark, may be null or empty
     */
    public static void markActive(ItemStack stack) {
        if (stack == null || stack.isEmpty()) {
            return;
        }
        stack.getOrCreateTag().putInt(TAG_CUSTOM_MODEL_DATA, ACTIVE_MODEL_DATA);
    }

    /**
     * Removes the activation mark of a stack.
     *
     * <p>Only the key is removed; a tag that becomes empty afterwards is
     * dropped entirely ({@code setTag(null)}), so an unmarked stack carries no
     * remnant of the activation.</p>
     *
     * @param stack the stack to unmark, may be null or empty
     */
    public static void clearActive(ItemStack stack) {
        if (stack == null || stack.isEmpty()) {
            return;
        }
        CompoundTag tag = stack.getTag();
        if (tag == null) {
            return;
        }
        tag.remove(TAG_CUSTOM_MODEL_DATA);
        if (tag.isEmpty()) {
            stack.setTag(null);
        }
    }

    /**
     * True when a stack currently carries the activation mark.
     *
     * @param stack the stack to inspect, may be null
     */
    public static boolean isActive(ItemStack stack) {
        if (stack == null || stack.isEmpty()) {
            return false;
        }
        CompoundTag tag = stack.getTag();
        return tag != null && tag.contains(TAG_CUSTOM_MODEL_DATA)
                && tag.getInt(TAG_CUSTOM_MODEL_DATA) == ACTIVE_MODEL_DATA;
    }

    // -------------------------------------------------------------- state

    /**
     * Arms the confirmation for {@code definitionId} and marks {@code stack} as
     * active.
     *
     * <p>The "shown second" marker is reset, so the countdown line is printed
     * immediately on the next tick instead of waiting for the next whole
     * second.</p>
     *
     * @param player the acting player, required
     * @param level the level the click happened in, required
     * @param definitionId the definition the confirming click would run
     * @param stack the held core stack to mark
     */
    public static void activate(ServerPlayer player, ServerLevel level, String definitionId,
                                ItemStack stack) {
        if (player == null || level == null) {
            return;
        }
        ResetConfirmation.arm(player, level, definitionId);
        markActive(stack);
        CompoundTag data = player.getPersistentData();
        data.putBoolean(TAG_ACTIVE, true);
        data.remove(TAG_SHOWN_SECOND);
    }

    /**
     * Clears the whole activation state of a player: the mark of every
     * {@code structurereset:structure_reset_core} stack in the main hand, the
     * off hand and the main inventory, the pending confirmation and the two
     * bookkeeping tags.
     *
     * @param player the player to clear, may be null
     */
    public static void clearPlayer(ServerPlayer player) {
        if (player == null) {
            return;
        }
        clearActive(player.getMainHandItem());
        clearActive(player.getOffhandItem());
        Inventory inventory = player.getInventory();
        if (inventory != null) {
            for (int slot = 0; slot < inventory.getContainerSize(); slot++) {
                ItemStack stack = inventory.getItem(slot);
                if (!stack.isEmpty() && stack.is(ModItems.STRUCTURE_RESET_CORE.get())) {
                    clearActive(stack);
                }
            }
        }
        ResetConfirmation.clear(player);
        CompoundTag data = player.getPersistentData();
        data.remove(TAG_ACTIVE);
        data.remove(TAG_SHOWN_SECOND);
    }

    // ------------------------------------------------------- server tick

    /**
     * The server side activation window: for every armed player it recomputes
     * the remaining time of the window, prints the countdown line only when the
     * displayed whole second changed, and closes the window once with the
     * "expired" line.
     *
     * @param event the player tick event, both phases are ignored except END
     */
    public static void onPlayerTick(TickEvent.PlayerTickEvent event) {
        if (event == null || event.phase != TickEvent.Phase.END) {
            return;
        }
        if (!(event.player instanceof ServerPlayer player)) {
            return;
        }
        if (!(player.level() instanceof ServerLevel level)) {
            return;
        }
        CompoundTag data = player.getPersistentData();
        long armedAt = data.getLong(ResetConfirmation.TAG_ARMED_AT);
        if (armedAt <= 0L) {
            // No pending confirmation at all: nothing to count down and
            // nothing to expire.
            return;
        }
        if (!ResetAim.PHASE_NONE.equals(ResetAim.phase(player, level))) {
            // The crosshair driven respawn owns the window while it runs. The
            // legacy confirmation window is much shorter than the aiming window,
            // so expiring here would cut a perfectly valid aim short.
            return;
        }
        int windowSeconds = StructureResetConfig.confirmWindowSeconds();
        long now = ResetEngine.nowMillis(level);
        boolean sameDimension = level.dimension().location().toString()
                .equals(data.getString(ResetConfirmation.TAG_DIMENSION));
        if (!sameDimension || !CooldownPolicy.withinWindow(now, armedAt, windowSeconds)) {
            boolean wasActive = data.getBoolean(TAG_ACTIVE);
            clearPlayer(player);
            if (wasActive) {
                player.displayClientMessage(Component.translatable(MESSAGE_EXPIRED)
                        .withStyle(ChatFormatting.RED), true);
            }
            return;
        }
        int secondsLeft = secondsLeft(armedAt, now, windowSeconds);
        if (!data.contains(TAG_SHOWN_SECOND)
                || data.getLong(TAG_SHOWN_SECOND) != (long) secondsLeft) {
            data.putLong(TAG_SHOWN_SECOND, (long) secondsLeft);
            player.displayClientMessage(Component.translatable(MESSAGE_ACTIVE)
                    .withStyle(ChatFormatting.GOLD), true);
        }
    }

    /**
     * The whole seconds left of a window, never negative.
     *
     * @param armedAt the world clock (milliseconds) the window was armed at
     * @param now the current world clock (milliseconds)
     * @param windowSeconds the length of the window
     */
    static int secondsLeft(long armedAt, long now, int windowSeconds) {
        if (windowSeconds <= 0 || armedAt <= 0L) {
            return 0;
        }
        long remaining = (armedAt + (long) windowSeconds * 1000L) - now;
        if (remaining <= 0L) {
            return 0;
        }
        return (int) (remaining / 1000L);
    }

    // ------------------------------------------------------ forge events

    /** No activation survives leaving the server. */
    public static void onPlayerLoggedOut(PlayerEvent.PlayerLoggedOutEvent event) {
        if (event != null && event.getEntity() instanceof ServerPlayer player) {
            clearPlayer(player);
        }
    }

    /** The activation window is bound to one dimension. */
    public static void onPlayerChangedDimension(PlayerEvent.PlayerChangedDimensionEvent event) {
        if (event != null && event.getEntity() instanceof ServerPlayer player) {
            clearPlayer(player);
        }
    }

    /** Death drops the activation window of a player, and nothing else. */
    public static void onLivingDeath(LivingDeathEvent event) {
        if (event != null && event.getEntity() instanceof ServerPlayer player) {
            clearPlayer(player);
        }
    }
}
