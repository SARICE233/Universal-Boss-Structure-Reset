package com.structurereset.core.event;

import com.structurereset.api.ResetDefinition;
import com.structurereset.core.ResetResult;
import java.util.List;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.phys.AABB;
import net.minecraftforge.eventbus.api.Event;

/**
 * Posted after a structure reset ran, whether it succeeded or not.
 *
 * <p>The event is not cancelable; it exists so that addons can react to a
 * finished reset (announce it, award something, write a log, ...).</p>
 */
public class ResetPostEvent extends Event {

    private final ServerLevel level;
    private final BlockPos origin;
    private final AABB box;
    private final ServerPlayer actor;
    private final ResetDefinition definition;
    private final ResetResult result;
    private String message;

    public ResetPostEvent(ServerLevel level, BlockPos origin, AABB box, ServerPlayer actor,
                          ResetDefinition definition, ResetResult result, String message) {
        this.level = level;
        this.origin = origin;
        this.box = box;
        this.actor = actor;
        this.definition = definition;
        this.result = result;
        this.message = message == null ? "" : message;
    }

    /** The level the reset ran in. */
    public ServerLevel getLevel() {
        return level;
    }

    /** The block the reset was triggered at. */
    public BlockPos getOrigin() {
        return origin;
    }

    /** The area the reset covered. */
    public AABB getBox() {
        return box;
    }

    /** The acting player, or null for a server side reset. */
    public ServerPlayer getActor() {
        return actor;
    }

    /** True when a player was part of this reset. */
    public boolean hasActor() {
        return actor != null;
    }

    /** The definition that ran. */
    public ResetDefinition getDefinition() {
        return definition;
    }

    /**
     * The outcome of the reset, never null.
     *
     * <p>Carries its own name on purpose: {@link Event#getResult()} already
     * exists and returns {@link Event.Result}, so an override returning
     * {@link ResetResult} cannot compile.</p>
     */
    public ResetResult getResetResult() {
        return result;
    }

    /** True when every action of the reset succeeded. */
    public boolean isSuccess() {
        return result != null && result.success();
    }

    /** The action type ids that were executed. */
    public List<String> getActionsRun() {
        return result == null ? List.of() : result.actionsRun();
    }

    /** The first error of the reset, or an empty string. */
    public String getError() {
        return result == null ? "" : result.error();
    }

    /** The message key that was handed to the player. */
    public String getMessage() {
        return message;
    }

    /** Replaces the message key carried by this event. */
    public void setMessage(String message) {
        this.message = message == null ? "" : message;
    }

    @Override
    public String toString() {
        return "ResetPostEvent[definition=" + (definition == null ? "-" : definition.id())
                + ", success=" + isSuccess() + ", actions=" + getActionsRun() + "]";
    }
}
