package com.structurereset.kubejs;

import dev.latvian.mods.kubejs.KubeJSPlugin;
import dev.latvian.mods.kubejs.script.BindingsEvent;

/**
 * KubeJS integration for Structure Reset (soft dependency).
 *
 * <p>KubeJS 2001.x discovers plugins by reading the file named by
 * {@code kubejs.plugins.txt} (measured from the real jar, see docs/KUBEJS.md);
 * this workspace therefore ships
 * {@code src/main/resources/kubejs.plugins.txt} containing this class name.</p>
 *
 * <p>{@link dev.latvian.mods.kubejs.KubeJSPlugin} is a <em>class</em> in KubeJS
 * 2001.x, not an interface, and its hooks are plain {@code public void}
 * methods.  The ones used here were read straight out of
 * {@code dev/latvian/mods/kubejs/KubeJSPlugin.class} with javap:</p>
 *
 * <pre>
 * public void init();
 * public void registerEvents();
 * public void registerBindings(dev.latvian.mods.kubejs.script.BindingsEvent);
 * </pre>
 */
public class StructureResetKubeJSPlugin extends KubeJSPlugin {

    /** Script scope name of the global {@code StructureReset} object. */
    public static final String BINDING = "StructureReset";

    /** Script scope name of the {@code StructureResetEvents} event group. */
    public static final String EVENT_GROUP = "StructureResetEvents";

    @Override
    public void init() {
        // Wire this mod's listeners into StructureResetApi exactly once.
        StructureResetEvents.init();
    }

    @Override
    public void registerEvents() {
        // EventGroup.register() puts the group into KubeJS' group registry, which
        // is what makes StructureResetEvents.onPreReset(fn) callable in scripts.
        StructureResetEvents.register();
    }

    @Override
    public void registerBindings(BindingsEvent event) {
        // A Class binding exposes its public static methods to scripts.
        event.add(BINDING, StructureResetBindings.class);
    }
}
