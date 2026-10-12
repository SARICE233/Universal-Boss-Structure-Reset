package com.structurereset.core.event;

import com.structurereset.api.ResetDefinition;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.phys.AABB;
import net.minecraftforge.eventbus.api.Cancelable;
import net.minecraftforge.eventbus.api.Event;

/**
 * Posted before a structure reset runs.
 *
 * <p>The event is cancelable: cancelling it aborts the reset and the message
 * carried by {@link #getMessage()} is reported instead. A listener may also
 * call {@link #setMessage(String)} to replace the message the player sees.</p>
 */
@Cancelable
public class ResetPreEvent extends Event {

    private final ServerLevel level;
    private final BlockPos origin;
    private final AABB box;
    private final ServerPlayer actor;
    private final ResetDefinition definition;
    private String message;

    public ResetPreEvent(ServerLevel level, BlockPos origin, AABB box, ServerPlayer actor,
                         ResetDefinition definition, String message) {
        this.level = level;
        this.origin = origin;
        this.box = box;
        this.actor = actor;
        this.definition = definition;
        this.message = message == null ? "" : message;
    }

    /** The level the reset would run in. */
    public ServerLevel getLevel() {
        return level;
    }

    /** The block the reset was triggered at. */
    public BlockPos getOrigin() {
        return origin;
    }

    /** The area the reset would cover. */
    public AABB getBox() {
        return box;
    }

    /** The acting player, or null for a server side reset. */
    public ServerPlayer getActor() {
        return actor;
    }

    /** True when a player is part of this reset. */
    public boolean hasActor() {
        return actor != null;
    }

    /** The definition that would run. */
    public ResetDefinition getDefinition() {
        return definition;
    }

    /** The message key handed to the player, or the cancellation reason. */
    public String getMessage() {
        return message;
    }

    /** Replaces the message key handed to the player. */
    public void setMessage(String message) {
        this.message = message == null ? "" : message;
    }

    @Override
    public String toString() {
        return "ResetPreEvent[definition=" + (definition == null ? "-" : definition.id())
                + ", origin=" + origin + ", canceled=" + isCanceled() + "]";
    }
}
