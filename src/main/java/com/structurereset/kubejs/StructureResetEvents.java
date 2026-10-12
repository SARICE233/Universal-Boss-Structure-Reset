package com.structurereset.kubejs;

import com.structurereset.core.StructureResetApi;
import com.structurereset.core.event.ResetPostEvent;
import com.structurereset.core.event.ResetPreEvent;

import dev.latvian.mods.kubejs.event.EventGroup;
import dev.latvian.mods.kubejs.event.EventHandler;

/**
 * The {@code StructureResetEvents} event group that KubeJS scripts see.
 *
 * <p>KubeJS discovers an event group through {@link EventGroup#register()}: the
 * group is stored in a static registry and KubeJS then exposes every handler of
 * the group to scripts, which is why script authors simply write
 * {@code StructureResetEvents.onPreReset(fn)}.  The two handlers are declared as
 * {@link EventHandler} fields, exactly like KubeJS' own
 * {@code ServerEvents.recipes} or {@code ItemEvents.modification}.</p>
 *
 * <p>Both handlers are {@code server} events: pre/post reset only ever run on
 * the logical server.</p>
 */
public final class StructureResetEvents {

    /** The script-visible name of this group. */
    public static final String NAME = "StructureResetEvents";

    /** Registered by {@link StructureResetKubeJSPlugin#registerEvents()}. */
    public static final EventGroup GROUP = EventGroup.of(NAME);

    /** {@code StructureResetEvents.onPreReset(event => { ... })} */
    public static final EventHandler ON_PRE_RESET =
            GROUP.server("onPreReset", () -> ResetEventJS.class);

    /** {@code StructureResetEvents.onPostReset(event => { ... })} */
    public static final EventHandler ON_POST_RESET =
            GROUP.server("onPostReset", () -> ResetEventJS.class);

    private static boolean wired;

    private StructureResetEvents() {
    }

    /** Makes the group known to KubeJS. Idempotent. */
    public static void register() {
        GROUP.register();
    }

    /**
     * Bridges this mod's own listeners to the KubeJS event group.
     *
     * <p>Called once from {@link StructureResetKubeJSPlugin#init()}; a script
     * reload does not add a second listener.</p>
     */
    public static synchronized void init() {
        if (wired) {
            return;
        }
        wired = true;
        StructureResetApi.addPreListener(StructureResetEvents::handlePre);
        StructureResetApi.addPostListener(StructureResetEvents::handlePost);
    }

    private static void handlePre(ResetPreEvent event) {
        if (!ON_PRE_RESET.hasListeners()) {
            return;
        }
        ON_PRE_RESET.post(new ResetEventJS(event));
    }

    private static void handlePost(ResetPostEvent event) {
        if (!ON_POST_RESET.hasListeners()) {
            return;
        }
        ON_POST_RESET.post(new ResetEventJS(event));
    }

    /** @return true once {@link #init()} wired the listeners. */
    public static boolean isWired() {
        return wired;
    }
}
