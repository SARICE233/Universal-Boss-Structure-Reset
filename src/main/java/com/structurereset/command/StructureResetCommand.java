package com.structurereset.command;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import com.structurereset.api.LearnedAnchor;
import com.structurereset.impl.LearnedAnchorService;
import java.util.List;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;

/**
 * The operator command tree of the addon.
 *
 * <p>The whole tree hangs off the root literal {@code structurereset} and is
 * gated with {@code hasPermission(2)}, so only operators (and the server
 * console) ever reach it:</p>
 *
 * <pre>
 * /structurereset records list
 * /structurereset records remove &lt;id&gt;
 * /structurereset records clear
 * /structurereset records clear confirm
 * /structurereset records teach-here &lt;entity&gt;
 * </pre>
 *
 * <p>The whole tree is registered twice: once under {@code records} and once
 * under the old name {@code learned}, which stays as an alias for servers and
 * scripts written before the rename. Both registrations expose exactly the same
 * five sub commands.</p>
 *
 * <p>{@code records list} prints one line per remembered anchor, {@code records
 * remove <id>} forgets a single record and {@code records clear} deliberately
 * needs the explicit trailing {@code confirm} before it wipes everything.
 * {@code records teach-here <entity>} is the command line twin of sneaking and
 * using the core on a mob: the operator names the entity to record and the
 * record is anchored at the operator's own position. It skips only the two
 * positional gates of the item path (stand inside a structure, look at the
 * target); the enable switch and the whole entity classification still
 * apply.</p>
 *
 * <p>Every reply goes through {@code source.sendSuccess(..., false)} so it is
 * broadcast to the command source only, and each sub command returns 1 when it
 * changed or listed something and 0 when there was nothing to do.</p>
 */
public final class StructureResetCommand {

    /** Header sent once before the entries of a non empty {@code learned list}. */
    private static final String MESSAGE_LIST_HEADER = "structurereset.command.learned.list.header";
    /** One remembered anchor of {@code learned list}. */
    private static final String MESSAGE_LIST_ENTRY = "structurereset.command.learned.list.entry";
    /** Sent by {@code learned list} when nothing was learned yet. */
    private static final String MESSAGE_LIST_EMPTY = "structurereset.command.learned.list.empty";
    /** Sent when {@code learned remove <id>} deleted a record. */
    private static final String MESSAGE_REMOVE_OK = "structurereset.command.learned.remove.ok";
    /** Sent when {@code learned remove <id>} found no such record. */
    private static final String MESSAGE_REMOVE_MISSING = "structurereset.command.learned.remove.missing";
    /** Sent by a bare {@code learned clear}, which never deletes anything. */
    private static final String MESSAGE_CLEAR_CONFIRM = "structurereset.command.learned.clear.confirm";
    /** Sent by {@code learned clear confirm} with the number of deleted records. */
    private static final String MESSAGE_CLEAR_OK = "structurereset.command.learned.clear.ok";

    /** Static use only. */
    private StructureResetCommand() {
    }

    /**
     * Registers the tree on the server dispatcher; called from the
     * {@code RegisterCommandsEvent} listener of the mod.
     *
     * @param dispatcher the dispatcher of the running server
     */
    public static void register(CommandDispatcher<CommandSourceStack> dispatcher) {
        dispatcher.register(Commands.literal("structurereset")
                .requires(source -> source.hasPermission(2))
                .then(Commands.literal("records")
                        .then(Commands.literal("list")
                                .executes(context -> list(context.getSource())))
                        .then(Commands.literal("remove")
                                .then(Commands.argument("id", StringArgumentType.string())
                                        .executes(context -> remove(context.getSource(),
                                                StringArgumentType.getString(context, "id")))))
                        .then(Commands.literal("clear")
                                .executes(context -> confirmClear(context.getSource()))
                                .then(Commands.literal("confirm")
                                        .executes(context -> clear(context.getSource()))))
                        .then(Commands.literal("teach-here")
                                .then(Commands.argument("entity", StringArgumentType.string())
                                        .executes(context -> teachHere(context.getSource(),
                                                StringArgumentType.getString(context, "entity")))))));
        // The name this branch had before the rename stays as an alias, so servers
        // and scripts written against "learned" keep working unchanged.
        dispatcher.register(Commands.literal("structurereset")
                .requires(source -> source.hasPermission(2))
                .then(Commands.literal("learned")
                        .then(Commands.literal("list")
                                .executes(context -> list(context.getSource())))
                        .then(Commands.literal("remove")
                                .then(Commands.argument("id", StringArgumentType.string())
                                        .executes(context -> remove(context.getSource(),
                                                StringArgumentType.getString(context, "id")))))
                        .then(Commands.literal("clear")
                                .executes(context -> confirmClear(context.getSource()))
                                .then(Commands.literal("confirm")
                                        .executes(context -> clear(context.getSource()))))
                        .then(Commands.literal("teach-here")
                                .then(Commands.argument("entity", StringArgumentType.string())
                                        .executes(context -> teachHere(context.getSource(),
                                                StringArgumentType.getString(context, "entity")))))));
    }

