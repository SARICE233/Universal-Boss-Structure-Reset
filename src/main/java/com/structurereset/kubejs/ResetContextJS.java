package com.structurereset.kubejs;

import com.structurereset.api.ResetDefinition;
import com.structurereset.api.StructureRef;
import com.structurereset.core.ResetContext;
import com.structurereset.core.ResetResult;
import com.structurereset.core.event.ResetPostEvent;
import com.structurereset.core.event.ResetPreEvent;

import com.google.gson.JsonObject;

import java.util.Map;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.phys.AABB;

/**
 * Read-only script view of a {@link ResetContext}.
 *
 * <p>It is what a custom action registered with
 * {@code StructureReset.registerAction(type, ctx => { ... })} receives, and it
 * is also reachable from {@link ResetEventJS#getContext()}.</p>
 *
 * <p>Three sources are supported: a live {@link ResetContext} (custom actions),
 * a {@link ResetPreEvent} and a {@link ResetPostEvent}; only the first exposes
 * the action parameters and placeholder variables.</p>
 */
public class ResetContextJS {

    private final ResetContext context;
    private final ResetPreEvent preEvent;
    private final ResetPostEvent postEvent;
    private final ResetResult result;

    private String message;
    private boolean cancelled;

    /** View over a live action context. */
    public ResetContextJS(ResetContext context, ResetResult result) {
        this(context, null, null, result);
    }

    private ResetContextJS(ResetContext context, ResetPreEvent preEvent,
                           ResetPostEvent postEvent, ResetResult result) {
        this.context = context;
        this.preEvent = preEvent;
        this.postEvent = postEvent;
        this.result = result;
    }

    /** View over a pre-reset event. */
    public static ResetContextJS of(ResetPreEvent event, ResetResult result) {
        return new ResetContextJS(null, event, null, result);
    }

    /** View over a post-reset event. */
    public static ResetContextJS of(ResetPostEvent event, ResetResult result) {
        return new ResetContextJS(null, null, event, result);
    }

    // ------------------------------------------------------------- read-only

    /** @return the level of the reset, or {@code null}. */
    public ServerLevel getLevel() {
        if (context != null) {
            return context.getLevel();
        }
        if (preEvent != null) {
            return preEvent.getLevel();
        }
        return postEvent == null ? null : postEvent.getLevel();
    }

    /** @return the triggering player, or {@code null}. */
    public ServerPlayer getPlayer() {
        if (context != null) {
            return context.getActor();
        }
        if (preEvent != null) {
            return preEvent.getActor();
        }
        return postEvent == null ? null : postEvent.getActor();
    }

    /** @return the primary structure reference of the definition. */
    public String getStructure() {
        ResetDefinition definition = getDefinition();
        if (definition == null || definition.structures().isEmpty()) {
            return null;
        }
        StructureRef first = definition.structures().get(0);
        return first.raw();
    }

    /** @return the definition being executed, or {@code null}. */
    public ResetDefinition getDefinition() {
        if (context != null) {
            return context.getDefinition();
        }
        if (preEvent != null) {
            return preEvent.getDefinition();
        }
        return postEvent == null ? null : postEvent.getDefinition();
    }

    /** @return the trigger origin, or {@code null}. */
    public BlockPos getOrigin() {
        if (context != null) {
            return context.getOrigin();
        }
        if (preEvent != null) {
            return preEvent.getOrigin();
        }
        return postEvent == null ? null : postEvent.getOrigin();
    }

    /** @return the affected box, or {@code null}. */
    public AABB getBox() {
        if (context != null) {
            return context.getBox();
        }
        if (preEvent != null) {
            return preEvent.getBox();
        }
        return postEvent == null ? null : postEvent.getBox();
    }

    /** @return true when the action this context belongs to succeeded. */
    public boolean isSuccess() {
        return result != null && result.success();
    }

    /** @return how many actions ran, or {@code 0}. */
    public int getActionsRun() {
        return result == null ? 0 : result.actionCount();
    }

    /** @return the ids of the actions that ran (never null). */
    public java.util.List<String> getActionIds() {
        return result == null ? java.util.List.of() : result.actionsRun();
    }

    /** @return the parameters of the action currently running (never null). */
    public JsonObject getParams() {
        return context == null ? new JsonObject() : context.getParams();
    }

    /** @return the placeholder variables (x/y/z/player/structure/...). */
    public Map<String, String> getVars() {
        return context == null ? Map.of() : context.vars();
    }

    /** @return the result of the action, or {@code null}. */
    public ResetResult getResult() {
        return result;
    }

    /** @return the live core context, or {@code null} for event-backed views. */
    public ResetContext getCoreContext() {
        return context;
    }

    // ---------------------------------------------------------------- control

    /** @return the message this context wants to report. */
    public String getMessage() {
        return message;
    }

    /** Sets the message reported by the running action. */
    public void setMessage(String text) {
        this.message = text;
    }

    /**
     * Marks the running action as failed.
     *
     * <p>Unlike {@link ResetEventJS#cancel()} this raises no KubeJS
     * {@code EventExit}: it only makes the adapted {@code ResetActionType}
     * return {@code success = false}.</p>
     */
    public void cancel() {
        this.cancelled = true;
    }

    /** @return true when {@link #cancel()} was called. */
    public boolean isCancelled() {
        return cancelled;
    }

    @Override
    public String toString() {
        ResetDefinition definition = getDefinition();
        return "ResetContextJS[" + (definition == null ? "?" : definition.id()) + "]";
    }
}
