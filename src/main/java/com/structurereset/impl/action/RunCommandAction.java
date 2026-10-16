package com.structurereset.impl.action;

import com.structurereset.StructureResetMod;
import com.structurereset.core.ResetActionType;
import com.structurereset.core.ResetContext;
import com.structurereset.core.ResetResult;
import java.util.List;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;

/**
 * {@code structurereset:run_command} - runs one command as the server or as the
 * acting player.
 *
 * <p>Parameters: {@code command} (required, placeholders such as
 * {@code ${player}} are resolved by
 * {@link com.structurereset.api.Placeholders}), {@code as} ({@code server} by
 * default, or {@code player}) and {@code permission_level} (default 2, used for
 * the server source).</p>
 */
public final class RunCommandAction implements ResetActionType {

    /** The name part of this action id. */
    public static final String NAME = "run_command";
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
        String raw = ctx.paramString("command", "");
        if (raw.isBlank()) {
            return ResetResult.fail(MESSAGE_FAILED, "run_command requires a params.command");
        }
        // A leading "/" needs no special handling: performPrefixedCommand strips it
        // itself, and stripping it here as well only meant doing the same work twice.
        String command = ctx.placeholders(raw);
        if (command.isBlank()) {
            return ResetResult.fail(MESSAGE_FAILED, "the command of run_command resolved to nothing");
        }
        MinecraftServer server = ctx.level().getServer();
        if (server == null) {
            return ResetResult.fail(MESSAGE_FAILED, "run_command needs a running server");
        }
        boolean asPlayer = "player".equalsIgnoreCase(ctx.paramString("as", "server"));
        ServerPlayer actor = ctx.actor();
        CommandSourceStack source;
        if (asPlayer && actor != null) {
            source = actor.createCommandSourceStack();
        } else {
            int permission = Math.max(0, ctx.paramInt("permission_level", 2));
            source = server.createCommandSourceStack().withPermission(permission);
        }
        try {
            int executed = server.getCommands().performPrefixedCommand(source, command);
            if (executed == 0) {
                // A command that ran no branch at all (a silent parse failure or a
                // false requirement) must not be reported as a successful action.
                return ResetResult.fail(MESSAGE_FAILED,
                        "the command '" + command + "' did not run (it returned 0)");
            }
        } catch (RuntimeException failure) {
            return ResetResult.fail(MESSAGE_FAILED, "the command '" + command + "' failed: " + failure);
        }
        return ResetResult.ok(List.of(id()));
    }
}