    /**
     * Lists every remembered anchor of the executing dimension store.
     *
     * <p>A non empty list first sends {@code list.header} and then one
     * {@code list.entry} per anchor, always with the same argument order:
     * id, dimension, structures, boss, x, y, z, radius, containers.  An anchor
     * without structures sends {@code -} instead of an empty list.</p>
     *
     * @param source the command source
     * @return 1 when at least one anchor was listed, 0 when the list was empty
     */
    private static int list(CommandSourceStack source) {
        List<LearnedAnchor> anchors = LearnedAnchorService.all(source.getLevel());
        if (anchors.isEmpty()) {
            source.sendSuccess(() -> Component.translatable(MESSAGE_LIST_EMPTY), false);
            return 0;
        }
        source.sendSuccess(() -> Component.translatable(MESSAGE_LIST_HEADER), false);
        for (LearnedAnchor anchor : anchors) {
            String structures = anchor.structureIds().isEmpty()
                    ? "-" : String.join(", ", anchor.structureIds());
            source.sendSuccess(() -> Component.translatable(MESSAGE_LIST_ENTRY,
                    anchor.id(), anchor.dimension(), structures, anchor.bossEntity(),
                    anchor.x(), anchor.y(), anchor.z(), anchor.radius(),
                    anchor.containers().size()), false);
        }
        return 1;
    }

    /**
     * Forgets the anchor with the given id.
     *
     * @param source the command source
     * @param id the learning id to forget
     * @return 1 when a record was deleted, 0 when the id was unknown
     */
    private static int remove(CommandSourceStack source, String id) {
        if (LearnedAnchorService.remove(source.getLevel(), id)) {
            source.sendSuccess(() -> Component.translatable(MESSAGE_REMOVE_OK, id), false);
            return 1;
        }
        source.sendSuccess(() -> Component.translatable(MESSAGE_REMOVE_MISSING, id), false);
        return 0;
    }

    /**
     * The bare {@code records clear}: it only asks for the confirmation and
     * never deletes anything.
     *
     * @param source the command source
     * @return always 0, because nothing was cleared
     */
    private static int confirmClear(CommandSourceStack source) {
        source.sendSuccess(() -> Component.translatable(MESSAGE_CLEAR_CONFIRM), false);
        return 0;
    }

    /**
     * {@code records clear confirm}: wipes every record and reports how many
     * were removed.
     *
     * @param source the command source
     * @return always 1
     */
    private static int clear(CommandSourceStack source) {
        ServerLevel level = source.getLevel();
        int before = LearnedAnchorService.all(level).size();
        LearnedAnchorService.clear(level);
        source.sendSuccess(() -> Component.translatable(MESSAGE_CLEAR_OK, before), false);
        return 1;
    }

    /**
     * {@code records teach-here <entity>}: records the named entity as the boss of
     * the structure the operator stands in, anchored at the operator's own
     * position.
     *
     * <p>It skips only the two positional gates of the item path: the operator does
     * not have to stand inside a structure this mod can see and does not have to
     * look at the target. The enable switch and the whole entity classification
     * still apply, so the command can never record a mob the sneaking right click
     * would refuse.</p>
     *
     * @param source the command source, which must be a player
     * @param entityId the entity type id the new record should name
     * @return 1 when an anchor was recorded, 0 when the id was unknown or the
     *         teaching was refused
     * @throws CommandSyntaxException when the source is not a player
     */
    private static int teachHere(CommandSourceStack source, String entityId) throws CommandSyntaxException {
        ServerPlayer player = source.getPlayerOrException();
        LearnedAnchorService.TeachResult result = LearnedAnchorService.teachEntity(player, entityId);
        source.sendSuccess(() -> result.message(), !result.success());
        return result.success() ? 1 : 0;
    }

}
