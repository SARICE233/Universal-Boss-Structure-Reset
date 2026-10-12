package com.structurereset.impl;

import com.structurereset.StructureResetMod;
import com.structurereset.registry.ModItems;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraftforge.event.entity.player.PlayerInteractEvent;
import net.minecraftforge.eventbus.api.Event;

/**
 * The left mouse button half of the core item: capturing a structure's loot
 * tables before they are lost.
 *
 * <p>This is intentionally a separate path from the right mouse button reset
 * flow. Left clicking never resets anything, never consumes the item and never
 * starts a cooldown; right clicking never captures anything. The two only share
 * the structure lookup and the {@link LootIndexData} store.</p>
 *
 * <p>Only {@code LeftClickBlock} is used: in 1.20.1 a left click into thin air
 * never reaches the server, so there is no server side event to hook for it.
 * The block use is denied so the click cannot also mine the block.</p>
 */
public final class LootCaptureHandler {

    private LootCaptureHandler() {
    }

    /** Captures the loot tables around the player when the core is left clicked on a block. */
    public static void onLeftClickBlock(PlayerInteractEvent.LeftClickBlock event) {
        try {
            capture(event);
        } catch (Throwable failure) {
            // Capturing must never be able to break a click, let alone the tick loop.
            StructureResetMod.LOGGER.warn("[{}] loot capture skipped one click",
                    StructureResetMod.MODID, failure);
        }
    }

    /** The real body of {@link #onLeftClickBlock(PlayerInteractEvent.LeftClickBlock)}. */
    private static void capture(PlayerInteractEvent.LeftClickBlock event) {
        if (event == null || event.getAction() != PlayerInteractEvent.LeftClickBlock.Action.START) {
            return;
        }
        Player player = event.getEntity();
        if (player == null || player.level().isClientSide()) {
            return;
        }
        if (!(player instanceof ServerPlayer serverPlayer)) {
            return;
        }
        ItemStack stack = event.getItemStack();
        if (stack == null || !stack.is(ModItems.STRUCTURE_RESET_CORE.get())) {
            return;
        }
        // Left click is the capture key for this item, so it must never mine.
        event.setUseBlock(Event.Result.DENY);

        LootIndexService.CaptureResult result = LootIndexService.capture(serverPlayer);
        if (result.recorded() > 0) {
            serverPlayer.displayClientMessage(Component.translatable(
                    LootIndexService.MESSAGE_CAPTURED, result.recorded(), result.alreadyOpened()), true);
        } else {
            serverPlayer.displayClientMessage(Component.translatable(
                    LootIndexService.MESSAGE_NONE, result.alreadyOpened()), true);
        }
        StructureResetMod.LOGGER.info(
                "[{}] loot capture at {} {} {}: recorded={} alreadyOpened={} structures={} boundByRadius={}",
                StructureResetMod.MODID, result.x(), result.y(), result.z(), result.recorded(),
                result.alreadyOpened(), result.structures(), result.boundByRadius());
    }
}
