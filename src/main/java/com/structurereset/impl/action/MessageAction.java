package com.structurereset.impl.action;

import com.structurereset.StructureResetMod;
import com.structurereset.core.ResetActionType;
import com.structurereset.core.ResetContext;
import com.structurereset.core.ResetResult;
import java.util.List;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;

/**
 * {@code structurereset:message} - sends a chat message.
 *
 * <p>Parameters: {@code text} (placeholders such as {@code ${definition}} are
 * resolved by {@link com.structurereset.api.Placeholders}) and {@code target}
 * ({@code actor} by default, or {@code server} to broadcast to everyone).</p>
 */
public final class MessageAction implements ResetActionType {

    /** The name part of this action id. */
    public static final String NAME = "message";
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
        String text = ctx.placeholders(ctx.paramString("text", ""));
        if (text.isBlank()) {
            return ResetResult.fail(MESSAGE_FAILED, "message requires a params.text");
        }
        Component component = Component.literal(text);
        if ("server".equalsIgnoreCase(ctx.paramString("target", "actor"))) {
            MinecraftServer server = ctx.level().getServer();
            if (server == null) {
                return ResetResult.fail(MESSAGE_FAILED, "a broadcast message needs a running server");
            }
            server.getPlayerList().broadcastSystemMessage(component, false);
        } else if (ctx.hasActor()) {
            ctx.actor().sendSystemMessage(component);
        }
        return ResetResult.ok(List.of(id()));
    }
}
