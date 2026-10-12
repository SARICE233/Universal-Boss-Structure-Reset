package com.structurereset.core;

/**
 * One kind of reset action, for example {@code structurereset:respawn_boss}.
 *
 * <p>Implementations are registered through
 * {@link StructureResetApi#registerActionType(net.minecraft.resources.ResourceLocation, ResetActionType)}
 * and are looked up by the action {@code type} string of a datapack definition.
 * A single failure must be reported as a failed {@link ResetResult} instead of
 * an exception: the engine records it and keeps running the remaining actions.</p>
 */
public interface ResetActionType {

    /** The namespaced id of this action type, for example {@code structurereset:respawn_boss}. */
    String id();

    /** Runs this action once. Must never return null. */
    ResetResult run(ResetContext ctx);
}
