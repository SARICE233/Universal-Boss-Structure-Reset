package com.structurereset.kubejs;

import com.structurereset.api.ResetDefinition;
import com.structurereset.api.StructureRef;
import com.structurereset.core.ResetResult;
import com.structurereset.core.event.ResetPostEvent;
import com.structurereset.core.event.ResetPreEvent;

import dev.latvian.mods.kubejs.event.EventExit;
import dev.latvian.mods.kubejs.event.EventJS;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.phys.AABB;

/**
 * The event object handed to {@code StructureResetEvents.onPreReset} and
 * {@code StructureResetEvents.onPostReset} handlers.
 *
 * <p>Exactly one of the two wrapped core events is non-null: a pre event can
 * still be cancelled, a post event only reports what happened.</p>
 */
public class ResetEventJS extends EventJS {

    private final ResetPreEvent preEvent;
    private final ResetPostEvent postEvent;
    private final ResetResult result;

    private String message;
    private boolean cancelled;

    /** Wraps a pre-reset event. */
    public ResetEventJS(ResetPreEvent event) {
        this(event, null);
    }

    /** Wraps a pre-reset event plus the result of an action, when one exists. */
    public ResetEventJS(ResetPreEvent event, ResetResult result) {
        this.preEvent = event;
        this.postEvent = null;
        this.result = result;
        this.message = event == null ? null : event.getMessage();
    }

    /** Wraps a post-reset event; the reset result is taken from the event. */
    public ResetEventJS(ResetPostEvent event) {
        this(event, event == null ? null : event.getResetResult());
    }

    /**
     * Wraps a post-reset event plus the result of the reset.
     *
     * <p>Without a {@link ResetResult} the {@link #isSuccess()} and
     * {@link #getActionsRun()} getters cannot be answered and report
     * {@code false} / {@code 0}.</p>
     */
    public ResetEventJS(ResetPostEvent event, ResetResult result) {
        this.preEvent = null;
        this.postEvent = event;
        this.result = result;
        this.message = event == null ? null : event.getMessage();
    }

    // ------------------------------------------------------------- read-only

    /** @return the level the reset happened in, or {@code null}. */
    public ServerLevel getLevel() {
        if (preEvent != null) {
            return preEvent.getLevel();
        }
        return postEvent == null ? null : postEvent.getLevel();
    }

    /** @return the triggering player, or {@code null} (non-player trigger). */
    public ServerPlayer getPlayer() {
        if (preEvent != null) {
            return preEvent.getActor();
        }
        return postEvent == null ? null : postEvent.getActor();
    }

    /**
     * @return the structure reference the definition matched on.
     *
     * <p>The core event carries the definition rather than the concrete matched
     * structure, so the definition's first reference is reported; use
     * {@link #getDefinition()} when a definition lists several references.</p>
     */
    public String getStructure() {
        ResetDefinition definition = getDefinition();
        if (definition == null || definition.structures().isEmpty()) {
            return null;
        }
        StructureRef first = definition.structures().get(0);
        return first.raw();
    }

    /** @return the definition that fired, or {@code null}. */
    public ResetDefinition getDefinition() {
        if (preEvent != null) {
            return preEvent.getDefinition();
        }
        return postEvent == null ? null : postEvent.getDefinition();
    }

    /** @return the trigger origin, or {@code null}. */
    public BlockPos getOrigin() {
        if (preEvent != null) {
            return preEvent.getOrigin();
        }
        return postEvent == null ? null : postEvent.getOrigin();
    }

    /** @return the affected box, or {@code null} when none was computed. */
    public AABB getBox() {
        if (preEvent != null) {
            return preEvent.getBox();
        }
        return postEvent == null ? null : postEvent.getBox();
    }

    /** @return true when the reset finished successfully. */
    public boolean isSuccess() {
        if (result != null) {
            return result.success();
        }
        return postEvent != null && !cancelled;
    }

    /** @return how many actions ran, or {@code 0} when no result is known. */
    public int getActionsRun() {
        return result == null ? 0 : result.actionCount();
    }

    /** @return the ids of the actions that ran (never null). */
    public java.util.List<String> getActionIds() {
        return result == null ? java.util.List.of() : result.actionsRun();
    }

    /** @return the current message (may be {@code null}). */
    public String getMessage() {
        return message;
    }

    /** @return true when this reset was already cancelled. */
    public boolean isCancelled() {
        return cancelled || (preEvent != null && preEvent.isCanceled());
    }

    /** @return true for pre-reset events. */
    public boolean isPreReset() {
        return preEvent != null;
    }

    /** @return true for post-reset events. */
    public boolean isPostReset() {
        return postEvent != null;
    }

    /** @return the result of the reset, or {@code null}. */
    public ResetResult getResult() {
        return result;
    }

    /** @return the wrapped pre event, or {@code null}. */
    public ResetPreEvent getPreEvent() {
        return preEvent;
    }

    /** @return the wrapped post event, or {@code null}. */
    public ResetPostEvent getPostEvent() {
        return postEvent;
    }

    // ---------------------------------------------------------------- control

    /** Sets the message shown to the player and forwarded to the core event. */
    public void setMessage(String text) {
        this.message = text;
        if (preEvent != null) {
            preEvent.setMessage(text);
        } else if (postEvent != null) {
            postEvent.setMessage(text);
        }
    }

    /**
     * Cancels the reset.
     *
     * <p>Overrides {@link EventJS#cancel()} so KubeJS' control flow and this
     * mod's cancellation stay in sync: the wrapped pre event is marked
     * cancelled, then the usual KubeJS {@link EventExit} is raised (which is how
     * KubeJS aborts the running script callback).</p>
     */
    @Override
    public Object cancel() throws EventExit {
        this.cancelled = true;
        if (preEvent != null) {
            preEvent.setCanceled(true);
        }
        return super.cancel();
    }

    /** @return a read-only script view of the same reset. */
    public ResetContextJS getContext() {
        if (preEvent != null) {
            return ResetContextJS.of(preEvent, result);
        }
        return ResetContextJS.of(postEvent, result);
    }

    @Override
    public String toString() {
        ResetDefinition definition = getDefinition();
        return "ResetEventJS[" + (definition == null ? "?" : definition.id())
                + (isPreReset() ? ", pre]" : ", post]");
    }
}
