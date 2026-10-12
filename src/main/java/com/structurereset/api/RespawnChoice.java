package com.structurereset.api;

/**
 * The outcome of the respawn degradation chain.
 *
 * @param point the chosen position
 * @param branch the branch that produced it
 */
public record RespawnChoice(RespawnPoint point, RespawnBranch branch) {
}
